// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.pairing

import io.motohub.android.session.TBoxConnectionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TBoxQrParserTest {
    @Test
    fun parsesEasyConnQrWithEncodedCredentials() {
        val result = TBoxQrParser.parse(
            "https://setup.carbit.com/connect?ssid=TBOX%20RIDE&pwd=pass%2Bword&auth=WPA2&name=My%20Bike"
        )

        assertEquals("TBOX RIDE", result.getOrThrow().ssid)
        assertEquals("pass+word", result.getOrThrow().password)
        assertEquals("My Bike", result.getOrThrow().displayName)
        assertEquals(TBoxQrOrigin.RECOGNISED, result.getOrThrow().origin)
    }

    @Test
    fun keepsALiteralPlusInsideAPassphrase() {
        // A provisioning URL is a query string, not a submitted form: URLDecoder's form rules
        // turned this passphrase into "rider 2026" and every join failed association silently.
        val payload = TBoxQrParser.parse(
            "https://setup.carbit.com/connect?ssid=VOGE-5G-58e4&pwd=rider+2026&auth=wpa2-psk"
        ).getOrThrow()

        assertEquals("rider+2026", payload.password)
        assertEquals("VOGE-5G-58e4", payload.ssid)
    }

    @Test
    fun keepsAnUnescapedPercentInsteadOfRejectingTheWholeCode() {
        val payload = TBoxQrParser.parse(
            "https://setup.carbit.com/connect?ssid=TBOX-9f21&pwd=100%pure&auth=wpa2-psk"
        ).getOrThrow()

        assertEquals("100%pure", payload.password)
        assertEquals("TBOX-9f21", payload.ssid)
        // The host still has to be recognised on the hand-rolled path, or a valid Carbit QR
        // would silently drop to UNVERIFIED just because its passphrase held a `%`.
        assertEquals(TBoxQrOrigin.RECOGNISED, payload.origin)
    }

    @Test
    fun decodesMultiByteEscapeSequencesAsOneCharacter() {
        val payload = TBoxQrParser.parse(
            "https://setup.carbit.com/connect?ssid=TBOX-9f21&pwd=secret&name=Moto%C3%A8"
        ).getOrThrow()

        assertEquals("Motoè", payload.displayName)
    }

    @Test
    fun preservesTheQrModelIdAsAnOpaqueTboxIdentifier() {
        val result = TBoxQrParser.parse(
            "http://www.carbit.com.cn/downsdk/657/658/_sdk?modelid=37416&sn=test&action=9&ssid=TBOX-test&pwd=example&auth=wpa2-psk&mac=00%3A00%3A00%3A00%3A00%3A00&name=TBOX-test"
        )

        assertEquals("TBOX-test", result.getOrThrow().ssid)
        assertEquals("example", result.getOrThrow().password)
        assertEquals("wpa2-psk", result.getOrThrow().encryption)
        assertEquals("37416", result.getOrThrow().modelId)
        assertEquals(TBoxQrOrigin.RECOGNISED, result.getOrThrow().origin)
    }

    @Test
    fun acceptsARebrandedProvisioningHostAsUnverified() {
        val payload = TBoxQrParser.parse(
            "https://connect.example-motors.com/pair?modelid=90210&ssid=VG-9F21A0&pwd=secret&auth=wpa2-psk"
        ).getOrThrow()

        assertEquals("VG-9F21A0", payload.ssid)
        assertEquals("secret", payload.password)
        assertEquals("90210", payload.modelId)
        assertEquals(TBoxQrOrigin.UNVERIFIED, payload.origin)
    }

    @Test
    fun parsesAPlainWifiNetworkCodeAsUnverified() {
        val payload = TBoxQrParser.parse("WIFI:S:ZT-DASH-7742;T:WPA;P:rider2026;H:false;;").getOrThrow()

        assertEquals("ZT-DASH-7742", payload.ssid)
        assertEquals("rider2026", payload.password)
        assertEquals("WPA", payload.encryption)
        assertNull(payload.modelId)
        assertEquals(TBoxQrOrigin.UNVERIFIED, payload.origin)
    }

    @Test
    fun honoursBackslashEscapesInsideAWifiNetworkCode() {
        val payload = TBoxQrParser.parse("WIFI:S:Bike\\:One;T:WPA;P:a\\;b\\\\c;;").getOrThrow()

        assertEquals("Bike:One", payload.ssid)
        assertEquals("a;b\\c", payload.password)
    }

    @Test
    fun rejectsContentWithoutANetworkName() {
        assertTrue(TBoxQrParser.parse("https://example.com/watch?v=abc123").isFailure)
        assertTrue(TBoxQrParser.parse("just some scanned text").isFailure)
        assertTrue(TBoxQrParser.parse("WIFI:T:WPA;P:secret;;").isFailure)
    }

    @Test
    fun readsAMotoFunCodeWhoseSeparatorWouldBeMistakenForAFragment() {
        // Confirmed on the Moto Morini X-Cape 649 / 700 and Seiemmezzo. Both URI.rawQuery and a
        // substringBefore('#') split stop at the first separator and return "Wifi=ML174167",
        // dropping the password: whatever reads this has to scan the raw string.
        val payload = TBoxQrParser.parse(
            "http://admin.motomorini.com/app.html?Wifi=ML174167#12345678#dc0d30da1b6c" +
                "&MachineID=dc0d30da1b6c&ProductID=00297"
        ).getOrThrow()

        assertEquals("ML174167", payload.ssid)
        assertEquals("12345678", payload.password)
        assertEquals("00297", payload.modelId)
        assertEquals("wpa2-psk", payload.encryption)
        assertEquals(TBoxQrOrigin.RECOGNISED, payload.origin)
    }

    @Test
    fun readsAMotoFunCodeWithNoTrailingMacAddress() {
        val payload = TBoxQrParser.parse(
            "http://admin.motomorini.com/app.html?Wifi=ML174167#12345678&ProductID=00297"
        ).getOrThrow()

        assertEquals("ML174167", payload.ssid)
        assertEquals("12345678", payload.password)
    }

    @Test
    fun treatsAnUncorroboratedMotoFunShapeAsUnverified() {
        // Same shape, unfamiliar host, and neither MotoFun identifier to vouch for it: usable, but
        // the rider confirms it rather than having it saved on the strength of the shape alone.
        val payload = TBoxQrParser.parse(
            "http://dash.example-motors.com/app.html?Wifi=XM-4471#rider2026"
        ).getOrThrow()

        assertEquals("XM-4471", payload.ssid)
        assertEquals("rider2026", payload.password)
        assertEquals(TBoxQrOrigin.UNVERIFIED, payload.origin)
    }

    @Test
    fun leavesACarbitCodeToTheQueryParserWhenNoPasswordFollowsTheSeparator() {
        // `wifi=` without a `#password` after it is not the MotoFun dialect, so the code still has
        // to be read as a query string - otherwise adding that dialect would break Carbit dashes.
        val payload = TBoxQrParser.parse(
            "https://setup.carbit.com/connect?wifi=1&ssid=TBOX-9f21&pwd=secret&auth=wpa2-psk"
        ).getOrThrow()

        assertEquals("TBOX-9f21", payload.ssid)
        assertEquals("secret", payload.password)
        assertEquals(TBoxQrOrigin.RECOGNISED, payload.origin)
    }

    @Test
    fun readsParameterNamesRegardlessOfCase() {
        val payload = TBoxQrParser.parse(
            "https://setup.carbit.com/connect?SSID=TBOX-9f21&PWD=secret&Auth=wpa2-psk&ModelId=37416"
        ).getOrThrow()

        assertEquals("TBOX-9f21", payload.ssid)
        assertEquals("secret", payload.password)
        assertEquals("37416", payload.modelId)
    }

    @Test
    fun namesTheVehicleInformationCodeInsteadOfCallingItUnreadable() {
        // The dash prints several codes and only one of them pairs. "Unreadable" sends the rider
        // polishing the screen; naming the content sends them to the right screen.
        val failure = TBoxQrParser.parse("code:8A1&engine:CF400&vin:LCEPRJ&color:Fuji White")

        assertTrue(failure.isFailure)
        assertTrue(
            failure.exceptionOrNull()?.message.orEmpty().contains("vehicle information")
        )
    }

    @Test
    fun explainsAMotoMoriniCodeThatIsNotThePairingScreen() {
        val failure = TBoxQrParser.parse("http://admin.motomorini.com/app.html?MachineID=dc0d30da")

        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull()?.message.orEmpty().contains("Wifi="))
    }

    @Test
    fun tellsTheRiderToHostTheHotspotWhenACarbitCodeCarriesNoNetwork() {
        // Some dashes are Wi-Fi clients: they join a hotspot the phone hosts, under an SSID the
        // dash prints itself, so their QR is a bare product link. Sending that rider to "scan the
        // pairing code instead" sends them after a code that does not exist.
        val failure = TBoxQrParser.parse("https://www.carbit.com.cn/app/download.html")

        assertTrue(failure.isFailure)
        val message = failure.exceptionOrNull()?.message.orEmpty()
        assertTrue(message, message.contains("hotspot your phone creates"))
        assertTrue(message, message.contains("Ssid"))
        // The generic web-address advice must not win: it is the wrong instruction here.
        assertTrue(message, !message.contains("Scan the dash pairing"))
        // A dash that asks the phone to host may still raise an access point on its iPhone screen
        // (support case FD79-4FFB), and that is the easier link - but only after the instruction
        // the scanned code actually gives, so the hotspot has to be named first.
        assertTrue(message, message.contains("iPhone / CarPlay"))
        assertTrue(message, message.indexOf("hotspot") < message.indexOf("iPhone / CarPlay"))
    }

    @Test
    fun stillSendsAnUnrelatedWebAddressBackToThePairingScreen() {
        val failure = TBoxQrParser.parse("https://example.com/some/page")

        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull()?.message.orEmpty().contains("Scan the dash pairing"))
    }

    @Test
    fun pointsAtTheIphoneCodeWheneverTheRemedyIsScanningAnotherCode() {
        // The single most common support thread across Benelli, CFMOTO, QJ-Motor and Voge: the
        // dash prints two codes and only the one labelled for iPhone pairs. Riders never guess it,
        // so every failure whose remedy is "scan the other code" has to say it.
        val remediable = listOf(
            "https://example.com/some/page",
            "just some scanned text",
            "code:8A1&engine:CF400&vin:LCEPRJ&color:Fuji White",
            "WIFI:T:WPA;P:secret;;"
        )
        for (raw in remediable) {
            val message = TBoxQrParser.parse(raw).exceptionOrNull()?.message.orEmpty()
            assertTrue(raw, message.contains("iPhone / CarPlay"))
        }
    }

    @Test
    fun keepsTheIphoneHintAwayFromFailuresItWouldMisdirect() {
        // The Moto Morini screen carries one code and no other: its remedy is to open the
        // phone-link screen, so hunting for an iPhone code sends that rider looking for nothing.
        // The phone-hotspot dash is NOT in here any more - see the hotspot test above, where the
        // iPhone screen is a real second topology rather than a second code.
        val misdirected = listOf(
            "http://admin.motomorini.com/app.html?MachineID=dc0d30da"
        )
        for (raw in misdirected) {
            val message = TBoxQrParser.parse(raw).exceptionOrNull()?.message.orEmpty()
            assertFalse(raw, message.contains("iPhone / CarPlay"))
        }
    }

    @Test
    fun aPhoneHotspotCodeCarriesNoNetworkAndStillPairs() {
        // Carbit's dash-as-client dialect: action bit7, a `bm=` MAC, and deliberately no ssid/pwd
        // because the dash has no access point to name. This used to be rejected outright.
        val payload = TBoxQrParser.parse(
            "http://www.carbit.com.cn/down6/645/644/_ylqxos" +
                "?modelid=21322&sn=t6J4&action=128&bm=DD%3A0D%3A30%3A24%3A87%3A6D"
        ).getOrThrow()

        assertEquals("", payload.ssid)
        assertEquals("", payload.password)
        assertEquals("21322", payload.modelId)
        assertEquals("dd:0d:30:24:87:6d", payload.dashMacAddress)
        assertEquals(TBoxConnectionMode.PHONE_HOTSPOT, payload.suggestedConnectionMode)
        assertTrue(payload.topology.phoneHostsHotspot)
        assertFalse(payload.topology.accessPoint)
        assertFalse(payload.topology.wifiDirect)
    }

    @Test
    fun theTopologyClaimReadsBackInWords() {
        val both = TBoxQrParser.parse(
            "http://www.carbit.com.cn/x?ssid=ZT_e0082100e5ff_3&pwd=12345678&action=9"
        ).getOrThrow()
        val hotspot = TBoxQrParser.parse(
            "http://www.carbit.com.cn/x?modelid=21322&action=128&bm=DD0D3024876D"
        ).getOrThrow()
        val silent = TBoxQrParser.parse("http://www.carbit.com.cn/x?ssid=EASYCONN_5G-A1").getOrThrow()

        assertEquals("access point, Wi-Fi Direct (action=9)", both.topology.describe())
        assertEquals("phone hosts the hotspot (action=128)", hotspot.topology.describe())
        assertEquals("nothing (no action bitmask in the code)", silent.topology.describe())
    }

    @Test
    fun anOpaqueCarbitTokenPairsOverBluetooth() {
        // The whole code a Zontes S350 prints. No network, no password, no action bitmask - just
        // the dash's identity - so the only transport that can use it is the Bluetooth one.
        val payload = TBoxQrParser.parse("CARBITDC0D301738D4").getOrThrow()

        assertEquals("EC301738D4", payload.ssid)
        assertEquals("EC301738D4", payload.displayName)
        assertEquals("", payload.password)
        assertEquals("dc:0d:30:17:38:d4", payload.dashMacAddress)
        assertEquals(TBoxConnectionMode.BLE_PROVISIONED, payload.suggestedConnectionMode)
        assertEquals(TBoxQrOrigin.RECOGNISED, payload.origin)
    }

    @Test
    fun aCarbitTokenIsRecognisedWhateverCaseItIsPrintedIn() {
        assertEquals("EC301738D4", TBoxQrParser.parse("carbitdc0d301738d4").getOrThrow().ssid)
    }

    @Test
    fun somethingThatMerelyStartsWithCarbitIsNotAToken() {
        // Twelve hex digits and nothing else: a URL that happens to mention Carbit still has to
        // go through the provisioning parser, which is the one that can read its parameters.
        assertTrue(TBoxQrParser.parse("CARBITDC0D3017").isFailure)
        val url = TBoxQrParser.parse(
            "http://www.carbit.com.cn/x?ssid=EASYCONN_5G-F3116E&pwd=12345678"
        ).getOrThrow()
        assertEquals("EASYCONN_5G-F3116E", url.ssid)
    }

    @Test
    fun aBareMacIsAcceptedAsWellAsTheColonForm() {
        val payload = TBoxQrParser.parse(
            "http://www.carbit.com.cn/x?modelid=21322&action=128&bm=DD0D3024876D"
        ).getOrThrow()
        assertEquals("dd:0d:30:24:87:6d", payload.dashMacAddress)
    }

    @Test
    fun anActionBitmaskWithNoAccessPointBitMarksTheDashAsNeverOfferingOne() {
        // action=8 is Wi-Fi Direct only. Knowing that lets a caller skip an access-point attempt
        // that can only fail, and vice versa - the point of reading the mask at all.
        val p2pOnly = TBoxQrParser.parse(
            "http://www.carbit.com.cn/x?ssid=ZT5Gcf3b&pwd=12345678&auth=WPA2&action=8"
        ).getOrThrow()
        assertTrue(p2pOnly.topology.wifiDirect)
        assertFalse(p2pOnly.topology.accessPoint)
        assertTrue(p2pOnly.topology.neverOffersAccessPoint)
        assertEquals(TBoxConnectionMode.WIFI_DIRECT, p2pOnly.suggestedConnectionMode)
    }

    @Test
    fun aWifiDirectOnlyCodeForcesP2pEvenWhenTheSsidIsNotDirectShaped() {
        // The QJ SRK921 RR of field log 6b345de4 (2026-08-28). AUTO would test the SSID for a
        // "DIRECT-" prefix, not find one, and spend every attempt on an access point that is not
        // in a single Wi-Fi scan - which is exactly what happened to that rider three times.
        // A P2P code carries the dash's peer name in ssid=, never the group name, so the prefix
        // test can never pass here and the mask has to decide.
        val payload = TBoxQrParser.parse(
            "http://www.carbit.com.cn/x?ssid=qj5inch-0758&pwd=12345678&auth=WPA2&action=8&modelid=37303"
        ).getOrThrow()

        assertEquals("qj5inch-0758", payload.ssid)
        assertEquals(TBoxConnectionMode.WIFI_DIRECT, payload.suggestedConnectionMode)
    }

    @Test
    fun aCodeClaimingBothAnAccessPointAndP2pLeavesTheChoiceToAuto() {
        // action=9 advertises both. Only the dash knows which it will be on when the rider taps
        // Connect, and AUTO picks from the SSID at that moment - forcing either one here would
        // be a guess made minutes too early.
        val both = TBoxQrParser.parse(
            "http://www.carbit.com.cn/x?ssid=ZT_e0082100e5ff_3&pwd=12345678&action=9"
        ).getOrThrow()

        assertTrue(both.topology.accessPoint)
        assertTrue(both.topology.wifiDirect)
        assertNull(both.suggestedConnectionMode)
    }

    @Test
    fun aCodeWithNoActionParameterClaimsNothingAboutTopology() {
        val payload = TBoxQrParser.parse(
            "http://www.carbit.com.cn/x?ssid=CFMOTO-1234&pwd=12345678"
        ).getOrThrow()
        assertEquals(TBoxQrTopology.UNSPECIFIED, payload.topology)
        assertFalse(payload.topology.neverOffersAccessPoint)
        assertNull(payload.suggestedConnectionMode)
        assertNull(payload.dashMacAddress)
    }

    @Test
    fun aCodeWithNoSsidAndNoMacIsStillUnusable() {
        // The phone-hotspot branch needs the MAC: without it nothing identifies the dash, so this
        // must keep failing rather than producing an empty profile.
        assertTrue(
            TBoxQrParser.parse("http://www.carbit.com.cn/x?modelid=21322&action=128").isFailure
        )
    }
}
