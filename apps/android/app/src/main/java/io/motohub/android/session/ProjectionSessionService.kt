package io.motohub.android.session

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.motohub.android.R
import io.motohub.android.encoding.AvcEncoder
import io.motohub.android.androidauto.DisplayGeometry
import io.motohub.android.androidauto.TBoxDisplayGeometryStore
import io.motohub.android.tbox.TBoxEvent
import io.motohub.android.tbox.TBoxNetworkEvent
import io.motohub.android.tbox.TBoxSessionHandle
import io.motohub.android.tbox.TBoxSessionRegistry
import io.motohub.android.tbox.TBoxVideoAreaSource
import io.motohub.android.tbox.negotiateVideoConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Foreground owner of the Android grant, AVC encoder and active T-Box session. */
class ProjectionSessionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: AvcEncoder? = null
    private var tBoxHandle: TBoxSessionHandle? = null
    private var transportEventsJob: Job? = null
    private var networkEventsJob: Job? = null
    private val transportUnavailable = AtomicBoolean(false)
    private val framesAccepted = AtomicLong(0)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var displayDimmer: PhoneDisplayDimmer
    @Volatile
    private var stopping = false

    private val autoDimRunnable = Runnable {
        if (!stopping && mediaProjection != null && PhoneDisplayDimPreferences.isEnabled(this)) {
            setDisplayDimmed(true)
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopSession(
                stopProjection = false,
                reason = "Android revoked screen sharing."
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSession(stopProjection = true, reason = "Streaming stopped by the user.")
                return START_NOT_STICKY
            }
            ACTION_DIM_DISPLAY -> {
                if (mediaProjection != null) setDisplayDimmed(true) else stopSelf(startId)
                return START_NOT_STICKY
            }
            ACTION_RESTORE_DISPLAY -> {
                setDisplayDimmed(false)
                if (mediaProjection == null) stopSelf(startId)
                return START_NOT_STICKY
            }
        }

        ProjectionEventLog.record("SERVICE", "Projection request received.")
        startForeground(NOTIFICATION_ID, createNotification())

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData = intent?.parcelableIntent(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            fail("The sharing permission is invalid.")
            return START_NOT_STICKY
        }

        serviceScope.launch { startCapture(resultCode, resultData) }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        ProjectionEventLog.record("SERVICE", "Projection foreground service onDestroy called.")
        stopSession(stopProjection = true, reason = "Projection service stopped by Android.")
        super.onDestroy()
    }

    private suspend fun startCapture(resultCode: Int, resultData: Intent) {
        if (stopping || mediaProjection != null) return
        ProjectionRuntime.publish(ProjectionRuntimeState.Starting)
        ProjectionEventLog.record("SERVICE", "Starting EasyConn handshake.")

        val handle = TBoxSessionRegistry.current()
            ?: return fail("No T-Box session is ready. Reconnect the motorcycle before sharing.")
        tBoxHandle = handle
        observeActiveSession(handle)
        val geometryStore = TBoxDisplayGeometryStore(this)
        val savedArea = geometryStore.load(handle.motorcycle.ssid)?.let { geometry ->
            TBoxEvent.VideoArea(geometry.width, geometry.height)
        }
        val configurationResult = handle.transport.negotiateVideoConfiguration(
            host = handle.host,
            savedArea = savedArea,
            timeoutMillis = VIDEO_CONFIGURATION_TIMEOUT_MS
        )
        configurationResult.exceptionOrNull()?.let {
            return fail("T-Box video negotiation failed: ${it.message}")
        }
        val configuration = configurationResult.getOrThrow()
        val area = configuration.rawArea
        val profile = configuration.encoderProfile
        ProjectionEventLog.record("T-BOX", "Handshake completed.")
        if (configuration.source == TBoxVideoAreaSource.LIVE) {
            geometryStore.save(
                handle.motorcycle.ssid,
                DisplayGeometry(area.width, area.height)
            )
        } else {
            ProjectionEventLog.warning(
                "T-BOX",
                "The live TFT area was not received; using the saved geometry for " +
                    "${handle.motorcycle.ssid}."
            )
        }
        ProjectionEventLog.record(
            "T-BOX",
            "${configuration.source} TFT area ${area.width}x${area.height}; aligned AVC canvas " +
                "${profile.width}x${profile.height}."
        )
        ProjectionEventLog.record(
            "T-BOX",
            "Area video ${profile.width}x${profile.height} ${profile.frameRate}fps."
        )
        if (stopping) return

        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val projection = manager.getMediaProjection(resultCode, resultData)
                ?: error("Android did not create the media projection")
            projection.registerCallback(projectionCallback, null)
            val activeEncoder = AvcEncoder(
                profile = profile,
                onAccessUnit = { accessUnit ->
                    if (!handle.transport.offerAccessUnit(accessUnit) &&
                        transportUnavailable.compareAndSet(false, true)
                    ) {
                        serviceScope.launch {
                            if (!stopping) fail("The T-Box session no longer accepts video frames.")
                        }
                    } else {
                        val accepted = framesAccepted.incrementAndGet()
                        if (accepted == 1L || accepted % FRAME_LOG_INTERVAL == 0L) {
                            ProjectionEventLog.record("ENCODER", "Frames sent to T-Box: $accepted.")
                        }
                    }
                },
                onFailure = { failure ->
                    // The drain thread must not release its own codec while it owns an output buffer.
                    serviceScope.launch {
                        if (!stopping) fail("AVC encoder stopped: ${failure.message}")
                    }
                }
            )
            activeEncoder.start()
            val surface = activeEncoder.inputSurface ?: error("AVC encoder has no input surface")

            mediaProjection = projection
            encoder = activeEncoder
            virtualDisplay = projection.createVirtualDisplay(
                "MOTO-HUB capture",
                profile.width,
                profile.height,
                resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null
            ) ?: error("Virtual display was not created")
            ProjectionRuntime.publish(ProjectionRuntimeState.Streaming)
            ProjectionEventLog.record("SERVICE", "Android capture and streaming are active.")
            scheduleAutoDim()
        } catch (failure: Throwable) {
            ProjectionEventLog.error("MIRROR", "Screen capture setup threw an exception.", failure)
            fail("Screen capture did not start: ${failure.message}")
        }
    }

    private fun fail(message: String) {
        if (stopping) return
        ProjectionEventLog.error("MIRROR", message)
        ProjectionRuntime.publish(ProjectionRuntimeState.Failed(message))
        stopSession(stopProjection = true, reason = message)
    }

    private fun observeActiveSession(handle: TBoxSessionHandle) {
        transportEventsJob?.cancel()
        networkEventsJob?.cancel()
        transportEventsJob = serviceScope.launch {
            handle.transport.events.collect { event ->
                if (stopping) return@collect
                when (event) {
                    is TBoxEvent.Warning -> ProjectionEventLog.record("T-BOX", event.message)
                    is TBoxEvent.FatalError -> fail("T-Box error: ${event.message}")
                    TBoxEvent.Stopped -> fail("The T-Box ended the session.")
                    is TBoxEvent.VideoArea -> Unit
                    is TBoxEvent.Touch -> Unit
                }
            }
        }
        networkEventsJob = serviceScope.launch {
            handle.networkConnector.events.collect { event ->
                if (event is TBoxNetworkEvent.Lost && !stopping) {
                    fail("T-Box Wi-Fi connection lost.")
                }
            }
        }
    }

    @Synchronized
    private fun stopSession(stopProjection: Boolean, reason: String) {
        if (stopping) return
        stopping = true
        ProjectionEventLog.record(
            "SERVICE",
            "Stopping mirroring session: stopProjection=$stopProjection, reason=$reason, frames=${framesAccepted.get()}."
        )
        transportEventsJob?.cancel()
        transportEventsJob = null
        networkEventsJob?.cancel()
        networkEventsJob = null
        mainHandler.removeCallbacks(autoDimRunnable)
        displayDimmer.restore()
        virtualDisplay?.release()
        virtualDisplay = null
        encoder?.stop()
        encoder = null
        mediaProjection?.unregisterCallback(projectionCallback)
        if (stopProjection) mediaProjection?.stop()
        mediaProjection = null

        val releasedHandle = tBoxHandle ?: TBoxSessionRegistry.current()
        tBoxHandle = null
        if (releasedHandle != null) {
            serviceScope.launch {
                releasedHandle.transport.stop()
                releasedHandle.networkConnector.disconnect()
                TBoxSessionRegistry.clear(releasedHandle)
            }
        }
        if (ProjectionRuntime.state.value !is ProjectionRuntimeState.Failed) {
            ProjectionEventLog.record("STOP", reason)
            ProjectionRuntime.publish(ProjectionRuntimeState.Stopped(reason))
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotification(): android.app.Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle(getString(R.string.projection_notification_title))
        .setContentText(
            if (::displayDimmer.isInitialized && displayDimmer.isDimmed) {
                getString(R.string.projection_notification_dimmed_text)
            } else {
                getString(R.string.projection_notification_text)
            }
        )
        .setOngoing(true)

        if (::displayDimmer.isInitialized && displayDimmer.isDimmed) {
            builder.addAction(
                R.drawable.ic_launcher,
                getString(R.string.restore_phone_display),
                serviceAction(ACTION_RESTORE_DISPLAY, 1)
            )
        } else if (PhoneDisplayDimmer.canDim(this)) {
            builder.addAction(
                R.drawable.ic_launcher,
                getString(R.string.dim_phone_display),
                serviceAction(ACTION_DIM_DISPLAY, 2)
            )
        }

        builder
        .addAction(
            R.drawable.ic_launcher,
            getString(R.string.stop_projection),
            serviceAction(ACTION_STOP, 0)
        )
        return builder.build()
    }

    private fun serviceAction(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, ProjectionSessionService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun scheduleAutoDim() {
        mainHandler.removeCallbacks(autoDimRunnable)
        if (PhoneDisplayDimPreferences.isEnabled(this) && PhoneDisplayDimmer.canDim(this)) {
            mainHandler.postDelayed(autoDimRunnable, AUTO_DIM_DELAY_MS)
        }
    }

    private fun setDisplayDimmed(dimmed: Boolean) {
        mainHandler.removeCallbacks(autoDimRunnable)
        if (dimmed) {
            if (!displayDimmer.dim()) {
                Log.w(TAG, "Unable to activate phone display dimmer")
                ProjectionEventLog.warning("DISPLAY", "Unable to activate the phone display dimmer.")
                return
            }
            ProjectionEventLog.record("DISPLAY", "Phone display dimmer enabled.")
        } else {
            displayDimmer.restore()
            ProjectionEventLog.record("DISPLAY", "Phone display dimmer disabled.")
        }
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            createNotification()
        )
    }

    override fun onCreate() {
        super.onCreate()
        ProjectionEventLog.record("SERVICE", "Projection foreground service created.")
        displayDimmer = PhoneDisplayDimmer(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.projection_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = getString(R.string.projection_channel_description) }
        )
    }

    private fun Intent.parcelableIntent(key: String): Intent? =
        getParcelableExtra(key, Intent::class.java)

    companion object {
        // New id prevents Android from retaining the silent channel created by older builds.
        private const val CHANNEL_ID = "projection_session_v2"
        private const val TAG = "ProjectionSession"
        private const val NOTIFICATION_ID = 4101
        private const val ACTION_STOP = "io.motohub.android.action.STOP_PROJECTION"
        private const val ACTION_DIM_DISPLAY = "io.motohub.android.action.DIM_DISPLAY"
        private const val ACTION_RESTORE_DISPLAY = "io.motohub.android.action.RESTORE_DISPLAY"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val VIDEO_CONFIGURATION_TIMEOUT_MS = 10_000L
        private const val AUTO_DIM_DELAY_MS = 5_000L
        private const val FRAME_LOG_INTERVAL = 120L

        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, ProjectionSessionService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ProjectionSessionService::class.java).setAction(ACTION_STOP)
            )
        }

        fun dimDisplay(context: Context) {
            context.startService(
                Intent(context, ProjectionSessionService::class.java).setAction(ACTION_DIM_DISPLAY)
            )
        }

        fun restoreDisplay(context: Context) {
            context.startService(
                Intent(context, ProjectionSessionService::class.java).setAction(ACTION_RESTORE_DISPLAY)
            )
        }
    }
}
