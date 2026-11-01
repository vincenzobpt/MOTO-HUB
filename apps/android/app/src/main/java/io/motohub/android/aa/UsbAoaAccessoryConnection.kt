// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.aa

import android.os.SystemClock
import io.motohub.android.externaldisplay.AoaAccessorySession
import java.io.IOException

/**
 * [AccessoryConnection] over a physical USB AOA accessory (the external head unit), instead of
 * the loopback socket [SocketAccessoryConnection] uses to talk to Google's own Android Auto app.
 * The accessory is opened by the app and lent here as two streams: the file descriptor and its
 * lifetime stay on that side.
 *
 * A dedicated thread keeps one blocking read of [BULK_READ_SIZE] bytes outstanding from the
 * moment this is built, and [recvBlocking] takes from what it has buffered. Two things about the
 * accessory device force this shape:
 *
 *  - `available()` never reports anything on it. It is an `ioctl(FIONREAD)` the accessory driver
 *    does not implement, and the gadget side only receives a bulk transfer while a read is
 *    queued, so there is never anything waiting to be counted. The earlier version polled
 *    `available()` before reading, and so never read at all: that is the "the head unit put no
 *    byte on the wire" of the 2026-09-07 probe. Some head units also give up on a phone that has
 *    not read within about a second.
 *  - One read returns one bulk transfer. Asking for six bytes of header when a whole frame has
 *    arrived risks the rest of that transfer, so reads always ask for a full buffer and frames
 *    are cut out of the bytes afterwards.
 */
class UsbAoaAccessoryConnection(private val session: AoaAccessorySession) : AccessoryConnection {
    @Volatile private var closed = false

    private val lock = Object()
    private var buffer = ByteArray(INITIAL_BUFFER_SIZE) // guarded by lock
    private var head = 0                                 // first unread byte, guarded by lock
    private var tail = 0                                 // one past the last byte, guarded by lock
    private var endOfStream = false                      // guarded by lock

    init {
        Thread({ readLoop() }, "aa-aoa-reader").apply { isDaemon = true; start() }
    }

    override val isSingleMessage: Boolean get() = false

    override val isConnected: Boolean get() = !closed

    override fun connect(): Boolean = !closed

    override fun sendBlocking(buf: ByteArray, length: Int, timeout: Int): Int {
        if (closed) return -1
        return try {
            session.write(if (length == buf.size) buf else buf.copyOf(length))
            length
        } catch (e: IOException) {
            AaLog.e("UsbAoaAccessoryConnection: send failed", e)
            -1
        }
    }

    override fun recvBlocking(buf: ByteArray, length: Int, timeout: Int, readFully: Boolean): Int {
        if (closed) return -1
        val deadline = SystemClock.elapsedRealtime() + timeout
        var offset = 0
        synchronized(lock) {
            while (offset < length) {
                val unread = tail - head
                if (unread > 0) {
                    val take = minOf(unread, length - offset)
                    System.arraycopy(buffer, head, buf, offset, take)
                    head += take
                    offset += take
                    // The reader may be parked on a full buffer.
                    lock.notifyAll()
                    if (!readFully) return offset
                    continue
                }
                if (closed || endOfStream) return if (offset > 0) offset else -1
                try {
                    if (timeout > 0) {
                        val remaining = deadline - SystemClock.elapsedRealtime()
                        if (remaining <= 0) return offset
                        lock.wait(remaining)
                    } else {
                        lock.wait()
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return -1
                }
            }
        }
        return offset
    }

    override fun disconnect() {
        closed = true
        synchronized(lock) { lock.notifyAll() }
        session.close()
    }

    private fun readLoop() {
        val chunk = ByteArray(BULK_READ_SIZE)
        var total = 0L
        try {
            while (!closed) {
                val n = session.inputStream.read(chunk)
                if (n < 0) break
                if (n == 0) {
                    // A zero-length packet; back off so a driver that keeps returning them
                    // cannot turn this thread into a spin.
                    Thread.sleep(ZERO_READ_BACKOFF_MS)
                    continue
                }
                if (total == 0L) AaLog.i("UsbAoaAccessoryConnection: first $n bytes from the head unit: ${hex(chunk, minOf(n, 32))}")
                total += n
                synchronized(lock) {
                    // Nobody is draining: hold the next read until they do, rather than buffer a
                    // head unit's stream until the process runs out of memory.
                    while (!closed && tail - head + n > MAX_BUFFERED_BYTES) lock.wait()
                    if (closed) return
                    append(chunk, n)
                    lock.notifyAll()
                }
            }
        } catch (e: IOException) {
            // Closing the accessory is how a blocked read ends, so this is the normal way out.
            if (!closed) AaLog.i("UsbAoaAccessoryConnection: accessory read ended (${e.message})")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            AaLog.i("UsbAoaAccessoryConnection: reader stopped after $total bytes")
            synchronized(lock) {
                endOfStream = true
                lock.notifyAll()
            }
        }
    }

    /** Caller holds [lock]. Compacts or grows [buffer] so [n] more bytes fit after [tail]. */
    private fun append(src: ByteArray, n: Int) {
        if (tail + n > buffer.size) {
            val unread = tail - head
            val target = if (unread + n > buffer.size) ByteArray(maxOf(buffer.size * 2, unread + n)) else buffer
            System.arraycopy(buffer, head, target, 0, unread)
            buffer = target
            head = 0
            tail = unread
        }
        System.arraycopy(src, 0, buffer, tail, n)
        tail += n
    }

    private fun hex(bytes: ByteArray, length: Int): String =
        buildString(length * 3) {
            for (i in 0 until length) {
                if (i > 0) append(' ')
                append("%02x".format(bytes[i]))
            }
        }

    companion object {
        /** The accessory driver's bulk buffer size; one read never returns more than this. */
        private const val BULK_READ_SIZE = 16 * 1024
        private const val INITIAL_BUFFER_SIZE = 64 * 1024
        /** Unread bytes the reader may hold before it waits for the consumer. */
        private const val MAX_BUFFERED_BYTES = 4 * 1024 * 1024
        private const val ZERO_READ_BACKOFF_MS = 5L
    }
}
