package io.motohub.android.androidauto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidAutoCapabilityProfileTest {
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
    fun `keeps the validated landscape profile for a landscape T-Box`() {
        val target = DisplayGeometry(1280, 576)

        val profile = AndroidAutoCapabilityProfiles.select(target)

        assertEquals(AndroidAutoVideoPreset.LANDSCAPE_800X480, profile.videoPreset)
        assertEquals(AndroidAutoCapabilitySource.SAVED_TBOX_GEOMETRY, profile.source)
        assertEquals(target, profile.target)
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
    fun `rejects implausible saved geometry instead of changing the AAP contract`() {
        val profile = AndroidAutoCapabilityProfiles.select(DisplayGeometry(100, 900))

        assertEquals(AndroidAutoVideoPreset.LANDSCAPE_800X480, profile.videoPreset)
        assertEquals(AndroidAutoCapabilitySource.FALLBACK, profile.source)
    }
}
