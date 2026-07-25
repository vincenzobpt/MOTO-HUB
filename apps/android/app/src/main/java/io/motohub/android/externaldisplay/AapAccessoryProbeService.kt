package io.motohub.android.externaldisplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.motohub.android.R
import io.motohub.android.aa.AapPhoneHandshake
import io.motohub.android.aa.UsbAoaAccessoryConnection
import io.motohub.android.session.ProjectionEventLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Diagnostic-only: opens the USB AOA accessory and runs [AapPhoneHandshake] to confirm the
 * physical head unit accepts MOTO-HUB as a real AAP phone-role peer, before any video channel
 * work is built on top. Not wired to the UI - triggered manually via
 * `adb shell am start-foreground-service -n io.motohub.android/.externaldisplay.AapAccessoryProbeService`
 * and read back from the app's diagnostics log (Settings > Diagnostics).
 */
class AapAccessoryProbeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "AAP accessory probe", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("AAP accessory probe running")
                .build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
        scope.launch {
            ProjectionEventLog.record("AAP_PROBE", "Opening AOA accessory for phone-role handshake probe.")
            val session = AoaAccessorySession.open(this@AapAccessoryProbeService).getOrElse {
                ProjectionEventLog.error("AAP_PROBE", "Could not open accessory: ${it.message}")
                stopSelf()
                return@launch
            }
            val outcome = AapPhoneHandshake.run(this@AapAccessoryProbeService, UsbAoaAccessoryConnection(session))
            if (outcome.success) {
                ProjectionEventLog.record("AAP_PROBE", "PROBE SUCCEEDED: ${outcome.detail}")
            } else {
                ProjectionEventLog.error("AAP_PROBE", "PROBE FAILED: ${outcome.detail}")
            }
            session.close()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: android.content.Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "aap_accessory_probe_v1"
        private const val NOTIFICATION_ID = 4401
    }
}
