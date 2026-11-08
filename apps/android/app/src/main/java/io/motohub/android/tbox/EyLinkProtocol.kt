// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

/**
 * Byte format of the EyLink (Erya) projection stack, found on the Kove 350 RR.
 *
 * Written clean-room from the maintainer's EyLink protocol notes, which is where every fact
 * here comes from and where its provenance is recorded. Nothing in this file has met a real dash
 * yet: the note's open questions are left as they are, and the transport logs what it cannot
 * explain instead of guessing.
 *
 * Two sockets, both opened by the phone towards the dash's Wi-Fi Direct group owner:
 *  - [CONTROL_PORT] carries a JSON heartbeat inside an `AF BB CC 0F` frame;
 *  - [VIDEO_PORT] carries every `AA BB CC <func>` frame: the mirror handshake, the geometry and
 *    the H.264 access units, pushed without any acknowledgement.
 *
 * All multi-byte fields are little-endian. Pure functions only, so all of it is unit-testable.
 */
object EyLinkProtocol {

    /**
     * Pseudo modelId the EyLink QR dialect stamps on a saved motorcycle, which routes it to
     * [TBoxModelProfile.KOVE_350RR_EYLINK]. Not something a dash reports; it is the value of the
     * code's own `productid=` field.
     */
    const val PROVISIONING_MODEL_ID = "EYLINK"

    /** The group owner address on the one dash reported. The link's real gateway wins. */
    const val DEFAULT_HOST = "192.168.13.1"
    const val CONTROL_PORT = 11113
    const val VIDEO_PORT = 11111

    const val HEARTBEAT_INTERVAL_MS = 500L

    /** How often [widthHeightFrame] is repeated. Whether the dash needs the repeat is unknown. */
    const val WIDTH_HEIGHT_INTERVAL_MS = 2_000L

    const val FUNC_VIDEO_DATA = 0x00
    const val FUNC_MIRROR_START = 0x04
    const val FUNC_MIRROR_STOP = 0x05
    const val FUNC_WIDTH_HEIGHT = 0x06

    const val HEADER_SIZE = 12

    /** `"OK"` + four LE16 sizes + u8 fps + LE16 kbps. */
    const val OK_REPLY_SIZE = 13

    /**
     * The source size MIRROR_START announces. It is the official client's own phone screen,
     * replayed verbatim by the only implementation that has painted a dash; whether a dash reads
     * it at all is an open question, so it is not replaced with this phone's size until a bench
     * session says that is safe.
     */
    const val DEFAULT_SOURCE_WIDTH = 1152
    const val DEFAULT_SOURCE_HEIGHT = 2560

    const val HEARTBEAT_JSON = """{"EYLINKheart":"Mapheart"}"""

    private val CONTROL_MAGIC = byteArrayOf(0xAF.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0x0F)
    private val VIDEO_MAGIC = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())

    /** What the dash answers MIRROR_START with: its panel, and the stream it wants. */
    data class Capabilities(
        val portraitWidth: Int,
        val portraitHeight: Int,
        val landscapeWidth: Int,
        val landscapeHeight: Int,
        val frameRate: Int,
        val bitRateKbps: Int
    ) {
        /**
         * The area to encode. Landscape, because that is the panel the one known dash has (it
         * reports the same size twice); portrait only when landscape came back empty.
         */
        val videoWidth: Int get() = if (landscapeWidth > 0 && landscapeHeight > 0) landscapeWidth else portraitWidth
        val videoHeight: Int get() = if (landscapeWidth > 0 && landscapeHeight > 0) landscapeHeight else portraitHeight
    }

    fun heartbeatFrame(): ByteArray = controlFrame(HEARTBEAT_JSON)

    /** `AF BB CC 0F`, four zero bytes, LE32 length, UTF-8 JSON. */
    fun controlFrame(json: String): ByteArray {
        val payload = json.toByteArray(Charsets.UTF_8)
        val frame = ByteArray(HEADER_SIZE + payload.size)
        CONTROL_MAGIC.copyInto(frame, 0)
        putLe32(frame, 8, payload.size.toLong())
        payload.copyInto(frame, HEADER_SIZE)
        return frame
    }

    fun mirrorStartFrame(
        sourceWidth: Int = DEFAULT_SOURCE_WIDTH,
        sourceHeight: Int = DEFAULT_SOURCE_HEIGHT
    ): ByteArray = sizeFrame(FUNC_MIRROR_START, sourceWidth, sourceHeight)

    fun mirrorStopFrame(): ByteArray = videoFrame(FUNC_MIRROR_STOP, ByteArray(4), ByteArray(0))

    fun widthHeightFrame(width: Int, height: Int): ByteArray = sizeFrame(FUNC_WIDTH_HEIGHT, width, height)

    /**
     * One H.264 access unit in Annex-B form. [frameIndex] wraps at 65536 and [timestampMs] is
     * milliseconds since MIRROR_START; the timestamp counts towards the length but not towards
     * the checksum, which covers [annexB] alone.
     */
    fun videoDataFrame(frameIndex: Int, timestampMs: Long, annexB: ByteArray): ByteArray {
        val frame = ByteArray(HEADER_SIZE + 4 + annexB.size)
        VIDEO_MAGIC.copyInto(frame, 0)
        frame[3] = FUNC_VIDEO_DATA.toByte()
        putLe16(frame, 4, frameIndex and 0xFFFF)
        putLe16(frame, 6, checksum16(annexB))
        putLe32(frame, 8, (4 + annexB.size).toLong())
        putLe32(frame, HEADER_SIZE, timestampMs and 0xFFFFFFFFL)
        annexB.copyInto(frame, HEADER_SIZE + 4)
        return frame
    }

    /**
     * Sum of the data read as LE32 words, the last partial word zero-padded at the top, folded
     * to 16 bits with end-around carry.
     */
    fun checksum16(data: ByteArray): Int {
        var sum = 0L
        var offset = 0
        while (offset < data.size) {
            var word = 0L
            for (index in 0 until minOf(4, data.size - offset)) {
                word = word or ((data[offset + index].toLong() and 0xFF) shl (8 * index))
            }
            sum += word
            offset += 4
        }
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return (sum and 0xFFFF).toInt()
    }

    /**
     * Finds the dash's `OK` reply in [bytes] and decodes it. Searched for rather than read at
     * offset zero because nobody has seen what, if anything, precedes it on the wire; null until
     * a complete reply is present.
     */
    fun parseOkReply(bytes: ByteArray, length: Int = bytes.size): Capabilities? {
        val start = okReplyOffset(bytes, length) ?: return null
        return Capabilities(
            portraitWidth = le16(bytes, start + 2),
            portraitHeight = le16(bytes, start + 4),
            landscapeWidth = le16(bytes, start + 6),
            landscapeHeight = le16(bytes, start + 8),
            frameRate = bytes[start + 10].toInt() and 0xFF,
            bitRateKbps = le16(bytes, start + 11)
        )
    }

    /** Offset of the first complete `OK` reply in the first [length] bytes, or null. */
    fun okReplyOffset(bytes: ByteArray, length: Int = bytes.size): Int? {
        for (start in 0..length - OK_REPLY_SIZE) {
            if (bytes[start] == 'O'.code.toByte() && bytes[start + 1] == 'K'.code.toByte()) return start
        }
        return null
    }

    /** What a KTEY dash answers on `aaa4` when asked how it wants to be reached. */
    enum class BleConnectType { PHONE_HOTSPOT, PHONE_P2P_GROUP, BLE_ONLY }

    fun bleConnectType(text: String): BleConnectType? = when (text) {
        "-1" -> BleConnectType.PHONE_HOTSPOT
        "-4" -> BleConnectType.PHONE_P2P_GROUP
        "-3" -> BleConnectType.BLE_ONLY
        else -> null
    }

    /** A characteristic's text: the dash pads its values with NULs. */
    fun bleText(value: ByteArray): String =
        String(value, 0, value.indexOf(0).takeIf { it >= 0 } ?: value.size, Charsets.UTF_8)

    /**
     * The dash's address from `aaa1`, or null while it has not joined: until then the same
     * characteristic reads `0.0.0.0`, or the `1`/`2` left by the write that started the join.
     */
    fun bleDashIp(value: ByteArray): String? {
        val text = bleText(value)
        if (text == "0.0.0.0" || !text.contains('.')) return null
        return text.takeWhile { it == '.' || it.isDigit() }.takeIf(String::isNotEmpty)
    }

    fun hex(bytes: ByteArray, length: Int = bytes.size): String =
        (0 until minOf(length, bytes.size)).joinToString(" ") { "%02X".format(bytes[it].toInt() and 0xFF) }

    /** A video-port frame whose payload is two LE16 values and whose header bytes 4..7 are zero. */
    private fun sizeFrame(func: Int, width: Int, height: Int): ByteArray {
        val payload = ByteArray(4)
        putLe16(payload, 0, width)
        putLe16(payload, 2, height)
        return videoFrame(func, ByteArray(4), payload)
    }

    private fun videoFrame(func: Int, functionBytes: ByteArray, payload: ByteArray): ByteArray {
        val frame = ByteArray(HEADER_SIZE + payload.size)
        VIDEO_MAGIC.copyInto(frame, 0)
        frame[3] = func.toByte()
        functionBytes.copyInto(frame, 4)
        putLe32(frame, 8, payload.size.toLong())
        payload.copyInto(frame, HEADER_SIZE)
        return frame
    }

    private fun le16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun putLe16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
    }

    private fun putLe32(target: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 4) target[offset + index] = (value ushr (8 * index)).toByte()
    }
}
