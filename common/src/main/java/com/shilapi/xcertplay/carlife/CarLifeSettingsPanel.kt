package com.shilapi.xcertplay.carlife

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * Floating settings panel over the CarLife projection, modelled on DiPlay's
 * picture panel: resolution preset, picture sliders (brightness / contrast /
 * saturation / warmth), day-night mode and mic toggle. Changes persist via
 * [CarLifePersistence] and apply live.
 */
class CarLifeSettingsPanel(
    context: Context,
    private val persistence: CarLifePersistence,
    private val onApply: (CarLifePicture.Settings) -> Unit,
    private val onResolution: (Int, Int, Int) -> Unit,
) : FrameLayout(context) {

    private var brightness = persistence.brightness()
    private var contrast = persistence.contrast()
    private var saturation = persistence.saturation()
    private var warmth = persistence.warmth()
    private var night = persistence.dayNightMode() == CarLifePersistence.DAY_NIGHT_NIGHT

    private val status: TextView

    init {
        setBackgroundColor(0xEE14181C.toInt())
        val pad = (14 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
        elevation = 12f

        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(column)

        fun title(text: String) = column.addView(TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 16f
        })

        fun slider(label: String, value: Int, onChange: (Int) -> Unit) {
            val caption = TextView(context).apply {
                this.text = label
                setTextColor(0xFFB8C0C8.toInt())
                textSize = 13f
            }
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val bar = SeekBar(context).apply {
                max = 100
                progress = value + 50
            }
            val readout = TextView(context).apply {
                this.text = "%+d".format(value)
                setTextColor(Color.WHITE)
                textSize = 13f
                gravity = Gravity.CENTER
                minWidth = (48 * resources.displayMetrics.density).toInt()
            }
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val v = progress - 50
                    readout.text = "%+d".format(v)
                    onChange(v)
                    apply()
                }

                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    persistence.savePicture(brightness, contrast, saturation, warmth)
                }
            })
            row.addView(bar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(readout)
            column.addView(caption)
            column.addView(row)
        }

        title("CarLife 设置")
        status = TextView(context).apply {
            setTextColor(0xFF9AA4AE.toInt())
            textSize = 12f
        }
        column.addView(status)

        // Resolution presets.
        val resRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        CarLifePersistence.RESOLUTION_PRESETS.forEachIndexed { index, preset ->
            val active = preset[0] == persistence.videoWidth() && preset[1] == persistence.videoHeight()
            val b = Button(context).apply {
                text = "${preset[0]}×${preset[1]}"
                textSize = 11f
                isAllCaps = false
                background = GradientDrawable().apply {
                    cornerRadius = 12f
                    setColor(if (active) 0xFFE4002B.toInt() else 0xFF2A3038.toInt())
                }
                setTextColor(Color.WHITE)
                setOnClickListener {
                    persistence.saveVideo(preset[0], preset[1], persistence.videoFrameRate())
                    onResolution(preset[0], preset[1], persistence.videoFrameRate())
                    apply()
                }
            }
            resRow.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (6 * resources.displayMetrics.density).toInt()
            })
        }
        column.addView(resRow)

        slider("亮度", brightness) { brightness = it }
        slider("对比度", contrast) { contrast = it }
        slider("饱和度", saturation) { saturation = it }
        slider("色温(冷/暖)", warmth) { warmth = it }

        // Day/night toggle.
        val nightBtn = Button(context).apply {
            text = if (night) "夜间模式:开" else "夜间模式:关"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = 12f
                setColor(if (night) 0xFFE4002B.toInt() else 0xFF2A3038.toInt())
            }
            setOnClickListener {
                night = !night
                persistence.saveDayNightMode(
                    if (night) CarLifePersistence.DAY_NIGHT_NIGHT else CarLifePersistence.DAY_NIGHT_DAY,
                )
                text = if (night) "夜间模式:开" else "夜间模式:关"
                apply()
            }
        }
        column.addView(nightBtn)

        // Close button.
        column.addView(Button(context).apply {
            text = "收起设置"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = 12f; setColor(0xFF2A3038.toInt()) }
            setOnClickListener { (parent as? View)?.visibility = View.GONE }
        })

        apply()
    }

    fun note(text: String) {
        status.text = text
    }

    @SuppressLint("SetTextI18n")
    private fun apply() {
        onApply(
            CarLifePicture.Settings(brightness, contrast, saturation, warmth, night),
        )
    }
}
