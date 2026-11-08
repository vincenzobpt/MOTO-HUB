// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * Listens for the mySPIN dash's UDP announcement after the phone has joined its Wi-Fi. The socket
 * opens in the constructor, so nothing broadcast between construction and [await] is lost.
 *
 * [bindToNetwork] lets the Android side pin the socket to the dash network before binding; the
 * tests leave it empty and use port 0.
 */
class MySpinAnnouncementListener(
    port: Int = MySpinDiscovery.PORT,
    bindToNetwork: (DatagramSocket) -> Unit = {}
) : Closeable {

    private val socket = DatagramSocket(null as InetSocketAddress?).apply {
        reuseAddress = true
        broadcast = true
        bindToNetwork(this)
        bind(InetSocketAddress(port))
    }

    @Volatile private var closed = false

    val localPort: Int get() = socket.localPort

    /** The first usable announcement within [timeoutMillis], or null. Garbage datagrams are skipped. */
    fun await(timeoutMillis: Long): MySpinDiscovery.Endpoint? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        val buffer = ByteArray(MySpinDiscovery.MAX_ANNOUNCEMENT_BYTES + 1)
        while (!closed) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return null
            // Short slices so close() from another thread ends the wait promptly.
            socket.soTimeout = remaining.coerceAtMost(SLICE_MS).toInt().coerceAtLeast(1)
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (slice: SocketTimeoutException) {
                continue
            }
            val source = packet.address?.hostAddress ?: continue
            MySpinDiscovery.parse(packet.data.copyOf(packet.length), source)?.let { return it }
        }
        return null
    }

    override fun close() {
        closed = true
        socket.close()
    }

    private companion object {
        const val SLICE_MS = 500L
    }
}
