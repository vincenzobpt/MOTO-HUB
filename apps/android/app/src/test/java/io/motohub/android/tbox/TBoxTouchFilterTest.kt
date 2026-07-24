package io.motohub.android.tbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TBoxTouchFilterTest {
    @Test
    fun dropsDuplicateContactCloseToActiveFinger() {
        val events = mutableListOf<TBoxEvent.Touch>()
        TBoxTouchFilter({}, events::add).use { filter ->
            filter.onTouch(TBoxEvent.Touch(0, 1, 100, 200))
            filter.onTouch(TBoxEvent.Touch(0, 2, 104, 202))
        }

        assertEquals(1, events.size)
        assertEquals(1, events.single().pointerId)
    }

    @Test
    fun stitchesShortDigitizerReleaseIntoOriginalPointer() {
        val events = mutableListOf<TBoxEvent.Touch>()
        TBoxTouchFilter({}, events::add).use { filter ->
            filter.onTouch(TBoxEvent.Touch(0, 1, 100, 200))
            filter.onTouch(TBoxEvent.Touch(1, 1, 100, 200))
            filter.onTouch(TBoxEvent.Touch(0, 9, 104, 203))
        }

        assertEquals(2, events.size)
        assertEquals(0, events[0].action)
        assertEquals(2, events[1].action)
        assertEquals(1, events[1].pointerId)
    }

    @Test
    fun eventuallyForwardsRealRelease() {
        val events = mutableListOf<TBoxEvent.Touch>()
        TBoxTouchFilter({}, events::add).use { filter ->
            filter.onTouch(TBoxEvent.Touch(0, 1, 100, 200))
            filter.onTouch(TBoxEvent.Touch(1, 1, 100, 200))
            Thread.sleep(100)
        }

        assertTrue(events.any { it.action == 1 && it.pointerId == 1 })
    }

    @Test
    fun stalePointerIsReleasedBeforeNextFrame() {
        val events = mutableListOf<TBoxEvent.Touch>()
        TBoxTouchFilter(
            log = {},
            downstream = events::add,
            policy = TBoxTouchPolicy(staleContactMillis = 1)
        ).use { filter ->
            filter.onTouch(TBoxEvent.Touch(0, 1, 100, 200))
            Thread.sleep(10)
            filter.onTouch(TBoxEvent.Touch(0, 2, 300, 400))
        }

        assertTrue(events.any { it.action == 1 && it.pointerId == 1 })
        assertTrue(events.any { it.action == 0 && it.pointerId == 2 })
    }

    @Test
    fun keepsAtMostTwoActivePointers() {
        val events = mutableListOf<TBoxEvent.Touch>()
        TBoxTouchFilter({}, events::add).use { filter ->
            filter.onTouch(TBoxEvent.Touch(0, 1, 100, 200))
            filter.onTouch(TBoxEvent.Touch(0, 2, 500, 600))
            filter.onTouch(TBoxEvent.Touch(0, 3, 700, 800))
        }

        assertTrue(events.any { it.action == 1 && it.pointerId == 1 })
        assertTrue(events.any { it.action == 0 && it.pointerId == 3 })
    }
}
