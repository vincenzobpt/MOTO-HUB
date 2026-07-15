package io.motohub.android.tbox

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TBoxNetworkConnectorTest {
    @Test
    fun recognizesTBoxSubnetWithoutMatchingHomeWifi() {
        assertTrue(isTBoxLinkAddress("192.168.0.23"))
        assertFalse(isTBoxLinkAddress("192.168.50.7"))
        assertFalse(isTBoxLinkAddress("10.0.0.4"))
        assertFalse(isTBoxLinkAddress("fe80::1"))
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
