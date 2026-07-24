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
            .withFullVideoTargetForDashboard()

        assertEquals(AndroidAutoVideoPreset.LANDSCAPE_800X480, profile.videoPreset)
        assertEquals(DisplayGeometry(800, 480), profile.target)
        assertEquals(0, profile.marginWidth)
        assertEquals(0, profile.marginHeight)
        assertEquals(DisplayGeometry(800, 480), profile.touchSurface)
    }

    @Test
   fun `selects the standard portrait profile for a portrait T-Box`() {
        val target = DisplayGeometry(800, 944)

        val profile = AndroidAutoCapabilityProfiles.select(target)

        assertEquals(AndroidAutoVideoPreset.PORTRAIT_720X1280, profile.videoPreset)
        assertEquals(AndroidAutoCapabilitySource.SAVED_TBOX_GEOMETRY, profile.source)
        assertEquals(DisplayGeometry(720, 1280), profile.video)
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
            DisplayGeometry(544, 512) to AndroidAutoVideoPreset.LANDSCAPE_800X480,
            DisplayGeometry(720, 712) to AndroidAutoVideoPreset.PORTRAIT_720X1280,
            DisplayGeometry(800, 944) to AndroidAutoVideoPreset.PORTRAIT_720X1280
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
