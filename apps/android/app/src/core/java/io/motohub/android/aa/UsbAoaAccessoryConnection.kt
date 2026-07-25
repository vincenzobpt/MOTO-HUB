package io.motohub.android.aa

import android.os.SystemClock
import io.motohub.android.externaldisplay.AoaAccessorySession
import java.io.IOException

/**
 * [AccessoryConnection] over a physical USB AOA accessory (the external head unit), instead of
 * the loopback socket [SocketAccessoryConnection] uses to talk to Google's own Android Auto app.
 *
 * FileInputStream has no native read timeout, so recvBlocking polls [available] in short sleeps
 * until data shows up or the deadline passes - the same workaround used elsewhere for plain fd
 * streams that don't support a socket-style timeout.
 */
class UsbAoaAccessoryConnection(private val session: AoaAccessorySession) : AccessoryConnection {
    @Volatile private var closed = false

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
        return try {
            var offset = 0
            val deadline = SystemClock.elapsedRealtime() + timeout
            while (offset < length) {
                if (timeout > 0 && SystemClock.elapsedRealtime() >= deadline) {
                    return offset
                }
                if (session.inputStream.available() <= 0) {
                    Thread.sleep(POLL_INTERVAL_MS)
                    continue
                }
                val n = session.inputStream.read(buf, offset, length - offset)
                if (n < 0) return -1
                offset += n
                if (!readFully) return offset
            }
            offset
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            -1
        } catch (e: IOException) {
            AaLog.e("UsbAoaAccessoryConnection: receive failed", e)
            -1
        }
    }

    override fun disconnect() {
        closed = true
        session.close()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 10L
    }
}
