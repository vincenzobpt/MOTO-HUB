// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.pairing

import io.motohub.android.session.TBoxConnectionMode
import io.motohub.android.tbox.EyLinkProtocol
import io.motohub.android.tbox.ProfileOverride
import io.motohub.android.tbox.TBoxModelProfile
import io.motohub.android.tbox.TBoxTransportFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An EyLink (Erya) code becomes a Wi-Fi Direct profile named after the dash's P2P device, routed
 * to the EyLink transport. The code looks close enough to a Carbit one that the generic parser
 * would otherwise save an access-point profile for a dash that has no access point.
 */
class EylinkQrDialectTest {

    private val eylinkCode =
        "https://appstore.eryanet.com?productid=EYLINK&device=EY_9F2C&ssid=EY_9F2C&pwd=12345678" +
            "&name=EY_9F2C&mac=TYWKOVE9F2C01&IP=192.168.13.1&key=abcdef01"

    @Test
    fun savesTheDashAsAWifiDirectPeerUnderItsDeviceName() {
        val payload = TBoxQrParser.parse(eylinkCode).getOrThrow()

        assertEquals("EY_9F2C", payload.ssid)
        assertEquals(TBoxConnectionMode.WIFI_DIRECT, payload.suggestedConnectionMode)
        assertEquals(EyLinkProtocol.PROVISIONING_MODEL_ID, payload.modelId)
        assertEquals(TBoxQrOrigin.RECOGNISED, payload.origin)
    }

    @Test
    fun theStampedModelIdRoutesToTheEyLinkTransport() {
        val payload = TBoxQrParser.parse(eylinkCode).getOrThrow()
        val profile = TBoxModelProfile.resolve(payload.modelId, null, ProfileOverride.AUTO)

        assertEquals(TBoxModelProfile.KOVE_350RR_EYLINK, profile)
        assertEquals(TBoxTransportFamily.EYLINK, profile.transportFamily)
    }

    @Test
    fun theOpaqueMacIsNotKeptAsADashAddress() {
        assertEquals(null, TBoxQrParser.parse(eylinkCode).getOrThrow().dashMacAddress)
    }

    @Test
    fun fallsBackToTheSsidWhenTheCodeHasNoName() {
        val payload = TBoxQrParser.parse(
            "https://appstore.eryanet.com?productid=EYLINK&ssid=EY_1&pwd=1"
        ).getOrThrow()

        assertEquals("EY_1", payload.ssid)
    }

    @Test
    fun aCodeNamingNoDashIsRefusedRatherThanHandedToTheGenericParser() {
        assertTrue(TBoxQrParser.parse("https://appstore.eryanet.com?productid=EYLINK&pwd=1").isFailure)
    }

    /** The real code of a KTEY 450 Rally (30C1-136A-AF70), with its activation key replaced. */
    @Test
    fun aCodeWithOnlyABluetoothNameIsProvisionedOverBluetooth() {
        val payload = TBoxQrParser.parse(
            "https://appstore.eryanet.com/?productid=EYLINK&device=KTEY_1E55BF_LE" +
                "&mac=40D95A1E55BE&key=0123456789abcdef0123456789abcdef"
        ).getOrThrow()

        assertEquals("KTEY_1E55BF_LE", payload.ssid)
        assertEquals("", payload.password)
        assertEquals(TBoxConnectionMode.BLE_PROVISIONED, payload.suggestedConnectionMode)
        assertEquals(EyLinkProtocol.PROVISIONING_MODEL_ID, payload.modelId)
        assertEquals("40:d9:5a:1e:55:be", payload.dashMacAddress)
    }

    @Test
    fun ignoresCase() {
        val payload = TBoxQrParser.parse(
            "https://appstore.eryanet.com?ProductID=eylink&Name=EY_1&pwd=1"
        ).getOrThrow()

        assertEquals(TBoxConnectionMode.WIFI_DIRECT, payload.suggestedConnectionMode)
    }

    @Test
    fun leavesEveryOtherDialectAlone() {
        // A Carbit code carrying its own productid must not be caught by the EyLink dialect.
        val carbit = TBoxQrParser.parse(
            "http://www.carbit.com.cn/app?ssid=CF-MOTO-1234&pwd=87654321&action=1&productid=00297"
        ).getOrThrow()

        assertEquals("CF-MOTO-1234", carbit.ssid)
        assertTrue(carbit.suggestedConnectionMode != TBoxConnectionMode.WIFI_DIRECT)
    }
}
