// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import io.motohub.android.tbox.ProfileSuggestions.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSuggestionsTest {

    private fun suggest(
        activeProfileKey: String? = null,
        currentKey: String? = null,
        modelId: String? = null,
        capabilities: TBoxCapabilities? = null
    ) = ProfileSuggestions.forFailingSession(activeProfileKey, currentKey, modelId, capabilities)

    @Test
    fun theXCapeCasePutsTheProfileThatFixesItFirst() {
        // Rider 315e0af3 exactly: session routed to Yunmo, running the X-Cape profile, dashboard
        // refusing most frames, nothing pinned. The three entries that speak this dash's wire
        // have to come before the whole EasyConn catalogue.
        val offered = suggest(activeProfileKey = "morini_xcape_1200", modelId = "00297")
        val yunmo = offered.filter { it.override.resolve()?.transportFamily == TBoxTransportFamily.YUNMO }
        assertEquals(
            "every remaining Yunmo entry must be offered (mirror, JPEG, KOVE 625X)",
            3,
            yunmo.size
        )
        assertTrue(
            "a Yunmo entry has to beat every EasyConn one",
            offered.indexOf(yunmo.first()) < offered.indexOfFirst {
                it.override.resolve()?.transportFamily == TBoxTransportFamily.EASYCONN
            }
        )
    }

    @Test
    fun theProfileAlreadyRunningIsNeverOffered() {
        val offered = suggest(activeProfileKey = "morini_xcape_1200", modelId = "00297")
        assertFalse(offered.any { it.override == ProfileOverride.MORINI_XCAPE_1200 })
    }

    @Test
    fun theRidersOwnPinIsNeverOfferedBack() {
        val offered = suggest(
            activeProfileKey = "morini_xcape_1200_jpeg",
            currentKey = "morini_xcape_1200_jpeg"
        )
        assertFalse(offered.any { it.override == ProfileOverride.MORINI_XCAPE_1200_JPEG })
    }

    @Test
    fun autoIsNotSomethingToTryHere() {
        // The session already got here through detection; offering "let it detect" as the fix is
        // offering the rider what just failed.
        assertFalse(suggest().any { it.override == ProfileOverride.AUTO })
    }

    @Test
    fun theDevelopmentSimulatorIsNeverPutInFrontOfARider() {
        assertFalse(suggest().any { it.override == ProfileOverride.MOTO_HUB_SIMULATOR })
    }

    @Test
    fun experimentsAreOfferedButAlwaysBelowRealProfilesAndTheNeutralOne() {
        // An EasyConn session on a catalogued profile, so the neutral entry is genuinely on
        // offer and shares the active wire with the experiments it has to outrank.
        val offered = suggest(activeProfileKey = "cfmoto_800nk")
        val firstExperiment = offered.indexOfFirst { it.reason == Reason.EXPERIMENT }
        val neutral = offered.indexOfFirst { it.reason == Reason.NEUTRAL }
        assertTrue("experiments must be reachable", firstExperiment >= 0)
        assertTrue("the neutral profile must be on offer at all", neutral >= 0)
        assertTrue("the neutral profile outranks a guess", neutral < firstExperiment)
        // And every entry marked as a guess in the table is reported as one.
        offered.filter { it.override.experimental }.forEach {
            assertEquals("${it.override.name} is an experiment", Reason.EXPERIMENT, it.reason)
        }
    }

    @Test
    fun aDashboardTheModelIdIdentifiesIsTheFirstThingProposed() {
        // 66660732 is the MTX800's own id: nothing should outrank the profile written for it.
        val offered = suggest(activeProfileKey = "generic", modelId = "66660732")
        assertEquals(ProfileOverride.CFMOTO_MTX800, offered.first().override)
        assertEquals(Reason.IDENTIFIED, offered.first().reason)
    }

    @Test
    fun everyRiderSelectableProfileStaysReachable() {
        // The ordering may bury an entry; it may never lose one. A dashboard nobody has
        // catalogued is exactly the case where the unlikely entry is the one that works.
        val offered = suggest().map { it.override }.toSet()
        val expected = ProfileOverride.entries
            .filter { it.riderSelectable }
            .filterNot { it == ProfileOverride.AUTO }
            .toSet()
        assertEquals(expected, offered)
    }

    @Test
    fun anUnknownActiveProfileKeyDegradesToAPlainListRatherThanNothing() {
        // An older Core naming a profile this build does not have. Ranking loses its best signal;
        // the offer must survive it.
        val offered = suggest(activeProfileKey = "something_this_build_never_heard_of")
        assertTrue(offered.isNotEmpty())
        assertTrue(offered.any { it.override == ProfileOverride.GENERIC })
    }
}
