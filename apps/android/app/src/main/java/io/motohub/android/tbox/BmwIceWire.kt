// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * The few pieces of the ZeroC Ice wire protocol the BMW Motorrad TFT speaks on Bluetooth, written
 * from the public protocol description, never from Ice's own (GPL) sources. Pure byte work,
 * JVM-testable.
 *
 * Protocol 1.0 headers, parameters in encoding 1.1 (the BMW app's default), little-endian
 * throughout. Only what the MySpinICE dialogue needs: requests, replies, validate/close, strings,
 * longs, ints, byte sequences, identities, endpoint-less proxies and `ConnectedRide::MySpinICEmsg`.
 */
object BmwIceWire {
    const val HEADER_SIZE = 14
    const val REQUEST = 0
    const val REPLY = 2
    const val VALIDATE_CONNECTION = 3
    const val CLOSE_CONNECTION = 4

    // Reply status values.
    const val REPLY_OK = 0
    const val REPLY_USER_EXCEPTION = 1
    const val REPLY_OBJECT_NOT_EXIST = 2
    const val REPLY_FACET_NOT_EXIST = 3
    const val REPLY_OPERATION_NOT_EXIST = 4

    private val MAGIC = byteArrayOf('I'.code.toByte(), 'c'.code.toByte(), 'e'.code.toByte(), 'P'.code.toByte())

    data class Identity(val name: String, val category: String = "")

    class Writer {
        private val out = ByteArrayOutputStream()

        fun byte(value: Int) = apply { out.write(value and 0xFF) }
        fun bool(value: Boolean) = byte(if (value) 1 else 0)
        fun short(value: Int) = byte(value).byte(value shr 8)
        fun int(value: Int) = apply { repeat(4) { out.write((value shr (8 * it)) and 0xFF) } }
        fun long(value: Long) = apply { repeat(8) { out.write(((value shr (8 * it)) and 0xFF).toInt()) } }
        fun size(value: Int) = if (value < 255) byte(value) else byte(255).int(value)
        fun raw(bytes: ByteArray) = apply { out.write(bytes) }
        fun bytes(bytes: ByteArray) = size(bytes.size).raw(bytes)
        fun string(value: String) = bytes(value.toByteArray(StandardCharsets.UTF_8))
        fun strings(values: List<String>) = apply { size(values.size); values.forEach { string(it) } }
        fun identity(identity: Identity) = string(identity.name).string(identity.category)

        /** An encapsulation in encoding 1.1; its size counts its own 6-byte header. */
        fun encapsulation(body: Writer.() -> Unit = {}) = apply {
            val inner = Writer().apply(body).toByteArray()
            int(inner.size + 6).byte(1).byte(1).raw(inner)
        }

        /**
         * A twoway proxy with no endpoints: the TFT can only reach it over the connection it
         * arrived on, which is the only way it can reach the BMW app's own callback too, since
         * Android reports every phone's Bluetooth address as 02:00:00:00:00:00.
         */
        fun proxy(identity: Identity?) = apply {
            if (identity == null) {
                identity(Identity(""))
                return@apply
            }
            identity(identity)
            strings(emptyList()) // facet
            byte(0) // mode: twoway
            bool(false) // secure
            byte(1).byte(0) // protocol 1.0
            byte(1).byte(1) // encoding 1.1
            size(0) // no endpoints
            string("") // adapter id
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    class Reader(private val data: ByteArray, private var position: Int = 0, private val end: Int = data.size) {
        val remaining: Int get() = end - position

        private fun need(count: Int) {
            if (count < 0 || position + count > end) throw IOException("Ice message truncated: need $count byte(s), $remaining left")
        }

        fun byte(): Int { need(1); return data[position++].toInt() and 0xFF }
        fun bool(): Boolean = byte() != 0
        fun short(): Int = byte() or (byte() shl 8)
        fun int(): Int { var v = 0; repeat(4) { v = v or (byte() shl (8 * it)) }; return v }
        fun long(): Long { var v = 0L; repeat(8) { v = v or (byte().toLong() shl (8 * it)) }; return v }
        fun size(): Int { val first = byte(); return if (first < 255) first else int() }
        fun raw(count: Int): ByteArray { need(count); return data.copyOfRange(position, position + count).also { position += count } }
        fun bytes(): ByteArray = raw(size())
        fun string(): String = String(bytes(), StandardCharsets.UTF_8)
        fun strings(): List<String> = List(size()) { string() }
        fun identity(): Identity = Identity(string(), string())

        /** The payload of an encapsulation, with its encoding; position moves past it. */
        fun encapsulation(): Pair<Reader, Int> {
            val size = int()
            if (size < 6) throw IOException("Ice encapsulation size $size is too small")
            val major = byte()
            val minor = byte()
            need(size - 6)
            val payload = Reader(data, position, position + size - 6)
            position += size - 6
            return payload to (major * 10 + minor)
        }

        /** Reads a proxy written in [encoding] (10 or 11) and keeps only its identity; null for a null proxy. */
        fun proxy(encoding: Int): Identity? {
            val identity = identity()
            if (identity.name.isEmpty()) return null
            strings() // facet
            byte() // mode
            bool() // secure
            if (encoding >= 11) raw(4) // protocol and encoding versions
            val endpoints = size()
            if (endpoints == 0) {
                string() // adapter id
            } else {
                repeat(endpoints) {
                    short() // endpoint type
                    val encapsulationSize = int()
                    raw(encapsulationSize - 4)
                }
            }
            return identity
        }
    }

    // --- framing -----------------------------------------------------------------------------

    fun frame(type: Int, body: ByteArray = ByteArray(0)): ByteArray = Writer()
        .raw(MAGIC).byte(1).byte(0).byte(1).byte(0) // protocol 1.0, header encoding 1.0
        .byte(type).byte(0) // no compression
        .int(HEADER_SIZE + body.size)
        .raw(body)
        .toByteArray()

    /** The whole frame's size, read from a 14-byte header; refuses anything that is not Ice. */
    fun frameSize(header: ByteArray): Int {
        if (header.size < HEADER_SIZE || !header.copyOfRange(0, 4).contentEquals(MAGIC)) {
            throw IOException("not an Ice message: ${header.take(8).joinToString(" ") { "%02X".format(it) }}")
        }
        if (header[9].toInt() != 0) throw IOException("compressed Ice messages are not supported")
        val size = Reader(header, 10).int()
        if (size < HEADER_SIZE || size > MAX_FRAME) throw IOException("Ice message size $size is out of range")
        return size
    }

    fun request(requestId: Int, target: Identity, operation: String, params: Writer.() -> Unit = {}): ByteArray =
        frame(
            REQUEST,
            Writer().int(requestId).identity(target).strings(emptyList()).string(operation)
                .byte(0) // mode: Normal
                .size(0) // empty context
                .encapsulation(params)
                .toByteArray()
        )

    fun reply(requestId: Int, result: Writer.() -> Unit = {}): ByteArray =
        frame(REPLY, Writer().int(requestId).byte(REPLY_OK).encapsulation(result).toByteArray())

    fun replyNotExist(requestId: Int, status: Int, target: Identity, facet: List<String>, operation: String): ByteArray =
        frame(REPLY, Writer().int(requestId).byte(status).identity(target).strings(facet).string(operation).toByteArray())

    sealed interface Message {
        data class Request(
            val requestId: Int,
            val target: Identity,
            val facet: List<String>,
            val operation: String,
            val params: Reader,
            val encoding: Int
        ) : Message

        /** [result] is the encapsulation's payload for OK and user exceptions, null otherwise. */
        data class Reply(val requestId: Int, val status: Int, val result: Reader?, val encoding: Int) : Message

        data object ValidateConnection : Message
        data object CloseConnection : Message
        data class Other(val type: Int) : Message
    }

    fun parse(frame: ByteArray): Message {
        val size = frameSize(frame)
        if (frame.size != size) throw IOException("Ice frame is ${frame.size} bytes, header says $size")
        val body = Reader(frame, HEADER_SIZE)
        return when (frame[8].toInt()) {
            REQUEST -> {
                val requestId = body.int()
                val target = body.identity()
                val facet = body.strings()
                val operation = body.string()
                body.byte() // mode
                repeat(body.size()) { body.string(); body.string() } // context
                val (params, encoding) = body.encapsulation()
                Message.Request(requestId, target, facet, operation, params, encoding)
            }
            REPLY -> {
                val requestId = body.int()
                val status = body.byte()
                if (status == REPLY_OK || status == REPLY_USER_EXCEPTION) {
                    val (result, encoding) = body.encapsulation()
                    Message.Reply(requestId, status, result, encoding)
                } else {
                    Message.Reply(requestId, status, null, 11)
                }
            }
            VALIDATE_CONNECTION -> Message.ValidateConnection
            CLOSE_CONNECTION -> Message.CloseConnection
            else -> Message.Other(frame[8].toInt())
        }
    }

    fun hex(bytes: ByteArray, limit: Int = 96): String =
        bytes.take(limit).joinToString(" ") { "%02X".format(it) } + if (bytes.size > limit) " …(${bytes.size} bytes)" else ""

    // --- ConnectedRide::MySpinICEmsg -----------------------------------------------------------

    /** `ConnectedRide::MessageID`, written as an enum with 12 as its largest value. */
    enum class MessageId(val wire: Int) {
        HELLO(0), HELLO_ACK(1), COUNTRY_CODE(2), WLAN_CREDENTIALS_AP(3), WLAN_CREDENTIALS_P2P(4),
        CLUSTER_WLAN_STATE(5), PHONE_WLAN_STATE(6), CLUSTER_WLAN_ERROR(7), PHONE_WLAN_ERROR(8),
        PHONE_MYSPIN_STATE(9), PHONE_TYPE(10), MMC_FOCUS(11), BUNDLE_ID(12);

        companion object {
            fun of(wire: Int): MessageId? = entries.firstOrNull { it.wire == wire }
        }
    }

    data class MySpinIceMsg(
        val msgId: MessageId = MessageId.HELLO,
        val sessionId: String = "",
        val val0: Long = 0, val val1: Long = 0, val val2: Long = 0, val val3: Long = 0, val val4: Long = 0,
        val str0: String = "", val str1: String = "", val str2: String = "", val str3: String = "", val str4: String = ""
    ) {
        fun write(writer: Writer) {
            writer.size(msgId.wire) // an enum of at most 12 is one byte in both encodings
            writer.string(sessionId)
            listOf(val0, val1, val2, val3, val4).forEach { writer.long(it) }
            listOf(str0, str1, str2, str3, str4).forEach { writer.string(it) }
        }

        companion object {
            fun read(reader: Reader): MySpinIceMsg {
                val wire = reader.size()
                val id = MessageId.of(wire) ?: throw IOException("MySpinICE message id $wire is out of range")
                val session = reader.string()
                val values = List(5) { reader.long() }
                val strings = List(5) { reader.string() }
                return MySpinIceMsg(
                    id, session, values[0], values[1], values[2], values[3], values[4],
                    strings[0], strings[1], strings[2], strings[3], strings[4]
                )
            }
        }
    }

    /** `name=value` inside a credentials field, up to the next space, as the BMW app reads it. */
    fun field(text: String, name: String): String? =
        text.substringAfter("$name=", "").substringBefore(" ").takeIf { text.contains("$name=") }

    private const val MAX_FRAME = 1 shl 20
}
