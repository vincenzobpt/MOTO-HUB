package io.motohub.android.feature.ridedashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideDashboardLayoutControllerTest {
    @Test
    fun upCyclesThroughTheRequestedPanelSequence() {
        val controller = RideDashboardLayoutController()

        assertVisibility(controller.snapshot(), left = true, right = true)
        assertVisibility(controller.onUp(), left = true, right = false)
        assertVisibility(controller.onUp(), left = false, right = false)
        assertVisibility(controller.onUp(), left = false, right = true)
        assertVisibility(controller.onUp(), left = true, right = true)
    }

    @Test
    fun downRestoresTheLayoutThatWasActiveBeforeFullscreen() {
        val controller = RideDashboardLayoutController()
        controller.onUp()

        val fullscreen = controller.onDown()
        assertTrue(fullscreen.fullscreenMap)
        assertFalse(fullscreen.chromeVisible)
        assertVisibility(fullscreen, left = false, right = false)

        val restored = controller.onDown()
        assertFalse(restored.fullscreenMap)
        assertEquals(RideDashboardLayoutPhase.RIGHT_PANEL_HIDDEN, restored.phase)
        assertVisibility(restored, left = true, right = false)
    }

    @Test
    fun upDoesNotAlterTheRememberedLayoutWhileFullscreen() {
        val controller = RideDashboardLayoutController()
        controller.onUp()
        controller.onDown()

        val unchanged = controller.onUp()
        assertTrue(unchanged.fullscreenMap)
        assertEquals(RideDashboardLayoutPhase.RIGHT_PANEL_HIDDEN, unchanged.phase)

        val restored = controller.onDown()
        assertVisibility(restored, left = true, right = false)
    }

    private fun assertVisibility(
        snapshot: RideDashboardLayoutSnapshot,
        left: Boolean,
        right: Boolean
    ) {
        assertEquals(left, snapshot.leftPanelVisible)
        assertEquals(right, snapshot.rightPanelVisible)
    }
}
