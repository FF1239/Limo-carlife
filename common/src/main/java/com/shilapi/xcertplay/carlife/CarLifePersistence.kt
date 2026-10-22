package com.shilapi.xcertplay.carlife

import android.content.Context
import android.content.SharedPreferences

/**
 * CarLife channel settings, persisted like DiPlay's AirPlayPersistence.
 */
class CarLifePersistence private constructor(private val prefs: SharedPreferences) {

    // ---- video negotiation ----

    fun videoWidth(): Int = prefs.getInt(KEY_VIDEO_W, 1280)

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

    /** Defaults to DAY: the phone's CarLife UI is light-themed, and the night
     *  ColorMatrix only dims it into a muddy grey. Ambient auto-dim stays
     *  available as an explicit setting. */
    fun dayNightMode(): Int = prefs.getInt(KEY_DAY_NIGHT, DAY_NIGHT_DAY)

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
     * Defaults to off: during a real projection session fake "DEMO" speed and
     * simulated navigation must not overlay the live data (opt in via settings).
     */
    fun demoData(): Boolean = prefs.getBoolean(KEY_DEMO_DATA, false)

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

    /** Drop the remembered phone so the next session re-detects it. */
    fun clearLastPhone() {
        prefs.edit().remove(KEY_LAST_PHONE).apply()
    }

    /** Turn-card overlay position in percent of the free area (0..100). */
    fun turnCardX(): Int = prefs.getInt(KEY_TURN_X, 4)

    fun turnCardY(): Int = prefs.getInt(KEY_TURN_Y, 8)

    fun saveTurnCardPosition(x: Int, y: Int) {
        prefs.edit().putInt(KEY_TURN_X, x).putInt(KEY_TURN_Y, y).apply()
    }

    /** Navigation-voice volume (TTS track gain), 0..100. */
    fun ttsVolume(): Int = prefs.getInt(KEY_TTS_VOLUME, 100)

    fun saveTtsVolume(v: Int) {
        prefs.edit().putInt(KEY_TTS_VOLUME, v.coerceIn(0, 100)).apply()
    }

    /** CarPlay behaviour: when guidance starts the map takes the whole screen. */
    fun naviAutoMap(): Boolean = prefs.getBoolean(KEY_NAVI_AUTO_MAP, true)

    fun saveNaviAutoMap(v: Boolean) {
        prefs.edit().putBoolean(KEY_NAVI_AUTO_MAP, v).apply()
    }

    /** Duck the music track while the phone is speaking a turn prompt. */
    fun naviDuckMusic(): Boolean = prefs.getBoolean(KEY_NAVI_DUCK, true)

    fun saveNaviDuckMusic(v: Boolean) {
        prefs.edit().putBoolean(KEY_NAVI_DUCK, v).apply()
    }

    /** Which feed drives the cluster speed readout when both are live. */
    fun speedSource(): Int = prefs.getInt(KEY_SPEED_SOURCE, SPEED_SOURCE_AUTO)

    fun saveSpeedSource(v: Int) {
        prefs.edit().putInt(KEY_SPEED_SOURCE, v).apply()
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
        private const val KEY_TTS_VOLUME = "tts_volume"
        private const val KEY_TURN_X = "turn_card_x"
        private const val KEY_TURN_Y = "turn_card_y"
        private const val KEY_NAVI_AUTO_MAP = "navi_auto_map"
        private const val KEY_NAVI_DUCK = "navi_duck_music"
        private const val KEY_SPEED_SOURCE = "speed_source"

        const val DAY_NIGHT_SYSTEM = 0
        const val DAY_NIGHT_DAY = 1
        const val DAY_NIGHT_NIGHT = 2

        const val SPEED_SOURCE_AUTO = 0
        const val SPEED_SOURCE_UNIT_GPS = 1
        const val SPEED_SOURCE_PHONE_GPS = 2

        const val ACCENT_RED = "red"
        const val ACCENT_BLUE = "blue"
        const val ACCENT_ORANGE = "orange"

        /** Resolution presets: width x height, landscape head-unit canvases
         *  plus portrait phone-shaped ones (phones mirror their own screen
         *  aspect; a 16:9 landscape canvas letterboxes a portrait phone). */
        /**
         * Resolution presets offered to the phone; the first entry is the
         * measured default (16:9 matches a wide head unit with no cropping).
         * 1920x720 stays available for units that prefer the wider canvas.
         */
        val RESOLUTION_PRESETS = arrayOf(
            intArrayOf(1280, 720),
            intArrayOf(1920, 720),
            intArrayOf(1024, 600),
            intArrayOf(800, 480),
            intArrayOf(1280, 480),
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
