// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What opens the access-point road for a motorcycle saved as "My phone hosts the hotspot" - see
 * [accessPointEvidence].
 *
 * Rider 6e77dcf7 (samsung SM-S948B, CFMOTO6627, 2026-09-06) is the log this exists for. Seven
 * times in three minutes the road was declined for want of a scan sighting while the phone was
 * associated to that exact access point, on a network this connector was holding and had measured
 * at -41dBm 840ms earlier. Android's scan throttling had handed back zero networks; the link was
 * never in doubt.
 */
class AccessPointEvidenceTest {

    @Test
    fun aHeldNetworkOpensTheRoadWithNoScanAtAll() {
        // The exact state at 11:00:58: network held, `getScanResults` empty, so `broadcasting`
        // is not even asked and arrives as null.
        assertEquals(
            AccessPointEvidence.HELD_NETWORK,
            accessPointEvidence(holdsNetwork = true, associatedToSsid = false, broadcasting = null)
        )
    }

    @Test
    fun aHeldNetworkOutranksAScanThatDeniesTheDash() {
        // A scan can be empty, throttled, stale or simply wrong about a 5GHz channel. Being ON
        // the network cannot be any of those things, so a denial does not get to overrule it.
        assertEquals(
            AccessPointEvidence.HELD_NETWORK,
            accessPointEvidence(holdsNetwork = true, associatedToSsid = false, broadcasting = false)
        )
    }

    @Test
    fun aSightingStillOpensTheRoadOnItsOwn() {
        // The 11:00:44 / 11:01:05 / 11:01:09 path, unchanged: this is what already worked.
        assertEquals(
            AccessPointEvidence.SCAN_SIGHTING,
            accessPointEvidence(holdsNetwork = false, associatedToSsid = false, broadcasting = true)
        )
    }

    @Test
    fun anAssociationOpensTheRoadWhenTheScanIsThrottledToNothing() {
        // Rider f27f3825 (samsung SM-S938B, Benelli TRK 702X, bj5G2266, 2026-09-07): hotspot up
        // and Wi-Fi associated to the dash's own access point at the same time, four fallbacks
        // declined with "no usable scan at all", an _EasyConn._tcp. advertisement resolved on
        // that other network and discarded every time. The association is the fact none of the
        // throttled readings could withdraw.
        assertEquals(
            AccessPointEvidence.ASSOCIATED_SSID,
            accessPointEvidence(holdsNetwork = false, associatedToSsid = true, broadcasting = null)
        )
    }

    @Test
    fun anAssociationOutranksAScanThatDeniesTheDash() {
        // Same argument as the held network: being ON the network cannot be stale or throttled,
        // so a scan that failed to see it does not get to close the road.
        assertEquals(
            AccessPointEvidence.ASSOCIATED_SSID,
            accessPointEvidence(holdsNetwork = false, associatedToSsid = true, broadcasting = false)
        )
    }

    @Test
    fun aHeldNetworkStillOutranksAnAssociation() {
        // Both are true whenever this connector owns the join. The held network is reported
        // because it is the one the caller can reuse without re-requesting anything.
        assertEquals(
            AccessPointEvidence.HELD_NETWORK,
            accessPointEvidence(holdsNetwork = true, associatedToSsid = true, broadcasting = null)
        )
    }

    @Test
    fun withNeitherHeldNorSeenTheHotspotAdviceStands() {
        // The rider whose dash really is a Wi-Fi client must keep being told to turn the hotspot
        // on. An unknown scan is not evidence of an access point, and never becomes one here.
        assertEquals(
            AccessPointEvidence.NONE,
            accessPointEvidence(holdsNetwork = false, associatedToSsid = false, broadcasting = false)
        )
        assertEquals(
            AccessPointEvidence.NONE,
            accessPointEvidence(holdsNetwork = false, associatedToSsid = false, broadcasting = null)
        )
    }
}
