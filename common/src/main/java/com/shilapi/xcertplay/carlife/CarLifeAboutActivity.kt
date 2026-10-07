package com.shilapi.xcertplay.carlife

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.shilapi.xcertplay.AppLocale
import com.shilapi.xcertplay.AppUpdate
import com.shilapi.xcertplay.host.R

/**
 * Standalone about page: version, the upstream credit (this project learns
 * its layout/engineering from DiPlay / DiPlay-CN), plus the update, language
 * and log-export entry points moved out of settings.
 */
class CarLifeAboutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CarbonTheme.load(this)

        val scroll = ScrollView(this).apply { background = CarbonWeave.tile(this@CarLifeAboutActivity) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(18), dp(28), dp(28))
        }
        scroll.addView(column, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // header
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(TextView(this).apply {
            text = "‹"
            setTextColor(CarbonTheme.RED)
            textSize = 30f
            setPadding(dp(6), dp(2), dp(22), dp(2))
            isClickable = true
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = getString(R.string.settings_about)
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            gravity = Gravity.CENTER_VERTICAL
        })
        column.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // identity + credit
        column.addView(TextView(this).apply {
            text = getString(R.string.settings_version_row, AppUpdate.currentVersion(applicationContext))
            setTextColor(CarbonTheme.TEXT_DIM)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(20), 0, dp(10))
        })
        val creditPanel = panel()
        creditPanel.addView(TextView(this).apply {
            text = getString(R.string.about_credit)
            setTextColor(CarbonTheme.TEXT)
            textSize = 14f
            lineHeight = dp(22).toInt()
            setPadding(0, dp(4), 0, dp(4))
        })
        column.addView(creditPanel)

        // entry points (moved out of settings)
        val actions = panel()
        actions.addView(actionChip(getString(R.string.settings_check_update)) { checkUpdate() })
        actions.addView(actionChip(getString(R.string.settings_language)) { AppLocale.showPicker(this) })
        actions.addView(actionChip(getString(R.string.settings_export_log)) { exportLog() })
        column.addView(actions)

        setContentView(scroll)
    }

    private fun panel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(14), dp(18), dp(14))
        background = GradientDrawable().apply {
            cornerRadius = 0f
            setStroke(dp(1), CarbonTheme.HAIRLINE)
            setColor(CarbonTheme.PANEL)
        }
    }

    private fun panelTitle(text: String, top: Int, bottom: Int): TextView = TextView(this).apply {
        this.text = text
        setTextColor(CarbonTheme.TEXT_DIM)
        textSize = 13f
        letterSpacing = 0.18f
        setPadding(0, dp(top), 0, dp(bottom))
    }

    private fun actionChip(label: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = label
        isAllCaps = false
        textSize = 14f
        setTextColor(CarbonTheme.TEXT)
        background = GradientDrawable().apply {
            cornerRadius = 0f
            setColor(Color.parseColor("#1D2024"))
            setStroke(dp(1), CarbonTheme.HAIRLINE)
        }
        stateListAnimator = null
        setPadding(dp(16), dp(10), dp(16), dp(10))
        setOnClickListener { onClick() }
    }

    private fun exportLog() {
        val src = CarLifeSessionLog.currentFile()
        if (src == null || !src.isFile) {
            Toast.makeText(this, getString(R.string.log_export_failed), Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val dir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS,
            ).apply { mkdirs() }
            val dst = java.io.File(dir, src.name)
            src.copyTo(dst, overwrite = true)
            Toast.makeText(this, getString(R.string.log_exported, dst.name), Toast.LENGTH_LONG).show()
        }.onFailure {
            Toast.makeText(this, getString(R.string.log_export_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkUpdate() {
        Toast.makeText(this, getString(R.string.settings_check_update), Toast.LENGTH_SHORT).show()
        AppUpdate.check { release, error ->
            runOnUiThread {
                val message = when {
                    release != null && AppUpdate.isAvailable(release, this) ->
                        getString(R.string.update_available_no_size, release.tag)
                    release != null -> getString(R.string.up_to_date)
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
