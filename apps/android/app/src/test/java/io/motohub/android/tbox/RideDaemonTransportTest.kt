package io.motohub.android.tbox

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RideDaemonTransportTest {
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

    private fun captureRequest(width: Int, height: Int): ByteArray = ByteBuffer
        .allocate(204)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putShort(0, width.toShort())
        .putShort(2, height.toShort())
        .array()
}
