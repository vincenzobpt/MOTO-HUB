// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
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
import io.motohub.android.encoding.EncoderProfile
import io.motohub.android.encoding.JpegDisplaySource
import io.motohub.android.encoding.VideoDeliveryProbe
import io.motohub.android.encoding.VideoBackpressureGuard
import io.motohub.android.androidauto.DisplayGeometry
import io.motohub.android.androidauto.TBoxDisplayGeometryStore
import io.motohub.android.feature.settings.MotoHubSettings
import io.motohub.android.tbox.TBoxEvent
import io.motohub.android.tbox.TBoxLinkResolver
import io.motohub.android.tbox.TBoxModelProfile
import io.motohub.android.tbox.TBoxWireLadder
import io.motohub.android.tbox.TBoxTransportFamily
import io.motohub.android.tbox.ProfileOverride
import io.motohub.android.tbox.TBoxCapabilityStore
import io.motohub.android.tbox.TBoxNetworkEvent
import io.motohub.android.tbox.SelectingTBoxTransport
import io.motohub.android.tbox.TBoxSessionHandle
import io.motohub.android.tbox.tBoxFailureOwnedByHandshake
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
    /** Set only on the JPEG-stills path; null for every dash that receives video. */
    private var jpegSource: JpegDisplaySource? = null
    private val adaptiveVideoController = AdaptiveVideoController(this, ::log)
    private var adaptiveJob: Job? = null
    private val streamingLocks = TBoxStreamingLocks(this, "Mirroring")
    private var tBoxHandle: TBoxSessionHandle? = null
    private var transportEventsJob: Job? = null
    private var networkEventsJob: Job? = null
    private var p2pGroupWatcher: AutoCloseable? = null
    private var handleCleanupJob: Job? = null
    private var recoveryJob: Job? = null
    private val recoveryRequested = AtomicBoolean(false)
    /**
     * True while the EasyConn handshake below is running.
     *
     * Same window as Android Auto's and the Ride Dashboard's: observeActiveSession() installs the
     * transport-event collector before the first handshake and the encoder is built after it, so a
     * Stopped/FatalError produced BY that handshake reached handleRecoverableFailure - which with
     * auto-recovery off calls fail() and beats this mode's own, far more useful diagnosis to the
     * log, and with it on starts a whole recovery loop against a session that never opened.
     * See [tBoxFailureOwnedByHandshake].
     */
    private val handshakeInFlight = AtomicBoolean(false)
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
    /** Whether this dash is taking the stream at all, as opposed to whether the link is alive. */
    private var deliveryProbe = VideoDeliveryProbe()
    private val videoStreamStartRequested = AtomicBoolean(false)
    /**
     * Guards [startCapture] against duplicate concurrent starts. [mediaProjection] is
     * only assigned near the end of [startCapture] (after the T-Box handshake, which can
     * take up to [VIDEO_AREA_TIMEOUT_MS]), so it cannot be used as the reentrancy
     * guard: a second `onStartCommand` before the first finishes would otherwise pass the
     * old "mediaProjection != null" check and race a second encoder/VirtualDisplay into
     * existence, leaking the first one.
     */
    private val capturing = AtomicBoolean(false)
    private val framesAccepted = AtomicLong(0)
    private val frameLogThrottle = FrameLogThrottle()
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
        // Deliberately not named after a family: this runs before the session handle is in hand,
        // so the only honest word here is the neutral one. "EasyConn" was wrong on every KOVE.
        ProjectionEventLog.record("SERVICE", "Starting the dashboard handshake.")

        val handle = TBoxSessionRegistry.current()
            ?: return fail("No T-Box session is ready. Reconnect the motorcycle before sharing.")
        tBoxHandle = handle
        TBoxSessionRegistry.claim(SESSION_CONSUMER)
        observeActiveSession(handle)
        val geometryStore = TBoxDisplayGeometryStore(this)
        val savedArea = geometryStore.load(handle.motorcycle.ssid)?.let { geometry ->
            TBoxEvent.VideoArea(geometry.width, geometry.height)
        }
        val fallbackArea = TBoxModelProfile.fallbackVideoArea(
            handle.motorcycle.modelId,
            capabilityStore.load(handle.motorcycle)?.capabilities,
            ProfileOverride.byKey(handle.motorcycle.profileOverrideKey)
        )
        // The transport reports a failed handshake twice - as the return value here and as a
        // Stopped/FatalError event - and the event handler must not race this block. See
        // onTBoxFailureEvent.
        handshakeInFlight.set(true)
        val configurationResult = try {
            handle.transport.negotiateVideoConfiguration(
                host = handle.host,
                savedArea = savedArea,
                fallbackArea = fallbackArea,
                videoAreaTimeoutMillis = VIDEO_AREA_TIMEOUT_MS
            )
        } finally {
            handshakeInFlight.set(false)
        }
        configurationResult.exceptionOrNull()?.let {
            return fail("T-Box video negotiation failed: ${it.message}")
        }
        val configuration = configurationResult.getOrThrow()
        val area = configuration.rawArea
        val quality = MotoHubSettings.videoQuality(this)
        // The transport's own profile wins when discovery changed it - a dash that answered Yunmo
        // after EasyConn found nothing is not the profile the saved motorcycle resolves to.
        val modelProfile = handle.transport.activeProtocolProfile ?: TBoxModelProfile.resolve(
            handle.motorcycle.modelId,
            capabilityStore.load(handle.motorcycle)?.capabilities,
            ProfileOverride.byKey(handle.motorcycle.profileOverrideKey)
        )
        // What this dash is actually being sent. Identical to the profile's own fields for every
        // recognised dash; for an unidentified one it is whichever rung TBoxWireLadder has reached.
        val sessionWire = TBoxWireLadder.configFor(applicationContext, handle.motorcycle, modelProfile)
        val profile = configuration.encoderProfile.copy(
            frameRate = modelProfile.encoderFrameRate ?: configuration.encoderProfile.frameRate,
            // The profile's rate, where it has one, is the base the rider's quality setting scales -
            // same as the negotiated one, so the setting keeps working on these dashes too.
            bitRate = quality.bitrateFor(
                modelProfile.encoderBitRate ?: configuration.encoderProfile.bitRate
            ),
            keyframeIntervalSeconds = sessionWire.encoderKeyframeIntervalSeconds,
            // Yunmo's split framing needs real keyframes to split; intra refresh would make them
            // rare. A profile can also demand plain IDRs for its decoder's sake (KOVE froze on
            // intra refresh); every other EasyConn dash keeps intra refresh.
            plainGopWithoutIntraRefresh =
                sessionWire.encoderPlainGopWithoutIntraRefresh ||
                    modelProfile.transportFamily == TBoxTransportFamily.YUNMO,
            // ThinkerRide's video header declares the exact stream size; encode precisely that
            // instead of the 16-aligned canvas, like the reference app does.
            width = if (modelProfile.encoderUsesExactVideoArea) {
                configuration.rawArea.width
            } else {
                configuration.encoderProfile.width
            },
            height = if (modelProfile.encoderUsesExactVideoArea) {
                configuration.rawArea.height
            } else {
                configuration.encoderProfile.height
            }
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
            // A null handler makes MediaProjection build one on the calling thread, and this
            // runs on a coroutine dispatcher with no Looper - which threw before the capture
            // ever started. The callback only reports that projection stopped, so the main
            // thread is the right place to receive it.
            projection.registerCallback(projectionCallback, mainHandler)
            backpressureGuard = VideoBackpressureGuard()
            deliveryProbe = VideoDeliveryProbe()
            DashboardDeliveryMonitor.clear()

            // The one dash that is fed stills instead of video. Everything below this block - the
            // encoder, its surface, the adaptive controller - is the path every other motorcycle
            // takes, unchanged; this branch returns before reaching any of it. `encoder` stays null,
            // which every later call site already tolerates (`encoder?.`, and the adaptive
            // controller's onTick returns immediately on a null encoder).
            if (modelProfile.usesJpegStills) {
                startJpegCapture(projection, profile, modelProfile, handle)
                return
            }

            val activeEncoder = AvcEncoder(
                profile = profile,
                onAccessUnit = { accessUnit ->
                    if (handle.transport.offerAccessUnit(accessUnit)) {
                        backpressureGuard.onAccepted()
                        deliveryProbe.onAccepted()?.let { publishDeliveryVerdict(it, handle, modelProfile) }
                        val accepted = framesAccepted.incrementAndGet()
                        frameLogThrottle.rateSuffixIfDue(accepted, SystemClock.elapsedRealtime())
                            ?.let { rate ->
                                ProjectionEventLog.record(
                                    "ENCODER",
                                    "Frames sent to T-Box: $accepted$rate."
                                )
                            }
                        true
                    } else {
                        // A single rejection is a pushFrame() overlap, not a dead link - only a
                        // sustained streak ends the session (see VideoBackpressureGuard).
                        val fatal = backpressureGuard.onRejected()
                        deliveryProbe.onRejected()?.let {
                            publishDeliveryVerdict(it, handle, modelProfile)
                        }
                        if (backpressureGuard.isStreakStart()) {
                            ProjectionEventLog.warning(
                                "ENCODER",
                                "The T-Box rejected an AVC frame; holding the session open while " +
                                    "the transport recovers. Rejected so far: " +
                                    "${backpressureGuard.totalRejections()}."
                            )
                        }
                        if (fatal && transportUnavailable.compareAndSet(false, true)) {
                            val streak = backpressureGuard.rejectionStreak()
                            val streakMillis = backpressureGuard.streakMillis()
                            serviceScope.launch {
                                if (!stopping) {
                                    fail(
                                        "The T-Box session no longer accepts video frames " +
                                            "($streak in a row over ${streakMillis}ms)."
                                    )
                                }
                            }
                        }
                        false
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
                modelProfile.virtualDisplayDpi ?: resources.displayMetrics.densityDpi,
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
                    adaptiveVideoController.onTick(
                        encoder = encoder,
                        linkDown = transportUnavailable.get() || recoveryRequested.get()
                    )
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
    /**
     * Routes a transport failure event, unless the handshake that produced it is still running and
     * about to report the same failure itself.
     */
    private fun onTBoxFailureEvent(message: String) {
        val ownedByHandshake =
            tBoxFailureOwnedByHandshake(handshakeInFlight.get(), "mirroring", message)
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
        p2pGroupWatcher?.close()
        p2pGroupWatcher = null
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
        val host = previousHandle.transport.discover(link, previousHandle.motorcycle.modelId).getOrThrow()
        val recoveredHandle = previousHandle.copy(host = host, link = link)
        tBoxHandle = recoveredHandle
        TBoxSessionRegistry.claim(SESSION_CONSUMER)
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
                    is TBoxEvent.FatalError -> onTBoxFailureEvent("T-Box error: ${event.message}")
                    TBoxEvent.Stopped -> onTBoxFailureEvent("The T-Box ended the session.")
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
        // A Wi-Fi Direct group has no ConnectivityManager network, so the Lost event above never
        // fires for it; watch the P2P broadcasts and recover through the same retry budget as a
        // transport error instead of waiting for the frame watchdog.
        p2pGroupWatcher?.close()
        p2pGroupWatcher = (handle.link as? io.motohub.android.tbox.TBoxLink.WifiDirect)?.watchGroupLost {
            if (!stopping) {
                serviceScope.launch {
                    handleRecoverableFailure("The Wi-Fi Direct group with the dash was lost.")
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
        p2pGroupWatcher?.close()
        p2pGroupWatcher = null
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
        jpegSource?.stop()
        jpegSource = null
        adaptiveVideoController.reset()
        mediaProjection?.unregisterCallback(projectionCallback)
        if (stopProjection) mediaProjection?.stop()
        mediaProjection = null

        // Only release the handle this mode owns: the old `?: current()` fallback let a mode
        // that never started tear down whatever session happened to be active.
        val releasedHandle = tBoxHandle
        tBoxHandle = null
        if (releasedHandle != null) {
            handleCleanupJob = serviceScope.launch {
                // Another mode may still be streaming on this session.
                if (TBoxSessionRegistry.releaseAndClear(SESSION_CONSUMER, releasedHandle)) {
                    releasedHandle.transport.stop()
                    // The network itself is the registry's to drop: clear() released the
                    // session's lease on the shared connector, which disconnects only when
                    // no other owner (the Hub UI, the AIDL bridge) still needs it.
                }
            }
        } else {
            TBoxSessionRegistry.release(SESSION_CONSUMER)
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
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(key, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") getParcelableExtra(key) as? Intent
        }

    companion object {
        private const val SESSION_CONSUMER = "mirroring"
        // New id prevents Android from retaining the silent channel created by older builds.
        private const val CHANNEL_ID = "projection_session_v2"
        private const val TAG = "ProjectionSession"
        private const val NOTIFICATION_ID = 4101
        private const val ACTION_STOP = "io.motohub.android.action.STOP_PROJECTION"
        private const val ACTION_DIM_DISPLAY = "io.motohub.android.action.DIM_DISPLAY"
        private const val ACTION_RESTORE_DISPLAY = "io.motohub.android.action.RESTORE_DISPLAY"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val VIDEO_AREA_TIMEOUT_MS = 10_000L
        private const val AUTO_DIM_DELAY_MS = 5_000L
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

    /**
     * Captures the display as JPEG stills for a profile that asks for them, and hands each one to
     * the Yunmo transport.
     *
     * Split out rather than inlined so the encoder path above reads exactly as it did before. The
     * two share only the media projection and the virtual-display call; there is no encoder here,
     * no bitrate to adapt and no access units, so none of the surrounding machinery applies.
     */
    private fun startJpegCapture(
        projection: android.media.projection.MediaProjection,
        profile: EncoderProfile,
        modelProfile: TBoxModelProfile,
        handle: TBoxSessionHandle
    ) {
        val transport = handle.transport
        val source = JpegDisplaySource(
            width = profile.width,
            height = profile.height,
            frameRate = profile.frameRate,
            onFrame = { jpeg, frameId ->
                val accepted = (transport as? SelectingTBoxTransport)
                    ?.offerJpegFrame(jpeg, frameId) ?: false
                if (accepted) {
                    val sent = framesAccepted.incrementAndGet()
                    frameLogThrottle.rateSuffixIfDue(sent, SystemClock.elapsedRealtime())
                        ?.let { rate ->
                            ProjectionEventLog.record(
                                "JPEG",
                                "Stills sent to the dashboard: $sent$rate."
                            )
                        }
                }
                accepted
            },
            onFailure = { failure ->
                serviceScope.launch { if (!stopping) fail("JPEG capture stopped: ${failure.message}") }
            }
        )
        source.start()
        val surface = source.surface ?: error("JPEG capture has no surface")
        jpegSource = source
        mediaProjection = projection
        virtualDisplay = projection.createVirtualDisplay(
            "MOTO-HUB capture",
            profile.width,
            profile.height,
            modelProfile.virtualDisplayDpi ?: resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null
        ) ?: error("Virtual display was not created")
        ProjectionRuntime.publish(ProjectionRuntimeState.Streaming)
        ProjectionEventLog.record(
            "SERVICE",
            "Android capture and streaming are active (JPEG stills, ${profile.width}x${profile.height})."
        )
        scheduleAutoDim()
    }
}
