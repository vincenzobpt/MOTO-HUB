// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RideDaemonTransportTest {
    @Test
    fun `a dash that advertises no package name still has a usable identity`() {
        // The Zontes 125X (modelId 21340, field log 2026-08-19) resolves _EasyConn._tcp with a
        // usable host and port and no packagename at all. Discovery no longer rejects that
        // advertisement, and the name it sends back in the EC init command comes from the wake
        // probe's ladder instead: the leading candidate until a probe settles the question, then
        // whatever the dash acknowledged.
        EasyConnClientIdentity.forget()
        try {
            assertNull(decodeEasyConnPackage(null))
            assertNull(decodeEasyConnPackage("   ".toByteArray(Charsets.UTF_8)))
            assertEquals("com.cfmoto.cfmotointernational", EasyConnClientIdentity.probeOrder().first())

            EasyConnClientIdentity.remember("net.easyconn.carman")
            assertEquals("net.easyconn.carman", EasyConnClientIdentity.probeOrder().first())
        } finally {
            EasyConnClientIdentity.forget()
        }
    }

    @Test
    fun `an advertised package name still wins over the probe identity`() {
        EasyConnClientIdentity.forget()
        try {
            assertEquals(
                "tayo.com.ZontesIntelligence",
                decodeEasyConnPackage("tayo.com.ZontesIntelligence".toByteArray(Charsets.UTF_8))
            )
        } finally {
            EasyConnClientIdentity.forget()
        }
    }

    @Test
    fun `decodes CFDL26 capture area`() {
        val payload = captureRequest(width = 720, height = 712)

        assertEquals(TBoxEvent.VideoArea(width = 720, height = 712), decodeTBoxVideoArea(payload))
    }

    @Test
    fun `decodes legacy capture area`() {
        val payload = captureRequest(width = 800, height = 386)

        assertEquals(TBoxEvent.VideoArea(width = 800, height = 386), decodeTBoxVideoArea(payload))
    }

    @Test
    fun `rejects incomplete or empty capture area`() {
        assertNull(decodeTBoxVideoArea(byteArrayOf(1, 2, 3)))
        assertNull(decodeTBoxVideoArea(captureRequest(width = 0, height = 712)))
    }

    @Test
    fun `describes the capture request of the Zontes field log`() {
        // Verbatim opening bytes of the 204-byte REQ_RV_CONFIG_CAPTURE body logged by the Zontes
        // dash (package tayo.com.ZontesIntelligence, modelId 21334) on 2026-07-30 - the session
        // that negotiated cleanly, took 4531 frames and never lit the TFT.
        val payload = ByteArray(204)
        byteArrayOf(
            0x00, 0x04, 0xa9.toByte(), 0x01, 0x00, 0x00, 0x00, 0x00,
            0x02, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x80.toByte(), 0x00,
            0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00
        ).copyInto(payload)

        val described = describeTBoxCaptureRequest(payload)

        assertEquals(
            "size=204B, device=1024x425, fps=0, encoder=2, supportCodec=2, bitrate=8388608, " +
                "capScreenMode=0, touchMode=0, orientation=1, videoType=0, supportExtendProtocol=0",
            described
        )
    }

    @Test
    fun `describes a short capture request without inventing fields`() {
        val described = describeTBoxCaptureRequest(
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(0, 800).putShort(2, 480).array()
        )

        assertEquals(
            "size=4B, device=800x480, fps=?, encoder=?, supportCodec=?, bitrate=?, " +
                "capScreenMode=?, touchMode=?, orientation=?, videoType=?, supportExtendProtocol=?",
            described
        )
        assertNull(describeTBoxCaptureRequest(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `accepts simulator compatibility preset advertisements`() {
        assertTrue(isMotoHubSimulatorAdvertisement("MOTO-HUB T-Box Simulator 55262", "MOTO-HUB-SIMULATOR"))
        assertTrue(isMotoHubSimulatorAdvertisement("CFDL16-6GUV", "37416"))
        assertTrue(isMotoHubSimulatorAdvertisement("CFMOTO-805120", "37426"))
        assertTrue(isMotoHubSimulatorAdvertisement("CFMOTO-66660742", "66660742"))
    }

    @Test
    fun `rejects unrelated EasyConn advertisements for simulator profile`() {
        assertFalse(isMotoHubSimulatorAdvertisement("Someone Else", "37416"))
        assertFalse(isMotoHubSimulatorAdvertisement("CFDL16-6GUV", "unknown"))
        assertFalse(isMotoHubSimulatorAdvertisement("CFMOTO-123456", null))
    }

    @Test
    fun `native startup timeout allows simulator compatibility retries`() {
        assertEquals(25L, RIDE_DAEMON_STARTUP_TIMEOUT_SEC)
    }

    @Test
    fun `handshake traffic before the first frame is not a streaming beat`() {
        // No frame offered yet: whatever the dash says during the handshake proves nothing
        // about a keepalive cadence.
        assertFalse(isStreamingPxcBeat(previousPxcEventElapsed = 1_000L, lastFrameOfferedElapsed = 0L, now = 4_000L))
        // First-ever PXC event: no previous beat to measure a gap from.
        assertFalse(isStreamingPxcBeat(previousPxcEventElapsed = 0L, lastFrameOfferedElapsed = 2_000L, now = 4_000L))
        // Same-burst flurry during streaming: one transmission, not a cadence.
        assertFalse(isStreamingPxcBeat(previousPxcEventElapsed = 3_990L, lastFrameOfferedElapsed = 2_000L, now = 4_000L))
    }

    @Test
    fun `a CFDL16 that goes quiet after the handshake never reaches the fatal cadence`() {
        // Field log 2026-07-31, session 07:16: PXC at 34.700, 34.760, 34.761, 34.769, 34.831,
        // 34.847 and 37.860 (s.ms, relative order preserved); first frame offered at 35.096.
        // Only the 37.860 heartbeat arrives during streaming with a real gap -> one beat, and
        // the TFT kept displaying video, so one beat must stay below the fatal threshold.
        val events = longArrayOf(34_700, 34_760, 34_761, 34_769, 34_831, 34_847, 37_860)
        val firstFrameAt = 35_096L
        var beats = 0L
        for (index in 1 until events.size) {
            val lastFrame = if (events[index] > firstFrameAt) firstFrameAt else 0L
            if (isStreamingPxcBeat(events[index - 1], lastFrame, events[index])) beats++
        }
        assertEquals(1L, beats)
        assertTrue(beats < PXC_STREAMING_CADENCE_MIN_BEATS)
    }

    @Test
    fun `a dash with a 2s keepalive cadence arms the silence watchdog`() {
        // The simulator (and per open-cfmoto a CFDL26) heartbeats about every 2s during
        // streaming; that shape must still earn the fatal verdict when it later goes silent.
        val firstFrameAt = 5_000L
        var previous = 4_000L
        var beats = 0L
        var now = 6_000L
        repeat(4) {
            if (isStreamingPxcBeat(previous, firstFrameAt, now)) beats++
            previous = now
            now += 2_000L
        }
        assertTrue(beats >= PXC_STREAMING_CADENCE_MIN_BEATS)
    }

    @Test
    fun `reads the pull count the daemon reports for the dashboard`() {
        // [phase, 8 bytes big-endian]. That count is the only number in a whole session that the
        // DASHBOARD produced; every other counter the transport prints describes this phone.
        val payload = byteArrayOf(1, 0, 0, 0, 0, 0, 0, 1, 0x2C)
        assertEquals(300L, decodeVideoPullCount(payload))
    }

    @Test
    fun `a dash that pulled nothing reads as zero, not as missing`() {
        val closed = byteArrayOf(3, 0, 0, 0, 0, 0, 0, 0, 0)
        assertEquals(0L, decodeVideoPullCount(closed))
    }

    @Test
    fun `a truncated pull event costs a number in the log, never the session`() {
        assertEquals(0L, decodeVideoPullCount(null))
        assertEquals(0L, decodeVideoPullCount(byteArrayOf(1, 0, 0)))
    }

    private fun captureRequest(width: Int, height: Int): ByteArray = ByteBuffer
        .allocate(204)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putShort(0, width.toShort())
        .putShort(2, height.toShort())
        .array()
}
