// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Runs one mySPIN TCP session on an already connected socket: a reader thread feeds
 * [MySpinProtocol], a timer pings the dash and watches for silence, and [offerFrame] hands in
 * pictures. No Android types, so the whole loop is tested against a fake dash on loopback.
 *
 * The dash pulls frames, the app's JPEG source pushes them, and the two meet in a one-slot cache:
 * - a frame offered while a request is open goes out at once;
 * - a request that finds an unsent frame takes it at once;
 * - a request that finds only the frame already sent waits for the next one, but after
 *   [staleFrameResendMillis] gets that same frame again, because the JPEG source never re-offers
 *   an unchanged picture and a still screen must not leave the dash waiting forever.
 *
 * shortcut: the stale resend interval is a guess (1 s); tune it from the first field log.
 */
class MySpinSession(
    private val socket: Socket,
    private val listener: Listener,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pingIntervalMillis: Long = PING_INTERVAL_MS,
    private val idleTimeoutMillis: Long = IDLE_TIMEOUT_MS,
    private val staleFrameResendMillis: Long = STALE_FRAME_RESEND_MS,
    private val tickMillis: Long = TICK_MS
) : Closeable {

    interface Listener {
        /** Every protocol event, for the log. */
        fun onEvent(event: MySpinProtocol.Event) {}
        fun onReady(screen: MySpinProtocol.Screen) {}
        fun onKey(event: MySpinProtocol.Event.KeyInput) {}

        /** The session ended; null when it was closed on purpose or the dash hung up cleanly. */
        fun onClosed(error: Throwable?) {}
    }

    private val lock = Any()
    private val output = BufferedOutputStream(socket.getOutputStream())
    private val protocol = MySpinProtocol(send = ::write, clock = clock)
    private val closed = AtomicBoolean()
    private var timer: ScheduledExecutorService? = null

    // Guarded by lock.
    private var latestFrame: ByteArray? = null
    private var latestFrameSent = true
    private var requestedAt = 0L
    private var lastPingAt = 0L
    private var pingSequence = 0
    private var framesSent = 0

    @Volatile private var lastReceiveAt = clock()

    val isOpen: Boolean get() = !closed.get()

    /** Frames that reached the socket so far. */
    val sentFrameCount: Int get() = synchronized(lock) { framesSent }

    fun start() {
        thread(name = "myspin-reader", isDaemon = true) { readLoop() }
        timer = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "myspin-timer").apply { isDaemon = true }
        }.also { it.scheduleWithFixedDelay(::tick, tickMillis, tickMillis, TimeUnit.MILLISECONDS) }
    }

    /** Hands in the newest picture, already at the dash's screen size. False once the session is over. */
    fun offerFrame(jpeg: ByteArray): Boolean {
        if (closed.get()) return false
        val sent = try {
            synchronized(lock) {
                latestFrame = jpeg
                latestFrameSent = false
                sendIfDue()
            }
        } catch (failure: Throwable) {
            finish(failure)
            return false
        }
        sent?.let(listener::onEvent)
        return true
    }

    private fun readLoop() {
        try {
            val input = BufferedInputStream(socket.getInputStream())
            while (!closed.get()) {
                val packet = MySpinWire.read(input) ?: break
                lastReceiveAt = clock()
                val events = synchronized(lock) { react(protocol.receive(packet)) }
                events.forEach(::dispatch)
            }
            finish(null)
        } catch (failure: Throwable) {
            finish(failure)
        }
    }

    /** Called with the lock held; returns everything to report once it is released. */
    private fun react(events: List<MySpinProtocol.Event>): List<MySpinProtocol.Event> {
        val all = events.toMutableList()
        for (event in events) {
            when (event) {
                is MySpinProtocol.Event.StateChanged ->
                    if (event.state == MySpinProtocol.State.READY) protocol.announceDisplayReady()
                is MySpinProtocol.Event.FrameRequested -> {
                    requestedAt = clock()
                    sendIfDue()?.let(all::add)
                }
                else -> Unit
            }
        }
        return all
    }

    private fun dispatch(event: MySpinProtocol.Event) {
        listener.onEvent(event)
        when (event) {
            is MySpinProtocol.Event.StateChanged ->
                if (event.state == MySpinProtocol.State.READY) protocol.screen?.let(listener::onReady)
            is MySpinProtocol.Event.KeyInput -> listener.onKey(event)
            else -> Unit
        }
    }

    /** With the lock held: answers an open request with an unsent frame, if there is one. */
    private fun sendIfDue(): MySpinProtocol.Event.FrameSent? {
        val frame = latestFrame ?: return null
        if (latestFrameSent || protocol.state != MySpinProtocol.State.READY || !protocol.hasPendingFrameRequest) return null
        return sendLocked(frame)
    }

    private fun sendLocked(frame: ByteArray): MySpinProtocol.Event.FrameSent? =
        protocol.sendFrame(frame)?.also {
            latestFrameSent = true
            framesSent++
        }

    private fun tick() {
        if (closed.get()) return
        val now = clock()
        if (now - lastReceiveAt > idleTimeoutMillis) {
            finish(SocketTimeoutException("no data from the dash for ${idleTimeoutMillis / 1000} s"))
            return
        }
        val resent = try {
            synchronized(lock) {
                if (protocol.state != MySpinProtocol.State.READY) return
                if (now - lastPingAt >= pingIntervalMillis) {
                    lastPingAt = now
                    protocol.ping(pingSequence++)
                }
                val frame = latestFrame
                if (frame != null && latestFrameSent && protocol.hasPendingFrameRequest &&
                    now - requestedAt >= staleFrameResendMillis
                ) {
                    sendLocked(frame)
                } else {
                    null
                }
            }
        } catch (failure: Throwable) {
            finish(failure)
            return
        }
        resent?.let(listener::onEvent)
    }

    private fun write(packet: MySpinWire.Packet) {
        output.write(MySpinWire.encode(packet))
        output.flush()
    }

    private fun finish(error: Throwable?) {
        if (!closed.compareAndSet(false, true)) return
        timer?.shutdownNow()
        runCatching { socket.close() }
        listener.onClosed(error)
    }

    override fun close() = finish(null)

    companion object {
        const val PING_INTERVAL_MS = 2_000L
        const val IDLE_TIMEOUT_MS = 15_000L
        const val STALE_FRAME_RESEND_MS = 1_000L
        const val TICK_MS = 100L
    }
}
