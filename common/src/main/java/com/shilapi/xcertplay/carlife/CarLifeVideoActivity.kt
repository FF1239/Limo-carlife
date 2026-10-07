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
 * Connected screen per the approved C1 Pro design: projection dominant
 * (left 2/3, full height), right instrument column (live speed ring, vehicle
 * data panel, controls), bottom telemetry ticker, carbon HUD styling.
 */
class CarLifeVideoActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var engine: CarLifeEngine? = null
    private lateinit var textureView: TextureView
    private lateinit var gauge: GaugeView
    private lateinit var ticker: TelemetryBar
    private lateinit var hudMedia: LinearLayout
    private lateinit var hudCover: android.widget.ImageView
    private lateinit var hudText: TextView
    private lateinit var hudProgress: android.widget.ProgressBar
    private lateinit var hudNavi: TextView
    private lateinit var statusOverlay: TextView
    private lateinit var settingsPanel: CarLifeSettingsPanel
    private var dataGear: TextView? = null
    private var dataRpm: TextView? = null
    private val picture = CarLifePicture.Settings()
    private var phoneWidth = CarLife.DEFAULT_VIDEO_WIDTH
    private var phoneHeight = CarLife.DEFAULT_VIDEO_HEIGHT

    private val hideStatus = Runnable { statusOverlay.visibility = View.GONE }

    /**
     * Demo vehicle source until a real CAN feed exists: a gently wandering
     * speed that drives the ring, gear and rpm readouts. Suppressed while the
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
            val gear = gearForSpeed(demoSpeed)
            val rpm = if (demoSpeed == 0) 800 else 900 + (demoSpeed * 23) % 4200
            gauge.setGauge(demoSpeed, gear, rpm, isStandby = false)
            dataGear?.text = gear
            dataRpm?.text = rpm.toString()
            ticker.update("SPEED", "$demoSpeed km/h", red = demoSpeed > 100)
            ticker.update("GEAR", gear)
            main.postDelayed(this, 900)
        }
    }

    private fun gearForSpeed(kmh: Int): String = if (kmh == 0) "P" else "D"

    /**
     * Picks the freshest real speed: the unit's own GNSS wins ties (roof
     * antenna), the phone's CAR_GPS feed backs it up, demo data fills gaps.
     */
    private fun updateVehicleDisplay() {
        val now = System.currentTimeMillis()
        val unitFresh = now - unitGpsAt < GPS_FRESH_MS && unitKmh >= 0
        val phoneFresh = now - lastGpsAt < GPS_FRESH_MS && phoneKmh >= 0
        val kmh = when {
            unitFresh && phoneFresh -> if (unitGpsAt >= lastGpsAt) unitKmh else phoneKmh
            unitFresh -> unitKmh
            phoneFresh -> phoneKmh
            else -> return
        }
        val gear = gearForSpeed(kmh)
        gauge.setGauge(kmh, gear, -1, isStandby = false)
        dataGear?.text = gear
        dataRpm?.text = "GPS"
        ticker.update("SPEED", "$kmh km/h", red = kmh > 100)
        ticker.update("GEAR", gear)
    }

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
                updateVehicleDisplay()
            }
            if (source.start()) unitGps = source
        }

        if (intent.getStringExtra("mode") == "wireless") {
            main.post { startWirelessScan() }
        }
        showStatus(getString(R.string.carlife_connecting))

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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(CarbonTheme.BG)
        }
        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        // ---- left: projection stage ----
        val stage = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

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
        }
        stage.addView(
            textureView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )
        textureView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyVideoTransform() }

        hudMedia = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 0f
                setColor(0xB30C0D0F.toInt())
                setStroke(dp(1), CarbonTheme.HAIRLINE)
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = View.GONE
            val row = LinearLayout(this@CarLifeVideoActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            hudCover = android.widget.ImageView(this@CarLifeVideoActivity).apply {
                visibility = View.GONE
                background = GradientDrawable().apply { setColor(0x22FFFFFF.toInt()) }
            }
            row.addView(hudCover, LinearLayout.LayoutParams(dp(40), dp(40)))
            hudText = TextView(this@CarLifeVideoActivity).apply {
                setTextColor(CarbonTheme.TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                maxLines = 2
                typeface = Typeface.MONOSPACE
                setPadding(dp(10), 0, 0, 0)
            }
            row.addView(hudText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            hudProgress = android.widget.ProgressBar(
                this@CarLifeVideoActivity, null, android.R.attr.progressBarStyleHorizontal,
            ).apply {
                max = 100
                progress = 0
                visibility = View.GONE
            }
            addView(hudProgress, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(8) })
        }
        stage.addView(
            hudMedia,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ).apply {
                val m = dp(16)
                setMargins(m, m, m, m)
            },
        )

        hudNavi = TextView(this).apply {
            setTextColor(CarbonTheme.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 2
            typeface = Typeface.MONOSPACE
            background = GradientDrawable().apply {
                cornerRadius = 0f
                setColor(0xB30C0D0F.toInt())
                setStroke(dp(1), CarbonTheme.RED)
            }
            setPadding(dp(14), dp(8), dp(14), dp(8))
            visibility = View.GONE
        }
        stage.addView(
            hudNavi,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply {
                val m = dp(16)
                setMargins(m, m, m, m)
            },
        )

        statusOverlay = TextView(this).apply {
            setTextColor(CarbonTheme.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            letterSpacing = 0.06f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = 0f
                setColor(0xB30C0D0F.toInt())
                setStroke(dp(1), CarbonTheme.HAIRLINE)
            }
            setPadding(dp(26), dp(14), dp(26), dp(14))
        }
        stage.addView(
            statusOverlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )

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
        stage.addView(
            settingsPanel,
            FrameLayout.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.62f).toInt().coerceAtLeast(dp(360)),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )

        body.addView(stage, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2f))

        // ---- right: instrument column ----
        val instruments = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(CarbonTheme.PANEL_DEEP)
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }

        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        brandRow.addView(View(this).apply { setBackgroundColor(CarbonTheme.RED) },
            LinearLayout.LayoutParams(dp(4), dp(14)))
        brandRow.addView(TextView(this).apply {
            text = "INSTRUMENTS"
            setTextColor(CarbonTheme.TEXT)
            textSize = 12f
            letterSpacing = 0.22f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), 0, 0, 0)
        })
        brandRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        instruments.addView(brandRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        instruments.addView(View(this).apply { setBackgroundColor(CarbonTheme.HAIRLINE) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))

        gauge = GaugeView(this).apply { setGauge(0, "P", 0, isStandby = true) }
        instruments.addView(gauge, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(220)).apply { topMargin = dp(10) })

        // vehicle data panel
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = CarbonTheme.hairlinePanel(this@CarLifeVideoActivity)
        }
        val gearRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val gearBig = TextView(this).apply {
            text = "P"
            setTextColor(CarbonTheme.RED)
            textSize = 30f
            typeface = Typeface.MONOSPACE
        }
        dataGear = gearBig
        gearRow.addView(gearBig)
        gearRow.addView(TextView(this).apply {
            text = "GEAR · 挡位"
            setTextColor(CarbonTheme.TEXT_FAINT)
            textSize = 10f
            letterSpacing = 0.18f
            setPadding(dp(14), 0, 0, 0)
        })
        gearRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        dataRpm = TextView(this).apply {
            text = "800"
            setTextColor(CarbonTheme.TEXT)
            textSize = 17f
            typeface = Typeface.MONOSPACE
        }
        gearRow.addView(dataRpm)
        panel.addView(gearRow)
        panel.addView(View(this).apply { setBackgroundColor(CarbonTheme.HAIRLINE) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        val statsRow = LinearLayout(this)
        listOf("续航" to "--", "油量" to "--", "水温" to "--").forEachIndexed { i, (k, v) ->
            if (i > 0) {
                statsRow.addView(View(this).apply { setBackgroundColor(CarbonTheme.HAIRLINE) },
                    LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT))
            }
            statsRow.addView(statCell(k, v))
        }
        panel.addView(statsRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        instruments.addView(panel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })

        // controls
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(actionBtn(getString(R.string.carlife_settings_button)) {
            settingsPanel.visibility =
                if (settingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        })
        btnRow.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        btnRow.addView(actionBtn(getString(R.string.carlife_wireless_connect)) {
            startWirelessScan()
        })
        btnRow.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        btnRow.addView(actionBtn(getString(R.string.carlife_manual_ip_button)) {
            promptForIp()
        })
        instruments.addView(btnRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })

        var videoPaused = false
        var pauseLabel: TextView? = null
        val btnRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val pauseBtn = actionBtn(getString(R.string.carlife_pause)) {
            videoPaused = !videoPaused
            engine?.setVideoPaused(videoPaused)
            pauseLabel?.text = getString(if (videoPaused) R.string.carlife_resume else R.string.carlife_pause)
        }
        pauseLabel = (pauseBtn as ViewGroup).getChildAt(0) as TextView
        btnRow2.addView(pauseBtn)
        btnRow2.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        btnRow2.addView(actionBtn(getString(R.string.carlife_screenshot)) { takeScreenshot() })
        btnRow2.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        btnRow2.addView(actionBtn(getString(R.string.carlife_disconnect)) {
            engine?.stop()
            finish()
        })
        instruments.addView(btnRow2, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })

        // phone-app shortcuts: ask the phone to open navi / music / foreground
        val btnRow3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow3.addView(actionBtn(getString(R.string.carlife_quick_navi)) {
            engine?.sendLaunchMode(CarLife.Cmd.LAUNCH_MODE_MAP)
        })
        btnRow3.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        btnRow3.addView(actionBtn(getString(R.string.carlife_quick_music)) {
            engine?.sendLaunchMode(CarLife.Cmd.LAUNCH_MODE_MUSIC)
        })
        btnRow3.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        btnRow3.addView(actionBtn(getString(R.string.carlife_quick_foreground)) {
            engine?.sendGoToForeground()
        })
        instruments.addView(btnRow3, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })

        body.addView(instruments, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        root.addView(body, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- telemetry ticker ----
        ticker = TelemetryBar(this)
        root.addView(ticker, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        ticker.update("LINK", "OFFLINE", red = true)
        ticker.update("SPEED", "0 km/h")
        ticker.update("GEAR", "P")
        ticker.update("CANVAS", "$phoneWidth×$phoneHeight")

        setContentView(root)
    }

    private fun statCell(key: String, value: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(0, dp(10), 0, dp(10))
        addView(TextView(this@CarLifeVideoActivity).apply {
            text = key
            setTextColor(CarbonTheme.TEXT_FAINT)
            textSize = 9f
            letterSpacing = 0.18f
        })
        addView(TextView(this@CarLifeVideoActivity).apply {
            text = value
            setTextColor(CarbonTheme.TEXT)
            textSize = 16f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(3), 0, 0)
        })
    }

    private fun actionBtn(text: String, onClick: () -> Unit): View = FrameLayout(this).apply {
        background = GradientDrawable().apply {
            cornerRadius = 0f
            setStroke(dp(1), CarbonTheme.HAIRLINE)
            setColor(Color.parseColor("#1D2024"))
        }
        addView(TextView(this@CarLifeVideoActivity).apply {
            this.text = text
            setTextColor(CarbonTheme.TEXT)
            textSize = 13f
            letterSpacing = 0.14f
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        minimumHeight = dp(46)
        isClickable = true
        setOnClickListener { onClick() }
    }

    /**
     * The stripped platform jar here has no TextureView.setPaint, so the
     * ColorMatrix goes through a hardware layer paint instead (and drops the
     * layer entirely at neutral settings).
     */
    /**
     * Letterboxes the projection inside the stage: the phone canvas keeps its
     * aspect (black bars top/bottom or left/right) instead of stretching.
     */
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

    private fun applyPicture() {
        val neutral = picture.brightness == 0 && picture.contrast == 0 &&
            picture.saturation == 0 && picture.warmth == 0 && !picture.night
        if (neutral) {
            textureView.setLayerType(View.LAYER_TYPE_NONE, null)
        } else {
            textureView.setLayerType(View.LAYER_TYPE_HARDWARE, CarLifePicture.paint(picture))
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
                        if (name.isNotEmpty()) ticker.update("PHONE", name)
                    }
                }

                override fun onEncoderSize(width: Int, height: Int) {
                    main.post {
                        phoneWidth = width
                        phoneHeight = height
                        persistence.saveNegotiated(width, height)
                        textureView.surfaceTexture?.setDefaultBufferSize(width, height)
                        applyVideoTransform()
                        ticker.update("CANVAS", "${width}×${height}")
                    }
                }

                override fun onMediaInfo(info: CarLifeMessages.MediaInfo) {
                    main.post {
                        val cover = info.albumArt.takeIf { it.isNotEmpty() }?.let { bytes ->
                            runCatching {
                                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            }.getOrNull()
                        }
                        val text = "♪ ${info.artist} — ${info.song}".trim(' ', '—')
                        if (cover == null && text.length <= 2) {
                            hudMedia.visibility = View.GONE
                            return@post
                        }
                        hudCover.visibility = if (cover != null) View.VISIBLE else View.GONE
                        cover?.let { hudCover.setImageBitmap(it) }
                        hudText.text = text
                        hudProgress.visibility = View.GONE
                        hudMedia.visibility = View.VISIBLE
                    }
                }

                override fun onMediaProgress(percent: Int) {
                    main.post {
                        if (percent in 1..100 && hudMedia.visibility == View.VISIBLE) {
                            hudProgress.progress = percent
                            hudProgress.visibility = View.VISIBLE
                        }
                    }
                }

                override fun onCarGps(gps: CarLifeMessages.CarGps) {
                    main.post {
                        lastGpsAt = System.currentTimeMillis()
                        // Reference app reports Location.speed * 100 (cm/s).
                        phoneKmh = (gps.speedCms * 36.0 / 1000.0).roundToInt()
                        updateVehicleDisplay()
                        CarLifeSessionLog.log(
                            this@CarLifeVideoActivity, "GPS",
                            "phone=${phoneKmh}kmh sats=${gps.satsUsed} fix=${gps.fix} head=${gps.heading}",
                        )
                    }
                }

                override fun onNaviInfo(info: CarLifeMessages.NaviNextTurnInfo) {
                    main.post {
                        val text = listOfNotNull(
                            info.roadName.takeIf { it.isNotBlank() },
                            info.remainDistance.takeIf { it > 0 }?.let { "${it}m" },
                        ).joinToString("  ")
                        if (text.isEmpty()) {
                            hudNavi.visibility = View.GONE
                        } else {
                            hudNavi.text = "↰ $text"
                            hudNavi.visibility = View.VISIBLE
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
                ticker.update("LINK", "ONLINE", online = true)
                main.removeCallbacks(demoTick)
                if (CarLifePersistence.get(this).demoData()) {
                    main.post(demoTick)
                } else {
                    gauge.setGauge(0, "P", 0, isStandby = true)
                    dataGear?.text = "P"
                    dataRpm?.text = "--"
                }
                showStatus(getString(R.string.carlife_projecting))
            }

            state == "disconnected" || state == "closed" || state == "stopped" -> {
                main.removeCallbacks(demoTick)
                gauge.setGauge(0, "P", 0, isStandby = true)
                dataGear?.text = "P"
                dataRpm?.text = "800"
                ticker.update("LINK", "OFFLINE", red = true)
                ticker.update("SPEED", "0 km/h")
                ticker.update("GEAR", "P")
                showStatus(getString(R.string.carlife_disconnected, detail ?: ""))
            }

            state == "scanning" -> showStatus(getString(R.string.state_scanning))
            state == "wireless" || state == "wireless-connecting" ->
                showStatus(getString(R.string.state_wireless_connecting, detail ?: ""))
            state == "reconnecting" ->
                showStatus(getString(R.string.state_reconnecting, detail ?: ""))
            state == "watchdog" -> showStatus(getString(R.string.state_watchdog))
            else -> showStatus(localizedState(state, detail))
        }
    }

    private fun localizedState(state: String, detail: String?): String =
        state + (detail?.let { " — $it" } ?: "")

    private fun showStatus(text: String) {
        statusOverlay.text = text
        statusOverlay.visibility = View.VISIBLE
    }

    // ---- input ----

    override fun onTouchEvent(event: MotionEvent): Boolean {
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

    companion object {
        private const val TAG = "CarLifeVideo"

        /** Demo data yields to the phone GPS feed for this long after a fix. */
        private const val GPS_FRESH_MS = 5_000L
    }
}
