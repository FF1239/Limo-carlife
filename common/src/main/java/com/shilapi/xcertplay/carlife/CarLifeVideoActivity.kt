package com.shilapi.xcertplay.carlife

import android.app.Activity
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.diplay.carlife.core.CarLife
import com.diplay.carlife.core.CarLifeMessages
import com.shilapi.xcertplay.AndroidAmbientLight
import com.shilapi.xcertplay.host.R
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The complete projection screen: the phone canvas fills the whole unit;
 * driving info floats in glass capsules (turn top-left, speed top-right,
 * control column on the right edge) and a thin media bar docks at the bottom.
 * Mode switch asks the phone to open its music app or navigation via
 * LAUNCH_MODE, so the projection itself becomes that app.
 */
class CarLifeVideoActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var engine: CarLifeEngine? = null
    private lateinit var textureView: TextureView
    private lateinit var statusOverlay: TextView
    private lateinit var rootLayout: FrameLayout
    private lateinit var settingsPanel: CarLifeSettingsPanel
    private val picture = CarLifePicture.Settings()
    private var phoneWidth = CarLife.DEFAULT_VIDEO_WIDTH
    private var phoneHeight = CarLife.DEFAULT_VIDEO_HEIGHT

    // turn card (draggable overlay; survives phone app switches)
    private lateinit var turnCard: CarLifeTurnCardView
    private var turnX = 4
    private var turnY = 8
    private var turnDragStartX = 0f
    private var turnDragStartY = 0f
    private var turnOriginX = 0f
    private var turnOriginY = 0f

    // speed capsule
    private lateinit var speedText: TextView
    private lateinit var gearBadge: TextView
    private lateinit var speedSource: TextView

    // control column
    private lateinit var btnModeLabel: TextView
    private lateinit var btnPauseLabel: TextView
    private var videoPaused = false
    private var modeIsMusic = false

    // media bar + expanded panel
    private lateinit var mediaCover: ImageView
    private lateinit var mediaTitle: TextView
    private lateinit var mediaArtist: TextView
    private lateinit var mediaProgress: android.widget.ProgressBar
    private lateinit var mediaPlayLabel: TextView
    private lateinit var mediaExpanded: View
    private lateinit var expCover: ImageView
    private lateinit var expTitle: TextView
    private lateinit var expArtist: TextView
    private lateinit var expProgress: android.widget.ProgressBar
    private lateinit var expPlayLabel: TextView
    private var mediaPlaying = false
    private var lastMediaKey = ""

    private val hideStatus = Runnable { statusOverlay.visibility = View.GONE }

    /**
     * Demo vehicle source while no real feed exists; suppressed while the
     * phone's GPS feed is fresh (see [onCarGps]).
     */
    private var demoSpeed = 0
    private var lastGpsAt = 0L
    private var phoneKmh = -1

    /** Head unit's own GNSS (preferred when present) vs the phone's CAR_GPS feed. */
    private var unitGps: UnitGpsSpeedSource? = null
    private var unitKmh = -1
    private var unitGpsAt = 0L

    /** Auto day/night via the head unit's light sensor (SYSTEM mode only). */
    private var ambientLight: AndroidAmbientLight? = null
    private var ambientNight = false

    private val demoTick = object : Runnable {
        override fun run() {
            val gpsFresh = System.currentTimeMillis() - maxOf(lastGpsAt, unitGpsAt) < GPS_FRESH_MS
            if (gpsFresh) {
                main.postDelayed(this, 900)
                return
            }
            demoSpeed = (demoSpeed + Random.nextInt(-9, 12)).coerceIn(0, 132)
            renderSpeed(demoSpeed, "DEMO")
            // Demo navigation so the turn card can be previewed without a phone.
            demoDistance -= 70
            if (demoDistance < 40) {
                demoDistance = 900
                demoRoad = demoRoads[demoRoadIndex++ % demoRoads.size]
            }
            turnCard.setGuidance(demoRoad, demoDistance)
            main.postDelayed(this, 900)
        }
    }
    private var demoDistance = 320
    private var demoRoadIndex = 0
    private val demoRoads = arrayOf("中山北路", "体育场路", "环城北路", "中河高架")
    private var demoRoad = demoRoads[0]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CarbonTheme.load(this)
        installCrashCapture()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Projection screen stays on while the session runs.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        buildUi()

        if (CarLifePersistence.get(this).dayNightMode() == CarLifePersistence.DAY_NIGHT_SYSTEM) {
            val light = AndroidAmbientLight(this)
            if (light.start { lux -> main.post { applyAmbientLux(lux) } }) {
                ambientLight = light
            }
        }

        // Unit-side GNSS speed when the unit has a GPS receiver and the user
        // granted the location permission (the phone CAR_GPS feed is the fallback).
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            val source = UnitGpsSpeedSource(this) { kmh ->
                unitKmh = kmh
                unitGpsAt = System.currentTimeMillis()
                main.post { renderSpeed(unitKmh, "GPS") }
            }
            if (source.start()) unitGps = source
        }

        if (intent.getStringExtra("mode") == "wireless") {
            main.post { startWirelessScan() }
        }
        showStatus(getString(R.string.carlife_connecting))
        // Demo mode previews the instruments and turn card before any phone
        // connects; the tick yields to real GPS/NAVI data when they arrive.
        if (CarLifePersistence.get(this).demoData()) {
            main.post(demoTick)
        }

        // FGS microphone type needs RECORD_AUDIO; the unit GNSS speed source
        // needs fine location. Request both together, degrade gracefully.
        val missing = mutableListOf(
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        ).filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 1)
        }
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        rootLayout = root

        // ---- full-screen projection ----
        textureView = TextureView(this).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    st: SurfaceTexture,
                    w: Int,
                    h: Int,
                ) {
                    st.setDefaultBufferSize(phoneWidth, phoneHeight)
                    applyVideoTransform()
                    startEngine(Surface(st))
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                    applyVideoTransform()
                }

                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    engine?.stop()
                    engine = null
                    return true
                }

                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                    // First painted frame: hide the status toast shortly after.
                    main.removeCallbacks(hideStatus)
                    main.postDelayed(hideStatus, 1200)
                }
            }
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyVideoTransform() }
        }
        root.addView(textureView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // ---- status overlay ----
        statusOverlay = TextView(this).apply {
            setTextColor(CarbonTheme.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            letterSpacing = 0.06f
            gravity = Gravity.CENTER
            background = glass(20f)
            setPadding(dp(26), dp(14), dp(26), dp(14))
        }
        root.addView(statusOverlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        // ---- turn card (top-left by default; draggable, position persists) ----
        turnCard = CarLifeTurnCardView(this)
        root.addView(turnCard, object : FrameLayout.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.34f).toInt(),
            (resources.displayMetrics.widthPixels * 0.34f * 0.21f).toInt(),
        ) {})
        turnCard.post { applyTurnCardPosition() }
        turnCard.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    turnDragStartX = ev.rawX; turnDragStartY = ev.rawY
                    turnOriginX = (v.layoutParams as FrameLayout.LayoutParams).leftMargin.toFloat()
                    turnOriginY = (v.layoutParams as FrameLayout.LayoutParams).topMargin.toFloat()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val p = v.layoutParams as FrameLayout.LayoutParams
                    p.leftMargin = (turnOriginX + ev.rawX - turnDragStartX).toInt()
                        .coerceIn(0, root.width - v.width)
                    p.topMargin = (turnOriginY + ev.rawY - turnDragStartY).toInt()
                        .coerceIn(0, root.height - v.height)
                    v.layoutParams = p
                    true
                }

                MotionEvent.ACTION_UP -> {
                    turnX = ((v.layoutParams as FrameLayout.LayoutParams).leftMargin * 100f
                        / (root.width - v.width).coerceAtLeast(1)).toInt().coerceIn(0, 100) / 2 * 2
                    turnY = ((v.layoutParams as FrameLayout.LayoutParams).topMargin * 100f
                        / (root.height - v.height).coerceAtLeast(1)).toInt().coerceIn(0, 100) / 2 * 2
                    CarLifePersistence.get(this).saveTurnCardPosition(turnX, turnY)
                    true
                }

                else -> false
            }
        }

        // ---- speed capsule (top-right) ----
        val speed = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glass(30f)
            setPadding(dp(20), dp(8), dp(18), dp(8))
            speedText = TextView(this@CarLifeVideoActivity).apply {
                text = "0"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
                typeface = Typeface.DEFAULT_BOLD
            }
            addView(speedText)
            val col = LinearLayout(this@CarLifeVideoActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, 0, 0)
            }
            col.addView(TextView(this@CarLifeVideoActivity).apply {
                text = "km/h"
                setTextColor(0x8CFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.MONOSPACE
            })
            gearBadge = TextView(this@CarLifeVideoActivity).apply {
                text = "P"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    cornerRadius = dp(6f)
                    setColor(CarbonTheme.RED)
                }
                setPadding(dp(10), dp(1), dp(10), dp(2))
            }
            col.addView(gearBadge)
            speedSource = TextView(this@CarLifeVideoActivity).apply {
                text = ""
                setTextColor(0x66FFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(4), 0, 0)
            }
            col.addView(speedSource)
            addView(col)
        }
        root.addView(speed, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END,
        ).apply {
            val m = dp(16)
            setMargins(m, m, m, m)
        })

        // ---- control column (right edge) ----
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        btnModeLabel = TextView(this).apply {
            text = "♪"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
        }
        column.addView(roundButton(btnModeLabel) { toggleAppMode() })
        column.addView(space())
        btnPauseLabel = TextView(this).apply {
            text = "❚❚"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
        }
        column.addView(roundButton(btnPauseLabel) {
            videoPaused = !videoPaused
            engine?.setVideoPaused(videoPaused)
            btnPauseLabel.text = if (videoPaused) "►" else "❚❚"
        })
        column.addView(space())
        column.addView(roundButton(TextView(this).apply {
            text = "◎"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
        }) { takeScreenshot() })
        column.addView(space())
        column.addView(roundButton(TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
        }) {
            engine?.stop()
            finish()
        })
        column.addView(space())
        column.addView(roundButton(TextView(this).apply {
            text = "≡"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
        }) {
            setVisibleAnimated(settingsPanel, settingsPanel.visibility != View.VISIBLE)
        })
        root.addView(column, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.CENTER_VERTICAL,
        ).apply {
            val m = dp(16)
            setMargins(0, m, m, m)
        })

        // ---- thin media bar (bottom) ----
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(0xF20C0D0F.toInt()) }
            setPadding(dp(14), dp(8), dp(14), dp(8))
            mediaCover = ImageView(this@CarLifeVideoActivity).apply {
                background = GradientDrawable().apply { setColor(0x22FFFFFF.toInt()) }
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(8f))
                    }
                }
            }
            addView(mediaCover, LinearLayout.LayoutParams(dp(44), dp(44)))
            val textCol = LinearLayout(this@CarLifeVideoActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
            }
            mediaTitle = TextView(this@CarLifeVideoActivity).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
            }
            mediaArtist = TextView(this@CarLifeVideoActivity).apply {
                text = getString(R.string.widget_no_media)
                setTextColor(0x8CFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                maxLines = 1
                setPadding(0, dp(2), 0, 0)
            }
            mediaProgress = android.widget.ProgressBar(
                this@CarLifeVideoActivity, null, android.R.attr.progressBarStyleHorizontal,
            ).apply {
                max = 100
                progress = 0
                progressTintList = android.content.res.ColorStateList.valueOf(CarbonTheme.RED)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x24FFFFFF)
            }
            textCol.addView(mediaTitle)
            textCol.addView(mediaArtist)
            textCol.addView(mediaProgress, LinearLayout.LayoutParams(dp(220), dp(5)).apply {
                topMargin = dp(5)
            })
            addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(roundButton(TextView(this@CarLifeVideoActivity).apply {
                text = "❮"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER
            }) { engine?.sendHardKey(CarLife.HardKey.SELECTOR_PREVIOUS) })
            addView(space(8))
            mediaPlayLabel = TextView(this@CarLifeVideoActivity).apply {
                text = "►"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    cornerRadius = dp(22f)
                    setColor(CarbonTheme.RED)
                }
            }
            addView(FrameLayout(this@CarLifeVideoActivity).apply {
                addView(mediaPlayLabel, FrameLayout.LayoutParams(dp(44), dp(44)))
                isClickable = true
                setOnClickListener { toggleMediaPlay() }
            })
            addView(space(8))
            addView(roundButton(TextView(this@CarLifeVideoActivity).apply {
                text = "❯"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER
            }) { engine?.sendHardKey(CarLife.HardKey.SELECTOR_NEXT) })
            addView(space(10))
            addView(roundButton(TextView(this@CarLifeVideoActivity).apply {
                text = "⌃"
                setTextColor(0xB3FFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER
            }) { toggleMediaPanel() })
        }
        root.addView(bar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(64), Gravity.BOTTOM))

        // ---- expanded media panel (hidden by default) ----
        mediaExpanded = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(0xF60C0D0F.toInt())
                setStroke(dp(1), CarbonTheme.HAIRLINE)
            }
            setPadding(dp(24), dp(18), dp(24), dp(18))
            expCover = ImageView(this@CarLifeVideoActivity).apply {
                background = GradientDrawable().apply { setColor(0x22FFFFFF.toInt()) }
            }
            addView(expCover, LinearLayout.LayoutParams(dp(96), dp(96)))
            val col = LinearLayout(this@CarLifeVideoActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), 0, 0, 0)
            }
            expTitle = TextView(this@CarLifeVideoActivity).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
            }
            expArtist = TextView(this@CarLifeVideoActivity).apply {
                setTextColor(0x8CFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                maxLines = 1
                setPadding(0, dp(4), 0, 0)
            }
            expProgress = android.widget.ProgressBar(
                this@CarLifeVideoActivity, null, android.R.attr.progressBarStyleHorizontal,
            ).apply {
                max = 100
                progress = 0
                progressTintList = android.content.res.ColorStateList.valueOf(CarbonTheme.RED)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x24FFFFFF)
            }
            col.addView(expTitle)
            col.addView(expArtist)
            col.addView(expProgress, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(7)).apply { topMargin = dp(12) })
            addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val ctl = LinearLayout(this@CarLifeVideoActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            ctl.addView(roundButton(TextView(this@CarLifeVideoActivity).apply {
                text = "❮"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                gravity = Gravity.CENTER
            }) { engine?.sendHardKey(CarLife.HardKey.SELECTOR_PREVIOUS) })
            ctl.addView(space(12))
            expPlayLabel = TextView(this@CarLifeVideoActivity).apply {
                text = "►"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    cornerRadius = dp(28f)
                    setColor(CarbonTheme.RED)
                }
            }
            ctl.addView(FrameLayout(this@CarLifeVideoActivity).apply {
                addView(expPlayLabel, FrameLayout.LayoutParams(dp(56), dp(56)))
                isClickable = true
                setOnClickListener { toggleMediaPlay() }
            })
            ctl.addView(space(12))
            ctl.addView(roundButton(TextView(this@CarLifeVideoActivity).apply {
                text = "❯"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                gravity = Gravity.CENTER
            }) { engine?.sendHardKey(CarLife.HardKey.SELECTOR_NEXT) })
            ctl.addView(space(12))
            ctl.addView(roundButton(TextView(this@CarLifeVideoActivity).apply {
                text = "⌄"
                setTextColor(0xB3FFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                gravity = Gravity.CENTER
            }) { toggleMediaPanel() })
            addView(ctl)
            visibility = View.GONE
        }
        root.addView(mediaExpanded, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ))

        // ---- settings panel ----
        settingsPanel = CarLifeSettingsPanel(
            this,
            CarLifePersistence.get(this),
            onApply = { settings ->
                picture.brightness = settings.brightness
                picture.contrast = settings.contrast
                picture.saturation = settings.saturation
                picture.warmth = settings.warmth
                picture.night = settings.night
                applyPicture()
            },
            onResolution = { w, h, _ ->
                phoneWidth = w
                phoneHeight = h
                // Re-create the engine with the new negotiation canvas.
                engine?.stop()
                engine = null
                textureView.surfaceTexture?.setDefaultBufferSize(w, h)
                startEngine(currentSurface())
            },
        ).apply { visibility = View.GONE }
        root.addView(settingsPanel, FrameLayout.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.62f).toInt().coerceAtLeast(dp(360)),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))

        setContentView(root)
    }

    // ---- ui helpers ----

    private fun glass(radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(radiusDp)
        setColor(0x8C101216.toInt())
        setStroke(dp(1), 0x2EFFFFFF)
    }

    private fun roundButton(label: TextView, onClick: () -> Unit): View =
        FrameLayout(this).apply {
            background = glass(26f)
            addView(label, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun space(w: Int = 10): View = View(this).also {
        it.layoutParams = LinearLayout.LayoutParams(dp(w), 1)
    }

    private fun toggleAppMode() {
        modeIsMusic = !modeIsMusic
        // The turn card stays visible in BOTH modes: navigation data keeps
        // flowing from the phone while music is in the foreground (0.2.11
        // lesson - guidance must not disappear with the map).
        if (modeIsMusic) {
            engine?.sendLaunchMode(CarLife.Cmd.LAUNCH_MODE_MUSIC)
            btnModeLabel.text = "➤"
        } else {
            engine?.sendLaunchMode(CarLife.Cmd.LAUNCH_MODE_MAP)
            btnModeLabel.text = "♪"
        }
    }

    /** Re-applies the persisted card position within the current canvas. */
    private fun applyTurnCardPosition() {
        val p = turnCard.layoutParams as FrameLayout.LayoutParams
        val maxX = (rootLayout.width - turnCard.width).coerceAtLeast(1)
        val maxY = (rootLayout.height - turnCard.height).coerceAtLeast(1)
        p.leftMargin = turnX * maxX / 100
        p.topMargin = turnY * maxY / 100
        turnCard.layoutParams = p
    }

    private fun toggleMediaPlay() {
        mediaPlaying = !mediaPlaying
        engine?.sendHardKey(if (mediaPlaying) CarLife.HardKey.MEDIA_START else CarLife.HardKey.MEDIA_STOP)
        engine?.setMediaPlaying(mediaPlaying)
        val glyph = if (mediaPlaying) "❚❚" else "►"
        mediaPlayLabel.text = glyph
        expPlayLabel.text = glyph
    }

    private fun toggleMediaPanel() {
        setVisibleAnimated(mediaExpanded, mediaExpanded.visibility != View.VISIBLE)
    }

    /** DiPlay-style 300ms fade for overlay panels instead of hard visibility cuts. */
    private fun setVisibleAnimated(view: View, visible: Boolean) {
        if (visible == (view.visibility == View.VISIBLE) && view.alpha == 1f) return
        view.animate()
            .alpha(if (visible) 1f else 0f)
            .setDuration(300)
            .withStartAction { if (visible) view.visibility = View.VISIBLE }
            .withEndAction { if (!visible) view.visibility = View.GONE }
            .start()
    }

    /** Letterboxes the projection inside the full screen (no stretching). */
    private fun applyVideoTransform() {
        val vw = textureView.width
        val vh = textureView.height
        if (vw == 0 || vh == 0 || phoneWidth == 0 || phoneHeight == 0) return
        val scale = minOf(vw.toFloat() / phoneWidth, vh.toFloat() / phoneHeight)
        val matrix = Matrix()
        matrix.setScale(scale, scale)
        matrix.postTranslate((vw - phoneWidth * scale) / 2f, (vh - phoneHeight * scale) / 2f)
        textureView.setTransform(matrix)
    }

    /**
     * The stripped platform jar here has no TextureView.setPaint, so the
     * ColorMatrix goes through a hardware layer paint instead (and drops the
     * layer entirely at neutral settings).
     */
    private fun applyPicture() {
        val neutral = picture.brightness == 0 && picture.contrast == 0 &&
            picture.saturation == 0 && picture.warmth == 0 && !picture.night
        if (neutral) {
            textureView.setLayerType(View.LAYER_TYPE_NONE, null)
        } else {
            textureView.setLayerType(View.LAYER_TYPE_HARDWARE, CarLifePicture.paint(picture))
        }
    }

    /** Writes any uncaught crash into the session log before the process dies. */
    private fun installCrashCapture() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            CarLifeSessionLog.log(
                this, "CRASH",
                "${thread.name}: ${android.util.Log.getStackTraceString(throwable)}",
            )
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Hysteresis so passing shadows/tunnels do not flicker the picture. */
    private fun applyAmbientLux(lux: Float) {
        if (lux.isNaN()) return
        val night = when {
            lux > 15f -> false
            lux < 8f -> true
            else -> ambientNight
        }
        if (night != ambientNight) {
            ambientNight = night
            picture.night = night
            applyPicture()
            CarLifeSessionLog.log(this, "NIGHT", "lux=$lux night=$night")
        }
    }

    /** Renders one speed reading into the capsule with its source badge. */
    private fun renderSpeed(kmh: Int, source: String) {
        speedText.text = kmh.toString()
        gearBadge.text = if (kmh == 0) "P" else "D"
        speedSource.text = if (source.isBlank()) "" else "$source ●"
    }

    /** Saves the current projection frame into this app's external pictures dir. */
    private fun takeScreenshot() {
        val bitmap = textureView.bitmap
        if (bitmap == null) {
            Toast.makeText(this, getString(R.string.screenshot_failed), Toast.LENGTH_SHORT).show()
            return
        }
        val dir = getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES) ?: filesDir
        val file = java.io.File(dir, "carlife-${System.currentTimeMillis()}.jpg")
        try {
            java.io.FileOutputStream(file).use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, out)
            }
            Toast.makeText(this, getString(R.string.screenshot_saved, file.name), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.w(TAG, "screenshot failed", e)
            Toast.makeText(this, getString(R.string.screenshot_failed), Toast.LENGTH_SHORT).show()
        }
    }

    // ---- engine wiring ----

    private fun currentSurface(): Surface? =
        textureView.surfaceTexture?.let { Surface(it) }

    private fun startWirelessScan() {
        showStatus(getString(R.string.carlife_searching_phone))
        SubnetScanner.findPhone(
            onFound = { host ->
                main.post {
                    showStatus(getString(R.string.carlife_phone_found, host.hostAddress))
                    engine?.stop()
                    engine = null
                    startEngine(currentSurface(), CarLifeEngine.Mode.WIRELESS, host)
                }
            },
            onDone = { main.post { showStatus(getString(R.string.carlife_phone_not_found)) } },
        )
    }

    private fun promptForIp() {
        val input = android.widget.EditText(this).apply {
            setText("127.0.0.1")
            hint = "192.168.x.x / 127.0.0.1"
            setSingleLine()
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(16), dp(16), dp(16), 0)
            addView(input)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.carlife_manual_ip_button))
            .setMessage(getString(R.string.carlife_manual_ip_hint))
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val host = runCatching {
                    java.net.InetAddress.getByName(input.text.toString().trim())
                }.getOrNull() ?: return@setPositiveButton
                engine?.stop()
                engine = null
                showStatus(getString(R.string.carlife_phone_found, host.hostAddress))
                startEngine(currentSurface(), CarLifeEngine.Mode.WIRELESS, host)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startEngine(
        surface: Surface?,
        mode: CarLifeEngine.Mode = CarLifeEngine.Mode.WIRED,
        wirelessHost: java.net.InetAddress? = null,
    ) {
        if (engine != null || surface == null || !surface.isValid) return
        val persistence = CarLifePersistence.get(this)
        engine = CarLifeEngine(
            applicationContext,
            surface,
            object : CarLifeEngine.UiListener {
                override fun onEngineState(state: String, detail: String?) {
                    main.post { onState(state, detail) }
                }

                override fun onMobileInfo(info: CarLifeMessages.DeviceInfo) {
                    main.post {
                        Log.i(TAG, "phone: ${info.manufacturer} ${info.model}")
                        val name = listOf(info.manufacturer, info.model)
                            .filter { it.isNotBlank() }
                            .joinToString(" ")
                        CarLifePersistence.get(this@CarLifeVideoActivity).saveLastPhone(name)
                    }
                }

                override fun onEncoderSize(width: Int, height: Int) {
                    main.post {
                        phoneWidth = width
                        phoneHeight = height
                        persistence.saveNegotiated(width, height)
                        textureView.surfaceTexture?.setDefaultBufferSize(width, height)
                        applyVideoTransform()
                    }
                }

                override fun onMediaInfo(info: CarLifeMessages.MediaInfo) {
                    main.post {
                        // 0.2.10 lesson: phones resend identical metadata; skip
                        // identical payloads to avoid needless view churn.
                        val key = "${info.song}|${info.artist}|${info.albumArt.size}"
                        if (key == lastMediaKey) return@post
                        lastMediaKey = key
                        val cover = info.albumArt.takeIf { it.isNotEmpty() }?.let { bytes ->
                            runCatching {
                                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            }.getOrNull()
                        }
                        val song = info.song.ifBlank { getString(R.string.media_unknown_song) }
                        val artist = info.artist.ifBlank { getString(R.string.media_unknown_artist) }
                        mediaTitle.text = song
                        mediaArtist.text = artist
                        expTitle.text = song
                        expArtist.text = artist
                        if (cover != null) {
                            mediaCover.setImageBitmap(cover)
                            expCover.setImageBitmap(cover)
                        }
                    }
                }

                override fun onMediaProgress(percent: Int) {
                    main.post {
                        mediaProgress.progress = percent
                        expProgress.progress = percent
                    }
                }

                override fun onCarGps(gps: CarLifeMessages.CarGps) {
                    main.post {
                        lastGpsAt = System.currentTimeMillis()
                        // Reference app reports Location.speed * 100 (cm/s).
                        phoneKmh = (gps.speedCms * 36.0 / 1000.0).roundToInt()
                        renderSpeed(phoneKmh, "GPS")
                        CarLifeSessionLog.log(
                            this@CarLifeVideoActivity, "GPS",
                            "phone=${phoneKmh}kmh sats=${gps.satsUsed} fix=${gps.fix} head=${gps.heading}",
                        )
                    }
                }

                override fun onNaviInfo(info: CarLifeMessages.NaviNextTurnInfo) {
                    main.post {
                        // Expired or empty guidance clears; missing values stay absent.
                        if (info.roadName.isBlank() && info.remainDistance <= 0) {
                            turnCard.hideCard()
                        } else {
                            turnCard.setGuidance(info.roadName, info.remainDistance)
                        }
                    }
                }
            },
        ).also {
            it.start(mode, wirelessHost)
            // Keep the session alive under a foreground notification so the
            // head unit cannot kill it while the user switches screens.
            CarLifeSessionService.start(applicationContext)
        }
    }

    private fun onState(state: String, detail: String?) {
        Log.i(TAG, "state=$state detail=$detail")
        when {
            state.startsWith("phase:streaming") || state == "connected" -> {
                main.removeCallbacks(demoTick)
                if (CarLifePersistence.get(this).demoData()) {
                    main.post(demoTick)
                } else {
                    renderSpeed(0, "")
                }
                showStatus(getString(R.string.carlife_projecting))
            }

            state == "disconnected" || state == "closed" || state == "stopped" -> {
                main.removeCallbacks(demoTick)
                renderSpeed(0, "")
                showStatus(getString(R.string.carlife_disconnected, detail ?: ""))
            }

            state == "scanning" -> showStatus(getString(R.string.state_scanning))
            state == "wireless" || state == "wireless-connecting" ->
                showStatus(getString(R.string.state_wireless_connecting, detail ?: ""))
            state == "reconnecting" ->
                showStatus(getString(R.string.state_reconnecting, detail ?: ""))
            state == "watchdog" -> showStatus(getString(R.string.state_watchdog))
            else -> showStatus(state + (detail?.let { " — $it" } ?: ""))
        }
    }

    private fun showStatus(text: String) {
        statusOverlay.text = text
        statusOverlay.visibility = View.VISIBLE
    }

    // ---- input ----

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Paused projection: tapping the frozen frame resumes instead of
        // forwarding touches to a phone picture the user cannot see move.
        if (videoPaused && event.actionMasked == MotionEvent.ACTION_UP) {
            videoPaused = false
            engine?.setVideoPaused(false)
            btnPauseLabel.text = "❚❚"
            return true
        }
        val e = engine ?: return super.onTouchEvent(event)
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> CarLife.TOUCH_ACTION_DOWN
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> CarLife.TOUCH_ACTION_UP
            MotionEvent.ACTION_MOVE -> CarLife.TOUCH_ACTION_MOVE
            else -> return super.onTouchEvent(event)
        }
        // Report the pointer that triggered the event (or the first one while
        // dragging); multi-finger gestures still map to single taps for now.
        val sample = if (event.actionMasked == MotionEvent.ACTION_MOVE) 0 else event.actionIndex
        val px = event.getX(sample)
        val py = event.getY(sample)
        val x = (px / textureView.width.coerceAtLeast(1) * phoneWidth).toInt().coerceIn(0, phoneWidth - 1)
        val y = (py / textureView.height.coerceAtLeast(1) * phoneHeight).toInt().coerceIn(0, phoneHeight - 1)
        e.sendTouch(action, x, y)
        return true
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        engine?.sendHardKey(CarLife.HardKey.BACK)
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        val e = engine ?: return super.onKeyDown(keyCode, event)
        val code: Int? = when (keyCode) {
            android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> CarLife.HardKey.SELECTOR_NEXT
            android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> CarLife.HardKey.SELECTOR_PREVIOUS
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
            android.view.KeyEvent.KEYCODE_HEADSETHOOK,
            -> CarLife.HardKey.MEDIA_START

            android.view.KeyEvent.KEYCODE_MEDIA_STOP -> CarLife.HardKey.MEDIA_STOP
            android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> CarLife.HardKey.SEEK_ADD
            android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> CarLife.HardKey.SEEK_SUB
            android.view.KeyEvent.KEYCODE_HOME -> CarLife.HardKey.HOME
            android.view.KeyEvent.KEYCODE_CALL -> CarLife.HardKey.PHONE_CALL
            android.view.KeyEvent.KEYCODE_ENDCALL -> CarLife.HardKey.PHONE_END
            android.view.KeyEvent.KEYCODE_VOICE_ASSIST -> CarLife.HardKey.VR_START
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> CarLife.HardKey.MOVE_LEFT
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> CarLife.HardKey.MOVE_RIGHT
            android.view.KeyEvent.KEYCODE_DPAD_UP -> CarLife.HardKey.MOVE_UP
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> CarLife.HardKey.MOVE_DOWN
            android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER -> CarLife.HardKey.OK
            else -> null
        }
        return if (code != null) {
            e.sendHardKey(code)
            true
        } else {
            super.onKeyDown(keyCode, event)
        }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        ambientLight?.stop()
        ambientLight = null
        unitGps?.stop()
        unitGps = null
        engine?.stop()
        engine = null
        CarLifeSessionService.stop(this)
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    companion object {
        private const val TAG = "CarLifeVideo"

        /** Demo data yields to the phone GPS feed for this long after a fix. */
        private const val GPS_FRESH_MS = 5_000L
    }
}
