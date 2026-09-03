// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YunmoProfileRoutingTest {

    private fun overrideFor(profile: TBoxModelProfile): ProfileOverride =
        ProfileOverride.entries.first { it.resolve() == profile }

    /** The X-Cape profile and its mirror variant; everything else must stay EasyConn. */
    private val yunmoProfiles = setOf(
        TBoxModelProfile.MORINI_XCAPE_1200,
        TBoxModelProfile.MORINI_XCAPE_1200_MIRROR,
        TBoxModelProfile.MORINI_XCAPE_1200_JPEG,
        TBoxModelProfile.KOVE_625X
    )

    @Test
    fun theXCape1200ProfileRoutesToTheYunmoTransport() {
        assertEquals(TBoxTransportFamily.YUNMO, TBoxModelProfile.MORINI_XCAPE_1200.transportFamily)
    }

    @Test
    fun onlyTheXCape1200RoutesToYunmoEveryOtherProfileKeepsItsWire() {
        TBoxModelProfile.entries
            .filterNot { it in yunmoProfiles }
            .forEach { profile ->
                assertNotEquals(
                    "${profile.name} must not accidentally route to Yunmo",
                    TBoxTransportFamily.YUNMO,
                    profile.transportFamily
                )
            }
    }

    @Test
    fun theProfileMatchesTheOemEncoderSettings() {
        // Read from Ride MO 1.0.23's GoogleMediaCodecH264LiveThread, not inferred.
        val profile = TBoxModelProfile.MORINI_XCAPE_1200
        assertEquals(10, profile.encoderFrameRate)
        assertEquals(2_000_000, profile.encoderBitRate)
        assertEquals("the OEM encodes a 2-second GOP, not all-intra", 2, profile.encoderKeyframeIntervalSeconds)
        assertEquals(187, profile.virtualDisplayDpi)
        assertTrue(profile.yunmoMapNavExperiment)
    }

    @Test
    fun theSharedProductIdNeverAutoResolvesToTheYunmoProfile() {
        // ProductID 00297 is shared with the EasyConn X-Cape 649/700 and Seiemmezzo, so the QR
        // model id alone must land on GENERIC (EasyConn), never on the 1200's Yunmo profile.
        assertEquals(TBoxModelProfile.GENERIC, TBoxModelProfile.fromModelId("00297"))
        assertEquals(TBoxModelProfile.GENERIC, TBoxModelProfile.resolve("00297", null))
        assertEquals(TBoxTransportFamily.EASYCONN, TBoxModelProfile.resolve("00297", null).transportFamily)
    }

    @Test
    fun theManualOverridePinsTheYunmoProfile() {
        assertEquals(
            TBoxModelProfile.MORINI_XCAPE_1200,
            TBoxModelProfile.resolve("00297", null, ProfileOverride.MORINI_XCAPE_1200)
        )
        assertEquals(ProfileOverride.MORINI_XCAPE_1200, ProfileOverride.byKey("morini_xcape_1200"))
        assertEquals(TBoxModelProfile.MORINI_XCAPE_1200, ProfileOverride.MORINI_XCAPE_1200.resolve())
    }

    @Test
    fun theProfileCarriesFallbackGeometryAndDrivesTheOemMapNavPath() {
        val area = TBoxModelProfile.MORINI_XCAPE_1200.fallbackTBoxVideoArea!!
        assertEquals(800, area.width)
        assertEquals(480, area.height)
        // The OEM app never mirrors this dash - it always drives the navigation path with the
        // presentation the rider picked on the TFT (owner ADB capture, 2026-08-07).
        assertTrue(TBoxModelProfile.MORINI_XCAPE_1200.yunmoMapNavExperiment)
    }

    @Test
    fun mapNavStaysOffForEveryProfileThatIsNotTheXCape() {
        // The flag only ever applies to Yunmo sessions, and only this dash has evidence for it.
        TBoxModelProfile.entries
            .filterNot { it in yunmoProfiles }
            .forEach { profile ->
                assertFalse(
                    "${profile.name} must not enable the Yunmo map-nav path",
                    profile.yunmoMapNavExperiment
                )
            }
    }

    @Test
    fun theMirrorVariantDiffersOnlyInTheDisplayModeItAsksFor() {
        val base = TBoxModelProfile.MORINI_XCAPE_1200
        val mirror = TBoxModelProfile.MORINI_XCAPE_1200_MIRROR
        // The point of the variant is to isolate one question - does this dash paint without being
        // put into map-nav - so every other setting has to stay identical or it answers nothing.
        assertEquals(base.encoderFrameRate, mirror.encoderFrameRate)
        assertEquals(base.encoderBitRate, mirror.encoderBitRate)
        assertEquals(base.encoderKeyframeIntervalSeconds, mirror.encoderKeyframeIntervalSeconds)
        assertEquals(base.virtualDisplayDpi, mirror.virtualDisplayDpi)
        assertEquals(base.fallbackTBoxVideoArea, mirror.fallbackTBoxVideoArea)
        assertEquals(TBoxTransportFamily.YUNMO, mirror.transportFamily)
        assertTrue(base.yunmoMapNavExperiment)
        assertFalse("the mirror variant must ask for the mirror path", mirror.yunmoMapNavExperiment)
    }

    @Test
    fun neitherXCapeProfileIsEverAutoResolvedFromTheSharedProductId() {
        // Both are manual pins: 00297 belongs to the EasyConn 649/700/Seiemmezzo as well.
        assertEquals(TBoxModelProfile.GENERIC, TBoxModelProfile.fromModelId("00297"))
        // Reachable only by a manual pin, never by resolution from the QR.
        yunmoProfiles.forEach { profile ->
            assertNotEquals(profile, TBoxModelProfile.resolve("00297", null))
            assertEquals(profile, TBoxModelProfile.resolve("00297", null, overrideFor(profile)))
        }
    }

    @Test
    fun onlyTheTwoStillsProfilesEverCaptureStills() {
        // The guarantee that matters for every other motorcycle: this flag is what diverts a
        // session away from the AVC encoder, so only the two profiles whose dashes were
        // proven to paint stills (X-Cape 1200, KOVE 625X) may carry it.
        val jpegProfiles = TBoxModelProfile.entries.filter { it.yunmoJpegVideo }
        assertEquals(
            setOf(TBoxModelProfile.MORINI_XCAPE_1200_JPEG, TBoxModelProfile.KOVE_625X),
            jpegProfiles.toSet()
        )
    }

    @Test
    fun theJpegProfileCannotBeReachedWithoutTheRiderPinningIt() {
        // It answers to no modelId, so no QR and no capability score can land a bike here.
        // The shared ProductID must keep resolving to the EasyConn Morinis, not to this.
        assertTrue(TBoxModelProfile.fromModelId("00297") != TBoxModelProfile.MORINI_XCAPE_1200_JPEG)
        assertTrue(TBoxModelProfile.fromModelId("21322") != TBoxModelProfile.MORINI_XCAPE_1200_JPEG)
        // A rider pinning it is the only way in.
        assertEquals(
            TBoxModelProfile.MORINI_XCAPE_1200_JPEG,
            ProfileOverride.MORINI_XCAPE_1200_JPEG.resolve()
        )
    }
}
