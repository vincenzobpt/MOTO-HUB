// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.motohub.android.MainActivity
import io.motohub.android.R
import io.motohub.android.i18n.motoHubText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The thing that is awake while the rider's phone is in a pocket and the motorcycle is not up yet.
 *
 * It runs no connection code. Its whole job is to hold the process at
 * IMPORTANCE_FOREGROUND_SERVICE, which is both what keeps HyperOS and friends from reclaiming it
 * as cached and what makes Android accept the Wi-Fi request the activity's watch loop submits -
 * see [BikeWatch] for the rider's log this was built from.
 *
 * `connectedDevice` is the honest type: the work being waited on is joining the dashboard's
 * access point. It is already declared for the Android Auto session and the Core bridge.
 */
class BikeWatchService : Service() {

    private var scope: CoroutineScope? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Wrapped like every other foreground service here: a start that races the app leaving
        // the screen throws ForegroundServiceStartNotAllowedException, and taking the process
        // down over a convenience is the one outcome worse than not watching.
        val foreground = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        }
        foreground.exceptionOrNull()?.let { failure ->
            ProjectionEventLog.warning(
                "AUTO_CONNECT",
                "The background watch could not go foreground: ${failure.javaClass.simpleName} " +
                    "${failure.message}."
            )
            BikeWatch.onServiceGone()
            stopSelf()
            return
        }
        val created = CoroutineScope(SupervisorJob())
        scope = created
        created.launch {
            delay(BikeWatch.WATCH_WINDOW_MS)
            ProjectionEventLog.record(
                "AUTO_CONNECT",
                "The background watch reached its ${BikeWatch.WATCH_WINDOW_MS / 60_000}-minute " +
                    "limit without the motorcycle appearing; open MOTO-HUB to try again."
            )
            BikeWatch.onServiceGone()
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ProjectionEventLog.record("AUTO_CONNECT", "The rider stopped the background watch.")
            BikeWatch.onServiceGone()
            stopSelf()
        }
        // NOT sticky: a watch restarted by the system minutes after the process died would put a
        // notification in front of a rider who is no longer anywhere near the motorcycle.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        BikeWatch.onServiceGone()
        super.onDestroy()
    }

    /**
     * Says what is being waited for, and offers the way out.
     *
     * A rider who parks the bike and walks off with a "waiting for the motorcycle" notification
     * needs to be able to end it where they are looking, not by finding a screen in the app.
     */
    private fun notification(): Notification {
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, BikeWatchService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val bike = BikeWatch.watchingFor
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(
                if (bike == null) {
                    motoHubText("Waiting for the motorcycle")
                } else {
                    motoHubText("Waiting for %s", bike)
                }
            )
            .setContentText(motoHubText("MOTO-HUB will connect on its own when the dashboard comes up."))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, motoHubText("Stop"), stop)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                motoHubText("Waiting for the motorcycle"),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description =
                    motoHubText("Shown while MOTO-HUB watches for the motorcycle's Wi-Fi.")
            }
        )
    }

    private companion object {
        const val CHANNEL_ID = "moto_hub_bike_watch"
        const val NOTIFICATION_ID = 5_140
        const val ACTION_STOP = "io.motohub.android.bikewatch.STOP"
    }
}
