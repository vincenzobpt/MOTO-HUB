// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import io.motohub.android.feature.pairing.withModelIdForConnectionMode
import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.session.TBoxConnectionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Ducati (mySPIN) wiring must not reach any motorcycle that worked before it existed. Every
 * test here is about who can NOT get there.
 */
class MySpinProfileRoutingTest {

    private val ducati = TBoxModelProfile.DUCATI_MULTISTRADA_V4

    private fun bike(mode: TBoxConnectionMode, modelId: String? = null, ssid: String = "Multistrada V4") =
        MotorcycleProfile(ssid = ssid, password = "", modelId = modelId, connectionMode = mode)

    @Test
    fun onlyTheDucatiProfileRoutesToMySpin() {
        assertEquals(TBoxTransportFamily.MYSPIN, ducati.transportFamily)
        TBoxModelProfile.entries.filter { it != ducati }.forEach { profile ->
            assertNotEquals("${profile.name} must keep its own wire", TBoxTransportFamily.MYSPIN, profile.transportFamily)
        }
    }

    @Test
    fun stillsAreCapturedExactlyWhereTheyWereBeforeOnEveryOtherProfile() {
        TBoxModelProfile.entries.filter { it != ducati }.forEach { profile ->
            assertEquals(profile.name, profile.yunmoJpegVideo, profile.usesJpegStills)
        }
        assertTrue(ducati.usesJpegStills)
        assertFalse("the Yunmo flag itself is untouched", ducati.yunmoJpegVideo)
    }

    @Test
    fun noRealModelIdSsidOrOverrideResolvesToTheDucati() {
        // Real dash ids, the other pseudo ids, and nothing at all.
        listOf(
            null, "", "00297", "66660732", "65540", "65561",
            ThinkerRideProtocol.PROVISIONING_MODEL_ID, KOVE_625X_PROVISIONING_MODEL_ID, "myspin", "Ducati"
        ).forEach { id ->
            assertNotEquals("modelId $id", ducati, TBoxModelProfile.resolve(id, null, null))
        }
        ProfileOverride.entries.forEach { override ->
            assertNotEquals("override ${override.name}", ducati, override.resolve())
        }
        assertTrue("no SSID can name it", ducati.ssidPrefixes.isEmpty())
        assertNull(TBoxModelProfile.modelIdForSsid("Ducati Multistrada"))
    }

    @Test
    fun thePseudoIdIsTheOnlyWayIn() {
        assertEquals(ducati, TBoxModelProfile.resolve(MYSPIN_PROVISIONING_MODEL_ID, null, null))
        assertEquals(ducati, TBoxModelProfile.resolve(bike(TBoxConnectionMode.MYSPIN).withModelIdForConnectionMode().modelId, null, null))
    }

    @Test
    fun pickingTheDucatiModeStampsThePseudoIdEvenOverARealOne() {
        assertEquals(MYSPIN_PROVISIONING_MODEL_ID, bike(TBoxConnectionMode.MYSPIN).withModelIdForConnectionMode().modelId)
        assertEquals(
            MYSPIN_PROVISIONING_MODEL_ID,
            bike(TBoxConnectionMode.MYSPIN, modelId = "66660732").withModelIdForConnectionMode().modelId
        )
    }

    @Test
    fun leavingTheDucatiModeRemovesThePseudoId() {
        val back = bike(TBoxConnectionMode.AUTO, modelId = MYSPIN_PROVISIONING_MODEL_ID).withModelIdForConnectionMode()
        assertNull(back.modelId)
        val toKove = bike(TBoxConnectionMode.THINKERRIDE, modelId = MYSPIN_PROVISIONING_MODEL_ID).withModelIdForConnectionMode()
        assertEquals(ThinkerRideProtocol.PROVISIONING_MODEL_ID, toKove.modelId)
    }

    @Test
    fun otherModesAreStampedAsBefore() {
        assertNull(bike(TBoxConnectionMode.AUTO).withModelIdForConnectionMode().modelId)
        assertEquals("66660732", bike(TBoxConnectionMode.ACCESS_POINT, modelId = "66660732").withModelIdForConnectionMode().modelId)
    }

    @Test
    fun theDucatiModeIsNeverWifiDirectUpFrontAndAsksForBluetooth() {
        assertFalse(TBoxLinkResolver.usesWifiDirect(bike(TBoxConnectionMode.MYSPIN, ssid = "DIRECT-ab-Ducati")))
        assertTrue(ThinkerRideGate.requiresBle(bike(TBoxConnectionMode.MYSPIN)))
    }

    @Test
    fun theSavedDashMatchesByNameOrAddress() {
        assertTrue(MySpinBluetoothLink.matchesSavedDevice("Multistrada V4", "AA:BB:CC:DD:EE:FF", "multistrada v4"))
        assertTrue(MySpinBluetoothLink.matchesSavedDevice("aa:bb:cc:dd:ee:ff", "AA:BB:CC:DD:EE:FF", null))
        assertFalse(MySpinBluetoothLink.matchesSavedDevice("Multistrada V4", "11:22:33:44:55:66", "Headset"))
        assertFalse(MySpinBluetoothLink.matchesSavedDevice(" ", "11:22:33:44:55:66", " "))
    }
}
