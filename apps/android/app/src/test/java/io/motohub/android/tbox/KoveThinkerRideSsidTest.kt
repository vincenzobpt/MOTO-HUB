// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import io.motohub.android.feature.pairing.withModelIdForConnectionMode
import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.session.TBoxConnectionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Case 74149ea9 (KOVE 450 Rally, 2026-09-14): re-paired by hand, the dash was saved as Auto with
 * no modelId, resolved to GENERIC and spent its one good Wi-Fi join on EasyConn discovery. Then,
 * even on ThinkerRide, nothing said which of the two KOVE panels it drives.
 */
class KoveThinkerRideSsidTest {

    private val handPaired = MotorcycleProfile(ssid = "CQKY_c632fceef", password = "secret")

    @Test
    fun aHandPairedKoveOnAutoNowReachesThinkerRide() {
        val stamped = handPaired.withModelIdForConnectionMode()

        assertEquals(ThinkerRideProtocol.PROVISIONING_MODEL_ID, stamped.modelId)
        assertEquals(
            TBoxTransportFamily.THINKERRIDE,
            TBoxModelProfile.resolve(stamped.modelId, null, ProfileOverride.AUTO).transportFamily
        )
    }

    /** The rider in the log flipped the chip to ACCESS_POINT; the network name still knows. */
    @Test
    fun movingAKoveOffTheChipKeepsItOnThinkerRide() {
        val corrected = handPaired.copy(
            modelId = ThinkerRideProtocol.PROVISIONING_MODEL_ID,
            connectionMode = TBoxConnectionMode.ACCESS_POINT
        ).withModelIdForConnectionMode()

        assertEquals(ThinkerRideProtocol.PROVISIONING_MODEL_ID, corrected.modelId)
    }

    @Test
    fun anUnpinnedKoveIsAskedWhichModelItIs() {
        assertTrue(KoveModelChoice.isNeeded(handPaired.withModelIdForConnectionMode()))
    }

    @Test
    fun anyPinAnswersTheQuestion() {
        val stamped = handPaired.withModelIdForConnectionMode()
        assertFalse(KoveModelChoice.isNeeded(stamped.copy(profileOverrideKey = "kove_450_rally")))
        assertFalse(KoveModelChoice.isNeeded(stamped.copy(profileOverrideKey = "kove_800x")))
        assertFalse(KoveModelChoice.isNeeded(stamped.copy(profileOverrideKey = "generic")))
    }

    @Test
    fun everyOtherDashboardIsNeverAsked() {
        assertFalse(KoveModelChoice.isNeeded(MotorcycleProfile(ssid = "CFMOTO6627", password = "x")))
        assertFalse(
            KoveModelChoice.isNeeded(
                MotorcycleProfile(ssid = "KY_ADV_90f6d3be4cc2", password = "x").withModelIdForConnectionMode()
            )
        )
    }

    @Test
    fun theChoicesPinTheirOwnPanels() {
        assertEquals(
            listOf(TBoxModelProfile.KOVE_450_RALLY, TBoxModelProfile.KOVE_800X),
            KoveModelChoice.options.map { it.resolve() }
        )
    }

    @Test
    fun aKoveOnAutoIsGatedOnTheBluetoothGrantToo() {
        assertTrue(ThinkerRideGate.requiresBle(handPaired.withModelIdForConnectionMode()))
        assertFalse(ThinkerRideGate.requiresBle(MotorcycleProfile(ssid = "CFMOTO6627", password = "x")))
    }
}
