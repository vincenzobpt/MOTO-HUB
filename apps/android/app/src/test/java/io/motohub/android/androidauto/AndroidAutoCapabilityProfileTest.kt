// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.androidauto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidAutoCapabilityProfileTest {
    @Test
    fun `ignores non exact portrait geometry for a validated landscape model`() {
        val usable = AndroidAutoCapabilityProfiles.usableSavedGeometryForAuto(
            DisplayGeometry(460, 750),
            AndroidAutoVideoPreset.LANDSCAPE_800X480
        )

        assertNull(usable)
    }

    @Test
    fun `keeps portrait geometry when the landscape fallback is only the generic guess`() {
        val target = DisplayGeometry(800, 951)

        val usable = AndroidAutoCapabilityProfiles.usableSavedGeometryForAuto(
            target,
            AndroidAutoVideoPreset.LANDSCAPE_800X480,
            fallbackIsValidated = false
        )

        assertEquals(target, usable)
    }

    @Test
    fun `keeps non exact geometry when it matches the model orientation`() {
        val target = DisplayGeometry(460, 750)

        val usable = AndroidAutoCapabilityProfiles.usableSavedGeometryForAuto(
            target,
            AndroidAutoVideoPreset.PORTRAIT_720X1280
        )

        assertEquals(target, usable)
    }

    @Test
    fun `keeps exact fit geometry even when it is near square`() {
        val target = DisplayGeometry(720, 712)

        val usable = AndroidAutoCapabilityProfiles.usableSavedGeometryForAuto(
            target,
            AndroidAutoVideoPreset.LANDSCAPE_800X480
        )

        assertEquals(target, usable)
    }

    @Test
    fun `uses the validated landscape fallback when geometry is unavailable`() {
        val profile = AndroidAutoCapabilityProfiles.select(null)

        assertEquals(AndroidAutoVideoPreset.LANDSCAPE_800X480, profile.videoPreset)
        assertEquals(AndroidAutoCapabilitySource.FALLBACK, profile.source)
        assertEquals(DisplayGeometry(800, 480), profile.video)
        assertEquals(160, profile.densityDpi)
        assertNull(profile.target)
    }

    @Test
    fun `keeps the validated landscape profile for a matching landscape T-Box`() {
        val target = DisplayGeometry(800, 480)

        val profile = AndroidAutoCapabilityProfiles.select(target)

        assertEquals(AndroidAutoVideoPreset.LANDSCAPE_800X480, profile.videoPreset)
        assertEquals(AndroidAutoCapabilitySource.SAVED_TBOX_GEOMETRY, profile.source)
        assertEquals(target, profile.target)
    }

    @Test
    fun `uses the full AA source for a smaller landscape projection canvas`() {
        val target = DisplayGeometry(800, 384)

        val profile = AndroidAutoCapabilityProfiles.select(target)

        assertEquals(AndroidAutoVideoPreset.LANDSCAPE_800X480, profile.videoPreset)
        assertEquals(AndroidAutoCapabilitySource.SAVED_TBOX_GEOMETRY, profile.source)
        assertEquals(target, profile.target)
        assertEquals(0, profile.marginWidth)
        assertEquals(0, profile.marginHeight)
        assertEquals(DisplayGeometry(800, 480), profile.touchSurface)
    }

    @Test
    fun `dashboard profile does not declare preset remainder as video margin`() {
        val target = DisplayGeometry(800, 384)

        val profile = AndroidAutoCapabilityProfiles.select(target)
            .withFullVideoTarget()

        assertEquals(AndroidAutoVideoPreset.LANDSCAPE_800X480, profile.videoPreset)
        assertEquals(DisplayGeometry(800, 480), profile.target)
        assertEquals(0, profile.marginWidth)
        assertEquals(0, profile.marginHeight)
        assertEquals(DisplayGeometry(800, 480), profile.touchSurface)
    }

    @Test
   fun `selects the portrait source that covers the whole 1000 MT-X panel`() {
        // 720 wide is narrower than the 800 px panel: AA would be upscaled onto it (9EE8-EAFB).
        val target = DisplayGeometry(800, 944)

        val profile = AndroidAutoCapabilityProfiles.select(target)

        assertEquals(AndroidAutoVideoPreset.PORTRAIT_1080X1920, profile.videoPreset)
        assertEquals(AndroidAutoCapabilitySource.SAVED_TBOX_GEOMETRY, profile.source)
        assertEquals(DisplayGeometry(1080, 1920), profile.video)
        assertEquals(240, profile.densityDpi)
        assertEquals(target, profile.target)
   }

    @Test
    fun `uses explicit manual AA insets without changing projection selection`() {
        val profile = AndroidAutoCapabilityProfiles.select(
            target = DisplayGeometry(800, 480),
            screenMargins = TBoxScreenMargins(top = 40, bottom = 40)
        )

        assertEquals(AndroidAutoVideoPreset.LANDSCAPE_800X480, profile.videoPreset)
        assertEquals(DisplayGeometry(800, 400), profile.touchSurface)
        assertEquals(80, profile.marginHeight)
    }

    @Test
    fun `all supported projection shapes keep protocol margins at zero by default`() {
        val cases = listOf(
            DisplayGeometry(800, 384) to AndroidAutoVideoPreset.LANDSCAPE_800X480,
            DisplayGeometry(800, 480) to AndroidAutoVideoPreset.LANDSCAPE_800X480,
            DisplayGeometry(1280, 720) to AndroidAutoVideoPreset.LANDSCAPE_1280X720,
            DisplayGeometry(544, 512) to AndroidAutoVideoPreset.LANDSCAPE_1280X720,
            DisplayGeometry(720, 712) to AndroidAutoVideoPreset.PORTRAIT_720X1280,
            DisplayGeometry(800, 944) to AndroidAutoVideoPreset.PORTRAIT_1080X1920
        )

        cases.forEach { (projectionCanvas, expectedPreset) ->
            val profile = AndroidAutoCapabilityProfiles.select(projectionCanvas)
            assertEquals(expectedPreset, profile.videoPreset)
            assertEquals(0, profile.marginWidth)
            assertEquals(0, profile.marginHeight)
            assertEquals(profile.video, profile.touchSurface)
        }
    }

    @Test
    fun `rejects implausible saved geometry instead of changing the AAP contract`() {
        val profile = AndroidAutoCapabilityProfiles.select(DisplayGeometry(100, 900))

        assertEquals(AndroidAutoVideoPreset.LANDSCAPE_800X480, profile.videoPreset)
        assertEquals(AndroidAutoCapabilitySource.FALLBACK, profile.source)
    }

    @Test
    fun `manual HD landscape override takes precedence over learned geometry`() {
        val target = DisplayGeometry(800, 944)

        val profile = AndroidAutoCapabilityProfiles.select(
            target = target,
            overridePreset = AndroidAutoVideoPreset.LANDSCAPE_1280X720
        )

        assertEquals(AndroidAutoVideoPreset.LANDSCAPE_1280X720, profile.videoPreset)
        assertEquals(AndroidAutoCapabilitySource.USER_OVERRIDE, profile.source)
        assertEquals(DisplayGeometry(1280, 720), profile.video)
        assertEquals(160, profile.densityDpi)
        assertEquals(target, profile.target)
    }

    @Test
    fun `auto never selects a source that no dashboard has been validated on`() {
        // 1920x1080 is an exact pixel match for a source the protocol defines, so before
        // autoSelectable this geometry would have been enough to put an unproven 1080p stream
        // on a dash whose only evidence for it is its own CLIENT_INFO.
        val profile = AndroidAutoCapabilityProfiles.select(DisplayGeometry(1920, 1080))

        assertEquals(AndroidAutoVideoPreset.LANDSCAPE_800X480, profile.videoPreset)
        assertEquals(AndroidAutoCapabilitySource.SAVED_TBOX_GEOMETRY, profile.source)
    }

    @Test
    fun `the rider can still select an unvalidated source by hand`() {
        val profile = AndroidAutoCapabilityProfiles.select(
            target = null,
            overridePreset = AndroidAutoVideoPreset.PORTRAIT_2160X3840
        )

        assertEquals(AndroidAutoCapabilitySource.USER_OVERRIDE, profile.source)
        assertEquals(DisplayGeometry(2160, 3840), profile.video)
        assertEquals(480, profile.densityDpi)
    }

    @Test
    fun `an explicit density replaces the preset's own on every selection path`() {
        val fallback = AndroidAutoCapabilityProfiles.select(null, densityOverride = 320)
        val learned = AndroidAutoCapabilityProfiles.select(
            DisplayGeometry(800, 480),
            densityOverride = 320
        )
        val manual = AndroidAutoCapabilityProfiles.select(
            target = null,
            overridePreset = AndroidAutoVideoPreset.PORTRAIT_720X1280,
            densityOverride = 320
        )

        assertEquals(320, fallback.densityDpi)
        assertEquals(320, learned.densityDpi)
        assertEquals(320, manual.densityDpi)
    }

    @Test
    fun `no density override leaves the preset's own density alone`() {
        val profile = AndroidAutoCapabilityProfiles.select(
            target = null,
            overridePreset = AndroidAutoVideoPreset.LANDSCAPE_1280X720,
            densityOverride = null
        )

        assertEquals(160, profile.densityDpi)
    }

    @Test
    fun `every protocol source keeps the SD layout size in dp for its orientation`() {
        // dp = px * 160 / dpi. The extra pixels of the bigger sources are there to be sharper,
        // not to shrink the UI, so each orientation's family lands on one layout size.
        AndroidAutoVideoPreset.entries.forEach { preset ->
            val widthDp = preset.source.width * 160 / preset.densityDpi
            val heightDp = preset.source.height * 160 / preset.densityDpi
            val expected = when {
                preset == AndroidAutoVideoPreset.LANDSCAPE_800X480 -> 800 to 480
                preset.source.width >= preset.source.height -> 1280 to 720
                preset == AndroidAutoVideoPreset.PORTRAIT_720X1280 -> 480 to 853
                else -> 720 to 1280
            }
            assertEquals(preset.name, expected.first, widthDp)
            assertEquals(preset.name, expected.second, heightDp)
        }
    }

    /**
     * Rider 6e77dcf7 (samsung SM-S948B, 2026-09-06, MOTO-HUB 1.1.112): a CFMOTO6627 dash asking
     * for 784x576 LANDSCAPE over CAPTURE_CONFIG, matched to CFDL26_NK_TOUCH's portrait
     * 720x1280 preset by two generic touch capability flags. The veto refused the area AND
     * refused to save it, so the compositor letterboxed Android Auto into 311x554 of the panel
     * - 38% of the screen - on every session, forever.
     */
    @Test
    fun `keeps a landscape area a portrait preset only guessed at`() {
        val target = DisplayGeometry(784, 576)

        val usable = AndroidAutoCapabilityProfiles.usableSavedGeometryForAuto(
            target,
            AndroidAutoVideoPreset.PORTRAIT_720X1280,
            fallbackIsValidated = false
        )

        assertEquals(target, usable)
    }

    @Test
    fun `a measured landscape area still loses to a portrait profile that named the hardware`() {
        // The other half of the same rule: 784x576 is no more trustworthy than 460x750 was when
        // the profile behind the preset really did identify this dashboard.
        val usable = AndroidAutoCapabilityProfiles.usableSavedGeometryForAuto(
            DisplayGeometry(784, 576),
            AndroidAutoVideoPreset.PORTRAIT_720X1280
        )

        assertNull(usable)
    }

    @Test
    fun `the rescued landscape area buys back most of rider 6e77dcf7's panel`() {
        // What the fix is actually worth. The portrait preset composited to 311x554 inside
        // 784x576; the landscape source AUTO reaches from the measured area is the smallest one
        // that covers the panel (800x480 is 96 rows short), and the aspect margins match its
        // shape to the panel's.
        val target = DisplayGeometry(784, 576)

        val profile = AndroidAutoCapabilityProfiles.select(target).let {
            it.copy(aspectMargins = AaAspectMargins.forPanel(it.video, target))
        }

        assertEquals(AndroidAutoVideoPreset.LANDSCAPE_1280X720, profile.videoPreset)
        assertEquals(AndroidAutoCapabilitySource.SAVED_TBOX_GEOMETRY, profile.source)
        assertEquals(DisplayGeometry(980, 720), profile.touchSurface)
        assertEquals(300, profile.marginWidth)
        assertEquals(0, profile.marginHeight)
    }

    @Test
    fun `orientation reads the same for every protocol source`() {
        // usableSavedGeometryForAuto and TBoxModelProfile.hasValidatedAndroidAutoPreset compare
        // orientations across two different objects; they have to mean the same thing by it.
        AndroidAutoVideoPreset.entries.forEach { preset ->
            assertEquals(preset.name, preset.source.height > preset.source.width, preset.isPortrait)
        }
    }

    @Test
    fun `manual HD portrait override exposes the supported Android Auto source`() {
        val profile = AndroidAutoCapabilityProfiles.select(
            target = null,
            overridePreset = AndroidAutoVideoPreset.PORTRAIT_1080X1920
        )

        assertEquals(AndroidAutoCapabilitySource.USER_OVERRIDE, profile.source)
        assertEquals(DisplayGeometry(1080, 1920), profile.video)
        assertEquals(240, profile.densityDpi)
    }
}
