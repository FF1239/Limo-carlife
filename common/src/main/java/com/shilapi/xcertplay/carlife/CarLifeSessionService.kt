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
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
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
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "carlife_session"
        private const val NOTIFICATION_ID = 0xCA12

        const val ACTION_STOP = "com.shihab.diplay.carlife.STOP"

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
