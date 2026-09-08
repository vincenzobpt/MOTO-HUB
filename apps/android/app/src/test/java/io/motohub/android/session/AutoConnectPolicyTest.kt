// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoConnectPolicyTest {
    private fun decide(
        riderCancelled: Boolean = false,
        previousAttempts: Int = 0,
        dashBroadcasting: Boolean? = null,
        associatedToDash: Boolean = false,
        dashReachableWhenCancelled: Boolean = false,
    ) = autoConnectDecision(
        riderCancelled, previousAttempts, dashBroadcasting, associatedToDash, dashReachableWhenCancelled
    )

    @Test
    fun firstAttemptOfALaunchAlwaysRuns() {
        // The attempt the rider actually waits for. A scan can be minutes old at launch, so
        // even a definite absence must not stop this one.
        assertEquals(AutoConnectDecision.Go, decide(dashBroadcasting = false))
        assertEquals(AutoConnectDecision.Go, decide(dashBroadcasting = null))
        assertEquals(AutoConnectDecision.Go, decide(dashBroadcasting = true))
    }

    @Test
    fun aCancelIsNotAnsweredByTheNextResume() {
        // Rider c110050c, 2026-08-26: cancelled at 18:41:04, got a fresh attempt at 18:42:04,
        // cancelled again. And 2026-08-25 21:53:51 → 21:53:56, that one while he was starting
        // phone-only Android Auto.
        assertTrue(decide(riderCancelled = true, dashBroadcasting = null) is AutoConnectDecision.Skip)
        assertTrue(decide(riderCancelled = true, dashBroadcasting = false) is AutoConnectDecision.Skip)
    }

    @Test
    fun aCancelIsLiftedWhenTheDashIsSeenOnTheAir() {
        // The rider said no to a hunt that could not have worked. Once the bike is demonstrably
        // broadcasting the situation has changed, and that is what auto-connect is for.
        assertEquals(
            AutoConnectDecision.Go,
            decide(
                riderCancelled = true,
                previousAttempts = 3,
                dashBroadcasting = true,
                dashReachableWhenCancelled = false
            )
        )
    }

    @Test
    fun aCancelIsLiftedWhenThePhoneIsAlreadyOnTheDashNetwork() {
        // The rung a throttle cannot take away, and the reason this branch is no longer a dead
        // end. A phone associated to the dash's own network is not merely evidence that the dash
        // is on the air - it IS on it, and a connect from there is the cheap one.
        assertEquals(
            AutoConnectDecision.Go,
            decide(
                riderCancelled = true,
                previousAttempts = 3,
                associatedToDash = true,
                dashReachableWhenCancelled = false
            )
        )
        // Even while the scan is definitely absent: on a single-STA phone the association is a
        // fact about the link being held and outranks a list that failed to mention it.
        assertEquals(
            AutoConnectDecision.Go,
            decide(
                riderCancelled = true,
                dashBroadcasting = false,
                associatedToDash = true,
                dashReachableWhenCancelled = false
            )
        )
    }

    @Test
    fun aBlindPhoneIsNotPromisedAResumptionItCannotDeliver() {
        // The defect this pair pins. Support 36a3fd37 (2026-09-02) fired the cancel skip three
        // times in ninety seconds on a phone whose every scan came back empty, and every one of
        // them told him it would "resume by itself once the dash is broadcasting" - which on that
        // phone could never happen, because dashBroadcasting is null forever.
        val blind = decide(riderCancelled = true, dashBroadcasting = null) as AutoConnectDecision.Skip
        assertFalse(blind.reason.contains("resumes by itself"))
        assertTrue(blind.reason.contains("Tap Connect"))

        // A phone that CAN see the air keeps the promise, because there it is true.
        val sighted = decide(riderCancelled = true, dashBroadcasting = false) as AutoConnectDecision.Skip
        assertTrue(sighted.reason.contains("resumes by itself"))
    }

    @Test
    fun aCancelMadeAtTheMotorcycleIsNotLiftedByStillBeingAtIt() {
        // The regression this clause exists for. isAssociatedTo is true precisely where a rider
        // stands when they cancel a slow discovery, and lifting on "reachable now" handed them
        // rider c110050c's complaint: cancel, 5s cooldown, Go, every 15s, for as long as they
        // stay by the bike. Nothing has changed, so the cancel stands.
        assertTrue(
            decide(
                riderCancelled = true,
                associatedToDash = true,
                dashReachableWhenCancelled = true
            ) is AutoConnectDecision.Skip
        )
        assertTrue(
            decide(
                riderCancelled = true,
                dashBroadcasting = true,
                dashReachableWhenCancelled = true
            ) is AutoConnectDecision.Skip
        )
    }

    @Test
    fun andSaysSoRatherThanPromisingSomethingWillChange() {
        val skip = decide(
            riderCancelled = true,
            associatedToDash = true,
            dashReachableWhenCancelled = true
        ) as AutoConnectDecision.Skip
        assertTrue(skip.reason.contains("already within reach"))
        assertFalse(skip.reason.contains("resumes by itself"))
    }

    @Test
    fun aDashThatBecomesReachableLiftsTheCancel() {
        // Out of reach when they said no, in reach now: that IS a change, and it is what
        // auto-connect is for. Both rungs count.
        assertEquals(
            AutoConnectDecision.Go,
            decide(riderCancelled = true, associatedToDash = true, dashReachableWhenCancelled = false)
        )
        assertEquals(
            AutoConnectDecision.Go,
            decide(riderCancelled = true, dashBroadcasting = true, dashReachableWhenCancelled = false)
        )
    }

    @Test
    fun retriesStopWhileTheDashIsDefinitelyAbsent() {
        // Rider c110050c, 2026-08-25 21:46-21:47: four attempts fired by the ON_RESUME of
        // returning from the photo picker, each burning a 30s+6s Wi-Fi request for an SSID CORE
        // had already reported missing from a 5-network scan.
        assertTrue(decide(previousAttempts = 1, dashBroadcasting = false) is AutoConnectDecision.Skip)
        assertTrue(decide(previousAttempts = 9, dashBroadcasting = false) is AutoConnectDecision.Skip)
    }

    @Test
    fun noScanEvidenceNeverBlocksARetry() {
        // The tri-state is the whole safety of this gate: a phone that hands back nothing is
        // describing itself, not the dash, and must not be allowed to convict it.
        assertEquals(AutoConnectDecision.Go, decide(previousAttempts = 5, dashBroadcasting = null))
    }

    @Test
    fun aSightingKeepsTheRetriesComing() {
        assertEquals(AutoConnectDecision.Go, decide(previousAttempts = 5, dashBroadcasting = true))
    }

    @Test
    fun theCancelEvidenceIsRetiredByTheDashGoingOutOfReach() {
        // Without this the sample taken at the cancel is a fact about the past, and nothing can
        // contradict it: a rider who cancels standing at the motorcycle - which is where cancels
        // happen - then rides away and comes back never gets an automatic attempt again for the
        // life of the process, although the dash genuinely went out of reach and returned.
        assertFalse(cancelEvidenceStillStands(dashReachableWhenCancelled = true, dashReachableNow = false))
        // And once retired it stays retired: the return is then a change, so the cancel lifts.
        assertEquals(
            AutoConnectDecision.Go,
            decide(
                riderCancelled = true,
                associatedToDash = true,
                dashReachableWhenCancelled = cancelEvidenceStillStands(true, dashReachableNow = false)
            )
        )
    }

    @Test
    fun butStayingInReachDoesNotRetireIt() {
        // The other direction, and the one that must not regress: while the dash has been within
        // reach continuously since the cancel, nothing has changed and the cancel stands.
        assertTrue(cancelEvidenceStillStands(dashReachableWhenCancelled = true, dashReachableNow = true))
        // A cancel made out of reach never had the evidence to begin with.
        assertFalse(cancelEvidenceStillStands(dashReachableWhenCancelled = false, dashReachableNow = true))
    }

    @Test
    fun everySkipSaysWhyInTheLog() {
        // These strings land in a rider's log and are the only account of a connect that did not
        // happen; an empty one would read as a bug in the app rather than a decision.
        listOf(
            decide(riderCancelled = true, dashBroadcasting = false),
            decide(riderCancelled = true, dashBroadcasting = null),
            decide(previousAttempts = 1, dashBroadcasting = false),
        ).forEach { decision ->
            assertTrue((decision as AutoConnectDecision.Skip).reason.length > 20)
        }
    }
}
