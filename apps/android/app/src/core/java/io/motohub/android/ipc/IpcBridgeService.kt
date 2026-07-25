// Public, GPL-3.0/AGPL-3.0-licensed bridge: exposes Core's T-Box transport (ridedaemon-lib,
// GPL-3.0) and Android Auto AAP receiver (aa/, AGPL-3.0 technique ported from headunit-revived)
// to another app's process over Binder IPC, so a closed-source companion app can use both
// without linking this code into its own binary. See the "Core/Pro split" note in
// documentation/ARCHITECTURE.md for why this boundary exists.
package io.motohub.android.ipc

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteCallbackList
import android.view.Surface
import androidx.core.app.NotificationCompat
import io.motohub.android.R
import io.motohub.android.androidauto.AaInputBridge
import io.motohub.android.aa.AaReceiver
import io.motohub.android.aa.AaSelfMode
import io.motohub.android.aa.SingleKeyKeyManager
import io.motohub.android.androidauto.AaCompositor
import io.motohub.android.androidauto.AndroidAutoCapabilityProfiles
import io.motohub.android.androidauto.AndroidAutoDisplayMode
import io.motohub.android.androidauto.AndroidAutoDisplayModeStore
import io.motohub.android.androidauto.AndroidAutoPreviewRuntime
import io.motohub.android.androidauto.AndroidAutoRuntime
import io.motohub.android.androidauto.AndroidAutoRuntimeState
import io.motohub.android.androidauto.AndroidAutoSessionService
import io.motohub.android.androidauto.withFullVideoTargetForDashboard
import io.motohub.android.feature.ridedashboard.RideDashboardAndroidAutoRuntime
import io.motohub.android.feature.ridedashboard.RideDashboardAndroidAutoState
import io.motohub.android.feature.ridedashboard.RideDashboardRuntime
import io.motohub.android.feature.ridedashboard.RideDashboardRuntimeState
import io.motohub.android.feature.ridedashboard.RideDashboardSessionService
import io.motohub.android.feature.settings.AndroidAutoAspectMatchingMode
import io.motohub.android.feature.settings.AndroidAutoResolutionMode
import io.motohub.android.feature.settings.MotoHubSettings
import io.motohub.android.feature.settings.VideoQuality
import io.motohub.android.session.ProjectionEventLog
import io.motohub.android.tbox.TBoxSessionHandle
import io.motohub.android.tbox.TBoxSessionRegistry
import io.motohub.android.tbox.negotiateVideoConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class IpcBridgeService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── T-Box transport ──────────────────────────────────────────────

    private val sessionListeners = RemoteCallbackList<ITBoxSessionListener>()
    private var sessionPollJob: Job? = null
    @Volatile private var lastKnownHandle: TBoxSessionHandle? = null
    @Volatile private var activeConnect: Pair<CoreTBoxConnector, Deferred<Boolean>>? = null

    private val tboxTransportBinder = object : ITBoxTransportService.Stub() {
        override fun isSessionReady(): Boolean = TBoxSessionRegistry.current() != null

        override fun getActiveMotorcycle(): MotorcycleSummary? =
            TBoxSessionRegistry.current()?.motorcycle?.let { profile ->
                MotorcycleSummary(
                    id = profile.id,
                    ssid = profile.ssid,
                    modelId = profile.modelId,
                    displayName = profile.displayName
                )
            }

        // TBoxSessionHandle doesn't carry a negotiated encoder profile today — that negotiation
        // happens per session-start inside each mode's own startCapture(), not as a queryable
        // property. Phase 2 (once a caller actually needs this) wires the real value through.
        override fun getNegotiatedEncoderProfile(): EncoderProfileParcel? = null

        // Runs the same EasyConn video start + live TFT-area negotiation Core's own
        // ProjectionSessionService does, but on behalf of a companion app that can't contain the
        // GPL transport. Blocking on the binder thread; the returned width/height are the raw TFT
        // area (the caller derives its own encoder profile/bitrate from it). offerAccessUnit()
        // starts delivering frames only after this returns non-null.
        override fun startVideoSession(): EncoderProfileParcel? =
            kotlinx.coroutines.runBlocking {
                val handle = TBoxSessionRegistry.current() ?: return@runBlocking null
                val result = handle.transport.negotiateVideoConfiguration(
                    host = handle.host,
                    savedArea = null,
                    timeoutMillis = VIDEO_CONFIGURATION_TIMEOUT_MS
                )
                val configuration = result.getOrElse {
                    ProjectionEventLog.warning(
                        "IPC_TBOX",
                        "startVideoSession negotiation failed: ${it.message}"
                    )
                    return@runBlocking null
                }
                val area = configuration.rawArea
                ProjectionEventLog.record(
                    "IPC_TBOX",
                    "Video session started for a companion app; TFT area ${area.width}x${area.height}."
                )
                EncoderProfileParcel(
                    width = area.width,
                    height = area.height,
                    frameRate = 30,
                    bitRate = 2_500_000
                )
            }

        override fun offerAccessUnit(accessUnit: ByteArray): Boolean =
            TBoxSessionRegistry.current()?.transport?.offerAccessUnit(accessUnit) ?: false

        // Runs Core's own GPL connect flow (hudlib) on behalf of a companion app that can't
        // contain it. Blocking on the binder thread until READY, mirroring the AIDL contract.
        // Launched as a cancellable Deferred (not a bare runBlocking body) so a concurrent
        // cancelConnect() call — arriving on a DIFFERENT binder thread — can actually interrupt
        // it instead of this call only ever returning once the connect attempt times out on
        // its own. See cancelConnect() below.
        override fun connect(request: MotorcycleConnectRequest): Boolean {
            val connector = CoreTBoxConnector(applicationContext)
            val deferred = serviceScope.async { connector.connect(request.toProfile()) }
            activeConnect = connector to deferred
            return kotlinx.coroutines.runBlocking {
                val result = try {
                    deferred.await()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    false
                }
                if (activeConnect?.second === deferred) activeConnect = null
                result
            }
        }

        override fun cancelConnect() {
            val (connector, deferred) = activeConnect ?: return
            deferred.cancel()
            serviceScope.launch { connector.cancel() }
        }

        override fun disconnect() {
            kotlinx.coroutines.runBlocking {
                CoreTBoxConnector(applicationContext).disconnect()
            }
        }

        override fun registerSessionListener(listener: ITBoxSessionListener) {
            sessionListeners.register(listener)
            ensureSessionPolling()
        }

        override fun unregisterSessionListener(listener: ITBoxSessionListener) {
            sessionListeners.unregister(listener)
        }
    }

    /** TBoxSessionRegistry exposes no observable state (a deliberately small, in-process-only
     *  registry — see its own doc comment), so this is the simplest way to turn its polled
     *  current() into ready/lost callbacks for a remote caller without changing that shared,
     *  already-depended-upon class. */
    private fun ensureSessionPolling() {
        if (sessionPollJob?.isActive == true) return
        sessionPollJob = serviceScope.launch {
            while (true) {
                val handle = TBoxSessionRegistry.current()
                if ((handle != null) != (lastKnownHandle != null)) {
                    val ready = handle != null
                    val count = sessionListeners.beginBroadcast()
                    for (i in 0 until count) {
                        runCatching {
                            if (ready) sessionListeners.getBroadcastItem(i).onSessionReady()
                            else sessionListeners.getBroadcastItem(i).onSessionLost()
                        }
                    }
                    sessionListeners.finishBroadcast()
                }
                lastKnownHandle = handle
                delay(SESSION_POLL_INTERVAL_MS)
            }
        }
    }

    // ── Android Auto receiver ────────────────────────────────────────

    private val stateListeners = RemoteCallbackList<IAndroidAutoStateListener>()
    private val embeddedDashboardStateListeners = RemoteCallbackList<IAndroidAutoStateListener>()
    private var compositor: AaCompositor? = null
    private var receiver: AaReceiver? = null

    private val androidAutoBinder = object : IAndroidAutoReceiverService.Stub() {
        override fun attachOutputSurface(surface: Surface, width: Int, height: Int): Boolean {
            if (receiver != null) {
                publishState(AndroidAutoIpcState.FAILED, "An Android Auto receiver session is already active.")
                return false
            }
            if (AndroidAutoRuntime.isActive()) {
                publishState(
                    AndroidAutoIpcState.FAILED,
                    "Core's full Android Auto session is already active; stop it before attaching a preview."
                )
                return false
            }
            if (!SingleKeyKeyManager.isAvailable(this@IpcBridgeService)) {
                publishState(AndroidAutoIpcState.FAILED, "Android Auto identity is not included in this build.")
                return false
            }
            publishState(AndroidAutoIpcState.PREPARING, "")
            val profile = AndroidAutoCapabilityProfiles.fallback().withFullVideoTargetForDashboard()
            val activeCompositor = AaCompositor(
                log = { ProjectionEventLog.debug("IPC_AA", it) },
                displayMode = AndroidAutoDisplayMode.STRETCH,
                sourceGeometry = profile.video
            )
            if (!activeCompositor.start()) {
                publishState(AndroidAutoIpcState.FAILED, "Compositor failed to initialize (EGL/GL).")
                return false
            }
            val decoderSurface = activeCompositor.inputSurface
            if (decoderSurface == null) {
                activeCompositor.release()
                publishState(AndroidAutoIpcState.FAILED, "Compositor did not create a video surface.")
                return false
            }
            activeCompositor.setOutput(surface, width, height, profile.video.width, profile.video.height)
            compositor = activeCompositor

            val activeReceiver = AaReceiver(
                context = applicationContext,
                encoderSurface = decoderSurface,
                log = { ProjectionEventLog.debug("IPC_AA", it) },
                onVideoReady = { publishState(AndroidAutoIpcState.STREAMING, "") },
                onSessionEnded = { clean, userExit ->
                    publishState(
                        AndroidAutoIpcState.STOPPED,
                        when {
                            userExit -> "The user ended the Android Auto session."
                            clean -> "Android Auto ended the session."
                            else -> "Android Auto connection closed unexpectedly."
                        }
                    )
                    releaseReceiver()
                },
                mapTouchToSource = activeCompositor::mapCanvasToUi,
                capabilityProfile = profile
            )
            if (!activeReceiver.start()) {
                releaseReceiver()
                publishState(AndroidAutoIpcState.FAILED, "Android Auto local port ${AaReceiver.PORT} is unavailable.")
                return false
            }
            receiver = activeReceiver
            publishState(AndroidAutoIpcState.RECEIVER_READY, "")
            return true
        }

        override fun detachOutputSurface() {
            releaseReceiver()
        }

        override fun sendTouch(action: Int, x: Int, y: Int): Boolean {
            val activeReceiver = receiver ?: return false
            activeReceiver.sendTouch(action, x, y)
            return true
        }

        // Route key/scroll to Core's active AAP input channel (installed by AaReceiver while a
        // session streams). Returns false when no input channel is ready.
        override fun sendKey(keycode: Int): Boolean = AaInputBridge.sendKey(keycode)
        override fun sendScroll(delta: Int): Boolean = AaInputBridge.sendScroll(delta)

        // Applies a companion app's settings snapshot to Core's own prefs (via the same setters
        // the UI uses) so the next session honors them. Enums are parsed defensively.
        override fun applyAndroidAutoSettings(settings: AndroidAutoSettingsParcel) {
            val ctx = applicationContext
            runCatching {
                MotoHubSettings.setAndroidAutoResolution(
                    ctx, AndroidAutoResolutionMode.valueOf(settings.resolutionMode)
                )
            }
            runCatching {
                MotoHubSettings.setAndroidAutoAspectMatching(
                    ctx, AndroidAutoAspectMatchingMode.valueOf(settings.aspectMatching)
                )
            }
            runCatching {
                MotoHubSettings.setVideoQuality(ctx, VideoQuality.valueOf(settings.videoQuality))
            }
            runCatching { MotoHubSettings.setDisableTouchscreen(ctx, settings.disableTouchscreen) }
            runCatching { MotoHubSettings.setSeamlessResume(ctx, settings.seamlessResume) }
            // Display mode (Garage's Stretch/Fit/Letterbox) is stored per-motorcycle, in the
            // caller's OWN app data — Core never sees it unless the caller forwards it here.
            // AndroidAutoSessionService reads it back keyed by handle.motorcycle (the same
            // profile this connect installed), so this must be saved before startFullSession.
            runCatching {
                if (settings.displayMode.isNotBlank()) {
                    TBoxSessionRegistry.current()?.motorcycle?.let { motorcycle ->
                        AndroidAutoDisplayModeStore(ctx).save(
                            motorcycle,
                            AndroidAutoDisplayMode.valueOf(settings.displayMode)
                        )
                    }
                }
            }
            ProjectionEventLog.record("IPC_AA", "Applied companion Android Auto settings snapshot.")
        }

        // Toggles day/night on the running session via the same runtime path the UI uses.
        override fun setNightMode(isNight: Boolean): Boolean =
            AndroidAutoPreviewRuntime.setNightMode(isNight)

        // Triggers Core's own existing AndroidAutoSessionService unchanged — this deliberately
        // does not duplicate its pipeline (watchdog/recovery/T-Box negotiation) here. Both this
        // and attachOutputSurface ultimately bind the same fixed local AA port; the loser of a
        // race fails cleanly (AaReceiver.start() returns false), it does not crash.
        override fun startFullSession(): Boolean {
            if (receiver != null) {
                publishState(AndroidAutoIpcState.FAILED, "An embedded preview session is already attached.")
                return false
            }
            if (AndroidAutoRuntime.isActive()) return true
            ensureFullSessionStateForwarding()
            AndroidAutoSessionService.start(this@IpcBridgeService)
            // Core's own UI (MainActivity) normally fires the self-mode trigger once the receiver
            // is ready. When a companion app drives the session over AIDL, that Activity isn't in
            // the loop, so trigger it here instead — the broadcast fallback works from a service.
            triggerSelfModeWhenReady()
            return true
        }

        override fun stopFullSession() {
            // Cancel any pending self-mode trigger first: a stop issued while the receiver is
            // still coming up would otherwise fire AaSelfMode after teardown and immediately
            // re-launch Google Android Auto, making the stop look like it "didn't work".
            selfModeJob?.cancel()
            selfModeJob = null
            AndroidAutoSessionService.stop(this@IpcBridgeService)
        }

        override fun registerStateListener(listener: IAndroidAutoStateListener) {
            stateListeners.register(listener)
            ensureFullSessionStateForwarding()
        }

        override fun unregisterStateListener(listener: IAndroidAutoStateListener) {
            stateListeners.unregister(listener)
        }

        // Triggers Core's own Ride Dashboard with Android Auto as the embedded map panel —
        // decoded AA video, dashboard widgets and telemetry are composited and pushed to the
        // real T-Box entirely inside Core, exactly as Core's own UI runs it today. A companion
        // app with no local GPL/AGPL code cannot run this panel itself. Guarded against the same
        // fixed AA port both this and startFullSession ultimately depend on.
        override fun startEmbeddedDashboardSession(): Boolean {
            val current = RideDashboardRuntime.state.value
            if (current is RideDashboardRuntimeState.Starting || current is RideDashboardRuntimeState.Streaming) {
                return true
            }
            if (receiver != null) {
                publishEmbeddedDashboardState(AndroidAutoIpcState.FAILED, "An embedded preview session is already attached.")
                return false
            }
            if (AndroidAutoRuntime.isActive()) {
                publishEmbeddedDashboardState(
                    AndroidAutoIpcState.FAILED,
                    "Core's full Android Auto session is already active; stop it before starting the dashboard."
                )
                return false
            }
            ensureEmbeddedDashboardStateForwarding()
            // Not a direct RideDashboardSessionService.start() call: that service declares
            // FOREGROUND_SERVICE_TYPE_LOCATION, which Android 14+ refuses to promote from a
            // background bound-service context like this one — only from a momentarily-visible
            // Activity. RideDashboardTrampolineActivity provides exactly that, invisibly.
            startActivity(
                android.content.Intent(this@IpcBridgeService, RideDashboardTrampolineActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            // Core's own UI normally fires the self-mode trigger once the embedded AA receiver is
            // ready (MainActivity.startRideDashboardAndroidAuto). When Pro drives this remotely,
            // that Activity isn't in the loop, so nothing ever asks Google Android Auto to
            // connect — the dashboard renders but the AA panel never receives video. Trigger it
            // here instead, exactly like startFullSession's triggerSelfModeWhenReady.
            triggerEmbeddedDashboardSelfModeWhenReady()
            return true
        }

        override fun stopEmbeddedDashboardSession() {
            // Cancel any pending self-mode trigger first — same reasoning as stopFullSession.
            embeddedDashboardSelfModeJob?.cancel()
            embeddedDashboardSelfModeJob = null
            RideDashboardSessionService.stop(this@IpcBridgeService)
        }

        override fun registerEmbeddedDashboardStateListener(listener: IAndroidAutoStateListener) {
            embeddedDashboardStateListeners.register(listener)
            ensureEmbeddedDashboardStateForwarding()
        }

        override fun unregisterEmbeddedDashboardStateListener(listener: IAndroidAutoStateListener) {
            embeddedDashboardStateListeners.unregister(listener)
        }
    }

    private var selfModeJob: Job? = null

    /** Mirrors MainActivity.startAndroidAuto's coordinator: wait for the receiver to be ready,
     *  let it settle, then ask Google Android Auto to connect to Core's local AAP port. */
    private fun triggerSelfModeWhenReady() {
        selfModeJob?.cancel()
        selfModeJob = serviceScope.launch {
            val state = kotlinx.coroutines.withTimeoutOrNull(SELF_MODE_READY_TIMEOUT_MS) {
                AndroidAutoRuntime.state
                    .dropWhile {
                        it is AndroidAutoRuntimeState.Idle ||
                            it is AndroidAutoRuntimeState.Stopped ||
                            it is AndroidAutoRuntimeState.Failed
                    }
                    .first {
                        it is AndroidAutoRuntimeState.ReceiverReady ||
                            it is AndroidAutoRuntimeState.Failed
                    }
            }
            if (state is AndroidAutoRuntimeState.ReceiverReady) {
                delay(ANDROID_AUTO_RECEIVER_SETTLE_MS)
                if (AndroidAutoRuntime.state.value is AndroidAutoRuntimeState.ReceiverReady) {
                    AaSelfMode.trigger(applicationContext) { ProjectionEventLog.record("AAP", it) }
                }
            }
        }
    }

    private var embeddedDashboardSelfModeJob: Job? = null

    /** Mirrors MainActivity.startRideDashboardAndroidAuto's coordinator, for when Ride Dashboard's
     *  embedded AA panel is driven remotely by a companion app with no Activity of its own in the
     *  loop to fire the trigger. */
    private fun triggerEmbeddedDashboardSelfModeWhenReady() {
        embeddedDashboardSelfModeJob?.cancel()
        embeddedDashboardSelfModeJob = serviceScope.launch {
            val state = kotlinx.coroutines.withTimeoutOrNull(SELF_MODE_READY_TIMEOUT_MS) {
                RideDashboardAndroidAutoRuntime.state
                    .dropWhile {
                        it is RideDashboardAndroidAutoState.Idle ||
                            it is RideDashboardAndroidAutoState.Failed
                    }
                    .first {
                        it is RideDashboardAndroidAutoState.ReceiverReady ||
                            it is RideDashboardAndroidAutoState.Failed
                    }
            }
            if (state is RideDashboardAndroidAutoState.ReceiverReady) {
                delay(ANDROID_AUTO_RECEIVER_SETTLE_MS)
                if (RideDashboardAndroidAutoRuntime.state.value is RideDashboardAndroidAutoState.ReceiverReady) {
                    AaSelfMode.trigger(applicationContext) { ProjectionEventLog.record("RIDE_AA", it) }
                }
            }
        }
    }

    private fun releaseReceiver() {
        receiver?.stop()
        receiver = null
        compositor?.clearOutput()
        compositor?.release()
        compositor = null
    }

    private var fullSessionForwardingJob: Job? = null

    /** Forwards Core's own AndroidAutoRuntime.state (used by AndroidAutoSessionService, already
     *  Core's shipping full-AA feature) to remote listeners — Core's implementation itself is
     *  untouched, this only republishes its existing state on the same channel embedded-preview
     *  callers already listen on. */
    private fun ensureFullSessionStateForwarding() {
        if (fullSessionForwardingJob?.isActive == true) return
        fullSessionForwardingJob = serviceScope.launch {
            var firstEmission = true
            AndroidAutoRuntime.state.collectLatest { state ->
                // AndroidAutoRuntime.state is a StateFlow that keeps whatever it was last set to
                // by ANY previous attempt (including one that predates this listener, e.g. a
                // user tapping start before a T-Box was ready). Don't surface a stale
                // Failed/Stopped from before this listener existed as if it just happened now —
                // only report it if it happens WHILE we're actually watching.
                if (firstEmission) {
                    firstEmission = false
                    if (state is AndroidAutoRuntimeState.Failed || state is AndroidAutoRuntimeState.Stopped) {
                        publishState(AndroidAutoIpcState.IDLE, "")
                        return@collectLatest
                    }
                }
                val (ipcState, message) = when (state) {
                    AndroidAutoRuntimeState.Idle -> AndroidAutoIpcState.IDLE to ""
                    AndroidAutoRuntimeState.Preparing -> AndroidAutoIpcState.PREPARING to ""
                    AndroidAutoRuntimeState.ReceiverReady -> AndroidAutoIpcState.RECEIVER_READY to ""
                    AndroidAutoRuntimeState.Streaming -> AndroidAutoIpcState.STREAMING to ""
                    is AndroidAutoRuntimeState.Stopped -> AndroidAutoIpcState.STOPPED to state.reason
                    is AndroidAutoRuntimeState.Failed -> AndroidAutoIpcState.FAILED to state.message
                }
                publishState(ipcState, message)
            }
        }
    }

    private fun publishState(state: Int, message: String) {
        ProjectionEventLog.debug("IPC_AA", "state=$state message=$message")
        val count = stateListeners.beginBroadcast()
        for (i in 0 until count) {
            runCatching { stateListeners.getBroadcastItem(i).onStateChanged(state, message) }
        }
        stateListeners.finishBroadcast()
    }

    private var embeddedDashboardForwardingJob: Job? = null

    /** Forwards Core's own RideDashboardRuntime.state (used by RideDashboardSessionService,
     *  already Core's shipping feature) to remote listeners — same first-emission-guard pattern
     *  as ensureFullSessionStateForwarding, so a stale Failed/Stopped from before this listener
     *  existed isn't surfaced as if it just happened. */
    private fun ensureEmbeddedDashboardStateForwarding() {
        if (embeddedDashboardForwardingJob?.isActive == true) return
        embeddedDashboardForwardingJob = serviceScope.launch {
            var firstEmission = true
            RideDashboardRuntime.state.collectLatest { state ->
                if (firstEmission) {
                    firstEmission = false
                    if (state is RideDashboardRuntimeState.Failed || state is RideDashboardRuntimeState.Stopped) {
                        publishEmbeddedDashboardState(AndroidAutoIpcState.IDLE, "")
                        return@collectLatest
                    }
                }
                val (ipcState, message) = when (state) {
                    RideDashboardRuntimeState.Idle -> AndroidAutoIpcState.IDLE to ""
                    RideDashboardRuntimeState.Starting -> AndroidAutoIpcState.PREPARING to ""
                    RideDashboardRuntimeState.Streaming -> AndroidAutoIpcState.STREAMING to ""
                    is RideDashboardRuntimeState.Stopped -> AndroidAutoIpcState.STOPPED to state.reason
                    is RideDashboardRuntimeState.Failed -> AndroidAutoIpcState.FAILED to state.message
                }
                publishEmbeddedDashboardState(ipcState, message)
            }
        }
    }

    private fun publishEmbeddedDashboardState(state: Int, message: String) {
        ProjectionEventLog.debug("IPC_RIDE_AA", "state=$state message=$message")
        val count = embeddedDashboardStateListeners.beginBroadcast()
        for (i in 0 until count) {
            runCatching { embeddedDashboardStateListeners.getBroadcastItem(i).onStateChanged(state, message) }
        }
        embeddedDashboardStateListeners.finishBroadcast()
    }

    // ── Service lifecycle ────────────────────────────────────────────

    // This service only exists while a companion app (PRO) is bound to it — but a plain bound
    // service with no foreground presence is just a background process to the OS, and OEM
    // battery managers (ColorOS/OnePlus in particular) reap those aggressively even while the
    // binding client (PRO) is itself in the foreground. That silently drops TBoxSessionRegistry
    // (in-memory only) and any active AA session, surfacing as "No T-Box is ready" or a session
    // that stops working until the rider disconnects and reconnects. Run in the foreground for
    // this service's whole lifetime (bind-to-unbind) so it survives like Core's own AA/Mirroring/
    // Ride Dashboard sessions already do.
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.core_bridge_channel_name),
                NotificationManager.IMPORTANCE_MIN
            ).apply { description = getString(R.string.core_bridge_channel_description) }
        )
        startForeground(NOTIFICATION_ID, createNotification())
    }

    private fun createNotification(): android.app.Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.core_bridge_notification_title))
            .setContentText(getString(R.string.core_bridge_notification_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    override fun onBind(intent: Intent): IBinder? = when (intent.action) {
        IpcBridgeContract.BIND_ACTION_TBOX_TRANSPORT -> tboxTransportBinder
        IpcBridgeContract.BIND_ACTION_ANDROID_AUTO_RECEIVER -> androidAutoBinder
        else -> null
    }

    override fun onDestroy() {
        sessionPollJob?.cancel()
        fullSessionForwardingJob?.cancel()
        embeddedDashboardForwardingJob?.cancel()
        selfModeJob?.cancel()
        embeddedDashboardSelfModeJob?.cancel()
        releaseReceiver()
        sessionListeners.kill()
        stateListeners.kill()
        embeddedDashboardStateListeners.kill()
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private companion object {
        const val SESSION_POLL_INTERVAL_MS = 1_000L
        const val VIDEO_CONFIGURATION_TIMEOUT_MS = 8_000L
        const val SELF_MODE_READY_TIMEOUT_MS = 10_000L
        const val ANDROID_AUTO_RECEIVER_SETTLE_MS = 900L
        const val CHANNEL_ID = "core_bridge_v1"
        const val NOTIFICATION_ID = 9101
    }
}
