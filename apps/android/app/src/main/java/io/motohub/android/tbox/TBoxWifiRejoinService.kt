// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.motohub.android.MainActivity
import io.motohub.android.R
import io.motohub.android.i18n.MotoHubStrings
import io.motohub.android.session.ProjectionEventLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps this process askable while the T-Box Wi-Fi rejoin ladder runs.
 *
 * It connects to nothing and holds no radio. Its entire job is to exist, because Android's
 * `WifiNetworkFactory` drops a `WifiNetworkSpecifier` request from a process past
 * `IMPORTANCE_FOREGROUND_SERVICE` without ever looking for the access point — and the app's only
 * foreground service at that moment is the session's, which the session tears down the instant
 * the link is lost. Support 13274a94, 2026-09-20: the Android Auto service's onDestroy at
 * 13:05:31.468, the ladder's own reading 229 ms later at importance 400, then three minutes of
 * waiting for a foreground that never came because the phone was in the rider's pocket and the
 * motorcycle was moving. The rider's log ends with "open MOTO-HUB and tap Connect", which on a
 * motorcycle means stopping it.
 *
 * ### Why this is not gated on seamless resume
 *
 * Getting back onto the motorcycle's Wi-Fi and resuming the projection are two different jobs
 * with two different costs. Resuming the stream has to bring an activity up from the background,
 * which is why the "Seamless resume" switch asks for the "Display over other apps" permission.
 * Rejoining a Wi-Fi network asks for nothing of the kind. They were nevertheless decided by the
 * same switch, so a rider who declined an intrusive overlay permission silently lost automatic
 * reconnection as well, with the "Recovery watchdog" switch turned on doing nothing. This
 * service is started under the recovery watchdog alone.
 *
 * It must be started while the caller is still in the foreground: Android 16 refuses a
 * foreground service started from the background, which is exactly the hole being plugged. So
 * the session services start it *before* they fail, not after.
 */
class TBoxWifiRejoinService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val started = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        }
        started.exceptionOrNull()?.let { failure ->
            // Nothing is lost that was not already lost: without this service the rejoin was
            // going to be refused anyway. Say so and go, rather than leaving a started service
            // that Android will kill for not calling startForeground().
            ProjectionEventLog.warning(
                "NETWORK",
                "Could not hold the foreground for the T-Box Wi-Fi rejoin: " +
                    "${failure.javaClass.simpleName} ${failure.message}. The rejoin will only be " +
                    "able to ask while MOTO-HUB is open."
            )
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (watchJob != null) return START_NOT_STICKY
        val connector = TBoxNetworkConnectors.shared(this)
        val startedAt = SystemClock.elapsedRealtime()
        watchJob = scope.launch {
            while (true) {
                delay(POLL_MILLIS)
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                connector.currentNetwork()?.let { network ->
                    ProjectionEventLog.record(
                        "NETWORK",
                        "Back on the bike's Wi-Fi ${elapsed / 1_000L}s after the link was lost " +
                            "(network=$network); releasing the foreground hold. The stream was " +
                            "not resumed: seamless resume is off."
                    )
                    return@launch
                }
                // Not before GRACE_MILLIS: the ladder is launched asynchronously from onLost and
                // may not have raised its own flag yet, and stopping here would take the
                // foreground away a few milliseconds before the submission that needs it.
                if (elapsed > GRACE_MILLIS && !connector.isRejoinRunning()) {
                    ProjectionEventLog.record(
                        "NETWORK",
                        "The T-Box Wi-Fi rejoin is over and the bike's network did not come " +
                            "back; releasing the foreground hold after ${elapsed / 1_000L}s."
                    )
                    return@launch
                }
                if (elapsed > MAX_HOLD_MILLIS) {
                    ProjectionEventLog.warning(
                        "NETWORK",
                        "Releasing the foreground hold for the T-Box Wi-Fi rejoin after its " +
                            "${MAX_HOLD_MILLIS / 1_000L}s cap. Nothing should keep a notification " +
                            "up longer than the ladder it is holding the door open for."
                    )
                    return@launch
                }
            }
        }
        watchJob?.invokeOnCompletion { stopSelf() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        watchJob = null
        super.onDestroy()
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MOTO-HUB")
            .setContentText(MotoHubStrings.get("Reconnecting to the motorcycle's Wi-Fi"))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                MotoHubStrings.get(this, "Reconnecting to the motorcycle"),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    companion object {
        private const val CHANNEL_ID = "moto_hub_wifi_rejoin"
        private const val NOTIFICATION_ID = 5_140
        private const val POLL_MILLIS = 1_000L

        /** How long the ladder is given to raise its own flag before absence means "finished". */
        private const val GRACE_MILLIS = 5_000L

        /**
         * Longer than the ladder's own 180s budget so the hold never ends first, and short enough
         * that a bug here cannot leave a notification up for the rest of the ride. Written out
         * rather than derived: REJOIN_GIVE_UP_MS lives in a private companion, and the two
         * numbers only have to be ordered, not equal.
         */
        private const val MAX_HOLD_MILLIS = 200_000L

        /**
         * Called by a session service that is about to end because the T-Box Wi-Fi dropped, while
         * it still holds its own foreground service — see the class comment: from the background
         * this start is refused, and the refusal is the very thing being worked around.
         */
        fun hold(context: Context, source: String) {
            runCatching {
                context.startForegroundService(Intent(context, TBoxWifiRejoinService::class.java))
            }.onSuccess {
                ProjectionEventLog.record(
                    "NETWORK",
                    "Holding MOTO-HUB in the foreground so the T-Box Wi-Fi rejoin can be asked " +
                        "for after the $source session ends. Reconnecting to the bike's network " +
                        "needs no overlay permission, so it is not refused along with seamless " +
                        "resume."
                )
            }.onFailure { failure ->
                ProjectionEventLog.warning(
                    "NETWORK",
                    "Could not start the T-Box Wi-Fi rejoin hold: ${failure.message}"
                )
            }
        }
    }
}
