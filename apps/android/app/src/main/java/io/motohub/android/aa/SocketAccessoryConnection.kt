// Adapted from headunit-revived (AGPLv3): connection/SocketAccessoryConnection.kt
// Trimmed to a pre-connected loopback socket (self-mode): no network binding, no outbound
// connect() — Android Auto (gearhead) connects IN to our WirelessServer on 127.0.0.1:5288.
package io.motohub.android.aa

import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException

class SocketAccessoryConnection(private val socket: Socket) : AccessoryConnection {
    private var output: OutputStream? = null
    private var input: DataInputStream? = null

    init {
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
        } catch (_: Exception) {}
        if (socket.isConnected) {
            try {
                input = DataInputStream(socket.getInputStream())
                output = socket.getOutputStream()
            } catch (e: IOException) {
                AaLog.e("Failed to get streams from pre-connected socket", e)
            }
        }
    }

    /** HUR loopback/Nearby sockets read one full AAP message at a time. */
    override val isSingleMessage: Boolean get() = true

    override val isConnected: Boolean get() = socket.isConnected && !socket.isClosed

    override fun connect(): Boolean = socket.isConnected

    override fun sendBlocking(buf: ByteArray, length: Int, timeout: Int): Int {
        val out = output ?: return -1
        return try {
            synchronized(out) {
                out.write(buf, 0, length)
                out.flush()
            }
            length
        } catch (e: IOException) {
            AaLog.e("send failed", e)
            -1
        }
    }

    override fun recvBlocking(buf: ByteArray, length: Int, timeout: Int, readFully: Boolean): Int {
        val inp = input ?: return -1
        return try {
            try { socket.soTimeout = timeout } catch (_: Exception) {}
            if (readFully) {
                inp.readFully(buf, 0, length)
                length
            } else {
                inp.read(buf, 0, length)
            }
        } catch (e: SocketTimeoutException) {
            0
        } catch (e: IOException) {
            -1
        }
    }

    override fun disconnect() {
        try { socket.close() } catch (_: IOException) {}
        input = null
        output = null
    }
}
