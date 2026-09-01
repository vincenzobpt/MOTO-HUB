// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_INDICATE
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_READ
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
import io.motohub.android.session.MotorcycleProfile
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The GATT tables here are transcribed from the log of support case f014ce61 (VOGE 800 Rally,
 * 2026-08-31), where the lab dumped the right channel and then declined to use it.
 */
class EcBtpClockLabTest {
    private val vogeService = UUID.fromString("5fe695f1-fd7b-4f9b-98cc-ee6cf57a776e")
    private val vogeNotify = UUID.fromString("ab9938d5-c354-4e2b-94f4-364e16ebcd33")
    private val vogeWrite = UUID.fromString("6052202a-2928-4131-a2d0-456d5673ed2f")

    @Test
    fun `the VOGE's own vendor service is shaped like a serial channel`() {
        val pair = EcBtpClockLab.serialShapedPair(
            vogeService,
            listOf(
                vogeNotify to (PROPERTY_NOTIFY or PROPERTY_INDICATE),
                vogeWrite to (PROPERTY_WRITE or PROPERTY_WRITE_NO_RESPONSE)
            )
        )
        assertEquals(vogeWrite to vogeNotify, pair)
    }

    @Test
    fun `a Bluetooth SIG service is never guessed at, whatever its shape`() {
        // Every peripheral carries GAP, GATT and Device Information. One of them growing a
        // write and a notify characteristic must not turn the lab loose on it.
        assertNull(
            EcBtpClockLab.serialShapedPair(
                UUID.fromString("00001801-0000-1000-8000-00805f9b34fb"),
                listOf(
                    UUID.fromString("00002a05-0000-1000-8000-00805f9b34fb") to PROPERTY_INDICATE,
                    UUID.fromString("00002b29-0000-1000-8000-00805f9b34fb") to PROPERTY_WRITE
                )
            )
        )
    }

    @Test
    fun `an ambiguous service is left alone rather than written to`() {
        // A pipe has one way in and one way out. Two writable characteristics is something else,
        // and EC-BTP frames are not worth firing into a guess that wide.
        assertNull(
            EcBtpClockLab.serialShapedPair(
                vogeService,
                listOf(
                    vogeNotify to PROPERTY_NOTIFY,
                    vogeWrite to PROPERTY_WRITE,
                    UUID.fromString("6052202b-2928-4131-a2d0-456d5673ed2f") to PROPERTY_WRITE
                )
            )
        )
        assertNull(
            EcBtpClockLab.serialShapedPair(
                vogeService,
                listOf(vogeWrite to PROPERTY_WRITE, vogeNotify to PROPERTY_READ)
            )
        )
    }

    @Test
    fun `the dash is recognised from the group name it hosts`() {
        val hints = EcBtpClockLab.dashNameHints(
            listOf(MotorcycleProfile(ssid = "DIRECT-VOGE-057543", password = "x"))
        )
        // Bonded as VOGE-057543, advertising as BLE-VOGE-057543 - both are the same dash.
        assertTrue(EcBtpClockLab.looksLikeDash("VOGE-057543", hints))
        assertTrue(EcBtpClockLab.looksLikeDash("BLE-VOGE-057543", hints))
        assertTrue(EcBtpClockLab.looksLikeDash("DIRECT-VOGE-057543", hints))
        // The devices that filled the first ten slots in that rider's log.
        assertFalse(EcBtpClockLab.looksLikeDash("Soundcore Liberty Neo", hints))
        assertFalse(EcBtpClockLab.looksLikeDash("Galaxy Watch6 (GDQF)", hints))
        assertFalse(EcBtpClockLab.looksLikeDash(null, hints))
        assertFalse(EcBtpClockLab.looksLikeDash("   ", hints))
    }

    @Test
    fun `a rider who named the bike is matched on that name too`() {
        val hints = EcBtpClockLab.dashNameHints(
            listOf(
                MotorcycleProfile(
                    ssid = "DIRECT-VOGE-057543",
                    password = "x",
                    displayName = "Valico"
                )
            )
        )
        assertTrue(EcBtpClockLab.looksLikeDash("Valico", hints))
    }

    @Test
    fun `an empty garage matches nothing rather than everything`() {
        val hints = EcBtpClockLab.dashNameHints(emptyList())
        assertTrue(hints.isEmpty())
        assertFalse(EcBtpClockLab.looksLikeDash("VOGE-057543", hints))
    }
}
