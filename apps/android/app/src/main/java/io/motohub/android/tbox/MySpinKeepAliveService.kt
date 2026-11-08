// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.motohub.android.MainActivity
import io.motohub.android.R
import io.motohub.android.i18n.MotoHubStrings
import io.motohub.android.session.ProjectionEventLog
import java.util.concurrent.atomic.AtomicInteger

/**
 * The foreground the mySPIN Bluetooth link borrows while it waits for the dash.
 *
 * Without it the link lives in a coroutine of the screen that started it, and nothing else holds
 * the process: once the rider leaves the app a "cached" process is frozen or killed, and the dash
 * never finds the Bluetooth service it looks for.
 *
 * Counted: the service runs while at least one handle is open and stops with the last one.
 */
class MySpinKeepAliveService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, MotoHubStrings.get("Bike link running"), NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val reason = intent?.getStringExtra(EXTRA_REASON).orEmpty().ifBlank { MotoHubStrings.get("Bike link running") }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(reason)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        // This start has now reached startForeground(), so a stopService() is safe again.
        if (intent?.getBooleanExtra(EXTRA_COUNTED, false) == true) pendingStarts.decrementAndGet()
        if (wakeLock?.isHeld != true) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MotoHub:MySpinKeepAlive")
                .apply { acquire(WAKE_LOCK_CEILING_MS) }
        }
        // The last handle may have closed while this start was on its way.
        if (synchronized(holders) { holders.isEmpty() }) stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "myspin_keep_alive_v1"
        private const val NOTIFICATION_ID = 4402
        private const val EXTRA_REASON = "reason"
        private const val EXTRA_COUNTED = "counted"

        // A leaked handle must end eventually; the link itself gives up long before this.
        private const val WAKE_LOCK_CEILING_MS = 12 * 60 * 60 * 1000L

        private val holders = mutableSetOf<Any>()

        /**
         * startForegroundService() calls whose onStartCommand has not run yet. Stopping the
         * service in that window makes Android kill the app with
         * ForegroundServiceDidNotStartInTimeException (a mySPIN link that failed at once on a
         * misspelt bike name).
         */
        private val pendingStarts = AtomicInteger()

        /** Stop now only when no start is still on its way; otherwise onStartCommand stops itself. */
        internal fun stopsNow(lastHolder: Boolean, pending: Int): Boolean = lastHolder && pending == 0

        /** Opens a handle; closing the last one stops the service. */
        fun hold(context: Context, holderId: String, reason: String): AutoCloseable {
            val token = Any()
            synchronized(holders) { holders += token }
            runCatching {
                pendingStarts.incrementAndGet()
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, MySpinKeepAliveService::class.java)
                        .putExtra(EXTRA_REASON, reason)
                        .putExtra(EXTRA_COUNTED, true)
                )
                ProjectionEventLog.record("MYSPIN", "$holderId keeps the app in the foreground: $reason")
            }.onFailure {
                pendingStarts.decrementAndGet()
                ProjectionEventLog.warning(
                    "MYSPIN",
                    "$holderId asked to stay in the foreground and Android refused (${it.javaClass.simpleName}); " +
                        "the link can be stopped once the app is in the background."
                )
            }
            return AutoCloseable {
                val last = synchronized(holders) { holders.remove(token) && holders.isEmpty() }
                if (stopsNow(last, pendingStarts.get())) context.stopService(Intent(context, MySpinKeepAliveService::class.java))
            }
        }
    }
}
