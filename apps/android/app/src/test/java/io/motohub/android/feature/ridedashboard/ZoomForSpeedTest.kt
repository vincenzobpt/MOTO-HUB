package io.motohub.android.feature.ridedashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomForSpeedTest {
    @Test
    fun `zooms in when stationary or slow`() {
        assertEquals(17, zoomForSpeed(0f, baseZoom = 16))
        assertEquals(17, zoomForSpeed(9.9f, baseZoom = 16))
    }

    @Test
    fun `uses base zoom at city riding speed`() {
        assertEquals(16, zoomForSpeed(10f, baseZoom = 16))
        assertEquals(16, zoomForSpeed(49.9f, baseZoom = 16))
    }

    @Test
    fun `zooms out at highway speed`() {
        assertEquals(15, zoomForSpeed(50f, baseZoom = 16))
        assertEquals(15, zoomForSpeed(89.9f, baseZoom = 16))
    }

    @Test
    fun `zooms out further at very high speed`() {
        assertEquals(14, zoomForSpeed(90f, baseZoom = 16))
        assertEquals(14, zoomForSpeed(180f, baseZoom = 16))
    }
}
