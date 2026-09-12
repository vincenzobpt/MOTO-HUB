// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import io.motohub.android.session.TBoxConnectionMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a failed access-point join may propose "My phone hosts the hotspot" - see
 * [shouldOfferPhoneHotspot].
 *
 * Case 94e45e62 (Zontes 125X, ZT663590) is the log this exists for. The dash was joined in 4796ms
 * on 2026-09-08; two joins failed on 2026-09-09 while a truncated scan list did not list it; the
 * offer appeared anyway, the rider took it, and three days of connects then went looking for a
 * hotspot that could never exist - on a motorcycle that was broadcasting at -46dBm throughout.
 */
class PhoneHotspotOfferTest {

    @Test
    fun aDashThisPhoneHasReachedBeforeIsNeverOffered() {
        // 2026-09-09, with 2026-09-08 remembered: the single fact that would have closed the
        // door before the rider ever saw it.
        assertFalse(
            shouldOfferPhoneHotspot(
                mode = TBoxConnectionMode.AUTO,
                reachedBefore = true,
                broadcastingNow = false,
                consecutiveFailures = 9
            )
        )
    }

    @Test
    fun aDashSeenBroadcastingRightNowIsNeverOffered() {
        // A join can fail on a dash that is plainly on the air - Android refusing the specifier,
        // a 30s association timeout, a channel the phone would not take. None of that makes the
        // motorcycle a Wi-Fi client.
        assertFalse(
            shouldOfferPhoneHotspot(
                mode = TBoxConnectionMode.AUTO,
                reachedBefore = false,
                broadcastingNow = true,
                consecutiveFailures = 9
            )
        )
    }

    @Test
    fun oneFailedJoinWithNoProofEitherWayIsNotEnough() {
        // The change. A truncated scan that did not list the dash is not evidence that the dash
        // has no access point, and it used to be treated as though it were.
        assertFalse(
            shouldOfferPhoneHotspot(
                mode = TBoxConnectionMode.AUTO,
                reachedBefore = false,
                broadcastingNow = false,
                consecutiveFailures = 1
            )
        )
        assertFalse(
            shouldOfferPhoneHotspot(
                mode = TBoxConnectionMode.AUTO,
                reachedBefore = false,
                broadcastingNow = null,
                consecutiveFailures = 1
            )
        )
    }

    @Test
    fun aPatternWithNoProofEitherWayIsStillOffered() {
        // The rider whose dash really is a Wi-Fi client must still be helped to the right mode:
        // nothing was ever reached, nothing is on the air, and it keeps happening.
        assertTrue(
            shouldOfferPhoneHotspot(
                mode = TBoxConnectionMode.AUTO,
                reachedBefore = false,
                broadcastingNow = false,
                consecutiveFailures = OFFERS_AFTER_FAILED_JOINS
            )
        )
        assertTrue(
            shouldOfferPhoneHotspot(
                mode = TBoxConnectionMode.AUTO,
                reachedBefore = false,
                broadcastingNow = null,
                consecutiveFailures = OFFERS_AFTER_FAILED_JOINS
            )
        )
    }

    @Test
    fun wifiDirectIsNeverOffered() {
        // Field log 6b345de4, unchanged: a P2P Group Owner hosts the network and a phone-hotspot
        // dash joins one, so the offer would contradict what the dash's own code said.
        assertFalse(
            shouldOfferPhoneHotspot(
                mode = TBoxConnectionMode.WIFI_DIRECT,
                reachedBefore = false,
                broadcastingNow = false,
                consecutiveFailures = 99
            )
        )
    }

    @Test
    fun aMotorcycleAlreadyInThatModeIsNeverOffered() {
        assertFalse(
            shouldOfferPhoneHotspot(
                mode = TBoxConnectionMode.PHONE_HOTSPOT,
                reachedBefore = false,
                broadcastingNow = false,
                consecutiveFailures = 99
            )
        )
    }

    @Test
    fun theOtherAccessPointModesBehaveLikeAuto() {
        // ACCESS_POINT and THINKERRIDE both ride the infrastructure path, so they can produce the
        // same failure and deserve the same help - once it is actually earned.
        for (mode in listOf(TBoxConnectionMode.ACCESS_POINT, TBoxConnectionMode.THINKERRIDE)) {
            assertFalse(
                "$mode should not be offered on proof the dash has an access point",
                shouldOfferPhoneHotspot(mode, reachedBefore = true, broadcastingNow = null, consecutiveFailures = 5)
            )
            assertTrue(
                "$mode should still be offered once the pattern is there",
                shouldOfferPhoneHotspot(
                    mode,
                    reachedBefore = false,
                    broadcastingNow = false,
                    consecutiveFailures = OFFERS_AFTER_FAILED_JOINS
                )
            )
        }
    }
}
