package com.shilapi.xcertplay.carlife

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Shared carbon/track-red palette for the CarLife channel UI. */
object CarbonTheme {
    val BG = Color.parseColor("#0F1114")
    val PANEL = Color.parseColor("#17191C")
    val PANEL_DEEP = Color.parseColor("#0C0D0F")
    val HAIRLINE = Color.parseColor("#2A2E33")

    /** Mutable accent — every screen calls [load] before building views. */
    var RED = Color.parseColor("#E1062C")

    val TEXT = Color.parseColor("#E8EAEE")
    val TEXT_DIM = Color.parseColor("#9AA0A8")
    val TEXT_FAINT = Color.parseColor("#585F68")
    val ONLINE = Color.parseColor("#2EE06C")

    fun load(context: Context) {
        RED = when (CarLifePersistence.get(context).accent()) {
            CarLifePersistence.ACCENT_BLUE -> Color.parseColor("#2E7CF6")
            CarLifePersistence.ACCENT_ORANGE -> Color.parseColor("#FF7A00")
            else -> Color.parseColor("#E1062C")
        }
    }

    fun hairlinePanel(context: Context): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 0f
            setStroke((1 * context.resources.displayMetrics.density).toInt(), HAIRLINE)
            setColor(PANEL)
        }
}

/**
 * Programmatic carbon-fibre twill tile (the C3.0 background): a 20dp repeat
 * of 2x2 blocks, each with a diagonal sheen band and a hairline edge, tiled
 * to fill any surface. Generated once and cached.
 */
object CarbonWeave {
    @Volatile
    private var cache: BitmapDrawable? = null

    fun tile(context: Context): BitmapDrawable {
        cache?.let { return it }
        val density = context.resources.displayMetrics.density
        val size = (20 * density).toInt().coerceAtLeast(20)
        val half = (size / 2f)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.parseColor("#111316"))
        val block = Paint().apply { color = Color.parseColor("#14161a") }
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = maxOf(1f, density * 0.5f)
            color = Color.parseColor("#191b1f")
        }
        val sheen = Paint().apply { isDither = true }
        val thread = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = maxOf(1f, density * 0.8f)
        }
        for (row in 0..1) for (col in 0..1) {
            val l = col * half
            val t = row * half
            sheen.shader = LinearGradient(
                l, t + half, l + half, t,
                Color.parseColor("#191c21"), Color.parseColor("#0e1013"),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(l, t, l + half, t + half, block)
            canvas.drawRect(l, t, l + half, t + half, sheen)
            // Weave direction alternates per block, like a real twill.
            if ((row + col) % 2 == 0) {
                thread.color = Color.parseColor("#1b1e23")
                canvas.drawLine(l, t + half, l + half, t, thread)
            } else {
                thread.color = Color.parseColor("#0d0f12")
                canvas.drawLine(l, t, l + half, t + half, thread)
            }
            canvas.drawRect(l, t, l + half, t + half, edge)
        }
        val drawable = BitmapDrawable(context.resources, bmp).apply {
            tileModeX = Shader.TileMode.REPEAT
            tileModeY = Shader.TileMode.REPEAT
        }
        cache = drawable
        return drawable
    }
}

/**
 * The skewed CTA from the C3.0 design: a parallelogram with the accent
 * gradient, soft red glow and letter-spaced label. Clickable.
 */
class SkewCtaView(context: Context) : View(context) {
    var label: String = ""
        set(value) {
            field = value
            invalidate()
        }

    var onAction: (() -> Unit)? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val path = Path()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)  // soft glow needs software
        isClickable = true
        isFocusable = true
        setOnClickListener { onAction?.invoke() }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val skew = h * 0.25f
        fill.setShadowLayer(dp(16f), 0f, dp(5f), 0x66E1062C)
        fill.shader = LinearGradient(
            0f, 0f, w, 0f,
            CarbonTheme.RED, Color.parseColor("#a80b21"),
            Shader.TileMode.CLAMP,
        )
        path.reset()
        path.moveTo(skew, 0f)
        path.lineTo(w, 0f)
        path.lineTo(w - skew, h)
        path.lineTo(0f, h)
        path.close()
        canvas.drawPath(path, fill)
        textPaint.textSize = dp(17f)
        textPaint.letterSpacing = 0.18f
        val cy = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, w / 2f, cy, textPaint)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
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

    /** Animated value the needle/arc/ticks follow so changes glide. */
    private var displaySpeed = 0f
    private var animator: ValueAnimator? = null

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
        animator?.cancel()
        if (isStandby) {
            displaySpeed = 0f
        } else {
            animator = ValueAnimator.ofFloat(displaySpeed, speedKmh.toFloat()).apply {
                duration = 450
                addUpdateListener {
                    displaySpeed = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
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
        val fraction = displaySpeed.coerceIn(0f, 240f) / 240f
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
        canvas.drawText(if (standby) "000" else displaySpeed.roundToInt().toString(), cx, ty, speedPaint)
        unitPaint.textSize = dp(11f)
        canvas.drawText(if (standby) "RPM ×100" else "km/h", cx, ty + dp(24f), unitPaint)
        subPaint.textSize = dp(13f)
        canvas.drawText(
            when {
                standby -> "CARLIFE · STANDBY"
                rpm < 0 -> "GEAR $gear · GPS"
                else -> "GEAR $gear · RPM $rpm"
            },
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

/**
 * The standby ring: the connection heart of the home screen. Offline it is a
 * slow radar sweep around a big clock; while searching the sweep brightens
 * and speeds up; when a session is alive it turns into a green ONLINE arc
 * with the connected phone's name.
 */
class StandbyRingView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    enum class State { OFFLINE, SEARCHING, ONLINE }

    private var state = State.OFFLINE
    private var clock = "--:--"
    private var dateLine = ""
    private var statusLine = "CARLIFE · STANDBY"
    private var phoneName = ""

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.parseColor("#1B1E22"); strokeWidth = 0f
    }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = CarbonTheme.RED
    }
    private val onlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = CarbonTheme.ONLINE
    }
    private val clockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER
    }
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CarbonTheme.TEXT_FAINT; typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER
    }
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CarbonTheme.RED; typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER
    }
    private val arcRect = RectF()

    fun setState(newState: State, phone: String = "") {
        state = newState
        phoneName = phone
        statusLine = when (newState) {
            State.OFFLINE -> "CARLIFE · STANDBY"
            State.SEARCHING -> "SEARCHING PHONE…"
            State.ONLINE -> if (phoneName.isBlank()) "CARLIFE · ONLINE" else phoneName.uppercase()
        }
        statusPaint.color = when (newState) {
            State.ONLINE -> CarbonTheme.ONLINE
            State.SEARCHING -> Color.parseColor("#FF9500")
            State.OFFLINE -> CarbonTheme.RED
        }
        invalidate()
    }

    /** Called from the home clock tick so the centre always reads current. */
    fun setClock(time: String, date: String) {
        clock = time
        dateLine = date
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f - dp(14f)
        val stroke = dp(9f)

        // base ring
        trackPaint.strokeWidth = stroke
        canvas.drawCircle(cx, cy, radius, trackPaint)

        // sweep / online arc
        sweepPaint.strokeWidth = stroke
        onlinePaint.strokeWidth = stroke
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        when (state) {
            State.ONLINE -> {
                onlinePaint.setShadowLayer(dp(8f), 0f, 0f, 0x662EE06C)
                canvas.drawArc(arcRect, -90f, 270f, false, onlinePaint)
            }

            State.SEARCHING -> {
                val angle = (System.currentTimeMillis() % 1600) / 1600f * 360f
                sweepPaint.shader = LinearGradient(
                    cx - radius, cy - radius, cx + radius, cy + radius,
                    CarbonTheme.RED, Color.parseColor("#FF9500"),
                    Shader.TileMode.CLAMP,
                )
                sweepPaint.setShadowLayer(dp(10f), 0f, 0f, 0x88E1062C)
                canvas.drawArc(arcRect, angle, 70f, false, sweepPaint)
                postInvalidateOnAnimation()
            }

            State.OFFLINE -> {
                val angle = (System.currentTimeMillis() % 9000) / 9000f * 360f
                sweepPaint.shader = LinearGradient(
                    cx - radius, cy - radius, cx + radius, cy + radius,
                    CarbonTheme.RED, Color.parseColor("#FF9500"),
                    Shader.TileMode.CLAMP,
                )
                sweepPaint.setShadowLayer(0f, 0f, 0f, 0)
                canvas.drawArc(arcRect, angle, 50f, false, sweepPaint)
                postInvalidateOnAnimation()
            }
        }

        // centre: clock / date / status
        clockPaint.textSize = dp(if (dateLine.isBlank()) 40f else 44f)
        val clockY = cy - dp(if (dateLine.isBlank()) 2f else 8f)
        canvas.drawText(clock, cx, clockY, clockPaint)
        if (dateLine.isNotBlank()) {
            datePaint.textSize = dp(12f)
            canvas.drawText(dateLine, cx, clockY + dp(26f), datePaint)
        }
        statusPaint.textSize = dp(12f)
        canvas.drawText(statusLine, cx, cy + dp(if (dateLine.isBlank()) 34f else 54f), statusPaint)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
