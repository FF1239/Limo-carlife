package com.shilapi.xcertplay.carlife

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.shilapi.xcertplay.host.R

/**
 * CarLife settings page in the instrument style: black panels with hairline
 * RED strokes, sharp edges. Resolution presets, picture sliders, day/night,
 * mic and auto-connect toggles — all persisted via [CarLifePersistence].
 */
class CarLifeSettingsActivity : Activity() {

    private val persistence by lazy { CarLifePersistence.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CarbonTheme.load(this)

        val scroll = android.widget.ScrollView(this).apply { setBackgroundColor(BG) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(18), dp(28), dp(28))
        }
        scroll.addView(column, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // header
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(backButton())
        header.addView(sectionTitle(getString(R.string.settings_title), 22f),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                gravity = Gravity.CENTER_VERTICAL
            })
        column.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // video section
        column.addView(panelTitle(getString(R.string.settings_video), 0, dp(22)))
        val resPanel = panel()
        CarLifePersistence.RESOLUTION_PRESETS.forEachIndexed { index, preset ->
            val active = preset[0] == persistence.videoWidth() && preset[1] == persistence.videoHeight()
            resPanel.addView(chip("${preset[0]}×${preset[1]}", active) {
                persistence.saveVideo(preset[0], preset[1], persistence.videoFrameRate())
                recreate()
            }, rowParams(index, CarLifePersistence.RESOLUTION_PRESETS.size))
        }
        column.addView(resPanel)

        // picture section
        column.addView(panelTitle(getString(R.string.settings_picture), dp(26), dp(10)))
        val picturePanel = panel()
        var brightness = persistence.brightness()
        var contrast = persistence.contrast()
        var saturation = persistence.saturation()
        var warmth = persistence.warmth()
        val savePicture = {
            persistence.savePicture(brightness, contrast, saturation, warmth)
        }
        picturePanel.addView(slider(getString(R.string.settings_brightness), brightness) {
            brightness = it; savePicture()
        })
        picturePanel.addView(slider(getString(R.string.settings_contrast), contrast) {
            contrast = it; savePicture()
        })
        picturePanel.addView(slider(getString(R.string.settings_saturation), saturation) {
            saturation = it; savePicture()
        })
        picturePanel.addView(slider(getString(R.string.settings_warmth), warmth) {
            warmth = it; savePicture()
        })
        column.addView(picturePanel)

        // behaviour section
        column.addView(panelTitle(getString(R.string.settings_behaviour), dp(26), dp(10)))
        val behaviourPanel = panel()
        // day/night: three-way (auto = light sensor)
        behaviourPanel.addView(TextView(this).apply {
            text = getString(R.string.settings_daynight)
            setTextColor(TEXT_DIM)
            textSize = 13f
            setPadding(0, dp(10), 0, dp(6))
        })
        val dnRow = LinearLayout(this)
        listOf(
            CarLifePersistence.DAY_NIGHT_SYSTEM to getString(R.string.settings_daynight_auto),
            CarLifePersistence.DAY_NIGHT_DAY to getString(R.string.settings_daynight_day),
            CarLifePersistence.DAY_NIGHT_NIGHT to getString(R.string.settings_daynight_night),
        ).forEachIndexed { i, (mode, label) ->
            if (i > 0) dnRow.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
            dnRow.addView(chip(label, persistence.dayNightMode() == mode) {
                persistence.saveDayNightMode(mode)
                recreate()
            }, LinearLayout.LayoutParams(0, dp(46), 1f))
        }
        behaviourPanel.addView(dnRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        behaviourPanel.addView(toggle(getString(R.string.settings_autoconnect), persistence.autoConnect()) {
            persistence.saveAutoConnect(it)
        })
        behaviourPanel.addView(toggle(getString(R.string.settings_mic), persistence.micUplink()) {
            persistence.saveMicUplink(it)
        })
        behaviourPanel.addView(toggle(getString(R.string.settings_wireless), persistence.wirelessEnabled()) {
            persistence.saveWirelessEnabled(it)
        })
        behaviourPanel.addView(toggle(getString(R.string.settings_demo_data), persistence.demoData()) {
            persistence.saveDemoData(it)
        })
        // session log (no-adb debugging: file lives in Android/data files/logs)
        behaviourPanel.addView(TextView(this).apply {
            text = getString(R.string.settings_session_log)
            setTextColor(TEXT)
            textSize = 15f
            setPadding(0, dp(12), 0, dp(2))
        })
        behaviourPanel.addView(TextView(this).apply {
            val f = CarLifeSessionLog.currentFile()
            text = f?.absolutePath ?: getString(R.string.settings_log_none)
            setTextColor(TEXT_DIM)
            textSize = 12f
            typeface = Typeface.MONOSPACE
        })
        behaviourPanel.addView(actionChip(getString(R.string.settings_export_log)) { exportLog() })
        column.addView(behaviourPanel)

        // about section (reuses the app-wide updater/locale, CarLife entry points)
        column.addView(panelTitle(getString(R.string.settings_about), dp(26), dp(10)))
        val aboutPanel = panel()
        aboutPanel.addView(actionChip(getString(R.string.settings_language)) {
            com.shilapi.xcertplay.AppLocale.showPicker(this)
        })
        aboutPanel.addView(actionChip(getString(R.string.settings_check_update)) { checkUpdate() })
        aboutPanel.addView(TextView(this).apply {
            text = getString(
                R.string.settings_version_row,
                com.shilapi.xcertplay.AppUpdate.currentVersion(context),
            )
            setTextColor(TEXT_DIM)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(10), 0, dp(2))
        })
        column.addView(aboutPanel)

        // sound section
        column.addView(panelTitle(getString(R.string.settings_sound), dp(26), dp(10)))
        val soundPanel = panel()
        soundPanel.addView(volumeSlider(getString(R.string.settings_tts_volume), persistence.ttsVolume()) {
            persistence.saveTtsVolume(it)
        })
        column.addView(soundPanel)

        // appearance section
        column.addView(panelTitle(getString(R.string.settings_appearance), dp(26), dp(10)))
        val accentPanel = panel()
        val accentRow = LinearLayout(this)
        listOf(
            CarLifePersistence.ACCENT_RED to getString(R.string.settings_accent_red),
            CarLifePersistence.ACCENT_BLUE to getString(R.string.settings_accent_blue),
            CarLifePersistence.ACCENT_ORANGE to getString(R.string.settings_accent_orange),
        ).forEachIndexed { i, (value, label) ->
            if (i > 0) accentRow.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
            accentRow.addView(chip(label, persistence.accent() == value) {
                persistence.saveAccent(value)
                recreate()
            }, LinearLayout.LayoutParams(0, dp(46), 1f))
        }
        accentPanel.addView(accentRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(accentPanel)

        setContentView(scroll)
    }

    // ---- widgets ----

    private fun backButton(): View = TextView(this).apply {
        text = "‹"
        setTextColor(RED)
        textSize = 30f
        setPadding(dp(6), dp(2), dp(22), dp(2))
        isClickable = true
        setOnClickListener { finish() }
    }

    private fun sectionTitle(text: String, size: Float): TextView = TextView(this).apply {
        this.text = text
        setTextColor(TEXT)
        textSize = size
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.04f
    }

    private fun panelTitle(text: String, top: Int, bottom: Int): TextView = TextView(this).apply {
        this.text = text
        setTextColor(TEXT_DIM)
        textSize = 13f
        letterSpacing = 0.18f
        setPadding(0, dp(top), 0, dp(bottom))
    }

    private fun panel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(14), dp(18), dp(14))
        background = GradientDrawable().apply {
            cornerRadius = 0f
            setStroke(dp(1), HAIRLINE)
            setColor(PANEL)
        }
    }

    private fun chip(text: String, active: Boolean, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
        setTextColor(if (active) BG else TEXT)
        background = GradientDrawable().apply {
            cornerRadius = 0f
            setColor(if (active) RED else Color.parseColor("#17191C"))
            setStroke(dp(1), if (active) RED else HAIRLINE)
        }
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun rowParams(index: Int, count: Int): LinearLayout.LayoutParams {
        val margin = dp(6)
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply {
            topMargin = if (index == 0) 0 else margin
        }
    }

    private fun slider(label: String, value: Int, onChange: (Int) -> Unit): View {
        val caption = TextView(this).apply {
            text = "$label  %+d".format(value)
            setTextColor(TEXT_DIM)
            textSize = 13f
            setPadding(0, dp(8), 0, dp(2))
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val bar = SeekBar(this).apply {
            max = 100
            progress = value + 50
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                caption.text = "$label  %+d".format(progress - 50)
                onChange(progress - 50)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })
        row.addView(bar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        holder.addView(caption)
        holder.addView(row)
        return holder
    }

    /** Plain 0..100 slider (no ±50 offset) for absolute volumes. */
    private fun volumeSlider(label: String, value: Int, onChange: (Int) -> Unit): View {
        val caption = TextView(this).apply {
            text = "$label  $value%"
            setTextColor(TEXT_DIM)
            textSize = 13f
            setPadding(0, dp(8), 0, dp(2))
        }
        val bar = SeekBar(this).apply {
            max = 100
            progress = value
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                caption.text = "$label  $progress%"
                onChange(progress)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })
        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        holder.addView(caption)
        holder.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return holder
    }

    private fun toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        var state = checked
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        row.addView(TextView(this@CarLifeSettingsActivity).apply {
            text = label
            setTextColor(TEXT)
            textSize = 15f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val switchText = TextView(this).apply {
            text = if (state) "开" else "关"
            setTextColor(if (state) BG else TEXT_DIM)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = 0f
                setColor(if (state) RED else Color.parseColor("#17191C"))
                setStroke(dp(1), if (state) RED else HAIRLINE)
            }
            setPadding(dp(18), dp(6), dp(18), dp(6))
        }
        row.addView(switchText)
        row.isClickable = true
        row.setOnClickListener {
            state = !state
            onChange(state)
            switchText.text = if (state) "开" else "关"
            switchText.setTextColor(if (state) BG else TEXT_DIM)
            (switchText.background as GradientDrawable).apply {
                setColor(if (state) RED else Color.parseColor("#17191C"))
                setStroke(dp(1), if (state) RED else HAIRLINE)
            }
        }
        return row
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Wide action chip used by the log/about rows. */
    private fun actionChip(label: String, onClick: () -> Unit): View = android.widget.Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setTextColor(TEXT)
        background = GradientDrawable().apply {
            cornerRadius = 0f
            setColor(Color.parseColor("#1D2024"))
            setStroke(dp(1), HAIRLINE)
        }
        stateListAnimator = null
        setPadding(dp(16), dp(10), dp(16), dp(10))
        setOnClickListener { onClick() }
    }

    /** Copies the current session log into Downloads so it survives without adb. */
    private fun exportLog() {
        val src = CarLifeSessionLog.currentFile()
        if (src == null || !src.isFile) {
            android.widget.Toast.makeText(this, getString(R.string.log_export_failed), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val dir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS,
            ).apply { mkdirs() }
            val dst = java.io.File(dir, src.name)
            src.copyTo(dst, overwrite = true)
            android.widget.Toast.makeText(this, getString(R.string.log_exported, dst.name), android.widget.Toast.LENGTH_LONG).show()
        }.onFailure {
            android.widget.Toast.makeText(this, getString(R.string.log_export_failed), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** Reuses the app-wide updater from the CarLife settings entry point. */
    private fun checkUpdate() {
        android.widget.Toast.makeText(this, getString(R.string.state_scanning), android.widget.Toast.LENGTH_SHORT).show()
        com.shilapi.xcertplay.AppUpdate.check { release, error ->
            runOnUiThread {
                val message = when {
                    release != null && com.shilapi.xcertplay.AppUpdate.isAvailable(release, this) ->
                        getString(com.shilapi.xcertplay.host.R.string.update_available, release.tag)
                    release != null -> getString(com.shilapi.xcertplay.host.R.string.up_to_date)
                    else -> "${getString(R.string.settings_check_update)}: $error"
                }
                android.app.AlertDialog.Builder(this)
                    .setTitle(getString(R.string.settings_check_update))
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    companion object {
        private val BG = Color.parseColor("#0F1114")
        private val PANEL = Color.parseColor("#17191C")
        private val HAIRLINE = Color.parseColor("#2A2E33")
        private val RED = Color.parseColor("#E1062C")
        private val TEXT = Color.parseColor("#E8E4DC")
        private val TEXT_DIM = Color.parseColor("#9AA0A8")
    }
}
