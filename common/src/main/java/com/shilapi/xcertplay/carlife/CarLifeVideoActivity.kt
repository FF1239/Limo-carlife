package com.shilapi.xcertplay.carlife

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.diplay.carlife.core.CarLife
import com.diplay.carlife.core.CarLifeMessages
import com.shilapi.xcertplay.host.R

/**
 * Full-screen CarLife projection surface. Touch events are forwarded to the
 * phone (passthrough down/move/up as the reference docs require: no blocking in
 * the sender), back/hard keys map to CarLife keycodes. Mirrors the CarPlay host
 * UX: immersive, black, with a status toast until the first video frame.
 */
class CarLifeVideoActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var engine: CarLifeEngine? = null
    private lateinit var status: TextView
    private lateinit var surfaceView: SurfaceView
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

        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                startEngine(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                engine?.stop()
                engine = null
            }
        })

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

        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                surfaceView,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER),
            )
            addView(
                wirelessButton,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                    bottomMargin = 24.dp
                },
            )
            addView(
                status,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
            )
        })
        showStatus(getString(R.string.carlife_connecting))
    }

    /** Wireless: find the phone's CarLife TCP ports on the local network. */
    private var scanThread: Thread? = null

    private val wirelessButton = android.widget.Button(this).apply {
        text = getString(R.string.carlife_wireless_connect)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = 20.dp.toFloat()
            setColor(0x80000000.toInt())
        }
        setPadding(24.dp, 10.dp, 24.dp, 10.dp)
        setOnClickListener {
            showStatus(getString(R.string.carlife_searching_phone))
            scanThread = SubnetScanner.findPhone(
                onFound = { host ->
                    main.post {
                        showStatus(getString(R.string.carlife_phone_found, host.hostAddress))
                        engine?.stop()
                        engine = null
                        startEngine(holderSurface(), CarLifeEngine.Mode.WIRELESS, host)
                    }
                },
                onDone = { main.post { showStatus(getString(R.string.carlife_phone_not_found)) } },
            )
        }
    }

    private fun holderSurface(): Surface? = if (surfaceView.holder.surface?.isValid == true) surfaceView.holder.surface else null

    private fun startEngine(
        surface: Surface?,
        mode: CarLifeEngine.Mode = CarLifeEngine.Mode.WIRED,
        wirelessHost: java.net.InetAddress? = null,
    ) {
        if (engine != null) return
        engine = CarLifeEngine(applicationContext, surface, object : CarLifeEngine.UiListener {
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
                }
            }
        }).also { it.start(mode, wirelessHost) }
    }

    private fun onState(state: String, detail: String?) {
        Log.i(TAG, "state=$state detail=$detail")
        when {
            state.startsWith("phase:streaming") || state == "connected" -> showStatus(getString(R.string.carlife_projecting))
            state == "disconnected" || state == "closed" ->
                showStatus(getString(R.string.carlife_disconnected, detail ?: ""))
            state == "stopped" -> finish()
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
        // Scale this display's touch coordinates onto the negotiated phone canvas.
        val x = (event.x / surfaceView.width.coerceAtLeast(1) * phoneWidth).toInt().coerceIn(0, phoneWidth - 1)
        val y = (event.y / surfaceView.height.coerceAtLeast(1) * phoneHeight).toInt().coerceIn(0, phoneHeight - 1)
        e.sendTouch(action, x, y)
        return true
    }

    override fun onBackPressed() {
        // Back drives the phone, not this activity (DiPlay behaviour parity).
        engine?.sendHardKey(CarLife.HardKey.BACK)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        engine?.stop()
        engine = null
        super.onDestroy()
    }

    private fun Float.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "CarLifeVideo"
        private const val STATUS_SHORT_MS = 2500L
    }
}
