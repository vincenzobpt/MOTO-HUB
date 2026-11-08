// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.session.TBoxConnectionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the rider and the log read while a Ducati is waited for (field reports of 2026-10-20/22). */
class MySpinBluetoothMessagesTest {

    @Test
    fun anUnpairedBikeNamesWhatThePhoneDoesHave() {
        val message = MySpinBluetoothLink.notPairedMessage("DUCATI_4885", listOf("Galaxy Buds", "DUCATI 4885"))
        assertTrue(message.contains("\"DUCATI_4885\""))
        assertTrue("a near-miss name is visible", message.contains("DUCATI 4885"))
    }

    @Test
    fun noPairedDevicesAtAllIsSaid() {
        assertTrue(MySpinBluetoothLink.notPairedMessage("DUCATI_4885", emptyList()).contains("paired: none"))
    }

    @Test
    fun theTimeoutPointsAtTheOfficialAppOnlyWhenItIsInstalled() {
        assertTrue(MySpinBluetoothLink.timeoutMessage(20, officialAppInstalled = true).contains("force-stop"))
        assertFalse(MySpinBluetoothLink.timeoutMessage(20, officialAppInstalled = false).contains("official"))
    }

    @Test
    fun theWaitIsOpenEndedLikeTheOfficialAppWithOnlyACeiling() {
        assertTrue(MySpinBluetoothLink.WAIT_CEILING_MS >= 15 * 60_000L)
        assertTrue(MySpinBluetoothLink.ACCEPT_SLICE_MS < MySpinBluetoothLink.STATUS_EVERY_MS)
    }

    @Test
    fun theStatusLineSaysWhichStepTheRiderIsAt() {
        assertTrue(MySpinBluetoothLink.statusLine(null, null, null, 5).contains("not connected to this phone yet"))
        assertTrue(MySpinBluetoothLink.statusLine(true, false, false, 5).contains("not yet connected as phone or media"))
        val ready = MySpinBluetoothLink.statusLine(true, true, false, 65)
        assertTrue(ready.contains("phone (HFP)=yes"))
        assertTrue(ready.contains("can now offer Ducati Connect"))
        assertTrue(ready.startsWith("Still waiting after 65s"))
    }

    @Test
    fun aSuzukiIsToldByItsBluetoothNameAndReadsItsOwnServiceName() {
        // The SUZUKI mySPIN launcher only accepts peers named SUZUKI_MYSPIN.* (strings.xml).
        assertEquals("SUZUKI mySPIN", MySpinBluetoothLink.dashServiceName(" SUZUKI_MYSPIN_1A2B "))
        assertEquals("Ducati Connect", MySpinBluetoothLink.dashServiceName("DUCATI_4885"))
        assertEquals("Ducati Connect", MySpinBluetoothLink.dashServiceName("AA:BB:CC:DD:EE:FF"))
        assertEquals("BMW Motorrad Connected", MySpinBluetoothLink.dashServiceName("BMW Motorrad 12345"))
        val suzuki = MySpinBluetoothLink.timeoutMessage(20, officialAppInstalled = true, brand = "SUZUKI mySPIN")
        assertTrue(suzuki.contains("open SUZUKI mySPIN") && !suzuki.contains("Ducati"))
        assertTrue(MySpinBluetoothLink.statusLine(true, true, null, 5, "SUZUKI mySPIN").contains("can now offer SUZUKI mySPIN"))
        assertTrue(MySpinBluetoothLink.OFFICIAL_APP_PACKAGES.contains("com.SUZUKI.SUZUKImySPIN"))
    }

    @Test
    fun aKawasakiReadsKawasakiSpin() {
        // Kawasaki SPIN 2.25.0 accepts peers named Kawasaki001.*.
        assertEquals("Kawasaki SPIN", MySpinBluetoothLink.dashServiceName("Kawasaki001_3F2A"))
        assertTrue(MySpinBluetoothLink.OFFICIAL_APP_PACKAGES.contains("jp.co.khi.mce.kawasakispin"))
    }

    private fun bike(mode: TBoxConnectionMode, ssid: String = "DUCATI_4885") =
        MotorcycleProfile(ssid = ssid, password = "", connectionMode = mode)

    @Test
    fun onlyTheSavedDucatiConnectingStartsAStandbyListener() {
        assertEquals(
            MySpinStandby.Decision.LISTEN,
            MySpinStandby.decide(bike(TBoxConnectionMode.MYSPIN), "AA:BB:CC:DD:95:4B", "DUCATI_4885", connected = true, current = null)
        )
        assertEquals(
            "another Bluetooth device",
            MySpinStandby.Decision.IGNORE,
            MySpinStandby.decide(bike(TBoxConnectionMode.MYSPIN), "11:22:33:44:55:66", "Headset", connected = true, current = null)
        )
        assertEquals(
            "a bike on any other wire",
            MySpinStandby.Decision.IGNORE,
            MySpinStandby.decide(bike(TBoxConnectionMode.ACCESS_POINT), "AA:BB:CC:DD:95:4B", "DUCATI_4885", connected = true, current = null)
        )
        assertEquals(
            "no motorcycle saved",
            MySpinStandby.Decision.IGNORE,
            MySpinStandby.decide(null, "AA:BB:CC:DD:95:4B", "DUCATI_4885", connected = true, current = null)
        )
        assertEquals(
            "a BMW TFT connects only when the rider asks",
            MySpinStandby.Decision.IGNORE,
            MySpinStandby.decide(bike(TBoxConnectionMode.MYSPIN, "BMW Motorrad 12345"), "AA:BB:CC:DD:95:4B", "BMW Motorrad 12345", connected = true, current = null)
        )
        assertEquals(
            "a drop with nothing listening",
            MySpinStandby.Decision.IGNORE,
            MySpinStandby.decide(bike(TBoxConnectionMode.MYSPIN), "AA:BB:CC:DD:95:4B", "DUCATI_4885", connected = false, current = null)
        )
    }
}
