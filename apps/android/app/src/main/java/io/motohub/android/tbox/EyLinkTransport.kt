// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import io.motohub.android.session.ProjectionEventLog
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext

/**
 * EyLink (Erya) projection transport, for the Kove 350 RR and any dash on the same stack.
 *
 * The Wi-Fi Direct join is not this class's job: the QR saves the dash's P2P device name as a
 * [io.motohub.android.session.TBoxConnectionMode.WIFI_DIRECT] profile, and
 * [TBoxWifiDirectConnector] already finds a peer by that name and joins its group with the dash
 * as owner. What arrives here is that link, whose gateway is the dash.
 *
 * The phone is the client on both sockets. [start] opens the control socket and keeps the
 * heartbeat running on it, then opens the video socket, sends MIRROR_START and waits for the
 * dash's `OK`, which carries the panel size, frame rate and bit rate. That reply is the
 * [TBoxEvent.VideoArea] the session encoder is configured from; the profile's fallback stands in
 * only when it never comes. From then on video is pushed: no request, no acknowledgement, so the
 * one-slot executor below is the only back-pressure there is.
 *
 * Unverified against hardware. Everything the dash sends that this class does not act on is
 * logged once in hex, because the next field log is the only bench this code has had.
 */
class EyLinkTransport : TBoxTransport {

    private val mutableEvents = MutableSharedFlow<TBoxEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val events: Flow<TBoxEvent> = mutableEvents.asSharedFlow()

    @Volatile
    private var protocolProfile: TBoxModelProfile? = null

    @Volatile
    private var link: TBoxLink? = null

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
                teardownSession()
                this@EyLinkTransport.link = link
                // The group owner is the dash; its address comes from the live group, and the
                // reported one is only the fallback for a link that names no gateway.
                val host = link.peerHint?.hostAddress ?: EyLinkProtocol.DEFAULT_HOST
                ProjectionEventLog.record(
                    "EYLINK",
                    "Link ready (${link.label}); dash expected at $host, control " +
                        "${EyLinkProtocol.CONTROL_PORT}, video ${EyLinkProtocol.VIDEO_PORT}."
                )
                TBoxHost(host, EyLinkProtocol.VIDEO_PORT, EYLINK_PACKAGE_TAG)
            }
        }

    override suspend fun start(host: TBoxHost): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val activeLink = link ?: error("EyLink link is not established; discover first.")
            val fallback = protocolProfile?.fallbackTBoxVideoArea
                ?: TBoxEvent.VideoArea(DEFAULT_WIDTH, DEFAULT_HEIGHT)

            teardownSession()
            val created = Session()
            synchronized(sessionLock) { session = created }
            try {
                created.connect(activeLink, host.ipAddress)
                val capabilities = created.startMirror()
                val width = capabilities?.videoWidth?.takeIf { it > 0 } ?: fallback.width
                val height = capabilities?.videoHeight?.takeIf { it > 0 } ?: fallback.height
                created.videoWidth = width
                created.videoHeight = height
                ProjectionEventLog.record(
                    "EYLINK",
                    if (capabilities != null) {
                        "Dash asked for ${capabilities.landscapeWidth}x${capabilities.landscapeHeight} " +
                            "landscape (${capabilities.portraitWidth}x${capabilities.portraitHeight} " +
                            "portrait) at ${capabilities.frameRate} fps, ${capabilities.bitRateKbps} kbps; " +
                            "encoding ${width}x$height."
                    } else {
                        "No OK from the dash - encoding fallback ${width}x$height."
                    }
                )
                created.startDraining()
                mutableEvents.tryEmit(TBoxEvent.VideoArea(width, height, isFallback = capabilities == null))
                mutableEvents.tryEmit(TBoxEvent.VideoStreamStart)
                Unit
            } catch (failure: Throwable) {
                teardownSession()
                throw failure
            }
        }
    }

    override fun offerAccessUnit(avcc: ByteArray): Boolean {
        val active = session ?: return false
        return active.offerAccessUnit(avcc)
    }

    override suspend fun stop() {
        val active = synchronized(sessionLock) { session } ?: return
        // MIRROR_STOP first, so the dash goes back to its own screen before the sockets close.
        active.sendStop()
        teardownSession()
        mutableEvents.tryEmit(TBoxEvent.Stopped)
    }

    private fun teardownSession() {
        val previous = synchronized(sessionLock) {
            val current = session
            session = null
            current
        }
        previous?.close()
    }

    private fun reportFatal(owner: Session, message: String) {
        if (session !== owner) return
        if (owner.fatalReported.compareAndSet(false, true)) {
            ProjectionEventLog.error("EYLINK", message)
            mutableEvents.tryEmit(TBoxEvent.FatalError(message))
        }
    }

    /** Both sockets and every thread of one dash connection, torn down as a unit. */
    private inner class Session {
        val fatalReported = AtomicBoolean(false)
        private val running = AtomicBoolean(false)

        @Volatile
        private var controlSocket: Socket? = null

        @Volatile
        private var videoSocket: Socket? = null

        @Volatile
        private var controlOutput: OutputStream? = null

        @Volatile
        private var videoOutput: OutputStream? = null

        @Volatile
        var videoWidth = 0

        @Volatile
        var videoHeight = 0

        private val frameIndex = AtomicInteger(0)
        private var mirrorStartedAtMillis = 0L

        /** When WIDTH_HEIGHT last went out; zero sends it ahead of the first access unit. */
        private var lastWidthHeightMillis = 0L

        private val threads = mutableListOf<Thread>()
        private val startedAtMillis = System.currentTimeMillis()
        private val loggedFirsts = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        // One access unit queued at most, like the other transports: a stalled socket produces
        // refused offers that VideoBackpressureGuard understands, not a backlog of stale video.
        // On a push wire this is the only limit there is.
        private val frameExecutor = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(1)
        ) { runnable -> Thread(runnable, "MotoHubEyLinkVideo").apply { isDaemon = true } }

        private fun phase(event: String) {
            ProjectionEventLog.record("EYLINK", "phase +${System.currentTimeMillis() - startedAtMillis}ms: $event")
        }

        private fun phaseOnce(key: String, event: () -> String) {
            if (loggedFirsts.add(key)) phase(event())
        }

        fun connect(link: TBoxLink, host: String) {
            running.set(true)
            val control = link.createSocket()
            control.connect(InetSocketAddress(host, EyLinkProtocol.CONTROL_PORT), CONNECT_TIMEOUT_MS)
            control.tcpNoDelay = true
            controlSocket = control
            controlOutput = BufferedOutputStream(control.getOutputStream())
            phase("control connected to $host:${EyLinkProtocol.CONTROL_PORT}")
            // A dash on a phone-hosted network came through the official client's road, and that
            // client sends nothing on this socket until it has JSON to send: its `AF BB CC 0F`
            // heartbeat only goes to a localhost SDK port. Only the Wi-Fi Direct 350 RR has been
            // seen painting with this frame on the wire, so it keeps it.
            if (link !is TBoxLink.PhoneHotspot) startThread("MotoHubEyLinkHeartbeat") { heartbeatLoop() }

            val video = link.createSocket()
            video.connect(InetSocketAddress(host, EyLinkProtocol.VIDEO_PORT), CONNECT_TIMEOUT_MS)
            video.tcpNoDelay = true
            videoSocket = video
            videoOutput = BufferedOutputStream(video.getOutputStream())
            phase("video connected to $host:${EyLinkProtocol.VIDEO_PORT}")
        }

        /** Sends MIRROR_START and reads the dash's `OK`, or null when it never arrives. */
        fun startMirror(): EyLinkProtocol.Capabilities? {
            val socket = videoSocket ?: return null
            mirrorStartedAtMillis = System.currentTimeMillis()
            writeVideo(EyLinkProtocol.mirrorStartFrame())
            phase(
                "MIRROR_START sent (source ${EyLinkProtocol.DEFAULT_SOURCE_WIDTH}x" +
                    "${EyLinkProtocol.DEFAULT_SOURCE_HEIGHT})"
            )

            val buffer = ByteArray(OK_SEARCH_LIMIT)
            var filled = 0
            val input = socket.getInputStream()
            val deadline = System.currentTimeMillis() + OK_TIMEOUT_MS
            try {
                while (filled < buffer.size) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) break
                    // The official client repeats MIRROR_START every second until the dash answers.
                    socket.soTimeout = minOf(remaining, MIRROR_START_REPEAT_MS).toInt()
                    val read = try {
                        input.read(buffer, filled, buffer.size - filled)
                    } catch (_: SocketTimeoutException) {
                        writeVideo(EyLinkProtocol.mirrorStartFrame())
                        continue
                    }
                    if (read < 0) break
                    filled += read
                    val offset = EyLinkProtocol.okReplyOffset(buffer, filled) ?: continue
                    if (offset > 0) phase("${offset}b before the OK: [${EyLinkProtocol.hex(buffer, offset)}]")
                    val after = filled - offset - EyLinkProtocol.OK_REPLY_SIZE
                    if (after > 0) {
                        phase(
                            "${after}b after the OK: [" + EyLinkProtocol.hex(
                                buffer.copyOfRange(offset + EyLinkProtocol.OK_REPLY_SIZE, filled)
                            ) + "]"
                        )
                    }
                    phase(
                        "dash replied [" + EyLinkProtocol.hex(
                            buffer.copyOfRange(offset, offset + EyLinkProtocol.OK_REPLY_SIZE)
                        ) + "]"
                    )
                    return EyLinkProtocol.parseOkReply(buffer, filled)
                }
            } catch (_: SocketTimeoutException) {
                // Falls through to the fallback below; what did arrive is logged there.
            } finally {
                runCatching { socket.soTimeout = 0 }
            }
            phase(
                "no OK within ${OK_TIMEOUT_MS}ms" +
                    if (filled > 0) "; got ${filled}b [${EyLinkProtocol.hex(buffer, filled)}]" else ""
            )
            return null
        }

        /** Reads whatever the dash sends on either socket for the rest of the session. */
        fun startDraining() {
            controlSocket?.let { socket ->
                startThread("MotoHubEyLinkControlRecv") { drain(socket.getInputStream(), "control") }
            }
            videoSocket?.let { socket ->
                startThread("MotoHubEyLinkVideoRecv") { drain(socket.getInputStream(), "video") }
            }
        }

        fun offerAccessUnit(avcc: ByteArray): Boolean {
            if (!running.get()) return false
            return try {
                frameExecutor.execute {
                    try {
                        sendAccessUnit(avcc)
                    } catch (failure: IOException) {
                        reportFatal(this, "The dashboard video connection dropped: ${failure.message}")
                    }
                }
                true
            } catch (_: RejectedExecutionException) {
                false
            }
        }

        private fun sendAccessUnit(avcc: ByteArray) {
            if (!running.get()) return
            val now = System.currentTimeMillis()
            if (now - lastWidthHeightMillis >= EyLinkProtocol.WIDTH_HEIGHT_INTERVAL_MS) {
                writeVideo(EyLinkProtocol.widthHeightFrame(videoWidth, videoHeight))
                lastWidthHeightMillis = now
                phaseOnce("width-height") { "WIDTH_HEIGHT ${videoWidth}x$videoHeight sent" }
            }
            val annexB = annexBFromAvcc(avcc)
            writeVideo(
                EyLinkProtocol.videoDataFrame(
                    frameIndex.getAndIncrement(),
                    now - mirrorStartedAtMillis,
                    annexB
                )
            )
            phaseOnce("first-frame") { "first access unit out (${annexB.size}b)" }
        }

        private fun heartbeatLoop() {
            val frame = EyLinkProtocol.heartbeatFrame()
            while (running.get()) {
                try {
                    writeControl(frame)
                } catch (failure: IOException) {
                    reportFatal(this, "The dashboard control connection dropped: ${failure.message}")
                    return
                }
                try {
                    Thread.sleep(EyLinkProtocol.HEARTBEAT_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }

        /**
         * Logs the first bytes the dash sends on [channel] and then only counts. The control
         * replies (`EY`/`EH`, big-endian length) are only partly documented, and the note's open
         * question is what else arrives on the video port; neither is acted on yet.
         */
        private fun drain(input: InputStream, channel: String) {
            val buffer = ByteArray(DRAIN_BUFFER_SIZE)
            var total = 0L
            while (running.get()) {
                val read = try {
                    input.read(buffer)
                } catch (_: IOException) {
                    -1
                }
                if (read < 0) {
                    if (running.get()) reportFatal(this, "The dashboard closed the EyLink $channel connection.")
                    return
                }
                if (total == 0L) {
                    val shown = EyLinkProtocol.hex(buffer, minOf(read, HEX_LOG_LIMIT))
                    phase("first ${read}b from the dash on $channel: [$shown]")
                }
                total += read
            }
        }

        fun sendStop() {
            if (!running.get()) return
            runCatching { writeVideo(EyLinkProtocol.mirrorStopFrame()) }
        }

        private fun writeControl(data: ByteArray) = write(controlOutput, data)

        private fun writeVideo(data: ByteArray) = write(videoOutput, data)

        private fun write(output: OutputStream?, data: ByteArray) {
            val out = output ?: return
            synchronized(out) {
                out.write(data)
                out.flush()
            }
        }

        private fun startThread(name: String, body: () -> Unit) {
            threads += Thread(body, name).apply { isDaemon = true }.also { it.start() }
        }

        fun close() {
            running.set(false)
            threads.forEach { it.interrupt() }
            frameExecutor.shutdownNow()
            // Video before control, the order the protocol note gives for shutdown.
            runCatching { videoSocket?.close() }
            runCatching { controlSocket?.close() }
            videoSocket = null
            controlSocket = null
            videoOutput = null
            controlOutput = null
        }
    }

    private companion object {
        const val EYLINK_PACKAGE_TAG = "eylink"
        const val CONNECT_TIMEOUT_MS = 5_000
        const val OK_TIMEOUT_MS = 10_000L
        const val MIRROR_START_REPEAT_MS = 1_000L

        /** Room for the 13-byte reply plus whatever unknown bytes may come around it. */
        const val OK_SEARCH_LIMIT = 256
        const val DRAIN_BUFFER_SIZE = 4_096
        const val HEX_LOG_LIMIT = 64

        /** The panel of the one dash reported, for a profile that names no fallback. */
        const val DEFAULT_WIDTH = 784
        const val DEFAULT_HEIGHT = 352
    }
}
