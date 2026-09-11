// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry's Context-bound half is SharedPreferences and nothing else; the decision is here.
 */
class TBoxClockAskRegistryTest {

    private val voge = "SSDQ01/51/37504/V0.0.1"

    @Test
    fun aDashboardSeenAskingIsRememberedForTheNextSession() {
        val known = TBoxClockAskRegistry.withFingerprint(emptySet(), voge)
        assertEquals(setOf(voge), known)
        assertTrue(TBoxClockAskRegistry.asksForTime(known!!, voge))
    }

    @Test
    fun aDashboardNeverSeenAskingIsStillOfferedThePush() {
        assertFalse(TBoxClockAskRegistry.asksForTime(setOf(voge), "SSDQ01/51/37501/V0.0.1"))
        assertFalse(TBoxClockAskRegistry.asksForTime(emptySet(), voge))
    }

    /**
     * The verdict is per firmware, so a second bike on the same fingerprint inherits it and a
     * bike on different firmware does not.
     */
    @Test
    fun theVerdictFollowsTheFirmwareNotTheMotorcycle() {
        val known = setOf(voge)
        assertTrue(TBoxClockAskRegistry.asksForTime(known, voge))
        assertFalse(TBoxClockAskRegistry.asksForTime(known, "Zontes/12/368/V1.2.0"))
    }

    /**
     * A dashboard whose CLIENT_INFO carries none of the fingerprint fields reads as null. Storing
     * or matching that would silence the unsolicited push for every other unidentified dash -
     * exactly the panels the push was written for.
     */
    @Test
    fun anUnusableFingerprintIsNeitherStoredNorMatched() {
        assertNull(TBoxClockAskRegistry.withFingerprint(emptySet(), null))
        assertNull(TBoxClockAskRegistry.withFingerprint(emptySet(), ""))
        assertNull(TBoxClockAskRegistry.withFingerprint(emptySet(), "   "))
        assertFalse(TBoxClockAskRegistry.asksForTime(setOf(""), null))
        assertFalse(TBoxClockAskRegistry.asksForTime(setOf(""), ""))
    }

    /** Second and later sessions must not rewrite the set, so the log line stays a one-off. */
    @Test
    fun recordingADashboardAlreadyKnownIsANoOp() {
        assertNull(TBoxClockAskRegistry.withFingerprint(setOf(voge), voge))
    }

    @Test
    fun rememberingOneDashboardKeepsTheOthers() {
        val zontes = "Zontes/12/368/V1.2.0"
        val known = TBoxClockAskRegistry.withFingerprint(setOf(voge), zontes)
        assertEquals(setOf(voge, zontes), known)
    }

    private val rx2 = "51/37516/V0.0.1"

    /**
     * The measured case: a Cyclone RX2 answered at one CLIENT_INFO and still counting 87s later,
     * with the uptime grown by exactly that much, so nothing restarted in between.
     * (support id C1FD-A8CF-9389, 2026-09-11 00:16:50 -> 00:18:17)
     */
    @Test
    fun aDashboardStillCountingInTheSamePowerCycleDiscardedTheAnswer() {
        val previous = TBoxClockAskRegistry.ClockProbe(
            uptimeMillis = 74_626L,
            seenAtMillis = 1_000_000L,
            answered = true
        )
        assertTrue(
            TBoxClockAskRegistry.discardedTheAnswer(
                previous,
                uptimeMillis = 161_474L,
                nowMillis = 1_000_000L + 86_848L
            )
        )
    }

    /**
     * The same two readings with the bike switched off in between: the uptime restarted, so the
     * counter says nothing about what the dashboard did with the answer. This is the case that
     * makes the naive rule wrong - a cluster with no RTC loses the time with the ignition.
     */
    @Test
    fun aDashboardThatRestartedInBetweenIsNotBlamed() {
        val previous = TBoxClockAskRegistry.ClockProbe(
            uptimeMillis = 562_084L,
            seenAtMillis = 1_000_000L,
            answered = true
        )
        // 36 minutes later, back to 4 minutes of uptime: the C1FD-A8CF-9389 00:24:57 -> 01:01:05 pair.
        assertFalse(
            TBoxClockAskRegistry.discardedTheAnswer(
                previous,
                uptimeMillis = 265_859L,
                nowMillis = 1_000_000L + 2_168_000L
            )
        )
    }

    /** An uptime that grew, but nowhere near as much as the wall clock, is also a restart. */
    @Test
    fun anUptimeThatFellBehindTheWallClockIsARestart() {
        val previous = TBoxClockAskRegistry.ClockProbe(10_000L, 1_000_000L, answered = true)
        assertFalse(
            TBoxClockAskRegistry.discardedTheAnswer(previous, 70_000L, 1_000_000L + 600_000L)
        )
    }

    /** Nothing is concluded from a handshake where the daemon never put a clock on the wire. */
    @Test
    fun aDashboardThatWasNeverAnsweredIsNotBlamed() {
        val previous = TBoxClockAskRegistry.ClockProbe(74_626L, 1_000_000L, answered = false)
        assertFalse(
            TBoxClockAskRegistry.discardedTheAnswer(previous, 161_474L, 1_000_000L + 86_848L)
        )
        assertFalse(TBoxClockAskRegistry.discardedTheAnswer(null, 161_474L, 1_000_000L))
    }

    /** A dashboard that came back reporting a real date took the answer, whatever else it does. */
    @Test
    fun aDashboardReportingADateIsNotDiscardingAnything() {
        val previous = TBoxClockAskRegistry.ClockProbe(74_626L, 1_000_000L, answered = true)
        assertFalse(
            TBoxClockAskRegistry.discardedTheAnswer(previous, 1_789_082_212_435L, 1_000_000L + 86_848L)
        )
        assertTrue(TBoxClockAskRegistry.looksLikeCounter(998_264L))
        assertFalse(TBoxClockAskRegistry.looksLikeCounter(1_789_082_212_435L))
    }

    /** A phone clock that moved backwards between the two readings yields no verdict at all. */
    @Test
    fun aPhoneClockThatWentBackwardsYieldsNoVerdict() {
        val previous = TBoxClockAskRegistry.ClockProbe(74_626L, 1_000_000L, answered = true)
        assertFalse(TBoxClockAskRegistry.discardedTheAnswer(previous, 161_474L, 900_000L))
    }

    /** The discard verdict is keyed by firmware, exactly like the asking one. */
    @Test
    fun theDiscardVerdictAlsoFollowsTheFirmware() {
        val known = TBoxClockAskRegistry.withFingerprint(emptySet(), rx2)
        assertEquals(setOf(rx2), known)
        assertNull(TBoxClockAskRegistry.withFingerprint(known!!, rx2))
        assertNull(TBoxClockAskRegistry.withFingerprint(known, null))
    }
}
