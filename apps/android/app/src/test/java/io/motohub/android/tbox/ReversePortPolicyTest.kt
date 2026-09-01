// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule that decides whether a rider waits twelve seconds or one for a busy reverse port.
 *
 * The case that produced it, 36A3-FD37-1DD7: eight full waits in eight minutes on a phone where
 * another EasyConn app held the ports the whole time, and not one release. Nothing there was ever
 * a hand-off, and the code could have known.
 */
class ReversePortPolicyTest {

    private val handoff = ReversePortProbe.HANDOFF_WAIT_MS
    private val foreign = ReversePortProbe.FOREIGN_WAIT_MS
    private val window = ReversePortProbe.HANDOFF_WINDOW_MS

    @Test
    fun withoutANativeStopOfOurOwnTheHolderCanOnlyBeAnotherApp() {
        assertEquals(foreign, reversePortWaitMillis(nowElapsed = 60_000L, lastNativeStopElapsed = 0L))
    }

    @Test
    fun aNativeSessionStoppedMomentsAgoStillEarnsThePatientWait() {
        assertEquals(
            handoff,
            reversePortWaitMillis(nowElapsed = 60_000L, lastNativeStopElapsed = 59_000L)
        )
    }

    @Test
    fun theWindowIsInclusiveAtItsEdge() {
        assertEquals(
            handoff,
            reversePortWaitMillis(nowElapsed = 60_000L, lastNativeStopElapsed = 60_000L - window)
        )
    }

    @Test
    fun aStopOlderThanTheWindowIsNoLongerAPlausibleExplanation() {
        assertEquals(
            foreign,
            reversePortWaitMillis(nowElapsed = 60_000L, lastNativeStopElapsed = 60_000L - window - 1)
        )
    }

    /**
     * elapsedRealtime is monotonic so this cannot happen; if it ever did, being patient costs a
     * rider eleven seconds while the other direction blames another app for our own sockets.
     */
    @Test
    fun aClockThatRanBackwardsKeepsTheOldPatientBehaviour() {
        assertEquals(
            handoff,
            reversePortWaitMillis(nowElapsed = 10_000L, lastNativeStopElapsed = 20_000L)
        )
    }

    /**
     * The whole point, stated as the case that failed: attempt after attempt, no session of ours
     * ever stopped, and the wait must not keep growing back to twelve seconds.
     */
    @Test
    fun repeatedAttemptsAgainstAForeignHolderNeverEarnThePatientWait() {
        var now = 0L
        repeat(8) {
            now += 12_000L
            assertEquals(foreign, reversePortWaitMillis(now, lastNativeStopElapsed = 0L))
        }
    }
}
