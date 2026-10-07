package com.shilapi.xcertplay.carlife

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Shared carbon/track-red palette for the CarLife channel UI. */
object CarbonTheme {
    val BG = Color.parseColor("#0F1114")
    val PANEL = Color.parseColor("#17191C")
    val PANEL_DEEP = Color.parseColor("#0C0D0F")
    val HAIRLINE = Color.parseColor("#2A2E33")
    val RED = Color.parseColor("#E1062C")
    val TEXT = Color.parseColor("#E8EAEE")
    val TEXT_DIM = Color.parseColor("#9AA0A8")
    val TEXT_FAINT = Color.parseColor("#585F68")
    val ONLINE = Color.parseColor("#2EE06C")

    fun hairlinePanel(context: Context): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 0f
            setStroke((1 * context.resources.displayMetrics.density).toInt(), HAIRLINE)
            setColor(PANEL)
        }
}

/**
 * The RPM/speed ring from the instrument design: outer tick ring (lit
 * proportionally to speed), red sweep arc with glow, needle dot, centre
 * readout (speed, km/h, gear·rpm). Standby shows 000 / STANDBY.
 */
class GaugeView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var speed = 0
    private var gear = "—"
    private var rpm = 0
    private var standby = true

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.parseColor("#23262B"); strokeWidth = 2f
    }
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.parseColor("#2A2E33"); strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(6f, 10f), 0f)
    }
    private val arcTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.parseColor("#1B1E22"); strokeWidth = 22f
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = CarbonTheme.RED; strokeWidth = 22f
        setShadowLayer(18f, 0f, 0f, Color.parseColor("#66E1062C"))
    }
    private val tickLit = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CarbonTheme.RED }
    private val tickDim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3A4046") }
    private val needleGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CarbonTheme.RED; setShadowLayer(14f, 0f, 0f, CarbonTheme.RED)
    }
    private val needle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val speedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CarbonTheme.RED; typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CarbonTheme.TEXT_FAINT; typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CarbonTheme.RED; typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER
    }
    private val arcRect = RectF()

    fun setGauge(speedKmh: Int, gearText: String, rpm: Int, isStandby: Boolean) {
        speed = speedKmh
        gear = gearText
        this.rpm = rpm
        standby = isStandby
        speedPaint.color = if (isStandby) CarbonTheme.RED else Color.WHITE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f - dp(10f)

        // base rings
        canvas.drawCircle(cx, cy, radius, ringPaint)
        canvas.drawCircle(cx, cy, radius - dp(11f), dashPaint)

        // 12 major ticks; lit proportionally to speed (0..240)
        val fraction = speed.coerceIn(0, 240) / 240f
        val litCount = (fraction * 12).toInt()
        for (i in 0 until 12) {
            val angle = Math.toRadians((i * 30 - 90).toDouble())
            val outer = radius + dp(3f)
            val len = dp(13f)
            canvas.drawLine(
                (cx + cos(angle) * outer).toFloat(),
                (cy + sin(angle) * outer).toFloat(),
                (cx + cos(angle) * (outer - len)).toFloat(),
                (cy + sin(angle) * (outer - len)).toFloat(),
                if (!standby && i < litCount) tickLit else tickDim,
            )
        }

        // 270° sweep arc (from top, clockwise)
        val arcRadius = radius - dp(16f)
        arcRect.set(cx - arcRadius, cy - arcRadius, cx + arcRadius, cy + arcRadius)
        val sweep = if (standby) 8f else fraction * 270f
        canvas.drawArc(arcRect, -90f, 270f, false, arcTrack)
        if (sweep > 1f) canvas.drawArc(arcRect, -90f, sweep, false, arcPaint)

        // needle dot at arc end
        if (!standby && sweep > 2f) {
            val endAngle = Math.toRadians((-90 + sweep).toDouble())
            canvas.drawCircle(
                (cx + cos(endAngle) * arcRadius).toFloat(),
                (cy + sin(endAngle) * arcRadius).toFloat(),
                dp(4.5f), needleGlow,
            )
            canvas.drawCircle(
                (cx + cos(endAngle) * arcRadius).toFloat(),
                (cy + sin(endAngle) * arcRadius).toFloat(),
                dp(3.2f), needle,
            )
        } else if (standby) {
            canvas.drawCircle(cx, cy - arcRadius, dp(4.5f), needleGlow)
        }

        // centre readout
        speedPaint.textSize = dp(if (standby) 44f else 58f)
        val ty = cy - dp(if (standby) 6f else 10f)
        canvas.drawText(if (standby) "000" else speed.toString(), cx, ty, speedPaint)
        unitPaint.textSize = dp(11f)
        canvas.drawText(if (standby) "RPM ×100" else "km/h", cx, ty + dp(24f), unitPaint)
        subPaint.textSize = dp(13f)
        canvas.drawText(
            if (standby) "CARLIFE · STANDBY" else "GEAR $gear · RPM $rpm",
            cx, ty + dp(46f), subPaint,
        )
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}

/** HUD corner bracket: an L drawn on canvas, corner chosen via enum. */
class BracketView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    var corner = Corner.TOP_LEFT
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CarbonTheme.RED; style = Paint.Style.STROKE
    }

    override fun onDraw(canvas: Canvas) {
        val stroke = 2f * resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()
        paint.strokeWidth = stroke
        when (corner) {
            Corner.TOP_LEFT -> {
                canvas.drawLine(0f, 0f, w, 0f, paint)
                canvas.drawLine(0f, 0f, 0f, h, paint)
            }
            Corner.TOP_RIGHT -> {
                canvas.drawLine(0f, 0f, w, 0f, paint)
                canvas.drawLine(w, 0f, w, h, paint)
            }
            Corner.BOTTOM_LEFT -> {
                canvas.drawLine(0f, h, w, h, paint)
                canvas.drawLine(0f, 0f, 0f, h, paint)
            }
            Corner.BOTTOM_RIGHT -> {
                canvas.drawLine(0f, h, w, h, paint)
                canvas.drawLine(w, 0f, w, h, paint)
            }
        }
    }
}

/** Bottom telemetry ticker row (LINK / SPEED / GEAR / AUDIO / CANVAS …). */
class TelemetryBar(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    private val cells = HashMap<String, TextView>()
    private val row: LinearLayout

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(CarbonTheme.PANEL_DEEP)
        val pad = (14 * resources.displayMetrics.density).toInt()
        setPadding(pad, 0, pad, 0)
        row = LinearLayout(context).apply { orientation = HORIZONTAL }
        addView(row, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    }

    /** Creates/updates a labelled cell, e.g. update("SPEED", "64 km/h", red = true). */
    fun update(key: String, value: String, red: Boolean = false, online: Boolean = false) {
        val tv = cells.getOrPut(key) {
            TextView(context).apply {
                typeface = Typeface.MONOSPACE
                textSize = 12f
                setPadding(0, 0, (24 * resources.displayMetrics.density).toInt(), 0)
                row.addView(this)
            }
        }
        val valueColor = when {
            online -> CarbonTheme.ONLINE
            red -> CarbonTheme.RED
            else -> CarbonTheme.TEXT
        }
        tv.text = span(key, CarbonTheme.TEXT_FAINT)
        tv.append(span(" · ", CarbonTheme.TEXT_FAINT))
        tv.append(span(value, valueColor))
    }

    private fun span(text: String, color: Int) = android.text.SpannableString(text).apply {
        setSpan(android.text.style.ForegroundColorSpan(color), 0, length, 0)
    }
}
