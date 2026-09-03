// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// MOTO-HUB receiver glue (uses AGPLv3 code ported from headunit-revived). Orchestrates the loopback
// "self-mode" Android Auto Projection receiver:
//   1. Listen on TCP 127.0.0.1:5288 (+ NSD _aawireless._tcp).
//   2. Launch Google Android Auto's WirelessStartupActivity pointed at 127.0.0.1:5288 (no VPN).
//   3. Accept the inbound socket, run the AAP version+SSL handshake, point the H.264 decoder at
//      the supplied encoder Surface, and start the message loop → AA video flows into the encoder.
package io.motohub.android.aa

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.SystemClock
import android.view.Surface
import io.motohub.android.androidauto.AaInputBridge
import io.motohub.android.androidauto.AndroidAutoCapabilityProfile
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import io.motohub.android.androidauto.AndroidAutoNightModeStore

class AaReceiver(
    private val context: Context,
    private val encoderSurface: Surface,
    private val log: (String) -> Unit,
    private val onVideoReady: () -> Unit,
    private val onSessionEnded: (clean: Boolean, userExit: Boolean) -> Unit,
    private val mapTouchToSource: (Int, Int) -> Pair<Int, Int>?,
    private val capabilityProfile: AndroidAutoCapabilityProfile,
    /**
     * How long whatever consumes [encoderSurface] has spent blocked passing frames onward, so the
     * decoder's stall watchdog can tell a wedged decoder from a jammed pipe behind it.
     *
     * Wired to `AaCompositor.downstreamBlockedMillis` by every caller that owns a compositor.
     */
    private val downstreamBlockedMillis: (() -> Long)? = null,
) {
    companion object {
        const val PORT = 5288

        /**
         * Android Auto's own "head unit server" (its settings ▸ three-dot menu ▸ Start head unit
         * server; NOT inside Developer settings, which only has to be unlocked for the menu),
         * the port the Desktop Head Unit connects to. Here the roles are reversed from self-mode:
         * Android Auto listens and the head unit dials in, so nothing has to ask Android Auto to
         * start — which is the whole point, since 17.4 removed every way of asking.
         */
        const val HEAD_UNIT_SERVER_PORT = 5277
        private const val HEAD_UNIT_SERVER_POLL_MS = 1_500L
        private const val HEAD_UNIT_SERVER_CONNECT_TIMEOUT_MS = 400

        /** How often the decoder's per-second frame rate is summarised into the log. */
        private const val FPS_SUMMARY_WINDOW_MS = 30_000L

        /**
         * Process-wide "Android Auto has opened the local AAP socket" flag. The self-mode trigger
         * needs it to tell a dispatched intent from one that actually reached Gearhead:
         * sendBroadcast and startService report delivery, never whether anything acted on it.
         */
        @Volatile
        private var androidAutoConnectedSinceStart = false

        fun hasAndroidAutoConnectedSinceStart(): Boolean = androidAutoConnectedSinceStart
    }

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var headUnitServerThread: Thread? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    @Volatile private var transport: AapTransport? = null

    /**
     * The single session slot, taken before anything is built and released when the session ends.
     *
     * Both entry points - gearhead dialling in on [PORT] and us dialling out to
     * [HEAD_UNIT_SERVER_PORT] - used to test `transport != null` and then call handleConnection,
     * which assigns `transport` several object constructions later (one of them a SharedPreferences
     * read that hits disk the first time). @Volatile makes that read visible; it does not make
     * check-then-act atomic, and on Android Auto releases where the self-mode pokes still land a
     * rider who has also started the head unit server has both paths live at once. Two sessions
     * through that window means two SSL handshakes, and the loser is left with no reference - so
     * `stop()` never reaches it, and its `onQuit` tears down the session that won.
     */
    private val sessionSlot = AtomicBoolean(false)

    /** True only for the caller that took the slot; the loser must close its own socket. */
    private fun claimSession(): Boolean = sessionSlot.compareAndSet(false, true)

    private fun releaseSession() { sessionSlot.set(false) }

    /**
     * Runs a claimed session, making sure a failure on the way up gives the slot back.
     *
     * [handleConnection] returns with the session still *running*, so the slot cannot simply be
     * freed in a `finally` - only the two places that know the session is over may release it. But
     * everything before `startReading()` can throw, and an exception escaping there would leave the
     * slot taken with nothing to release it: the receiver would then refuse every later connection
     * for the rest of the ride. On the accept thread it would also reach Android's default handler
     * and take the process down mid-ride, the same way the head unit server poller guards against.
     */
    private fun runSession(socket: Socket) {
        try {
            handleConnection(socket)
        } catch (failure: Exception) {
            log("[AA] session failed to start: ${failure.message}")
            try { socket.close() } catch (_: Exception) {}
            releaseSession()
        }
    }
    @Volatile private var connection: SocketAccessoryConnection? = null
    @Volatile private var videoReadyFired = false
    /**
     * Whether Google Android Auto ever opened the local AAP socket. False means the self-mode
     * trigger never reached it — a different failure from "connected but sent no video", and the
     * one to report when a newer Android Auto refuses every startup entry point.
     */
    @Volatile private var androidAutoConnected = false

    /** Rate-limits the process-pin diagnosis to one line per receiver, not one per poll. */
    @Volatile private var headUnitPinLogged = false
    val hasAndroidAutoConnected: Boolean get() = androidAutoConnected

    /**
     * True while an AAP session is actually running, as opposed to merely having been accepted
     * once. What the session service waits on when it holds a dropped session open instead of
     * tearing it down - see AndroidAutoSessionService.handleAndroidAutoDrop.
     */
    val hasLiveSession: Boolean get() = transport != null
    @Volatile private var input: AaInput? = null
    private val videoDecoder = VideoDecoder().apply {
        fallbackWidth = capabilityProfile.video.width
        fallbackHeight = capabilityProfile.video.height
        downstreamBlockedMillis = this@AaReceiver.downstreamBlockedMillis
        onFirstFrameListener = {
            if (!videoReadyFired) {
                videoReadyFired = true
                log("[AA] first decoded video frame received — signalling ready for bike hand-off")
                try { onVideoReady() } catch (failure: Exception) {
                    log("[AA] bike hand-off callback failed: ${failure.message}")
                }
            }
        }
        onFpsChanged = { fps ->
            recordDecodeFps(fps)
        }
    }

    // One decoder-reported frame rate per second, folded into a window instead of a log line.
    private var fpsWindowStartedAtMs = 0L
    private var fpsSampleCount = 0
    private var fpsSampleTotal = 0
    private var fpsSampleMin = Int.MAX_VALUE
    private var fpsSampleMax = 0

    /**
     * The decoder reports its frame rate once a second. Logging every sample cost one line per
     * second of Android Auto, which is enough to evict the whole 800-entry diagnostic ring buffer
     * in about thirteen minutes: a rider's exported CORE log was 762 entries of `decode fps=` and
     * nothing about the connection that had failed before it. A summary every
     * [FPS_SUMMARY_WINDOW_MS] keeps what the line is actually read for - a stalling decoder shows
     * up as a low minimum - at a thirtieth of the volume.
     */
    private fun recordDecodeFps(fps: Int) {
        val now = SystemClock.elapsedRealtime()
        fpsSampleCount++
        fpsSampleTotal += fps
        if (fps < fpsSampleMin) fpsSampleMin = fps
        if (fps > fpsSampleMax) fpsSampleMax = fps
        if (fpsWindowStartedAtMs == 0L) {
            // The first sample goes out immediately: "video is decoding at all" is the answer
            // wanted right after the hand-off, not thirty seconds later.
            fpsWindowStartedAtMs = now
            log("[AA] decode fps=$fps")
            resetFpsWindow(now)
            return
        }
        if (now - fpsWindowStartedAtMs < FPS_SUMMARY_WINDOW_MS) return
        val seconds = (now - fpsWindowStartedAtMs) / 1000
        log(
            "[AA] decode fps over ${seconds}s: avg=${fpsSampleTotal / fpsSampleCount}, " +
                "min=$fpsSampleMin, max=$fpsSampleMax"
        )
        resetFpsWindow(now)
    }

    private fun resetFpsWindow(now: Long) {
        fpsWindowStartedAtMs = now
        fpsSampleCount = 0
        fpsSampleTotal = 0
        fpsSampleMin = Int.MAX_VALUE
        fpsSampleMax = 0
    }

    /** Ensure Conscrypt/AAP logging are wired before anything touches SSL. */
    fun start(): Boolean {
        if (running) { log("[AA] already running"); return true }
        if (!SingleKeyKeyManager.isAvailable(context)) {
            log("[AA] Android Auto identity is not included in this build")
            return false
        }
        running = true
        androidAutoConnected = false
        androidAutoConnectedSinceStart = false
        AaLog.sink = log
        ConscryptInitializer.initialize()

        try {
            serverSocket = bindWirelessServerSocket()
            log("[AA] WirelessServer listening on :$PORT")
        } catch (e: Exception) {
            log("[AA] failed to bind :$PORT — ${e.message}")
            running = false
            return false
        }

        registerNsd()

        acceptThread = thread(name = "aa-accept", isDaemon = true) { acceptLoop() }
        headUnitServerThread = thread(name = "aa-hu-server", isDaemon = true) {
            // A fallback poller must never take the process down: anything escaping this thread
            // reaches Android's default handler, which kills the app while the rider is riding.
            try { headUnitServerLoop() } catch (failure: Exception) {
                log("[AA] head unit server poll ended: ${failure.message}")
            }
        }
        // Self-mode (launching Google Android Auto) is triggered by MainActivity from the
        // foreground, via AaSelfMode.trigger(), to satisfy background-activity-launch rules.
        return true
    }

    /**
     * Binds the loopback listener, clearing a stale process→network pin if that is what stops it.
     *
     * The process can still be bound to a T-Box network that has just died: Android delivers
     * `onLost` seconds after the AP is gone, and the connector cannot release the pin before it
     * hears about the loss. Until then every socket this process creates fails with ENONET —
     * including loopback listeners that never route through that network — so a phone-only or
     * AIDL-preview session started in that window reported "port unavailable" for a port nobody
     * held. A live pinned network binds loopback fine, which makes the diagnosis safe: this
     * failure shape proves the pin is dead weight, so drop it and bind again. The connector's own
     * bookkeeping is not consulted or updated — its `onLost` handler unbinds a second time,
     * harmlessly, when the verdict finally arrives.
     */
    private fun bindWirelessServerSocket(): ServerSocket = try {
        openWirelessServerSocket()
    } catch (failure: Exception) {
        if (!isStaleNetworkPinFailure(failure)) throw failure
        log(
            "[AA] bind :$PORT failed on a stale network binding (${failure.message}); " +
                "clearing the process binding and retrying"
        )
        val cleared = runCatching {
            context.getSystemService(ConnectivityManager::class.java)
                .bindProcessToNetwork(null)
        }.getOrDefault(false)
        if (!cleared) throw failure
        openWirelessServerSocket()
    }

    /**
     * SO_REUSEADDR only has any effect if it is set *before* the bind, which `ServerSocket(PORT)`
     * (which binds in its constructor) makes impossible - so the previous `.apply { reuseAddress
     * = true }` was setting the flag on an already-bound socket and doing nothing. Without it a
     * still-draining connection from a previous session can keep the port unbindable for a while
     * after the old listener is gone.
     */
    private fun openWirelessServerSocket(): ServerSocket {
        val socket = ServerSocket()
        return try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(PORT))
            socket
        } catch (failure: Exception) {
            runCatching { socket.close() }
            throw failure
        }
    }

    fun stop() {
        running = false
        AaInputBridge.clear(input)
        input = null
        try { transport?.stop() } catch (_: Exception) {
            try { transport?.quit() } catch (_: Exception) {}
        }
        transport = null
        try { connection?.disconnect() } catch (_: Exception) {}
        connection = null
        try { videoDecoder.stop("AaReceiver.stop") } catch (_: Exception) {}
        unregisterNsd()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptThread?.interrupt(); acceptThread = null
        headUnitServerThread?.interrupt(); headUnitServerThread = null
        releaseSession()
        // The nav app can no longer retract its last turn once the session is gone.
        AaNavigationGuidance.clear()
        AaLog.sink = null
        log("[AA] receiver stopped")
    }

    /**
     * Dials Android Auto's head unit server while nothing has connected yet.
     *
     * Self-mode asks Android Auto to connect to us; 17.4 closed every entry point for that. The
     * head unit server is the mirror image — the rider starts it from Android Auto's own
     * developer menu and Android Auto listens, so a plain outbound socket is all that is needed
     * and no permission or exported component is involved. Everything after the socket is the
     * same AAP session, so this hands over to [handleConnection] unchanged.
     */
    private fun headUnitServerLoop() {
        var announced = false
        while (running) {
            if (transport != null) {
                if (!awaitNextPoll()) return
                continue
            }
            val socket = try {
                connectHeadUnitServer()
            } catch (_: Exception) {
                // Not running: this is the normal state until the rider starts it.
                if (!announced) {
                    announced = true
                    log(
                        "[AA] Android Auto's head unit server is not running on " +
                            ":$HEAD_UNIT_SERVER_PORT; polling for it as a fallback."
                    )
                }
                if (!awaitNextPoll()) return
                continue
            }
            if (!running || !claimSession()) {
                try { socket.close() } catch (_: Exception) {}
                return
            }
            log("[AA] <<< connected to Android Auto's head unit server on :$HEAD_UNIT_SERVER_PORT")
            androidAutoConnected = true
            androidAutoConnectedSinceStart = true
            runSession(socket)
            // Deliberately NOT a return. A session that ends - Android Auto restarting, the phone
            // going to sleep, the bike's Wi-Fi taking the process route down with it - leaves this
            // receiver running with `transport` back to null, and on a release that exports no
            // startup component this poller is the ONLY way back in: 17.4 answers "no head unit
            // server component" to every discovery there is. Returning here is why a rider's
            // Android Auto never came back after the second drop of a ride, with the receiver
            // still listening and nothing left to dial it (field log 2026-09-02, from 15:27:12).
            //
            // `announced` is reset with the session so the next dry spell is reported once more:
            // "the server is not running" is a different fact each time the rider stops it.
            announced = false
            if (!awaitNextPoll()) return
        }
    }

    /**
     * Waits one poll interval, reporting whether the wait completed. [stop] interrupts this
     * thread, so an interrupt is the normal way the loop ends — and an InterruptedException left
     * to escape it would reach Android's default handler and kill the process.
     */
    private fun awaitNextPoll(): Boolean = try {
        Thread.sleep(HEAD_UNIT_SERVER_POLL_MS)
        true
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    /**
     * Connects to Android Auto's head unit server, stepping over a process→network pin if that is
     * what is in the way.
     *
     * A process pinned to the bike network resolves 127.0.0.1 through that network's routing
     * table, which carries no loopback route: the connect fails "network is unreachable" while
     * the server is listening perfectly well, and [headUnitServerLoop] reports it as "not
     * running" - the wrong diagnosis, and a remedy the rider has already applied.
     *
     * The Core session never meets this, because [AndroidAutoSessionService] releases the pin
     * before it starts the receiver and does not restore it until the hand-off. The receiver
     * ADVANCED attaches over AIDL has no such release: it starts while the bike link is up, so
     * this is the path the retry is here for.
     *
     * The pin is dropped for the retry only - never on the first attempt, and never on a refused
     * connection, which is the ordinary "the rider has not started the server yet" answer. Dash
     * traffic does not ride on the process route (`TBoxLink.createSocket` binds every socket to
     * the network itself) and the connector already treats an absent pin as a supported state
     * ([TBoxNetworkConnector.releaseProcessBinding]), so the few hundred microseconds this takes
     * cost the bike link nothing.
     */
    private fun connectHeadUnitServer(): Socket = try {
        openHeadUnitServerSocket()
    } catch (failure: Exception) {
        if (!isStaleNetworkPinFailure(failure)) throw failure
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: throw failure
        val pinned = runCatching { cm.boundNetworkForProcess }.getOrNull() ?: throw failure
        if (!headUnitPinLogged) {
            headUnitPinLogged = true
            log(
                "[AA] head unit server unreachable behind the process→network pin ($pinned), not " +
                    "absent; dropping the pin for the connect and restoring it straight after"
            )
        }
        try {
            if (!runCatching { cm.bindProcessToNetwork(null) }.getOrDefault(false)) throw failure
            openHeadUnitServerSocket()
        } finally {
            // Restore only what we took away. If the connector re-pinned the process while we
            // were connecting, that binding is the current one and overwriting it would undo a
            // decision this class knows nothing about.
            if (runCatching { cm.boundNetworkForProcess }.getOrNull() == null) {
                runCatching { cm.bindProcessToNetwork(pinned) }
            }
        }
    }

    /**
     * A connect that closes its socket when it fails. `Socket().apply { connect(…) }` leaks the
     * descriptor on every unsuccessful attempt, and this one runs every [HEAD_UNIT_SERVER_POLL_MS]
     * for as long as Android Auto has not attached.
     */
    private fun openHeadUnitServerSocket(): Socket {
        val socket = Socket()
        return try {
            socket.connect(
                InetSocketAddress("127.0.0.1", HEAD_UNIT_SERVER_PORT),
                HEAD_UNIT_SERVER_CONNECT_TIMEOUT_MS
            )
            socket
        } catch (failure: Exception) {
            runCatching { socket.close() }
            throw failure
        }
    }

    private fun acceptLoop() {
        val ss = serverSocket ?: return
        while (running) {
            val client = try {
                ss.accept()
            } catch (e: Exception) {
                if (running) log("[AA] accept ended: ${e.message}")
                break
            }
            androidAutoConnected = true
            androidAutoConnectedSinceStart = true
            log("[AA] <<< Android Auto connected from ${client.inetAddress?.hostAddress}")
            if (!claimSession()) {
                log("[AA] already have a session — dropping extra connection")
                try { client.close() } catch (_: Exception) {}
                continue
            }
            thread(name = "aa-session", isDaemon = true) { runSession(client) }
        }
    }

    private fun handleConnection(client: Socket) {
        val conn = SocketAccessoryConnection(client)
        connection = conn
        val t = AapTransport(
            videoDecoder = videoDecoder,
            context = context,
            androidAutoCapabilityProfile = capabilityProfile
        )
        t.nightMode = AndroidAutoNightModeStore(context).load()
        t.onQuit = { clean ->
            val userExit = t.wasUserExit
            log("[AA] transport quit (clean=$clean, userExit=$userExit)")
            AaInputBridge.clear(input)
            input = null
            transport = null
            try { conn.disconnect() } catch (_: Exception) {}
            connection = null
            releaseSession()
            onSessionEnded(clean, userExit)
        }
       transport = t
        t.microphone = AaMicrophone(context, t, log)

        // Bike touchscreen → Android Auto: EasyConnProber decodes dash touches (PXC cmdType 32) and
        // calls this sink with raw bike-canvas coords + a normalised action. Letterbox-map into AA
        // video space and forward over the AAP INPUT channel. Dropped if the point is in a black bar.
        input = AaInput(t, log)

        log("[AA] starting AAP handshake (version + SSL)…")
        if (!t.startHandshake(conn)) {
            log("[AA] handshake FAILED")
            AaInputBridge.clear(input)
            input = null
            transport = null
            try { conn.disconnect() } catch (_: Exception) {}
            connection = null
            // Nothing else will: onQuit only fires for a transport that started.
            releaseSession()
            return
        }
        AaInputBridge.install(checkNotNull(input))
        log("[AA] handshake OK — pointing decoder at encoder surface and starting read loop")
        videoDecoder.setSurface(encoderSurface)
        t.startReading()
        log("[AA] read loop started — expecting ServiceDiscovery then video")
    }

    /**
     * Takes **output-canvas** pixels - a TFT touch, or a phone-only preview whose SurfaceView is
     * itself the compositor's output - and maps them to Android Auto's UI surface on the way in.
     *
     * Coordinates that have already been mapped belong in [sendSourceTouch]. Handing them here
     * instead applies the canvas mapping twice, which is not a rounding error: it scales by
     * canvasWidth/sourceWidth about the origin, so taps land correctly in the corner and drift
     * further out the further the rider presses from it.
     */
    fun sendTouch(action: Int, pointerId: Int, canvasX: Int, canvasY: Int) {
        val activeInput = input ?: return
        val mapped = mapTouchToSource(canvasX, canvasY) ?: return
        if (action != AaInput.ACTION_MOVE) {
            log("[AA] touch action=$action p$pointerId canvas=($canvasX,$canvasY) → AA=(${mapped.first},${mapped.second})")
        }
        activeInput.sendTouch(action, pointerId, mapped.first, mapped.second)
    }

    fun sendTouch(action: Int, canvasX: Int, canvasY: Int) = sendTouch(action, 0, canvasX, canvasY)

    /** Takes coordinates already in Android Auto's UI space and forwards them untouched. */
    fun sendSourceTouch(action: Int, pointerId: Int, sourceX: Int, sourceY: Int) {
        val activeInput = input ?: return
        activeInput.sendTouch(action, pointerId, sourceX, sourceY)
    }

    fun sendSourceTouch(action: Int, sourceX: Int, sourceY: Int) =
        sendSourceTouch(action, 0, sourceX, sourceY)

    fun setNightMode(isNight: Boolean): Boolean {
        val activeTransport = transport ?: return false
        activeTransport.sendNightMode(isNight)
        return true
    }

    private fun registerNsd() {
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            if (nsdManager == null) { log("[AA] NSD unavailable"); return }
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "AAWireless"
                serviceType = "_aawireless._tcp"
                port = PORT
            }
            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) = log("[AA] NSD registered: ${info.serviceName}")
                override fun onRegistrationFailed(info: NsdServiceInfo, err: Int) = log("[AA] NSD reg fail: $err")
                override fun onServiceUnregistered(info: NsdServiceInfo) = log("[AA] NSD unregistered")
                override fun onUnregistrationFailed(info: NsdServiceInfo, err: Int) = log("[AA] NSD unreg fail: $err")
            }
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            log("[AA] NSD register error: ${e.message}")
        }
    }

    private fun unregisterNsd() {
        try { registrationListener?.let { nsdManager?.unregisterService(it) } } catch (_: Exception) {}
        registrationListener = null
    }
}

/** ENONET / unreachable from a socket that never leaves the phone: the pin, not the port. */
internal fun isStaleNetworkPinFailure(failure: Throwable): Boolean =
    generateSequence(failure) { it.cause }
        .mapNotNull(Throwable::message)
        .any { message ->
            message.contains("ENONET", ignoreCase = true) ||
                message.contains("network is unreachable", ignoreCase = true)
        }
