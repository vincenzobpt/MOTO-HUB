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
 * The KOVE 625X is identified by its network name alone: its QR carries no modelId and the dash
 * speaks Yunmo, not the ThinkerRide chip the other KOVE profiles are built for. Everything below
 * is what the field log of 2026-09-03 established, pinned so the next KOVE does not undo it.
 */
class Kove625xProfileTest {

    @Test
    fun theNetworkNamePrefixEarnsThePseudoModelId() {
        assertEquals(KOVE_625X_PROVISIONING_MODEL_ID, TBoxModelProfile.modelIdForSsid("KY_ADV_90f6d3be4cc2"))
        assertEquals(KOVE_625X_PROVISIONING_MODEL_ID, TBoxModelProfile.modelIdForSsid("  ky_adv_0000  "))
    }

    @Test
    fun otherNetworkNamesEarnNothing() {
        assertNull(TBoxModelProfile.modelIdForSsid("CQKY_763116fcb"))
        assertNull(TBoxModelProfile.modelIdForSsid("ML065622"))
        assertNull(TBoxModelProfile.modelIdForSsid("KY_ADV"))
        assertNull(TBoxModelProfile.modelIdForSsid(""))
        assertNull(TBoxModelProfile.modelIdForSsid(null))
    }

    @Test
    fun noOtherProfileClaimsANetworkNamePrefix() {
        val claimants = TBoxModelProfile.entries.filter { it.ssidPrefixes.isNotEmpty() }
        assertEquals(listOf(TBoxModelProfile.KOVE_625X), claimants)
    }

    @Test
    fun thePseudoModelIdResolvesToTheProfileWithoutAPin() {
        assertEquals(TBoxModelProfile.KOVE_625X, TBoxModelProfile.fromModelId(KOVE_625X_PROVISIONING_MODEL_ID))
        assertEquals(
            TBoxModelProfile.KOVE_625X,
            TBoxModelProfile.resolve(KOVE_625X_PROVISIONING_MODEL_ID, null, ProfileOverride.AUTO)
        )
    }

    @Test
    fun theProfileIsWhatTheFieldLogProved() {
        val profile = TBoxModelProfile.KOVE_625X
        assertEquals(TBoxTransportFamily.YUNMO, profile.transportFamily)
        assertTrue("only JPEG stills were ever acknowledged by this dash", profile.yunmoJpegVideo)
        assertTrue("the dash confirmed map-nav in every session", profile.yunmoMapNavExperiment)
        assertFalse(profile.supportsScreenTouch)
        assertEquals(TBoxEvent.VideoArea(640, 480), profile.fallbackTBoxVideoArea)
        assertEquals(10, profile.encoderFrameRate)
        assertEquals(187, profile.virtualDisplayDpi)
    }

    @Test
    fun theRiderCanStillPinItByHand() {
        assertEquals(ProfileOverride.KOVE_625X, ProfileOverride.byKey("kove_625x"))
        assertEquals(TBoxModelProfile.KOVE_625X, ProfileOverride.KOVE_625X.resolve())
        assertTrue(ProfileOverride.KOVE_625X.riderSelectable)
        assertEquals(TBoxModelProfile.KOVE_625X, TBoxModelProfile.byKey("kove_625x"))
    }

    @Test
    fun theLearnedYunmoShortcutKeepsTheRecognisedProfile() {
        // The regression this guards: from the second connect on, the remembered family used to
        // route straight to the family's first entry - the X-Cape's H.264 profile - and the 625X
        // got 93% of its frames refused although its own profile was one modelId lookup away.
        assertEquals(
            TBoxModelProfile.KOVE_625X,
            TBoxModelProfile.shortcutFor(TBoxTransportFamily.YUNMO, KOVE_625X_PROVISIONING_MODEL_ID)
        )
    }

    @Test
    fun theShortcutStillPicksTheFamilysFirstEntryForEveryoneElse() {
        assertEquals(TBoxModelProfile.MORINI_XCAPE_1200, TBoxModelProfile.shortcutFor(TBoxTransportFamily.YUNMO, null))
        assertEquals(TBoxModelProfile.MORINI_XCAPE_1200, TBoxModelProfile.shortcutFor(TBoxTransportFamily.YUNMO, "00297"))
        // A recognised profile of ANOTHER family does not hijack the shortcut either.
        assertEquals(
            TBoxModelProfile.KOVE_800X,
            TBoxModelProfile.shortcutFor(TBoxTransportFamily.THINKERRIDE, KOVE_625X_PROVISIONING_MODEL_ID)
        )
        assertNull(TBoxModelProfile.shortcutFor(TBoxTransportFamily.EASYCONN, null).takeIf { it == null })
    }

    @Test
    fun theProfileNeverRoutesToTheThinkerRideChip() {
        // The mistake the rider was sent down first: both existing KOVE profiles scan for a BLE
        // service this dash does not have.
        assertTrue(TBoxModelProfile.KOVE_625X.transportFamily != TBoxTransportFamily.THINKERRIDE)
        assertFalse(TBoxModelProfile.KOVE_625X.bleUsesByteCatFraming)
    }
}
