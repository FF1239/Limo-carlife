package com.shilapi.xcertplay

import android.content.Context
import android.content.res.Configuration

/**
 * Returns the explicit night state carried by [uiMode], or null when the head unit reports
 * UI_MODE_NIGHT_UNDEFINED so callers keep their previous state instead of flipping to light.
 */
internal fun nightModeOrNull(uiMode: Int): Boolean? =
    when (uiMode and Configuration.UI_MODE_NIGHT_MASK) {
        Configuration.UI_MODE_NIGHT_YES -> true
        Configuration.UI_MODE_NIGHT_NO -> false
        else -> null
    }

/**
 * Night state for AppDialogs.kt's dialog themes. An explicit uiMode wins; a head unit that
 * reports UI_MODE_NIGHT_UNDEFINED keeps the carbon dark chrome rather than flashing light.
 */
internal fun Context.resolveAppNightNow(): Boolean =
    nightModeOrNull(resources.configuration.uiMode) ?: true
