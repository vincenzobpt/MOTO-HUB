// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import io.motohub.android.feature.controls.HandlebarGesture
import io.motohub.android.tbox.MySpinProtocol.Key
import io.motohub.android.tbox.MySpinProtocol.KeyAction

/**
 * Turns a mySPIN dash key into the handlebar gesture the rider's own mapping already knows, the
 * same way ThinkerRide's MUSIC controls are fed in. Each gesture is picked for its default action:
 * up/down and the rotary scroll, left/right keep their track meaning, OK selects, and BACK uses the
 * gesture whose default is "back".
 *
 * A dash reports a key either as CLICK/LONG_PRESS or as PRESS/RELEASE. The Suzuki GSX-S1000GX sends
 * only PRESS and RELEASE, milliseconds apart (report 303A-85C3-874E: 122 presses, all ignored while
 * only CLICK counted). So a key fires on PRESS once the dash has shown PRESS for it, and from then
 * on its CLICK/LONG_PRESS are ignored, so a dash that reports all of them does not fire twice. One
 * instance per session: what a key reports is learnt per dash.
 *
 * shortcut: PRESS carries no hold time, so such a key has no long-press gesture; time PRESS to
 * RELEASE if a rider needs one.
 */
class MySpinHandlebar {
    private val pressKeys = mutableSetOf<Key>()

    fun gestureFor(key: Key?, action: KeyAction?): HandlebarGesture? {
        if (key == null) return null
        val held = when (action) {
            KeyAction.PRESS -> {
                pressKeys += key
                false
            }
            KeyAction.CLICK -> if (key in pressKeys) return null else false
            KeyAction.LONG_PRESS -> if (key in pressKeys) return null else true
            else -> return null
        }
        return when (key) {
            Key.OK -> if (held) HandlebarGesture.ENTER_LONG else HandlebarGesture.ENTER
            Key.BACK -> if (held) null else HandlebarGesture.ENTER_DOUBLE
            Key.UP, Key.ROTATE_COUNTERCLOCKWISE -> if (held) null else HandlebarGesture.VOLUME_UP
            Key.DOWN, Key.ROTATE_CLOCKWISE -> if (held) null else HandlebarGesture.VOLUME_DOWN
            Key.LEFT -> if (held) HandlebarGesture.TRACK_BACK_LONG else HandlebarGesture.TRACK_BACK
            Key.RIGHT -> if (held) HandlebarGesture.TRACK_FORWARD_LONG else HandlebarGesture.TRACK_FORWARD
        }
    }
}
