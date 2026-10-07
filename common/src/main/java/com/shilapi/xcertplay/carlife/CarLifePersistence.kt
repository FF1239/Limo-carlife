package com.shilapi.xcertplay.carlife

import android.content.Context
import android.content.SharedPreferences

/**
 * CarLife channel settings, persisted like DiPlay's AirPlayPersistence.
 */
class CarLifePersistence private constructor(private val prefs: SharedPreferences) {

    // ---- video negotiation ----

    fun videoWidth(): Int = prefs.getInt(KEY_VIDEO_W, 1920)

    fun videoHeight(): Int = prefs.getInt(KEY_VIDEO_H, 720)

    fun videoFrameRate(): Int = prefs.getInt(KEY_VIDEO_FPS, 30)

    fun saveVideo(width: Int, height: Int, frameRate: Int) {
        prefs.edit().putInt(KEY_VIDEO_W, width).putInt(KEY_VIDEO_H, height)
            .putInt(KEY_VIDEO_FPS, frameRate).apply()
    }

    /** Negotiated canvas reported by the phone (scaled output uses this). */
    fun negotiatedWidth(): Int = prefs.getInt(KEY_NEGO_W, 0)

    fun negotiatedHeight(): Int = prefs.getInt(KEY_NEGO_H, 0)

    fun saveNegotiated(width: Int, height: Int) {
        prefs.edit().putInt(KEY_NEGO_W, width).putInt(KEY_NEGO_H, height).apply()
    }

    // ---- picture adjustments (CarPlay-parity ranges) ----

    /** -50..50, 0 neutral. */
    fun brightness(): Int = prefs.getInt(KEY_BRIGHTNESS, 0)

    fun contrast(): Int = prefs.getInt(KEY_CONTRAST, 0)

    fun saturation(): Int = prefs.getInt(KEY_SATURATION, 0)

    /** Warmth: -50 (cool) .. 50 (warm), 0 neutral. */
    fun warmth(): Int = prefs.getInt(KEY_WARMTH, 0)

    fun savePicture(brightness: Int, contrast: Int, saturation: Int, warmth: Int) {
        prefs.edit().putInt(KEY_BRIGHTNESS, brightness).putInt(KEY_CONTRAST, contrast)
            .putInt(KEY_SATURATION, saturation).putInt(KEY_WARMTH, warmth).apply()
    }

    // ---- day / night ----

    fun dayNightMode(): Int = prefs.getInt(KEY_DAY_NIGHT, DAY_NIGHT_SYSTEM)

    fun saveDayNightMode(mode: Int) {
        prefs.edit().putInt(KEY_DAY_NIGHT, mode).apply()
    }

    // ---- behaviour ----

    fun autoConnect(): Boolean = prefs.getBoolean(KEY_AUTO_CONNECT, true)

    fun saveAutoConnect(v: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CONNECT, v).apply()
    }

    /** Mirror the phone's mic uplink (VR) — off keeps CarLife text-only. */
    fun micUplink(): Boolean = prefs.getBoolean(KEY_MIC_UPLINK, true)

    fun saveMicUplink(v: Boolean) {
        prefs.edit().putBoolean(KEY_MIC_UPLINK, v).apply()
    }

    /** Wired / wireless transport choice for auto-connect. */
    fun wirelessEnabled(): Boolean = prefs.getBoolean(KEY_WIRELESS, false)

    fun saveWirelessEnabled(v: Boolean) {
        prefs.edit().putBoolean(KEY_WIRELESS, v).apply()
    }

    /**
     * Simulated speed/gear when no real vehicle source exists (no CAN, no
     * GPS channel yet). Off = gauge stays at standby until a real source lands.
     */
    fun demoData(): Boolean = prefs.getBoolean(KEY_DEMO_DATA, true)

    fun saveDemoData(v: Boolean) {
        prefs.edit().putBoolean(KEY_DEMO_DATA, v).apply()
    }

    /** Accent colour of the carbon theme (red / blue / orange). */
    fun accent(): String = prefs.getString(KEY_ACCENT, ACCENT_RED) ?: ACCENT_RED

    fun saveAccent(v: String) {
        prefs.edit().putString(KEY_ACCENT, v).apply()
    }

    /** Last connected phone (manufacturer + model) for the home ticker. */
    fun lastPhone(): String? = prefs.getString(KEY_LAST_PHONE, null)

    fun saveLastPhone(v: String) {
        prefs.edit().putString(KEY_LAST_PHONE, v).apply()
    }

    companion object {
        private const val FILE = "carlife_settings"

        private const val KEY_VIDEO_W = "video_w"
        private const val KEY_VIDEO_H = "video_h"
        private const val KEY_VIDEO_FPS = "video_fps"
        private const val KEY_NEGO_W = "nego_w"
        private const val KEY_NEGO_H = "nego_h"
        private const val KEY_BRIGHTNESS = "brightness"
        private const val KEY_CONTRAST = "contrast"
        private const val KEY_SATURATION = "saturation"
        private const val KEY_WARMTH = "warmth"
        private const val KEY_DAY_NIGHT = "day_night"
        private const val KEY_AUTO_CONNECT = "auto_connect"
        private const val KEY_MIC_UPLINK = "mic_uplink"
        private const val KEY_WIRELESS = "wireless"
        private const val KEY_DEMO_DATA = "demo_data"
        private const val KEY_ACCENT = "accent"
        private const val KEY_LAST_PHONE = "last_phone"

        const val DAY_NIGHT_SYSTEM = 0
        const val DAY_NIGHT_DAY = 1
        const val DAY_NIGHT_NIGHT = 2

        const val ACCENT_RED = "red"
        const val ACCENT_BLUE = "blue"
        const val ACCENT_ORANGE = "orange"

        /** Resolution presets: width x height, landscape head-unit canvases
         *  plus portrait phone-shaped ones (phones mirror their own screen
         *  aspect; a 16:9 landscape canvas letterboxes a portrait phone). */
        val RESOLUTION_PRESETS = arrayOf(
            intArrayOf(800, 480),
            intArrayOf(1024, 600),
            intArrayOf(1280, 480),
            intArrayOf(1280, 720),
            intArrayOf(1920, 720),
            intArrayOf(720, 1280),
            intArrayOf(1080, 1920),
        )

        @Volatile
        private var instance: CarLifePersistence? = null

        fun get(context: Context): CarLifePersistence =
            instance ?: synchronized(this) {
                instance ?: CarLifePersistence(
                    context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE),
                ).also { instance = it }
            }
    }
}
