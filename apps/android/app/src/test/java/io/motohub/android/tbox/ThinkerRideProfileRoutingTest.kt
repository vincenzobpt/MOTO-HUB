// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import org.junit.Assert.assertEquals
import org.junit.Test

class ThinkerRideProfileRoutingTest {

    @Test
    fun theQrPseudoModelIdResolvesToTheKoveProfile() {
        assertEquals(
            TBoxModelProfile.KOVE_800X,
            TBoxModelProfile.fromModelId(ThinkerRideProtocol.PROVISIONING_MODEL_ID)
        )
        assertEquals(
            TBoxModelProfile.KOVE_800X,
            TBoxModelProfile.resolve(ThinkerRideProtocol.PROVISIONING_MODEL_ID, null)
        )
    }

    @Test
    fun theManualOverridePinsTheKoveProfile() {
        assertEquals(
            TBoxModelProfile.KOVE_800X,
            TBoxModelProfile.resolve(null, null, ProfileOverride.KOVE_800X)
        )
        assertEquals(ProfileOverride.KOVE_800X, ProfileOverride.byKey("kove_800x"))
    }

    /**
     * The family now decides more than the wire. AndroidAutoSessionService.handoffKeepsProcessUnbound
     * skips the T-Box process rebind for THINKERRIDE alone, because that transport opens no
     * outbound IP socket for the default route to carry. Moving a profile into this family
     * therefore also changes its Android Auto hand-off - which is what this test still guards.
     */
    @Test
    fun onlyThinkerRideProfilesRouteToTheThinkerRideTransport() {
        assertEquals(TBoxTransportFamily.THINKERRIDE, TBoxModelProfile.KOVE_800X.transportFamily)
        assertEquals(
            TBoxTransportFamily.THINKERRIDE,
            TBoxModelProfile.KOVE_450_RALLY.transportFamily
        )
        // Every EasyConn profile must keep the wire it has always used. KOVE is ThinkerRide and
        // the X-Cape 1200 is Yunmo, the Ducati is mySPIN (MySpinProfileRoutingTest guards that
        // one), the 350 RR is EyLink (EylinkQrDialectTest); every other profile stays on EasyConn.
        TBoxModelProfile.entries
            .filterNot {
                it == TBoxModelProfile.KOVE_800X ||
                    it == TBoxModelProfile.KOVE_450_RALLY ||
                    it.transportFamily == TBoxTransportFamily.YUNMO ||
                    it == TBoxModelProfile.DUCATI_MULTISTRADA_V4 ||
                    it == TBoxModelProfile.KOVE_350RR_EYLINK
            }
            .forEach { profile ->
                assertEquals(
                    "${profile.name} must stay on EasyConn",
                    TBoxTransportFamily.EASYCONN,
                    profile.transportFamily
                )
            }
    }

    @Test
    fun theKoveProfileOwnsThePanelGeometryBecauseTheWireCannotNegotiateOne() {
        val area = TBoxModelProfile.KOVE_800X.fallbackTBoxVideoArea

        assertEquals(ThinkerRideProtocol.DEFAULT_VIDEO_WIDTH, area?.width)
        assertEquals(ThinkerRideProtocol.DEFAULT_VIDEO_HEIGHT, area?.height)
    }

    @Test
    fun theKoveStreamMatchesTheReferenceImplementation() {
        // Field logs 2026-08-13: intra refresh (IDR only every 10s), a 592-wide stream under a
        // 600-wide header, and 2.5 Mbps froze the dash 15-30s into every session, on two bikes.
        // KoveMirror's plain 1s-IDR 600x1024 ~1.8 Mbps stream runs clean on the same hardware,
        // so the profile pins the exact same stream shape.
        val profile = TBoxModelProfile.KOVE_800X

        assertEquals(1, profile.encoderKeyframeIntervalSeconds)
        assertEquals(true, profile.encoderPlainGopWithoutIntraRefresh)
        assertEquals(true, profile.encoderUsesExactVideoArea)
        assertEquals(
            ThinkerRideProtocol.DEFAULT_VIDEO_WIDTH * ThinkerRideProtocol.DEFAULT_VIDEO_HEIGHT * 3,
            profile.encoderBitRate
        )
    }

    /**
     * The 450 Rally shares the QR, the wire and the modelId space with the 800X, so the only
     * thing keeping it from breaking every existing KOVE is that it claims no modelId at all:
     * two profiles matching PROVISIONING_MODEL_ID make [TBoxModelProfile.fromModelId] answer
     * GENERIC - an EasyConn profile - and the whole family would silently lose its transport.
     */
    @Test
    fun theSecondKoveProfileNeverCompetesForTheSharedQrModelId() {
        assertEquals(
            TBoxModelProfile.KOVE_800X,
            TBoxModelProfile.fromModelId(ThinkerRideProtocol.PROVISIONING_MODEL_ID)
        )
        assertEquals(
            TBoxModelProfile.KOVE_450_RALLY,
            TBoxModelProfile.resolve(
                ThinkerRideProtocol.PROVISIONING_MODEL_ID,
                null,
                ProfileOverride.KOVE_450_RALLY
            )
        )
        assertEquals(ProfileOverride.KOVE_450_RALLY, ProfileOverride.byKey("kove_450_rally"))
    }

    /**
     * The panel the rider actually has. 1280x640 landscape, 30fps, 1s GOP and 3*w*h are what
     * ttarlov/kove-dash confirmed rendering on this exact bike and firmware (SV=3.0.x); a 450
     * Rally left on the 800X profile gets a 600x1024 portrait stream on a landscape TFT.
     */
    @Test
    fun theRallyProfileDeclaresTheLandscapePanelAndTheReferenceStream() {
        val profile = TBoxModelProfile.KOVE_450_RALLY
        val area = profile.fallbackTBoxVideoArea

        assertEquals(1280, area?.width)
        assertEquals(640, area?.height)
        assertEquals(1, profile.encoderKeyframeIntervalSeconds)
        assertEquals(true, profile.encoderPlainGopWithoutIntraRefresh)
        assertEquals(true, profile.encoderUsesExactVideoArea)
        assertEquals(1280 * 640 * 3, profile.encoderBitRate)
    }

    /**
     * The framing is per profile and must stay that way. A KOVE 800X streamed 2226 frames on
     * bare JSON (tester log 2026-08-15), so framing every ThinkerRide dash would break the one
     * rider known to work; the 450 Rally's firmware answers nothing unframed.
     */
    @Test
    fun onlyTheRallyProfileAsksForByteCatFraming() {
        assertEquals(true, TBoxModelProfile.KOVE_450_RALLY.bleUsesByteCatFraming)
        TBoxModelProfile.entries
            .filterNot { it == TBoxModelProfile.KOVE_450_RALLY }
            .forEach { profile ->
                assertEquals(
                    "${profile.name} must keep writing bare JSON",
                    false,
                    profile.bleUsesByteCatFraming
                )
            }
    }
}
