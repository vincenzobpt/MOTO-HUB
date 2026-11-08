// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import io.motohub.android.feature.controls.HandlebarAction
import io.motohub.android.feature.controls.HandlebarGesture
import io.motohub.android.tbox.MySpinProtocol.Key
import io.motohub.android.tbox.MySpinProtocol.KeyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MySpinHandlebarTest {

    private fun click(key: Key) = MySpinHandlebar().gestureFor(key, KeyAction.CLICK)

    @Test
    fun eachKeyLandsOnAGestureWhoseDefaultMatchesIt() {
        assertEquals(HandlebarAction.SELECT, click(Key.OK)!!.defaultAction)
        assertEquals(HandlebarAction.BACK, click(Key.BACK)!!.defaultAction)
        assertEquals(HandlebarAction.SCROLL_BACK, click(Key.UP)!!.defaultAction)
        assertEquals(HandlebarAction.SCROLL_FORWARD, click(Key.DOWN)!!.defaultAction)
        assertEquals(HandlebarAction.SCROLL_BACK, click(Key.LEFT)!!.defaultAction)
        assertEquals(HandlebarAction.SCROLL_FORWARD, click(Key.RIGHT)!!.defaultAction)
    }

    @Test
    fun theRotaryScrollsLikeUpAndDown() {
        assertEquals(click(Key.DOWN), click(Key.ROTATE_CLOCKWISE))
        assertEquals(click(Key.UP), click(Key.ROTATE_COUNTERCLOCKWISE))
    }

    @Test
    fun aLongPressUsesTheHoldGestureWhereOneExists() {
        assertEquals(HandlebarGesture.ENTER_LONG, MySpinHandlebar().gestureFor(Key.OK, KeyAction.LONG_PRESS))
        assertEquals(HandlebarGesture.TRACK_FORWARD_LONG, MySpinHandlebar().gestureFor(Key.RIGHT, KeyAction.LONG_PRESS))
        assertNull(MySpinHandlebar().gestureFor(Key.UP, KeyAction.LONG_PRESS))
    }

    @Test
    fun releaseAndUnknownKeysFireNothing() {
        assertNull(MySpinHandlebar().gestureFor(Key.OK, KeyAction.RELEASE))
        assertNull(MySpinHandlebar().gestureFor(null, KeyAction.CLICK))
        assertNull(MySpinHandlebar().gestureFor(Key.OK, null))
    }

    @Test
    fun aSuzukiKeyFiresOnPressOnce() {
        // Report 303A-85C3-874E: the GSX-S1000GX sends PRESS then RELEASE, never CLICK.
        val keys = MySpinHandlebar()
        assertEquals(HandlebarGesture.ENTER, keys.gestureFor(Key.OK, KeyAction.PRESS))
        assertNull(keys.gestureFor(Key.OK, KeyAction.RELEASE))
        assertEquals(click(Key.DOWN), keys.gestureFor(Key.DOWN, KeyAction.PRESS))
    }

    @Test
    fun aDashReportingPressAndClickFiresOnlyOnce() {
        val keys = MySpinHandlebar()
        assertEquals(HandlebarGesture.TRACK_FORWARD, keys.gestureFor(Key.RIGHT, KeyAction.PRESS))
        assertNull(keys.gestureFor(Key.RIGHT, KeyAction.RELEASE))
        assertNull(keys.gestureFor(Key.RIGHT, KeyAction.CLICK))
        assertNull(keys.gestureFor(Key.RIGHT, KeyAction.LONG_PRESS))
        // A key never seen as PRESS keeps the CLICK behaviour.
        assertEquals(HandlebarGesture.ENTER, keys.gestureFor(Key.OK, KeyAction.CLICK))
    }
}
