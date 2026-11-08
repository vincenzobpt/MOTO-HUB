// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import io.motohub.android.tbox.MySpinWire.Packet
import io.motohub.android.tbox.MySpinWire.Writer
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * A mySPIN dash on loopback, scripted by the test: it speaks the dash side of the protocol.
 * Defaults are the Multistrada V4 test bike.
 */
class FakeMySpinDash(val width: Int = 848, val height: Int = 304) : Closeable {

    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private lateinit var socket: Socket
    private lateinit var input: BufferedInputStream
    private lateinit var output: BufferedOutputStream

    /** Everything the phone sent, in order. */
    val received = mutableListOf<Packet>()

    /** The phone's end of the connection; the dash side is accepted at once. */
    fun connectPhone(): Socket {
        val phone = Socket(InetAddress.getLoopbackAddress(), server.localPort)
        socket = server.accept().apply { soTimeout = READ_TIMEOUT_MS }
        input = BufferedInputStream(socket.getInputStream())
        output = BufferedOutputStream(socket.getOutputStream())
        return phone
    }

    fun send(type: Int, writer: Writer = Writer()) {
        output.write(MySpinWire.encode(Packet(type, writer.build())))
        output.flush()
    }

    /** Reads until a packet of [type] arrives, keeping everything read on the way. */
    fun expect(type: Int): Packet {
        repeat(MAX_PACKETS_SKIPPED) {
            val packet = MySpinWire.read(input)
                ?: throw AssertionError("the phone closed the session while the dash waited for type $type")
            received += packet
            if (packet.type == type) return packet
        }
        throw AssertionError("no packet of type $type in $MAX_PACKETS_SKIPPED")
    }

    /** Version 1.2, ClientInit, ClientReady: leaves the session READY. */
    fun handshake() {
        send(MySpinProtocol.TYPE_CLIENT_VERSION, Writer().u16(1).u16(2).u16(0))
        expect(MySpinProtocol.TYPE_VERSION_ACCEPTED)
        send(MySpinProtocol.TYPE_CLIENT_INIT, clientInit())
        expect(MySpinProtocol.TYPE_SERVER_READY)
        send(MySpinProtocol.TYPE_CLIENT_READY, Writer().u8(1))
        expect(MySpinProtocol.TYPE_PHONE_TIME)
    }

    fun requestFrame() = send(
        MySpinProtocol.TYPE_FRAME_REQUEST,
        Writer().u16(0).u16(0).u16(width).u16(height).u8(1)
    )

    /** The picture inside a type 26 packet of a 1.2 session. */
    fun frameBytes(packet: Packet): ByteArray {
        val reader = MySpinWire.Reader(packet.payload)
        reader.u8()
        repeat(4) { reader.u16() }
        reader.u8()
        reader.u8()
        reader.i32()
        repeat(3) { reader.u8() }
        return reader.bytes(reader.i32())
    }

    fun pressKey(key: MySpinProtocol.Key, action: MySpinProtocol.KeyAction) = send(
        MySpinProtocol.TYPE_CUSTOM_DATA,
        Writer().u8(MySpinProtocol.CUSTOM_KIND_KEY).u16(action.wire).u16(1).u8(key.wire)
    )

    private fun clientInit() = Writer().string("Ducati TFT").u16(width).u16(height).u16(150).u16(54).i32(0)
        .u8(2).u8(2).i32(MySpinProtocol.COMPRESSION_JPEG).u8(80).i32(MySpinProtocol.COMPRESSION_JPEG)
        .u8(0).u8(0)

    override fun close() {
        if (::socket.isInitialized) runCatching { socket.close() }
        runCatching { server.close() }
    }

    private companion object {
        const val READ_TIMEOUT_MS = 3_000
        const val MAX_PACKETS_SKIPPED = 200
    }
}
