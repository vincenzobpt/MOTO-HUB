// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Byte-exact checks against the frames written down in the maintainer's EyLink protocol notes.
 * There is no dash to test against yet, so the note is the only reference these can hold to.
 */
class EyLinkProtocolTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun mirrorStartMatchesTheCapturedFrame() {
        assertArrayEquals(
            bytes(0xAA, 0xBB, 0xCC, 0x04, 0, 0, 0, 0, 0x04, 0, 0, 0, 0x80, 0x04, 0x00, 0x0A),
            EyLinkProtocol.mirrorStartFrame()
        )
    }

    @Test
    fun mirrorStopIsAnEmptyFrame() {
        assertArrayEquals(
            bytes(0xAA, 0xBB, 0xCC, 0x05, 0, 0, 0, 0, 0, 0, 0, 0),
            EyLinkProtocol.mirrorStopFrame()
        )
    }

    @Test
    fun widthHeightCarriesTheEncodedSize() {
        assertArrayEquals(
            bytes(0xAA, 0xBB, 0xCC, 0x06, 0, 0, 0, 0, 0x04, 0, 0, 0, 0x10, 0x03, 0x60, 0x01),
            EyLinkProtocol.widthHeightFrame(784, 352)
        )
    }

    @Test
    fun readsTheBluetoothConnectType() {
        assertEquals(
            EyLinkProtocol.BleConnectType.PHONE_P2P_GROUP,
            EyLinkProtocol.bleConnectType(EyLinkProtocol.bleText("-4\u0000\u0000".toByteArray()))
        )
        assertEquals(EyLinkProtocol.BleConnectType.PHONE_HOTSPOT, EyLinkProtocol.bleConnectType("-1"))
        assertEquals(EyLinkProtocol.BleConnectType.BLE_ONLY, EyLinkProtocol.bleConnectType("-3"))
        assertEquals(null, EyLinkProtocol.bleConnectType(""))
    }

    @Test
    fun waitsForARealDashAddress() {
        assertEquals(null, EyLinkProtocol.bleDashIp("0.0.0.0".toByteArray()))
        assertEquals(null, EyLinkProtocol.bleDashIp("1".toByteArray()))
        assertEquals(null, EyLinkProtocol.bleDashIp(ByteArray(8)))
        assertEquals("192.168.49.20", EyLinkProtocol.bleDashIp("192.168.49.20\u0000x".toByteArray()))
    }

    @Test
    fun heartbeatIsJsonBehindTheControlMagic() {
        val frame = EyLinkProtocol.heartbeatFrame()
        val json = EyLinkProtocol.HEARTBEAT_JSON.toByteArray()

        assertArrayEquals(bytes(0xAF, 0xBB, 0xCC, 0x0F, 0, 0, 0, 0), frame.copyOfRange(0, 8))
        assertEquals(json.size, frame[8].toInt() and 0xFF)
        assertArrayEquals(json, frame.copyOfRange(12, frame.size))
    }

    @Test
    fun parsesTheReportedOkReply() {
        val reply = bytes(0x4F, 0x4B, 0x10, 0x03, 0x60, 0x01, 0x10, 0x03, 0x60, 0x01, 0x1E, 0xD0, 0x07)

        assertEquals(
            EyLinkProtocol.Capabilities(784, 352, 784, 352, 30, 2000),
            EyLinkProtocol.parseOkReply(reply)
        )
    }

    @Test
    fun findsTheOkReplyBehindBytesNobodyHasSeenYet() {
        val reply = bytes(0x01, 0x02, 0x4F, 0x4B, 0x10, 0x03, 0x60, 0x01, 0x10, 0x03, 0x60, 0x01, 0x1E, 0xD0, 0x07)

        assertEquals(2, EyLinkProtocol.okReplyOffset(reply))
        assertEquals(784, EyLinkProtocol.parseOkReply(reply)?.videoWidth)
    }

    @Test
    fun anIncompleteReplyIsNotParsed() {
        assertNull(EyLinkProtocol.parseOkReply(bytes(0x4F, 0x4B, 0x10, 0x03, 0x60)))
    }

    @Test
    fun portraitIsUsedOnlyWhenLandscapeIsEmpty() {
        val portraitOnly = EyLinkProtocol.Capabilities(352, 784, 0, 0, 30, 2000)

        assertEquals(352, portraitOnly.videoWidth)
        assertEquals(784, portraitOnly.videoHeight)
    }

    @Test
    fun checksumSumsLittleEndianWords() {
        assertEquals(3, EyLinkProtocol.checksum16(bytes(1, 0, 0, 0, 2, 0, 0, 0)))
    }

    @Test
    fun checksumPadsATrailingPartialWordAtTheTop() {
        // 0x04030201 + 0x00000005 = 0x04030206, folded: 0x0206 + 0x0403.
        assertEquals(0x0609, EyLinkProtocol.checksum16(bytes(1, 2, 3, 4, 5)))
    }

    @Test
    fun checksumFoldsCarriesUntilSixteenBitsRemain() {
        // 2 x 0xFFFFFFFF = 0x1FFFFFFFE -> 0xFFFE + 0x1FFFF = 0x2FFFD -> 0xFFFD + 0x2.
        assertEquals(0xFFFF, EyLinkProtocol.checksum16(ByteArray(8) { 0xFF.toByte() }))
    }

    @Test
    fun videoDataChecksumsTheAccessUnitButNotTheTimestamp() {
        val accessUnit = bytes(0, 0, 0, 1, 0x65, 0x88)
        val frame = EyLinkProtocol.videoDataFrame(frameIndex = 65_537, timestampMs = 0x01020304, annexB = accessUnit)

        assertArrayEquals(bytes(0xAA, 0xBB, 0xCC, 0x00), frame.copyOfRange(0, 4))
        // The index wraps at 65536.
        assertArrayEquals(bytes(0x01, 0x00), frame.copyOfRange(4, 6))
        val checksum = EyLinkProtocol.checksum16(accessUnit)
        assertArrayEquals(bytes(checksum and 0xFF, checksum ushr 8), frame.copyOfRange(6, 8))
        assertArrayEquals(bytes(4 + accessUnit.size, 0, 0, 0), frame.copyOfRange(8, 12))
        assertArrayEquals(bytes(0x04, 0x03, 0x02, 0x01), frame.copyOfRange(12, 16))
        assertArrayEquals(accessUnit, frame.copyOfRange(16, frame.size))
    }
}
