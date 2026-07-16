package io.motohub.android.tbox

import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TBoxNetworkConnectorTest {
    @Test
    fun acceptsUsableIpv4AddressesAcrossTBoxDhcpSubnets() {
        assertTrue(isUsableTBoxIpv4Address(InetAddress.getByName("192.168.0.23")))
        assertTrue(isUsableTBoxIpv4Address(InetAddress.getByName("192.168.43.91")))
        assertTrue(isUsableTBoxIpv4Address(InetAddress.getByName("10.42.0.8")))
        assertTrue(isUsableTBoxIpv4Address(InetAddress.getByName("172.20.10.4")))
    }

    @Test
    fun rejectsAddressesThatCannotCarryTheEasyConnIpv4Session() {
        assertFalse(isUsableTBoxIpv4Address(InetAddress.getByName("0.0.0.0")))
        assertFalse(isUsableTBoxIpv4Address(InetAddress.getByName("127.0.0.1")))
        assertFalse(isUsableTBoxIpv4Address(InetAddress.getByName("169.254.12.4")))
        assertFalse(isUsableTBoxIpv4Address(InetAddress.getByName("224.0.0.251")))
        assertFalse(isUsableTBoxIpv4Address(InetAddress.getByName("fe80::1")))
    }

    @Test
    fun decodesEasyConnTouchFrame() {
        val payload = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0, 2.toShort())
            putShort(2, 645.toShort())
            putInt(4, 217)
        }.array()

        assertEquals(TBoxEvent.Touch(action = 0, x = 645, y = 217), decodeTBoxTouch(payload))
    }

    @Test
    fun rejectsUnknownOrTruncatedTouchFrames() {
        assertNull(decodeTBoxTouch(byteArrayOf(1, 2, 3)))
        val unknown = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0, 99.toShort())
        }.array()
        assertNull(decodeTBoxTouch(unknown))
    }
}
