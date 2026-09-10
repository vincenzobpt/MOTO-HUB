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
}
