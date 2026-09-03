// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.androidauto

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression cover for the log line that sent support case 6A55-7ACB after the wrong suspect:
 * every deliberate stop of the Android Auto session was recorded as "stopped by Android", because
 * stopService() reaches the service as a bare onDestroy() with no reason attached.
 */
class AndroidAutoStopReasonTest {
    @After
    fun tearDown() = AndroidAutoStopReason.clear()

    @Test
    fun `a stop nobody in the app asked for is still Android's`() {
        assertNull(AndroidAutoStopReason.take())
    }

    @Test
    fun `the caller's reason reaches the service`() {
        AndroidAutoStopReason.publish("A companion app asked for the Android Auto session to stop.")

        assertEquals(
            "A companion app asked for the Android Auto session to stop.",
            AndroidAutoStopReason.take()
        )
    }

    @Test
    fun `a reason is consumed once, so the next destroy is not misattributed`() {
        AndroidAutoStopReason.publish("Android Auto stopped by the user.")
        AndroidAutoStopReason.take()

        // The session that dies next was killed by something else - saying "the user" here is
        // exactly the lie this object exists to stop, in the other direction.
        assertNull(AndroidAutoStopReason.take())
    }

    @Test
    fun `a stop that never reached a running service cannot name the next session`() {
        // stop() publishes before stopService(), which does nothing when no service is running:
        // the reason would otherwise sit there and be spent on the following session's death.
        AndroidAutoStopReason.publish("Android Auto stopped by the user.")

        AndroidAutoStopReason.clear()

        assertNull(AndroidAutoStopReason.take())
    }
}
