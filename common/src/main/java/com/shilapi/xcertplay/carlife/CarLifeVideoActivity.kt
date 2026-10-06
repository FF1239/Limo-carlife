package com.shilapi.xcertplay.carlife

import android.app.Activity
import android.graphics.Color
import android.graphics.SurfaceTexture
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
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.diplay.carlife.core.CarLife
import com.diplay.carlife.core.CarLifeMessages
import com.shilapi.xcertplay.host.R

/**
 * Full-screen CarLife projection with DiPlay-parity picture controls:
 * TextureView rendering (live brightness/contrast/saturation/warmth/night via
 * ColorMatrix), a floating settings panel, mic uplink, and the foreground
 * session service. Touch and hard keys pass through to the phone.
 */
class CarLifeVideoActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var engine: CarLifeEngine? = null
    private lateinit var status: TextView
    private lateinit var hudCard: TextView
    private lateinit var textureView: TextureView
    private lateinit var settingsPanel: CarLifeSettingsPanel
    private val picture = CarLifePicture.Settings()
    private var phoneWidth = CarLife.DEFAULT_VIDEO_WIDTH
    private var phoneHeight = CarLife.DEFAULT_VIDEO_HEIGHT

    private val hideStatus = Runnable { status.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val persistence = CarLifePersistence.get(this)

        textureView = TextureView(this).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    st.setDefaultBufferSize(phoneWidth, phoneHeight)
                    startEngine(Surface(st))
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
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

        settingsPanel = CarLifeSettingsPanel(
            this,
            persistence,
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

        val settingsButton = Button(this).apply {
            text = getString(R.string.carlife_settings_button)
            textSize = 13f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = 20.dp.toFloat()
                setColor(0x80000000.toInt())
            }
            setPadding(24.dp, 10.dp, 24.dp, 10.dp)
            setOnClickListener {
                settingsPanel.visibility =
                    if (settingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }

        val wirelessButton = Button(this).apply {
            text = getString(R.string.carlife_wireless_connect)
            textSize = 13f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = 20.dp.toFloat()
                setColor(0x80000000.toInt())
            }
            setPadding(24.dp, 10.dp, 24.dp, 10.dp)
            setOnClickListener {
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
        }

        val manualIpButton = Button(this).apply {
            text = getString(R.string.carlife_manual_ip_button)
            textSize = 13f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = 20.dp.toFloat()
                setColor(0x80000000.toInt())
            }
            setPadding(24.dp, 10.dp, 24.dp, 10.dp)
            setOnClickListener { promptForIp() }
        }

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = 24.dp.toFloat()
                setColor(0xB3000000.toInt())
            }
            setPadding(32.dp, 16.dp, 32.dp, 16.dp)
        }

        hudCard = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 2
            background = GradientDrawable().apply {
                cornerRadius = 16.dp.toFloat()
                setColor(0x99000000.toInt())
            }
            setPadding(20.dp, 10.dp, 20.dp, 10.dp)
        }

        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                textureView,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER),
            )
            addView(
                LinearLayout(this@CarLifeVideoActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    setPadding(0, 0, 0, 16.dp)
                    addView(settingsButton)
                    addView(View(this@CarLifeVideoActivity), FrameLayout.LayoutParams(12.dp, 1))
                    addView(wirelessButton)
                    addView(View(this@CarLifeVideoActivity), FrameLayout.LayoutParams(12.dp, 1))
                    addView(manualIpButton)
                },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
            )
            addView(
                settingsPanel,
                FrameLayout.LayoutParams(
                    (resources.displayMetrics.widthPixels * 0.62f).toInt().coerceAtLeast(360.dp),
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
            addView(
                hudCard,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                ).apply { topMargin = 16.dp },
            )
            addView(
                status,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
            )
        })
        showStatus(getString(R.string.carlife_connecting))

        // FGS microphone type requires RECORD_AUDIO granted at runtime.
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1)
        }

        // Automated/external launches: intent extra "host" connects immediately.
        intent.getStringExtra("host")?.let { host ->
            runCatching {
                java.net.InetAddress.getByName(host)
            }.getOrNull()?.let { addr ->
                main.postDelayed({
                    engine?.stop()
                    engine = null
                    showStatus(getString(R.string.carlife_phone_found, addr.hostAddress))
                    startEngine(currentSurface(), CarLifeEngine.Mode.WIRELESS, addr)
                }, 800)
            }
        }
    }

    /** Manual target: same-phone self-test uses 127.0.0.1 (local CarLife). */
    private fun promptForIp() {
        val input = android.widget.EditText(this).apply {
            setText("127.0.0.1")
            hint = "192.168.x.x / 127.0.0.1"
            setSingleLine()
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply {
            setPadding(pad, pad, pad, 0)
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

    private fun applyPicture() {
        val mode = CarLifePersistence.get(this).dayNightMode()
        val night = CarLifePicture.isNight(mode, picture.night)
        // Color filter applies through the view's hardware layer (TextureView
        // has no setPaint; setLayerPaint on a hardware layer is the standard).
        textureView.setLayerType(View.LAYER_TYPE_HARDWARE, CarLifePicture.paint(picture.copy(night = night)))
    }

    private fun currentSurface(): Surface? =
        textureView.surfaceTexture?.let { texture -> Surface(texture) }

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
                        Log.i(TAG, "phone: ${info.manufacturer} ${info.model} (android ${info.release})")
                    }
                }

                override fun onEncoderSize(width: Int, height: Int) {
                    main.post {
                        phoneWidth = width
                        phoneHeight = height
                        persistence.saveNegotiated(width, height)
                    }
                }

                override fun onMediaInfo(info: CarLifeMessages.MediaInfo) {
                    main.post {
                        hudCard.visibility = View.VISIBLE
                        hudCard.text = if (info.song.isNotEmpty()) {
                            "${info.artist} — ${info.song}".trim(' ', '—')
                        } else {
                            ""
                        }
                    }
                }

                override fun onNaviInfo(info: CarLifeMessages.NaviNextTurnInfo) {
                    main.post {
                        if (info.roadName.isNotEmpty() || info.remainDistance > 0) {
                            hudCard.visibility = View.VISIBLE
                            hudCard.text = "导航:${info.roadName} · 剩余 ${info.remainDistance}m"
                        }
                    }
                }
            },
        ).also {
            it.start(mode, wirelessHost)
            if (persistence.autoConnect()) CarLifeSessionService.start(this)
        }
    }

    private fun onState(state: String, detail: String?) {
        Log.i(TAG, "state=$state detail=$detail")
        when {
            state.startsWith("phase:streaming") || state == "connected" ->
                showStatus(getString(R.string.carlife_projecting))
            state == "disconnected" || state == "closed" ->
                showStatus(getString(R.string.carlife_disconnected, detail ?: ""))
            state == "stopped" -> Unit
            state.startsWith("reconnecting") ->
                showStatus(getString(R.string.carlife_state, state + (detail?.let { " — $it" } ?: "")))
            else -> showStatus(getString(R.string.carlife_state, state + (detail?.let { " — $it" } ?: "")))
        }
    }

    private fun showStatus(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
        main.removeCallbacks(hideStatus)
        if (text == getString(R.string.carlife_projecting)) {
            main.postDelayed(hideStatus, STATUS_SHORT_MS)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val e = engine ?: return super.onTouchEvent(event)
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> CarLife.TOUCH_ACTION_DOWN
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> CarLife.TOUCH_ACTION_UP
            MotionEvent.ACTION_MOVE -> CarLife.TOUCH_ACTION_MOVE
            else -> return super.onTouchEvent(event)
        }
        val x = (event.x / textureView.width.coerceAtLeast(1) * phoneWidth).toInt().coerceIn(0, phoneWidth - 1)
        val y = (event.y / textureView.height.coerceAtLeast(1) * phoneHeight).toInt().coerceIn(0, phoneHeight - 1)
        e.sendTouch(action, x, y)
        return true
    }

    override fun onBackPressed() {
        engine?.sendHardKey(CarLife.HardKey.BACK)
    }

    /**
     * Physical/steering-wheel keys → CarLife hard-key codes (0x01–0x31).
     * On real head units wheel buttons arrive as KeyEvents on the foreground
     * activity; mapping them here gives wheel control car-agnostically.
     */
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
        engine?.stop()
        engine = null
        CarLifeSessionService.stop(this)
        super.onDestroy()
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "CarLifeVideo"
        private const val STATUS_SHORT_MS = 2500L
    }
}
