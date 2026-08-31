// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import io.motohub.android.androidauto.AndroidAutoDisplayMode
import io.motohub.android.androidauto.TBoxScreenMargins

class TBoxModelProfileTest {
    @Test
    fun `finds a profile by the key CORE names it with over the bridge`() {
        // The companion app has nothing but this key to go on: CORE resolved the profile in its
        // own process and can only name it. Anything less than an exact round trip here sends the
        // dashboard back to the generic profile, which is the whole bug (rider 315e0af3).
        TBoxModelProfile.entries.forEach { profile ->
            assertEquals(profile, TBoxModelProfile.byKey(profile.key))
        }
        assertEquals(TBoxModelProfile.MORINI_XCAPE_1200, TBoxModelProfile.byKey(" morini_xcape_1200 "))
    }

    @Test
    fun `an unknown key is null rather than the generic profile`() {
        // A mismatched pair has to be distinguishable from a dash that really is generic: one
        // needs an update, the other is working as designed.
        assertNull(TBoxModelProfile.byKey("a_profile_from_a_newer_core"))
        assertNull(TBoxModelProfile.byKey(null))
        assertNull(TBoxModelProfile.byKey(""))
        assertNull(TBoxModelProfile.byKey("   "))
    }

    @Test
    fun `every profile key is unique`() {
        // byKey() picks the first match, so two profiles sharing a key would make the answer
        // depend on declaration order.
        val keys = TBoxModelProfile.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `the X-Cape profile still asks for the slow capture the send window needs`() {
        // The fix is only worth carrying across the bridge because of these two numbers: three
        // frames fit in YunmoProtocol.SEND_WINDOW, and the generic profile's 30fps does not.
        assertEquals(10, TBoxModelProfile.MORINI_XCAPE_1200.encoderFrameRate)
        assertEquals(TBoxTransportFamily.YUNMO, TBoxModelProfile.MORINI_XCAPE_1200.transportFamily)
        assertNull(TBoxModelProfile.GENERIC.encoderFrameRate)
    }

    @Test
    fun `recognizes the MOTO-HUB simulator model id`() {
        assertEquals(
            TBoxModelProfile.MOTO_HUB_SIMULATOR,
            TBoxModelProfile.fromModelId("MOTO-HUB-SIMULATOR")
        )
        assertEquals(false, TBoxModelProfile.MOTO_HUB_SIMULATOR.mapTilesRequireCellular)
    }

    @Test
    fun `recognizes the tester 800NK model id`() {
        assertEquals(TBoxModelProfile.CFMOTO_800NK, TBoxModelProfile.fromModelId("66660703"))
        assertEquals(true, TBoxModelProfile.CFMOTO_800NK.mapTilesRequireCellular)
    }

    @Test
    fun `unknown model id uses generic profile`() {
        assertEquals(TBoxModelProfile.GENERIC, TBoxModelProfile.fromModelId("unknown"))
        assertEquals(true, TBoxModelProfile.GENERIC.mapTilesRequireCellular)
        assertEquals(
            TBoxEvent.VideoArea(800, 480),
            TBoxModelProfile.fallbackVideoArea("unknown", null)
        )
    }

    @Test
    fun `pinning a real profile rescues an unrecognised model id`() {
        // The Zontes case: modelId 48405 is claimed by no profile, so detection can only reach
        // GENERIC and CLIENT_INFO scoring has nothing to score. Pinning is the rider's only way
        // to put a different supportFunction and PXC heartbeat on the wire, and both the session
        // registry's log line and CoreTBoxConnector's protocol profile depend on this resolving
        // to the pin rather than to GENERIC.
        assertEquals(TBoxModelProfile.GENERIC, TBoxModelProfile.resolve("48405", null))
        assertEquals(
            TBoxModelProfile.CFMOTO_800NK,
            TBoxModelProfile.resolve("48405", null, ProfileOverride.CFMOTO_800NK)
        )
        assertEquals(
            128,
            TBoxModelProfile.resolve("48405", null, ProfileOverride.CFMOTO_800NK)
                .advertisedSupportFunction
        )
        assertEquals(
            true,
            TBoxModelProfile.resolve("48405", null, ProfileOverride.CFMOTO_800NK)
                .requiresProactivePxcHeartbeat
        )
        // GENERIC is what the same dash gets with no pin, and it advertises no support function.
        // It does keep the proactive heartbeat: see the generic-profile test below.
        assertEquals(0, TBoxModelProfile.GENERIC.advertisedSupportFunction)
    }

    @Test
    fun `the generic profile keeps the PXC link alive by itself`() {
        // GENERIC is the fallback for every dash we cannot identify - a Zontes dash reporting an
        // empty CLIENT_INFO landed here and lost its session after ~96s of one-way streaming,
        // because nothing was keeping the reverse PXC socket busy. Firmware that does not need the
        // keepalive ignores it; firmware that does need it drops the whole session without it.
        assertEquals(true, TBoxModelProfile.GENERIC.requiresProactivePxcHeartbeat)
        assertEquals(
            true,
            TBoxModelProfile.resolve("21334", null).requiresProactivePxcHeartbeat
        )
    }

    @Test
    fun `pinning the generic override beats a recognised model id`() {
        // Detection would land on CFMOTO_800NK here. A rider who pinned Generic is saying the
        // match is wrong, so the pin has to win over the model id, not merely over an empty one.
        assertEquals(
            TBoxModelProfile.GENERIC,
            TBoxModelProfile.resolve("66660703", null, ProfileOverride.GENERIC)
        )
        assertEquals(
            TBoxEvent.VideoArea(800, 480),
            TBoxModelProfile.fallbackVideoArea("66660703", null, ProfileOverride.GENERIC)
        )
    }

    @Test
    fun `pinning the generic override withdraws the validated-preset veto`() {
        assertEquals(
            true,
            TBoxModelProfile.hasValidatedAndroidAutoPreset("66660703", null)
        )
        // Without this, a pinned Generic would keep vetoing learned geometry on the strength of
        // the very profile the rider rejected.
        assertFalse(
            TBoxModelProfile.hasValidatedAndroidAutoPreset("66660703", null, ProfileOverride.GENERIC)
        )
    }

    @Test
    fun `the generic override key survives a persistence round trip`() {
        assertEquals(ProfileOverride.GENERIC, ProfileOverride.byKey(ProfileOverride.GENERIC.key))
        assertEquals(TBoxModelProfile.GENERIC, ProfileOverride.GENERIC.resolve())
    }

    @Test
    fun `capabilities can resolve an ambiguous model id`() {
        assertEquals(
            TBoxModelProfile.CFMOTO_800NK,
            TBoxModelProfile.resolve(
                modelId = "unknown",
                capabilities = TBoxCapabilities(carModel = "CFMOTO 800NK")
            )
        )
    }

    @Test
    fun `800NK profile carries touch and display defaults`() {
        val profile = TBoxModelProfile.CFMOTO_800NK
        assertEquals(2, profile.touchPolicy.maxPointers)
        assertEquals(22, profile.defaultScreenMargins.top)
        assertFalse(profile.supportsScreenTouch)
        assertEquals(true, profile.requiresProactivePxcHeartbeat)
    }

    @Test
    fun `recognizes CRCP client info without misidentifying the MTX800`() {
        assertEquals(
            TBoxModelProfile.CFMOTO_800NK,
            TBoxModelProfile.resolve(
                modelId = "unknown",
                capabilities = TBoxCapabilities(
                    huName = "CRCP-1E9714",
                    packageName = "linux_no_package",
                    sdkVersion = "0.9.23.9"
                )
            )
        )
        assertEquals(
            TBoxModelProfile.CFMOTO_MTX800,
            TBoxModelProfile.fromModelId("66660732")
        )
        assertEquals(
            io.motohub.android.androidauto.AndroidAutoVideoPreset.PORTRAIT_720X1280,
            TBoxModelProfile.defaultAndroidAutoPreset("66660732", null)
        )
        assertEquals(TBoxScreenMargins.NONE, TBoxModelProfile.CFMOTO_MTX800.defaultScreenMargins)
        assertEquals(AndroidAutoDisplayMode.FILL, TBoxModelProfile.CFMOTO_MTX800.defaultAndroidAutoDisplayMode)
        assertEquals(true, TBoxModelProfile.CFMOTO_MTX800.requiresProactivePxcHeartbeat)
        assertEquals(
            TBoxEvent.VideoArea(460, 750),
            TBoxModelProfile.fallbackVideoArea("66660732", null)
        )
    }

    @Test
    fun `recognizes the CFDL16 MotoPlay Landscape model id`() {
        assertEquals(
            TBoxModelProfile.CFDL16_MOTOPLAY_LANDSCAPE,
            TBoxModelProfile.fromModelId("66660742")
        )
    }

    @Test
    fun `recognizes the CL-C450 model id, including its alphanumeric alias`() {
        assertEquals(TBoxModelProfile.CL_C450, TBoxModelProfile.fromModelId("66660736"))
        assertEquals(TBoxModelProfile.CL_C450, TBoxModelProfile.fromModelId("CLC450"))
    }

    @Test
    fun `modelId 37426 alone is ambiguous across three CFDL26 variants`() {
        // Same modelId as CFDL26_LANDSCAPE and CFDL26_PORTRAIT - fromModelId cannot pick one
        // without CLIENT_INFO, matching the pre-existing ambiguity behavior for that id.
        assertEquals(TBoxModelProfile.GENERIC, TBoxModelProfile.fromModelId("37426"))
    }

    @Test
    fun `CFDL26 800NK Advanced touch panel is distinguished by touch capability flags`() {
        val resolved = TBoxModelProfile.resolve(
            modelId = "37426",
            capabilities = TBoxCapabilities(
                versionName = "CFDL26.2.3.0.5",
                packageName = "com.cfmoto.easyconnect",
                socketServerAuth = true,
                sdkVersion = "1.2.0",
                supportFunction = 128,
                mirrorOverlayTouch = true,
                screenTouch = true
            )
        )
        assertEquals(TBoxModelProfile.CFDL26_NK_TOUCH, resolved)
    }

    @Test
    fun `CFDL26 portrait is distinguished from landscape by a portrait keyword hint`() {
        val resolved = TBoxModelProfile.resolve(
            modelId = "37426",
            capabilities = TBoxCapabilities(
                versionName = "CFDL26.2.3.0.5",
                packageName = "com.cfmoto.easyconnect",
                socketServerAuth = true,
                sdkVersion = "1.2.0",
                supportFunction = 128,
                carModel = "1000 MT-X Portrait"
            )
        )
        assertEquals(TBoxModelProfile.CFDL26_PORTRAIT, resolved)
    }

    @Test
    fun `ambiguous modelId 37426 without any distinguishing signal falls back to landscape`() {
        // No CLIENT_INFO at all: fromModelId alone can't disambiguate and resolve() has
        // nothing to score, so it must not silently guess - GENERIC, not a random pick.
        assertEquals(
            TBoxModelProfile.GENERIC,
            TBoxModelProfile.resolve(modelId = "37426", capabilities = null)
        )
        // Weak but real CFDL26 signal with no orientation hint at all: landscape is the
        // conservative, hardware-validated default among the three ambiguous candidates.
        val resolved = TBoxModelProfile.resolve(
            modelId = "37426",
            capabilities = TBoxCapabilities(versionName = "CFDL26.2.3.0.5")
        )
        assertEquals(TBoxModelProfile.CFDL26_LANDSCAPE, resolved)
    }

    @Test
    fun `generic EasyConn CLIENT_INFO never claims a CFMOTO profile`() {
        // Exact signals from the Zontes tester's diagnostics (2026-08-03, app 1.1.35): a modern
        // sdkVersion plus supportFunction=128 plus the generic mirrorOverlayTouch/
        // landscapeAdaptive flags scored CFDL26 800NK Advanced at 4 (and CFDL16 MotoPlay at 1),
        // forcing a portrait 720x1280 AA source on a 1024x443 landscape TFT and vetoing the
        // learned geometry. None of these flags is brand-specific, so the dash must stay GENERIC
        // and keep the learned-geometry path.
        val zontes = TBoxCapabilities(
            huName = "AJQC05-A003",
            packageName = "linux_no_package",
            versionName = "1.2.5-20240918.1057",
            sdkVersion = "1.1.3.2",
            supportFunction = 128,
            socketServerAuth = false,
            screenTouch = false,
            mirrorOverlayTouch = true,
            landscapeAdaptive = true,
            productType = 3,
            screenType = 1,
            channel = "48405"
        )
        assertEquals(TBoxModelProfile.GENERIC, TBoxModelProfile.resolve("48405", zontes))
        assertFalse(TBoxModelProfile.hasValidatedAndroidAutoPreset("48405", zontes))
    }

    @Test
    fun `weak corroborating signals still count once a real CFDL26 identity is present`() {
        // The same corroborating signals the Zontes reports must keep boosting a dash that
        // really is CFDL26 — the fix gates them, it must not discard them.
        val resolved = TBoxModelProfile.resolve(
            modelId = null,
            capabilities = TBoxCapabilities(
                versionName = "CFDL26.2.3.0.5",
                sdkVersion = "1.2.0",
                supportFunction = 128
            )
        )
        assertEquals(TBoxModelProfile.CFDL26_LANDSCAPE, resolved)
    }

    @Test
    fun `manual override selects the new profiles directly`() {
        assertEquals(TBoxModelProfile.CFDL26_NK_TOUCH, ProfileOverride.CFDL26_NK_TOUCH.resolve())
        assertEquals(
            TBoxModelProfile.CFDL16_MOTOPLAY_LANDSCAPE,
            ProfileOverride.CFDL16_MOTOPLAY_LANDSCAPE.resolve()
        )
        assertEquals(TBoxModelProfile.CL_C450, ProfileOverride.CL_C450.resolve())
        assertEquals(
            TBoxModelProfile.ZONTES_368G_TEST_B,
            ProfileOverride.ZONTES_368G_TEST_B.resolve()
        )
    }

    @Test
    fun `the two Zontes experiments differ only in the video framing`() {
        // The point of test B is to move one variable at a time: the tester's 2026-08-11 log
        // showed the indexed framing of test A killing the session sooner than GENERIC's plain
        // framing did, so B keeps the 1s GOP and hands the framing choice back to the dash.
        val a = TBoxModelProfile.ZONTES_368G_TEST
        val b = TBoxModelProfile.ZONTES_368G_TEST_B
        assertFalse(a.allowsPlainVideoFraming)
        assertEquals(true, b.allowsPlainVideoFraming)
        assertEquals(a.encoderKeyframeIntervalSeconds, b.encoderKeyframeIntervalSeconds)
        assertEquals(1, b.encoderKeyframeIntervalSeconds)
        assertEquals(a.fallbackTBoxVideoArea, b.fallbackTBoxVideoArea)
        assertEquals(a.advertisedSupportFunction, b.advertisedSupportFunction)
        assertEquals(a.requiresProactivePxcHeartbeat, b.requiresProactivePxcHeartbeat)
        // B's GOP must reach the wire as real periodic IDRs on any phone: the all-intra fallback
        // for codecs without intra refresh would otherwise erase the only variable left to test.
        assertEquals(true, b.encoderPlainGopWithoutIntraRefresh)
    }

    @Test
    fun `only unclaimed dashboards and the framing experiment honour the ext byte`() {
        // Every recognised unit streams today on indexed framing; letting its own
        // supportExtendProtocol byte change that would break bikes that work. The exceptions are
        // GENERIC, which claims nothing about the dash, and the experiments below - including the
        // QJ, which is a rate experiment and inherits GENERIC's framing precisely so that framing
        // is not a second variable in its next log.
        assertEquals(true, TBoxModelProfile.GENERIC.allowsPlainVideoFraming)
        val opted = TBoxModelProfile.entries.filter { it.allowsPlainVideoFraming }
        assertEquals(
            listOf(
                TBoxModelProfile.ZONTES_368G_TEST_B,
                TBoxModelProfile.VOGE_TEST,
                TBoxModelProfile.QJ_SRK921_RR,
                TBoxModelProfile.GENERIC
            ),
            opted
        )
    }

    @Test
    fun `the Voge experiment is GENERIC with the KOVE's stream`() {
        // The whole point of the profile is that only the video stream moves: if a tester's log
        // still shows the dash rebooting, that has to rule the stream out rather than leave a
        // second changed variable to argue about.
        val voge = TBoxModelProfile.VOGE_TEST
        val generic = TBoxModelProfile.GENERIC
        assertEquals(generic.allowsPlainVideoFraming, voge.allowsPlainVideoFraming)
        assertEquals(generic.requiresProactivePxcHeartbeat, voge.requiresProactivePxcHeartbeat)
        assertEquals(generic.requiresSockAuth, voge.requiresSockAuth)
        assertEquals(generic.advertisedSupportFunction, voge.advertisedSupportFunction)
        assertEquals(generic.supportsScreenTouch, voge.supportsScreenTouch)
        assertEquals(generic.defaultAndroidAutoDisplayMode, voge.defaultAndroidAutoDisplayMode)
        // GENERIC is all-intra; this profile is the plain 1s-IDR stream that cured the KOVE.
        assertEquals(0, generic.encoderKeyframeIntervalSeconds)
        assertEquals(1, voge.encoderKeyframeIntervalSeconds)
        assertEquals(true, voge.encoderPlainGopWithoutIntraRefresh)
        assertEquals(
            TBoxModelProfile.KOVE_800X.encoderPlainGopWithoutIntraRefresh,
            voge.encoderPlainGopWithoutIntraRefresh
        )
        // No bitrate or frame-rate cap: those would be extra variables.
        assertNull(voge.encoderBitRate)
        assertNull(voge.encoderFrameRate)
    }

    @Test
    fun `detection never claims the Voge experiment`() {
        // A Voge reports flavor 51 / channel 37504 and lands on GENERIC today. Scoring must
        // keep sending it there, or riders whose dash streams fine would change wire format
        // without asking.
        val voge = TBoxCapabilities(
            versionName = "V0.0.1",
            packageName = "com.cfmoto.cfmotointernational",
            sdkVersion = "1.0.13.1",
            supportFunction = 128
        )
        assertEquals(TBoxModelProfile.GENERIC, TBoxModelProfile.resolve("37504", voge))
        assertEquals(
            TBoxModelProfile.VOGE_TEST,
            TBoxModelProfile.resolve("37504", voge, ProfileOverride.VOGE_TEST)
        )
    }

    @Test
    fun `the QJ SRK921 RR profile changes the rate and nothing else`() {
        // The ladder already denied both framings on this dash, so the profile must differ from
        // GENERIC in the one dimension the ladder had no rung for. Anything else changed here
        // would leave a second variable in play when the next log comes back.
        val qj = TBoxModelProfile.QJ_SRK921_RR
        val generic = TBoxModelProfile.GENERIC
        assertEquals(generic.allowsPlainVideoFraming, qj.allowsPlainVideoFraming)
        assertEquals(generic.requiresProactivePxcHeartbeat, qj.requiresProactivePxcHeartbeat)
        assertEquals(generic.requiresSockAuth, qj.requiresSockAuth)
        assertEquals(generic.defaultAndroidAutoDisplayMode, qj.defaultAndroidAutoDisplayMode)
        // GENERIC guesses 30 fps all-intra; this dash gets the reference fork's 10 fps / 2s GOP.
        assertEquals(0, generic.encoderKeyframeIntervalSeconds)
        assertEquals(2, qj.encoderKeyframeIntervalSeconds)
        assertEquals(10, qj.encoderFrameRate)
        assertEquals(2_000_000, qj.encoderBitRate)
        assertEquals(true, qj.encoderPlainGopWithoutIntraRefresh)
        // CLIENT_INFO says supportScreenTouch=false and supportFunction=128; echo both.
        assertEquals(false, qj.supportsScreenTouch)
        assertEquals(128, qj.advertisedSupportFunction)
    }

    @Test
    fun `the QJ profile is claimed by its modelId and never by a shared licence`() {
        // 37303 is this dashboard alone across the collector, so the QR may carry the profile.
        val qj = TBoxCapabilities(
            versionName = "1.0.0",
            packageName = "linux_no_package",
            sdkVersion = "0.9.23.1",
            supportFunction = 128,
            screenTouch = false,
            landscapeAdaptive = true,
            productType = 3,
            screenType = 1,
            flavor = "51",
            channel = "37303"
        )
        assertEquals(TBoxModelProfile.QJ_SRK921_RR, TBoxModelProfile.resolve("37303", qj))
        // The same firmware signals on a different dashboard must not: flavor 51 also covers a
        // Voge Valico and two further rebadges, and none of them asked for a 10 fps stream.
        assertNotEquals(
            TBoxModelProfile.QJ_SRK921_RR,
            TBoxModelProfile.resolve("37504", qj.copy(channel = "37504"))
        )
        // ...and neither may CLIENT_INFO alone, with no modelId to lead on.
        assertNotEquals(TBoxModelProfile.QJ_SRK921_RR, TBoxModelProfile.resolve(null, qj))
    }

    @Test
    fun `detection never claims either Zontes experiment`() {
        // Both are manual pins: a JCDZ dash that lands on them by detection would silently
        // change wire format for riders whose Zontes already streams.
        val zontes = TBoxCapabilities(
            versionName = "1.0.1",
            sdkVersion = "1.1.3.2",
            supportFunction = 128,
            socketServerAuth = false,
            screenTouch = false,
            mirrorOverlayTouch = true,
            landscapeAdaptive = true,
            productType = 3,
            screenType = 1,
            channel = "21334"
        )
        assertEquals(TBoxModelProfile.GENERIC, TBoxModelProfile.resolve("21334", zontes))
    }

    /**
     * Rider 36ee9d2c (2026-08-24), a Benelli TRK 702X: CLIENT_INFO carries no brand, no model and
     * no HUName a profile knows, so the only thing that matched was the 0.9.23 + linux_no_package
     * firmware dialect - and that scored CFMOTO 800NK 3, CL-C450 1, GENERIC 0. Core's Android
     * Auto took the win and letterboxed his 800x480 panel to 763x458 behind a CFMOTO dash's 22px
     * status-bar margin.
     */
    private val benelliTrk702x = TBoxCapabilities(
        huName = "ZHKJ13-1122",
        packageName = "linux_no_package",
        pxcVersion = "1.0.2",
        sdkVersion = "0.9.23.4",
        versionName = "1.0.0",
        versionCode = "0",
        supportFunction = 128,
        screenTouch = false,
        landscapeAdaptive = true,
        productType = 3,
        screenType = 1,
        flavor = "51",
        channel = "34813"
    )

    @Test
    fun `a Carbit-licensed dash is not claimed by the CFMOTO firmware dialect`() {
        assertEquals(TBoxModelProfile.GENERIC, TBoxModelProfile.resolve(null, benelliTrk702x))
        assertEquals(TBoxModelProfile.GENERIC, TBoxModelProfile.resolve("34813", benelliTrk702x))
    }

    @Test
    fun `the same firmware dialect still identifies a dash no other licence claims`() {
        // The guard must not cost a real 800NK its profile: same dialect, no Carbit licence.
        val nk800 = benelliTrk702x.copy(huName = null, flavor = "65540", channel = null)
        assertEquals(TBoxModelProfile.CFMOTO_800NK, TBoxModelProfile.resolve(null, nk800))
        // And a dash that reports no flavour at all is exactly where it was before the guard.
        assertEquals(
            TBoxModelProfile.CFMOTO_800NK,
            TBoxModelProfile.resolve(null, nk800.copy(flavor = null))
        )
    }

    @Test
    fun `a dash that names itself outranks its licence`() {
        // The licence only stops a fingerprint carrying the profile alone. A unit that says
        // 800NK in CLIENT_INFO is one, whoever licensed the stack it runs.
        val named = benelliTrk702x.copy(huName = "CFMOTO 800NK")
        assertEquals(TBoxModelProfile.CFMOTO_800NK, TBoxModelProfile.resolve(null, named))
    }

    @Test
    fun `the CL-C450 corroboration cannot carry that profile either`() {
        // With CFMOTO_800NK refused, this was the next thing standing: one point for 0.9.23,
        // enough to put a 544x512 profile on an 800x480 Benelli panel.
        assertEquals(TBoxModelProfile.GENERIC, TBoxModelProfile.resolve(null, benelliTrk702x))
        val clc450 = benelliTrk702x.copy(huName = "48FB4C-0001")
        assertEquals(TBoxModelProfile.CL_C450, TBoxModelProfile.resolve(null, clc450))
    }
}
