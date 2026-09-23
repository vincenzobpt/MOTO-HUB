// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Wire-level constants and codecs for the ThinkerRide projection protocol (KOVE dashboards,
 * QR host `g.thinkerride.com`). Everything here is pure byte/string work so it stays
 * JVM-testable; sockets and BLE live in [ThinkerRideTransport] / [ThinkerRideBleLink].
 *
 * The protocol inverts EasyConn's roles: after a BLE GATT handshake the PHONE opens three TCP
 * servers and the dash connects to them. Reference: refs/KoveMirror (decoded from the working
 * iOS implementation against a KOVE 800X).
 *
 * Multi-model note: the dash never announces its panel size anywhere in this protocol. The
 * phone declares the stream geometry in [videoSizeHeader] and the dash scales, so resolution
 * is owned by [TBoxModelProfile.fallbackTBoxVideoArea] per model — [DEFAULT_VIDEO_WIDTH] x
 * [DEFAULT_VIDEO_HEIGHT] (KOVE 800X panel) only backstops a profile without geometry.
 */
object ThinkerRideProtocol {

    /** Control channel: framed JSON + binary handshake; the dash heartbeats here too. */
    const val CONTROL_PORT = 17818

    /** Dedicated keep-alive channel: the phone pulses [KEEPALIVE_PACKET] every 200 ms. */
    const val HEARTBEAT_PORT = 15457

    /** Projection channel: [videoSizeHeader] once, then raw Annex-B H.264 access units. */
    const val VIDEO_PORT = 15456

    const val HEARTBEAT_PULSE_INTERVAL_MS = 200L
    const val VIDEO_KEEPALIVE_INTERVAL_MS = 2_000L

    /** KOVE 800X TFT native portrait panel; per-model geometry belongs on the profile. */
    const val DEFAULT_VIDEO_WIDTH = 600
    const val DEFAULT_VIDEO_HEIGHT = 1024

    /**
     * Pseudo modelId recorded when a ThinkerRide pairing QR is scanned. The QR itself carries no
     * model identifier (only SSID and password), so this is how the saved profile finds its way
     * back to a ThinkerRide-family [TBoxModelProfile] on later sessions.
     */
    const val PROVISIONING_MODEL_ID = "THINKERRIDE"

    /**
     * Marks a [TBoxHost] as this family's. The host it carries is *this phone* — the dash dials
     * in on this wire — so anything that would otherwise treat a host as something to connect to
     * has to recognise it.
     */
    const val PACKAGE_TAG = "thinkerride"

    /** GATT service the dash advertises; scan filter and service discovery both key on it. */
    const val BLE_SERVICE_UUID = "0000e0ff-3c17-d293-8e48-14fe2e4da212"

    /** Phone -> dash JSON commands (write without response). */
    const val BLE_WRITE_CHARACTERISTIC_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb"

    /** Dash -> phone notifications (pairing result and status JSON). */
    const val BLE_NOTIFY_CHARACTERISTIC_UUID = "0000ffe2-0000-1000-8000-00805f9b34fb"

    /** Standard Client Characteristic Configuration descriptor, needed to enable notify. */
    const val BLE_CCC_DESCRIPTOR_UUID = "00002902-0000-1000-8000-00805f9b34fb"

    /** The dash expects each BLE handshake write spaced out; bursts get dropped by firmware. */
    const val BLE_WRITE_SPACING_MS = 150L

    const val BLE_HEARTBEAT_INTERVAL_MS = 5_000L

    /**
     * MTU asked for right after connecting. At the 23-byte default a dash notification is cut
     * into 20-byte fragments — a KOVE 800X PRO was logged splitting one `send_pairinfo` across
     * four of them (2026-08-13). [NotifyAssembler] copes either way; this just avoids the split.
     */
    const val BLE_PREFERRED_MTU = 517

    /** 6-byte keep-alive used on every TCP channel; the control server must echo it back. */
    val KEEPALIVE_PACKET = byteArrayOf(0x02, 0x01, 0x00, 0x00, 0x00, 0x00)

    /** True for the dash's 6-byte control-channel heartbeat, which must be echoed verbatim. */
    fun isKeepaliveProbe(data: ByteArray, length: Int = data.size): Boolean =
        length == 6 && data[0] == 0x02.toByte() && data[1] == 0x01.toByte() && data[2] == 0x00.toByte()

    /**
     * How many keep-alives one read carries: 1 for the plain probe, N for N of them queued back
     * to back, 0 for anything else. The dash does queue them — a KOVE logged 108 bytes that were
     * exactly 18 probes (support ID 06DD-823E-D3B9) — and a check that only knows the 6-byte
     * read left every one of those unanswered. Each queued one must be the exact packet, so a
     * real message that happens to be a multiple of six long and start like one is not mistaken.
     */
    fun keepaliveProbeCount(data: ByteArray, length: Int = data.size): Int {
        if (isKeepaliveProbe(data, length)) return 1
        if (length < 12 || length % 6 != 0) return 0
        for (offset in 0 until length step 6) {
            for (index in KEEPALIVE_PACKET.indices) {
                if (data[offset + index] != KEEPALIVE_PACKET[index]) return 0
            }
        }
        return length / 6
    }

    // ---- BLE JSON commands -------------------------------------------------------------------

    /**
     * The four writes that open the BLE session, in order, each [BLE_WRITE_SPACING_MS] apart.
     * [timestamp] is the phone's local time as `yyyy-MM-dd HH:mm:ss`.
     */
    fun bleHandshakePackets(timestamp: String): List<String> = listOf(
        """{"msg_id":27,"func":"PAIR","act":"get_pairinfo"}""",
        """{"msg_id":13}""",
        """{"msg_id":25,"msg_type":18,"msg_source":2,"language":2}""",
        """{"msg_id":11,"time":"$timestamp","tag":-1}"""
    )

    fun bleHeartbeatPacket(): String =
        """{"msg_id":25,"msg_type":24,"msg_source":2,"status":1}"""

    /**
     * The two status writes that start (status=1) or stop (status=0) projection; the second one
     * follows the first after [BLE_WRITE_SPACING_MS]. On start, the dash reacts by connecting
     * to [VIDEO_PORT].
     */
    fun bleMirrorStatusPackets(active: Boolean): List<String> {
        val status = if (active) 1 else 0
        return listOf(
            """{"msg_id":25,"msg_type":23,"msg_source":2,"status":$status}""",
            """{"msg_id":25,"msg_type":21,"msg_source":2,"status":$status}"""
        )
    }

    /** True when a notify payload is the dash confirming BLE pairing (`send_pairresult` = 1). */
    fun isPairConfirmation(notifyPayload: String): Boolean {
        // The dash firmware may concatenate several JSON objects into one notification, and
        // org.json is lenient enough to silently parse just the first of them — so a
        // whole-payload parse answers for the wrong message. KoveMirror (794de80) matches
        // these payloads on substrings instead, and so do we.
        return notifyPayload.contains("send_pairresult") &&
            PAIR_RESULT_OK_REGEX.containsMatchIn(notifyPayload)
    }

    /** `"result": 1` exactly — the lookahead keeps 10, 11, … from matching. */
    private val PAIR_RESULT_OK_REGEX = Regex("\"result\"\\s*:\\s*1(?!\\d)")

    /**
     * Turns the dash's notify stream back into whole JSON messages.
     *
     * One notification is not one message: at the default 23-byte MTU the firmware splits a
     * single object across several notifications (`{"msg_id":27,"f` / `unc":"PAIR","act"` / …),
     * and it also concatenates several objects into one. Parsing notifications as they arrive
     * therefore both misses messages and mis-reads them, so every fragment goes through here
     * first and only balanced top-level objects come out.
     */
    class NotifyAssembler {
        private val pending = StringBuilder()

        fun accept(fragment: String): List<String> {
            pending.append(fragment)
            val messages = mutableListOf<String>()
            var depth = 0
            var inString = false
            var escaped = false
            var start = -1
            var consumedTo = 0
            for (index in pending.indices) {
                val char = pending[index]
                when {
                    escaped -> escaped = false
                    char == '\\' && inString -> escaped = true
                    char == '"' -> inString = !inString
                    inString -> Unit
                    char == '{' -> {
                        if (depth == 0) start = index
                        depth++
                    }
                    char == '}' -> {
                        if (depth > 0 && --depth == 0 && start >= 0) {
                            messages += pending.substring(start, index + 1)
                            consumedTo = index + 1
                            start = -1
                        }
                    }
                }
            }
            if (consumedTo > 0) pending.delete(0, consumedTo)
            // A dash that goes silent mid-object, or sends something that never balances, must
            // not grow this buffer without bound.
            if (pending.length > MAX_PENDING_CHARS) pending.setLength(0)
            return messages
        }

        private companion object {
            const val MAX_PENDING_CHARS = 8 * 1024
        }
    }

    // ---- BLE byteCat framing -----------------------------------------------------------------

    /**
     * Some ThinkerRide firmware does not read a bare JSON write on `ffe1` at all: it expects the
     * OEM's `byteCat` frame — a fixed 104-byte envelope `FE | seq_be:2 | payload | FF`, zero
     * padded, carrying at most [BYTE_CAT_CHUNK_MAX] bytes of a checksummed body per frame.
     *
     * Off by default and enabled per profile ([TBoxModelProfile.bleUsesByteCatFraming]), because
     * the two dialects are NOT interchangeable and one of them is field-proven: a KOVE 800X
     * streamed 2226 frames on bare JSON (tester log 2026-08-15), so framing every dash would
     * break the one rider known to work. The SiQi firmware on a KOVE 450 Rally is the opposite
     * case — across 12 sessions it answered none of our writes, not even the `item:6` version
     * reply that `{"msg_id":13}` is supposed to force, while sending its own telemetry the whole
     * time (case 32e132d0, 2026-08-24). Source for the frame shape: ttarlov/kove-dash
     * `app/.../net/ByteCat.kt` + `proto-poc/PROTOCOL.md`, itself decompiled from the OEM app.
     */
    const val BYTE_CAT_FRAME_LENGTH = 104
    const val BYTE_CAT_CHUNK_MAX = 100
    const val BYTE_CAT_HEAD = 0xFE.toByte()
    const val BYTE_CAT_TAIL = 0xFF.toByte()

    /**
     * The OEM's `getCRCCode`: a mod-256 sum of [body] split into two nibbles, each OR'd with
     * 0x80 so neither byte can collide with [BYTE_CAT_HEAD] or [BYTE_CAT_TAIL] inside a frame.
     * Not a CRC in any real sense, but it is what the dash checks.
     */
    fun byteCatChecksum(body: ByteArray): ByteArray {
        var total = 0
        for (byte in body) total = (total + (byte.toInt() and 0xFF)) and 0xFF
        return byteArrayOf(
            (((total and 0xF0) ushr 4) or 0x80).toByte(),
            ((total and 0x0F) or 0x80).toByte()
        )
    }

    /**
     * One JSON command as the 104-byte frames the dash reads, sequence numbers starting at
     * [startSeq] and advancing **per frame** — the dash's loss detector wants one contiguous
     * run per connection, so a caller must advance its own counter by the returned size rather
     * than stamp a whole message with one number.
     *
     * The body is the JSON, its NUL terminator replaced by the two checksum bytes, and the NUL
     * put back at the end — the reference's `cat()` does exactly this, and the dash rejects any
     * other arrangement, so it is copied rather than tidied.
     */
    fun byteCatFrames(json: String, startSeq: Int): List<ByteArray> {
        val body = json.toByteArray(StandardCharsets.UTF_8) + 0x00.toByte()
        val checksum = byteCatChecksum(body)
        val payload = ByteArray(body.size + 2)
        body.copyInto(payload)
        payload[body.size - 1] = checksum[0]
        payload[body.size] = checksum[1]

        val frames = mutableListOf<ByteArray>()
        var cursor = 0
        var sequence = startSeq
        while (cursor < payload.size) {
            val length = minOf(payload.size - cursor, BYTE_CAT_CHUNK_MAX)
            val frame = ByteArray(BYTE_CAT_FRAME_LENGTH)
            frame[0] = BYTE_CAT_HEAD
            frame[1] = ((sequence shr 8) and 0xFF).toByte()
            frame[2] = (sequence and 0xFF).toByte()
            payload.copyInto(frame, destinationOffset = 3, startIndex = cursor, endIndex = cursor + length)
            frame[3 + length] = BYTE_CAT_TAIL
            frames += frame
            cursor += length
            sequence++
        }
        return frames
    }

    // ---- Control channel framing -------------------------------------------------------------

    /** Frames one JSON command for the control channel: `EE FD` + 4-byte BE length + body + `FF`. */
    fun frameControlJson(json: String): ByteArray {
        val body = json.toByteArray(StandardCharsets.UTF_8)
        return ByteBuffer.allocate(2 + 4 + body.size + 1)
            .put(0xEE.toByte())
            .put(0xFD.toByte())
            .putInt(body.size)
            .put(body)
            .put(0xFF.toByte())
            .array()
    }

    /** Sent as soon as the dash opens the control connection. */
    fun controlOpeningQuery(): ByteArray = frameControlJson("""{"msg_id":27,"func":"TUC","act":"GET"}""")

    /**
     * The dash's answer to [controlOpeningQuery]: `tucs` is its activation flag, and the OEM
     * stack gates projection on it. `tucs != 1` means the firmware refuses to open the video
     * channel no matter what we send it - the dash completes the BLE pairing, acknowledges
     * mirror-start, and then simply never dials [VIDEO_PORT]. That is indistinguishable from a
     * wedged dash unless this field is read, which is why an unactivated rider used to be told
     * to power-cycle a dash that was working exactly as designed.
     *
     * Null when the payload is not a TUC reply or carries no `tucs` at all; only an explicit
     * value is worth acting on, and older firmware that omits the field is not "unactivated".
     */
    fun parseActivationFlag(payload: String): Int? {
        // Substring work rather than JSON parsing: the control channel hands this up with its
        // framing bytes still attached (`EE FD <len> … FF`), so the payload is not valid JSON
        // and a parser would reject the very reply we need.
        if (!payload.contains("\"func\":\"TUC\"")) return null
        val key = payload.indexOf(""""tucs"""")
        if (key < 0) return null
        var index = key + 6
        // Skip the colon and any whitespace the firmware pads with.
        while (index < payload.length && (payload[index] == ':' || payload[index].isWhitespace())) index++
        val start = index
        while (index < payload.length && payload[index].isDigit()) index++
        return if (index > start) payload.substring(start, index).toIntOrNull() else null
    }

    /** The activation value the dash must report before it will ever open [VIDEO_PORT]. */
    const val ACTIVATED_TUCS = 1

    /**
     * The binary command burst sent 100 ms after the dash's first control packet, followed by
     * the two [controlNaviQueries]. Fixed opcodes from the reference capture; the 262-byte
     * command 0x12 carries a rider-account identity string zero-padded to 256 bytes, which the
     * dash logs but does not validate.
     */
    fun controlHandshake(identity: String = CONTROL_IDENTITY): ByteArray {
        val identityBytes = identity.toByteArray(StandardCharsets.UTF_8).copyOf(256)
        return ByteBuffer.allocate(6 + 10 + 6 + 256 + 6 + 6)
            .put(byteArrayOf(0x01, 0x01, 0x00, 0x00, 0x00, 0x00))
            .put(byteArrayOf(0x01, 0x17, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00, 0x02))
            .put(byteArrayOf(0x01, 0x12, 0x00, 0x00, 0x01, 0x00))
            .put(identityBytes)
            .put(byteArrayOf(0x01, 0x0E, 0x00, 0x00, 0x00, 0x00))
            .put(byteArrayOf(0x01, 0x11, 0x00, 0x00, 0x00, 0x00))
            .array()
    }

    fun controlNaviQueries(): List<ByteArray> = listOf(
        frameControlJson("""{"msg_id":27,"func":"INSIDENAVI","query":2}"""),
        frameControlJson("""{"msg_id":27,"func":"INSIDENAVI","query":1}""")
    )

    private const val CONTROL_IDENTITY = "rider@moto-hub.app"

    // ---- Video channel -----------------------------------------------------------------------

    /**
     * The 69-byte prologue of the video connection. Offset 1 carries the literal OS name
     * `android` (the dash sizes its scaling buffer from it); offsets 65..68 carry the stream
     * width and height as big-endian 16-bit values. Everything else is zero.
     */
    fun videoSizeHeader(width: Int, height: Int): ByteArray {
        require(width in 1..0xFFFF && height in 1..0xFFFF) { "Invalid video size ${width}x$height" }
        val header = ByteArray(69)
        val name = "android".toByteArray(StandardCharsets.US_ASCII)
        name.copyInto(header, destinationOffset = 1)
        header[65] = ((width shr 8) and 0xFF).toByte()
        header[66] = (width and 0xFF).toByte()
        header[67] = ((height shr 8) and 0xFF).toByte()
        header[68] = (height and 0xFF).toByte()
        return header
    }

    /**
     * Converts one AVCC access unit (4-byte big-endian NAL lengths, the shape AvcEncoder emits)
     * to the Annex-B stream the dash decoder expects. Payloads already carrying a start code are
     * passed through untouched.
     */
    fun annexBFromAvcc(accessUnit: ByteArray): ByteArray {
        if (startsWithAnnexBCode(accessUnit)) return accessUnit
        val out = ByteBuffer.allocate(accessUnit.size + 16)
        var cursor = 0
        var converted: ByteBuffer = out
        while (cursor + 4 <= accessUnit.size) {
            val nalLength = ((accessUnit[cursor].toInt() and 0xFF) shl 24) or
                ((accessUnit[cursor + 1].toInt() and 0xFF) shl 16) or
                ((accessUnit[cursor + 2].toInt() and 0xFF) shl 8) or
                (accessUnit[cursor + 3].toInt() and 0xFF)
            if (nalLength <= 0 || cursor + 4 + nalLength > accessUnit.size) {
                // Not a well-formed AVCC unit after all; ship the original rather than corrupt it.
                return accessUnit
            }
            if (converted.remaining() < 4 + nalLength) {
                converted = grow(converted, 4 + nalLength)
            }
            converted.put(ANNEX_B_START_CODE)
            converted.put(accessUnit, cursor + 4, nalLength)
            cursor += 4 + nalLength
        }
        if (cursor != accessUnit.size) return accessUnit
        return ByteArray(converted.position()).also { converted.flip(); converted.get(it) }
    }

    private fun startsWithAnnexBCode(data: ByteArray): Boolean =
        (data.size >= 4 && data[0] == ZERO && data[1] == ZERO && data[2] == ZERO && data[3] == ONE) ||
            (data.size >= 3 && data[0] == ZERO && data[1] == ZERO && data[2] == ONE)

    private fun grow(buffer: ByteBuffer, needed: Int): ByteBuffer {
        val expanded = ByteBuffer.allocate(maxOf(buffer.capacity() * 2, buffer.capacity() + needed))
        buffer.flip()
        expanded.put(buffer)
        return expanded
    }

    private val ANNEX_B_START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)
    private const val ZERO = 0x00.toByte()
    private const val ONE = 0x01.toByte()
}
