package io.motohub.android.androidauto

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidAutoDisplayProfileTest {
    @Test
    fun computesVerticalMarginsForEightHundredByFourHundredTft() {
        val profile = calculateAndroidAutoDisplayProfile(DisplayGeometry(800, 400))

        assertEquals(0, profile.marginWidth)
        assertEquals(80, profile.marginHeight)
        assertEquals(800, profile.contentWidth)
        assertEquals(400, profile.contentHeight)
        assertEquals(40, profile.cropTop)
    }

    @Test
    fun computesVerticalMarginsForAReportedLandscapeTft() {
        val profile = calculateAndroidAutoDisplayProfile(DisplayGeometry(800, 384))

        assertEquals(0, profile.marginWidth)
        assertEquals(96, profile.marginHeight)
        assertEquals(800, profile.contentWidth)
        assertEquals(384, profile.contentHeight)
        assertEquals(48, profile.cropTop)
    }

    @Test
    fun computesHorizontalMarginsForPortraitCanvas() {
        val profile = calculateAndroidAutoDisplayProfile(DisplayGeometry(400, 800))

        assertEquals(560, profile.marginWidth)
        assertEquals(0, profile.marginHeight)
        assertEquals(240, profile.contentWidth)
        assertEquals(480, profile.contentHeight)
    }

    @Test
    fun uncalibratedProfilePreservesTheWholeAndroidAutoCanvas() {
        val profile = ActiveAndroidAutoDisplayProfile.configureUncalibrated()

        assertEquals(0, profile.marginWidth)
        assertEquals(0, profile.marginHeight)
        assertEquals(800, profile.contentWidth)
        assertEquals(480, profile.contentHeight)
    }
}
