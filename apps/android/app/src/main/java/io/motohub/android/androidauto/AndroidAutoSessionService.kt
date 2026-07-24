package io.motohub.android.androidauto

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.motohub.android.MainActivity
import io.motohub.android.R
import io.motohub.android.aa.AaReceiver
import io.motohub.android.aa.SingleKeyKeyManager
import io.motohub.android.encoding.AdaptiveVideoController
import io.motohub.android.encoding.AvcEncoder
import io.motohub.android.encoding.EncoderProfile
import io.motohub.android.feature.settings.MotoHubSettings
import io.motohub.android.feature.settings.AndroidAutoAspectMatchingMode
import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.session.ProjectionEventLog
import io.motohub.android.session.ProjectionRuntime
import io.motohub.android.session.ProjectionRuntimeState
import io.motohub.android.tbox.TBoxEvent
import io.motohub.android.tbox.TBoxLinkResolver
import io.motohub.android.tbox.ProfileOverride
import io.motohub.android.tbox.TBoxCapabilityStore
import io.motohub.android.tbox.TBoxNetworkEvent
import io.motohub.android.tbox.TBoxModelProfile
import io.motohub.android.tbox.TBoxSessionHandle
import io.motohub.android.tbox.TBoxSessionRegistry
import io.motohub.android.tbox.TBoxStreamingLocks
import io.motohub.android.tbox.TBoxTouchTransform
import io.motohub.android.tbox.TBoxTouchFilter
import io.motohub.android.tbox.TBoxVideoAreaSource
import io.motohub.android.tbox.negotiateVideoConfiguration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Owns the Android Auto loopback receiver and its independent T-Box video pipeline. */
class AndroidAutoSessionService : Service(), AndroidAutoPreviewController {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var compositor: AaCompositor? = null
    private var receiver: AaReceiver? = null
    private var encoder: AvcEncoder? = null
    private val adaptiveVideoController = AdaptiveVideoController(this, ::log)
    private var tBoxHandle: TBoxSessionHandle? = null
    private var transportEventsJob: Job? = null
    private var networkEventsJob: Job? = null
    private var receiverPreparationJob: Job? = null
    private var bikeStreamJob: Job? = null
    private var videoReadyTimeoutJob: Job? = null
    private var watchdogJob: Job? = null
    private var recoveryJob: Job? = null
    private var networkLossJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val streamingLocks = TBoxStreamingLocks(this, "Android Auto")
    private val displayGeometryStore by lazy { TBoxDisplayGeometryStore(this) }
    private val screenMarginsStore by lazy { TBoxScreenMarginsStore(this) }
    private val capabilityStore by lazy { TBoxCapabilityStore(this) }
    private val bikeStartRequested = AtomicBoolean(false)
    private val transportUnavailable = AtomicBoolean(false)
    private val videoStreamStartRequested = AtomicBoolean(false)
    private val framesAccepted = AtomicLong(0)
    private val recoveryRequested = AtomicBoolean(false)
    private var capabilityProfile = AndroidAutoCapabilityProfiles.fallback()
    @Volatile private var tBoxTouchTransform: TBoxTouchTransform? = null
    private var touchFilter: TBoxTouchFilter? = null
    private var hasReachedStreaming = false
    private var lastWatchdogFrameCount = 0L
    private var lastWatchdogProgressAt = 0L
    private var screenMarginsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

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
        streamingLocks.acquire()
        AndroidAutoRuntime.publish(AndroidAutoRuntimeState.Preparing)
        ProjectionRuntime.publish(ProjectionRuntimeState.Starting)
        receiverPreparationJob = serviceScope.launch { prepareReceiver() }
        return START_STICKY
    }

    private fun prepareReceiver() {
        val handle = TBoxSessionRegistry.current()
            ?: return fail("No T-Box is ready. Connect and find the T-Box before starting Android Auto.")
        tBoxHandle = handle
        val cachedCapabilities = capabilityStore.load(handle.motorcycle)?.capabilities
        val modelProfile = TBoxModelProfile.resolve(
            handle.motorcycle.modelId,
            cachedCapabilities,
            ProfileOverride.byKey(handle.motorcycle.profileOverrideKey)
        )
        if (cachedCapabilities != null && MotoHubSettings.verboseTBoxLogging(this)) {
            ProjectionEventLog.debug(
                "T-BOX",
                "Profile scores (verbose): ${TBoxModelProfile.scoreBreakdown(cachedCapabilities)}."
            )
        }
        val touchEnabled = modelProfile.supportsScreenTouch &&
            !MotoHubSettings.disableTouchscreen(this)
        touchFilter = if (touchEnabled) {
            TBoxTouchFilter(::log, ::forwardTBoxTouchRaw, modelProfile.touchPolicy)
        } else {
            null
        }
        val learnedGeometry = displayGeometryStore.load(handle.motorcycle.ssid)
        val fallbackPreset = TBoxModelProfile.defaultAndroidAutoPreset(
            handle.motorcycle.modelId,
            cachedCapabilities
        )
        val usableLearnedGeometry = AndroidAutoCapabilityProfiles.usableSavedGeometryForAuto(
            learnedGeometry,
            fallbackPreset
        )
        if (learnedGeometry != null && usableLearnedGeometry == null) {
            ProjectionEventLog.warning(
                "ANDROID AUTO",
                "Ignoring saved T-Box geometry ${learnedGeometry.width}x${learnedGeometry.height} " +
                    "because its orientation conflicts with the validated ${fallbackPreset.source.width}x" +
                    "${fallbackPreset.source.height} model profile."
            )
        }
        val screenMargins = screenMarginsStore.load(handle.motorcycle, modelProfile.defaultScreenMargins)
        ProjectionEventLog.record(
            "T-BOX",
            "Behavior profile=${modelProfile.displayName}; touch enabled=$touchEnabled, " +
                "touch max=${modelProfile.touchPolicy.maxPointers}, " +
                "stale=${modelProfile.touchPolicy.staleContactMillis}ms; " +
                "screen margins=${modelProfile.defaultScreenMargins}."
        )
        val resolutionMode = MotoHubSettings.androidAutoResolution(this)
        val aspectMatchingMode = MotoHubSettings.androidAutoAspectMatching(this)
        val advertisedMargins = if (aspectMatchingMode == AndroidAutoAspectMatchingMode.MANUAL) {
            screenMargins
        } else {
            TBoxScreenMargins.NONE
        }
        capabilityProfile = AndroidAutoCapabilityProfiles.select(
            target = usableLearnedGeometry,
            overridePreset = resolutionMode.preset,
            screenMargins = advertisedMargins,
            touchEnabled = touchEnabled,
            fallbackPreset = fallbackPreset
        )
        val learnedCanvas = usableLearnedGeometry?.let(::alignedCanvasGeometry)
        val displayProfile = learnedCanvas?.let { target ->
            ActiveAndroidAutoDisplayProfile.configure(target, capabilityProfile.video)
        } ?: ActiveAndroidAutoDisplayProfile.configureUncalibrated(capabilityProfile.video)
        ProjectionEventLog.record(
            "ANDROID AUTO",
                "Capability profile: source=${capabilityProfile.video.width}x" +
                "${capabilityProfile.video.height}@${capabilityProfile.densityDpi}dpi, " +
                "selection=${capabilityProfile.source}, resolution=${resolutionMode.name}, " +
                "aspectMatching=${aspectMatchingMode.name}; " +
                capabilityProfile.reason
        )
        if (learnedGeometry == null) {
            ProjectionEventLog.record(
                "ANDROID AUTO",
                "T-Box area not queried yet: starting AAP without assumed cropping. " +
                    "Geometry will be learned from the VideoArea message."
            )
        } else if (usableLearnedGeometry == null) {
            ProjectionEventLog.record(
                "ANDROID AUTO",
                "Saved T-Box area is not used for AUTO selection; starting with the validated " +
                    "model profile until a compatible live VideoArea is received."
            )
        } else {
            ProjectionEventLog.record(
                "ANDROID AUTO",
                "T-Box projection area learned: ${usableLearnedGeometry.width}x${usableLearnedGeometry.height}; " +
                    "aligned AVC canvas: ${learnedCanvas?.width}x${learnedCanvas?.height}. " +
                    "Android Auto content insets: ${capabilityProfile.marginWidth}x" +
                    "${capabilityProfile.marginHeight}."
            )
        }
        observeActiveSession(handle)
        if (handle.link.network != null) {
            handle.networkConnector.releaseProcessBinding()
            ProjectionEventLog.record(
                "NETWORK",
                "T-Box binding suspended while Android Auto starts locally."
            )
        } else {
            // A Wi-Fi Direct group is routed through its P2P interface, not through a
            // ConnectivityManager.Network.  Releasing/rebinding the process route is both
            // unnecessary and harmful here: it makes the later hand-off wait for a network
            // callback that Wi-Fi Direct can never provide.
            ProjectionEventLog.record(
                "NETWORK",
                "Wi-Fi Direct T-Box link detected; keeping the P2P route for Android Auto startup."
            )
        }

        try {
            val displayMode = AndroidAutoDisplayModeStore(this).load(handle.motorcycle)
            ProjectionEventLog.record(
                "ANDROID AUTO",
                "TFT display mode selected for ${handle.motorcycle.ssid}: $displayMode."
            )
            val activeCompositor = AaCompositor(
                log = ::log,
                displayMode = displayMode,
                sourceGeometry = capabilityProfile.video,
                touchSurface = capabilityProfile.touchSurface,
                screenMargins = screenMargins
            )
            check(activeCompositor.start()) { "Android Auto compositor failed to initialize (EGL/GL)" }
            val decoderSurface = activeCompositor.inputSurface
                ?: error("Android Auto compositor did not create the video surface")
            compositor = activeCompositor
            observeScreenMarginChanges(handle.motorcycle, modelProfile.defaultScreenMargins)

            val activeReceiver = AaReceiver(
                context = applicationContext,
                encoderSurface = decoderSurface,
                log = ::log,
                onVideoReady = {
                    if (bikeStartRequested.compareAndSet(false, true)) {
                        videoReadyTimeoutJob?.cancel()
                        bikeStreamJob?.cancel()
                        bikeStreamJob = serviceScope.launch { startBikeStream(handle) }
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
                mapTouchToSource = activeCompositor::mapCanvasToUi,
                capabilityProfile = capabilityProfile
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
        if (handle.link.network != null) {
            val rebound = handle.networkConnector.rebindProcessToTBoxWhenAvailable(
                TBOX_NETWORK_REBIND_TIMEOUT_MS
            )
            rebound.exceptionOrNull()?.let {
                return fail("T-Box network restore failed: ${it.message}")
            }
        } else {
            ProjectionEventLog.record(
                "NETWORK",
                "Android Auto video is ready; using the existing Wi-Fi Direct P2P route for T-Box hand-off."
            )
        }
        ProjectionEventLog.record("ANDROID AUTO", "First AAP video frame received. Starting EasyConn session.")

        val savedArea = displayGeometryStore.load(handle.motorcycle.ssid)?.let { geometry ->
            TBoxEvent.VideoArea(geometry.width, geometry.height)
        }
        val configurationResult = handle.transport.negotiateVideoConfiguration(
            host = handle.host,
            savedArea = savedArea,
            timeoutMillis = VIDEO_CONFIGURATION_TIMEOUT_MS
        )
        configurationResult.exceptionOrNull()?.let {
            return fail("T-Box handshake for Android Auto failed: ${it.message}")
        }
        if (stopping) return

        val configuration = configurationResult.getOrThrow()
        val quality = MotoHubSettings.videoQuality(this)
        val encoderProfile = configuration.encoderProfile.copy(
            bitRate = quality.bitrateFor(configuration.encoderProfile.bitRate)
        )
        val negotiatedArea = configuration.rawArea
        val actualGeometry = DisplayGeometry(encoderProfile.width, encoderProfile.height)
        tBoxTouchTransform = TBoxTouchTransform.forVideoConfiguration(configuration)
        ProjectionEventLog.record(
            "TOUCH",
            "T-Box touch domain ${negotiatedArea.width}x${negotiatedArea.height} maps to " +
                "AVC canvas ${encoderProfile.width}x${encoderProfile.height}; " +
                "AA source ${capabilityProfile.video.width}x${capabilityProfile.video.height}."
        )
        if (capabilityProfile.source == AndroidAutoCapabilitySource.USER_OVERRIDE &&
            actualGeometry != capabilityProfile.video
        ) {
            ProjectionEventLog.warning(
                "ANDROID AUTO",
                "Forced AA source is ${capabilityProfile.video.width}x${capabilityProfile.video.height}, " +
                    "but the T-Box announced ${negotiatedArea.width}x${negotiatedArea.height} " +
                    "(AVC ${actualGeometry.width}x${actualGeometry.height}). The T-Box canvas is " +
                    "independent of the AA source; verify and restart the simulator if this geometry is unexpected."
            )
        }
        val expectedGeometry = ActiveAndroidAutoDisplayProfile.current.expectedTft
        if (configuration.source == TBoxVideoAreaSource.LIVE) {
            val negotiatedGeometry = DisplayGeometry(negotiatedArea.width, negotiatedArea.height)
            val fallbackPreset = TBoxModelProfile.defaultAndroidAutoPreset(
                handle.motorcycle.modelId,
                capabilityStore.load(handle.motorcycle)?.capabilities
            )
            val shouldPersistGeometry = capabilityProfile.source == AndroidAutoCapabilitySource.USER_OVERRIDE ||
                AndroidAutoCapabilityProfiles.usableSavedGeometryForAuto(negotiatedGeometry, fallbackPreset) != null
            if (shouldPersistGeometry) {
                displayGeometryStore.save(handle.motorcycle.ssid, negotiatedGeometry)
            } else {
                ProjectionEventLog.warning(
                    "ANDROID AUTO",
                    "Not saving live T-Box geometry ${negotiatedGeometry.width}x${negotiatedGeometry.height}: " +
                        "orientation conflicts with the validated ${fallbackPreset.source.width}x" +
                        "${fallbackPreset.source.height} model profile."
                )
            }
        } else {
            ProjectionEventLog.warning(
                "ANDROID AUTO",
                "The live TFT area was not received; using the saved geometry for " +
                    "${handle.motorcycle.ssid}."
            )
        }
        if (actualGeometry != expectedGeometry) {
            ProjectionEventLog.record(
                "ANDROID AUTO",
                "Updating compositor in this session: ${configuration.source} TFT area " +
                    "${negotiatedArea.width}x${negotiatedArea.height}, aligned AVC canvas " +
                    "${actualGeometry.width}x${actualGeometry.height}."
            )
        }
        ActiveAndroidAutoDisplayProfile.configure(actualGeometry, capabilityProfile.video)
        // The T-Box area is the H.264/touch canvas, not an Android Auto inset.  Keep the
        // advertised AA input surface stable across every projection resolution.
        compositor?.setTouchSurface(capabilityProfile.touchSurface)
        val learnedCapability = AndroidAutoCapabilityProfiles.select(
            DisplayGeometry(negotiatedArea.width, negotiatedArea.height)
        )
        if (capabilityProfile.source != AndroidAutoCapabilitySource.USER_OVERRIDE &&
            learnedCapability.videoPreset != capabilityProfile.videoPreset
        ) {
            ProjectionEventLog.warning(
                "ANDROID AUTO",
                "The live TFT geometry recommends ${learnedCapability.video.width}x" +
                    "${learnedCapability.video.height}@${learnedCapability.densityDpi}dpi. " +
                    "The current AAP session remains ${capabilityProfile.video.width}x" +
                    "${capabilityProfile.video.height}; the learned profile will be used automatically " +
                    "the next time Android Auto starts."
            )
        }
        ProjectionEventLog.record(
            "T-BOX",
            "Area Android Auto ${encoderProfile.width}x${encoderProfile.height}; " +
                "quality=${quality.name}, bitrate=${encoderProfile.bitRate}."
        )
        try {
            val activeEncoder = AvcEncoder(
                profile = encoderProfile,
                onAccessUnit = { accessUnit ->
                    if (!handle.transport.offerAccessUnit(accessUnit)) {
                        if (transportUnavailable.compareAndSet(false, true)) {
                            serviceScope.launch {
                                handleRecoverableFailure(
                                    "The T-Box no longer accepts Android Auto frames."
                                )
                            }
                        }
                        false
                    } else {
                        val accepted = framesAccepted.incrementAndGet()
                        if (accepted == 1L || accepted % FRAME_LOG_INTERVAL == 0L) {
                            ProjectionEventLog.record("ANDROID AUTO", "Frames sent: $accepted.")
                        }
                        true
                    }
                },
                onFailure = { failure ->
                    serviceScope.launch {
                        handleRecoverableFailure("Android Auto encoder stopped: ${failure.message}")
                    }
                }
            )
            activeEncoder.start()
            adaptiveVideoController.reset()
            activeEncoder.setFrameCapListener { compositor?.setFrameCap(it) }
            if (videoStreamStartRequested.get()) {
                activeEncoder.requestSyncFrame("TFT consumer already requested Android Auto video")
            }
            val encoderSurface = activeEncoder.inputSurface
                ?: error("Android Auto encoder has no input surface")
            encoder = activeEncoder
            compositor?.setOutput(
                encoderSurface,
                encoderProfile.width,
                encoderProfile.height,
                capabilityProfile.video.width,
                capabilityProfile.video.height
            )
            AndroidAutoRuntime.publish(AndroidAutoRuntimeState.Streaming)
            ProjectionRuntime.publish(ProjectionRuntimeState.Streaming)
            hasReachedStreaming = true
            markWatchdogProgress()
            startWatchdog()
            ProjectionEventLog.record("ANDROID AUTO", "Android Auto streaming active on the TFT.")
        } catch (failure: Throwable) {
            fail("Android Auto pipeline did not start: ${failure.message}")
        }
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
                        encoder?.requestSyncFrame("TFT consumer requested Android Auto video")
                    }
                    is TBoxEvent.Touch -> touchFilter?.onTouch(event)
                    is TBoxEvent.Warning -> ProjectionEventLog.record("T-BOX", event.message)
                    is TBoxEvent.FatalError -> handleRecoverableFailure("T-Box error: ${event.message}")
                    TBoxEvent.Stopped -> handleRecoverableFailure("The T-Box ended Android Auto.")
                    is TBoxEvent.VideoArea -> Unit
                }
            }
        }
        networkEventsJob = serviceScope.launch {
            handle.networkConnector.events.collect { event ->
                if (stopping) return@collect
                when (event) {
                    is TBoxNetworkEvent.Lost -> handleTBoxNetworkLost(handle)
                    is TBoxNetworkEvent.Reacquired -> {
                        networkLossJob?.cancel()
                        networkLossJob = null
                        if (hasReachedStreaming && MotoHubSettings.seamlessResume(this@AndroidAutoSessionService)) {
                            requestTBoxRecovery("T-Box Wi-Fi re-acquired; resuming Android Auto stream.")
                        }
                    }
                }
            }
        }
    }

    private fun handleTBoxNetworkLost(handle: TBoxSessionHandle) {
        if (!shouldAutoRecoverAndroidAuto(
                hasReachedStreaming = hasReachedStreaming,
                enabled = MotoHubSettings.autoRecovery(this)
            )
        ) {
            fail("T-Box Wi-Fi connection lost.")
            return
        }
        if (!MotoHubSettings.seamlessResume(this)) {
            fail("T-Box Wi-Fi connection lost; seamless resume is disabled.")
            return
        }
        networkLossJob?.cancel()
        networkLossJob = serviceScope.launch {
            ProjectionEventLog.warning(
                "WATCHDOG",
                "T-Box Wi-Fi lost; keeping Android Auto parked for " +
                    "${NETWORK_LOSS_GRACE_MILLIS / 1_000L}s while auto-rejoin runs."
            )
            delay(NETWORK_LOSS_GRACE_MILLIS)
            if (!stopping && handle.networkConnector.currentNetwork() == null) {
                requestTBoxRecovery(
                    "T-Box Wi-Fi did not return within the grace period; resuming Android Auto recovery."
                )
            }
        }
    }

    private fun forwardTBoxTouchRaw(event: TBoxEvent.Touch) {
        val transform = tBoxTouchTransform
        if (transform == null) {
            if (event.action != 2) {
                ProjectionEventLog.warning(
                    "TOUCH",
                    "Touch dropped before T-Box geometry was negotiated: raw=(${event.x},${event.y})."
                )
            }
            return
        }
        val mapped = transform.map(event.x, event.y)
        if (mapped == null) {
            if (event.action != 2) {
                ProjectionEventLog.warning(
                    "TOUCH",
                    "Touch raw=(${event.x},${event.y}) is outside declared domain " +
                        "${transform.input.width}x${transform.input.height} " +
                        "@(${transform.input.left},${transform.input.top}); input was dropped."
                )
            }
            return
        }
        if (event.action != 2 && mapped != (event.x to event.y)) {
            ProjectionEventLog.debug(
                "TOUCH",
                "Normalised raw=(${event.x},${event.y}) to AVC=(${mapped.first},${mapped.second})."
            )
        }
        receiver?.sendTouch(event.action, mapped.first, mapped.second)
    }

    private fun startWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = serviceScope.launch {
            while (!stopping) {
                delay(WATCHDOG_TICK_MS)
                adaptiveVideoController.onTick(encoder)
                if (!MotoHubSettings.autoRecovery(this@AndroidAutoSessionService) ||
                    AndroidAutoRuntime.state.value !is AndroidAutoRuntimeState.Streaming ||
                    recoveryRequested.get()
                ) {
                    markWatchdogProgress()
                    continue
                }
                val currentFrames = framesAccepted.get()
                if (currentFrames > lastWatchdogFrameCount) {
                    lastWatchdogFrameCount = currentFrames
                    lastWatchdogProgressAt = SystemClock.elapsedRealtime()
                } else if (isAndroidAutoWatchdogStalled(
                        nowElapsed = SystemClock.elapsedRealtime(),
                        lastProgressElapsed = lastWatchdogProgressAt,
                        thresholdMillis = WATCHDOG_STALL_MS
                    )
                ) {
                    handleRecoverableFailure(
                        "Android Auto TFT stream stalled for at least ${WATCHDOG_STALL_MS / 1_000L} seconds."
                    )
                }
            }
        }
    }

    private fun markWatchdogProgress() {
        lastWatchdogFrameCount = framesAccepted.get()
        lastWatchdogProgressAt = SystemClock.elapsedRealtime()
    }

    private fun handleRecoverableFailure(message: String) {
        if (stopping) return
        if (!shouldAutoRecoverAndroidAuto(
                hasReachedStreaming = hasReachedStreaming,
                enabled = MotoHubSettings.autoRecovery(this)
            )
        ) {
            fail(message)
            return
        }
        requestTBoxRecovery(message)
    }

    /**
     * Retries [recoverTBoxStream] within a [RECOVERY_GIVE_UP_MILLIS] budget before giving
     * up and tearing the session down, instead of failing the whole Android Auto session
     * on the first transient error (a discovery timeout, a momentary Wi-Fi hiccup), matching
     * the "Reconnecting" retry-budget state ARCHITECTURE.md documents. [recoveryRequested]
     * stays true for the whole multi-attempt
     * run so the watchdog does not start a second concurrent recovery.
     */
    private fun requestTBoxRecovery(reason: String) {
        if (!recoveryRequested.compareAndSet(false, true)) {
            ProjectionEventLog.debug("WATCHDOG", "Recovery already active; ignored: $reason")
            return
        }
        ProjectionEventLog.warning("WATCHDOG", "Android Auto recovery requested: $reason")
        recoveryJob = serviceScope.launch {
            val deadline = SystemClock.elapsedRealtime() + RECOVERY_GIVE_UP_MILLIS
            var attempt = 0
            while (!stopping && SystemClock.elapsedRealtime() < deadline) {
                attempt++
                try {
                    recoverTBoxStream(reason)
                    recoveryRequested.set(false)
                    ProjectionEventLog.record(
                        "WATCHDOG",
                        "Android Auto TFT stream recovered on attempt $attempt."
                    )
                    return@launch
                } catch (cancelled: CancellationException) {
                    recoveryRequested.set(false)
                    throw cancelled
                } catch (failure: Throwable) {
                    ProjectionEventLog.warning(
                        "WATCHDOG",
                        "Android Auto recovery attempt $attempt failed: ${failure.message}"
                    )
                    delay(RECOVERY_RETRY_MILLIS)
                }
            }
            recoveryRequested.set(false)
            if (!stopping) {
                fail(
                    "Android Auto auto-recovery timed out after " +
                        "${RECOVERY_GIVE_UP_MILLIS / 1_000L} seconds ($attempt attempt(s))."
                )
            }
        }
    }

    private suspend fun recoverTBoxStream(reason: String) {
        val previousHandle = tBoxHandle ?: error("No T-Box session is available for recovery")
        AndroidAutoRuntime.publish(AndroidAutoRuntimeState.ReceiverReady)
        ProjectionRuntime.publish(ProjectionRuntimeState.Starting)
        ProjectionEventLog.record(
            "WATCHDOG",
            "Recovering EasyConn while keeping the Android Auto receiver active: $reason"
        )

        transportEventsJob?.cancel()
        networkEventsJob?.cancel()
        transportEventsJob = null
        networkEventsJob = null
        compositor?.clearOutput()
        encoder?.stop()
        encoder = null
        adaptiveVideoController.reset()
        tBoxTouchTransform = null
        transportUnavailable.set(false)
        videoStreamStartRequested.set(false)

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
        val host = previousHandle.transport.discover(
            link,
            previousHandle.motorcycle.modelId
        ).getOrThrow()
        val recoveredHandle = TBoxSessionHandle(
            transport = previousHandle.transport,
            host = host,
            networkConnector = previousHandle.networkConnector,
            motorcycle = previousHandle.motorcycle,
            link = link
        )
        tBoxHandle = recoveredHandle
        TBoxSessionRegistry.install(recoveredHandle)
        capabilityStore.recordDiscovery(previousHandle.motorcycle, host)
        observeActiveSession(recoveredHandle)
        startBikeStream(recoveredHandle)
        check(AndroidAutoRuntime.state.value is AndroidAutoRuntimeState.Streaming) {
            "Recovered T-Box handshake did not return to streaming"
        }
        ProjectionEventLog.record("WATCHDOG", "Android Auto TFT stream recovered successfully.")
    }

    private fun fail(message: String) {
        if (stopping) return
        ProjectionEventLog.error("ANDROID AUTO", message)
        AndroidAutoRuntime.publish(AndroidAutoRuntimeState.Failed(message))
        ProjectionRuntime.publish(ProjectionRuntimeState.Failed(message))
        stopSession(message)
    }

    /**
     * Applies a screen-margin change picked in
     * [io.motohub.android.feature.garage.MotorcycleDetailsScreen] to the running compositor
     * immediately, instead of only on the next Android Auto start.
     */
    private fun observeScreenMarginChanges(motorcycle: MotorcycleProfile, defaultMargins: TBoxScreenMargins) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (!screenMarginsStore.belongsToMotorcycle(key, motorcycle.ssid)) return@OnSharedPreferenceChangeListener
            val margins = screenMarginsStore.load(motorcycle, defaultMargins)
            compositor?.refreshMargins(margins)
            ProjectionEventLog.record("ANDROID AUTO", "Screen margins applied live: $margins.")
        }
        screenMarginsStore.addListener(listener)
        screenMarginsListener = listener
    }

    @Synchronized
    private fun stopSession(reason: String) {
        touchFilter?.close()
        touchFilter = null
        if (stopping) return
        stopping = true
        ProjectionEventLog.record(
            "ANDROID AUTO",
            "Stopping session: reason=$reason, framesSent=${framesAccepted.get()}."
        )
        transportEventsJob?.cancel()
        networkEventsJob?.cancel()
        receiverPreparationJob?.cancel()
        bikeStreamJob?.cancel()
        videoReadyTimeoutJob?.cancel()
        watchdogJob?.cancel()
        recoveryJob?.cancel()
        networkLossJob?.cancel()
        transportEventsJob = null
        networkEventsJob = null
        receiverPreparationJob = null
        bikeStreamJob = null
        videoReadyTimeoutJob = null
        watchdogJob = null
        recoveryJob = null
        networkLossJob = null
        receiver?.stop()
        receiver = null
        AndroidAutoPreviewRuntime.clear(this)
        screenMarginsListener?.let { screenMarginsStore.removeListener(it) }
        screenMarginsListener = null
        compositor?.release()
        compositor = null
        encoder?.stop()
        encoder = null
        tBoxTouchTransform = null
        releaseWakeLock()
        streamingLocks.release()

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
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.android_auto_notification_title))
            .setContentText(getString(R.string.android_auto_notification_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(R.drawable.ic_notification, getString(R.string.stop_android_auto), stopAction)
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

    override fun sendPreviewTouch(action: Int, pointerId: Int, x: Int, y: Int) {
        val mapped = compositor?.mapPreviewToUi(x, y) ?: return
        receiver?.sendSourceTouch(action, mapped.first, mapped.second)
    }

    override fun sendPreviewKey(keycode: Int): Boolean = false

    override fun sendPreviewScroll(delta: Int): Boolean = false

    override fun setPreviewNightMode(isNight: Boolean): Boolean = false

    companion object {
        private const val CHANNEL_ID = "android_auto_session_v1"
        private const val NOTIFICATION_ID = 4201
        private const val ACTION_STOP = "io.motohub.android.action.STOP_ANDROID_AUTO"
        private const val AAP_VIDEO_READY_TIMEOUT_MS = 60_000L
        private const val TBOX_NETWORK_REBIND_TIMEOUT_MS = 8_000L
        private const val VIDEO_CONFIGURATION_TIMEOUT_MS = 10_000L
        private const val FRAME_LOG_INTERVAL = 300L
        private const val WATCHDOG_TICK_MS = 5_000L
        private const val WATCHDOG_STALL_MS = 10_000L
        private const val NETWORK_LOSS_GRACE_MILLIS = 60_000L
        private const val NETWORK_REJOIN_WAIT_MILLIS = 75_000L
        private const val RECOVERY_RETRY_MILLIS = 5_000L
        private const val RECOVERY_GIVE_UP_MILLIS = 120_000L
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

internal fun shouldAutoRecoverAndroidAuto(
    hasReachedStreaming: Boolean,
    enabled: Boolean
): Boolean = hasReachedStreaming && enabled

internal fun isAndroidAutoWatchdogStalled(
    nowElapsed: Long,
    lastProgressElapsed: Long,
    thresholdMillis: Long
): Boolean = lastProgressElapsed > 0L && nowElapsed - lastProgressElapsed >= thresholdMillis

private fun alignedCanvasGeometry(geometry: DisplayGeometry): DisplayGeometry {
    val profile = EncoderProfile.forTBoxArea(geometry.width, geometry.height)
    return DisplayGeometry(profile.width, profile.height)
}
