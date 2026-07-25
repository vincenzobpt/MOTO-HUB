package io.motohub.android.feature.ridedashboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideDashboardLayoutGeometryTest {
    @Test
    fun `near square live tft areas use portrait adaptive layout`() {
        assertTrue(shouldUsePortraitRideDashboardLayout(width = 720, height = 704))
        assertTrue(shouldUsePortraitRideDashboardLayout(width = 720, height = 712))
    }

    @Test
    fun `tall tft areas use portrait adaptive layout`() {
        assertTrue(shouldUsePortraitRideDashboardLayout(width = 720, height = 1280))
        assertTrue(shouldUsePortraitRideDashboardLayout(width = 1080, height = 1920))
    }

    @Test
    fun `classic landscape tft areas keep the landscape layout`() {
        assertFalse(shouldUsePortraitRideDashboardLayout(width = 800, height = 384))
        assertFalse(shouldUsePortraitRideDashboardLayout(width = 800, height = 480))
        assertFalse(shouldUsePortraitRideDashboardLayout(width = 1280, height = 720))
    }
}
