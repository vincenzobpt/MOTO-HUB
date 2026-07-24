package io.motohub.android.session

import io.motohub.android.i18n.motoHubText

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import io.motohub.android.i18n.motoHubText
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.motohub.android.R

/**
 * Foreground service that holds a live projection while the phone is locked
 * or the app is in the background. Bound from MainActivity so we know when
 * the user is still actively watching, but always runs as a foreground service
 * while streaming to dodge the 60-second background startup deadline.
 */
class ProjectionForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val power = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MOTO-HUB:ProjectionService"
        )
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            createNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
        wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MILLIS)
        ProjectionEventLog.record("SERVICE", "Projection foreground service created, wake lock acquired")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        ProjectionEventLog.record("SERVICE", "Projection foreground service destroyed")
        stopForeground(STOP_FOREGROUND_REMOVE)
        wakeLock?.apply {
            runCatching { release() }
            wakeLock = null
        }
    }

    private fun createNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "MOTO-HUB projection service",
                NotificationManager.IMPORTANCE_LOW
            )
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(motoHubText("MOTO-HUB"))
            .setContentText(motoHubText("Projection is running"))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "projection_foreground_service_v1"
        private const val NOTIFICATION_ID = 4200
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 300_000L

        fun start(context: Context) {
            val intent = Intent(context, ProjectionForegroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ProjectionForegroundService::class.java)
            context.stopService(intent)
        }
    }
}
