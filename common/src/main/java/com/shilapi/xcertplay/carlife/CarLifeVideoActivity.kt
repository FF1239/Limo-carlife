package com.shilapi.xcertplay.carlife

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.text.TextPaint
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
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
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.diplay.carlife.core.CarLife
import com.diplay.carlife.core.CarLifeMessages
import com.shilapi.xcertplay.AndroidAmbientLight
import com.shilapi.xcertplay.DiPlayPalette
import com.shilapi.xcertplay.host.R
import kotlin.math.roundToInt

/**
 * CarLife dashboard host. The phone stream is not maximised any more — it
 * lives in a fixed left pane and is rendered through a dark-map filter so the
 * light-themed CarLife canvas blends into the dark shell. The right rack is
 * drawn by the head unit itself (media card + quick grid) from the CarLife
 * data channels, and a slim status bar carries time/speed/gear across the
 * top. Transport (handshake, touch forwarding inside the map pane, hard
 * keys, audio) is unchanged.
 */
class CarLifeVideoActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var engine: CarLifeEngine? = null
    private lateinit var textureView: TextureView
    private lateinit var rootLayout: FrameLayout
    private val picture = CarLifePicture.Settings()
    private var phoneWidth = CarLife.DEFAULT_VIDEO_WIDTH
    private var phoneHeight = CarLife.DEFAULT_VIDEO_HEIGHT

    // ---- dashboard chrome ----
    private lateinit var statusSpeed: TextView
    private lateinit var statusGear: TextView
    private lateinit var statusLink: TextView
    private lateinit var mapPane: FrameLayout
    private lateinit var dashRow: LinearLayout
    private lateinit var rackCol: LinearLayout
    private lateinit var dateText: TextView
    private lateinit var gpsText: TextView
    private lateinit var tripKmText: TextView
    private lateinit var tripDurText: TextView
    private lateinit var wTempText: TextView
    private lateinit var wDescText: TextView
    private lateinit var mCover: ImageView
    private lateinit var mCardBg: GradientDrawable
    private lateinit var mTitle: TextView
    private lateinit var mArtist: TextView
    private lateinit var mProgress: android.widget.ProgressBar
    private lateinit var mPlay: TextView
    private lateinit var mPlayHold: FrameLayout
    private lateinit var slimProgress: android.widget.ProgressBar
    private lateinit var slimBar: LinearLayout
    private lateinit var playerPanel: LinearLayout
    private lateinit var stripArrow: TextView
    private lateinit var stripDist: TextView
    private lateinit var stripRoad: TextView
    private lateinit var slimTitleView: TextView
    private lateinit var modeCover: ImageView
    private lateinit var modeTitle: TextView
    private lateinit var modeArtist: TextView
    private lateinit var modeProgress: android.widget.ProgressBar
    private lateinit var lyricView: TextView
    private lateinit var modeTimes: TextView
    private lateinit var statusLine: TextView
    private lateinit var slimPlay: SlantButton
    private lateinit var bigPlay: SlantButton
    private var statusBt: TextView? = null
    private var audioDeviceCallback: android.media.AudioDeviceCallback? = null
    private var settingsMenu: View? = null
    private var menuOpen = false
    private var gestureFingerCount = 3
    private var gestureSequenceActive = false
    private var gestureTracking = false
    private var gestureStartX = 0f
    private var gestureStartY = 0f

    // ---- waiting screen ----
    private var viewport: FrameLayout? = null
    private var stageStatusView: TextView? = null
    private var instructionsView: TextView? = null
    private var startupRetryButton: Button? = null
    private var paintWaitingScreen: (() -> Unit)? = null

    // ---- media state (stream-rendered; only the play-state mirror lives here) ----
    private var mediaPlaying = false
    private var lastMediaKey = ""
    private var mapAutoLaunched = false
    private var screenMode = MODE_DASH
    private var pinchActive = false
    private var tripMeters = 0f
    private var tripStartAt = 0L
    private var lastTripLat = 0.0
    private var lastTripLng = 0.0
    private var tripAnchorValid = false
    private var weatherFetched = false
    private var pinchStartSpan = 0f
    private var pinchCurSpan = 0f
    private var lastMediaDuration = 0
    private var naviActive = false
    private var lyricLines: List<Pair<Long, String>> = emptyList()
    private var lyricKey = ""
    private var lyricIdx = -1
    private var localPlaySec = 0
    private var btConnected = false
    private var phoneLabel = ""
    private var naviPill: LinearLayout? = null
    private var naviPillText: TextView? = null

    private val videoRect = android.graphics.RectF()
    private var scanHintShown = false

    /** True while an engine session is connecting/connected — guards scan callbacks from killing a live projection. */
    @Volatile
    private var sessionAlive = false

    /** Direct-connect host from the "host" intent extra, consumed when the surface becomes available. */
    @Volatile
    private var pendingDirectHost: java.net.InetAddress? = null

    private var lastGpsAt = 0L
    private var phoneKmh = -1
    private var unitGps: UnitGpsSpeedSource? = null
    private var unitKmh = -1
    private var unitGpsAt = 0L

    /** Auto day/night via the head unit's light sensor (SYSTEM mode only). */
    private var ambientLight: AndroidAmbientLight? = null
    private var ambientNight = false
    private var appNight = true

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level < 0 || scale <= 0) return
            statusBt?.text = "${level * 100 / scale}%"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CarbonTheme.load(this)
        installCrashCapture()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        buildContentView()
        main.postDelayed(uiTick, 1_000)
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        refreshBluetooth()
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val callback = object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<android.media.AudioDeviceInfo>) {
                main.post { refreshBluetooth() }
            }

            override fun onAudioDevicesRemoved(removed: Array<android.media.AudioDeviceInfo>) {
                main.post { refreshBluetooth() }
            }
        }
        am.registerAudioDeviceCallback(callback, main)
        audioDeviceCallback = callback

        if (CarLifePersistence.get(this).dayNightMode() == CarLifePersistence.DAY_NIGHT_SYSTEM) {
            val light = AndroidAmbientLight(this)
            if (light.start { lux -> main.post { applyAmbientLux(lux) } }) {
                ambientLight = light
            }
        }
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            val source = UnitGpsSpeedSource(this) { kmh ->
                unitKmh = kmh
                unitGpsAt = System.currentTimeMillis()
                main.post { renderSpeed() }
            }
            if (source.start()) unitGps = source
        }

        if (intent.getStringExtra("mode") == "wireless") {
            val directHost = intent.getStringExtra("host")
            if (directHost != null) {
                val addr = runCatching { java.net.InetAddress.getByName(directHost) }.getOrNull()
                if (addr != null) {
                    main.post { setStage(getString(R.string.carlife_phone_found, addr.hostAddress)) }
                    pendingDirectHost = addr
                }
            } else {
                main.post { startWirelessScan() }
            }
        }
        setStage(getString(R.string.carlife_connecting))

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

    // ---- dashboard layout ----

    private fun buildContentView() {
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF0B0B0C.toInt()) }
        rootLayout = root

        buildTopBar(root)
        buildDashRow(root)
        buildSlimBar(root)
        buildPlayerPanel(root)

        // Preparation viewport (connection stage) covers the whole dashboard.
        buildWaitingScreen(root)

        settingsMenu = buildSettingsMenu().apply { visibility = View.GONE }
        root.addView(settingsMenu, FrameLayout.LayoutParams(-1, -1))

        setContentView(root)
    }

    /** 30dp hairline top bar: brand, clock, link, speed, mode entries. */
    private fun buildTopBar(root: FrameLayout) {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF0B0B0C.toInt())
            setPadding(dp(18), 0, dp(10), 0)
        }
        bar.addView(View(this).apply { setBackgroundColor(0xFFE1062C.toInt()) },
            LinearLayout.LayoutParams(dp(3), dp(13)))
        bar.addView(space(8))
        bar.addView(TextView(this).apply {
            text = "Limo"
            setTextColor(0xFFEDEDEA.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
        })
        bar.addView(space(9))
        bar.addView(TextClock(this).apply {
            format24Hour = "HH:mm"
            format12Hour = null
            setTextColor(0xFF8A8983.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
        })
        bar.addView(space(6))
        dateText = TextView(this).apply {
            setTextColor(0xFF55544F.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        }
        dateText.text = formatToday()
        bar.addView(dateText)
        bar.addView(space(10))
        statusLink = TextView(this).apply {
            setTextColor(0xFF55544F.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            text = "在线"
        }
        bar.addView(statusLink)
        bar.addView(space(8))
        statusBt = TextView(this).apply {
            text = "--"
            setTextColor(0xFF55544F.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.MONOSPACE
        }
        bar.addView(statusBt)
        bar.addView(View(this).also { it.layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        statusSpeed = TextView(this).apply {
            text = "0 km/h"
            setTextColor(0xFFEDEDEA.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
        }
        bar.addView(statusSpeed)
        bar.addView(space(8))
        statusGear = TextView(this).apply {
            text = "P"
            setTextColor(0xFF8A8983.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), 0xFF26262A.toInt())
            }
            setPadding(dp(4), 0, dp(4), dp(1))
        }
        bar.addView(statusGear)
        bar.addView(space(10))
        bar.addView(circleButton("⛶") { applyScreenMode(MODE_MAP) })
        bar.addView(space(6))
        bar.addView(circleButton("♪") { applyScreenMode(MODE_MUSIC) })
        bar.addView(space(6))
        bar.addView(circleButton("≡") { openSettingsMenu() })

        // Navigation hint pill (music mode only — map modes show it in context)
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(0xFF141415.toInt())
                setStroke(dp(1), 0xFF26262A.toInt())
            }
            setPadding(dp(10), 0, dp(10), 0)
            visibility = View.GONE
        }
        naviPill = pill
        naviPillText = TextView(this).apply {
            setTextColor(0xFF999890.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.MONOSPACE
        }
        pill.addView(naviPillText)
        bar.addView(pill, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(20),
        ).apply { marginEnd = dp(10) })

        root.addView(bar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(30), Gravity.TOP,
        ))
    }

    /** Dashboard row: map mass (55) + soft-card rack (45). */
    private fun buildDashRow(root: FrameLayout) {
        dashRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        // ---- map mass ----
        val pane = FrameLayout(this).apply { setBackgroundColor(0xFF0B0D10.toInt()) }
        mapPane = pane
        textureView = TextureView(this).apply {
            isOpaque = false
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    st: SurfaceTexture,
                    w: Int,
                    h: Int,
                ) {
                    st.setDefaultBufferSize(phoneWidth, phoneHeight)
                    applyVideoTransform()
                    val direct = pendingDirectHost
                    if (direct != null) {
                        pendingDirectHost = null
                        startEngine(Surface(st), CarLifeEngine.Mode.WIRELESS, direct)
                    } else {
                        startEngine(Surface(st))
                    }
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                    applyVideoTransform()
                }

                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    engine?.stop()
                    engine = null
                    return true
                }

                override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
            }
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyVideoTransform() }
            setOnTouchListener { view, event -> onMapTouch(view, event) }
            isClickable = true
        }
        pane.addView(textureView, FrameLayout.LayoutParams(-1, -1))

        // turn strip: solid black, red arrow disc, white mono distance
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(0xFF0D0D0F.toInt()) }
            setPadding(dp(8), 0, dp(14), 0)
            visibility = View.GONE
        }
        stripArrow = TextView(this).apply {
            text = "↰"
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFE1062C.toInt())
            }
        }
        strip.addView(stripArrow, FrameLayout.LayoutParams(dp(26), dp(26)))
        strip.addView(space(10))
        stripDist = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.MONOSPACE
            typeface = Typeface.DEFAULT_BOLD
        }
        strip.addView(stripDist)
        strip.addView(space(10))
        stripRoad = TextView(this).apply {
            setTextColor(0xFF8A8983.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 1
        }
        strip.addView(stripRoad)
        pane.addView(strip, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(46), Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ).apply { topMargin = dp(12) })

        dashRow.addView(pane, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 55f))

        // ---- rack: soft cards ----
        val rack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF101011.toInt())
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        rackCol = rack

        // clock card
        val clockCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(0xFF1A1A1D.toInt()) }
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val clockHead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        clockHead.addView(TextClock(this).apply {
            format24Hour = "HH:mm"
            format12Hour = null
            setTextColor(0xFFEDEDEA.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            typeface = Typeface.MONOSPACE
            typeface = Typeface.DEFAULT_BOLD
        })
        clockHead.addView(space(12))
        val dateCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        dateCol.addView(TextView(this).apply {
            text = formatToday()
            setTextColor(0xFF8A8983.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        })
        gpsText = TextView(this).apply {
            text = "GPS·----"
            setTextColor(0xFF55544F.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(3), 0, 0)
        }
        dateCol.addView(gpsText)
        clockHead.addView(dateCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        clockCard.addView(clockHead)
        rack.addView(clockCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        // trip + weather card
        val tripCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(0xFF1A1A1D.toInt()) }
            setPadding(dp(16), dp(12), dp(16), dp(14))
        }
        val tripRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tripKmText = TextView(this).apply {
            text = "--"
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 23f)
            typeface = Typeface.DEFAULT_BOLD
        }
        tripRow.addView(tripKmText)
        tripDurText = TextView(this).apply {
            text = " 公里 · --"
            setTextColor(0xFF8A8983.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(4), dp(8), 0, 0)
        }
        tripRow.addView(tripDurText)
        tripRow.addView(View(this).also { it.layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        val weatherCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wTempText = TextView(this).apply {
            text = "--°"
            setTextColor(0xFFEDEDEA.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
        }
        weatherCol.addView(wTempText)
        wDescText = TextView(this).apply {
            setTextColor(0xFF8A8983.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        }
        weatherCol.addView(wDescText)
        tripRow.addView(weatherCol)
        tripCard.addView(tripRow)
        rack.addView(tripCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(12) })

        // media card: cover-tinted background
        val mediaCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(0xFF3A2430.toInt()) }
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        mCardBg = mediaCard.background as GradientDrawable
        val mediaHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        mCover = ImageView(this).apply {
            background = GradientDrawable().apply { setColor(0xFF141415.toInt()) }
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(8f))
                }
            }
        }
        mediaHead.addView(mCover, LinearLayout.LayoutParams(dp(52), dp(52)))
        mediaHead.addView(space(12))
        val mediaText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mTitle = TextView(this).apply {
            text = getString(R.string.widget_no_media)
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }
        mediaText.addView(mTitle)
        mArtist = TextView(this).apply {
            setTextColor(0xB3FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            maxLines = 1
        }
        mediaText.addView(mArtist)
        mediaHead.addView(mediaText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // inline controls
        fun miniControl(label: String, big: Boolean, onClick: () -> Unit): View =
            FrameLayout(this).apply {
                isClickable = true
                setOnClickListener { onClick() }
                addView(TextView(this@CarLifeVideoActivity).apply {
                    text = label
                    setTextColor(0xFFFFFFFF.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, if (big) 11f else 9f)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(if (big) 0x40FFFFFF.toInt() else 0x26FFFFFF.toInt())
                    }
                }, FrameLayout.LayoutParams(dp(if (big) 26 else 22), dp(if (big) 26 else 22)))
            }
        mediaHead.addView(miniControl("❮", false) {
            engine?.sendHardKey(CarLife.HardKey.SELECTOR_PREVIOUS)
        })
        mediaHead.addView(space(6))
        mPlayHold = FrameLayout(this).apply { isClickable = true }
        mPlay = TextView(this).apply {
            text = "▶"
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFE1062C.toInt())
            }
        }
        mPlayHold.addView(mPlay, FrameLayout.LayoutParams(dp(30), dp(30)))
        mPlayHold.setOnClickListener { toggleMediaPlay() }
        mediaHead.addView(mPlayHold)
        mediaHead.addView(space(6))
        mediaHead.addView(miniControl("❯", false) {
            engine?.sendHardKey(CarLife.HardKey.SELECTOR_NEXT)
        })
        mediaCard.addView(mediaHead)
        mProgress = android.widget.ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal,
        ).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(0xFFE1062C.toInt())
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x26FFFFFF.toInt())
        }
        mediaCard.addView(mProgress, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(3),
        ).apply { topMargin = dp(10) })
        rack.addView(mediaCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(12) })

        // quick pills
        val quick = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun pill(label: String, onClick: () -> Unit): View =
            TextView(this).apply {
                text = label
                setTextColor(0xFF8A8983.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { setColor(0xFF1A1A1D.toInt()) }
                isClickable = true
                setOnClickListener { onClick() }
            }
        quick.addView(pill("◈ 导航") {
            // Phone navigation, CarPlay-style: ask the phone to bring its
            // CarLife map forward, then take the screen fullscreen. When
            // guidance actually starts, onNaviInfo auto-expands the map too.
            engine?.sendLaunchMode(CarLife.Cmd.LAUNCH_MODE_MAP)
            applyScreenMode(MODE_MAP)
        },
            LinearLayout.LayoutParams(0, dp(30), 1f).apply { marginEnd = dp(8) })
        quick.addView(pill("◉ 语音") { engine?.sendHardKey(CarLife.HardKey.VR_START) },
            LinearLayout.LayoutParams(0, dp(30), 1f).apply { marginEnd = dp(8) })
        quick.addView(pill("♪ 音乐") { applyScreenMode(MODE_MUSIC) },
            LinearLayout.LayoutParams(0, dp(30), 1f).apply { marginEnd = dp(8) })
        quick.addView(pill("≡ 设置") { openSettingsMenu() },
            LinearLayout.LayoutParams(0, dp(30), 1f))
        rack.addView(quick, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(12) })

        dashRow.addView(rack, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 45f))

        root.addView(dashRow, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ).apply { topMargin = dp(30) })
    }

    /** Slim media rail overlaying the map in map mode; auto-hides after 4s. */
    private fun buildSlimBar(root: FrameLayout) {
        slimBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF0D0D0F.toInt())
            setPadding(dp(14), 0, dp(14), 0)
            visibility = View.GONE
        }
        slimPlay = slantButton("▶", dp(60), dp(26), filled = false) { toggleMediaPlay() }
        slimBar.addView(slimPlay)
        slimBar.addView(space(10))
        slimBar.addView(slantButton("❮", dp(22), dp(20), filled = false) {
            engine?.sendHardKey(CarLife.HardKey.SELECTOR_PREVIOUS)
        })
        slimBar.addView(space(6))
        slimBar.addView(slantButton("❯", dp(22), dp(20), filled = false) {
            engine?.sendHardKey(CarLife.HardKey.SELECTOR_NEXT)
        })
        slimBar.addView(space(12))
        val slimCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        slimTitleView = TextView(this).apply {
            text = getString(R.string.widget_no_media)
            setTextColor(0xFF8A8983.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.MONOSPACE
            maxLines = 1
        }
        slimCol.addView(slimTitleView)
        slimProgress = android.widget.ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal,
        ).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(0xFF777673.toInt())
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFF1E1E1E.toInt())
        }
        slimCol.addView(slimProgress, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(2),
        ).apply { topMargin = dp(3) })
        slimBar.addView(slimCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        slimBar.addView(slantButton("◉", dp(24), dp(20), filled = false) {
            engine?.sendHardKey(CarLife.HardKey.VR_START)
        })
        slimBar.addView(space(6))
        slimBar.addView(slantButton("≡", dp(24), dp(20), filled = false) { openSettingsMenu() })

        root.addView(slimBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(56), Gravity.BOTTOM,
        ))
    }

    /** Music mode: the listening form fills the panel below the top bar. */
    private fun buildPlayerPanel(root: FrameLayout) {
        playerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(20), dp(24), dp(16))
            visibility = View.GONE
            setOnTouchListener { _, event -> onPlayerPinch(event) }
            isClickable = true
        }
        modeCover = ImageView(this).apply {
            background = GradientDrawable().apply { setColor(0xFF141415.toInt()) }
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(10f))
                }
            }
        }
        playerPanel.addView(modeCover, LinearLayout.LayoutParams(dp(128), dp(128)).apply {
            topMargin = dp(12)
        })
        playerPanel.addView(space(18))
        modeTitle = TextView(this).apply {
            text = getString(R.string.widget_no_media)
            setTextColor(0xFFF2F4F7.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            gravity = Gravity.CENTER
        }
        playerPanel.addView(modeTitle, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        modeArtist = TextView(this).apply {
            setTextColor(0xFF8A8983.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
            gravity = Gravity.CENTER
        }
        playerPanel.addView(modeArtist, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(4) })
        // Lyrics: network-matched LRC, synced to the playback progress. The
        // CarLife protocol carries no lyric channel — honest fallback only.
        lyricView = TextView(this).apply {
            setTextColor(0xFF8A8983.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            maxLines = 3
            lineHeight = dp(26)
            text = ""
        }
        playerPanel.addView(lyricView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ).apply { topMargin = dp(18); bottomMargin = dp(10) })
        playerPanel.addView(space(10))
        val progressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        modeProgress = android.widget.ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal,
        ).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(0xFFE1062C.toInt())
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFF1E1E1E.toInt())
        }
        progressRow.addView(modeProgress, LinearLayout.LayoutParams(0, dp(4), 1f))
        progressRow.addView(space(10))
        modeTimes = TextView(this).apply {
            text = "--:-- / --:--"
            setTextColor(0xFF777673.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.MONOSPACE
        }
        progressRow.addView(modeTimes)
        playerPanel.addView(progressRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        playerPanel.addView(space(18))
        val playerControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        bigPlay = slantButton("❚❚", dp(96), dp(34), filled = true) { toggleMediaPlay() }
        playerControls.addView(bigPlay)
        playerControls.addView(space(14))
        playerControls.addView(slantButton("❮", dp(46), dp(26), filled = false) {
            engine?.sendHardKey(CarLife.HardKey.SELECTOR_PREVIOUS)
        })
        playerControls.addView(space(8))
        playerControls.addView(slantButton("❯", dp(46), dp(26), filled = false) {
            engine?.sendHardKey(CarLife.HardKey.SELECTOR_NEXT)
        })
        playerControls.addView(space(8))
        playerControls.addView(slantButton("◉", dp(46), dp(26), filled = false) {
            engine?.sendHardKey(CarLife.HardKey.VR_START)
        })
        playerControls.addView(space(8))
        playerControls.addView(slantButton("◎", dp(46), dp(26), filled = false) {
            applyScreenMode(MODE_DASH)
        })
        playerPanel.addView(playerControls, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(playerPanel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ).apply { topMargin = dp(30) })
    }

    /** Mode switch: dashboard ⇄ map ⇄ music, with the pinch semantics. */
    private fun applyScreenMode(mode: Int) {
        if (mode == screenMode) return
        screenMode = mode
        dashRow.visibility = if (mode == MODE_MUSIC) View.GONE else View.VISIBLE
        rackCol.visibility = if (mode == MODE_DASH) View.VISIBLE else View.GONE
        playerPanel.visibility = if (mode == MODE_MUSIC) View.VISIBLE else View.GONE
        if (mode == MODE_MAP) {
            slimBar.visibility = View.VISIBLE
            // The hide animation only translates the bar, so a second entry
            // into map mode has to reset it or the bar stays off-screen.
            slimBar.translationY = 0f
            autoHideSlim()
        } else {
            slimBar.visibility = View.GONE
            main.removeCallbacks(hideSlim)
        }
        refreshNaviPill()
    }

    private fun autoHideSlim() {
        main.removeCallbacks(hideSlim)
        main.postDelayed(hideSlim, 4_000)
    }

    private val hideSlim = Runnable {
        if (screenMode == MODE_MAP) slimBar.animate().translationY(dp(60).toFloat()).setDuration(300).start()
    }

    private fun showSlim() {
        if (screenMode == MODE_MAP) {
            slimBar.animate().translationY(0f).setDuration(300).start()
            autoHideSlim()
        }
    }

    private fun refreshNaviPill() {
        val stripVisible = naviActive && screenMode != MODE_MUSIC
        // map strip visibility follows mode too
        val strip = stripDist.parent as View
        strip.visibility = if (stripVisible) View.VISIBLE else View.GONE
        naviPill?.visibility =
            if (naviActive && screenMode == MODE_MUSIC) View.VISIBLE else View.GONE
    }

    /** Pinch-out on the player returns to the dashboard. */
    private fun onPlayerPinch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount == 2 && !pinchActive) {
                pinchActive = true
                pinchStartSpan = pointerSpan(event)
                return true
            }
            MotionEvent.ACTION_MOVE -> if (pinchActive && event.pointerCount >= 2) {
                pinchCurSpan = pointerSpan(event)
                return true
            }
        }
        if (pinchActive) {
            if (event.actionMasked == MotionEvent.ACTION_POINTER_UP ||
                event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                if (pinchStartSpan > 0 && pinchCurSpan > 0) {
                    val ratio = pinchCurSpan / pinchStartSpan
                    if (ratio > 1.18f && screenMode != MODE_DASH) applyScreenMode(MODE_DASH)
                }
                pinchActive = false
                pinchStartSpan = 0f
                pinchCurSpan = 0f
            }
            return true
        }
        return false
    }

    private fun setPlayGlyph(playing: Boolean) {
        val glyph = if (playing) "❚❚" else "▶"
        mPlay.text = glyph
        slimPlay.glyph = glyph
        slimPlay.invalidate()
        bigPlay.glyph = glyph
        bigPlay.invalidate()
    }

    private fun formatToday(): String {
        val cal = java.util.Calendar.getInstance()
        val weeks = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
        return "%d月%d日 %s".format(
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
            weeks[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1],
        )
    }

    /**
     * Parallelogram control — kept only inside the music player where the
     * slant language is part of the playback identity.
     */
    private inner class SlantButton(context: Context) : View(context) {
        var glyph: String = ""
        var filled: Boolean = false
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1f)
            color = 0xFF222222.toInt()
        }
        private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        override fun onDraw(canvas: Canvas) {
            val cut = width * 0.16f
            val path = Path().apply {
                moveTo(0f, 0f)
                lineTo(width - cut, 0f)
                lineTo(width.toFloat(), height.toFloat())
                lineTo(cut, height.toFloat())
                close()
            }
            fillPaint.style = Paint.Style.FILL
            fillPaint.color = if (filled) 0xFFE1062C.toInt() else 0xFF141415.toInt()
            canvas.drawPath(path, fillPaint)
            if (!filled) canvas.drawPath(path, strokePaint)
            textPaint.color = if (filled) 0xFFFFFFFF.toInt() else 0xFF999890.toInt()
            textPaint.textSize = height * (if (glyph.length > 1) 0.40f else 0.48f)
            val y = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(glyph, width / 2f, y, textPaint)
        }
    }

    private fun slantButton(glyph: String, w: Int, h: Int, filled: Boolean, onClick: () -> Unit): SlantButton =
        SlantButton(this).apply {
            this.glyph = glyph
            this.filled = filled
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(w, h)
        }

    /** Circle entry button for the top bar. */
    private fun circleButton(label: String, onClick: () -> Unit): View =
        FrameLayout(this).apply {
            isClickable = true
            setOnClickListener { onClick() }
            addView(TextView(this@CarLifeVideoActivity).apply {
                text = label
                setTextColor(0xFF8A8983.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(1), 0xFF26262A.toInt())
                }
            }, FrameLayout.LayoutParams(dp(20), dp(20)))
        }

    // ---- waiting screen (status surface) ----

    private fun buildWaitingScreen(root: FrameLayout) {
        val viewport = object : FrameLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                getChildAt(0)?.measure(
                    View.MeasureSpec.makeMeasureSpec(
                        (measuredWidth - paddingLeft - paddingRight).coerceAtLeast(0),
                        View.MeasureSpec.EXACTLY,
                    ),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
            }
        }.apply { isClickable = true }
        this.viewport = viewport

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_limo_mark)
        }
        panel.addView(icon, LinearLayout.LayoutParams(dp(88), dp(88)))
        val title = TextView(this).apply {
            text = "Limo"
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        panel.addView(title)
        val stage = TextView(this).apply {
            text = getString(R.string.carlife_connecting)
            gravity = Gravity.CENTER
        }
        panel.addView(stage)
        stageStatusView = stage
        val instructions = TextView(this).apply {
            text = getString(R.string.carlife_wireless_hint)
            gravity = Gravity.CENTER
        }
        panel.addView(instructions)
        instructionsView = instructions
        val retry = Button(this).apply {
            text = getString(R.string.side_panel_retry)
            isAllCaps = false
            visibility = View.GONE
            setOnClickListener {
                visibility = View.GONE
                engine?.stop()
                engine = null
                setStage(getString(R.string.carlife_connecting))
                // Retry must re-resolve the phone: a bare WIRELESS start has no
                // host and just stops again (the observed retry-loop).
                val direct = pendingDirectHost
                if (direct != null) {
                    startEngine(currentSurface(), CarLifeEngine.Mode.WIRELESS, direct)
                } else {
                    startWirelessScan()
                }
            }
            startupRetryButton = this
        }
        panel.addView(retry, LinearLayout.LayoutParams(dp(300), dp(64)).apply { bottomMargin = dp(12) })
        val back = Button(this).apply {
            text = getString(R.string.carlife_back_to_home)
            isAllCaps = false
            setOnClickListener { finish() }
        }
        panel.addView(back, LinearLayout.LayoutParams(dp(300), dp(64)))
        val gestureHint = TextView(this).apply {
            text = getString(R.string.open_settings_hint, gestureFingerCount)
            gravity = Gravity.CENTER
        }
        panel.addView(gestureHint)
        paintWaitingScreen = {
            // v16 tokens — DiPlay's WaitingScreenColors is gone from this screen.
            viewport.setBackgroundColor(0xFF0B0B0C.toInt())
            title.setTextColor(0xFFEDEDEA.toInt())
            stage.setTextColor(0xFFEDEDEA.toInt())
            instructions.setTextColor(0xFF8A8983.toInt())
            gestureHint.setTextColor(0xFF55544F.toInt())
            back.setTextColor(0xFFFFFFFF.toInt())
            back.background = GradientDrawable().apply {
                setColor(0xFFE1062C.toInt())
                cornerRadius = dp(20).toFloat()
            }
            retry.setTextColor(0xFFF2F4F7.toInt())
            retry.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF1E1E1E.toInt())
        }
        paintWaitingScreen?.invoke()
        viewport.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        root.addView(viewport, FrameLayout.LayoutParams(-1, -1))
        ViewCompat.setOnApplyWindowInsetsListener(viewport) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            viewport.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(viewport)
    }

    // ---- settings menu (scrolling overlay) ----

    private fun buildSettingsMenu(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(56), dp(48), dp(36))
        }
        content.addView(TextView(this).apply {
            text = getString(R.string.settings_title)
            setTextColor(0xFFF2F4F7.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(20) })

        content.addView(sectionHeader(getString(R.string.settings_section_connection)))
        content.addView(settingsButton(getString(R.string.carlife_wireless_connect)) {
            closeSettingsMenu()
            engine?.stop()
            engine = null
            startWirelessScan()
        })
        content.addView(settingsButton(getString(R.string.carlife_wired_connect)) {
            closeSettingsMenu()
            engine?.stop()
            engine = null
            startEngine(currentSurface(), CarLifeEngine.Mode.WIRED)
        })
        content.addView(settingsButton(getString(R.string.carlife_manual_ip_button)) {
            closeSettingsMenu()
            promptForIp()
        })

        content.addView(sectionHeader(getString(R.string.settings_section_display)))
        content.addView(CarLifeSettingsPanel(
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
                engine?.stop()
                engine = null
                textureView.surfaceTexture?.setDefaultBufferSize(w, h)
                startEngine(currentSurface(), usePanelSize = true)
            },
        ), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(14) })

        content.addView(sectionHeader(getString(R.string.settings_section_actions)))
        content.addView(settingsButton(getString(R.string.action_screenshot)) { takeScreenshot() })
        content.addView(settingsButton(getString(R.string.action_disconnect)) {
            closeSettingsMenu()
            engine?.stop()
            engine = null
        })

        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xF2121212.toInt())
            isClickable = true
            addView(content, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(0xE6121212.toInt())
            isClickable = true
            addView(Button(this@CarLifeVideoActivity).apply {
                text = "X"
                isAllCaps = false
                textSize = 20f
                setTextColor(0xFFF2F4F7.toInt())
                backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF2C2C2A.toInt())
                minWidth = 0
                minHeight = 0
                setPadding(0, 0, 0, 0)
                setOnClickListener { closeSettingsMenu() }
            }, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(12)
                marginEnd = dp(12)
            })
            addView(scroll, FrameLayout.LayoutParams(-1, -1))
        }
        return overlay
    }

    private fun sectionHeader(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(0xFF888780.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        letterSpacing = 0.08f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(30), 0, 0)
    }

    private fun settingsButton(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(0xFFF2F4F7.toInt())
        backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF1E1E1E.toInt())
        setOnClickListener { onClick() }
    }

    private fun openSettingsMenu() {
        if (menuOpen) return
        menuOpen = true
        settingsMenu?.let { setVisibleAnimated(it, true) }
    }

    private fun closeSettingsMenu() {
        if (!menuOpen) return
        menuOpen = false
        settingsMenu?.let { setVisibleAnimated(it, false) }
    }

    // ---- map touch (gesture + forwarding, restricted to the map pane) ----

    /** Free weather by GPS position (no key). Silent on failure. */
    private fun fetchWeather(lat: Double, lng: Double) {
        Thread {
            runCatching {
                val url = java.net.URL(
                    "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lng&current_weather=true",
                )
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val temp = Regex("\"temperature\":(-?[0-9.]+)").find(text)
                    ?.groupValues?.get(1)?.toDoubleOrNull()
                val code = Regex("\"weathercode\":([0-9]+)").find(text)
                    ?.groupValues?.get(1)?.toIntOrNull()
                conn.disconnect()
                if (temp != null) {
                    val desc = when (code) {
                        0 -> "晴"
                        in 1..3 -> "多云"
                        45, 48 -> "雾"
                        in 51..67 -> "雨"
                        in 71..77 -> "雪"
                        in 80..82 -> "阵雨"
                        in 95..99 -> "雷雨"
                        else -> ""
                    }
                    main.post {
                        wTempText.text = "${temp.toInt()}°"
                        wDescText.text = desc
                    }
                }
            }
        }.start()
    }

    private fun updateStatusLine() {
        statusLink.text = buildString {
            append(if (sessionAlive) "已连接" else "未连接")
            if (btConnected) append(" · 蓝牙")
        }
    }

    /**
     * One-second dashboard heartbeat. The phone never streams
     * MEDIA_PROGRESS_BAR on this build, so the play state comes from the
     * music audio flow (frames arriving = playing) and the progress position
     * is interpolated locally between song changes and any real progress
     * messages.
     */
    private val uiTick = object : Runnable {
        override fun run() {
            val playing = engine?.isMusicFlowing() == true
            if (playing != mediaPlaying) {
                mediaPlaying = playing
                engine?.setMediaPlaying(playing)
                setPlayGlyph(playing)
            }
            if (lastMediaDuration > 0) {
                val totalSec =
                    if (lastMediaDuration > 10_000) lastMediaDuration / 1000
                    else lastMediaDuration
                if (playing) localPlaySec = (localPlaySec + 1).coerceAtMost(totalSec)
                val pct = localPlaySec * 100 / totalSec
                mProgress.progress = pct
                modeProgress.progress = pct
                slimProgress.progress = pct
                fun mmss(sec: Int) = "%d:%02d".format(sec / 60, sec % 60)
                modeTimes.text = "${mmss(localPlaySec)} / ${mmss(totalSec)}"
                syncLyrics(localPlaySec * 1000L)
            }
            main.postDelayed(this, 1_000)
        }
    }

    /** Fetch synced lyrics for the current song (lrclib.net, free, no key). */
    private fun loadLyricsIfChanged(song: String, artist: String) {
        val key = "$song|$artist"
        if (key == lyricKey) return
        lyricKey = key
        lyricLines = emptyList()
        lyricIdx = -1
        lyricView.text = ""
        if (song.isBlank() || song == getString(R.string.media_unknown_song)) return
        Thread {
            var lines: List<Pair<Long, String>> = emptyList()
            runCatching {
                val q = "track_name=" + java.net.URLEncoder.encode(song, "UTF-8") +
                    "&artist_name=" + java.net.URLEncoder.encode(artist, "UTF-8")
                val conn = java.net.URL("https://lrclib.net/api/search?$q")
                    .openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                conn.setRequestProperty("User-Agent", "Limo-CarLife/1.0")
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val arr = org.json.JSONArray(body)
                for (i in 0 until arr.length()) {
                    val synced = arr.getJSONObject(i).optString("syncedLyrics")
                    if (synced.isNotBlank()) {
                        lines = parseLrc(synced)
                        break
                    }
                }
            }
            main.post {
                lyricLines = lines
                lyricIdx = -1
                lyricView.text = if (lines.isEmpty()) "暂无歌词" else ""
            }
        }.start()
    }

    private fun parseLrc(lrc: String): List<Pair<Long, String>> {
        val out = mutableListOf<Pair<Long, String>>()
        val stamp = Regex("\\[(\\d+):(\\d+)(?:\\.(\\d+))?\\]")
        for (raw in lrc.lines()) {
            val stamps = stamp.findAll(raw).toList()
            if (stamps.isEmpty()) continue
            val text = raw.substring(stamps.last().range.last + 1).trim()
            if (text.isBlank()) continue
            for (m in stamps) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3].padEnd(3, '0').take(3).toLong()
                out += (min * 60 + sec) * 1000 + frac to text
            }
        }
        return out.sortedBy { it.first }
    }

    /** Shows the 3-line window around the current lyric line. */
    private fun syncLyrics(curMs: Long) {
        if (lyricLines.isEmpty()) return
        var idx = lyricIdx
        if (idx < 0 || idx >= lyricLines.size || lyricLines[idx].first > curMs) idx = 0
        while (idx + 1 < lyricLines.size && lyricLines[idx + 1].first <= curMs) idx++
        if (idx == lyricIdx) return
        lyricIdx = idx
        val cur = lyricLines[idx].second
        val prev = if (idx > 0) lyricLines[idx - 1].second else ""
        val next = if (idx + 1 < lyricLines.size) lyricLines[idx + 1].second else ""
        val span = android.text.SpannableStringBuilder()
        if (prev.isNotEmpty()) {
            span.append(prev + "\n")
            span.setSpan(android.text.style.ForegroundColorSpan(0xFF55544F.toInt()), 0, span.length, 0)
            span.setSpan(android.text.style.RelativeSizeSpan(0.8f), 0, span.length, 0)
        }
        val curStart = span.length
        span.append(cur)
        span.setSpan(android.text.style.ForegroundColorSpan(0xFFF2F4F7.toInt()), curStart, span.length, 0)
        span.setSpan(android.text.style.RelativeSizeSpan(1.1f), curStart, span.length, 0)
        span.setSpan(android.text.style.StyleSpan(Typeface.BOLD), curStart, span.length, 0)
        if (next.isNotEmpty()) {
            val nextStart = span.length
            span.append("\n" + next)
            span.setSpan(android.text.style.ForegroundColorSpan(0xFF55544F.toInt()), nextStart, span.length, 0)
            span.setSpan(android.text.style.RelativeSizeSpan(0.8f), nextStart, span.length, 0)
        }
        lyricView.text = span
    }

    private fun pointerSpan(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
    }

    private fun onMapTouch(view: View, event: MotionEvent): Boolean {
        if (menuOpen) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureSequenceActive = false
                gestureTracking = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == gestureFingerCount && !gestureSequenceActive) {
                    gestureSequenceActive = true
                    gestureTracking = true
                    gestureStartX = pointerCentroid(event, horizontal = true)
                    gestureStartY = pointerCentroid(event, horizontal = false)
                    return true
                }
            }
        }

        if (gestureSequenceActive) {
            if (event.actionMasked == MotionEvent.ACTION_POINTER_UP || event.pointerCount != gestureFingerCount) {
                gestureTracking = false
            }
            if (!gestureTracking || event.pointerCount != gestureFingerCount) {
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    gestureSequenceActive = false
                    gestureTracking = false
                } else if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                    gestureTracking = false
                }
                return true
            }
            if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                val deltaX = Math.abs(pointerCentroid(event, horizontal = true) - gestureStartX)
                val deltaY = pointerCentroid(event, horizontal = false) - gestureStartY
                if (
                    deltaY >= dp(SETTINGS_SWIPE_DISTANCE_DP) &&
                    deltaY >= deltaX * SETTINGS_SWIPE_DIRECTION_RATIO
                ) {
                    gestureSequenceActive = false
                    gestureTracking = false
                    openSettingsMenu()
                    return true
                }
            }
            return true
        }

        // Two-finger pinch toggles dashboard mode and is consumed here —
        // never forwarded. Spread (ratio > 1.18) expands the map back to map
        // mode; pinch-in (ratio < 0.85) opens the music mode. Symmetric.
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount == 2 && !pinchActive) {
                pinchActive = true
                pinchStartSpan = pointerSpan(event)
                return true
            }
            MotionEvent.ACTION_MOVE -> if (pinchActive && event.pointerCount >= 2) {
                pinchCurSpan = pointerSpan(event)
                return true
            }
        }
        if (pinchActive) {
            if (event.actionMasked == MotionEvent.ACTION_POINTER_UP ||
                event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                if (pinchStartSpan > 0 && pinchCurSpan > 0) {
                    val ratio = pinchCurSpan / pinchStartSpan
                    if (ratio > 1.18f) {
                        if (screenMode == MODE_DASH) applyScreenMode(MODE_MAP)
                        else if (screenMode == MODE_MUSIC) applyScreenMode(MODE_DASH)
                    } else if (ratio < 0.85f) {
                        if (screenMode == MODE_DASH) applyScreenMode(MODE_MUSIC)
                        else if (screenMode == MODE_MAP) applyScreenMode(MODE_DASH)
                    }
                }
                pinchActive = false
                pinchStartSpan = 0f
                pinchCurSpan = 0f
            }
            return true
        }

        val content = videoRect
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            // Touch events arrive in TextureView coordinates; videoRect is in
            // pane coordinates, so shift by the view's offset inside the pane.
            val offsetX = textureView.translationX + textureView.left
            val offsetY = textureView.translationY + textureView.top
            if (!content.contains(event.x + offsetX, event.y + offsetY)) return true
        }
        val e = engine ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                forwardTouch(e, CarLife.TOUCH_ACTION_DOWN, event, event.actionIndex)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL ->
                forwardTouch(e, CarLife.TOUCH_ACTION_UP, event, event.actionIndex)
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    forwardTouch(e, CarLife.TOUCH_ACTION_MOVE, event, i)
                }
            }
        }
        return true
    }

    private fun pointerCentroid(event: MotionEvent, horizontal: Boolean): Float {
        var total = 0f
        for (index in 0 until event.pointerCount) {
            total += if (horizontal) event.getX(index) else event.getY(index)
        }
        return total / event.pointerCount
    }

    // ---- status surface ----

    private fun friendlyStage(message: String): String = when {
        message.contains("ECONNREFUSED", true) ||
            message.contains("ConnectException", true) ->
            getString(R.string.stage_waiting_for_phone)
        message.contains("timeout", true) || message.contains("timed out", true) ->
            getString(R.string.stage_waiting_for_phone)
        message.contains("NoRouteToHost", true) || message.contains("unreachable", true) ->
            getString(R.string.stage_same_network_hint)
        message.contains("denied", true) || message.contains("permission", true) ->
            getString(R.string.state_scan_hint)
        message.contains("reset", true) ->
            getString(R.string.state_reconnecting, "")
        message.contains("Failed", true) || message.contains("error", true) ->
            getString(R.string.connection_interrupted_retrying)
        message.contains("reconnect", true) || message.contains("ended", true) ->
            getString(R.string.state_reconnecting, "")
        else -> getString(R.string.carlife_connecting)
    }

    private fun setStage(stage: String, instructions: String? = null) {
        if (stage.contains("Exception", true) || stage.contains("failed", true) ||
            stage.contains("refused", true)
        ) {
            CarLifeSessionLog.log(this, "STAGE", stage)
            stageStatusView?.text = friendlyStage(stage)
        } else {
            stageStatusView?.text = stage
        }
        if (instructions != null) instructionsView?.text = instructions
        viewport?.visibility = View.VISIBLE
    }

    private fun hideWaiting() {
        viewport?.visibility = View.GONE
    }

    private fun toggleMediaPlay() {
        mediaPlaying = !mediaPlaying
        engine?.sendHardKey(if (mediaPlaying) CarLife.HardKey.MEDIA_START else CarLife.HardKey.MEDIA_STOP)
        engine?.setMediaPlaying(mediaPlaying)
        setPlayGlyph(mediaPlaying)
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

    /**
     * Fits the map stream inside the left pane by sizing the TextureView to
     * the stream's aspect (fill = crop overflow, DiPlay style). Layout-based
     * fitting instead of TextureView.setTransform: the transform does not
     * compose reliably with the HARDWARE layer the picture filter needs.
     */
    private fun applyVideoTransform() {
        val paneW = mapPane.width
        val paneH = mapPane.height
        if (paneW == 0 || paneH == 0 || phoneWidth == 0 || phoneHeight == 0) return
        val scale = maxOf(paneW.toFloat() / phoneWidth, paneH.toFloat() / phoneHeight)
        val tvW = (phoneWidth * scale).toInt()
        val tvH = (phoneHeight * scale).toInt()
        val lp = textureView.layoutParams as? FrameLayout.LayoutParams
        if (lp == null || lp.width != tvW || lp.height != tvH) {
            textureView.layoutParams = FrameLayout.LayoutParams(
                tvW, tvH, Gravity.CENTER,
            )
        }
        // Map-pane coordinates of the visible stream area (may exceed the
        // pane where the stream is cropped).
        val left = (paneW - tvW) / 2
        val top = (paneH - tvH) / 2
        videoRect.set(left.toFloat(), top.toFloat(), (left + tvW).toFloat(), (top + tvH).toFloat())
    }

    private fun applyPicture() {
        if (!::textureView.isInitialized) return
        // The left pane runs the dark-map filter permanently so the light
        // CarLife canvas blends into the dark shell.
        textureView.setLayerType(View.LAYER_TYPE_HARDWARE, CarLifePicture.darkMapPaint(picture))
    }

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
            paintWaitingScreen?.invoke()
            CarLifeSessionLog.log(this, "NIGHT", "lux=$lux night=$night")
        }
    }

    /**
     * Two speed sources feed the cluster (the unit's own GPS and the phone's
     * CarLife GPS). Publishing whichever updated last stops the readout from
     * flicking between two different values.
     */
    private fun renderSpeed() {
        val kmh = when (CarLifePersistence.get(this).speedSource()) {
            CarLifePersistence.SPEED_SOURCE_UNIT_GPS -> unitKmh
            CarLifePersistence.SPEED_SOURCE_PHONE_GPS -> phoneKmh
            // Automatic: publish whichever feed updated last so the readout
            // cannot flick between two different values.
            else -> if (unitKmh >= 0 && (unitGpsAt >= lastGpsAt || phoneKmh < 0)) unitKmh else phoneKmh
        }
        statusSpeed.text = if (kmh >= 0) "$kmh km/h" else "-- km/h"
        statusGear.text = when {
            kmh < 0 -> "--"
            kmh == 0 -> "P"
            else -> "D"
        }
    }

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
        setStage(getString(R.string.carlife_searching_phone))
        SubnetScanner.findPhone(
            onFound = { host ->
                main.post {
                    if (sessionAlive) return@post
                    setStage(getString(R.string.carlife_phone_found, host.hostAddress))
                    engine?.stop()
                    engine = null
                    startEngine(currentSurface(), CarLifeEngine.Mode.WIRELESS, host)
                }
            },
            onDone = { main.post { setStage(getString(R.string.carlife_phone_not_found)) } },
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
                setStage(getString(R.string.carlife_phone_found, host.hostAddress))
                startEngine(currentSurface(), CarLifeEngine.Mode.WIRELESS, host)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startEngine(
        surface: Surface?,
        mode: CarLifeEngine.Mode = CarLifeEngine.Mode.WIRED,
        wirelessHost: java.net.InetAddress? = null,
        usePanelSize: Boolean = false,
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
                        phoneLabel = name
                        CarLifePersistence.get(this@CarLifeVideoActivity).saveLastPhone(name)
                        updateStatusLine()
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
                        val key = "${info.song}|${info.artist}|${info.albumArt.size}"
                        if (key == lastMediaKey) return@post
                        lastMediaKey = key
                        if (info.song.isBlank() && info.artist.isBlank()) {
                            mTitle.text = getString(R.string.widget_no_media)
                            mArtist.text = ""
                            modeTitle.text = getString(R.string.widget_no_media)
                            modeArtist.text = ""
                            mProgress.progress = 0
                            modeProgress.progress = 0
                            slimProgress.progress = 0
                            mCover.setImageDrawable(null)
                            modeCover.setImageDrawable(null)
                            return@post
                        }
                        lastMediaDuration = info.duration
                        localPlaySec = 0
                        val cover = info.albumArt.takeIf { it.isNotEmpty() }?.let { bytes ->
                            runCatching {
                                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            }.getOrNull()
                        }
                        val song = info.song.ifBlank { getString(R.string.media_unknown_song) }
                        val artist = info.artist.ifBlank { getString(R.string.media_unknown_artist) }
                        mTitle.text = song
                        // source can decode to a numeric id on some phone
                        // builds — showing it looked like a broken artist.
                        mArtist.text = artist
                        modeTitle.text = song
                        modeArtist.text = artist
                        slimTitleView.text = "$song · $artist"
                        loadLyricsIfChanged(song, artist)
                        if (cover != null) {
                            mCover.setImageBitmap(cover)
                            modeCover.setImageBitmap(cover)
                            // card background tinted by the cover's average color
                            runCatching {
                                val one = android.graphics.Bitmap.createScaledBitmap(cover, 1, 1, true)
                                val c = one.getPixel(0, 0)
                                one.recycle()
                                val dim = android.graphics.Color.rgb(
                                    android.graphics.Color.red(c) * 45 / 100,
                                    android.graphics.Color.green(c) * 45 / 100,
                                    android.graphics.Color.blue(c) * 45 / 100,
                                )
                                mCardBg.setColor(dim)
                            }
                        }
                        if (!mediaPlaying) {
                            mediaPlaying = true
                            engine?.setMediaPlaying(true)
                            setPlayGlyph(true)
                        }
                    }
                }

                override fun onMediaProgress(percent: Int) {
                    main.post {
                        mProgress.progress = percent
                        modeProgress.progress = percent
                        slimProgress.progress = percent
                        if (lastMediaDuration > 0) {
                            val totalSec =
                                if (lastMediaDuration > 10_000) lastMediaDuration / 1000
                                else lastMediaDuration
                            localPlaySec = totalSec * percent / 100
                            fun mmss(sec: Int) = "%d:%02d".format(sec / 60, sec % 60)
                            modeTimes.text = "${mmss(localPlaySec)} / ${mmss(totalSec)}"
                            syncLyrics(localPlaySec * 1000L)
                        }
                    }
                }

                override fun onCarGps(gps: CarLifeMessages.CarGps) {
                    main.post {
                        lastGpsAt = System.currentTimeMillis()
                        // trip accumulation (haversine, moving only)
                        val lat = gps.latitude / 1e7
                        val lng = gps.longitude / 1e7
                        if (!tripAnchorValid && gps.fix > 0) {
                            lastTripLat = lat; lastTripLng = lng
                            tripStartAt = System.currentTimeMillis()
                            tripAnchorValid = true
                        } else if (tripAnchorValid && gps.speedCms > 30) {
                            val r = 6371e3
                            val dLat = Math.toRadians(lat - lastTripLat)
                            val dLng = Math.toRadians(lng - lastTripLng)
                            val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                                Math.cos(Math.toRadians(lastTripLat)) *
                                Math.cos(Math.toRadians(lat)) *
                                Math.sin(dLng / 2) * Math.sin(dLng / 2)
                            tripMeters += (r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))).toFloat()
                            lastTripLat = lat; lastTripLng = lng
                        }
                        if (tripAnchorValid) {
                            tripKmText.text = String.format("%.1f", tripMeters / 1000f)
                            val mins = (System.currentTimeMillis() - tripStartAt) / 60000
                            tripDurText.text = " km · ${mins}分钟"
                        }
                        gpsText.text =
                            if (gps.fix > 0) "GPS·${gps.satsUsed}星 LOCK" else "GPS·----"
                        if (gps.fix > 0 && !weatherFetched && gps.latitude != 0) {
                            weatherFetched = true
                            fetchWeather(lat, lng)
                        }
                        phoneKmh = (gps.speedCms * 36.0 / 1000.0).roundToInt()
                        renderSpeed()
                        CarLifeSessionLog.log(
                            this@CarLifeVideoActivity, "GPS",
                            "phone=${phoneKmh}kmh sats=${gps.satsUsed} fix=${gps.fix} head=${gps.heading}",
                        )
                    }
                }

                override fun onNaviInfo(info: CarLifeMessages.NaviNextTurnInfo) {
                    main.post {
                        val prefs = CarLifePersistence.get(this@CarLifeVideoActivity)
                        if (info.roadName.isBlank() && info.remainDistance <= 0) {
                            naviActive = false
                            if (prefs.naviDuckMusic()) engine?.duckForNavi(false)
                            refreshNaviPill()
                            return@post
                        }
                        val starting = !naviActive
                        naviActive = true
                        if (starting && prefs.naviDuckMusic()) engine?.duckForNavi(true)
                        // CarPlay behaviour: when guidance starts, the map takes
                        // the whole screen automatically — unless the driver
                        // turned that off in navigation settings.
                        if (starting && screenMode == MODE_DASH && prefs.naviAutoMap()) {
                            applyScreenMode(MODE_MAP)
                        }
                        stripDist.text =
                            if (info.remainDistance > 0) "${info.remainDistance}米" else ""
                        stripRoad.text = info.roadName
                        stripArrow.text = turnArrow(info.nextTurn)
                        naviPillText?.text = buildString {
                            append("↰ ")
                            if (info.remainDistance > 0) append(info.remainDistance).append("米 ")
                            append(info.roadName)
                        }
                        refreshNaviPill()
                    }
                }            },
        ).also {
            it.start(mode, wirelessHost, usePanelSize)
            CarLifeSessionService.start(applicationContext)
        }
    }

    /** CarLife turn codes → arrow glyph; unknown codes fall back to straight. */
    private fun turnArrow(turn: Int): String = when (turn) {
        1, 3 -> "⬅"
        2, 4 -> "➡"
        7, 8 -> "⤴"
        9, 10 -> "⤵"
        else -> "↑"
    }

    private fun onState(state: String, detail: String?) {
        Log.i(TAG, "state=$state detail=$detail")
        sessionAlive = state == "connected" || state == "wireless-connecting" ||
            (state.startsWith("phase:") && state != "phase:idle")
        when {
            state.startsWith("phase:streaming") || state == "connected" -> {
                scanHintShown = false
                hideWaiting()
                // The stream mirrors whatever the phone is showing. Command
                // the phone into its map right after the session comes up so
                // the head unit opens on navigation, not on the last app.
                if (!mapAutoLaunched) {
                    mapAutoLaunched = true
                    engine?.sendLaunchMode(CarLife.Cmd.LAUNCH_MODE_MAP)
                }
            }

            state == "disconnected" || state == "closed" || state == "stopped" -> {
                setStage(getString(R.string.carlife_disconnected, detail ?: ""))
                startupRetryButton?.visibility = View.VISIBLE
                mapAutoLaunched = false
                if (screenMode != MODE_DASH) applyScreenMode(MODE_DASH)
            }

            state == "scanning" -> {
                setStage(getString(R.string.state_scanning))
                if (!scanHintShown) {
                    scanHintShown = true
                    main.postDelayed({
                        if (viewport?.visibility == View.VISIBLE) {
                            instructionsView?.text = getString(R.string.state_scanning) +
                                "\n" + getString(R.string.state_scan_hint)
                        }
                    }, 20_000)
                }
            }

            state == "wireless" || state == "wireless-connecting" ->
                setStage(getString(R.string.state_wireless_connecting, detail ?: ""))

            state == "reconnecting" -> {
                setStage(getString(R.string.state_reconnecting, detail ?: ""))
                startupRetryButton?.visibility = View.VISIBLE
            }

            state == "watchdog" -> setStage(getString(R.string.state_watchdog))
            else -> setStage(state + (detail?.let { " — $it" } ?: ""))
        }
        updateStatusLine()
    }

    // ---- input ----

    /** Forward one pointer's coordinates as a CarLife touch action. */
    private fun forwardTouch(e: CarLifeEngine, action: Int, event: MotionEvent, i: Int) {
        val offsetX = textureView.left.toFloat()
        val offsetY = textureView.top.toFloat()
        val px = event.getX(i) + offsetX
        val py = event.getY(i) + offsetY
        if (!videoRect.contains(px, py)) return
        val x = ((px - videoRect.left) / videoRect.width().coerceAtLeast(1f) * phoneWidth).toInt().coerceIn(0, phoneWidth - 1)
        val y = ((py - videoRect.top) / videoRect.height().coerceAtLeast(1f) * phoneHeight).toInt().coerceIn(0, phoneHeight - 1)
        e.sendTouch(action, x, y)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (menuOpen) {
            closeSettingsMenu()
            return
        }
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

    /** Reflects a real Bluetooth audio route in the status bar; hides the
     *  label entirely when absent — never show an invented "connected". */
    private fun refreshBluetooth() {
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        btConnected = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
        updateStatusLine()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(batteryReceiver) }
        runCatching {
            audioDeviceCallback?.let {
                (getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager)
                    .unregisterAudioDeviceCallback(it)
            }
        }
        audioDeviceCallback = null
        ambientLight?.stop()
        ambientLight = null
        unitGps?.stop()
        unitGps = null
        engine?.stop()
        engine = null
        CarLifeSessionService.stop(this)
        super.onDestroy()
    }

    private fun space(w: Int = 10): View = View(this).also {
        it.layoutParams = LinearLayout.LayoutParams(dp(w), 1)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    companion object {
        private const val TAG = "CarLifeVideo"

        /** DiPlay's settings swipe: distance and vertical-dominance ratio. */
        private const val MODE_DASH = 0
        private const val MODE_MAP = 1
        private const val MODE_MUSIC = 2
        private const val SETTINGS_SWIPE_DISTANCE_DP = 72
        private const val SETTINGS_SWIPE_DIRECTION_RATIO = 1.15f
    }
}
