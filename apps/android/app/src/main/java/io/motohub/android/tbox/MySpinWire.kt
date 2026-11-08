// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/** A malformed or unsupported mySPIN packet. Always fatal for the session that read it. */
class MySpinProtocolException(message: String) : IOException(message)

/**
 * Packet framing of the Bosch mySPIN TCP session, the projection protocol behind the Ducati
 * Multistrada V4 "Ducati Connect" dash. Pure byte work, JVM-testable; the socket lives elsewhere.
 *
 * Every packet is `type u8 | metadata i32 | length i32 | payload`, and every integer on the wire
 * is little-endian in both directions; the test pins that byte order.
 *
 * There is no sync marker, so a corrupt header cannot be resynchronised: [read] throws and the
 * session ends.
 */
object MySpinWire {

    /** Largest payload either side accepts. */
    const val MAX_PAYLOAD = 8 * 1024 * 1024

    const val HEADER_SIZE = 9

    class Packet(val type: Int, val payload: ByteArray, val metadata: Int = 0) {
        override fun toString(): String = "Packet(type=$type, ${payload.size} bytes, metadata=$metadata)"
    }

    fun encode(packet: Packet): ByteArray {
        require(packet.type in 0..0xFF) { "packet type ${packet.type} does not fit a byte" }
        require(packet.payload.size <= MAX_PAYLOAD) { "payload of ${packet.payload.size} bytes is too large" }
        return Writer()
            .u8(packet.type)
            .i32(packet.metadata)
            .i32(packet.payload.size)
            .bytes(packet.payload)
            .build()
    }

    /** The next packet, or null if the stream ended cleanly before one started. */
    fun read(input: InputStream): Packet? {
        val type = input.read()
        if (type < 0) return null
        val header = Reader(readFully(input, HEADER_SIZE - 1))
        val metadata = header.i32()
        val length = header.i32()
        if (length !in 0..MAX_PAYLOAD) {
            throw MySpinProtocolException("mySPIN packet type $type announces an invalid length $length")
        }
        return Packet(type, readFully(input, length), metadata)
    }

    private fun readFully(input: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var filled = 0
        while (filled < size) {
            val count = input.read(buffer, filled, size - filled)
            if (count < 0) throw EOFException("mySPIN packet truncated after $filled of $size bytes")
            filled += count
        }
        return buffer
    }

    /** Little-endian field reader over one payload. */
    class Reader(private val bytes: ByteArray) {
        var position = 0
            private set

        val remaining: Int get() = bytes.size - position

        fun u8(): Int {
            if (remaining < 1) throw MySpinProtocolException("mySPIN payload truncated at byte $position")
            return bytes[position++].toInt() and 0xFF
        }

        fun u16(): Int {
            val low = u8()
            return low or (u8() shl 8)
        }

        fun i32(): Int {
            val low = u16()
            return low or (u16() shl 16)
        }

        /** An i32 the protocol treats as unsigned (vehicle-data keys, timestamps). */
        fun u32(): Long = i32().toLong() and 0xFFFF_FFFFL

        fun bytes(count: Int): ByteArray {
            if (count < 0 || count > remaining) {
                throw MySpinProtocolException("mySPIN field of $count bytes at $position overruns the payload")
            }
            return bytes.copyOfRange(position, position + count).also { position += count }
        }

        /** `encoding u8 | length u16 | bytes`: 1 is UTF-8, 2 is ISO-8859-1. */
        fun string(): String {
            val encoding = u8()
            val raw = bytes(u16())
            return when (encoding) {
                1 -> String(raw, Charsets.UTF_8)
                2 -> String(raw, Charsets.ISO_8859_1)
                else -> throw MySpinProtocolException("mySPIN string encoding $encoding is not supported")
            }
        }
    }

    /** Little-endian field writer for one payload. */
    class Writer {
        private val out = ByteArrayOutputStream()

        fun u8(value: Int): Writer = apply { out.write(value and 0xFF) }

        fun u16(value: Int): Writer = apply {
            u8(value)
            u8(value ushr 8)
        }

        fun i32(value: Int): Writer = apply {
            u16(value)
            u16(value ushr 16)
        }

        fun i64(value: Long): Writer = apply {
            i32(value.toInt())
            i32((value ushr 32).toInt())
        }

        fun bytes(value: ByteArray): Writer = apply { out.write(value) }

        /** Always written as UTF-8. */
        fun string(value: String): Writer = apply {
            val raw = value.toByteArray(Charsets.UTF_8)
            require(raw.size <= 0xFFFF) { "string of ${raw.size} bytes does not fit a u16 length" }
            u8(1)
            u16(raw.size)
            bytes(raw)
        }

        fun build(): ByteArray = out.toByteArray()
    }
}
