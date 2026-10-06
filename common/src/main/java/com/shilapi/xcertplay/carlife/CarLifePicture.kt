package com.shilapi.xcertplay.carlife

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

/**
 * Picture adjustments for the CarLife surface, matching DiPlay's CarPlay
 * picture controls: brightness / contrast / saturation / warmth, each
 * -50..50 around neutral, plus a night dimming factor.
 */
object CarLifePicture {

    data class Settings(
        var brightness: Int = 0,
        var contrast: Int = 0,
        var saturation: Int = 0,
        var warmth: Int = 0,
        var night: Boolean = false,
    )

    /** Builds the paint that TextureView.setPaint applies while compositing. */
    fun paint(settings: Settings): Paint {
        val brightness = settings.brightness / 50f          // -1..1
        val contrast = 1f + settings.contrast / 50f         // 0..2
        val saturation = 1f + settings.saturation / 50f     // 0..2
        val warmth = settings.warmth / 50f                  // -1..1 (positive = warm)

        // Warmth shifts red up / blue down (or reverse when negative).
        val warmR = 1f + 0.12f * warmth
        val warmB = 1f - 0.12f * warmth

        val cm = ColorMatrix()
        // Brightness + contrast on RGB.
        cm.set(floatArrayOf(
            contrast * warmR, 0f, 0f, 0f, (brightness * 255f * 0.5f),
            0f, contrast, 0f, 0f, (brightness * 255f * 0.5f),
            0f, 0f, contrast * warmB, 0f, (brightness * 255f * 0.5f),
            0f, 0f, 0f, 1f, 0f,
        ))
        val sat = ColorMatrix().apply { setSaturation(saturation) }
        cm.postConcat(sat)

        if (settings.night) {
            // Night: dim to ~45% and cut a little blue for comfort.
            val night = ColorMatrix(floatArrayOf(
                0.42f, 0f, 0f, 0f, 0f,
                0f, 0.42f, 0f, 0f, 0f,
                0f, 0f, 0.32f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ))
            cm.postConcat(night)
        }

        return Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
    }

    /** System day/night resolves to the effective night flag. */
    fun isNight(mode: Int, systemNight: Boolean): Boolean = when (mode) {
        CarLifePersistence.DAY_NIGHT_DAY -> false
        CarLifePersistence.DAY_NIGHT_NIGHT -> true
        else -> systemNight
    }
}
