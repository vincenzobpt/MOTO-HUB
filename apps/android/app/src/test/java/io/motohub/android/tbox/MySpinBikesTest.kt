// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The official launchers' Bluetooth-name rules, so a paired mySPIN bike can be added by tapping it. */
class MySpinBikesTest {
    @Test
    fun theBrandComesFromTheNameAsTheOfficialAppsReadIt() {
        assertEquals("Suzuki", MySpinBikes.brandOf("SUZUKI_MYSPIN_81CB9"))
        assertEquals("Ducati", MySpinBikes.brandOf("DUCATI_4885"))
        assertEquals("Kawasaki", MySpinBikes.brandOf("Kawasaki001_3F2A"))
        assertNull(MySpinBikes.brandOf("KAWASAKI001_3F2A"))
        assertEquals("BMW", MySpinBikes.brandOf("BMW Motorrad 12345"))
        assertEquals("BMW", MySpinBikes.brandOf("ICC_0A1B"))
        assertNull(MySpinBikes.brandOf("SZUZUKI_MYSPIN_1E4D7"))
        assertNull(MySpinBikes.brandOf("suzuki_myspin_81cb9"))
        assertNull(MySpinBikes.brandOf("Galaxy Buds"))
        assertNull(MySpinBikes.brandOf(null))
    }

    @Test
    fun onlyUnsavedMySpinBikesAreOffered() {
        val paired = listOf("Galaxy Buds", "SUZUKI_MYSPIN_81CB9", "DUCATI_4885", "SUZUKI_MYSPIN_0754D", "DUCATI_4885")
        assertEquals(
            listOf("DUCATI_4885", "SUZUKI_MYSPIN_0754D"),
            MySpinBikes.unsaved(paired, savedNames = listOf("suzuki_myspin_81cb9", "CFMOTO-D17118"))
        )
    }

    @Test
    fun thePickerPutsTheMySpinBikesFirstAndKeepsEveryOtherDevice() {
        val paired = listOf("Galaxy Buds", "SUZUKI_MYSPIN_81CB9", "airpods", "BMW Motorrad 12345", "Galaxy Buds", "CE 04")
        assertEquals(
            listOf("BMW Motorrad 12345", "SUZUKI_MYSPIN_81CB9", "airpods", "CE 04", "Galaxy Buds"),
            MySpinBikes.rankedForPicker(paired)
        )
    }
}
