package com.shilapi.xcertplay.carlife

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CarLife-only home in the C1 Pro instrument language: carbon HUD frame with
 * corner brackets, brand bar + SYS clock, left hero (status chip, headline,
 * primary CTA, capability chips), right RPM standby ring, bottom telemetry
 * ticker. Deliberately unlike the previous rounded blue-card look.
 */
class CarLifeHomeActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private var statusChip: TextView? = null
    private var connectButton: View? = null
    private lateinit var gauge: GaugeView
    private lateinit var ticker: TelemetryBar
    private lateinit var clockView: TextView

    private val clockTick = object : Runnable {
        override fun run() {
            ticker.update(
                "SYS",
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
            )
            main.postDelayed(this, 15_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CarbonTheme.load(this)
        buildUi()
        main.post(clockTick)
        ticker.update("LINK", "OFFLINE", red = true)
        ticker.update("PHONE", CarLifePersistence.get(this).lastPhone() ?: "--- ---")
        ticker.update("AUDIO", "--- kHz")
        ticker.update("CANVAS", canvasLabel())

        if (intent?.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            openProjectionWhenAutoConnect()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Already-open home + a fresh USB plug-in: honour the auto-connect
        // setting here too (singleTop means onCreate won't run again).
        setIntent(intent)
        if (intent.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            openProjectionWhenAutoConnect()
        }
    }

    private fun openProjectionWhenAutoConnect() {
        if (CarLifePersistence.get(this).autoConnect()) {
            openProjection(wired = true)
        }
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(CarbonTheme.BG) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // corner HUD brackets
        root.addView(bracket(BracketView.Corner.TOP_LEFT))
        root.addView(bracket(BracketView.Corner.TOP_RIGHT))
        root.addView(bracket(BracketView.Corner.BOTTOM_LEFT))
        root.addView(bracket(BracketView.Corner.BOTTOM_RIGHT))

        // ---- brand bar
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(40), dp(16), dp(40), dp(16))
        }
        brand.addView(View(this).apply { setBackgroundColor(CarbonTheme.RED) },
            LinearLayout.LayoutParams(dp(4), dp(16)))
        brand.addView(TextView(this).apply {
            text = "CARLIFE-HU"
            setTextColor(CarbonTheme.TEXT)
            textSize = 14f
            letterSpacing = 0.22f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), 0, 0, 0)
        })
        brand.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        clockView = TextView(this).apply {
            text = "SYS --:--"
            setTextColor(CarbonTheme.TEXT_FAINT)
            textSize = 14f
            typeface = Typeface.MONOSPACE
        }
        brand.addView(clockView)
        column.addView(brand, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(View(this).apply { setBackgroundColor(CarbonTheme.HAIRLINE) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))

        // ---- main area: left hero, right ring
        val mainArea = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(56), dp(8), dp(56), dp(8))
        }

        val hero = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        statusChip = chip("STANDBY", CarbonTheme.RED)
        chipRow.addView(statusChip)
        chipRow.addView(label(getString(R.string.home_waiting_phone), CarbonTheme.TEXT_DIM, 12f, 2f).apply {
            setPadding(dp(14), 0, 0, 0)
        })
        hero.addView(chipRow, wrap())
        hero.addView(TextView(this).apply {
            text = getString(R.string.home_headline)
            setTextColor(Color.WHITE)
            textSize = 46f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(16), 0, 0)
        }, wrap())
        connectButton = cta(getString(R.string.home_connect)) { openProjection(wired = null) }
        hero.addView(connectButton, LinearLayout.LayoutParams(
            dp(400), dp(56)).apply { topMargin = dp(28) })
        val caps = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            getString(R.string.home_cap_video),
            getString(R.string.home_cap_audio),
            getString(R.string.home_cap_touch),
        ).forEachIndexed { i, cap ->
            if (i > 0) caps.addView(View(this), LinearLayout.LayoutParams(dp(12), 1))
            caps.addView(capChip(cap))
        }
        hero.addView(caps, wrap().apply { topMargin = dp(24) })
        hero.addView(label(getString(R.string.home_hint_wireless), CarbonTheme.TEXT_FAINT, 13f, 0f).apply {
            setPadding(0, dp(18), 0, 0)
        }, wrap())
        mainArea.addView(hero, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.08f))

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        gauge = GaugeView(this)
        gauge.setGauge(0, "—", 0, isStandby = true)
        right.addView(gauge, LinearLayout.LayoutParams(dp(270), dp(270)))
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        }
        actions.addAction(getString(R.string.home_usb)) { openProjection(wired = true) }
        actions.addAction(getString(R.string.home_wireless)) { openProjection(wired = false) }
        actions.addAction(getString(R.string.home_settings)) {
            startActivity(Intent(this, CarLifeSettingsActivity::class.java))
        }
        actions.addAction(getString(R.string.home_carplay)) {
            startActivity(Intent(this, com.shilapi.xcertplay.CarPlayHostActivity::class.java))
        }
        right.addView(actions, wrap().apply { topMargin = dp(20) })
        mainArea.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        column.addView(mainArea, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- telemetry ticker
        ticker = TelemetryBar(this)
        column.addView(ticker, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        setContentView(root)
    }

    // ---- widgets ----

    private fun bracket(corner: BracketView.Corner): View = BracketView(this).apply {
        this.corner = corner
        val gravity = when (corner) {
            BracketView.Corner.TOP_LEFT -> Gravity.TOP or Gravity.START
            BracketView.Corner.TOP_RIGHT -> Gravity.TOP or Gravity.END
            BracketView.Corner.BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.START
            BracketView.Corner.BOTTOM_RIGHT -> Gravity.BOTTOM or Gravity.END
        }
        layoutParams = FrameLayout.LayoutParams(
            (34 * resources.displayMetrics.density).toInt(),
            (34 * resources.displayMetrics.density).toInt(),
            gravity,
        ).apply {
            val m = (18 * resources.displayMetrics.density).toInt()
            setMargins(m, m, m, m)
        }
    }

    private fun chip(text: String, accent: Int): TextView = TextView(this).apply {
        this.text = text
        setTextColor(accent)
        textSize = 12f
        letterSpacing = 0.2f
        typeface = Typeface.MONOSPACE
        background = GradientDrawable().apply {
            cornerRadius = 0f
            setStroke(dp(1), accent)
            setColor(Color.TRANSPARENT)
        }
        setPadding(dp(10), dp(4), dp(10), dp(4))
    }

    private fun capChip(text: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = 0f
            setStroke(dp(1), CarbonTheme.HAIRLINE)
            setColor(Color.parseColor("#17191C"))
        }
        setPadding(dp(14), dp(8), dp(14), dp(8))
        addView(TextView(this@CarLifeHomeActivity).apply {
            this.text = "●"
            setTextColor(CarbonTheme.TEXT_FAINT)
            textSize = 9f
        })
        addView(TextView(this@CarLifeHomeActivity).apply {
            this.text = text
            setTextColor(CarbonTheme.TEXT_DIM)
            textSize = 13f
            letterSpacing = 0.14f
            setPadding(dp(8), 0, 0, 0)
        })
    }

    private fun cta(text: String, onClick: () -> Unit): View = FrameLayout(this).apply {
        background = GradientDrawable().apply {
            cornerRadius = 0f
            setColor(CarbonTheme.RED)
        }
        addView(TextView(this@CarLifeHomeActivity).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 17f
            letterSpacing = 0.22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun label(text: String, color: Int, size: Float, spacing: Float): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(color)
            textSize = size
            letterSpacing = spacing
        }

    private fun wrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun canvasLabel(): String {
        val p = CarLifePersistence.get(this)
        return "${p.videoWidth()}×${p.videoHeight()}"
    }

    private fun openProjection(wired: Boolean?) {
        val intent = Intent(this, CarLifeVideoActivity::class.java)
        when (wired) {
            true -> intent.putExtra("mode", "wired")
            false -> intent.putExtra("mode", "wireless")
            null -> if (CarLifePersistence.get(this).wirelessEnabled()) {
                intent.putExtra("mode", "wireless")
            }
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        main.removeCallbacks(clockTick)
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

/** Adds a skewed carbon action chip to a row. */
private fun LinearLayout.addAction(text: String, onClick: () -> Unit) {
    addView(FrameLayout(this.context).apply {
        background = GradientDrawable().apply {
            cornerRadius = 0f
            setStroke(
                (1 * context.resources.displayMetrics.density).toInt(), CarbonTheme.HAIRLINE)
            setColor(Color.parseColor("#1D2024"))
        }
        addView(TextView(context).apply {
            this.text = text
            setTextColor(CarbonTheme.TEXT)
            textSize = 13f
            letterSpacing = 0.14f
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }, LinearLayout.LayoutParams(0, (48 * context.resources.displayMetrics.density).toInt(), 1f).apply {
        marginEnd = (12 * context.resources.displayMetrics.density).toInt()
    })
}
