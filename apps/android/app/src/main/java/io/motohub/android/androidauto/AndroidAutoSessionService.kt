// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
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
import io.motohub.android.androidauto.AaInputBridge
import io.motohub.android.aa.SingleKeyKeyManager
import io.motohub.android.encoding.AdaptiveVideoController
import io.motohub.android.encoding.AvcEncoder
import io.motohub.android.encoding.EncoderProfile
import io.motohub.android.encoding.JpegDisplaySource
import io.motohub.android.encoding.VideoBackpressureGuard
import io.motohub.android.feature.controls.HandlebarControlStore
import io.motohub.android.feature.controls.MediaButtonBridge
import io.motohub.android.feature.controls.SimulatorHandlebarBridge
import io.motohub.android.feature.settings.MotoHubSettings
import io.motohub.android.feature.settings.AndroidAutoAspectMatchingMode
import io.motohub.android.session.FrameLogThrottle
import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.encoding.VideoDeliveryProbe
import io.motohub.android.session.DashboardDeliveryMonitor
import io.motohub.android.session.ProjectionEventLog
import io.motohub.android.session.ProjectionRuntime
import io.motohub.android.session.ProjectionRuntimeState
import io.motohub.android.tbox.TBoxEvent
import io.motohub.android.tbox.TBoxLinkResolver
import io.motohub.android.tbox.ProfileOverride
import io.motohub.android.tbox.TBoxCapabilityStore
import io.motohub.android.tbox.TBoxNetworkEvent
import io.motohub.android.tbox.TBoxModelProfile
import io.motohub.android.tbox.TBoxWireLadder
import io.motohub.android.tbox.TBoxTransportFamily
import io.motohub.android.tbox.SelectingTBoxTransport
import io.motohub.android.tbox.TBoxSessionHandle
import io.motohub.android.tbox.TBoxSessionRegistry
import io.motohub.android.tbox.TBoxStreamingLocks
import io.motohub.android.tbox.TBoxTouchTransform
import io.motohub.android.tbox.tBoxFailureOwnedByHandshake
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Owns the Android Auto loopback receiver and its independent T-Box video pipeline. */
class AndroidAutoSessionService : Service(), AndroidAutoPreviewController {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var compositor: AaCompositor? = null
    private var receiver: AaReceiver? = null
    private var encoder: AvcEncoder? = null
    private var jpegSource: JpegDisplaySource? = null
    private val adaptiveVideoController = AdaptiveVideoController(this, ::log)
    private var tBoxHandle: TBoxSessionHandle? = null
    private var transportEventsJob: Job? = null
    private var networkEventsJob: Job? = null
    private var p2pGroupWatcher: AutoCloseable? = null
    private var receiverPreparationJob: Job? = null
    private var bikeStreamJob: Job? = null
    private var videoReadyTimeoutJob: Job? = null
    private var watchdogJob: Job? = null
    private var recoveryJob: Job? = null
    private var androidAutoReattachJob: Job? = null
    private var networkLossJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val streamingLocks = TBoxStreamingLocks(this, "Android Auto")
    private var mediaButtonBridge: MediaButtonBridge? = null
    private var simulatorHandlebarBridge: SimulatorHandlebarBridge? = null
    private val displayGeometryStore by lazy { TBoxDisplayGeometryStore(this) }
    private val screenMarginsStore by lazy { TBoxScreenMarginsStore(this) }
    private val capabilityStore by lazy { TBoxCapabilityStore(this) }
    private val bikeStartRequested = AtomicBoolean(false)
    private val transportUnavailable = AtomicBoolean(false)
    /** One place for both verdicts, so the accept and reject arms cannot drift apart. */
    private fun publishDeliveryVerdict(
        verdict: VideoDeliveryProbe.Verdict,
        handle: TBoxSessionHandle,
        profile: TBoxModelProfile
    ) = DashboardDeliveryMonitor.publish(
        verdict = verdict,
        ssid = handle.motorcycle.ssid,
        rejected = deliveryProbe.rejectedCount(),
        accepted = deliveryProbe.acceptedCount(),
        profileKey = profile.key
    )

    private var backpressureGuard = VideoBackpressureGuard()
    /**
     * Separate from [backpressureGuard] and asking the other question: not "is the link dead" but
     * "is this dash swallowing anything like what we send it". A session can be perfectly alive by
     * the guard's measure and still show the rider a frozen picture - see [VideoDeliveryProbe].
     */
    private var deliveryProbe = VideoDeliveryProbe()
    private val videoStreamStartRequested = AtomicBoolean(false)
    private val framesAccepted = AtomicLong(0)
    private val frameLogThrottle = FrameLogThrottle()
    /** When the dash last took a still. The stills path's liveness signal; see the offer below. */
    private val lastStillAcceptedAt = AtomicLong(0)
    private val recoveryRequested = AtomicBoolean(false)

    /** True while a dropped AAP session is being held open for Android Auto to come back. */
    private val androidAutoReattachRequested = AtomicBoolean(false)
    /**
     * True for exactly as long as [startBikeStream] is inside the EasyConn handshake, both
     * attempts included. A `Stopped`/`FatalError` arriving in that window is that handshake's own
     * failure reaching us by a second route, not the session dying; see
     * [tBoxFailureOwnedByHandshake] for what it cost to find that out.
     */
    private val handshakeInFlight = AtomicBoolean(false)
    private var capabilityProfile = AndroidAutoCapabilityProfiles.fallback()
    @Volatile private var tBoxTouchTransform: TBoxTouchTransform? = null
    private var touchFilter: TBoxTouchFilter? = null
    // Written and read from different coroutines on the IO dispatcher (watchdog tick, transport
    // event collector, network event collector), so the reads need the visibility guarantee
    // rather than relying on the dispatcher happening to establish happens-before.
    @Volatile private var hasReachedStreaming = false
    @Volatile private var lastWatchdogFrameCount = 0L
    @Volatile private var lastWatchdogProgressAt = 0L
    private var screenMarginsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    @Volatile
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Reached either from the notification's Stop action, which sets no reason of its
            // own, or as [stop]'s fallback when the service was still starting up - there the
            // caller's reason is waiting for us.
            stopSession(AndroidAutoStopReason.take() ?: "Android Auto stopped by the user.")
            return START_NOT_STICKY
        }
        // A stop that never reached a running service must not name the session about to start.
        AndroidAutoStopReason.clear()
        if (AndroidAutoRuntime.isActive()) return START_STICKY

        ProjectionEventLog.record("ANDROID AUTO", "Preparing local AAP receiver.")
        createNotificationChannel()
        // Going foreground can be refused outright - ForegroundServiceStartNotAllowedException
        // when the start came from the background (the PRO->CORE AIDL path can, since CORE need
        // not be foreground), or a SecurityException for a missing permission. Uncaught, that
        // kills the service, START_STICKY restarts it, and it fails the same way forever. Give up
        // once and say so instead: a session that cannot hold a foreground service cannot stream
        // anyway, and the loop only drains the battery while hiding the real cause.
        val foreground = runCatching {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        }
        foreground.exceptionOrNull()?.let { failure ->
            ProjectionEventLog.error(
                "ANDROID AUTO",
                "Android Auto could not start as a foreground service " +
                    "(${failure.javaClass.simpleName}: ${failure.message}); stopping instead of " +
                    "retrying, which would only loop. Start it from the app with the screen on."
            )
            stopSelf()
            return START_NOT_STICKY
        }
        if (mediaButtonBridge == null) {
            mediaButtonBridge = MediaButtonBridge(
                context = applicationContext,
                log = ::log,
                targetName = MediaButtonBridge.TARGET_ANDROID_AUTO
            ).also { it.start() }
        }
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
        TBoxSessionRegistry.claim(SESSION_CONSUMER)
        startSimulatorHandlebarBridgeIfNeeded(handle)
        val cachedCapabilities = capabilityStore.load(handle.motorcycle)?.capabilities
        val profileOverride = ProfileOverride.byKey(handle.motorcycle.profileOverrideKey)
        val modelProfile = TBoxModelProfile.resolve(
            handle.motorcycle.modelId,
            cachedCapabilities,
            profileOverride
        )
        // Landing on GENERIC means no profile recognised this dashboard, which is exactly the
        // case a rider cannot diagnose from the outside - so the scores go in the log whether or
        // not verbose logging is on. Everything else stays behind the setting.
        if (cachedCapabilities != null &&
            (modelProfile == TBoxModelProfile.GENERIC || MotoHubSettings.verboseTBoxLogging(this))
        ) {
            ProjectionEventLog.debug(
                "T-BOX",
                "Profile scores: ${TBoxModelProfile.scoreBreakdown(cachedCapabilities)}."
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
            cachedCapabilities,
            profileOverride
        )
        val fallbackIsValidated = TBoxModelProfile.hasValidatedAndroidAutoPreset(
            handle.motorcycle.modelId,
            cachedCapabilities,
            profileOverride
        )
        val usableLearnedGeometry = AndroidAutoCapabilityProfiles.usableSavedGeometryForAuto(
            learnedGeometry,
            fallbackPreset,
            fallbackIsValidated
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
                "screen margins=$screenMargins (profile default ${modelProfile.defaultScreenMargins})."
        )
        val resolutionMode = MotoHubSettings.androidAutoResolution(this)
        val densityMode = MotoHubSettings.androidAutoDensity(this)
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
            fallbackPreset = fallbackPreset,
            densityOverride = densityMode.dpi
        ).let { selected ->
            // AUTO used to mean "advertise no margins", which is the same thing as accepting the
            // letterbox: Android Auto only offers a handful of coded sizes and none of them is
            // the shape of a motorcycle panel. Now it means what a rider expects it to mean -
            // work the aspect out from the panel we measured. Computed AFTER selection because
            // the margins depend on which coded source was chosen, and left at NONE when nothing
            // has been learned yet, since guessing an aspect from the fallback preset would
            // crop the picture to fit a number nobody measured.
            val panel = usableLearnedGeometry
            if (aspectMatchingMode != AndroidAutoAspectMatchingMode.AUTO || panel == null) {
                selected
            } else {
                selected.copy(aspectMargins = AaAspectMargins.forPanel(selected.video, panel))
            }
        }
        val learnedCanvas = usableLearnedGeometry?.let(::alignedCanvasGeometry)
        val displayProfile = learnedCanvas?.let { target ->
            ActiveAndroidAutoDisplayProfile.configure(target, capabilityProfile.video)
        } ?: ActiveAndroidAutoDisplayProfile.configureUncalibrated(capabilityProfile.video)
        ProjectionEventLog.record(
            "ANDROID AUTO",
                "Capability profile: source=${capabilityProfile.video.width}x" +
                "${capabilityProfile.video.height}@${capabilityProfile.densityDpi}dpi, " +
                "selection=${capabilityProfile.source}, resolution=${resolutionMode.name}, " +
                "density=${densityMode.name}, aspectMatching=${aspectMatchingMode.name}; " +
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
                screenMargins = screenMargins,
                contentMargins = capabilityProfile.aspectMargins
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
                        bikeStreamJob = serviceScope.launch {
                            // Initial start: a failure here IS session-fatal. Recovery calls
                            // startBikeStream directly and lets its retry budget absorb throws.
                            try {
                                startBikeStream(handle)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Throwable) {
                                fail(failure.message ?: "Android Auto bike stream failed to start.")
                            }
                        }
                    }
                },
                onSessionEnded = { clean, userExit ->
                    if (!stopping) {
                        serviceScope.launch {
                            if (userExit) {
                                stopSession("Android Auto exited by user.")
                                return@launch
                            }
                            val reason = if (clean) {
                                "Android Auto ended the AAP session before projection completed."
                            } else {
                                "Android Auto connection closed unexpectedly."
                            }
                            handleAndroidAutoDrop(reason)
                        }
                    }
                },
                mapTouchToSource = activeCompositor::mapCanvasToUi,
                capabilityProfile = capabilityProfile,
                downstreamBlockedMillis = activeCompositor::downstreamBlockedMillis
            )
            if (!SingleKeyKeyManager.isAvailable(applicationContext)) {
                error(
                    "Android Auto identity is not included in this build. " +
                        "Build with -PincludeAndroidAutoIdentity=true for a private sideload APK."
                )
            }
            AndroidAutoReceiverOwnership.claim(this@AndroidAutoSessionService, "real-session") {
                stopSession("Superseded by a new Android Auto session.")
            }
            if (!activeReceiver.start()) error("Android Auto local port 5288 is unavailable")
            receiver = activeReceiver
            AndroidAutoPreviewRuntime.install(this)
            AndroidAutoRuntime.publish(AndroidAutoRuntimeState.ReceiverReady)
            ProjectionEventLog.record("ANDROID AUTO", "Receiver ready. Starting Google Android Auto.")
            videoReadyTimeoutJob = serviceScope.launch {
                delay(AAP_VIDEO_READY_TIMEOUT_MS)
                if (!stopping && !bikeStartRequested.get()) {
                    // "Never connected" and "connected but silent" are different failures with
                    // different remedies, and reporting the second for the first sent riders
                    // hunting a video problem when Google Android Auto had in fact refused to
                    // start at all (newer builds no longer export the self-mode entry point).
                    if (activeReceiver.hasAndroidAutoConnected) {
                        fail(
                            "Android Auto connected without delivering video. " +
                                "The AAP session was closed; start Android Auto again."
                        )
                    } else if (io.motohub.android.aa.AaSelfMode.anyEntryPointAccepted) {
                        // Android Auto took the request and ignored it. On a release that still
                        // has self-mode that is the "Add new cars" switch, and sending those
                        // riders to the head unit server answers a question they did not ask;
                        // from 17.3 on it is the release itself, and the switch is a dead end
                        // they have usually already tried. The version decides which - see
                        // AndroidAutoSelfModeHelp.acceptedButSilentMessage.
                        fail(
                            AndroidAutoSelfModeHelp.acceptedButSilentMessage(
                                io.motohub.android.aa.AaSelfMode.lastGearheadVersion
                            )
                        )
                    } else {
                        fail(AndroidAutoSelfModeHelp.NEVER_CONNECTED_MESSAGE)
                    }
                }
            }
        } catch (failure: Throwable) {
            fail("Android Auto receiver did not start: ${failure.message}")
        }
    }

    /**
     * Brings the negotiated T-Box video pipeline up for [handle].
     *
     * Never terminates the session itself: failures are reported by THROWING, and the caller
     * decides what a failure means. The initial start maps it to [fail]; the recovery path's
     * retry loop treats it as one failed attempt inside its budget. This used to call [fail]
     * directly, which tore the whole session down on the FIRST transient handshake error of a
     * recovery whose own doc promised a 120s retry budget.
     */
    /**
     * Whether the T-Box hand-off can leave Android's process route where Android Auto left it.
     *
     * `bindProcessToNetwork` moves only this process's DEFAULT route. Every socket that carries
     * dash traffic is bound to the T-Box network explicitly instead - `TBoxLink.createSocket`
     * goes through `network.socketFactory`, including the descriptor handed to the EasyConn Go
     * SDK - so the route is belt-and-braces rather than load-bearing, which is why a binding
     * Android refuses is already survivable everywhere else in the connector.
     *
     * It is not free, though. The AAP socket to Google's Android Auto is the one socket on this
     * path that is deliberately NOT network-bound (`AaReceiver.openWirelessClientSocket`), and on
     * a KOVE 800X rider's phone it went silent within seconds of the rebind, twice, and died on
     * the 15s read timeout with the T-Box link otherwise healthy (log 2026-08-20 23:38:17 and
     * 23:39:17). `AaReceiver.bindWirelessServerSocket` already carries a workaround for the same
     * interference on the bind path; this is the same conflict on an established socket.
     *
     * Scoped to ThinkerRide because there it is provably pointless: that transport opens no
     * outbound IP socket at all - three wildcard `ServerSocket`s and a BLE link, with the dash
     * dialling in - so there is nothing for the default route to carry. EasyConn and Yunmo do
     * open outbound sockets, and even though those are network-bound too, nothing in this log
     * says their dashes need the change. They keep the behaviour they ship with.
     */
    private fun handoffKeepsProcessUnbound(handle: TBoxSessionHandle): Boolean =
        resolveSessionProfile(handle).transportFamily == TBoxTransportFamily.THINKERRIDE

    /**
     * The profile this session is really speaking.
     *
     * The transport's own profile wins when discovery changed it - a dash that answered Yunmo
     * after EasyConn found nothing is not the profile the saved motorcycle resolves to. It is
     * null for every session that did NOT take that switch, though ([SelectingTBoxTransport]
     * clears it in `configureProtocolProfile` and only the Yunmo fallback sets it), so the saved
     * motorcycle is what answers for a KOVE or a CFMOTO - not an edge case, the normal path.
     */
    private fun resolveSessionProfile(handle: TBoxSessionHandle): TBoxModelProfile =
        handle.transport.activeProtocolProfile ?: TBoxModelProfile.resolve(
            handle.motorcycle.modelId,
            capabilityStore.load(handle.motorcycle)?.capabilities,
            ProfileOverride.byKey(handle.motorcycle.profileOverrideKey)
        )

    private suspend fun startBikeStream(handle: TBoxSessionHandle) {
        @Suppress("NAME_SHADOWING")
        var handle = handle
        if (stopping) return
        if (handle.link.network != null && !handoffKeepsProcessUnbound(handle)) {
            val rebound = handle.networkConnector.rebindProcessToTBoxWhenAvailable(
                TBOX_NETWORK_REBIND_TIMEOUT_MS
            )
            rebound.exceptionOrNull()?.let {
                throw IllegalStateException("T-Box network restore failed: ${it.message}", it)
            }
        } else if (handle.link.network != null) {
            ProjectionEventLog.record(
                "NETWORK",
                "Android Auto video is ready; leaving the process route alone for the T-Box " +
                    "hand-off (this transport opens no socket that needs it)."
            )
        } else {
            ProjectionEventLog.record(
                "NETWORK",
                "Android Auto video is ready; using the existing Wi-Fi Direct P2P route for T-Box hand-off."
            )
        }
        ProjectionEventLog.record(
            "ANDROID AUTO",
            "First AAP video frame received. Starting the dashboard session."
        )

        val savedArea = displayGeometryStore.load(handle.motorcycle.ssid)?.let { geometry ->
            TBoxEvent.VideoArea(geometry.width, geometry.height)
        }
        val fallbackArea = TBoxModelProfile.fallbackVideoArea(
            handle.motorcycle.modelId,
            capabilityStore.load(handle.motorcycle)?.capabilities,
            ProfileOverride.byKey(handle.motorcycle.profileOverrideKey)
        )
        // Both attempts run under this flag. The transport reports a failed handshake twice - once
        // as the return value below, once as a Stopped/FatalError event - and the event handler
        // must not race this block to the teardown; see onTBoxFailureEvent.
        handshakeInFlight.set(true)
        val configurationResult = try {
            var result = handle.transport.negotiateVideoConfiguration(
                host = handle.host,
                savedArea = savedArea,
                fallbackArea = fallbackArea,
                videoAreaTimeoutMillis = VIDEO_AREA_TIMEOUT_MS
            )
            if (result.isFailure) {
                // Same root cause as the other streaming-mode fix: whichever mode ran before this
                // one called transport.stop() on end, which for the real GPL transport fully tears
                // down the underlying session, so a bare retry of negotiateVideoConfiguration
                // fails identically every time. Re-run discover() from scratch instead, exactly
                // like a rider's manual "Connect" retry does.
                ProjectionEventLog.warning(
                    "ANDROID AUTO",
                    "T-Box handshake failed (first attempt): ${result.exceptionOrNull()?.message}. " +
                        "Re-discovering the T-Box before retrying."
                )
                val rediscovered = handle.transport.discover(handle.link, handle.motorcycle.modelId)
                val freshHost = rediscovered.getOrNull()
                if (freshHost == null) {
                    // Said out loud because the alternative reads identically in a log: a
                    // re-discovery that failed and a retry that was never reached both look like
                    // the warning above followed by nothing.
                    ProjectionEventLog.warning(
                        "ANDROID AUTO",
                        "Re-discovery failed, so there is no second handshake attempt: " +
                            "${rediscovered.exceptionOrNull()?.message}"
                    )
                } else {
                    handle = handle.copy(host = freshHost)
                    tBoxHandle = handle
                    TBoxSessionRegistry.install(handle)
                    // install() resets the claim list; this session is still using it.
                    TBoxSessionRegistry.claim(SESSION_CONSUMER)
                    result = handle.transport.negotiateVideoConfiguration(
                        host = handle.host,
                        savedArea = savedArea,
                        fallbackArea = fallbackArea,
                        videoAreaTimeoutMillis = VIDEO_AREA_TIMEOUT_MS
                    )
                }
            }
            result
        } finally {
            handshakeInFlight.set(false)
        }
        configurationResult.exceptionOrNull()?.let {
            throw IllegalStateException("T-Box handshake for Android Auto failed: ${it.message}", it)
        }
        if (stopping) return

        val configuration = configurationResult.getOrThrow()
        val quality = MotoHubSettings.videoQuality(this)
        val sessionModelProfile = resolveSessionProfile(handle)
        // What this dash is actually being sent. Identical to the profile's own fields for every
        // recognised dash; for an unidentified one it is whichever rung TBoxWireLadder has reached.
        val sessionWire = TBoxWireLadder.configFor(
            applicationContext,
            handle.motorcycle,
            sessionModelProfile
        )
        val encoderProfile = configuration.encoderProfile.copy(
            // Frame rate and bitrate were previously honoured only on the native mirror path, so a
            // dash whose profile asks for a slower capture still got Android Auto's negotiated 30
            // fps here. On a Yunmo dash that is not a quality preference: every all-intra frame is
            // a keyframe, a keyframe is split into three wire frames, and three times the frames
            // at three times the rate cannot fit through a three-frame send window.
            frameRate = sessionModelProfile.encoderFrameRate ?: configuration.encoderProfile.frameRate,
            bitRate = quality.bitrateFor(
                sessionModelProfile.encoderBitRate ?: configuration.encoderProfile.bitRate
            ),
            keyframeIntervalSeconds = sessionWire.encoderKeyframeIntervalSeconds,
            // Yunmo's split framing needs real keyframes to split; intra refresh would make them
            // rare. A profile can also demand plain IDRs for its decoder's sake (KOVE froze on
            // intra refresh); every other EasyConn dash keeps intra refresh.
            plainGopWithoutIntraRefresh =
                sessionWire.encoderPlainGopWithoutIntraRefresh ||
                    sessionModelProfile.transportFamily == TBoxTransportFamily.YUNMO,
            // ThinkerRide's video header declares the exact stream size; encode precisely that
            // instead of the 16-aligned canvas, like the reference app does.
            width = if (sessionModelProfile.encoderUsesExactVideoArea) {
                configuration.rawArea.width
            } else {
                configuration.encoderProfile.width
            },
            height = if (sessionModelProfile.encoderUsesExactVideoArea) {
                configuration.rawArea.height
            } else {
                configuration.encoderProfile.height
            }
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
        var liveGeometryPersisted = false
        if (configuration.source == TBoxVideoAreaSource.LIVE) {
            val negotiatedGeometry = DisplayGeometry(negotiatedArea.width, negotiatedArea.height)
            val liveCapabilities = capabilityStore.load(handle.motorcycle)?.capabilities
            val fallbackPreset = TBoxModelProfile.defaultAndroidAutoPreset(
                handle.motorcycle.modelId,
                liveCapabilities
            )
            val fallbackIsValidated = TBoxModelProfile.hasValidatedAndroidAutoPreset(
                handle.motorcycle.modelId,
                liveCapabilities
            )
            val shouldPersistGeometry = capabilityProfile.source == AndroidAutoCapabilitySource.USER_OVERRIDE ||
                AndroidAutoCapabilityProfiles.usableSavedGeometryForAuto(
                    negotiatedGeometry,
                    fallbackPreset,
                    fallbackIsValidated
                ) != null
            if (shouldPersistGeometry) {
                displayGeometryStore.save(handle.motorcycle.ssid, negotiatedGeometry)
                liveGeometryPersisted = true
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
            val followUp = if (liveGeometryPersisted) {
                "the learned profile will be used automatically the next time Android Auto starts."
            } else {
                // Saying "next time" when the geometry was just rejected is what made this
                // loop invisible in rider logs: the promise never came true.
                "this geometry was not saved, so the next session starts from the same profile - " +
                    "set the resolution manually in Settings to use it now."
            }
            ProjectionEventLog.warning(
                "ANDROID AUTO",
                "The live TFT geometry recommends ${learnedCapability.video.width}x" +
                    "${learnedCapability.video.height}@${learnedCapability.densityDpi}dpi. " +
                    "The current AAP session remains ${capabilityProfile.video.width}x" +
                    "${capabilityProfile.video.height}; $followUp"
            )
        }
        ProjectionEventLog.record(
            "T-BOX",
            "Area Android Auto ${encoderProfile.width}x${encoderProfile.height}; " +
                "quality=${quality.name}, bitrate=${encoderProfile.bitRate}."
        )
        try {
            backpressureGuard = VideoBackpressureGuard()
            // A session being (re)built around a profile is a new question, so the rider may be
            // asked again - including when they have just picked a different profile themselves.
            deliveryProbe = VideoDeliveryProbe()
            DashboardDeliveryMonitor.clear()

            // The one dash that is fed stills instead of video. Its OEM app never runs its own
            // H.264 path, and a dash that acknowledges every frame while painting none is what
            // feeding it a format it does not decode looks like. Everything below - the encoder,
            // the adaptive controller - is the path every other motorcycle takes, untouched.
            //
            // Wired here as well as in ProjectionSessionService because a rider reaches this dash
            // through whichever mode they happen to open, and a profile that silently falls back
            // to H.264 on three of the four paths produced three rounds of field tests that each
            // reported "JPEG does not work" without a single JPEG ever leaving the phone.
            if (sessionModelProfile.yunmoJpegVideo) {
                startJpegOutput(encoderProfile, capabilityProfile, handle)
                return
            }

            val activeEncoder = AvcEncoder(
                profile = encoderProfile,
                onAccessUnit = { accessUnit ->
                    if (handle.transport.offerAccessUnit(accessUnit)) {
                        backpressureGuard.onAccepted()
                        deliveryProbe.onAccepted()?.let { publishDeliveryVerdict(it, handle, sessionModelProfile) }
                        val accepted = framesAccepted.incrementAndGet()
                        frameLogThrottle.rateSuffixIfDue(accepted, SystemClock.elapsedRealtime())
                            ?.let { rate ->
                                ProjectionEventLog.record(
                                    "ANDROID AUTO",
                                    "Frames sent: $accepted$rate."
                                )
                            }
                        true
                    } else {
                        // A single rejection is a pushFrame() overlap, not a dead link - only a
                        // sustained streak ends the session (see VideoBackpressureGuard).
                        val fatal = backpressureGuard.onRejected()
                        // A dash that refuses most of what we send is not a link to tear down -
                        // it is a profile that does not match this dashboard, and the only one who
                        // can settle that is the rider. Concluded once per session; see
                        // DashboardDeliveryMonitor.
                        deliveryProbe.onRejected()?.let {
                            publishDeliveryVerdict(it, handle, sessionModelProfile)
                        }
                        if (backpressureGuard.isStreakStart()) {
                            ProjectionEventLog.warning(
                                "ANDROID AUTO",
                                "The T-Box rejected an Android Auto frame; holding the session " +
                                    "open while the transport recovers. Rejected so far: " +
                                    "${backpressureGuard.totalRejections()}."
                            )
                        }
                        if (fatal && transportUnavailable.compareAndSet(false, true)) {
                            val streak = backpressureGuard.rejectionStreak()
                            val streakMillis = backpressureGuard.streakMillis()
                            serviceScope.launch {
                                handleRecoverableFailure(
                                    "The T-Box no longer accepts Android Auto frames " +
                                        "($streak in a row over ${streakMillis}ms)."
                                )
                            }
                        }
                        false
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
            val handlebarEnabled = HandlebarControlStore.isEnabled(this)
            mediaButtonBridge?.setCaptureActive(handlebarEnabled)
            if (handlebarEnabled) {
                // The dash reads the AVRCP player's capabilities once, when its Bluetooth link
                // forms — usually before this session exists. Re-announcing here, with the
                // transport up, is what makes the dash actually route its handlebar buttons to us.
                mediaButtonBridge?.reassertCaptureAfterTransportReady()
            }
            ProjectionEventLog.record("ANDROID AUTO", "Android Auto streaming active on the TFT.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            throw IllegalStateException("Android Auto pipeline did not start: ${failure.message}", failure)
        }
    }

    /**
     * Feeds the dash JPEG stills instead of an H.264 stream, with the Android Auto compositor
     * drawing into the capture surface exactly as it would into the encoder's.
     *
     * No encoder is created, so [encoder] stays null - every later call site already tolerates
     * that (`encoder?.`, and the adaptive controller returns immediately on a null encoder), which
     * is also why the adaptive bitrate controller simply has nothing to do here: stills carry no
     * bitrate to adapt.
     */
    private fun startJpegOutput(
        encoderProfile: EncoderProfile,
        capabilityProfile: AndroidAutoCapabilityProfile,
        handle: TBoxSessionHandle
    ) {
        val transport = handle.transport as? SelectingTBoxTransport
            ?: error("JPEG projection needs the selecting transport; this session has none")
        lastStillAcceptedAt.set(SystemClock.elapsedRealtime())
        val source = JpegDisplaySource(
            width = encoderProfile.width,
            height = encoderProfile.height,
            frameRate = encoderProfile.frameRate,
            onFrame = { jpeg, frameId ->
                val accepted = transport.offerJpegFrame(jpeg, frameId)
                if (accepted) {
                    backpressureGuard.onAccepted()
                    lastStillAcceptedAt.set(SystemClock.elapsedRealtime())
                    val sent = framesAccepted.incrementAndGet()
                    frameLogThrottle.rateSuffixIfDue(sent, SystemClock.elapsedRealtime())
                        ?.let { rate ->
                            ProjectionEventLog.record(
                                "JPEG",
                                "Stills sent to the dashboard: $sent$rate."
                            )
                        }
                } else {
                    // A refused still is this path's normal resting state, not a fault. The source
                    // offers one every 100ms and this dash takes about two a second, so the other
                    // eight are simply the queue being busy - the guard's streak rule wrote 11,107
                    // warnings into one field log and buried everything worth reading. What does
                    // mean something is a dash that stops taking stills altogether, so that is
                    // what is watched here: nothing accepted at all, for a while.
                    val idle = SystemClock.elapsedRealtime() - lastStillAcceptedAt.get()
                    if (idle > STILL_SILENCE_FATAL_MS && transportUnavailable.compareAndSet(false, true)) {
                        serviceScope.launch {
                            handleRecoverableFailure(
                                "The T-Box has not taken a still for ${idle / 1000} seconds."
                            )
                        }
                    }
                }
                accepted
            },
            onFailure = { failure ->
                serviceScope.launch {
                    handleRecoverableFailure("JPEG capture stopped: ${failure.message}")
                }
            }
        )
        source.start()
        val captureSurface = source.surface ?: error("JPEG capture has no surface")
        jpegSource = source
        compositor?.setOutput(
            captureSurface,
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
        val handlebarEnabled = HandlebarControlStore.isEnabled(this)
        mediaButtonBridge?.setCaptureActive(handlebarEnabled)
        if (handlebarEnabled) {
            mediaButtonBridge?.reassertCaptureAfterTransportReady()
        }
        ProjectionEventLog.record(
            "ANDROID AUTO",
            "Android Auto streaming to the TFT as JPEG stills " +
                "(${encoderProfile.width}x${encoderProfile.height} @${encoderProfile.frameRate}fps)."
        )
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
                    is TBoxEvent.FatalError -> onTBoxFailureEvent("T-Box error: ${event.message}")
                    TBoxEvent.Stopped -> onTBoxFailureEvent("The T-Box ended Android Auto.")
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
        // A Wi-Fi Direct group has no ConnectivityManager network, so the Lost/Reacquired flow
        // above never fires for it. Watch the P2P broadcasts instead: recovery can then start
        // the moment the group dissolves rather than after a 10s video-watchdog stall.
        p2pGroupWatcher?.close()
        p2pGroupWatcher = (handle.link as? io.motohub.android.tbox.TBoxLink.WifiDirect)?.watchGroupLost {
            if (!stopping) {
                serviceScope.launch {
                    handleRecoverableFailure("The Wi-Fi Direct group with the dash was lost.")
                }
            }
        }
    }

    private fun handleTBoxNetworkLost(handle: TBoxSessionHandle) {
        // Losing the AP *while the session is still coming up* used to be fatal, because
        // auto-recovery only ever applied once streaming had been reached. On a dash whose
        // network is a WifiNetworkSpecifier request, Android reclaims it exactly here: the
        // process binding is released so Google's app can reach our AAP server, our activity
        // drops out of the foreground as Android Auto takes the screen, and the AP goes away a
        // second later (field logs 2026-08-15: healthy -49dBm/58Mbps right up to the loss, and
        // dash telemetry still arriving over Bluetooth 0.3s after it). The persistent request
        // brings the same AP back within seconds, so park and continue instead of tearing a
        // session down over a blip nobody could have felt.
        if (!hasReachedStreaming && !stopping) {
            resumeAfterStartupNetworkLoss(handle)
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

    /**
     * Waits for the persistent Wi-Fi request to bring the T-Box AP back after it vanished during
     * the hand-off, then restarts the bike stream on the reacquired network. The Android Auto
     * receiver is left running throughout: it is talking to Google's app over the phone, which
     * the missing AP never touched, so there is nothing to rebuild on that side.
     */
    private fun resumeAfterStartupNetworkLoss(handle: TBoxSessionHandle) {
        networkLossJob?.cancel()
        networkLossJob = serviceScope.launch {
            ProjectionEventLog.warning(
                "WATCHDOG",
                "T-Box Wi-Fi vanished while Android Auto was starting; waiting up to " +
                    "${STARTUP_NETWORK_LOSS_WAIT_MILLIS / 1_000L}s for it to come back " +
                    "before giving up on the session."
            )
            val network = handle.networkConnector.awaitNetworkAvailable(
                STARTUP_NETWORK_LOSS_WAIT_MILLIS
            )
            if (stopping) return@launch
            if (network == null) {
                fail("T-Box Wi-Fi connection lost and did not come back.")
                return@launch
            }
            ProjectionEventLog.record(
                "WATCHDOG",
                "T-Box Wi-Fi is back; restarting the Android Auto hand-off to the bike."
            )
            runCatching { requestTBoxRecovery("T-Box Wi-Fi returned after a hand-off blip.") }
                .onFailure { fail("T-Box Wi-Fi returned but the session could not restart: ${it.message}") }
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
            ProjectionEventLog.debug("TOUCH") {
                "Normalised raw=(${event.x},${event.y}) to AVC=(${mapped.first},${mapped.second})."
            }
        }
        receiver?.sendTouch(event.action, event.pointerId, mapped.first, mapped.second)
    }

    private fun startWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = serviceScope.launch {
            while (!stopping) {
                delay(WATCHDOG_TICK_MS)
                adaptiveVideoController.onTick(
                    encoder = encoder,
                    linkDown = transportUnavailable.get() || recoveryRequested.get()
                )
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

    /**
     * Routes a transport failure event, unless the handshake that produced it is still running and
     * about to report the same failure itself.
     */
    private fun onTBoxFailureEvent(message: String) {
        val ownedByHandshake =
            tBoxFailureOwnedByHandshake(handshakeInFlight.get(), "Android Auto", message)
        if (ownedByHandshake != null) {
            // INFO, not DEBUG: this is the line that says why the session did NOT end here, and
            // the console hides DEBUG by default.
            ProjectionEventLog.record("WATCHDOG", ownedByHandshake)
            return
        }
        handleRecoverableFailure(message)
    }

    private fun handleRecoverableFailure(message: String) {
        if (stopping) return
        val enabled = MotoHubSettings.autoRecovery(this)
        if (!shouldAutoRecoverAndroidAuto(
                hasReachedStreaming = hasReachedStreaming,
                enabled = enabled
            )
        ) {
            // Say why nothing will be retried. Without this line the log shows a session ending
            // and then simply stops, and "reconnection is switched off" reads exactly like
            // "reconnection ran and left no trace" - a distinction that took a full reading of
            // rider 8d5a1631's log (2026-08-26) to make, on the one question that log was sent to
            // answer. The failure message itself is untouched on purpose: the collector groups
            // failures across riders by the text of that line.
            androidAutoRecoveryRefusal(hasReachedStreaming, enabled)?.let { reason ->
                ProjectionEventLog.warning("WATCHDOG", "Not reconnecting Android Auto: $reason")
            }
            fail(message)
            return
        }
        requestTBoxRecovery(message)
    }

    /**
     * Retries [recoverTBoxStream] within a [RECOVERY_GIVE_UP_MILLIS] budget before giving
     * up and tearing the session down, instead of failing the whole Android Auto session
     * on the first transient error (a discovery timeout, a momentary Wi-Fi hiccup). This
     * mirrors the advanced streaming service's
     * `requestRecovery`, which already retries this way - Android Auto's own recovery was
     * previously a single attempt, contradicting the "Reconnecting" retry-budget state
     * ARCHITECTURE.md documents. [recoveryRequested] stays true for the whole multi-attempt
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
        p2pGroupWatcher?.close()
        p2pGroupWatcher = null
        compositor?.clearOutput()
        encoder?.stop()
        encoder = null
        jpegSource?.stop()
        jpegSource = null
        adaptiveVideoController.reset()
        tBoxTouchTransform = null
        transportUnavailable.set(false)
        videoStreamStartRequested.set(false)

        previousHandle.transport.stop()
        // Kept, not dropped: the recovery below reuses this very link, and on Wi-Fi Direct
        // releasing the group here is what made the rejoin impossible (see the retained-link
        // note on TBoxSessionRegistry).
        TBoxSessionRegistry.clear(previousHandle, retainLinkForRecovery = true)
        val link = TBoxLinkResolver.reacquire(
            applicationContext,
            previousHandle.networkConnector,
            previousHandle.motorcycle,
            NETWORK_REJOIN_WAIT_MILLIS,
            previousHandle.link
        )
        previousHandle.transport.configureProtocolProfile(
            TBoxModelProfile.resolve(
                previousHandle.motorcycle.modelId,
                null,
                ProfileOverride.byKey(previousHandle.motorcycle.profileOverrideKey)
            ),
            previousHandle.motorcycle
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
        // install() resets the claim list; this session is still using it. Without this, an AIDL
        // connect landing mid-recovery saw an empty consumer set and was admitted beside us.
        TBoxSessionRegistry.claim(SESSION_CONSUMER)
        capabilityStore.recordDiscovery(previousHandle.motorcycle, host)
        observeActiveSession(recoveredHandle)
        startBikeStream(recoveredHandle)
        check(AndroidAutoRuntime.state.value is AndroidAutoRuntimeState.Streaming) {
            "Recovered T-Box handshake did not return to streaming"
        }
        ProjectionEventLog.record("WATCHDOG", "Android Auto TFT stream recovered successfully.")
    }

    /**
     * An AAP session that ended without the rider asking for it.
     *
     * Deliberately not [fail]. Tearing the whole projection down here is what left riders looking
     * at the picker with the motorcycle still connected and nothing retrying: field log
     * 2026-09-02 caught it twice, at 15:11:59 and 15:27:12, and after the second one the session
     * was gone for the rest of the ride.
     *
     * The receiver SURVIVES a transport quit - `AaReceiver.stop` is only called from
     * [stopSession] - so it is still listening on :5288 and still dialling Android Auto's own
     * head unit server on :5277, which on 17.4 is the only way back in. Everything is therefore
     * left standing and Android Auto is given a window to reattach on its own; the T-Box session
     * and the bike stream are never touched, because nothing about them failed.
     *
     * The session is still torn down when that window closes, so a rider who has genuinely
     * finished with Android Auto gets a session that ends rather than one that hangs.
     */
    private fun handleAndroidAutoDrop(reason: String) {
        if (stopping) return
        if (!MotoHubSettings.autoRecovery(this)) {
            ProjectionEventLog.warning(
                "WATCHDOG",
                "Not holding the Android Auto session open: automatic reconnection is switched off."
            )
            fail(reason)
            return
        }
        if (!androidAutoReattachRequested.compareAndSet(false, true)) return
        ProjectionEventLog.warning(
            "WATCHDOG",
            "$reason The receiver is still up; holding the session open for " +
                "${AA_REATTACH_GIVE_UP_MILLIS / 1_000L}s while Android Auto reattaches."
        )
        // Out of Streaming for the duration, which also stops the frame watchdog from opening a
        // second, concurrent recovery over the same gap: its stall check only runs while Streaming.
        AndroidAutoRuntime.publish(AndroidAutoRuntimeState.ReceiverReady)
        androidAutoReattachJob = serviceScope.launch {
            val deadline = SystemClock.elapsedRealtime() + AA_REATTACH_GIVE_UP_MILLIS
            while (!stopping && SystemClock.elapsedRealtime() < deadline) {
                delay(AA_REATTACH_POLL_MILLIS)
                if (receiver?.hasLiveSession != true) continue
                androidAutoReattachRequested.set(false)
                // The bike stream never stopped, so this is Streaming again the moment the
                // transport is: there is no hand-off to redo and no frame to wait for.
                markWatchdogProgress()
                AndroidAutoRuntime.publish(AndroidAutoRuntimeState.Streaming)
                ProjectionEventLog.record("WATCHDOG", "Android Auto attached again.")
                return@launch
            }
            androidAutoReattachRequested.set(false)
            if (!stopping) {
                fail(
                    "$reason Android Auto did not come back within " +
                        "${AA_REATTACH_GIVE_UP_MILLIS / 1_000L} seconds."
                )
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
        androidAutoReattachJob?.cancel()
        networkLossJob?.cancel()
        transportEventsJob = null
        networkEventsJob = null
        receiverPreparationJob = null
        bikeStreamJob = null
        videoReadyTimeoutJob = null
        watchdogJob = null
        recoveryJob = null
        androidAutoReattachJob = null
        networkLossJob = null
        p2pGroupWatcher?.close()
        p2pGroupWatcher = null
        receiver?.stop()
        receiver = null
        AndroidAutoReceiverOwnership.release(this@AndroidAutoSessionService)
        simulatorHandlebarBridge?.stop()
        simulatorHandlebarBridge = null
        mediaButtonBridge?.stop()
        mediaButtonBridge = null
        AndroidAutoPreviewRuntime.clear(this)
        screenMarginsListener?.let { screenMarginsStore.removeListener(it) }
        screenMarginsListener = null
        compositor?.release()
        compositor = null
        encoder?.stop()
        encoder = null
        jpegSource?.stop()
        jpegSource = null
        tBoxTouchTransform = null
        releaseWakeLock()
        streamingLocks.release()

        // Only ever release the handle this session actually owns. The old
        // `?: TBoxSessionRegistry.current()` fallback meant an Android Auto session that never
        // started (framesSent=0, so tBoxHandle was still null) would grab whatever session
        // happened to be active - in practice a streaming Ride Dashboard - and tear it down.
        val releasedHandle = tBoxHandle
        tBoxHandle = null
        if (releasedHandle != null) {
            serviceScope.launch {
                try {
                    // Another mode may still be streaming on this session; only the last one out
                    // stops the transport and drops the network.
                    if (TBoxSessionRegistry.releaseAndClear(SESSION_CONSUMER, releasedHandle)) {
                        releasedHandle.transport.stop()
                        // The network itself is the registry's to drop: clear() released the
                        // session's lease on the shared connector, which disconnects only when
                        // no other owner (the Hub UI, the AIDL bridge) still needs it.
                    }
                } finally {
                    // Last thing this service ever does: the scope outlived stopSelf() before,
                    // so anything still suspended in it (a recovery mid-delay, an event
                    // collector) kept running against a service Android had already destroyed.
                    // Cancelling from inside is safe because this is the final statement -
                    // the work above has already completed.
                    serviceScope.cancel()
                }
            }
        } else {
            TBoxSessionRegistry.release(SESSION_CONSUMER)
            serviceScope.cancel()
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
        // Whoever asked for this stop left its reason behind; only a destroy nobody in the app
        // asked for still reads as Android's doing.
        stopSession(AndroidAutoStopReason.take() ?: AndroidAutoStopReason.STOPPED_BY_ANDROID)
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        val manager = getSystemService(PowerManager::class.java)
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:AndroidAuto").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun startSimulatorHandlebarBridgeIfNeeded(handle: TBoxSessionHandle) {
        if (TBoxModelProfile.fromModelId(handle.motorcycle.modelId) != TBoxModelProfile.MOTO_HUB_SIMULATOR) return
        simulatorHandlebarBridge = SimulatorHandlebarBridge(
            targetName = MediaButtonBridge.TARGET_ANDROID_AUTO,
            logTag = "ANDROID AUTO"
        ).also { it.start() }
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

    /**
     * Sink for the ported AAP stack and the bridges it drives, which emit plain strings with no
     * level. Classified by wording and deliberately kept out of telemetry: the guess used to
     * raise a Sentry event for every line that merely contained the word "dropped".
     */
    private fun log(message: String) = ProjectionEventLog.external("AAP", message)

    override fun attachPreview(surface: Surface, width: Int, height: Int) {
        compositor?.setPreview(surface, width, height)
    }

    override fun detachPreview() {
        compositor?.clearPreview()
    }

    override fun sendPreviewTouch(action: Int, pointerId: Int, x: Int, y: Int) {
        val mapped = compositor?.mapPreviewToUi(x, y) ?: return
        receiver?.sendSourceTouch(action, pointerId, mapped.first, mapped.second)
    }

    override fun sendPreviewKey(keycode: Int): Boolean = AaInputBridge.sendKey(keycode)

    override fun sendPreviewScroll(delta: Int): Boolean = AaInputBridge.sendScroll(delta)

    override fun setPreviewNightMode(isNight: Boolean): Boolean {
        val applied = receiver?.setNightMode(isNight) == true
        if (applied) AndroidAutoNightModeStore(this).save(isNight)
        return applied
    }

    companion object {
        private const val SESSION_CONSUMER = "android-auto"
        private const val CHANNEL_ID = "android_auto_session_v1"
        private const val NOTIFICATION_ID = 4201
        private const val ACTION_STOP = "io.motohub.android.action.STOP_ANDROID_AUTO"
        private const val AAP_VIDEO_READY_TIMEOUT_MS = 60_000L
        private const val TBOX_NETWORK_REBIND_TIMEOUT_MS = 8_000L
        private const val VIDEO_AREA_TIMEOUT_MS = 10_000L
        private const val WATCHDOG_TICK_MS = 5_000L
        private const val WATCHDOG_STALL_MS = 10_000L
        private const val NETWORK_LOSS_GRACE_MILLIS = 60_000L

        /**
         * How long a hand-off waits for an AP that disappeared mid-startup. Field logs show the
         * persistent request getting the same AP back in 10-50s, so this covers it with room to
         * spare while still failing in a time a rider would sit through.
         */
        private const val STARTUP_NETWORK_LOSS_WAIT_MILLIS = 60_000L
        private const val NETWORK_REJOIN_WAIT_MILLIS = 75_000L
        private const val RECOVERY_RETRY_MILLIS = 5_000L
        private const val RECOVERY_GIVE_UP_MILLIS = 120_000L

        /**
         * How long a dropped AAP session is held open before the session is given up on.
         *
         * Generous on purpose: the receiver polls Android Auto's head unit server every 1.5s and
         * the usual causes of a drop - the phone sleeping, Android Auto restarting itself - clear
         * in seconds, while the expensive alternative is a rider stopping to rebuild a session
         * that was about to come back on its own.
         */
        private const val AA_REATTACH_GIVE_UP_MILLIS = 90_000L
        private const val AA_REATTACH_POLL_MILLIS = 1_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 4 * 60 * 60 * 1_000L

        /**
         * How long the dash may take no still at all before the session is treated as lost.
         * Generous on purpose: this path expects most offers to be refused, so only complete
         * silence means anything, and a dash that is merely slow must not be torn down.
         */
        private const val STILL_SILENCE_FATAL_MS = 15_000L

        fun start(context: Context) {
            if (io.motohub.android.proFeatureUnavailable(context, "Android Auto")) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, AndroidAutoSessionService::class.java)
            )
        }

        /**
         * @param reason what the log should say this stop was, in the same voice as every other
         *   session-stop reason - it is what a rider's diagnostic report will name as the cause.
         *   Required on purpose: a caller that cannot say why it is stopping the session is the
         *   caller that used to leave Android holding the blame.
         */
        fun stop(context: Context, reason: String) {
            // Carried to onDestroy(), which is all Android gives an explicit stop; see
            // AndroidAutoStopReason for why the blame used to land on the system.
            AndroidAutoStopReason.publish(reason)
            // Stop the already-running foreground service explicitly. The previous implementation
            // started the service again with ACTION_STOP; that request could be ignored when it
            // came through the PRO AIDL bridge or the notification action. Android calls
            // onDestroy() for an explicit stop, where the complete session cleanup already lives.
            val intent = Intent(context, AndroidAutoSessionService::class.java)
            if (!context.stopService(intent)) {
                // If the service is still in its startup window, deliver the action as a fallback
                // so onStartCommand() can terminate the pending session as well.
                context.startService(intent.setAction(ACTION_STOP))
            }
        }
    }
}

private fun alignedCanvasGeometry(geometry: DisplayGeometry): DisplayGeometry {
    val profile = EncoderProfile.forTBoxArea(geometry.width, geometry.height)
    return DisplayGeometry(profile.width, profile.height)
}
