package io.motohub.android.externaldisplay

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.os.PowerManager
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.motohub.android.R
import io.motohub.android.encoding.AvcEncoder
import io.motohub.android.encoding.EncoderProfile
import io.motohub.android.session.ProjectionEventLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Foreground service that captures the phone screen and streams H.264 video
 * over USB AOA (Android Open Accessory) to an external head unit.
 *
 * Completely independent of the T-Box / EasyConn / ride daemon pipeline.
 * Uses the same AVC encoder as the rest of MOTO-HUB but writes encoded
 * access units directly to the AOA bulk endpoint instead of the T-Box transport.
 */
class AoaExternalService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: AvcEncoder? = null
    private var aoaOutputStream: FileOutputStream? = null
    private var aoaFileDescriptor: ParcelFileDescriptor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val framesSent = AtomicLong(0)
    private val capturing = AtomicBoolean(false)
    @Volatile private var stopping = false

    // ── Service lifecycle ──────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        ProjectionEventLog.record("AOA_SERVICE", "AOA external display service created.")
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.projection_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "MOTO-HUB external display streaming" }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSession("Stopped by user.")
                return START_NOT_STICKY
            }
        }
        ProjectionEventLog.record("AOA_SERVICE", "AOA start request received.")
        startForeground(NOTIFICATION_ID, createNotification())

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData = intent?.parcelableIntent(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            fail("Screen capture permission was denied or invalid.")
            return START_NOT_STICKY
        }
        serviceScope.launch { startCapture(resultCode, resultData) }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        ProjectionEventLog.record("AOA_SERVICE", "onDestroy called.")
        stopSession("Service destroyed by Android.")
        serviceScope.cancel()
        super.onDestroy()
    }

    // ── Capture & encoding ────────────────────────────────────────────

    private suspend fun startCapture(resultCode: Int, resultData: Intent) {
        if (stopping || !capturing.compareAndSet(false, true)) return
        AoaExternalRuntime.publish(AoaExternalRuntimeState.Starting)
        ProjectionEventLog.record("AOA_SERVICE", "Starting AOA external display pipeline.")

        try {
            // 1. Acquire partial wake lock so the CPU keeps encoding with screen off
            val powerManager = getSystemService(PowerManager::class.java)
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "MotoHub:AoaWakeLock"
            ).apply {
                acquire(/* timeoutHint = */ 2 * 60 * 60 * 1000L) // 2h cap
            }

            // 2. Open the AOA USB accessory
            val accessory = openAccessory() ?: return fail(
                "AOA USB accessory not found. Make sure the head unit is connected " +
                    "and close Autolink first."
            )

            // 3. Kill Autolink if running (releases any stale AOA claim)
            killAutolink()

            // 4. Request USB permission and open the accessory stream
            val usbManager = getSystemService(UsbManager::class.java)
            if (!usbManager.hasPermission(accessory)) {
                val granted = requestAoaPermission(usbManager, accessory)
                if (!granted) return fail("USB accessory permission was denied.")
            }
            val fd = usbManager.openAccessory(accessory)
                ?: return fail("Unable to open the USB AOA accessory.")
            aoaFileDescriptor = fd
            val outputStream = FileOutputStream(fd.fileDescriptor)
            aoaOutputStream = outputStream
            ProjectionEventLog.record("AOA_SERVICE", "AOA USB accessory opened OK.")

            // 5. Create MediaProjection from the granted consent
            val manager = getSystemService(MediaProjectionManager::class.java)
            val projection = manager.getMediaProjection(resultCode, resultData)
                ?: error("Android did not create the media projection.")
            projection.registerCallback(projectionCallback, null)
            mediaProjection = projection

            // 6. Build the encoder with Autolink-compatible settings
            val profile = EncoderProfile(
                width = EXTERNAL_WIDTH,
                height = EXTERNAL_HEIGHT,
                frameRate = EXTERNAL_FRAMERATE,
                bitRate = EXTERNAL_BITRATE
            )
            val activeEncoder = AvcEncoder(
                profile = profile,
                onAccessUnit = { accessUnit ->
                    try {
                        outputStream.write(accessUnit)
                        val count = framesSent.incrementAndGet()
                        if (count == 1L || count % FRAME_LOG_INTERVAL == 0L) {
                            ProjectionEventLog.record(
                                "AOA_ENCODER",
                                "AOA frames sent: $count."
                            )
                        }
                        true
                    } catch (e: IOException) {
                        serviceScope.launch {
                            if (!stopping) fail("AOA write error: ${e.message}")
                        }
                        false
                    }
                },
                onFailure = { failure ->
                    serviceScope.launch {
                        if (!stopping) fail("AVC encoder error: ${failure.message}")
                    }
                }
            )
            activeEncoder.start()
            encoder = activeEncoder

            val surface = activeEncoder.inputSurface
                ?: error("AVC encoder has no input surface.")

            // 7. Create the virtual display – renders the phone screen onto the encoder surface
            virtualDisplay = projection.createVirtualDisplay(
                "MOTO-HUB AOA capture",
                profile.width,
                profile.height,
                resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null
            ) ?: error("Virtual display was not created.")

            AoaExternalRuntime.publish(AoaExternalRuntimeState.Streaming)
            ProjectionEventLog.record(
                "AOA_SERVICE",
                "External display streaming ${profile.width}x${profile.height}" +
                    "@${profile.frameRate} to head unit via USB AOA."
            )
        } catch (failure: Throwable) {
            ProjectionEventLog.error(
                "AOA_SERVICE",
                "AOA capture setup threw an exception.",
                failure
            )
            fail("External display did not start: ${failure.message}")
        }
    }

    // ── AOA permission handling ───────────────────────────────────────

    /** Requests USB accessory permission synchronously (with a coroutine suspension). */
    private suspend fun requestAoaPermission(
        usbManager: UsbManager,
        accessory: UsbAccessory
    ): Boolean = withContext(Dispatchers.Main) {
        ProjectionEventLog.record("AOA_SERVICE", "Requesting USB AOA permission.")
        val latch = java.util.concurrent.CountDownLatch(1)
        val result = BooleanArray(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                result[0] = intent.getBooleanExtra(
                    UsbManager.EXTRA_PERMISSION_GRANTED,
                    false
                )
                latch.countDown()
            }
        }
        registerReceiver(receiver, IntentFilter(ACTION_USB_PERMISSION))
        usbManager.requestPermission(
            accessory,
            PendingIntent.getBroadcast(
                this@AoaExternalService,
                0,
                Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE
            )
        )
        // Wait up to 10s for the user response
        latch.await(10_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        if (!result[0]) {
            ProjectionEventLog.warning("AOA_SERVICE", "USB AOA permission denied by user.")
        }
        result[0]
    }

    /** Attempts to stop Autolink so it releases its AOA claim. */
    private fun killAutolink() {
        try {
            val am = getSystemService(android.app.ActivityManager::class.java)
            am.killBackgroundProcesses(AUTOLINK_PACKAGE)
            ProjectionEventLog.record(
                "AOA_SERVICE",
                "Requested background stop of $AUTOLINK_PACKAGE."
            )
        } catch (e: Exception) {
            ProjectionEventLog.warning(
                "AOA_SERVICE",
                "Unable to stop Autolink: ${e.message}"
            )
        }
    }

    /** Returns the first AOA accessory, or null if none is connected. */
    private fun openAccessory(): UsbAccessory? {
        val usbManager = getSystemService(UsbManager::class.java)
        val list = usbManager.accessoryList
        if (list.isNullOrEmpty()) return null
        // Pick the first one – typically there is only one
        return list[0]
    }

    // ── Teardown ──────────────────────────────────────────────────────

    private fun fail(message: String) {
        if (stopping) return
        ProjectionEventLog.error("AOA_SERVICE", message)
        AoaExternalRuntime.publish(AoaExternalRuntimeState.Failed(message))
        stopSession(message)
    }

    @Synchronized
    private fun stopSession(reason: String) {
        if (stopping) return
        stopping = true
        ProjectionEventLog.record("AOA_SERVICE", "Stopping AOA external display: $reason.")

        virtualDisplay?.release()
        virtualDisplay = null
        encoder?.stop()
        encoder = null
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        mediaProjection = null

        try { aoaOutputStream?.close() } catch (_: Exception) {}
        try { aoaFileDescriptor?.close() } catch (_: Exception) {}
        aoaOutputStream = null
        aoaFileDescriptor = null

        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null

        if (AoaExternalRuntime.state.value !is AoaExternalRuntimeState.Failed) {
            AoaExternalRuntime.publish(AoaExternalRuntimeState.Stopped(reason))
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── Notification ──────────────────────────────────────────────────

    private fun createNotification(): android.app.Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.projection_notification_title))
            .setContentText("Streaming to external display via USB")
            .setOngoing(true)
            .addAction(
                R.drawable.ic_notification,
                "Stop external display",
                serviceAction(ACTION_STOP, 0)
            )
            .build()
    }

    private fun serviceAction(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, AoaExternalService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    // ── Callback ──────────────────────────────────────────────────────

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopSession("Android revoked screen sharing.")
        }
    }

    // ── Companion ─────────────────────────────────────────────────────

    companion object {
        private const val CHANNEL_ID = "aoa_external_display_v1"
        private const val NOTIFICATION_ID = 4201
        private const val ACTION_STOP = "io.motohub.android.action.STOP_AOA"
        private const val ACTION_USB_PERMISSION =
            "io.motohub.android.action.AOA_USB_PERMISSION"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val AUTOLINK_PACKAGE = "com.link.autolink"

        // Autolink-compatible video parameters
        private const val EXTERNAL_WIDTH = 1280
        private const val EXTERNAL_HEIGHT = 720
        private const val EXTERNAL_FRAMERATE = 30
        private const val EXTERNAL_BITRATE = 4_194_304
        private const val FRAME_LOG_INTERVAL = 120L

        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, AoaExternalService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, AoaExternalService::class.java).setAction(ACTION_STOP)
            )
        }

        /** Returns true if an AOA USB accessory is currently connected. */
        fun isAccessoryConnected(context: Context): Boolean {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            return usbManager?.accessoryList?.isNotEmpty() == true
        }
    }
}

/** Helper to read a [Intent] extra as a [Intent] parcelable. */
private fun Intent.parcelableIntent(key: String): Intent? =
    if (android.os.Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(key, Intent::class.java)
    } else {
        @Suppress("DEPRECATION") getParcelableExtra(key) as? Intent
    }
