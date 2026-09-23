// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkerRideProtocolTest {

    @Test
    fun framesControlJsonWithMagicLengthAndTerminator() {
        val framed = ThinkerRideProtocol.frameControlJson("{}")

        assertArrayEquals(
            byteArrayOf(
                0xEE.toByte(), 0xFD.toByte(), // magic
                0x00, 0x00, 0x00, 0x02, // big-endian body length
                '{'.code.toByte(), '}'.code.toByte(),
                0xFF.toByte() // terminator
            ),
            framed
        )
    }

    @Test
    fun videoSizeHeaderCarriesOsNameAndBigEndianGeometry() {
        val header = ThinkerRideProtocol.videoSizeHeader(600, 1024)

        assertEquals(69, header.size)
        assertEquals(0, header[0].toInt())
        assertEquals("android", String(header, 1, 7, Charsets.US_ASCII))
        // 600 = 0x0258, 1024 = 0x0400, both big-endian at offsets 65..68.
        assertEquals(0x02, header[65].toInt() and 0xFF)
        assertEquals(0x58, header[66].toInt() and 0xFF)
        assertEquals(0x04, header[67].toInt() and 0xFF)
        assertEquals(0x00, header[68].toInt() and 0xFF)
    }

    @Test
    fun videoSizeHeaderIsResolutionAgnostic() {
        // Multi-model support hinges on this: any profile geometry must be encodable.
        val header = ThinkerRideProtocol.videoSizeHeader(1280, 535)

        assertEquals(0x05, header[65].toInt() and 0xFF)
        assertEquals(0x00, header[66].toInt() and 0xFF)
        assertEquals(0x02, header[67].toInt() and 0xFF)
        assertEquals(0x17, header[68].toInt() and 0xFF)
    }

    @Test(expected = IllegalArgumentException::class)
    fun videoSizeHeaderRejectsAnEmptyGeometry() {
        ThinkerRideProtocol.videoSizeHeader(0, 1024)
    }

    @Test
    fun convertsAnAvccAccessUnitToAnnexB() {
        val avcc = byteArrayOf(
            0x00, 0x00, 0x00, 0x03, 0x67, 0x42, 0x00, // SPS-ish NAL, length 3
            0x00, 0x00, 0x00, 0x02, 0x65, 0x11 // IDR-ish NAL, length 2
        )

        val annexB = ThinkerRideProtocol.annexBFromAvcc(avcc)

        assertArrayEquals(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00,
                0x00, 0x00, 0x00, 0x01, 0x65, 0x11
            ),
            annexB
        )
    }

    @Test
    fun passesThroughAccessUnitsAlreadyInAnnexB() {
        val annexB = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65, 0x11)

        assertSame(annexB, ThinkerRideProtocol.annexBFromAvcc(annexB))
    }

    @Test
    fun shipsMalformedAccessUnitsUntouchedRatherThanCorrupting() {
        // Length prefix claims more bytes than the buffer holds.
        val malformed = byteArrayOf(0x00, 0x00, 0x00, 0x7F, 0x65)

        assertSame(malformed, ThinkerRideProtocol.annexBFromAvcc(malformed))
    }

    @Test
    fun recognisesTheSixByteKeepaliveProbe() {
        assertTrue(ThinkerRideProtocol.isKeepaliveProbe(byteArrayOf(0x02, 0x01, 0x00, 0x00, 0x00, 0x00)))
        assertFalse(ThinkerRideProtocol.isKeepaliveProbe(byteArrayOf(0x02, 0x01, 0x00, 0x00, 0x00)))
        assertFalse(ThinkerRideProtocol.isKeepaliveProbe(byteArrayOf(0x01, 0x01, 0x00, 0x00, 0x00, 0x00)))
        // A longer read that merely starts like a probe is not one.
        val buffer = byteArrayOf(0x02, 0x01, 0x00, 0x00, 0x00, 0x00, 0x55)
        assertFalse(ThinkerRideProtocol.isKeepaliveProbe(buffer, buffer.size))
        assertTrue(ThinkerRideProtocol.isKeepaliveProbe(buffer, 6))
    }

    @Test
    fun countsKeepaliveProbesQueuedBackToBack() {
        val probe = ThinkerRideProtocol.KEEPALIVE_PACKET
        assertEquals(1, ThinkerRideProtocol.keepaliveProbeCount(probe))
        // The 108-byte read a KOVE sent (support ID 06DD-823E-D3B9): eighteen probes.
        val queued = ByteArray(0).let { acc -> (1..18).fold(acc) { bytes, _ -> bytes + probe } }
        assertEquals(18, ThinkerRideProtocol.keepaliveProbeCount(queued))
        // A multiple of six that is not all probes is a message, not keep-alives.
        val mixed = probe + byteArrayOf(0x02, 0x01, 0x00, 0x00, 0x00, 0x07)
        assertEquals(0, ThinkerRideProtocol.keepaliveProbeCount(mixed))
        assertEquals(0, ThinkerRideProtocol.keepaliveProbeCount(probe + byteArrayOf(0x02)))
        assertEquals(0, ThinkerRideProtocol.keepaliveProbeCount(ThinkerRideProtocol.frameControlJson("{}")))
    }

    @Test
    fun controlHandshakeHasTheFixedLayoutFromTheReferenceCapture() {
        val handshake = ThinkerRideProtocol.controlHandshake("id@example.com")

        // 6 (cmd 1) + 10 (cmd 23) + 6 + 256 (cmd 18 + identity) + 6 (cmd 14) + 6 (cmd 17).
        assertEquals(290, handshake.size)
        assertEquals(0x01, handshake[0].toInt())
        assertEquals(0x17, handshake[7].toInt())
        assertEquals(0x12, handshake[17].toInt())
        // Identity begins right after cmd 18's 6-byte header and is zero-padded to 256 bytes.
        assertEquals("id@example.com", String(handshake, 22, 14, Charsets.UTF_8))
        assertEquals(0x00, handshake[22 + 14].toInt())
        assertEquals(0x0E, handshake[279].toInt())
        assertEquals(0x11, handshake[285].toInt())
    }

    @Test
    fun mirrorStatusPacketsToggleBothMessageTypes() {
        val start = ThinkerRideProtocol.bleMirrorStatusPackets(active = true)
        val stop = ThinkerRideProtocol.bleMirrorStatusPackets(active = false)

        assertEquals(2, start.size)
        assertTrue(start[0].contains("\"msg_type\":23") && start[0].contains("\"status\":1"))
        assertTrue(start[1].contains("\"msg_type\":21") && start[1].contains("\"status\":1"))
        assertTrue(stop[0].contains("\"msg_type\":23") && stop[0].contains("\"status\":0"))
        assertTrue(stop[1].contains("\"msg_type\":21") && stop[1].contains("\"status\":0"))
    }

    @Test
    fun handshakeSendsPairInfoFirstAndCarriesTheTimestamp() {
        val packets = ThinkerRideProtocol.bleHandshakePackets("2026-08-04 10:15:00")

        assertEquals(4, packets.size)
        assertTrue(packets[0].contains("get_pairinfo"))
        assertTrue(packets[3].contains("2026-08-04 10:15:00"))
    }

    @Test
    fun acceptsOnlyAPositivePairResult() {
        assertTrue(
            ThinkerRideProtocol.isPairConfirmation(
                """{"msg_id":27,"act":"send_pairresult","result":1}"""
            )
        )
        assertFalse(
            ThinkerRideProtocol.isPairConfirmation(
                """{"msg_id":27,"act":"send_pairresult","result":0}"""
            )
        )
        assertFalse(ThinkerRideProtocol.isPairConfirmation("""{"msg_id":25,"msg_type":24}"""))
        assertFalse(ThinkerRideProtocol.isPairConfirmation("send_pairresult but not json"))
    }

    @Test
    fun recognisesAPairResultInsideAConcatenatedNotification() {
        // Dash firmware packs multiple JSON objects into one notify payload; whole-string
        // parsing fails on these, so the confirmation must still be recognised.
        assertTrue(
            ThinkerRideProtocol.isPairConfirmation(
                """{"msg_id":10,"item":1}{"msg_id":27,"func":"PAIR","act":"send_pairresult","result":1}"""
            )
        )
        assertTrue(
            ThinkerRideProtocol.isPairConfirmation(
                "{\n\t\"msg_id\":\t27,\n\t\"act\":\t\"send_pairresult\",\n\t\"result\":\t1\n}{\"msg_id\":10}"
            )
        )
        assertFalse(
            ThinkerRideProtocol.isPairConfirmation(
                """{"msg_id":27,"act":"send_pairresult","result":0}{"msg_id":10,"item":1}"""
            )
        )
    }

    @Test
    fun rebuildsAMessageSplitAcrossNotifications() {
        // Exactly how a KOVE 800X PRO delivered send_pairinfo at the default 23-byte MTU.
        val assembler = ThinkerRideProtocol.NotifyAssembler()

        assertTrue(assembler.accept("{\n\t\"msg_id\":\t27,\n\t\"f").isEmpty())
        assertTrue(assembler.accept("unc\":\t\"PAIR\",\n\t\"act\"").isEmpty())
        assertTrue(assembler.accept(":\t\"send_pairresult\",\n\t").isEmpty())

        val completed = assembler.accept("\"result\":\t1\n}")
        assertEquals(1, completed.size)
        assertTrue(ThinkerRideProtocol.isPairConfirmation(completed.first()))
    }

    @Test
    fun splitsSeveralMessagesArrivingInOneNotification() {
        val assembler = ThinkerRideProtocol.NotifyAssembler()

        val messages = assembler.accept(
            """{"msg_id":10,"item":1}{"msg_id":27,"act":"send_pairresult","result":1}"""
        )

        assertEquals(2, messages.size)
        assertEquals("""{"msg_id":10,"item":1}""", messages[0])
        assertTrue(ThinkerRideProtocol.isPairConfirmation(messages[1]))
    }

    @Test
    fun keepsTheTrailingPartialMessageForTheNextNotification() {
        val assembler = ThinkerRideProtocol.NotifyAssembler()

        val first = assembler.accept("""{"msg_id":13}{"msg_id":27,"act":""")
        assertEquals(listOf("""{"msg_id":13}"""), first)

        val second = assembler.accept(""""send_pairresult","result":1}""")
        assertEquals(1, second.size)
        assertTrue(ThinkerRideProtocol.isPairConfirmation(second.first()))
    }

    @Test
    fun bracesInsideStringsDoNotSplitAMessage() {
        val assembler = ThinkerRideProtocol.NotifyAssembler()

        val messages = assembler.accept("""{"msg_id":9,"name":"a}{b","tag":-1}""")

        assertEquals(listOf("""{"msg_id":9,"name":"a}{b","tag":-1}"""), messages)
    }

    @Test
    fun readsTheActivationFlagFromTheDashTucReply() {
        // Verbatim from a KOVE 800X diagnostics log (2026-08-20).
        val reply = """{"msg_id":27,"func":"TUC","act":"SEND","tuc":"700039886a79c2c9","tucs":1}"""

        assertEquals(1, ThinkerRideProtocol.parseActivationFlag(reply))
    }

    @Test
    fun readsAnUnactivatedFlag() {
        val reply = """{"msg_id":27,"func":"TUC","act":"SEND","tuc":"","tucs":0}"""

        assertEquals(0, ThinkerRideProtocol.parseActivationFlag(reply))
    }

    @Test
    fun toleratesWhitespacePaddingAroundTheFlag() {
        val reply = """{"msg_id":27,"func":"TUC","act":"SEND","tucs" :  1 }"""

        assertEquals(1, ThinkerRideProtocol.parseActivationFlag(reply))
    }

    @Test
    fun ignoresPayloadsThatCarryNoActivationFlag() {
        // A TUC reply without the field is old firmware, not an unactivated dash - the caller
        // must not be able to tell those apart by accident.
        assertNull(
            ThinkerRideProtocol.parseActivationFlag("""{"msg_id":27,"func":"TUC","act":"SEND"}""")
        )
        assertNull(
            ThinkerRideProtocol.parseActivationFlag("""{"msg_id":9,"func":"NAVI","tucs":1}""")
        )
        assertNull(ThinkerRideProtocol.parseActivationFlag("not json at all"))
    }

    /**
     * The exact bytes the OEM's `byteCat` produces for one short command, computed from
     * ttarlov/kove-dash `ByteCat.kt`. Pinned literally because every part of this envelope is
     * arbitrary - the 0x80 in each checksum nibble, the NUL that moves to the end, the fixed
     * 104-byte length - and a plausible-looking reimplementation is exactly the failure this
     * cannot afford: an unframed or mis-framed write is not rejected, it is silently ignored.
     */
    @Test
    fun byteCatFramesOneShortCommandIntoASingle104ByteFrame() {
        val frames = ThinkerRideProtocol.byteCatFrames("""{"msg_id":13}""", startSeq = 0)

        assertEquals(1, frames.size)
        val frame = frames.single()
        assertEquals(104, frame.size)
        assertEquals(
            "FE 00 00 7B 22 6D 73 67 5F 69 64 22 3A 31 33 7D 84 8D 00 FF",
            frame.take(20).joinToString(" ") { "%02X".format(it) }
        )
        // Everything after the tail is padding, and the dash reads the whole 104 bytes.
        assertTrue(frame.drop(20).all { it == 0x00.toByte() })
    }

    /** The NUL terminator is replaced by the checksum and re-appended; the JSON is untouched. */
    @Test
    fun theByteCatChecksumIsTheOemNibbleSum() {
        val body = """{"msg_id":13}""".toByteArray(Charsets.UTF_8) + 0x00.toByte()

        val checksum = ThinkerRideProtocol.byteCatChecksum(body)

        assertEquals(2, checksum.size)
        assertEquals(0x84.toByte(), checksum[0])
        assertEquals(0x8D.toByte(), checksum[1])
        // Neither byte may collide with the frame head or tail, which is what the 0x80 is for.
        assertTrue(checksum.none { it == ThinkerRideProtocol.BYTE_CAT_HEAD })
        assertTrue(checksum.none { it == ThinkerRideProtocol.BYTE_CAT_TAIL })
    }

    /**
     * A message longer than one chunk numbers its frames one apart. Stamping every frame of a
     * message with the same sequence is what made the dash see duplicates and gaps, and ask for
     * retransmits of frames nobody sent (kove-dash ByteCat KDoc).
     */
    @Test
    fun byteCatNumbersEveryFrameOfALongCommandSeparately() {
        val long = "{\"msg_id\":25,\"msg_type\":23,\"msg_source\":2,\"status\":1,\"pad\":\"" +
            "x".repeat(60) + "\"}"

        val frames = ThinkerRideProtocol.byteCatFrames(long, startSeq = 7)

        assertEquals(2, frames.size)
        assertEquals(7, (frames[0][1].toInt() shl 8) or frames[0][2].toInt())
        assertEquals(8, (frames[1][1].toInt() shl 8) or frames[1][2].toInt())
        assertTrue(frames.all { it.size == 104 && it[0] == ThinkerRideProtocol.BYTE_CAT_HEAD })
    }
}
