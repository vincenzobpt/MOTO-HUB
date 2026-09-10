// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.externaldisplay

import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sentence the AOA log gets when MOTO-HUB tries to make Autolink let go of the accessory.
 *
 * This is a test about wording, which is unusual and deliberate. The line it replaces read
 * "Requested background stop of com.link.autolink." on every phone, while on Android 14 and later
 * nothing whatsoever had been requested - so a rider log recorded an action that never happened,
 * and the next person to read it starts hunting somewhere else. The rule these cases pin down is
 * that no outcome may claim the stop was made unless it was, and every outcome has to leave the
 * reader expecting the accessory may still be held.
 *
 * `Build.VERSION_CODES` are compile-time constants and inline, so this needs no Android runtime.
 */
class AutolinkStopTest {

    private val android12 = Build.VERSION_CODES.S
    private val android13 = Build.VERSION_CODES.TIRAMISU
    private val android14 = Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    private val android15 = Build.VERSION_CODES.VANILLA_ICE_CREAM

    @Test
    fun onAndroid14AndLaterItSaysTheRequestWasNotMadeAtAll() {
        // The bug in one assertion: this is the platform every recent rider is on, and the old
        // line said the opposite of what happened.
        for (sdk in listOf(android14, android15, android15 + 1)) {
            val outcome = autolinkStopOutcome(sdk)
            assertTrue(outcome, outcome.startsWith("Did not ask Android to stop"))
            assertFalse(outcome, outcome.contains("Asked Android to stop"))
        }
    }

    @Test
    fun onAndroid12And13ItSaysTheRequestWasMadeBecauseItWas() {
        // minSdk is 31 and the app declares KILL_BACKGROUND_PROCESSES, so on these two releases
        // the call really does reach another package. Deleting it would have dropped that.
        for (sdk in listOf(android12, android13)) {
            val outcome = autolinkStopOutcome(sdk)
            assertTrue(outcome, outcome.startsWith("Asked Android to stop"))
            // Honoured only against a background Autolink - the limit that survives every version.
            assertTrue(outcome, outcome.contains("background"))
        }
    }

    @Test
    fun theVersionBoundaryIsTheOneTheDecisionUses() {
        // The gate in requestAutolinkStop and the sentence here read the same constant, so a
        // future minSdk bump cannot move one without moving the other.
        assertTrue(autolinkStopOutcome(FOREIGN_PROCESS_KILL_LAST_SDK).startsWith("Asked"))
        assertTrue(autolinkStopOutcome(FOREIGN_PROCESS_KILL_LAST_SDK + 1).startsWith("Did not ask"))
    }

    @Test
    fun aThrownFailureIsReportedAsOneAndCarriesItsReason() {
        val outcome = autolinkStopOutcome(android13, "SecurityException")
        assertTrue(outcome, outcome.startsWith("Could not ask Android to stop"))
        assertTrue(outcome, outcome.contains("SecurityException"))
    }

    @Test
    fun everyOutcomeWarnsThatAutolinkMayStillHoldTheAccessory() {
        // The only thing the reader must take away in all three cases, because the real test of
        // success is the openAccessory() call that follows, never this one.
        val outcomes = listOf(
            autolinkStopOutcome(android12),
            autolinkStopOutcome(android15),
            autolinkStopOutcome(android15, "boom")
        )
        outcomes.forEach { outcome ->
            assertTrue(outcome, outcome.contains("still holding it"))
            assertTrue(outcome, outcome.contains("only the rider can close it"))
        }
    }

    @Test
    fun everyOutcomeNamesThePackageSoTheLogIsSearchable() {
        listOf(autolinkStopOutcome(android12), autolinkStopOutcome(android15))
            .forEach { assertTrue(it, it.contains(AUTOLINK_PACKAGE)) }
    }
}
