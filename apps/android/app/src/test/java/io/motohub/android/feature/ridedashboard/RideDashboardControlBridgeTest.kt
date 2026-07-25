package io.motohub.android.feature.ridedashboard

import io.motohub.android.feature.controls.HandlebarGesture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideDashboardControlBridgeTest {
    private val received = mutableListOf<HandlebarGesture>()
    private val handler: (HandlebarGesture) -> Boolean = { gesture ->
        received += gesture
        true
    }

    @After
    fun tearDown() {
        RideDashboardControlBridge.clear(handler)
    }

    @Test
    fun dashboardCommandsAreRejectedWithoutAnActiveHandler() {
        assertFalse(RideDashboardControlBridge.isReady())
        assertFalse(RideDashboardControlBridge.cyclePanels())
        assertFalse(RideDashboardControlBridge.toggleFullscreenMap())
    }

    @Test
    fun dashboardCommandsReachTheInstalledHandler() {
        RideDashboardControlBridge.install(handler)

        assertTrue(RideDashboardControlBridge.isReady())
        assertTrue(RideDashboardControlBridge.cyclePanels())
        assertTrue(RideDashboardControlBridge.toggleFullscreenMap())
        assertEquals(
            listOf(HandlebarGesture.VOLUME_UP, HandlebarGesture.VOLUME_DOWN),
            received
        )
    }
}
