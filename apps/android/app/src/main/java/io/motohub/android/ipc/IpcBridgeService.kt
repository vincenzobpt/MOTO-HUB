// Public, GPL-3.0/AGPL-3.0-licensed bridge: exposes Core's T-Box transport (ridedaemon-lib,
// GPL-3.0) and Android Auto AAP receiver (aa/, AGPL-3.0 technique ported from headunit-revived)
// to another app's process over Binder IPC, so a closed-source companion app can use both
// without linking this code into its own binary. See the "Core/Pro split" note in
// documentation/ARCHITECTURE.md for why this boundary exists.
package io.motohub.android.ipc

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteCallbackList
import android.view.Surface
import io.motohub.android.aa.AaReceiver
import io.motohub.android.aa.SingleKeyKeyManager
import io.motohub.android.androidauto.AaCompositor
import io.motohub.android.androidauto.AndroidAutoCapabilityProfiles
import io.motohub.android.androidauto.AndroidAutoDisplayMode
import io.motohub.android.androidauto.withFullVideoTargetForDashboard
import io.motohub.android.session.ProjectionEventLog
import io.motohub.android.tbox.TBoxSessionHandle
import io.motohub.android.tbox.TBoxSessionRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class IpcBridgeService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── T-Box transport ──────────────────────────────────────────────

    private val sessionListeners = RemoteCallbackList<ITBoxSessionListener>()
    private var sessionPollJob: Job? = null
    @Volatile private var lastKnownHandle: TBoxSessionHandle? = null

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

        override fun offerAccessUnit(accessUnit: ByteArray): Boolean =
            TBoxSessionRegistry.current()?.transport?.offerAccessUnit(accessUnit) ?: false

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
    private var compositor: AaCompositor? = null
    private var receiver: AaReceiver? = null

    private val androidAutoBinder = object : IAndroidAutoReceiverService.Stub() {
        override fun attachOutputSurface(surface: Surface, width: Int, height: Int): Boolean {
            if (receiver != null) {
                publishState(AndroidAutoIpcState.FAILED, "An Android Auto receiver session is already active.")
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
                onSessionEnded = { clean ->
                    publishState(
                        AndroidAutoIpcState.STOPPED,
                        if (clean) "Android Auto ended the session." else "Android Auto connection closed unexpectedly."
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

        // Core's current public AA input channel (io.motohub.android.aa.AaInput) only implements
        // touch. No-ops until a public key/scroll channel exists — see IAndroidAutoReceiverService.aidl.
        override fun sendKey(keycode: Int): Boolean = false
        override fun sendScroll(delta: Int): Boolean = false

        override fun registerStateListener(listener: IAndroidAutoStateListener) {
            stateListeners.register(listener)
        }

        override fun unregisterStateListener(listener: IAndroidAutoStateListener) {
            stateListeners.unregister(listener)
        }
    }

    private fun releaseReceiver() {
        receiver?.stop()
        receiver = null
        compositor?.clearOutput()
        compositor?.release()
        compositor = null
    }

    private fun publishState(state: Int, message: String) {
        ProjectionEventLog.debug("IPC_AA", "state=$state message=$message")
        val count = stateListeners.beginBroadcast()
        for (i in 0 until count) {
            runCatching { stateListeners.getBroadcastItem(i).onStateChanged(state, message) }
        }
        stateListeners.finishBroadcast()
    }

    // ── Service lifecycle ────────────────────────────────────────────

    override fun onBind(intent: Intent): IBinder? = when (intent.action) {
        IpcBridgeContract.BIND_ACTION_TBOX_TRANSPORT -> tboxTransportBinder
        IpcBridgeContract.BIND_ACTION_ANDROID_AUTO_RECEIVER -> androidAutoBinder
        else -> null
    }

    override fun onDestroy() {
        sessionPollJob?.cancel()
        releaseReceiver()
        sessionListeners.kill()
        stateListeners.kill()
        serviceScope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val SESSION_POLL_INTERVAL_MS = 1_000L
    }
}
