package com.shilapi.xcertplay.carlife

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.shilapi.xcertplay.host.R

/**
 * Foreground service keeping the CarLife session alive while projected: the
 * head unit may kill background apps mid-drive, so the session runs under a
 * connectedDevice foreground notification like DiPlay's session service.
 */
class CarLifeSessionService : Service() {

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "CarLife", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            isRunning = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        isRunning = true
        val notification: Notification = if (android.os.Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.carlife_channel_title))
                .setContentText(getString(R.string.carlife_projecting))
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(getString(R.string.carlife_channel_title))
                .setContentText(getString(R.string.carlife_projecting))
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .build()
        }
        startForegroundGuarded(notification)
        return START_STICKY
    }

    /**
     * The microphone FGS type needs RECORD_AUDIO granted AND an eligible state
     * (Android 14+); degrade to connectedDevice-only when the mic side is not
     * yet grantable so the session service still runs.
     */
    private fun startForegroundGuarded(notification: Notification) {
        val withMic = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        val deviceOnly = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, withMic)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: SecurityException) {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, deviceOnly)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "carlife_session"
        private const val NOTIFICATION_ID = 0xCA12

        const val ACTION_STOP = "com.shihab.diplay.carlife.STOP"

        /** True while the foreground session service is alive (home ring state). */
        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, CarLifeSessionService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, CarLifeSessionService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
