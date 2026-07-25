package io.motohub.android.feature.controls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaButtonBridgeTest {
    @Test
    fun `maps volume changes to dashboard gestures`() {
        assertEquals(HandlebarGesture.VOLUME_UP, gestureForVolumeDelta(1))
        assertEquals(HandlebarGesture.VOLUME_UP_DOUBLE, gestureForVolumeDelta(3))
        assertEquals(HandlebarGesture.VOLUME_DOWN, gestureForVolumeDelta(-1))
        assertEquals(HandlebarGesture.VOLUME_DOWN_DOUBLE, gestureForVolumeDelta(-3))
        assertNull(gestureForVolumeDelta(0))
    }

    @Test
    fun `new handlebar gestures keep the expected defaults`() {
        assertEquals(HandlebarAction.SELECT, HandlebarGesture.ENTER.defaultAction)
        assertEquals(HandlebarAction.ASSISTANT, HandlebarGesture.ENTER_LONG.defaultAction)
        assertEquals(HandlebarAction.ASSISTANT, HandlebarGesture.ENTER_DOUBLE.defaultAction)
        assertEquals(HandlebarAction.HOME, HandlebarGesture.TRACK_BACK_DOUBLE.defaultAction)
        assertEquals(HandlebarAction.BACK, HandlebarGesture.TRACK_FORWARD_DOUBLE.defaultAction)
    }

    @Test
    fun `timing options match the upstream release values`() {
        assertEquals(listOf(200L, 300L, 450L), DoubleTapDelay.entries.map { it.millis })
        assertEquals(listOf(500L, 600L, 800L), SelectHoldDelay.entries.map { it.millis })
    }

    @Test
    fun `simulator gesture ids map to handlebar gestures`() {
        HandlebarGesture.entries.forEach { gesture ->
            assertEquals(gesture, handlebarGestureForSimulatorId(gesture.id))
        }
        assertNull(handlebarGestureForSimulatorId("missing"))
    }
}
