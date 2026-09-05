// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TBoxCapabilitiesTest {
    @Test
    fun `maps whitelisted CLIENT_INFO fields`() {
        val result = tBoxCapabilitiesFrom(
            mapOf(
                "HUID" to "secret-huid",
                "uuid" to "secret-uuid",
                "btPin" to "1234",
                "HUName" to "CFDL26",
                "carBrand" to "CFMOTO",
                "carModel" to "reported-model",
                "pxcVersion" to "1.2.3",
                "dpi" to 160,
                "supportScreenMirroring" to true,
                "supportScreenTouch" to false,
                "supportMirrorReconnect" to true
            )
        )

        assertEquals("CFDL26", result.huName)
        assertEquals("CFMOTO", result.carBrand)
        assertEquals("reported-model", result.carModel)
        assertEquals("1.2.3", result.pxcVersion)
        assertEquals(160, result.dpi)
        assertTrue(result.screenMirroring == true)
        assertFalse(result.screenTouch == true)
        assertTrue(result.mirrorReconnect == true)
    }

    @Test
    fun `preserves missing capability flags as not reported`() {
        val result = tBoxCapabilitiesFrom(mapOf("HUName" to "T-Box"))

        assertNull(result.screenMirroring)
        assertNull(result.screenTouch)
        assertNull(result.microphone)
    }

    @Test
    fun `keeps a numeric manufacturer flavor as text`() {
        // Shipped firmware reports flavor as an int (65561 is ZONTES in the EasyConn SDK's
        // ECP_FLAVOR_APP_SDK_* table); the simulator reports a string. Both must survive.
        val result = tBoxCapabilitiesFrom(mapOf("flavor" to 65561, "channel" to 48405))

        assertEquals("65561", result.flavor)
        assertEquals("48405", result.channel)
    }

    @Test
    fun `keeps a string manufacturer flavor`() {
        val result = tBoxCapabilitiesFrom(mapOf("flavor" to "simulator"))

        assertEquals("simulator", result.flavor)
        assertNull(result.channel)
    }

    @Test
    fun `decodes flavor and channel from a raw CLIENT_INFO payload`() {
        // Guards the CLIENT_INFO_KEYS whitelist: a field absent from it is dropped before
        // tBoxCapabilitiesFrom ever sees it, so mapping alone is not enough.
        val payload = """{"HUName":"ZT-DASH","flavor":65561,"channel":"48405"}"""
            .toByteArray(Charsets.UTF_8)

        val result = decodeTBoxCapabilities(payload)

        assertEquals("65561", result?.flavor)
        assertEquals("48405", result?.channel)
    }

    /**
     * The property the AIDL bridge rests on: Core encodes CLIENT_INFO with one of these functions
     * and the companion app decodes it with the other, so anything lost here is a capability the
     * companion silently never sees - which is the whole failure getCapabilitiesJson() exists to
     * end.
     */
    @Test
    fun `capabilities survive the round trip both apps make them take`() {
        val original = TBoxCapabilities(
            huName = "ZHKJ13-1122",
            carBrand = "Benelli",
            carModel = "TRK 702X",
            packageName = "linux_no_package",
            pxcVersion = "1.0.2",
            sdkVersion = "0.9.23.4",
            versionName = "1.0.0",
            versionCode = "0",
            dpi = 0,
            dpiEnabled = false,
            productType = 3,
            screenType = 1,
            transportType = 2,
            supportFunction = 128,
            socketTimeoutPeriodWifi = 15,
            socketServerAuth = false,
            screenTouch = false,
            screenMirroring = true,
            mirrorReconnect = true,
            landscapeAdaptive = true,
            microphone = false,
            hid = true,
            mirrorOverlayTouch = false,
            thirdPartyApps = true,
            phoneSignal = false,
            syncCorrectTime = true,
            bluetoothCall = false,
            bluetoothSettings = false,
            flavor = "51",
            channel = "34813"
        )
        assertEquals(original, decodeCapabilities(encodeCapabilities(original)))
    }

    @Test
    fun `an all-absent snapshot round trips as one, so an empty decode is distinguishable`() {
        val empty = TBoxCapabilities()
        assertEquals(empty, decodeCapabilities(encodeCapabilities(empty)))
    }

    @Test
    fun `currentHUTime is the dashboard's uptime, whether it comes as a number or as text`() {
        assertEquals(88_182L, tBoxCapabilitiesFrom(mapOf("currentHUTime" to 88182)).huUptimeMillis)
        assertEquals(88_182L, tBoxCapabilitiesFrom(mapOf("currentHUTime" to "88182")).huUptimeMillis)
        assertEquals(1_613_228_316_255L, tBoxCapabilitiesFrom(mapOf("currentHUTime" to 1_613_228_316_255L)).huUptimeMillis)
        assertNull(tBoxCapabilitiesFrom(mapOf("HUName" to "SSDQ01")).huUptimeMillis)
    }

    @Test
    fun `the uptime crosses the bridge to the companion app`() {
        val decoded = decodeCapabilities(encodeCapabilities(TBoxCapabilities(huName = "VOGE", huUptimeMillis = 9_738L)))
        assertEquals(9_738L, decoded.huUptimeMillis)
        assertNull(decodeCapabilities(encodeCapabilities(TBoxCapabilities(huName = "VOGE"))).huUptimeMillis)
    }
}
