// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import java.io.InputStream

/**
 * Wire-level constants and codecs for the "Yunmo" :8200 SoftAP projection protocol, spoken by the
 * Moto Morini X-Cape 1200 dashboard (SoftAP SSID `ML*`, host `192.168.4.1`). Everything here is
 * pure byte work so it stays JVM-testable; sockets live in [YunmoTransport].
 *
 * Yunmo is neither EasyConn nor ThinkerRide: there is no NSD, no PXC, no JSON CLIENT_INFO and no
 * BLE. The phone opens one TCP socket, asks the dash for its canvas size, tells it to start, then
 * pushes length-prefixed H.264 access units.
 * (Protocol notes first written 2026-08-07.)
 *
 * Two facts drive the whole design and both are re-derived in the tests:
 *  - **The dash reports its real canvas.** The OK/dimension reply carries the panel size as-is; an
 *    X-Cape 1200 answers 1024x464, matching the OEM `NaviVirtualDisplay` an owner measured over
 *    ADB. This file used to double the report — see [encodeCanvas] for how that was inferred and
 *    what disproved it.
 *  - **The media header carries no frame metadata on the wire.** The shipping build always encodes
 *    with media-type byte `2` and the metadata block (frameId/width/height) left zero. An earlier
 *    build that wrote those fields produced a black TFT, so [encodeH264Ex] defaults to omitting
 *    them and [YunmoTransport] never turns that off.
 */
object YunmoProtocol {

    const val DEFAULT_HOST = "192.168.4.1"
    const val DEFAULT_PORT = 8200

    /** Outer frame guard: four sync bytes then a one-byte command. */
    const val SYNC: Byte = 0xFE.toByte()

    // Commands (byte [4] of every frame).
    const val CMD_H264_EX = 0x1D
    const val CMD_OK_A = 0x32
    const val CMD_OK_B = 0x33
    const val CMD_ERR = 0x34
    const val CMD_DISPLAY = 0xA0
    const val CMD_DISPLAY_ALT = 0xB0

    // DISPLAY payload opcodes (a single byte). The teardown pair is `A0{2}` then `A0{5}`, matching
    // the OEM Ride MO app's sendExitDisplay (MediaCodecH264SplitLiveThread), not the `A0{3}` an
    // earlier reverse-engineering guess used.
    const val DISP_START_MIRROR = 7
    const val DISP_MAP_NAVI = 6
    const val DISP_EXIT_A = 2
    const val DISP_EXIT_B = 5

    /**
     * The dash switched to its own compact turn-arrow guidance, which it draws itself — anything
     * pushed while it is in this state is not painted. Same value as [DISP_EXIT_B]; direction
     * disambiguates them (outbound in the teardown pair, inbound it means SimpleNavi).
     */
    const val DISP_SIMPLE_NAVI = 5

    // Media-type byte written at header offset [15]. Always 2. The OEM app only ever sends
    // 2 here (checked 2026-08-12), and a field experiment that
    // derived a per-frame type instead made the dash stop acking frames entirely (1.1.59 variants
    // B/C). So this is measured now, not inherited: the byte is fixed.
    const val MEDIA_TYPE_LEGACY = 2

    /** Payload of the size query sent (with [CMD_DISPLAY_ALT]) before anything else. */
    val DIM_QUERY_PAYLOAD = byteArrayOf(1, 0, 1)

    /** How many frames may be in flight (unacked) before the sender must wait. */
    const val SEND_WINDOW = 3

    /**
     * Stills the dash may owe us an ack for before offers are refused.
     *
     * Deliberately tighter than [SEND_WINDOW]. This dash is byte-limited, not latency-limited - it
     * drinks about the same number of bytes per second however they are divided up - so a wider
     * window buys no throughput and costs delay one for one: at the ~2.5 stills a second the
     * X-Cape manages, every slot is another 400ms the rider waits. Two lets one still sit on the
     * wire while the next is being compressed, which is all the overlap there is to win. Raise it
     * only if a road test shows the rate dropping.
     */
    const val STILL_SEND_WINDOW = 2

    /**
     * Stills handed to the socket that the dash has not acknowledged yet.
     *
     * A pure function so it can be tested, because the arithmetic being wrong is not a theoretical
     * risk: 1.1.85 shipped a version of it reading a counter the stills path never advances, which
     * evaluated to `0 - lastAckedId` and so never once closed the send window. Nothing then limited
     * the sender but the socket, the stills queued inside the kernel and the dash's own buffers,
     * and riders saw a picture roughly fifteen seconds old (field log 2026-08-22).
     *
     * [lastOfferedId] and [lastAckedId] are both -1 before anything moves. Gaps in the ids are
     * expected and harmless - a latest-wins sender skips the stills it replaced - because this
     * measures the distance to the newest id, not how many were sent.
     */
    fun stillsInFlight(lastOfferedId: Int, lastAckedId: Int): Int =
        lastOfferedId - maxOf(lastAckedId, -1)

    /** Largest simple-frame payload the parser will accept, matching the reference guard. */
    private const val MAX_SIMPLE_PAYLOAD = 10240

    /** Largest number of byte slips the resync will tolerate before giving up on a frame. */
    private const val MAX_RESYNC_SLIP = 4096

    /**
     * The dash's reported canvas. The reported size **is** the panel — see [encodeCanvas] for why
     * this used to be doubled and no longer is.
     */
    data class DimensionReport(val reportedWidth: Int, val reportedHeight: Int)

    /** One decoded simple frame: its command byte and its raw payload. */
    data class SimpleFrame(val command: Int, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is SimpleFrame && command == other.command && payload.contentEquals(other.payload))

        override fun hashCode(): Int = 31 * command + payload.contentHashCode()
    }

    // ---- Encoders ----------------------------------------------------------------------------

    /**
     * Encodes a control/display frame:
     * `FE FE FE FE | cmd | lenHi | lenLo | (lenHi+lenLo)&0xFF | payload | checksum`
     * where checksum is the low byte of the sum of bytes [4 .. 8+len).
     */
    fun encodeSimple(command: Int, payload: ByteArray): ByteArray {
        val length = payload.size
        val frame = ByteArray(length + 9)
        frame[0] = SYNC
        frame[1] = SYNC
        frame[2] = SYNC
        frame[3] = SYNC
        frame[4] = command.toByte()
        val hi = (length ushr 8).toByte()
        val lo = length.toByte()
        frame[5] = hi
        frame[6] = lo
        frame[7] = ((hi.toInt() and 0xFF) + (lo.toInt() and 0xFF)).toByte()
        payload.copyInto(frame, destinationOffset = 8)
        var sum = 0
        for (i in 4 until 8 + length) {
            sum += frame[i].toInt() and 0xFF
        }
        frame[8 + length] = (sum and 0xFF).toByte()
        return frame
    }

    /**
     * Encodes one H.264 access unit as a [CMD_H264_EX] frame: a 40-byte header then the access
     * unit zero-padded to a 32-byte multiple.
     *
     * [omitMeta] (the shipping default) leaves offsets [16..24] — frameId, width, height — zero;
     * the dash reads none of them and an earlier build that filled them blacked the TFT out.
     */
    fun encodeH264Ex(
        accessUnit: ByteArray,
        width: Int,
        height: Int,
        frameId: Int,
        mediaType: Int = MEDIA_TYPE_LEGACY,
        omitMeta: Boolean = true
    ): ByteArray {
        val padded = ((accessUnit.size + 31) / 32) * 32
        val frame = ByteArray(padded + 40)
        accessUnit.copyInto(frame, destinationOffset = 40)
        frame[0] = SYNC
        frame[1] = SYNC
        frame[2] = SYNC
        frame[3] = SYNC
        frame[4] = CMD_H264_EX.toByte()
        val blocks = (padded + 32) / 32
        val bHi = (blocks ushr 8).toByte()
        val bLo = blocks.toByte()
        frame[5] = bHi
        frame[6] = bLo
        frame[7] = ((bHi.toInt() and 0xFF) + (bLo.toInt() and 0xFF)).toByte()
        frame[14] = 0
        frame[15] = mediaType.toByte()
        if (!omitMeta) {
            putLe(frame, 16, frameId, 4)
            putLe(frame, 20, width, 2)
            putLe(frame, 22, height, 2)
            frame[24] = 0
        }
        putLe(frame, 8, accessUnit.size, 4)
        var sum = 0
        for (b in accessUnit) sum += b.toInt() and 0xFF
        putLe(frame, 12, sum and 0xFFFF, 2)
        return frame
    }

    // ---- The three handshake / teardown frames ----------------------------------------------

    /** `B0` + `01 00 01`: asks the dash to report its canvas size. */
    fun dimQueryFrame(): ByteArray = encodeSimple(CMD_DISPLAY_ALT, DIM_QUERY_PAYLOAD)

    /** The mirror-start pair, in order: `B0{7}` then `A0{7}`. */
    fun startMirrorFrames(): List<ByteArray> = listOf(
        encodeSimple(CMD_DISPLAY_ALT, byteArrayOf(DISP_START_MIRROR.toByte())),
        encodeSimple(CMD_DISPLAY, byteArrayOf(DISP_START_MIRROR.toByte()))
    )

    /** `A0{6}`: asks the dash to enter its OEM map-navigation display path. */
    fun mapNaviFrame(): ByteArray = encodeSimple(CMD_DISPLAY, byteArrayOf(DISP_MAP_NAVI.toByte()))

    /** The teardown pair, in order: `A0{3}` then `A0{5}`; returns the dash to its stock UI. */
    fun stopFrames(): List<ByteArray> = listOf(
        encodeSimple(CMD_DISPLAY, byteArrayOf(DISP_EXIT_A.toByte())),
        encodeSimple(CMD_DISPLAY, byteArrayOf(DISP_EXIT_B.toByte()))
    )

    // ---- Parsing -----------------------------------------------------------------------------

    /**
     * Reads and validates one simple frame, resyncing on the `FE FE FE FE` guard. Returns null on
     * EOF, a checksum/length failure, or after [MAX_RESYNC_SLIP] bytes without a valid header.
     * H.264 frames are never parsed back — the phone only ever receives control/ack frames.
     */
    fun readSimpleFrame(input: InputStream): SimpleFrame? {
        val header = ByteArray(8)
        if (!readFully(input, header)) return null
        var slips = 0
        while (true) {
            val framed = header[0] == SYNC && header[1] == SYNC && header[2] == SYNC &&
                header[3] == SYNC && header[4] != SYNC
            if (framed) {
                val hi = header[5].toInt() and 0xFF
                val lo = header[6].toInt() and 0xFF
                val length = (hi shl 8) or lo
                if (((hi + lo) and 0xFF) != (header[7].toInt() and 0xFF) ||
                    length < 0 || length > MAX_SIMPLE_PAYLOAD
                ) {
                    return null
                }
                val payload = ByteArray(length)
                if (length > 0 && !readFully(input, payload)) return null
                val checksum = input.read()
                if (checksum < 0) return null
                var sum = 0
                for (i in 4 until 8) sum += header[i].toInt() and 0xFF
                for (b in payload) sum += b.toInt() and 0xFF
                if ((sum and 0xFF) != (checksum and 0xFF)) return null
                return SimpleFrame(header[4].toInt() and 0xFF, payload)
            }
            // Not aligned: drop the first byte, pull one more, and try again.
            System.arraycopy(header, 1, header, 0, 7)
            val next = input.read()
            if (next < 0) return null
            header[7] = next.toByte()
            if (++slips > MAX_RESYNC_SLIP) return null
        }
    }

    /**
     * Media-type byte for a JPEG still, written at header offset [15].
     *
     * This is the value the OEM app actually ships. Ride MO 1.0.23 never streams H.264 at all:
     * `ParamSettings.deviceStreamType` is initialised to `Image` and its only setter has zero call
     * sites in the APK, so `createDisplayAndLiveAdapter` always takes the image branch. Both this
     * project and the reference implementation spent weeks tuning an H.264 stream against a class
     * the OEM never instantiates, which is the best available explanation for a dash that
     * acknowledges every frame and paints none of them.
     */
    const val MEDIA_TYPE_JPEG = 0

    /**
     * Frames one JPEG still the way the OEM's image path does.
     *
     * Two differences from [encodeH264Ex], and they are the whole point:
     *  - the media-type byte is [MEDIA_TYPE_JPEG], not the legacy 2;
     *  - the frame id **is written** at [16..19]. The H.264 path leaves it zero, which is why
     *    every ack on that path comes back reporting frame 0 and why neither implementation has
     *    had a usable liveness signal. On this path the acks should carry real ids back.
     *
     * Everything else - sync bytes, the block count in [5..6], the length and payload checksum the
     * transport layer owns at [8..13] - is identical, because it belongs to the envelope rather
     * than to the codec.
     */
    fun encodeJpegEx(jpeg: ByteArray, frameId: Int): ByteArray {
        val padded = ((jpeg.size + 31) / 32) * 32
        val frame = ByteArray(padded + 40)
        jpeg.copyInto(frame, destinationOffset = 40)
        frame[0] = SYNC
        frame[1] = SYNC
        frame[2] = SYNC
        frame[3] = SYNC
        frame[4] = CMD_H264_EX.toByte()
        val blocks = (padded + 32) / 32
        val bHi = (blocks ushr 8).toByte()
        val bLo = blocks.toByte()
        frame[5] = bHi
        frame[6] = bLo
        frame[7] = ((bHi.toInt() and 0xFF) + (bLo.toInt() and 0xFF)).toByte()
        frame[14] = 0
        frame[15] = MEDIA_TYPE_JPEG.toByte()
        putLe(frame, 16, frameId, 4)
        putLe(frame, 8, jpeg.size, 4)
        var sum = 0
        for (b in jpeg) sum += b.toInt() and 0xFF
        putLe(frame, 12, sum and 0xFFFF, 2)
        return frame
    }

    /** Parses the canvas size out of an OK (`0x32`/`0x33`) payload; both axes are big-endian. */
    fun parseOkDimension(payload: ByteArray): DimensionReport? {
        if (payload.size < 8) return null
        val width = ((payload[4].toInt() and 0xFF) shl 8) or (payload[5].toInt() and 0xFF)
        val height = ((payload[6].toInt() and 0xFF) shl 8) or (payload[7].toInt() and 0xFF)
        if (width < 16 || height < 16 || width > 4096 || height > 4096) return null
        return DimensionReport(width, height)
    }

    /** True when a [CMD_DISPLAY] payload is the dash confirming map-nav (opcode 6) with a reset. */
    fun isMapNaviConfirm(payload: ByteArray): Boolean =
        payload.isNotEmpty() && (payload[0].toInt() and 0xFF) == DISP_MAP_NAVI

    /**
     * True when a [CMD_DISPLAY] payload is the dash announcing it moved to its own compact arrow
     * guidance ([DISP_SIMPLE_NAVI]). Only meaningful on frames read from the dash.
     */
    fun isSimpleNaviSwitch(payload: ByteArray): Boolean =
        payload.isNotEmpty() && (payload[0].toInt() and 0xFF) == DISP_SIMPLE_NAVI

    /**
     * Parses an acked frame id out of a [CMD_DISPLAY] payload (`payload[0]==0`, at least 5 bytes),
     * where bytes [1..4] are the id little-endian; returns null when the payload is not an ack.
     */
    fun parseAck(payload: ByteArray): Int? {
        if (payload.size < 5 || payload[0].toInt() != 0) return null
        return (payload[1].toInt() and 0xFF) or
            ((payload[2].toInt() and 0xFF) shl 8) or
            ((payload[3].toInt() and 0xFF) shl 16) or
            ((payload[4].toInt() and 0xFF) shl 24)
    }

    // ---- Canvas sizing -----------------------------------------------------------------------

    /**
     * The encode canvas for a session: the size the dash reported, forced even, or the caller's
     * fallback when the dash never answered.
     *
     * **This used to double the report and was wrong.** The doubling came from an X-Cape 1200
     * owner's ADB capture measuring the OEM `NaviVirtualDisplay` at 1024x464: with no capture of
     * the dash's own reply to compare against, a 512x232 report was inferred. A dimension reply
     * captured from a real X-Cape since (`00 00 00 00 04 00 01 d0`) says the dash reports
     * **1024x464 directly** — the same number, not half of it. Doubling therefore asked for a
     * 2048x928 canvas on a 1024x464 panel, which is the shape of the "dash acks every frame and
     * paints black" report.
     *
     * Even, not rounded up to 16: overshooting the panel is the failure mode being fixed here, so
     * a dash reporting an odd axis loses a line rather than gaining fifteen.
     */
    fun encodeCanvas(report: DimensionReport?, fallbackWidth: Int, fallbackHeight: Int): Pair<Int, Int> {
        if (report != null) return evenDown(report.reportedWidth) to evenDown(report.reportedHeight)
        return maxOf(fallbackWidth, 16) to maxOf(fallbackHeight, 16)
    }

    private fun evenDown(value: Int): Int = maxOf(value and 1.inv(), 16)

    // ---- H.264 Annex-B NAL helpers -----------------------------------------------------------

    /** One Annex-B NAL unit with its type (the low 5 bits of the first byte after the start code). */
    data class AnnexBNal(val type: Int, val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean =
            this === other || (other is AnnexBNal && type == other.type && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = 31 * type + bytes.contentHashCode()
    }

    /**
     * Converts one AVCC access unit (4-byte big-endian NAL lengths, the shape AvcEncoder emits) to
     * the Annex-B byte stream Yunmo carries. A payload already in Annex-B is returned untouched, and
     * a unit that does not parse cleanly is shipped as-is rather than corrupted.
     */
    fun annexBFromAvcc(accessUnit: ByteArray): ByteArray {
        if (startsWithAnnexBCode(accessUnit)) return accessUnit
        val out = ArrayList<ByteArray>()
        var cursor = 0
        while (cursor + 4 <= accessUnit.size) {
            val nalLength = ((accessUnit[cursor].toInt() and 0xFF) shl 24) or
                ((accessUnit[cursor + 1].toInt() and 0xFF) shl 16) or
                ((accessUnit[cursor + 2].toInt() and 0xFF) shl 8) or
                (accessUnit[cursor + 3].toInt() and 0xFF)
            if (nalLength <= 0 || cursor + 4 + nalLength > accessUnit.size) return accessUnit
            out += accessUnit.copyOfRange(cursor + 4, cursor + 4 + nalLength)
            cursor += 4 + nalLength
        }
        if (cursor != accessUnit.size || out.isEmpty()) return accessUnit
        val total = out.sumOf { 4 + it.size }
        val result = ByteArray(total)
        var offset = 0
        for (nal in out) {
            result[offset + 3] = 1
            nal.copyInto(result, offset + 4)
            offset += 4 + nal.size
        }
        return result
    }

    /** Splits an Annex-B buffer into its NAL units (start codes stripped). */
    fun splitAnnexB(data: ByteArray): List<AnnexBNal> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 3 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                val codeLen = when {
                    data[i + 2].toInt() == 1 -> 3
                    data[i + 2].toInt() == 0 && i + 3 < data.size && data[i + 3].toInt() == 1 -> 4
                    else -> 0
                }
                if (codeLen > 0) {
                    starts += i
                    i += codeLen
                    continue
                }
            }
            i++
        }
        if (starts.isEmpty()) return emptyList()
        val nals = ArrayList<AnnexBNal>(starts.size)
        for (index in starts.indices) {
            val start = starts[index]
            val end = if (index + 1 < starts.size) starts[index + 1] else data.size
            val codeLen = if (start + 2 < data.size && data[start + 2].toInt() == 0) 4 else 3
            val payloadStart = start + codeLen
            if (payloadStart < end) {
                nals += AnnexBNal(data[payloadStart].toInt() and 0x1F, data.copyOfRange(payloadStart, end))
            }
        }
        return nals
    }

    /** True when the buffer carries at least one NAL of [nalType]. */
    fun annexBContainsNal(data: ByteArray, nalType: Int): Boolean =
        splitAnnexB(data).any { it.type == nalType }

    /** Re-frames a single NAL (start-code stripped) as its own Annex-B buffer. */
    fun toAnnexBFrame(nal: AnnexBNal): ByteArray {
        val out = ByteArray(nal.bytes.size + 4)
        out[3] = 1
        nal.bytes.copyInto(out, 4)
        return out
    }

    /** Drops the leading SPS (7) and PPS (8) NALs, leaving the coded picture. */
    fun stripLeadingSpsPps(data: ByteArray): ByteArray {
        val nals = splitAnnexB(data)
        if (nals.isEmpty()) return data
        val kept = nals.filter { it.type != NAL_SPS && it.type != NAL_PPS }
        if (kept.size == nals.size) return data
        if (kept.isEmpty()) return ByteArray(0)
        val total = kept.sumOf { 4 + it.bytes.size }
        val result = ByteArray(total)
        var offset = 0
        for (nal in kept) {
            result[offset + 3] = 1
            nal.bytes.copyInto(result, offset + 4)
            offset += 4 + nal.bytes.size
        }
        return result
    }

    const val NAL_P = 1
    const val NAL_IDR = 5
    const val NAL_SPS = 7
    const val NAL_PPS = 8

    /**
     * Space-separated lowercase hex, truncated with an ellipsis past [max] bytes.
     *
     * Control payloads on this wire are short and carry no rider data — they are display states,
     * canvas sizes and frame ids — so logging them whole is what lets an unrecognised frame be
     * identified from a field log instead of guessed at.
     */
    fun hex(bytes: ByteArray, max: Int = 24): String {
        val shown = bytes.take(max).joinToString(" ") { "%02x".format(it) }
        return if (bytes.size > max) "$shown … (${bytes.size}b)" else shown
    }

    /** Short human name for a NAL type, for the session phase log. */
    fun nalName(type: Int): String = when (type) {
        NAL_P -> "P"
        NAL_IDR -> "IDR"
        NAL_SPS -> "SPS"
        NAL_PPS -> "PPS"
        else -> "NAL$type"
    }

    // ---- Internals ---------------------------------------------------------------------------

    private fun startsWithAnnexBCode(data: ByteArray): Boolean =
        (data.size >= 4 && data[0].toInt() == 0 && data[1].toInt() == 0 &&
            data[2].toInt() == 0 && data[3].toInt() == 1) ||
            (data.size >= 3 && data[0].toInt() == 0 && data[1].toInt() == 0 && data[2].toInt() == 1)

    private fun putLe(buffer: ByteArray, offset: Int, value: Int, byteCount: Int) {
        var v = value
        for (i in 0 until byteCount) {
            buffer[offset + i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var read = 0
        while (read < buffer.size) {
            val count = input.read(buffer, read, buffer.size - read)
            if (count < 0) return false
            read += count
        }
        return true
    }
}
