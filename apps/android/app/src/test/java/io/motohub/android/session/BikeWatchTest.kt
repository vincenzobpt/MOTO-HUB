// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BikeWatchTest {
    private fun watch(
        autoConnectEnabled: Boolean = true,
        hasSavedMotorcycle: Boolean = true,
        phase: SessionPhase = SessionPhase.NETWORK_SETUP_REQUIRED,
        riderCancelled: Boolean = false,
    ) = shouldWatchForBike(autoConnectEnabled, hasSavedMotorcycle, phase, riderCancelled)

    @Test
    fun watchesWhileTheSessionIsWaitingForALink() {
        assertTrue(watch(phase = SessionPhase.NETWORK_SETUP_REQUIRED))
        assertTrue(watch(phase = SessionPhase.ERROR))
    }

    @Test
    fun watchesDuringAnAttemptTheRiderWalkedAwayFrom() {
        // Rider 36a3fd37 left the app five seconds into an attempt; it failed thirty seconds
        // later, with MOTO-HUB already in the background and no longer able to arm anything.
        // Refusing to arm during the attempt would miss exactly that rider.
        assertTrue(watch(phase = SessionPhase.CONNECTING_NETWORK))
        assertTrue(watch(phase = SessionPhase.DISCOVERING_TBOX))
    }

    @Test
    fun doesNotWatchWhenTheMotorcycleIsAlreadyThere() {
        assertFalse(watch(phase = SessionPhase.READY))
        assertFalse(watch(phase = SessionPhase.REQUESTING_PROJECTION))
        assertFalse(watch(phase = SessionPhase.CAPTURING))
    }

    @Test
    fun doesNotWatchBeforePairingIsFinished() {
        assertFalse(watch(phase = SessionPhase.SETUP_REQUIRED))
        assertFalse(watch(hasSavedMotorcycle = false))
    }

    @Test
    fun theRidersOwnNoOutranksTheWatch() {
        // The same flag autoConnectDecision reads. A watch that survived a cancel would be the
        // cancel bug again, only with no screen to see it on.
        assertFalse(watch(riderCancelled = true))
        assertFalse(watch(autoConnectEnabled = false))
    }
}
