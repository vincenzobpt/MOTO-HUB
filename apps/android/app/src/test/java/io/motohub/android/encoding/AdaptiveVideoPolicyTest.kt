// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.encoding

import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveVideoPolicyTest {
    @Test
    fun `a healthy link runs at the ceiling it was given`() {
        val decision = AdaptiveVideoPolicy.decide(
            baseBitrate = 4_000_000,
            baseFrameRate = 30,
            thermalStatus = PowerManager.THERMAL_STATUS_NONE,
            previousLinkFactor = AdaptiveVideoPolicy.LINK_MAX,
            lostFrames = 0
        )
        assertEquals(4_000_000, decision.bitrate)
        assertEquals(30, decision.frameRate)
    }

    @Test
    fun `a link losing frames gives up frame rate as well as bitrate`() {
        // Cutting the bitrate alone leaves the same number of frames queued on the transport whose
        // queue is what overflowed, which is how a CFMOTO MTX800 kept feeding 30fps into a pipe
        // that was dropping ~280 frames a session.
        val decision = AdaptiveVideoPolicy.decide(
            baseBitrate = 4_000_000,
            baseFrameRate = 30,
            thermalStatus = PowerManager.THERMAL_STATUS_NONE,
            previousLinkFactor = AdaptiveVideoPolicy.LINK_MAX,
            lostFrames = AdaptiveVideoPolicy.LOSS_TRIGGER
        )
        assertTrue(decision.bitrate < 4_000_000)
        assertTrue(decision.frameRate < 30)
    }

    @Test
    fun `the backoff never paces below the slideshow floor`() {
        var factor = AdaptiveVideoPolicy.LINK_MAX
        repeat(50) {
            factor = AdaptiveVideoPolicy.nextLinkFactor(factor, AdaptiveVideoPolicy.LOSS_TRIGGER)
        }
        assertEquals(AdaptiveVideoPolicy.LINK_MIN, factor, 0.001f)
        assertEquals(
            AdaptiveVideoPolicy.MIN_FRAME_RATE,
            AdaptiveVideoPolicy.linkFrameRateCap(baseFrameRate = 30, linkFactor = factor)
        )
    }

    @Test
    fun `a ceiling already below the floor is never raised by the backoff`() {
        assertEquals(
            10,
            AdaptiveVideoPolicy.linkFrameRateCap(baseFrameRate = 10, linkFactor = 0.4f)
        )
    }

    @Test
    fun `a recovered link climbs back to the ceiling`() {
        var factor = AdaptiveVideoPolicy.LINK_MIN
        repeat(20) { factor = AdaptiveVideoPolicy.nextLinkFactor(factor, 0) }
        assertEquals(AdaptiveVideoPolicy.LINK_MAX, factor, 0.001f)
        assertEquals(
            24,
            AdaptiveVideoPolicy.linkFrameRateCap(baseFrameRate = 24, linkFactor = factor)
        )
    }

    @Test
    fun `a link that stays clean climbs back from the floor in six ticks`() {
        var factor = AdaptiveVideoPolicy.LINK_MIN
        var streak = 0
        var ticks = 0
        while (factor < AdaptiveVideoPolicy.LINK_MAX) {
            streak = AdaptiveVideoPolicy.nextCleanStreak(streak, lostFrames = 0)
            factor = AdaptiveVideoPolicy.nextLinkFactor(factor, lostFrames = 0, cleanStreak = streak)
            ticks++
        }
        assertEquals(6, ticks)
    }

    @Test
    fun `any lost frame restarts the slow climb`() {
        val decision = AdaptiveVideoPolicy.decide(
            baseBitrate = 4_000_000,
            baseFrameRate = 30,
            thermalStatus = PowerManager.THERMAL_STATUS_NONE,
            previousLinkFactor = 0.5f,
            lostFrames = 1,
            previousCleanStreak = 7
        )
        assertEquals(0, decision.cleanStreak)
        assertEquals(0.55f, decision.linkFactor, 0.001f)
    }

    @Test
    fun `a struggling tick still sheds whatever the streak was`() {
        val decision = AdaptiveVideoPolicy.decide(
            baseBitrate = 4_000_000,
            baseFrameRate = 30,
            thermalStatus = PowerManager.THERMAL_STATUS_NONE,
            previousLinkFactor = 1.0f,
            lostFrames = AdaptiveVideoPolicy.LOSS_TRIGGER,
            previousCleanStreak = 9
        )
        assertEquals(0, decision.cleanStreak)
        assertEquals(AdaptiveVideoPolicy.LINK_SHED, decision.linkFactor, 0.001f)
    }

    @Test
    fun `a dash polling slower than the floor caps the frame rate below it`() {
        // 8F15-E4D6-0243: the dash polled ~11.6 times a second and the 12fps floor overflowed it.
        val decision = AdaptiveVideoPolicy.decide(
            baseBitrate = 2_500_000,
            baseFrameRate = 20,
            thermalStatus = PowerManager.THERMAL_STATUS_NONE,
            previousLinkFactor = AdaptiveVideoPolicy.LINK_MIN,
            lostFrames = 50,
            dashPullRate = 11.6f
        )
        assertEquals(10, decision.pullRateCap)
        assertEquals(10, decision.frameRate)
    }

    @Test
    fun `the pull rate only sets the cap on a tick that lost frames`() {
        assertNull(AdaptiveVideoPolicy.nextPullRateCap(null, 30, dashPullRate = 5f, lostFrames = 0, cleanStreak = 1))
        assertEquals(10, AdaptiveVideoPolicy.nextPullRateCap(10, 30, dashPullRate = 5f, lostFrames = 0, cleanStreak = 1))
    }

    @Test
    fun `a clean link climbs the pull cap one frame per tick and then lets go`() {
        val streak = AdaptiveVideoPolicy.FASTER_REGAIN_AFTER_CLEAN_TICKS
        assertEquals(11, AdaptiveVideoPolicy.nextPullRateCap(10, 30, null, lostFrames = 0, cleanStreak = streak))
        assertNull(AdaptiveVideoPolicy.nextPullRateCap(29, 30, null, lostFrames = 0, cleanStreak = streak))
    }
}
