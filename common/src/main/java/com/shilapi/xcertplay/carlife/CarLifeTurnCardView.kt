package com.shilapi.xcertplay.carlife

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.host.R

/**
 * CarLife turn card, drawn over the projection (layout language follows the
 * upstream ClusterTurnCardView: dark glass pill, maneuver glyph in a soft
 * chip, big distance over small road). It survives app switches on the phone
 * - navigation data keeps flowing while music is in the foreground.
 *
 * Unknown CarLife maneuver codes never invent arrows: one verified glyph is
 * used until a real code table exists (upstream 0.2.9 lesson). Expired or
 * empty guidance clears the card; missing values stay absent.
 */
class CarLifeTurnCardView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var road: String = ""
    private var distanceText: String = ""
    private var visible = false

    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xD90C0D0F.toInt()
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = 0x24FFFFFF
    }
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x1AE1062C
    }
    private val distancePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CarbonTheme.RED; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.LEFT
    }
    private val roadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.LEFT
    }
    private val rect = RectF()
    private var glyphTag = -1

    /** distanceMeters <= 0 keeps the distance line absent (missing stays absent). */
    fun setGuidance(roadName: String, distanceMeters: Int) {
        val hasData = roadName.isNotBlank() || distanceMeters > 0
        if (!hasData) {
            visible = false
            visibility = GONE
            return
        }
        road = roadName
        distanceText = distanceLabel(distanceMeters)
        visible = true
        visibility = VISIBLE
        bringToFront()
        invalidate()
    }

    fun hideCard() {
        visible = false
        visibility = GONE
    }

    private fun distanceLabel(meters: Int): String = when {
        meters <= 0 -> ""
        meters <= 20 -> context.getString(R.string.turn_card_now)
        meters < 1000 -> context.getString(R.string.turn_card_distance_m, meters)
        else -> context.getString(R.string.turn_card_distance_km, meters / 100 / 10f)
    }

    override fun onDraw(canvas: Canvas) {
        if (!visible) return
        val w = width.toFloat()
        val h = height.toFloat()

        val radius = h * 0.30f
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, radius, radius, glassPaint)
        canvas.drawRoundRect(rect, radius, radius, strokePaint)

        // maneuver chip with the verified glyph, tinted the channel accent
        val chip = h * 0.76f
        val chipLeft = h * 0.12f
        val chipTop = (h - chip) / 2f
        rect.set(chipLeft, chipTop, chipLeft + chip, chipTop + chip)
        canvas.drawRoundRect(rect, chip * 0.26f, chip * 0.26f, chipPaint)
        val resId = R.drawable.ic_turn_card_turn_right
        if (resId != glyphTag) glyphTag = resId
        ContextCompat.getDrawable(context, resId)?.mutate()?.apply {
            setTint(CarbonTheme.RED)
            val inset = chip * 0.16f
            setBounds(
                (chipLeft + inset).toInt(), (chipTop + inset).toInt(),
                (chipLeft + chip - inset).toInt(), (chipTop + chip - inset).toInt(),
            )
            draw(canvas)
        }

        // distance big, road small (driver priority, upstream proportions)
        val textLeft = chipLeft + chip + h * 0.14f
        val textWidth = w - h * 0.10f - textLeft
        if (textWidth <= 0f) return
        distancePaint.textSize = h * 0.30f
        roadPaint.textSize = h * 0.17f
        if (distanceText.isNotEmpty()) {
            canvas.drawText(ellipsize(distanceText, textWidth, distancePaint), textLeft, h * 0.44f, distancePaint)
        }
        if (road.isNotEmpty()) {
            canvas.drawText(ellipsize(road, textWidth, roadPaint), textLeft, h * 0.74f, roadPaint)
        }
    }

    private fun ellipsize(text: String, maxWidth: Float, paint: Paint): String {
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        var end = text.length
        while (end > 0 && paint.measureText(text.take(end) + ellipsis) > maxWidth) end--
        return if (end == 0) ellipsis else text.take(end) + ellipsis
    }
}
