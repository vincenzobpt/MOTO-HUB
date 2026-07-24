package io.motohub.android.tbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import io.motohub.android.androidauto.AndroidAutoDisplayMode
import io.motohub.android.androidauto.TBoxScreenMargins

class TBoxModelProfileTest {
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
        assertEquals(false, TBoxModelProfile.GENERIC.requiresProactivePxcHeartbeat)
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
    fun `manual override selects the new profiles directly`() {
        assertEquals(TBoxModelProfile.CFDL26_NK_TOUCH, ProfileOverride.CFDL26_NK_TOUCH.resolve())
        assertEquals(
            TBoxModelProfile.CFDL16_MOTOPLAY_LANDSCAPE,
            ProfileOverride.CFDL16_MOTOPLAY_LANDSCAPE.resolve()
        )
        assertEquals(TBoxModelProfile.CL_C450, ProfileOverride.CL_C450.resolve())
    }
}
