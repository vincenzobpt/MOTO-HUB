// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import io.motohub.android.session.ProjectionEventLog
import java.io.IOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ThinkerRide projection transport (KOVE-family dashboards). The roles are the inverse of
 * EasyConn: after [ThinkerRideBleLink] completes the BLE handshake, the *dash* connects to three
 * TCP servers the phone opens on the shared Wi-Fi network — control (17818), keep-alive (15457)
 * and video (15456). See [ThinkerRideProtocol] for the wire format and refs/KoveMirror for the
 * decoded reference implementation.
 *
 * The [TBoxTransport] lifecycle maps as: [discover] = bind the servers, pair over BLE and wait
 * for the dash's control connection (its "I can reach your phone" proof); [start] = tell the
 * dash over BLE to begin mirroring and wait for its video connection. The dash never reports a
 * panel size, so [start] emits the configured profile's geometry as the session's video area —
 * that is what makes other ThinkerRide models with other TFT resolutions a profile entry, not a
 * code change.
 *
 * A re-[discover] while the session is still healthy reuses it instead of tearing it down
 * (KoveMirror 9163284 keeps its TCP listeners alive across retries for the same reason): the
 * dash opens the video connection up to ~25s after mirror-start, and destroying the servers on
 * a retry leaves it mirroring into a socket nobody owns — a black TFT until power-cycle. The
 * video accept path is late-tolerant for the same scenario: a connection that arrives after
 * [start] gave up, or a dash that drops and reopens the channel, is wired straight into the
 * running session.
 */
class ThinkerRideTransport(context: Context) : TBoxTransport {

    private val appContext = context.applicationContext
    private val mutableEvents = MutableSharedFlow<TBoxEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val events: Flow<TBoxEvent> = mutableEvents.asSharedFlow()

    @Volatile
    private var protocolProfile: TBoxModelProfile? = null

    @Volatile
    private var session: Session? = null
    private val sessionLock = Any()

    override fun configureProtocolProfile(
        profile: TBoxModelProfile,
        motorcycle: io.motohub.android.session.MotorcycleProfile?
    ) {
        protocolProfile = profile
    }

    override suspend fun discover(link: TBoxLink, expectedModelId: String?): Result<TBoxHost> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!ThinkerRideGate.hasBlePermissions(appContext)) {
                    error(ThinkerRideGate.missingPermissionMessage("MOTO-HUB"))
                }
                reusableSession(link)?.let { alive ->
                    ProjectionEventLog.record(
                        "THINKERRIDE",
                        "Re-discovery found the dash session still healthy; reusing it instead " +
                            "of rescanning (servers and BLE link stay up)."
                    )
                    return@runCatching TBoxHost(
                        ipAddress = alive.localAddress(),
                        port = ThinkerRideProtocol.VIDEO_PORT,
                        packageName = ThinkerRideProtocol.PACKAGE_TAG
                    )
                }
                teardownSession()
                val created = Session(link)
                synchronized(sessionLock) { session = created }
                try {
                    created.bindServers()
                    created.startAccepting()
                    val deviceName = created.ble.connect(BLE_SCAN_TIMEOUT_MS).getOrThrow()
                    ProjectionEventLog.record(
                        "THINKERRIDE",
                        "Bluetooth handshake running with \"$deviceName\"; waiting for the dash " +
                            "to open the control connection on port ${ThinkerRideProtocol.CONTROL_PORT}."
                    )
                    val controlArrived =
                        withTimeoutOrNull(CONTROL_CONNECT_TIMEOUT_MS) { created.controlConnected.await() }
                    if (controlArrived == null) {
                        error(
                            "The dashboard paired over Bluetooth but never connected to this " +
                                "phone. Make sure the phone is on the dashboard's Wi-Fi network " +
                                "(scan the QR on the dash again if unsure), then retry."
                        )
                    }
                    TBoxHost(
                        ipAddress = created.localAddress(),
                        port = ThinkerRideProtocol.VIDEO_PORT,
                        packageName = ThinkerRideProtocol.PACKAGE_TAG
                    )
                } catch (failure: Throwable) {
                    teardownSession()
                    throw failure
                }
            }
        }

    override suspend fun start(host: TBoxHost): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val active = session ?: error("ThinkerRide session is not established; discover first.")
            val area = protocolProfile?.fallbackTBoxVideoArea
                ?: TBoxEvent.VideoArea(
                    ThinkerRideProtocol.DEFAULT_VIDEO_WIDTH,
                    ThinkerRideProtocol.DEFAULT_VIDEO_HEIGHT
                )
            // The dash only acts on a mirror-start once its own pairing handshake has landed, so
            // never fire one into a half-finished BLE session — that is what the AA retry path
            // used to do, 8 ms after re-opening the link.
            val confirmedBeforeStart = active.ble.awaitPairConfirmation(PAIR_CONFIRM_TIMEOUT_MS)
            if (!confirmedBeforeStart) {
                ProjectionEventLog.record(
                    "THINKERRIDE",
                    "The dashboard has not confirmed Bluetooth pairing after " +
                        "${PAIR_CONFIRM_TIMEOUT_MS}ms; sending mirror-start anyway."
                )
            }
            // Mirror-start goes out on the confirmation we already have, exactly as KoveMirror
            // does. A 2026-08-14 experiment re-sent the pairing handshake first, on the theory
            // that the dash only honours a mirror-start that closely follows one; the field
            // disproved it — a dash that ignores mirror-start ignored it 1.5s after a fresh
            // confirmation too, and answered none of 15 repeat handshakes. All that was left was
            // an unrequested `get_pairinfo` in front of every mirror-start, so it is gone. The
            // age is logged because it is the number any future theory about this would need.
            ProjectionEventLog.record(
                "THINKERRIDE",
                "Pairing was confirmed ${active.ble.millisSincePairConfirmation()}ms ago; " +
                    "sending mirror-start now."
            )
            active.mirrorArea = area
            active.ble.sendMirrorStatus(true)
            // "Sent" used to mean "queued", which is not the same claim at all: on a dash that
            // ignores mirror-start, whether the bytes actually left this phone is the difference
            // between our bug and its firmware. Wait for the queue to drain and say which it was.
            val drained = active.ble.awaitWritesDrained(MIRROR_START_DRAIN_TIMEOUT_MS)
            ProjectionEventLog.record(
                "THINKERRIDE",
                if (drained) {
                    "Mirror-start left the phone over Bluetooth; waiting for the dash video " +
                        "connection (stream ${area.width}x${area.height})."
                } else {
                    "Mirror-start is still queued after ${MIRROR_START_DRAIN_TIMEOUT_MS}ms; " +
                        "waiting for the dash video connection anyway " +
                        "(stream ${area.width}x${area.height})."
                }
            )
            // A mirror-start sent without a confirmation is a shot in the dark: in the field every
            // session that fired one went on to wait out the full video timeout, while every
            // session whose confirmation had landed got its video channel within 9s (KOVE 800X
            // rider log, 2026-08-20, 3 of each). If the confirmation turns up late - it has taken
            // 11s where the fallback allows 6 - that is new information arriving on a session that
            // is otherwise heading for the timeout, so spend one more mirror-start on it.
            //
            // Deliberately NOT a repeat of the 2026-08-14 experiment described above: no pairing
            // handshake is re-sent and nothing is added in front of a mirror-start that already
            // had its confirmation. This fires only when the first attempt went out blind and the
            // dash has since confirmed, so a session that works today never reaches it.
            val video = coroutineScope {
                val lateRetry = if (confirmedBeforeStart) null else launch {
                    if (!active.ble.awaitPairConfirmation(LATE_PAIR_CONFIRM_WINDOW_MS)) return@launch
                    if (active.videoConnected.isCompleted) return@launch
                    ProjectionEventLog.record(
                        "THINKERRIDE",
                        "The dashboard confirmed Bluetooth pairing after the first mirror-start " +
                            "had already gone out blind, and no video channel has opened since; " +
                            "sending one more mirror-start now that the confirmation is in."
                    )
                    active.ble.sendMirrorStatus(true)
                }
                // A dash that is going to answer by itself has always done so within 25.3s.
                // Past that the wait is on the rider, and a log that only ever says "waiting"
                // cannot tell the two apart - which is how a rider spent three days retrying a
                // dash that was working exactly as designed (case 32e132d0, 2026-08-24).
                val notice = launch {
                    delay(VIDEO_WAIT_NOTICE_MS)
                    if (active.videoConnected.isCompleted) return@launch
                    val message = "The dashboard has not opened the video connection " +
                        "${VIDEO_WAIT_NOTICE_MS / 1000}s after mirror-start. On these dashboards " +
                        "the rider starts projection FROM THE DASH by long-pressing UP; still " +
                        "listening on port ${ThinkerRideProtocol.VIDEO_PORT} until the timeout."
                    ProjectionEventLog.record("THINKERRIDE", message)
                    mutableEvents.tryEmit(TBoxEvent.Warning(message))
                }
                val connected =
                    withTimeoutOrNull(VIDEO_CONNECT_TIMEOUT_MS) { active.videoConnected.await() }
                // Cancel before leaving: coroutineScope waits for its children, and these would
                // otherwise hold the session open for the rest of their own windows.
                lateRetry?.cancel()
                notice.cancel()
                connected
            } ?: error(
                "The dashboard acknowledged the session but never opened the video connection. " +
                    "On these dashboards projection is started FROM THE DASH: press and hold the " +
                    "UP button on it while MOTO-HUB is connecting. If that does nothing, " +
                    "power-cycle the dash screen and connect again."
            )
            // The dash may have dropped and reopened the channel since the first accept; the
            // newest socket is the live one, the deferred only remembers the first.
            active.beginVideo(active.latestVideoSocket ?: video, area)
            mutableEvents.tryEmit(TBoxEvent.VideoArea(area.width, area.height, isFallback = false))
            mutableEvents.tryEmit(TBoxEvent.VideoStreamStart)
            Unit
        }
    }

    override fun offerAccessUnit(avcc: ByteArray): Boolean {
        val active = session ?: return false
        return active.offerAccessUnit(avcc)
    }

    override suspend fun stop() {
        val active = session ?: return
        // Tell the dash the mirror ended so its UI returns to the stock dashboard, and let the
        // write queue actually drain before the link is torn down under it.
        runCatching { active.ble.sendMirrorStatus(false) }
        runCatching { active.ble.awaitWritesDrained(BLE_DRAIN_TIMEOUT_MS) }
        teardownSession()
        mutableEvents.tryEmit(TBoxEvent.Stopped)
    }

    /**
     * The current session, when it can serve another [discover] as-is: same network, BLE link
     * never lost, and the dash has already proven it can reach the control server. Anything less
     * gets the full teardown-and-rescan.
     */
    private fun reusableSession(link: TBoxLink): Session? {
        val active = synchronized(sessionLock) { session } ?: return null
        val healthy = !active.closed.get() &&
            !active.fatalReported.get() &&
            active.controlConnected.isCompleted &&
            active.link.network == link.network
        return if (healthy) active else null
    }

    private fun teardownSession() {
        val previous = synchronized(sessionLock) {
            val current = session
            session = null
            current
        }
        previous?.close()
    }

    private fun reportFatal(message: String) {
        val active = session ?: return
        if (active.fatalReported.compareAndSet(false, true)) {
            ProjectionEventLog.error("THINKERRIDE", message)
            mutableEvents.tryEmit(TBoxEvent.FatalError(message))
        }
    }

    /** Everything owned by one dash connection, torn down as a unit. */
    private inner class Session(val link: TBoxLink) {
        val closed = AtomicBoolean(false)
        val fatalReported = AtomicBoolean(false)
        val controlConnected = CompletableDeferred<Socket>()
        val videoConnected = CompletableDeferred<Socket>()

        /** Set by [start] just before mirror-start; a reopened video connection needs it. */
        @Volatile
        var mirrorArea: TBoxEvent.VideoArea? = null

        /** Where the control connection came from: the dash's address on this network. */
        @Volatile
        var controlPeer: InetAddress? = null

        /** The most recent accepted video socket; [videoConnected] only remembers the first. */
        @Volatile
        var latestVideoSocket: Socket? = null

        val ble = ThinkerRideBleLink(
            appContext,
            log = { message -> ProjectionEventLog.record("THINKERRIDE", message) },
            onLinkLost = { reason -> reportFatal(reason) },
            useByteCatFraming = { protocolProfile?.bleUsesByteCatFraming == true }
        )

        private var controlServer: ServerSocket? = null
        private var heartbeatServer: ServerSocket? = null
        private var videoServer: ServerSocket? = null

        /** The dash repeats its TUC reply on every control reconnect; report the state once. */
        private val activationReported = AtomicBoolean(false)
        private val liveSockets = java.util.concurrent.CopyOnWriteArrayList<Socket>()

        private val pulseScheduler = ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "MotoHubThinkerRidePulse").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
        private var heartbeatPulse: ScheduledFuture<*>? = null
        private var videoKeepalive: ScheduledFuture<*>? = null

        // Same shape as RideDaemonTransport's pushFrameExecutor: one frame queued at most, so a
        // stalled dash socket produces rejected offers (which VideoBackpressureGuard understands)
        // instead of an unbounded backlog of stale video.
        private val frameExecutor = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(1)
        ) { runnable -> Thread(runnable, "MotoHubThinkerRideVideo").apply { isDaemon = true } }

        @Volatile
        private var videoOut: OutputStream? = null

        @Volatile
        private var videoSocket: Socket? = null

        /**
         * Binds the three server ports, retrying the ones still busy until [BIND_RETRY_WINDOW_MS]
         * runs out.
         *
         * The wait is there because the commonest holder of these ports is US, milliseconds ago.
         * A watchdog recovery tears the old session down and rebuilds it immediately: in rider
         * a7cda9d1's log (2026-08-24 17:34:35) the registry cleared at .162 and the rebind failed
         * at .171 - nine milliseconds - with "close the other dashboard/mirroring app", which was
         * not only wrong but cost recovery attempt 1 of the 3 that fit in the watchdog's 120s.
         * The very same bind succeeded five seconds later on attempt 2. The dash's own accepted
         * connections outlive our close() for as long as the kernel needs to finish tearing them
         * down, and SO_REUSEADDR does not cover a socket that has not reached TIME_WAIT yet.
         *
         * All three ports retry *together* against one deadline. Binding them in sequence, each
         * one free to spend the whole window, meant a busy first port starved the other two: they
         * got a single attempt apiece and were reported busy on the strength of it, which is the
         * opposite of what the retry is for.
         *
         * A port a DIFFERENT app holds still ends on the same honest error - just
         * [BIND_RETRY_WINDOW_MS] later, once per connect attempt.
         */
        fun bindServers() {
            val ports = listOf(
                ThinkerRideProtocol.CONTROL_PORT,
                ThinkerRideProtocol.HEARTBEAT_PORT,
                ThinkerRideProtocol.VIDEO_PORT
            )
            val startedAt = SystemClock.elapsedRealtime()
            val deadline = startedAt + BIND_RETRY_WINDOW_MS
            val bound = mutableMapOf<Int, ServerSocket>()
            val waitedFor = mutableListOf<Int>()
            var pass = 0
            while (true) {
                for (port in ports) {
                    if (bound.containsKey(port)) continue
                    val server = bindOnce(port) ?: continue
                    bound[port] = server
                    if (pass > 0) waitedFor += port
                }
                if (bound.size == ports.size) break
                if (SystemClock.elapsedRealtime() + BIND_RETRY_STEP_MS > deadline) break
                pass++
                Thread.sleep(BIND_RETRY_STEP_MS)
            }
            // Assign before any throw: the sockets that did bind belong to this session, and only
            // its close() will release them.
            controlServer = bound[ThinkerRideProtocol.CONTROL_PORT]
            heartbeatServer = bound[ThinkerRideProtocol.HEARTBEAT_PORT]
            videoServer = bound[ThinkerRideProtocol.VIDEO_PORT]
            if (waitedFor.isNotEmpty()) {
                // Logged only when the wait was actually needed: this is the line that tells a
                // future reader a recovery raced its own teardown rather than a rival app.
                ProjectionEventLog.record(
                    "THINKERRIDE",
                    "Ports ${waitedFor.joinToString()} were still busy when this session started " +
                        "and came free after ${SystemClock.elapsedRealtime() - startedAt}ms."
                )
            }
            val busy = ports.filterNot { bound.containsKey(it) }
            if (busy.isNotEmpty()) {
                error(
                    "Ports ${busy.joinToString()} are already in use on this phone, so the " +
                        "dashboard cannot connect. Close the other dashboard/mirroring app and retry."
                )
            }
        }

        /** One bind attempt, or null when the port is taken right now. */
        private fun bindOnce(port: Int): ServerSocket? = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port), 1)
            }
        }.getOrNull()

        fun startAccepting() {
            acceptLoop("control", controlServer) { socket -> runControl(socket) }
            acceptLoop("heartbeat", heartbeatServer) { socket -> runHeartbeat(socket) }
            acceptLoop("video", videoServer) { socket ->
                socket.tcpNoDelay = true
                // Only a dash that was told to mirror can be opening this; anything else is
                // something on this phone probing the port. The network diagnostics screen did
                // exactly that (it dials the registry's host:15456, which on this wire is *us*),
                // and without this guard that probe was accepted as the dash and the real
                // connection had nowhere to land.
                //
                // Except the dash itself. It opens this channel on its own — six times before any
                // mirror-start on one KOVE 800X (796efe04), and presumably whenever the rider
                // starts projection from the dash with a long press on UP, which is exactly what
                // our own error message tells them to do. It is recognised by coming from the
                // same address as the control connection, which a probe from this phone never
                // does. It is kept, not started: start() finds it already waiting.
                if (mirrorArea == null && isFromTheDash(socket)) {
                    ProjectionEventLog.record(
                        "THINKERRIDE",
                        "The dash opened the video channel before any mirror-start; keeping it " +
                            "for when projection starts."
                    )
                    latestVideoSocket = socket
                    videoConnected.complete(socket)
                    return@acceptLoop
                }
                if (mirrorArea == null) {
                    ProjectionEventLog.record(
                        "THINKERRIDE",
                        "Ignoring a video connection that arrived before any mirror-start; " +
                            "the dash was never asked to mirror."
                    )
                    runCatching { socket.close() }
                    return@acceptLoop
                }
                latestVideoSocket = socket
                if (!videoConnected.complete(socket)) {
                    // The dash reopened the channel — or connected after start() stopped
                    // waiting. Wire the running session onto the new socket instead of
                    // leaving the dash mirroring into nothing.
                    mirrorArea?.let { area -> beginVideo(socket, area) }
                }
            }
        }

        /** Same remote address as the control connection, and not a socket from this phone. */
        private fun isFromTheDash(socket: Socket): Boolean {
            val dash = controlPeer ?: return false
            val peer = socket.inetAddress ?: return false
            return peer == dash && !peer.isLoopbackAddress
        }

        private fun acceptLoop(label: String, server: ServerSocket?, onAccepted: (Socket) -> Unit) {
            val listening = server ?: return
            Thread({
                while (!closed.get()) {
                    val socket = runCatching { listening.accept() }.getOrNull() ?: break
                    liveSockets += socket
                    ProjectionEventLog.record(
                        "THINKERRIDE",
                        "Dash connected to the $label channel from ${socket.inetAddress?.hostAddress}."
                    )
                    runCatching { onAccepted(socket) }
                }
            }, "MotoHubThinkerRideAccept-$label").apply { isDaemon = true }.start()
        }

        private fun runControl(socket: Socket) {
            if (!controlConnected.isCompleted) controlConnected.complete(socket)
            controlPeer = socket.inetAddress
            val out = socket.getOutputStream()
            writeQuietly(out, ThinkerRideProtocol.controlOpeningQuery())
            Thread({
                val buffer = ByteArray(4096)
                var handshakeSent = false
                val input = runCatching { socket.getInputStream() }.getOrNull() ?: return@Thread
                while (!closed.get()) {
                    val read = runCatching { input.read(buffer) }.getOrDefault(-1)
                    if (read <= 0) break
                    logControlPayload(buffer, read)
                    val probes = ThinkerRideProtocol.keepaliveProbeCount(buffer, read)
                    if (probes > 0) {
                        // One echo per probe, as one write: the dash counts them.
                        val echo = ByteArray(probes * ThinkerRideProtocol.KEEPALIVE_PACKET.size)
                        for (index in 0 until probes) {
                            ThinkerRideProtocol.KEEPALIVE_PACKET.copyInto(
                                echo, index * ThinkerRideProtocol.KEEPALIVE_PACKET.size
                            )
                        }
                        writeQuietly(out, echo)
                    }
                    if (!handshakeSent) {
                        handshakeSent = true
                        // The reference implementation waits 100 ms after the dash's first
                        // packet before the binary handshake; firmware drops it otherwise.
                        pulseScheduler.schedule({
                            writeQuietly(out, ThinkerRideProtocol.controlHandshake())
                            ThinkerRideProtocol.controlNaviQueries().forEach { writeQuietly(out, it) }
                        }, 100, TimeUnit.MILLISECONDS)
                    }
                }
            }, "MotoHubThinkerRideControl").apply { isDaemon = true }.start()
        }

        private fun runHeartbeat(socket: Socket) {
            val out = socket.getOutputStream()
            heartbeatPulse?.cancel(false)
            heartbeatPulse = pulseScheduler.scheduleWithFixedDelay(
                { writeQuietly(out, ThinkerRideProtocol.KEEPALIVE_PACKET) },
                0,
                ThinkerRideProtocol.HEARTBEAT_PULSE_INTERVAL_MS,
                TimeUnit.MILLISECONDS
            )
        }

        // Reached from both the accept thread (dash reopened the channel) and start().
        @Synchronized
        fun beginVideo(socket: Socket, area: TBoxEvent.VideoArea) {
            if (videoSocket === socket) return
            videoSocket?.let { previous -> runCatching { previous.close() } }
            val out = socket.getOutputStream()
            out.write(ThinkerRideProtocol.videoSizeHeader(area.width, area.height))
            out.flush()
            videoSocket = socket
            videoOut = out
            videoKeepalive?.cancel(false)
            videoKeepalive = pulseScheduler.scheduleWithFixedDelay(
                { writeQuietly(out, ThinkerRideProtocol.KEEPALIVE_PACKET) },
                ThinkerRideProtocol.VIDEO_KEEPALIVE_INTERVAL_MS,
                ThinkerRideProtocol.VIDEO_KEEPALIVE_INTERVAL_MS,
                TimeUnit.MILLISECONDS
            )
        }

        fun offerAccessUnit(avcc: ByteArray): Boolean {
            val out = videoOut ?: return false
            return try {
                frameExecutor.execute {
                    try {
                        synchronized(out) {
                            out.write(ThinkerRideProtocol.annexBFromAvcc(avcc))
                            out.flush()
                        }
                    } catch (failure: IOException) {
                        reportFatal("The dashboard video connection dropped: ${failure.message}")
                    }
                }
                true
            } catch (_: RejectedExecutionException) {
                // A frame is already queued behind a slow socket; drop this one instead of
                // building latency. VideoBackpressureGuard turns a persistent streak into a stop.
                false
            }
        }

        fun localAddress(): String {
            (link.network)?.let { network ->
                val properties = appContext.getSystemService(ConnectivityManager::class.java)
                    ?.getLinkProperties(network)
                properties?.linkAddresses
                    ?.map { it.address }
                    ?.filterIsInstance<Inet4Address>()
                    ?.firstOrNull()
                    ?.hostAddress
                    ?.let { return it }
            }
            return runCatching {
                NetworkInterface.getNetworkInterfaces().toList()
                    .asSequence()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.asSequence() }
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { it.isSiteLocalAddress }
                    ?.hostAddress
            }.getOrNull() ?: "0.0.0.0"
        }

        private fun writeQuietly(out: OutputStream, data: ByteArray) {
            try {
                synchronized(out) {
                    out.write(data)
                    out.flush()
                }
            } catch (_: IOException) {
                // Channel-level drops are surfaced by the video path / watchdogs, not here.
            }
        }

        /**
         * Reads the activation flag out of the dash's TUC reply and says so once per session.
         * We have always asked the question - [ThinkerRideProtocol.controlOpeningQuery] goes out
         * the moment the control channel opens - and always thrown the answer away, so a rider
         * whose dash reports `tucs != 1` got "the dashboard never opened the video connection,
         * power-cycle it" for a firmware gate that no power cycle clears.
         *
         * Reporting only; nothing is blocked on it. A dash that says it is not activated and then
         * mirrors anyway is a fact worth having in the log rather than a session worth refusing.
         */
        private fun reportActivation(payload: String) {
            val flag = ThinkerRideProtocol.parseActivationFlag(payload) ?: return
            if (!activationReported.compareAndSet(false, true)) return
            if (flag == ThinkerRideProtocol.ACTIVATED_TUCS) {
                ProjectionEventLog.record(
                    "THINKERRIDE",
                    "Dashboard reports it is activated (tucs=$flag); projection is not gated."
                )
            } else {
                ProjectionEventLog.warning(
                    "THINKERRIDE",
                    "Dashboard reports it is NOT activated (tucs=$flag). ThinkerRide firmware " +
                        "refuses to open the video channel in this state, so mirroring will be " +
                        "acknowledged and then never start. Power-cycling the dash does not " +
                        "clear this - the dash has to be activated through the OEM app once."
                )
            }
        }

        private fun logControlPayload(buffer: ByteArray, length: Int) {
            // The control channel is where an unknown ThinkerRide model would identify itself,
            // so keep whatever readable content arrives; a future profile is written from these
            // lines the same way EasyConn profiles are written from CLIENT_INFO logs.
            //
            // Binary replies used to be dropped here, which left every diagnostic log showing
            // only our side of this conversation: a dash answering our five handshake commands
            // in binary said nothing we could read. On a dash that accepts mirror-start and then
            // never mirrors (KOVE 800X PRO, 2026-08-17) that silence is the whole question, so
            // anything unreadable is hex-dumped instead of discarded.
            // The 6-byte keep-alive arrives every few seconds and is answered, not read; hex
            // dumping it would bury everything else.
            if (ThinkerRideProtocol.keepaliveProbeCount(buffer, length) > 0) return
            val text = String(buffer, 0, length, StandardCharsets.UTF_8)
            val printable = text.count { it.code in 32..126 }
            if (printable >= length / 2 && text.isNotBlank()) {
                ProjectionEventLog.record("THINKERRIDE", "Dash control payload: ${text.trim()}")
                reportActivation(text)
                return
            }
            val shown = minOf(length, CONTROL_HEX_DUMP_LIMIT)
            val hex = buildString(shown * 3) {
                for (index in 0 until shown) {
                    if (index > 0) append(' ')
                    append("%02X".format(buffer[index]))
                }
            }
            val suffix = if (length > shown) " … (+${length - shown} bytes)" else ""
            ProjectionEventLog.record(
                "THINKERRIDE",
                "Dash control payload (binary, $length bytes): $hex$suffix"
            )
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            heartbeatPulse?.cancel(false)
            videoKeepalive?.cancel(false)
            pulseScheduler.shutdownNow()
            frameExecutor.shutdownNow()
            ble.close()
            videoOut = null
            videoSocket = null
            liveSockets.forEach { runCatching { it.close() } }
            liveSockets.clear()
            runCatching { controlServer?.close() }
            runCatching { heartbeatServer?.close() }
            runCatching { videoServer?.close() }
        }
    }

    private companion object {
        const val BLE_SCAN_TIMEOUT_MS = 20_000L
        const val CONTROL_CONNECT_TIMEOUT_MS = 20_000L

        /**
         * A real KOVE 800X was logged opening the video connection 12.0s and 25.3s after
         * mirror-start (2026-08-13 tester diagnostics), so 15s lost the race half the time.
         *
         * Raised from 40s once it became clear what this wait is actually waiting for. On the
         * SiQi firmware (`SV=3.0.x`, KOVE 450 Rally) the dash dials the video port when the
         * RIDER long-presses UP on it — a human gesture, not a protocol step, documented in the
         * owner's manual and in ttarlov/kove-dash but in none of the companion apps. The OEM's
         * own listener waits indefinitely for it; 40s was sized for a wire handshake and gives a
         * rider who has just been told what to do barely enough time to reach the bars. The
         * app's own connection notification already tells riders this can take a minute and a
         * half (see ConnectionProgressNotification).
         */
        const val VIDEO_CONNECT_TIMEOUT_MS = 75_000L

        /**
         * When the wait stops being "the dash is answering" and starts being "the dash is
         * waiting for the rider", so the log says which one it is. Comfortably past the 25.3s
         * worst case a dash that answers on its own has ever taken.
         */
        const val VIDEO_WAIT_NOTICE_MS = 30_000L

        /** How long [start] waits for `send_pairresult` before going ahead regardless. */
        const val PAIR_CONFIRM_TIMEOUT_MS = 6_000L

        /**
         * How long a blind mirror-start keeps watching for a late `send_pairresult`, so it can
         * spend one more mirror-start on a confirmation that missed [PAIR_CONFIRM_TIMEOUT_MS].
         * A confirmation has been seen landing 11.0s after the BLE handshake (KOVE 800X rider
         * log, 2026-08-20 23:30:10 to 23:30:21), which the 6s fallback cannot wait for without
         * delaying every dash that answers promptly. Kept well inside
         * [VIDEO_CONNECT_TIMEOUT_MS] so the second attempt still has a window to be answered in.
         */
        const val LATE_PAIR_CONFIRM_WINDOW_MS = 20_000L

        /**
         * How long [bindServers] keeps trying busy server ports before calling them taken -
         * for all three together, not each. Sized for the case it exists for: our own previous
         * session's sockets finishing their teardown, which took under five seconds in the log
         * that prompted it and normally takes a few hundred milliseconds. A rival app will still
         * be there afterwards, and gets the same honest error it always did.
         *
         * Three seconds was not enough. Rider 2e3b10d2's log (2026-08-27) burnt the whole window
         * four times in six minutes - 18:45:02, 18:47:06, 18:49:21, 18:50:28 - never once seeing a
         * port come free, so every retry after a video timeout was spent on a bind that could not
         * win and the rider got "close the other dashboard/mirroring app" for our own sockets.
         * Ten seconds covers the five that attempt 2 needed in a7cda9d1's log with margin, and
         * still fits several times over inside [VIDEO_CONNECT_TIMEOUT_MS].
         */
        const val BIND_RETRY_WINDOW_MS = 10_000L

        /** How often [bindServers] retries the still-busy ports inside [BIND_RETRY_WINDOW_MS]. */
        const val BIND_RETRY_STEP_MS = 100L

        /** How long [stop] gives the mirror-stop packets to leave the phone. */
        const val BLE_DRAIN_TIMEOUT_MS = 1_500L

        /** Bytes of an unreadable control payload written to the log before truncating. */
        const val CONTROL_HEX_DUMP_LIMIT = 64

        /**
         * How long [start] waits for the mirror-start packets to actually leave the phone before
         * it stops claiming they did. Two packets [ThinkerRideProtocol.BLE_WRITE_SPACING_MS]
         * apart clear well inside this even after a retry or two.
         */
        const val MIRROR_START_DRAIN_TIMEOUT_MS = 3_000L
    }
}
