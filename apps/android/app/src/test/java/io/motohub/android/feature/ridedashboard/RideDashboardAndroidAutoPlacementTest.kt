package io.motohub.android.feature.ridedashboard

import io.motohub.android.androidauto.AndroidAutoDisplayMode
import org.junit.Assert.assertEquals
import org.junit.Test

class RideDashboardAndroidAutoPlacementTest {
    @Test
    fun `portrait fill uses the full width and extends below a short map region`() {
        val placement = calculateRideDashboardAndroidAutoPlacement(
            containerLeft = 0f,
            containerTop = 52f,
            containerRight = 800f,
            containerBottom = 1048f,
            sourceWidth = 720,
            sourceHeight = 1280,
            fill = true,
            alignFillToTop = true
        )

        assertEquals(0f, placement.left, 0.01f)
        assertEquals(52f, placement.top, 0.01f)
        assertEquals(800f, placement.right, 0.01f)
        assertEquals(1474.22f, placement.bottom, 0.02f)
    }

    @Test
    fun `portrait fullscreen matches a nine by sixteen canvas without bars`() {
        val placement = calculateRideDashboardAndroidAutoPlacement(
            containerLeft = 0f,
            containerTop = 0f,
            containerRight = 1080f,
            containerBottom = 1920f,
            sourceWidth = 720,
            sourceHeight = 1280,
            fill = true,
            alignFillToTop = true
        )

        assertEquals(0f, placement.left, 0.01f)
        assertEquals(0f, placement.top, 0.01f)
        assertEquals(1080f, placement.right, 0.01f)
        assertEquals(1920f, placement.bottom, 0.01f)
    }

    @Test
    fun `landscape fit keeps the complete source visible`() {
        val placement = calculateRideDashboardAndroidAutoPlacement(
            containerLeft = 240f,
            containerTop = 52f,
            containerRight = 580f,
            containerBottom = 350f,
            sourceWidth = 800,
            sourceHeight = 480,
            fill = false
        )

        assertEquals(240f, placement.left, 0.01f)
        assertEquals(99f, placement.top, 0.01f)
        assertEquals(580f, placement.right, 0.01f)
        assertEquals(303f, placement.bottom, 0.01f)
    }

    @Test
    fun `landscape fill covers the container width even with portrait source`() {
        val placement = calculateRideDashboardAndroidAutoPlacement(
            containerLeft = 0f,
            containerTop = 0f,
            containerRight = 800f,
            containerBottom = 480f,
            sourceWidth = 720,
            sourceHeight = 1280,
            fill = true
        )

        assertEquals(0f, placement.left, 0.01f)
        assertEquals(800f, placement.right, 0.01f)
        assertEquals(-471.11f, placement.top, 0.02f)
        assertEquals(951.11f, placement.bottom, 0.02f)
    }

    @Test
    fun `landscape safe fill limits crop for wide ride dashboard fullscreen`() {
        val placement = calculateRideDashboardAndroidAutoPlacement(
            containerLeft = 0f,
            containerTop = 0f,
            containerRight = 800f,
            containerBottom = 384f,
            sourceWidth = 1280,
            sourceHeight = 720,
            fill = true,
            maxFillOverflowFraction = 0.08f
        )

        assertEquals(31.36f, placement.left, 0.02f)
        assertEquals(-15.36f, placement.top, 0.02f)
        assertEquals(768.64f, placement.right, 0.02f)
        assertEquals(399.36f, placement.bottom, 0.02f)
    }

    @Test
    fun `stretch uses all released dashboard space without bars`() {
        val placement = calculateRideDashboardAndroidAutoPlacement(
            containerLeft = 0f,
            containerTop = 52f,
            containerRight = 800f,
            containerBottom = 384f,
            sourceWidth = 800,
            sourceHeight = 480,
            displayMode = AndroidAutoDisplayMode.STRETCH
        )

        assertEquals(0f, placement.left, 0.01f)
        assertEquals(52f, placement.top, 0.01f)
        assertEquals(800f, placement.right, 0.01f)
        assertEquals(384f, placement.bottom, 0.01f)
    }

    @Test
    fun `crop fills dashboard fullscreen without residual side bars`() {
        val placement = calculateRideDashboardAndroidAutoPlacement(
            containerLeft = 0f,
            containerTop = 0f,
            containerRight = 800f,
            containerBottom = 384f,
            sourceWidth = 1280,
            sourceHeight = 720,
            displayMode = AndroidAutoDisplayMode.FILL
        )

        assertEquals(0f, placement.left, 0.01f)
        assertEquals(800f, placement.right, 0.01f)
        assertEquals(-33f, placement.top, 0.01f)
        assertEquals(417f, placement.bottom, 0.01f)
    }
}
