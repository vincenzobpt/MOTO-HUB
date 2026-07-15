package io.motohub.android.androidauto

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.motohub.android.MainActivity
import io.motohub.android.R
import io.motohub.android.aa.AaReceiver
import io.motohub.android.aa.SingleKeyKeyManager
import io.motohub.android.aa.ServiceDiscoveryResponse
import io.motohub.android.encoding.AvcEncoder
import io.motohub.android.encoding.EncoderProfile
import io.motohub.android.session.ProjectionEventLog
import io.motohub.android.session.ProjectionRuntime
import io.motohub.android.session.ProjectionRuntimeState
import io.motohub.android.tbox.TBoxEvent
import io.motohub.android.tbox.TBoxNetworkEvent
import io.motohub.android.tbox.TBoxSessionHandle
import io.motohub.android.tbox.TBoxSessionRegistry
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Owns the Android Auto loopback receiver and its independent T-Box video pipeline. */
class AndroidAutoSessionService : Service(), AndroidAutoPreviewController {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var compositor: AaCompositor? = null
    private var receiver: AaReceiver? = null
    private var encoder: AvcEncoder? = null
    private var tBoxHandle: TBoxSessionHandle? = null
    private var transportEventsJob: Job? = null
    private var networkEventsJob: Job? = null
    private var videoReadyTimeoutJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val displayGeometryStore by lazy { TBoxDisplayGeometryStore(this) }
    private val bikeStartRequested = AtomicBoolean(false)
    private val transportUnavailable = AtomicBoolean(false)
    private val framesAccepted = AtomicLong(0)

    @Volatile
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSession("Android Auto stopped by the user.")
            return START_NOT_STICKY
        }
        if (AndroidAutoRuntime.isActive()) return START_STICKY

        ProjectionEventLog.record("ANDROID AUTO", "Preparing local AAP receiver.")
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            createNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
        acquireWakeLock()
        AndroidAutoRuntime.publish(AndroidAutoRuntimeState.Preparing)
        ProjectionRuntime.publish(ProjectionRuntimeState.Starting)
        serviceScope.launch { prepareReceiver() }
        return START_STICKY
    }

    private fun prepareReceiver() {
        val handle = TBoxSessionRegistry.current()
            ?: return fail("No T-Box is ready. Connect and find the T-Box before starting Android Auto.")
        tBoxHandle = handle
        val learnedGeometry = displayGeometryStore.load(handle.motorcycle.ssid)
        val learnedCanvas = learnedGeometry?.let(::alignedCanvasGeometry)
        val displayProfile = learnedCanvas?.let(ActiveAndroidAutoDisplayProfile::configure)
            ?: ActiveAndroidAutoDisplayProfile.configureUncalibrated()
        if (learnedGeometry == null) {
            ProjectionEventLog.record(
                "ANDROID AUTO",
                "T-Box area not queried yet: starting AAP without assumed cropping. " +
                    "Geometry will be learned from the VideoArea message."
            )
        } else {
            ProjectionEventLog.record(
                "ANDROID AUTO",
                "T-Box area learned: ${learnedGeometry.width}x${learnedGeometry.height}; " +
                    "aligned AVC canvas: ${learnedCanvas?.width}x${learnedCanvas?.height}; " +
                    "compositor crop: ${displayProfile.marginWidth}x${displayProfile.marginHeight}."
            )
        }
        observeActiveSession(handle)
        handle.networkConnector.releaseProcessBinding()
        ProjectionEventLog.record(
            "NETWORK",
            "T-Box binding suspended while Android Auto starts locally."
        )

        try {
            val displayMode = AndroidAutoDisplayModeStore(this).load(handle.motorcycle)
            ProjectionEventLog.record(
                "ANDROID AUTO",
                "TFT display mode selected for ${handle.motorcycle.ssid}: $displayMode."
            )
            val activeCompositor = AaCompositor(::log, displayMode)
            activeCompositor.start()
            val decoderSurface = activeCompositor.inputSurface
                ?: error("Android Auto compositor did not create the video surface")
            compositor = activeCompositor

            val activeReceiver = AaReceiver(
                context = applicationContext,
                encoderSurface = decoderSurface,
                log = ::log,
                onVideoReady = {
                    if (bikeStartRequested.compareAndSet(false, true)) {
                        videoReadyTimeoutJob?.cancel()
                        serviceScope.launch { startBikeStream(handle) }
                    }
                },
                onSessionEnded = { clean ->
                    if (!stopping) {
                        serviceScope.launch {
                            val reason = if (clean) {
                                "Android Auto ended the AAP session before projection completed."
                            } else {
                                "Android Auto connection closed unexpectedly."
                            }
                            fail(reason)
                        }
                    }
                },
                mapTouchToSource = activeCompositor::mapCanvasToSource
            )
            if (!SingleKeyKeyManager.isAvailable(applicationContext)) {
                error(
                    "Android Auto identity is not included in this build. " +
                        "Build with -PincludeAndroidAutoIdentity=true for a private sideload APK."
                )
            }
            if (!activeReceiver.start()) error("Android Auto local port 5288 is unavailable")
            receiver = activeReceiver
            AndroidAutoPreviewRuntime.install(this)
            AndroidAutoRuntime.publish(AndroidAutoRuntimeState.ReceiverReady)
            ProjectionEventLog.record("ANDROID AUTO", "Receiver ready. Starting Google Android Auto.")
            videoReadyTimeoutJob = serviceScope.launch {
                delay(AAP_VIDEO_READY_TIMEOUT_MS)
                if (!stopping && !bikeStartRequested.get()) {
                    fail(
                        "Android Auto connected without delivering video. " +
                            "The AAP session was closed; start Android Auto again."
                    )
                }
            }
        } catch (failure: Throwable) {
            fail("Android Auto receiver did not start: ${failure.message}")
        }
    }

    private suspend fun startBikeStream(handle: TBoxSessionHandle) {
        if (stopping) return
        val rebound = handle.networkConnector.rebindProcessToTBox()
        rebound.exceptionOrNull()?.let {
            return fail("T-Box network restore failed: ${it.message}")
        }
        ProjectionEventLog.record("ANDROID AUTO", "First AAP video frame received. Starting EasyConn session.")

        val configuration = coroutineScope {
            val area = async {
                withTimeoutOrNull(VIDEO_CONFIGURATION_TIMEOUT_MS) {
                    handle.transport.events.filterIsInstance<TBoxEvent.VideoArea>().first()
                }
            }
            val transportResult = handle.transport.start(handle.host, EncoderProfile())
            transportResult.exceptionOrNull()?.let {
                area.cancel()
                return@coroutineScope Result.failure<BikeStreamConfiguration>(it)
            }
            val negotiated = area.await()
            Result.success(
                BikeStreamConfiguration(
                    encoderProfile = negotiated?.let {
                        EncoderProfile.forTBoxArea(it.width, it.height)
                    } ?: EncoderProfile(),
                    negotiatedArea = negotiated
                )
            )
        }
        configuration.exceptionOrNull()?.let {
            return fail("T-Box handshake for Android Auto failed: ${it.message}")
        }
        if (stopping) return

        val (encoderProfile, negotiatedArea) = configuration.getOrThrow()
        val actualGeometry = DisplayGeometry(encoderProfile.width, encoderProfile.height)
        val expectedGeometry = ActiveAndroidAutoDisplayProfile.current.expectedTft
        if (negotiatedArea != null) {
            displayGeometryStore.save(
                handle.motorcycle.ssid,
                DisplayGeometry(negotiatedArea.width, negotiatedArea.height)
            )
            if (actualGeometry != expectedGeometry) {
                ProjectionEventLog.record(
                    "ANDROID AUTO",
                    "Updating compositor in this session: raw TFT area " +
                        "${negotiatedArea.width}x${negotiatedArea.height}, aligned AVC canvas " +
                        "${actualGeometry.width}x${actualGeometry.height}."
                )
            }
            ActiveAndroidAutoDisplayProfile.configure(actualGeometry)
        }
        ProjectionEventLog.record(
            "T-BOX",
            "Area Android Auto ${encoderProfile.width}x${encoderProfile.height}."
        )
        try {
            val activeEncoder = AvcEncoder(
                profile = encoderProfile,
                onAccessUnit = { accessUnit ->
                    if (!handle.transport.offerAccessUnit(accessUnit) &&
                        transportUnavailable.compareAndSet(false, true)
                    ) {
                        serviceScope.launch {
                            if (!stopping) fail("The T-Box no longer accepts Android Auto frames.")
                        }
                    } else {
                        val accepted = framesAccepted.incrementAndGet()
                        if (accepted == 1L || accepted % FRAME_LOG_INTERVAL == 0L) {
                            ProjectionEventLog.record("ANDROID AUTO", "Frames sent: $accepted.")
                        }
                    }
                },
                onFailure = { failure ->
                    serviceScope.launch {
                        if (!stopping) fail("Android Auto encoder stopped: ${failure.message}")
                    }
                }
            )
            activeEncoder.start()
            val encoderSurface = activeEncoder.inputSurface
                ?: error("Android Auto encoder has no input surface")
            encoder = activeEncoder
            compositor?.setOutput(
                encoderSurface,
                encoderProfile.width,
                encoderProfile.height,
                ServiceDiscoveryResponse.AA_WIDTH,
                ServiceDiscoveryResponse.AA_HEIGHT
            )
            AndroidAutoRuntime.publish(AndroidAutoRuntimeState.Streaming)
            ProjectionRuntime.publish(ProjectionRuntimeState.Streaming)
            ProjectionEventLog.record("ANDROID AUTO", "Android Auto streaming active on the TFT.")
        } catch (failure: Throwable) {
            fail("Android Auto pipeline did not start: ${failure.message}")
        }
    }

    private fun observeActiveSession(handle: TBoxSessionHandle) {
        transportEventsJob?.cancel()
        networkEventsJob?.cancel()
        transportEventsJob = serviceScope.launch {
            handle.transport.events.collect { event ->
                if (stopping) return@collect
                when (event) {
                    is TBoxEvent.Touch -> receiver?.sendTouch(event.action, event.x, event.y)
                    is TBoxEvent.Warning -> ProjectionEventLog.record("T-BOX", event.message)
                    is TBoxEvent.FatalError -> fail("T-Box error: ${event.message}")
                    TBoxEvent.Stopped -> fail("The T-Box ended Android Auto.")
                    is TBoxEvent.VideoArea -> Unit
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

    private fun fail(message: String) {
        if (stopping) return
        ProjectionEventLog.error("ANDROID AUTO", message)
        AndroidAutoRuntime.publish(AndroidAutoRuntimeState.Failed(message))
        ProjectionRuntime.publish(ProjectionRuntimeState.Failed(message))
        stopSession(message)
    }

    @Synchronized
    private fun stopSession(reason: String) {
        if (stopping) return
        stopping = true
        ProjectionEventLog.record(
            "ANDROID AUTO",
            "Stopping session: reason=$reason, framesSent=${framesAccepted.get()}."
        )
        transportEventsJob?.cancel()
        networkEventsJob?.cancel()
        videoReadyTimeoutJob?.cancel()
        transportEventsJob = null
        networkEventsJob = null
        videoReadyTimeoutJob = null
        receiver?.stop()
        receiver = null
        AndroidAutoPreviewRuntime.clear(this)
        compositor?.release()
        compositor = null
        encoder?.stop()
        encoder = null
        releaseWakeLock()

        val releasedHandle = tBoxHandle ?: TBoxSessionRegistry.current()
        tBoxHandle = null
        if (releasedHandle != null) {
            serviceScope.launch {
                releasedHandle.transport.stop()
                releasedHandle.networkConnector.disconnect()
                TBoxSessionRegistry.clear(releasedHandle)
            }
        }
        if (AndroidAutoRuntime.state.value !is AndroidAutoRuntimeState.Failed) {
            AndroidAutoRuntime.publish(AndroidAutoRuntimeState.Stopped(reason))
        }
        if (ProjectionRuntime.state.value !is ProjectionRuntimeState.Failed) {
            ProjectionRuntime.publish(ProjectionRuntimeState.Stopped(reason))
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        ProjectionEventLog.record("ANDROID AUTO", "Android Auto foreground service onDestroy called.")
        stopSession("Android Auto service stopped by Android.")
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        val manager = getSystemService(PowerManager::class.java)
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:AndroidAuto").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.android_auto_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun createNotification(): android.app.Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopAction = PendingIntent.getService(
            this,
            1,
            Intent(this, AndroidAutoSessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.android_auto_notification_title))
            .setContentText(getString(R.string.android_auto_notification_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(R.drawable.ic_launcher, getString(R.string.stop_android_auto), stopAction)
            .build()
    }

    private fun log(message: String) {
        val normalized = message.lowercase()
        when {
            normalized.contains("failed") || normalized.contains("error") ||
                normalized.contains("timed out") -> ProjectionEventLog.error("AAP", message)
            normalized.contains("warning") || normalized.contains(" W: ".lowercase()) ||
                normalized.contains("dropped") -> ProjectionEventLog.warning("AAP", message)
            else -> ProjectionEventLog.record("AAP", message)
        }
    }

    override fun attachPreview(surface: Surface, width: Int, height: Int) {
        compositor?.setPreview(surface, width, height)
    }

    override fun detachPreview() {
        compositor?.clearPreview()
    }

    override fun sendPreviewTouch(action: Int, x: Int, y: Int) {
        val mapped = compositor?.mapPreviewToSource(x, y) ?: return
        receiver?.sendSourceTouch(action, mapped.first, mapped.second)
    }

    companion object {
        private const val CHANNEL_ID = "android_auto_session_v1"
        private const val NOTIFICATION_ID = 4201
        private const val ACTION_STOP = "io.motohub.android.action.STOP_ANDROID_AUTO"
        private const val AAP_VIDEO_READY_TIMEOUT_MS = 60_000L
        private const val VIDEO_CONFIGURATION_TIMEOUT_MS = 5_000L
        private const val FRAME_LOG_INTERVAL = 300L
        private const val WAKE_LOCK_TIMEOUT_MS = 4 * 60 * 60 * 1_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AndroidAutoSessionService::class.java)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, AndroidAutoSessionService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}

private data class BikeStreamConfiguration(
    val encoderProfile: EncoderProfile,
    val negotiatedArea: TBoxEvent.VideoArea?
)

private fun alignedCanvasGeometry(geometry: DisplayGeometry): DisplayGeometry {
    val profile = EncoderProfile.forTBoxArea(geometry.width, geometry.height)
    return DisplayGeometry(profile.width, profile.height)
}
