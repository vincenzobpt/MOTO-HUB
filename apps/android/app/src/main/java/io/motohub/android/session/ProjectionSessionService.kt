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
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.motohub.android.R
import io.motohub.android.encoding.AdaptiveVideoController
import io.motohub.android.encoding.AvcEncoder
import io.motohub.android.androidauto.DisplayGeometry
import io.motohub.android.androidauto.TBoxDisplayGeometryStore
import io.motohub.android.feature.settings.MotoHubSettings
import io.motohub.android.tbox.TBoxEvent
import io.motohub.android.tbox.TBoxLinkResolver
import io.motohub.android.tbox.TBoxModelProfile
import io.motohub.android.tbox.ProfileOverride
import io.motohub.android.tbox.TBoxCapabilityStore
import io.motohub.android.tbox.TBoxNetworkEvent
import io.motohub.android.tbox.TBoxSessionHandle
import io.motohub.android.tbox.TBoxSessionRegistry
import io.motohub.android.tbox.TBoxStreamingLocks
import io.motohub.android.tbox.TBoxVideoAreaSource
import io.motohub.android.tbox.negotiateVideoConfiguration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Foreground owner of the Android grant, AVC encoder and active T-Box session. */
class ProjectionSessionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: AvcEncoder? = null
    private val adaptiveVideoController = AdaptiveVideoController(this, ::log)
    private var adaptiveJob: Job? = null
    private val streamingLocks = TBoxStreamingLocks(this, "Mirroring")
    private var tBoxHandle: TBoxSessionHandle? = null
    private var transportEventsJob: Job? = null
    private var networkEventsJob: Job? = null
    private var handleCleanupJob: Job? = null
    private var recoveryJob: Job? = null
    private val recoveryRequested = AtomicBoolean(false)
    private val transportUnavailable = AtomicBoolean(false)
    private val videoStreamStartRequested = AtomicBoolean(false)
    /**
     * Guards [startCapture] against duplicate concurrent starts. [mediaProjection] is
     * only assigned near the end of [startCapture] (after the T-Box handshake, which can
     * take up to [VIDEO_CONFIGURATION_TIMEOUT_MS]), so it cannot be used as the reentrancy
     * guard: a second `onStartCommand` before the first finishes would otherwise pass the
     * old "mediaProjection != null" check and race a second encoder/VirtualDisplay into
     * existence, leaking the first one.
     */
    private val capturing = AtomicBoolean(false)
    private val framesAccepted = AtomicLong(0)
    private val capabilityStore by lazy { TBoxCapabilityStore(this) }
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
        streamingLocks.acquire()

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
        // Cancel only after any T-Box teardown stopSession() just launched has finished -
        // cancelling serviceScope immediately would abort transport.stop()/disconnect() mid-flight.
        val pendingCleanup = handleCleanupJob
        if (pendingCleanup != null) {
            pendingCleanup.invokeOnCompletion { serviceScope.cancel() }
        } else {
            serviceScope.cancel()
        }
        super.onDestroy()
    }

    private suspend fun startCapture(resultCode: Int, resultData: Intent) {
        if (stopping || !capturing.compareAndSet(false, true)) return
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
        val quality = MotoHubSettings.videoQuality(this)
        val profile = configuration.encoderProfile.copy(
            bitRate = quality.bitrateFor(configuration.encoderProfile.bitRate)
        )
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
            "Area video ${profile.width}x${profile.height} ${profile.frameRate}fps; " +
                "quality=${quality.name}, bitrate=${profile.bitRate}."
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
                        false
                    } else {
                        val accepted = framesAccepted.incrementAndGet()
                        if (accepted == 1L || accepted % FRAME_LOG_INTERVAL == 0L) {
                            ProjectionEventLog.record("ENCODER", "Frames sent to T-Box: $accepted.")
                        }
                        true
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
            adaptiveVideoController.reset()
            if (videoStreamStartRequested.get()) {
                activeEncoder.requestSyncFrame("TFT consumer already requested video")
            }
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
            adaptiveJob?.cancel()
            adaptiveJob = serviceScope.launch {
                while (!stopping) {
                    delay(ADAPTIVE_TICK_MS)
                    adaptiveVideoController.onTick(encoder)
                }
            }
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

    /**
     * The T-Box ending the EasyConn session or a fatal transport error previously went
     * straight to [fail], tearing down the whole mirroring session (and the user's granted
     * MediaProjection consent) on the very first transient hiccup - unlike
     * [io.motohub.android.androidauto.AndroidAutoSessionService], which retries within a
     * budget before giving up. This brings mirroring in line with that mode: the running
     * [MediaProjection]/[VirtualDisplay]/[AvcEncoder] are left alone -
     * only the T-Box transport needs to reconnect - so a recovered EasyConn session resumes
     * streaming without a new consent prompt.
     */
    private fun handleRecoverableFailure(message: String) {
        if (stopping) return
        if (!MotoHubSettings.autoRecovery(this)) {
            fail(message)
            return
        }
        requestTBoxRecovery(message)
    }

    private fun requestTBoxRecovery(reason: String) {
        if (!recoveryRequested.compareAndSet(false, true)) {
            ProjectionEventLog.debug("WATCHDOG", "Recovery already active; ignored: $reason")
            return
        }
        ProjectionEventLog.warning("WATCHDOG", "Mirroring recovery requested: $reason")
        recoveryJob = serviceScope.launch {
            val deadline = SystemClock.elapsedRealtime() + RECOVERY_GIVE_UP_MILLIS
            var attempt = 0
            while (!stopping && SystemClock.elapsedRealtime() < deadline) {
                attempt++
                try {
                    recoverTBoxStream(reason, attempt)
                    recoveryRequested.set(false)
                    transportUnavailable.set(false)
                    ProjectionEventLog.record("WATCHDOG", "Mirroring T-Box stream recovered on attempt $attempt.")
                    return@launch
                } catch (cancelled: CancellationException) {
                    recoveryRequested.set(false)
                    throw cancelled
                } catch (failure: Throwable) {
                    ProjectionEventLog.warning(
                        "WATCHDOG",
                        "Mirroring recovery attempt $attempt failed: ${failure.message}"
                    )
                    delay(RECOVERY_RETRY_MILLIS)
                }
            }
            recoveryRequested.set(false)
            if (!stopping) {
                fail(
                    "Mirroring recovery timed out after " +
                        "${RECOVERY_GIVE_UP_MILLIS / 1_000L} seconds ($attempt attempt(s))."
                )
            }
        }
    }

    private suspend fun recoverTBoxStream(reason: String, attempt: Int) {
        val previousHandle = tBoxHandle ?: error("No T-Box session is available for recovery")
        ProjectionEventLog.record(
            "WATCHDOG",
            "Reconnecting mirroring EasyConn, attempt=$attempt, reason=$reason."
        )
        transportEventsJob?.cancel()
        networkEventsJob?.cancel()
        transportEventsJob = null
        networkEventsJob = null
        previousHandle.transport.stop()
        TBoxSessionRegistry.clear(previousHandle)

        val link = TBoxLinkResolver.reacquire(
            applicationContext,
            previousHandle.networkConnector,
            previousHandle.motorcycle,
            NETWORK_REJOIN_WAIT_MILLIS
        )
        previousHandle.transport.configureProtocolProfile(
            TBoxModelProfile.resolve(
                previousHandle.motorcycle.modelId,
                null,
                ProfileOverride.byKey(previousHandle.motorcycle.profileOverrideKey)
            )
        )
        val host = previousHandle.transport.discover(link, previousHandle.motorcycle.modelId).getOrThrow()
        val recoveredHandle = previousHandle.copy(host = host, link = link)
        tBoxHandle = recoveredHandle
        TBoxSessionRegistry.install(recoveredHandle)
        observeActiveSession(recoveredHandle)
        recoveredHandle.transport.start(host).getOrThrow()
    }

    private fun observeActiveSession(handle: TBoxSessionHandle) {
        transportEventsJob?.cancel()
        networkEventsJob?.cancel()
        transportEventsJob = serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
            handle.transport.events.collect { event ->
                if (stopping) return@collect
                when (event) {
                    is TBoxEvent.Capabilities -> {
                        capabilityStore.recordCapabilities(handle.motorcycle, event.value)
                        ProjectionEventLog.record(
                            "T-BOX",
                            "Capability snapshot saved for ${handle.motorcycle.ssid}."
                        )
                    }
                    TBoxEvent.VideoStreamStart -> {
                        videoStreamStartRequested.set(true)
                        encoder?.requestSyncFrame("TFT consumer requested mirroring video")
                    }
                    is TBoxEvent.Warning -> ProjectionEventLog.record("T-BOX", event.message)
                    is TBoxEvent.FatalError -> handleRecoverableFailure("T-Box error: ${event.message}")
                    TBoxEvent.Stopped -> handleRecoverableFailure("The T-Box ended the session.")
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
        adaptiveJob?.cancel()
        adaptiveJob = null
        recoveryJob?.cancel()
        recoveryJob = null
        recoveryRequested.set(false)
        mainHandler.removeCallbacks(autoDimRunnable)
        displayDimmer.restore()
        streamingLocks.release()
        virtualDisplay?.release()
        virtualDisplay = null
        encoder?.stop()
        encoder = null
        adaptiveVideoController.reset()
        mediaProjection?.unregisterCallback(projectionCallback)
        if (stopProjection) mediaProjection?.stop()
        mediaProjection = null

        val releasedHandle = tBoxHandle ?: TBoxSessionRegistry.current()
        tBoxHandle = null
        if (releasedHandle != null) {
            handleCleanupJob = serviceScope.launch {
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

    private fun log(message: String) {
        ProjectionEventLog.record("ENCODER", message)
    }

    private fun createNotification(): android.app.Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
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
                R.drawable.ic_notification,
                getString(R.string.restore_phone_display),
                serviceAction(ACTION_RESTORE_DISPLAY, 1)
            )
        } else if (PhoneDisplayDimmer.canDim(this)) {
            builder.addAction(
                R.drawable.ic_notification,
                getString(R.string.dim_phone_display),
                serviceAction(ACTION_DIM_DISPLAY, 2)
            )
        }

        builder
        .addAction(
            R.drawable.ic_notification,
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
        private const val ADAPTIVE_TICK_MS = 5_000L
        private const val NETWORK_REJOIN_WAIT_MILLIS = 75_000L
        private const val RECOVERY_RETRY_MILLIS = 5_000L
        private const val RECOVERY_GIVE_UP_MILLIS = 120_000L

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
