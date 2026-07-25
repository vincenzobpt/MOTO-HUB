package io.motohub.android.feature.ridedashboard

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import io.motohub.android.session.ProjectionEventLog

/**
 * Empty notification-listener service.
 *
 * [android.media.session.MediaSessionManager.getActiveSessions] refuses to
 * return anything to a normal app unless it is passed the [android.content.ComponentName]
 * of one of that app's own enabled [NotificationListenerService]s - this
 * class exists solely to be that component for the Now Playing widget.
 * Notifications themselves are never read, stored, or acted on.
 */
class NowPlayingListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        ProjectionEventLog.record("NOW_PLAYING", "Notification listener connected - Now Playing can read active media sessions.")
    }

    companion object {
        /** Whether the user has granted MOTO-HUB notification access in system settings. */
        fun isEnabled(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)

        /**
         * Forces an immediate (re)bind instead of waiting on the system's own
         * schedule. On several OEM ROMs the listener doesn't actually connect
         * right after the user grants access in Settings - without this,
         * [android.media.session.MediaSessionManager.getActiveSessions] can
         * keep throwing until the next app restart or device reboot.
         */
        fun requestRebind(context: Context) {
            runCatching {
                NotificationListenerService.requestRebind(
                    ComponentName(context, NowPlayingListenerService::class.java)
                )
            }
        }
    }
}
