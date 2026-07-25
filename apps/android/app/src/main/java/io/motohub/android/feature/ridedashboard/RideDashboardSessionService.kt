package io.motohub.android.feature.ridedashboard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.motohub.android.R
import io.motohub.android.androidauto.AndroidAutoCapabilityProfiles
import io.motohub.android.androidauto.AndroidAutoDisplayModeStore
import io.motohub.android.androidauto.DisplayGeometry
import io.motohub.android.androidauto.TBoxDisplayGeometryStore
import io.motohub.android.androidauto.TBoxScreenMarginsStore
import io.motohub.android.androidauto.withFullVideoTargetForDashboard
import io.motohub.android.encoding.AdaptiveVideoController
import io.motohub.android.encoding.AvcEncoder
import io.motohub.android.feature.controls.HandlebarControlStore
import io.motohub.android.feature.controls.HandlebarGesture
import io.motohub.android.feature.controls.MediaButtonBridge
import io.motohub.android.feature.controls.SimulatorHandlebarBridge
import io.motohub.android.androidauto.TBoxScreenMargins
import io.motohub.android.feature.ridedashboard.widget.DashboardLayoutConfig
import io.motohub.android.feature.ridedashboard.widget.DashboardLayoutStore
import io.motohub.android.feature.ridedashboard.widget.DashboardWidgetRegistry
import io.motohub.android.feature.settings.MotoHubSettings
import io.motohub.android.feature.ridedashboard.nav.OpenMeteoWeatherClient
import io.motohub.android.feature.ridedashboard.nav.NavPoint
import io.motohub.android.feature.ridedashboard.nav.runWeatherUpdateLoop
import io.motohub.android.feature.trips.TripRecordingService
import io.motohub.android.feature.trips.TripRecordingSource
import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.session.ProjectionEventLog
import io.motohub.android.tbox.TBoxCapabilityStore
import io.motohub.android.tbox.ProfileOverride
import io.motohub.android.tbox.TBoxEvent
import io.motohub.android.tbox.TBoxLinkResolver
import io.motohub.android.tbox.TBoxNetworkEvent
import io.motohub.android.tbox.TBoxModelProfile
import io.motohub.android.tbox.TBoxSessionHandle
import io.motohub.android.tbox.TBoxSessionRegistry
import io.motohub.android.tbox.TBoxStreamingLocks
import io.motohub.android.tbox.TBoxTouchTransform
import io.motohub.android.tbox.TBoxTouchFilter
import io.motohub.android.tbox.TBoxVideoAreaSource
import io.motohub.android.tbox.TBoxVideoSink
import io.motohub.android.tbox.negotiateVideoConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Streams the native Ride Dashboard directly into the existing T-Box video transport. */
class RideDashboardSessionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startupRequested = AtomicBoolean(false)
    private val transportUnavailable = AtomicBoolean(false)
    private val videoStreamStartRequested = AtomicBoolean(false)
    private val framesAccepted = AtomicLong(0)
    private val capabilityStore by lazy { TBoxCapabilityStore(this) }
    private val layoutController = RideDashboardLayoutController()
    private val dashboardGestureHandler: (HandlebarGesture) -> Boolean = ::handleHandlebarGesture
    private var encoder: AvcEncoder? = null
    private val adaptiveVideoController = AdaptiveVideoController(this, ::log)
    private var adaptiveJob: Job? = null
    private val streamingLocks = TBoxStreamingLocks(this, "Ride Dashboard")
    private var pipeline: RideDashboardStreamingPipeline? = null
    private var renderer: RideDashboardRenderer? = null
    private var dashboardLayoutStore: DashboardLayoutStore? = null
    private var dashboardLayoutListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var embeddedAndroidAuto: EmbeddedAndroidAutoSource? = null
    private var screenMarginsStoreForListener: TBoxScreenMarginsStore? = null
    private var screenMarginsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var telemetryProvider: RideTelemetryProvider? = null
    private var mediaButtonBridge: MediaButtonBridge? = null
    private var simulatorHandlebarBridge: SimulatorHandlebarBridge? = null
    private var tBoxHandle: TBoxSessionHandle? = null
    private var transportEventsJob: Job? = null
    private var networkEventsJob: Job? = null
    private var recoveryJob: Job? = null
    private var networkLossJob: Job? = null
    private var weatherJob: Job? = null
    private var mapSource = RideDashboardMapSource.OPEN_STREET_MAP
    private val recoveryRequested = AtomicBoolean(false)
    @Volatile private var tBoxTouchTransform: TBoxTouchTransform? = null
    private var touchFilter: TBoxTouchFilter? = null

    @Volatile
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.ride_dashboard_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = getString(R.string.ride_dashboard_channel_description) }
        )
        ProjectionEventLog.record("RIDE_DASHBOARD", "Ride Dashboard foreground service created.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSession("Ride Dashboard stopped by the user.")
            return START_NOT_STICKY
        }
        if (!startupRequested.compareAndSet(false, true)) {
            ProjectionEventLog.warning("RIDE_DASHBOARD", "Duplicate dashboard start request ignored.")
            return START_NOT_STICKY
        }
        mapSource = intent?.getStringExtra(EXTRA_MAP_SOURCE)
            ?.let { stored -> RideDashboardMapSource.entries.firstOrNull { it.name == stored } }
            ?: RideDashboardMapSourceStore.load(this)
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                createNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
            streamingLocks.acquire()
        } catch (failure: Throwable) {
            ProjectionEventLog.error(
                "RIDE_DASHBOARD",
                "Unable to promote Ride Dashboard to a foreground service.",
                failure
            )
            fail("Ride Dashboard foreground service failed: ${failure.message}")
            return START_NOT_STICKY
        }
        mediaButtonBridge = MediaButtonBridge(
            context = applicationContext,
            log = { message -> ProjectionEventLog.record("RIDE_CONTROLS", message) },
            targetName = MediaButtonBridge.TARGET_RIDE_DASHBOARD,
            gestureHandler = dashboardGestureHandler
        ).also { it.start() }
        serviceScope.launch { startDashboard() }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopSession("Ride Dashboard service stopped by Android.")
        super.onDestroy()
    }

    private suspend fun startDashboard() {
        try {
            startDashboardInternal()
        } catch (failure: Throwable) {
            ProjectionEventLog.error(
                "RIDE_DASHBOARD",
                "Unhandled dashboard startup failure before pipeline initialization.",
                failure
            )
            if (!stopping) fail("Ride Dashboard startup failed: ${failure.message}")
        }
    }

    private suspend fun startDashboardInternal() {
        RideDashboardRuntime.publish(RideDashboardRuntimeState.Starting)
        ProjectionEventLog.record(
            "RIDE_DASHBOARD",
            "Starting EasyConn dashboard handshake with map source=${mapSource.name}."
        )
        val handle = TBoxSessionRegistry.current()
            ?: return fail("No T-Box session is ready. Reconnect the motorcycle before starting the dashboard.")
        tBoxHandle = handle
        startSimulatorHandlebarBridgeIfNeeded(handle)
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
            TBoxTouchFilter(
                log = { message -> ProjectionEventLog.record("TOUCH", message) },
                downstream = ::forwardTBoxTouchRaw,
                policy = modelProfile.touchPolicy
            )
        } else {
            null
        }
        observeActiveSession(handle)
        val cellularOnlyMaps = modelProfile.mapTilesRequireCellular
        val screenMargins = TBoxScreenMarginsStore(this).load(
            handle.motorcycle,
            modelProfile.defaultScreenMargins
        )
        ProjectionEventLog.record(
            "T-BOX",
            "Behavior profile=${modelProfile.displayName}; touch enabled=$touchEnabled, " +
                "touch max=${modelProfile.touchPolicy.maxPointers}, " +
                "stale=${modelProfile.touchPolicy.staleContactMillis}ms; screen margins=$screenMargins."
        )
        ProjectionEventLog.record(
            "RIDE_MAP",
            if (cellularOnlyMaps) {
                "Ride Dashboard map tiles will use the cellular network for ${modelProfile.displayName}."
            } else {
                "Ride Dashboard map tiles will use the phone's default network for the simulator."
            }
        )

        val geometryStore = TBoxDisplayGeometryStore(this)
        val savedArea = geometryStore.load(handle.motorcycle.ssid)?.let { geometry ->
            TBoxEvent.VideoArea(geometry.width, geometry.height)
        }
        val configurationResult = handle.transport.negotiateVideoConfiguration(
            host = handle.host,
            savedArea = savedArea,
            timeoutMillis = VIDEO_CONFIGURATION_TIMEOUT_MILLIS
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
        tBoxTouchTransform = TBoxTouchTransform.forVideoConfiguration(configuration)
        ProjectionEventLog.record(
            "TOUCH",
            "Ride Dashboard touch domain ${area.width}x${area.height} maps to " +
                "AVC canvas ${profile.width}x${profile.height}."
        )
        if (configuration.source == TBoxVideoAreaSource.LIVE) {
            geometryStore.save(handle.motorcycle.ssid, DisplayGeometry(area.width, area.height))
        }
        ProjectionEventLog.record(
            "RIDE_DASHBOARD",
            "TFT ${configuration.source} area ${area.width}x${area.height}; dashboard canvas " +
                "${profile.width}x${profile.height}@${profile.frameRate}, " +
                "quality=${quality.name}, bitrate=${profile.bitRate}."
        )
        if (stopping) return

        try {
            adaptiveVideoController.reset()
            val tBoxLabel = capabilityStore.load(handle.motorcycle)?.capabilities?.let { capabilities ->
                capabilities.huName ?: capabilities.carModel ?: capabilities.packageName
            } ?: handle.host.packageName.substringAfterLast('.').ifBlank { handle.motorcycle.ssid }
            val androidAutoDisplayMode = AndroidAutoDisplayModeStore(this).load(handle.motorcycle)
            val androidAutoResolutionMode = MotoHubSettings.androidAutoResolution(this)
            // Embedded Android Auto as the dashboard map panel runs the AGPL AA receiver in-process.
            // PRO holds none of that code (AA runs in CORE), so this stays CORE-only; in PRO an
            // ANDROID_AUTO map source simply renders without the embedded AA panel.
            val activeEmbeddedAndroidAuto = if (
                mapSource == RideDashboardMapSource.ANDROID_AUTO && !io.motohub.android.BuildConfig.IS_PRO
            ) {
                EmbeddedAndroidAutoSource(
                    context = this,
                    capabilityProfile = AndroidAutoCapabilityProfiles.select(
                        target = DisplayGeometry(profile.width, profile.height),
                        overridePreset = androidAutoResolutionMode.preset,
                        screenMargins = screenMargins,
                        touchEnabled = touchEnabled,
                        fallbackPreset = TBoxModelProfile.defaultAndroidAutoPreset(
                            handle.motorcycle.modelId,
                            cachedCapabilities
                        )
                    ).withFullVideoTargetForDashboard(),
                    displayMode = androidAutoDisplayMode
                )
            } else {
                null
            }
            val activePipeline = RideDashboardStreamingPipeline(
                context = this,
                encoderProfile = profile,
                tBoxLabel = tBoxLabel,
                motorcyclePhotoPath = handle.motorcycle.photoPath,
                layoutController = layoutController,
                mapSource = mapSource,
                embeddedAndroidAuto = activeEmbeddedAndroidAuto,
                cellularOnlyMaps = cellularOnlyMaps,
                sink = TBoxVideoSink(handle),
                onFrameAccepted = {
                    val accepted = framesAccepted.incrementAndGet()
                    if (accepted == 1L || accepted % FRAME_LOG_INTERVAL == 0L) {
                        ProjectionEventLog.record(
                            "RIDE_DASHBOARD",
                            "Dashboard frames sent to T-Box: $accepted."
                        )
                    }
                },
                onSinkRejected = {
                    if (!recoveryRequested.get() && transportUnavailable.compareAndSet(false, true)) {
                        serviceScope.launch {
                            if (!stopping) fail("The T-Box session no longer accepts dashboard frames.")
                        }
                    }
                },
                onFailure = { failure ->
                    serviceScope.launch {
                        if (!stopping) fail("Dashboard pipeline stopped: ${failure.message}")
                    }
                },
                leftWidget = DashboardWidgetRegistry.forId(
                    DashboardLayoutStore(this).load(handle.motorcycle.ssid).leftWidgetId
                ) ?: DashboardWidgetRegistry.forId(DashboardLayoutConfig.DEFAULT.leftWidgetId)!!,
                rightWidget = DashboardWidgetRegistry.forId(
                    DashboardLayoutStore(this).load(handle.motorcycle.ssid).rightWidgetId
                ) ?: DashboardWidgetRegistry.forId(DashboardLayoutConfig.DEFAULT.rightWidgetId)!!
            )
            activePipeline.start()
            pipeline = activePipeline
            encoder = activePipeline.encoder
            renderer = activePipeline.renderer
            telemetryProvider = activePipeline.telemetryProvider
            embeddedAndroidAuto = activeEmbeddedAndroidAuto
            observeDashboardLayoutChanges(handle.motorcycle.ssid)
            observeScreenMarginChanges(handle.motorcycle, modelProfile.defaultScreenMargins)
            if (videoStreamStartRequested.get()) {
                encoder?.requestSyncFrame("TFT consumer already requested dashboard video")
            }
            // Force an immediate keyframe so the encoder starts producing output
            // right away — the first frames carry the startup sweep and must not
            // linger in the encoder buffer waiting for a TFT stream-start command.
            // If VideoStreamStart arrived before the encoder was ready, the request
            // was already issued immediately after activeEncoder.start() above;
            // avoid issuing a duplicate IDR in that path.
            if (!videoStreamStartRequested.get()) {
                encoder?.requestSyncFrame("Dashboard renderer started")
            }
            adaptiveJob?.cancel()
            adaptiveJob = serviceScope.launch {
                while (!stopping) {
                    delay(ADAPTIVE_TICK_MILLIS)
                    adaptiveVideoController.onTick(encoder)
                }
            }
            if (activeEmbeddedAndroidAuto != null) {
                ProjectionEventLog.record(
                    "RIDE_AA",
                    "Embedded Android Auto display mode for ${handle.motorcycle.ssid}: " +
                        "$androidAutoDisplayMode."
                )
                handle.networkConnector.releaseProcessBinding()
                ProjectionEventLog.record(
                    "NETWORK",
                    "T-Box process binding released for embedded Android Auto loopback."
                )
                activeEmbeddedAndroidAuto.start()
            }
            RideDashboardControlBridge.install(dashboardGestureHandler)
            mediaButtonBridge?.setCaptureActive(HandlebarControlStore.isEnabled(this))
            if (HandlebarControlStore.isEnabled(this)) {
                mediaButtonBridge?.reassertCaptureAfterTransportReady()
            }
            ProjectionEventLog.record(
                "RIDE_CONTROLS",
                "Dashboard controls ready; handlebar capture=${HandlebarControlStore.isEnabled(this)}."
            )
            RideDashboardRuntime.publish(RideDashboardRuntimeState.Streaming)
            startWeatherUpdates()
            ProjectionEventLog.record(
                "RIDE_DASHBOARD",
                "Ride Dashboard renderer and T-Box streaming are active."
            )
        } catch (failure: Throwable) {
            ProjectionEventLog.error("RIDE_DASHBOARD", "Dashboard startup threw an exception.", failure)
            fail("Ride Dashboard did not start: ${failure.message}")
        }
    }

    /**
     * Applies a widget layout picked in [io.motohub.android.feature.ridedashboard.widget.DashboardWidgetPickerScreen]
     * to the running renderer immediately, instead of only on the next dashboard start.
     */
    private fun observeDashboardLayoutChanges(ssid: String) {
        val store = DashboardLayoutStore(this)
        val watchedKey = store.keyFor(ssid)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != watchedKey) return@OnSharedPreferenceChangeListener
            val config = store.load(ssid)
            val left = DashboardWidgetRegistry.forId(config.leftWidgetId)
                ?: DashboardWidgetRegistry.forId(DashboardLayoutConfig.DEFAULT.leftWidgetId)!!
            val right = DashboardWidgetRegistry.forId(config.rightWidgetId)
                ?: DashboardWidgetRegistry.forId(DashboardLayoutConfig.DEFAULT.rightWidgetId)!!
            renderer?.updateWidgetLayout(left, right)
            ProjectionEventLog.record(
                "RIDE_DASHBOARD",
                "Dashboard widget layout applied live: left=${config.leftWidgetId}, right=${config.rightWidgetId}."
            )
        }
        store.addListener(listener)
        dashboardLayoutStore = store
        dashboardLayoutListener = listener
    }

    /**
     * Applies a screen-margin change picked in
     * [io.motohub.android.feature.garage.MotorcycleDetailsScreen] to the running embedded
     * Android Auto compositor immediately, instead of only on the next dashboard start.
     * A no-op when the dashboard map source isn't Android Auto ([embeddedAndroidAuto] is null).
     */
    private fun observeScreenMarginChanges(motorcycle: MotorcycleProfile, defaultMargins: TBoxScreenMargins) {
        val store = TBoxScreenMarginsStore(this)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (!store.belongsToMotorcycle(key, motorcycle.ssid)) return@OnSharedPreferenceChangeListener
            val margins = store.load(motorcycle, defaultMargins)
            embeddedAndroidAuto?.refreshMargins(margins)
            ProjectionEventLog.record("RIDE_DASHBOARD", "Screen margins applied live: $margins.")
        }
        store.addListener(listener)
        screenMarginsStoreForListener = store
        screenMarginsListener = listener
    }

    private fun handleHandlebarGesture(gesture: HandlebarGesture): Boolean {
        val layout = when (gesture) {
            HandlebarGesture.VOLUME_UP,
            HandlebarGesture.VOLUME_UP_DOUBLE -> layoutController.onUp()

            HandlebarGesture.VOLUME_DOWN,
            HandlebarGesture.VOLUME_DOWN_DOUBLE -> layoutController.onDown()

            HandlebarGesture.ENTER,
            HandlebarGesture.ENTER_LONG,
            HandlebarGesture.ENTER_DOUBLE,
            HandlebarGesture.TRACK_BACK,
            HandlebarGesture.TRACK_BACK_DOUBLE,
            HandlebarGesture.TRACK_FORWARD,
            HandlebarGesture.TRACK_FORWARD_DOUBLE -> {
                // Ride Dashboard only claims Up/Down for panel switching. Returning false here
                // (not "handled") lets MediaButtonBridge fall through to the rider's configured
                // HandlebarControlStore mapping, same as full-screen Android Auto mode — otherwise
                // select/back/track gestures are silently swallowed while AA is embedded.
                ProjectionEventLog.record(
                    "RIDE_CONTROLS",
                    "${gesture.label} passed through to the embedded Android Auto/dashboard mapping."
                )
                return false
            }
        }
        ProjectionEventLog.record(
            "RIDE_CONTROLS",
            "Dashboard layout changed to ${layout.label}; phase=${layout.phase.name}."
        )
        return true
    }

    private fun startSimulatorHandlebarBridgeIfNeeded(handle: TBoxSessionHandle) {
        if (TBoxModelProfile.fromModelId(handle.motorcycle.modelId) != TBoxModelProfile.MOTO_HUB_SIMULATOR) return
        simulatorHandlebarBridge = SimulatorHandlebarBridge(
            targetName = MediaButtonBridge.TARGET_RIDE_DASHBOARD,
            logTag = "RIDE_CONTROLS"
        ).also { it.start() }
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
                            "RIDE_DASHBOARD",
                            "Capability snapshot refreshed for ${handle.motorcycle.ssid}."
                        )
                    }
                    TBoxEvent.VideoStreamStart -> {
                        videoStreamStartRequested.set(true)
                        encoder?.requestSyncFrame("TFT consumer requested dashboard video")
                    }
                    is TBoxEvent.Warning -> ProjectionEventLog.record("T-BOX", event.message)
                    is TBoxEvent.FatalError -> requestRecovery("T-Box error: ${event.message}")
                    TBoxEvent.Stopped -> requestRecovery("The T-Box ended the Ride Dashboard session.")
                    is TBoxEvent.VideoArea -> Unit
                    is TBoxEvent.Touch -> touchFilter?.onTouch(event)
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
                        if (MotoHubSettings.seamlessResume(this@RideDashboardSessionService)) {
                            requestRecovery("T-Box Wi-Fi re-acquired; resuming Ride Dashboard stream.")
                        }
                    }
                }
            }
        }
    }

    private fun handleTBoxNetworkLost(handle: TBoxSessionHandle) {
        if (!MotoHubSettings.autoRecovery(this)) {
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
                "T-Box Wi-Fi lost; keeping Ride Dashboard parked for " +
                    "${NETWORK_LOSS_GRACE_MILLIS / 1_000L}s while auto-rejoin runs."
            )
            delay(NETWORK_LOSS_GRACE_MILLIS)
            if (!stopping && handle.networkConnector.currentNetwork() == null) {
                requestRecovery(
                    "T-Box Wi-Fi did not return within the grace period; resuming Ride Dashboard recovery."
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
                    "Dashboard touch dropped before T-Box geometry was negotiated: " +
                        "raw=(${event.x},${event.y})."
                )
            }
            return
        }
        val canvas = transform.map(event.x, event.y)
        if (canvas == null) {
            if (event.action != 2) {
                ProjectionEventLog.warning(
                    "TOUCH",
                    "Dashboard touch raw=(${event.x},${event.y}) is outside declared domain " +
                        "${transform.input.width}x${transform.input.height} " +
                        "@(${transform.input.left},${transform.input.top}); input was dropped."
                )
            }
            return
        }
        val source = renderer?.mapTouchToAndroidAuto(canvas.first, canvas.second) ?: return
        embeddedAndroidAuto?.sendSourceTouch(
            action = event.action,
            pointerId = event.pointerId,
            x = source.first,
            y = source.second
        )
    }

    private fun requestRecovery(reason: String) {
        if (stopping) return
        if (encoder == null && renderer == null) {
            fail(reason)
            return
        }
        if (!MotoHubSettings.autoRecovery(this)) {
            fail(reason)
            return
        }
        if (!recoveryRequested.compareAndSet(false, true)) return
        ProjectionEventLog.warning("WATCHDOG", "Ride Dashboard recovery requested: $reason")
        RideDashboardRuntime.publish(RideDashboardRuntimeState.Starting)
        recoveryJob = serviceScope.launch {
            val deadline = SystemClock.elapsedRealtime() + RECOVERY_GIVE_UP_MILLIS
            var attempt = 0
            while (!stopping && SystemClock.elapsedRealtime() < deadline) {
                attempt++
                try {
                    recoverTBoxStream(reason, attempt)
                    recoveryRequested.set(false)
                    transportUnavailable.set(false)
                    RideDashboardRuntime.publish(RideDashboardRuntimeState.Streaming)
                    ProjectionEventLog.record(
                        "WATCHDOG",
                        "Ride Dashboard T-Box recovered on attempt $attempt."
                    )
                    return@launch
                } catch (cancelled: CancellationException) {
                    recoveryRequested.set(false)
                    throw cancelled
                } catch (failure: Throwable) {
                    ProjectionEventLog.warning(
                        "WATCHDOG",
                        "Ride Dashboard recovery attempt $attempt failed: ${failure.message}"
                    )
                    delay(RECOVERY_RETRY_MILLIS)
                }
            }
            recoveryRequested.set(false)
            if (!stopping) {
                fail(
                    "Ride Dashboard recovery timed out after " +
                        "${RECOVERY_GIVE_UP_MILLIS / 1_000L} seconds."
                )
            }
        }
    }

    private suspend fun recoverTBoxStream(reason: String, attempt: Int) {
        val previousHandle = tBoxHandle
            ?: error("No T-Box session is available for Ride Dashboard recovery")
        ProjectionEventLog.record(
            "WATCHDOG",
            "Reconnecting Ride Dashboard EasyConn, attempt=$attempt, reason=$reason."
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
        val host = previousHandle.transport.discover(
            link,
            previousHandle.motorcycle.modelId
        ).getOrThrow()
        val recoveredHandle = previousHandle.copy(host = host, link = link)
        tBoxHandle = recoveredHandle
        TBoxSessionRegistry.install(recoveredHandle)
        observeActiveSession(recoveredHandle)
        recoveredHandle.transport.start(host).getOrThrow()
        if (embeddedAndroidAuto != null) {
            recoveredHandle.networkConnector.releaseProcessBinding()
        }
    }

    private fun fail(message: String) {
        if (stopping) return
        ProjectionEventLog.error("RIDE_DASHBOARD", message)
        RideDashboardRuntime.publish(RideDashboardRuntimeState.Failed(message))
        stopSession(message)
    }

    @Synchronized
   private fun stopSession(reason: String) {
        touchFilter?.close()
        touchFilter = null
        recoveryRequested.set(false)
        recoveryJob?.cancel()
        recoveryJob = null
        networkLossJob?.cancel()
        networkLossJob = null
        if (stopping) return
        stopping = true
        TripRecordingService.stopAuto(this, TripRecordingSource.RIDE_DASHBOARD)
        streamingLocks.release()
        ProjectionEventLog.record(
            "RIDE_DASHBOARD",
            "Stopping Ride Dashboard: reason=$reason, frames=${framesAccepted.get()}."
        )
        transportEventsJob?.cancel()
        transportEventsJob = null
        networkEventsJob?.cancel()
        networkEventsJob = null
        adaptiveJob?.cancel()
        adaptiveJob = null
        weatherJob?.cancel()
        weatherJob = null
        RideDashboardControlBridge.clear(dashboardGestureHandler)
        simulatorHandlebarBridge?.stop()
        simulatorHandlebarBridge = null
        mediaButtonBridge?.stop()
        mediaButtonBridge = null
        pipeline?.stop()
        pipeline = null
        embeddedAndroidAuto = null
        renderer = null
        dashboardLayoutListener?.let { dashboardLayoutStore?.removeListener(it) }
        dashboardLayoutStore = null
        dashboardLayoutListener = null
        screenMarginsListener?.let { screenMarginsStoreForListener?.removeListener(it) }
        screenMarginsStoreForListener = null
        screenMarginsListener = null
        telemetryProvider = null
        encoder = null
        adaptiveVideoController.reset()
        tBoxTouchTransform = null

        val releasedHandle = tBoxHandle ?: TBoxSessionRegistry.current()
        tBoxHandle = null
        if (releasedHandle != null) {
            serviceScope.launch {
                releasedHandle.transport.stop()
                releasedHandle.networkConnector.disconnect()
                TBoxSessionRegistry.clear(releasedHandle)
            }
        }
        if (RideDashboardRuntime.state.value !is RideDashboardRuntimeState.Failed) {
            RideDashboardRuntime.publish(RideDashboardRuntimeState.Stopped(reason))
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun log(message: String) {
        ProjectionEventLog.record("ENCODER", message)
    }

    private fun createNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.ride_dashboard_notification_title))
            .setContentText(getString(R.string.ride_dashboard_notification_text))
            .setOngoing(true)
            .addAction(
                R.drawable.ic_notification,
                getString(R.string.stop_ride_dashboard),
                PendingIntent.getService(
                    this,
                    0,
                    Intent(this, RideDashboardSessionService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()

    /** See [runWeatherUpdateLoop] - bound to the cellular network like the rest of this session, since the T-Box Wi-Fi has no Internet. */
    private fun startWeatherUpdates() {
        val weatherClient = OpenMeteoWeatherClient(this, cellularOnly = true)
        weatherJob = serviceScope.launch {
            runWeatherUpdateLoop(weatherClient) {
                telemetryProvider?.snapshot()?.position?.let { NavPoint(it.latitude, it.longitude) }
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "ride_dashboard_session_v1"
        private const val NOTIFICATION_ID = 4301
        private const val ACTION_STOP = "io.motohub.android.action.STOP_RIDE_DASHBOARD"
        private const val EXTRA_MAP_SOURCE = "ride_dashboard_map_source"
        private const val VIDEO_CONFIGURATION_TIMEOUT_MILLIS = 10_000L
        private const val FRAME_LOG_INTERVAL = 120L
        private const val ADAPTIVE_TICK_MILLIS = 5_000L
        private const val RECOVERY_RETRY_MILLIS = 5_000L
        private const val RECOVERY_GIVE_UP_MILLIS = 120_000L
        private const val NETWORK_LOSS_GRACE_MILLIS = 60_000L
        private const val NETWORK_REJOIN_WAIT_MILLIS = 75_000L

        fun start(
            context: Context,
            mapSource: RideDashboardMapSource = RideDashboardMapSourceStore.load(context)
        ) {
            val intent = Intent(context, RideDashboardSessionService::class.java).apply {
                putExtra(EXTRA_MAP_SOURCE, mapSource.name)
            }
            ContextCompat.startForegroundService(
                context,
                intent
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, RideDashboardSessionService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
