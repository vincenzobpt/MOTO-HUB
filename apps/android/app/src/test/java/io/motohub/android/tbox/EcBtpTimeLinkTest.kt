// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_READ
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The tables are Carbit Ride's own: `pe/a.java` for the handlebar remote, `ne/a.java` for the
 * dashboard, with the three characteristic layouts `HudNSManager` accepts
 * (`isRequiredServiceSupportedV1/V2/V3`).
 */
class EcBtpTimeLinkTest {
    private val fff0 = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    private val fff1 = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
    private val ffe0 = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    private val ffe1 = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    private val b360 = UUID.fromString("0000b360-d6d8-c7ec-bdf0-eab1bfc6bcbc")
    private val b362 = UUID.fromString("0000b362-d6d8-c7ec-bdf0-eab1bfc6bcbc")
    private val b363 = UUID.fromString("0000b363-d6d8-c7ec-bdf0-eab1bfc6bcbc")
    private val b364 = UUID.fromString("0000b364-d6d8-c7ec-bdf0-eab1bfc6bcbc")
    private val gap = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")
    private val deviceName = UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb")

    private val readWriteNotify = PROPERTY_READ or PROPERTY_WRITE or PROPERTY_WRITE_NO_RESPONSE or PROPERTY_NOTIFY

    @Test
    fun `a remote-table dash is resolved exactly as before`() {
        val pair = EcBtpTimeLink.serialPair(
            listOf(
                gap to listOf(deviceName to PROPERTY_READ),
                ffe0 to listOf(ffe1 to readWriteNotify)
            )
        )
        assertEquals(ffe1 to ffe1, pair)
    }

    @Test
    fun `a dashboard-table V1 dash carries both sides on b362`() {
        // Carbit's HudNSManager V1: the one characteristic in the table notifies and is written.
        val pair = EcBtpTimeLink.serialPair(
            listOf(fff0 to listOf(b362 to readWriteNotify))
        )
        assertEquals(b362 to b362, pair)
    }

    @Test
    fun `a dashboard-table V2 dash writes on b363 and notifies on b364`() {
        val pair = EcBtpTimeLink.serialPair(
            listOf(
                b360 to listOf(
                    b364 to PROPERTY_NOTIFY,
                    b363 to PROPERTY_WRITE
                )
            )
        )
        assertEquals(b363 to b364, pair)
    }

    @Test
    fun `a dashboard-table V3 dash does both on b364`() {
        val pair = EcBtpTimeLink.serialPair(
            listOf(b360 to listOf(b364 to (PROPERTY_WRITE or PROPERTY_NOTIFY)))
        )
        assertEquals(b364 to b364, pair)
    }

    @Test
    fun `every known service is examined, not only the first one present`() {
        // ffe0 is earlier in Carbit's list than fff0. A peripheral carrying ffe0 for something
        // else and fff0 for EC-BTP used to be abandoned at ffe0 with "no data characteristic".
        val pair = EcBtpTimeLink.serialPair(
            listOf(
                ffe0 to listOf(UUID.fromString("0000ffe9-0000-1000-8000-00805f9b34fb") to PROPERTY_WRITE),
                fff0 to listOf(fff1 to readWriteNotify)
            )
        )
        assertEquals(fff1 to fff1, pair)
    }

    @Test
    fun `a known characteristic declaring no properties still counts for both sides`() {
        // The remote-table dashes were matched by UUID alone before properties were consulted;
        // a firmware that reports none must not lose the link it had.
        val pair = EcBtpTimeLink.serialPair(listOf(fff0 to listOf(fff1 to 0)))
        assertEquals(fff1 to fff1, pair)
    }

    @Test
    fun `a known service with none of the known characteristics is not a dash`() {
        assertNull(
            EcBtpTimeLink.serialPair(
                listOf(fff0 to listOf(UUID.fromString("0000fff5-0000-1000-8000-00805f9b34fb") to readWriteNotify))
            )
        )
    }

    @Test
    fun `an unknown service is never used whatever its shape`() {
        assertNull(
            EcBtpTimeLink.serialPair(
                listOf(
                    UUID.fromString("5fe695f1-fd7b-4f9b-98cc-ee6cf57a776e") to listOf(
                        UUID.fromString("ab9938d5-c354-4e2b-94f4-364e16ebcd33") to PROPERTY_NOTIFY,
                        UUID.fromString("6052202a-2928-4131-a2d0-456d5673ed2f") to PROPERTY_WRITE
                    )
                )
            )
        )
    }

    @Test
    fun `the dashboard table is in the scan filters and the bonded pre-filter`() {
        // Both sources of candidates read SERVICE_UUIDS; a dash advertising only Carbit's
        // dashboard service must reach onServicesDiscovered at all.
        assert(EcBtpTimeLink.SERVICE_UUIDS.contains(b360))
        assert(EcBtpTimeLink.SERVICE_UUIDS.contains(UUID.fromString("00006967-0000-1000-8000-00805f9b34fb")))
    }
}
