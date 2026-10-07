package com.shilapi.xcertplay.carlife

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import kotlin.math.roundToInt

/**
 * The head unit's own GNSS as a speed source. On an Accord factory unit with
 * navigation this reads truer than the phone's feed (roof antenna, no USB
 * latency); on units without GPS, [start] returns false and the phone's
 * CAR_GPS feed remains the only real source.
 */
class UnitGpsSpeedSource(
    private val context: Context,
    private val onSpeedKmh: (Int) -> Unit,
) {
    private var manager: LocationManager? = null
    private var stopped = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val kmh = (location.speed * 3.6).roundToInt().coerceAtLeast(0)
            onSpeedKmh(kmh)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit

        override fun onProviderEnabled(provider: String) = Unit

        override fun onProviderDisabled(provider: String) = Unit
    }

    /** @return false when the unit has no usable GPS provider. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (stopped) return false
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                ?: return false
            if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) return false
            manager = lm
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1_000L,
                0.5f,
                listener,
                Looper.getMainLooper(),
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    fun stop() {
        stopped = true
        runCatching { manager?.removeUpdates(listener) }
        manager = null
    }
}
