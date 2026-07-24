package io.motohub.android.tbox

import io.motohub.android.encoding.EncoderProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TBoxTouchTransformTest {
    @Test
    fun `maps raw T-Box edges to macroblock-aligned canvas edges`() {
        val transform = TBoxTouchTransform.forVideoConfiguration(
            TBoxVideoConfiguration(
                rawArea = TBoxEvent.VideoArea(800, 951),
                encoderProfile = EncoderProfile(800, 944),
                source = TBoxVideoAreaSource.LIVE
            )
        )

        assertEquals(0 to 0, transform.map(0, 0))
        assertEquals(799 to 943, transform.map(799, 950))
        assertEquals(400 to 471, transform.map(400, 475))
    }

    @Test
    fun `supports a projection rectangle inside physical touch coordinates`() {
        val transform = TBoxTouchTransform(
            input = TBoxTouchBounds(left = 0, top = 96, width = 800, height = 384),
            outputWidth = 800,
            outputHeight = 384
        )

        assertNull(transform.map(400, 95))
        assertEquals(0 to 0, transform.map(0, 96))
        assertEquals(799 to 383, transform.map(799, 479))
    }

    @Test
    fun `rejects coordinates outside the declared touch domain`() {
        val transform = TBoxTouchTransform(
            input = TBoxTouchBounds(0, 0, 720, 1280),
            outputWidth = 720,
            outputHeight = 1280
        )

        assertNull(transform.map(-1, 0))
        assertNull(transform.map(720, 0))
        assertNull(transform.map(0, 1280))
    }
}
