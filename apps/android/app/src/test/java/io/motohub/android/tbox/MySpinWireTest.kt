// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import java.io.ByteArrayInputStream
import java.io.EOFException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MySpinWireTest {

    @Test
    fun theHeaderIsTypeThenMetadataThenLengthAllLittleEndian() {
        val bytes = MySpinWire.encode(MySpinWire.Packet(26, byteArrayOf(7, 8, 9), metadata = 0x01020304))
        assertArrayEquals(byteArrayOf(26, 4, 3, 2, 1, 3, 0, 0, 0, 7, 8, 9), bytes)
    }

    @Test
    fun aPacketReadsBackAsWritten() {
        val written = MySpinWire.Packet(128, byteArrayOf(1, 2, 3, 4), metadata = 42)
        val read = MySpinWire.read(ByteArrayInputStream(MySpinWire.encode(written)))!!
        assertEquals(128, read.type)
        assertEquals(42, read.metadata)
        assertArrayEquals(written.payload, read.payload)
    }

    @Test
    fun aCleanEndOfStreamIsNoPacket() {
        assertNull(MySpinWire.read(ByteArrayInputStream(ByteArray(0))))
    }

    @Test(expected = EOFException::class)
    fun aTruncatedPayloadIsAnError() {
        MySpinWire.read(ByteArrayInputStream(byteArrayOf(1, 0, 0, 0, 0, 5, 0, 0, 0, 1, 2)))
    }

    @Test(expected = MySpinProtocolException::class)
    fun aNegativeLengthIsAnError() {
        MySpinWire.read(ByteArrayInputStream(byteArrayOf(1, 0, 0, 0, 0, -1, -1, -1, -1)))
    }

    @Test
    fun theReaderIsLittleEndian() {
        val reader = MySpinWire.Reader(byteArrayOf(0x34, 0x12, 0x78, 0x56, 0x34, 0x12))
        assertEquals(0x1234, reader.u16())
        assertEquals(0x12345678, reader.i32())
    }

    @Test
    fun anUnsignedFieldKeepsItsHighBit() {
        // KEY_DISPLAY_VEHICLE_SPEED, above Int.MAX_VALUE.
        val bytes = MySpinWire.Writer().i32(2_201_960_939L.toInt()).build()
        assertEquals(2_201_960_939L, MySpinWire.Reader(bytes).u32())
    }

    @Test
    fun stringsCarryTheirEncoding() {
        val utf8 = MySpinWire.Writer().string("Città").build()
        assertEquals("Città", MySpinWire.Reader(utf8).string())

        val latin1 = byteArrayOf(2, 3, 0, 'P'.code.toByte(), 0xE9.toByte(), 'r'.code.toByte())
        assertEquals("Pér", MySpinWire.Reader(latin1).string())
    }

    @Test(expected = MySpinProtocolException::class)
    fun anUnknownStringEncodingIsAnError() {
        MySpinWire.Reader(byteArrayOf(3, 0, 0)).string()
    }
}
