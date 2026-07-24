package io.motohub.android.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoQualityTest {
    @Test
    fun `balanced preserves the current MOTO-HUB bitrate`() {
        assertEquals(2_500_000, VideoQuality.BALANCED.bitrateFor(2_500_000))
    }

    @Test
    fun `smoother lowers bitrate using the reference multiplier`() {
        assertEquals(1_750_000, VideoQuality.SMOOTHER.bitrateFor(2_500_000))
    }

    @Test
    fun `sharper raises bitrate using the reference multiplier`() {
        assertEquals(4_000_000, VideoQuality.SHARPER.bitrateFor(2_500_000))
    }
}
