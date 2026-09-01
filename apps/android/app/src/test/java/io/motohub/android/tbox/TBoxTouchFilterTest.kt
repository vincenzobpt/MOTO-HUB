// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
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

    /**
     * Regression for the Android Auto task bar that would not scroll: the finger doing the dragging
     * used to be released by the stale sweep whenever the dash left a gap between two of its MOVE
     * frames, turning one drag into a run of taps.
     */
    @Test
    fun slowDragKeepsTheSameFingerDownInsteadOfRestartingIt() {
        val events = mutableListOf<TBoxEvent.Touch>()
        TBoxTouchFilter(
            log = {},
            downstream = events::add,
            policy = TBoxTouchPolicy(staleContactMillis = 1)
        ).use { filter ->
            filter.onTouch(TBoxEvent.Touch(0, 1, 100, 200))
            Thread.sleep(10)
            filter.onTouch(TBoxEvent.Touch(2, 1, 140, 200))
            Thread.sleep(10)
            filter.onTouch(TBoxEvent.Touch(2, 1, 180, 200))
        }

        assertEquals(listOf(0, 2, 2), events.map { it.action })
        assertTrue(events.none { it.action == 1 })
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
