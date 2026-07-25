package io.motohub.android.aa

import io.motohub.android.aa.proto.Control
import io.motohub.android.androidauto.AndroidAutoCapabilityProfiles
import io.motohub.android.androidauto.AndroidAutoCapabilitySource
import io.motohub.android.androidauto.AndroidAutoVideoPreset
import io.motohub.android.androidauto.DisplayGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ServiceDiscoveryResponseTest {
    @Test
    fun usesTheKnownCompatibleOpenCfMotoHeadUnitProfile() {
        val message = ServiceDiscoveryResponse()
        val response = message.parse(Control.ServiceDiscoveryResponse.newBuilder()).build()

        assertEquals("OpenCfMoto", response.make)
        assertEquals("MotoPlay", response.model)
        assertEquals("2024", response.year)
        assertEquals("opencfmoto", response.vehicleId)
        assertEquals("CFMoto", response.headUnitMake)
        assertEquals("CFDL16-6GUV", response.headUnitModel)
        assertEquals("0.1.0", response.headUnitSoftwareVersion)

        val video = response.servicesList
            .first { it.id == Channel.ID_VID }
            .mediaSinkService
            .videoConfigsList
            .single()
        assertEquals(
            Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._800x480,
            video.codecResolution
        )
        assertEquals(160, video.density)
        assertEquals(0, video.marginWidth)
        assertEquals(0, video.marginHeight)

        val touch = response.servicesList
            .first { it.id == Channel.ID_INP }
            .inputSourceService
            .touchscreen
        assertEquals(800, touch.width)
        assertEquals(480, touch.height)
    }

    @Test
    fun advertisesPortraitVideoAndTouchFromTheSelectedCapabilityProfile() {
        val profile = AndroidAutoCapabilityProfiles.select(DisplayGeometry(800, 944))

        val response = ServiceDiscoveryResponse(profile)
            .parse(Control.ServiceDiscoveryResponse.newBuilder())
            .build()
        val video = response.servicesList
            .first { it.id == Channel.ID_VID }
            .mediaSinkService
            .videoConfigsList
            .single()

        assertEquals(
            Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._720x1280,
            video.codecResolution
        )
        assertEquals(240, video.density)
        assertEquals(AndroidAutoVideoPreset.PORTRAIT_720X1280, profile.videoPreset)
        assertEquals(AndroidAutoCapabilitySource.SAVED_TBOX_GEOMETRY, profile.source)

        val touch = response.servicesList
            .first { it.id == Channel.ID_INP }
            .inputSourceService
            .touchscreen
        assertEquals(720, touch.width)
        assertEquals(1280, touch.height)
    }

    @Test
    fun smallerProjectionCanvasDoesNotBecomeAndroidAutoMargins() {
        val profile = AndroidAutoCapabilityProfiles.select(DisplayGeometry(800, 384))

        val response = ServiceDiscoveryResponse(profile)
            .parse(Control.ServiceDiscoveryResponse.newBuilder())
            .build()
        val video = response.servicesList
            .first { it.id == Channel.ID_VID }
            .mediaSinkService
            .videoConfigsList
            .single()

       assertEquals(
            Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._800x480,
           video.codecResolution
       )
       assertEquals(160, video.density)
       assertEquals(0, video.marginWidth)
        assertEquals(0, video.marginHeight)

        val touch = response.servicesList
            .first { it.id == Channel.ID_INP }
            .inputSourceService
            .touchscreen
        assertEquals(800, touch.width)
        assertEquals(480, touch.height)
    }

    @Test
    fun omitsTouchscreenForAHandlebarOnlyProfile() {
        val profile = AndroidAutoCapabilityProfiles.select(
            target = null,
            touchEnabled = false
        )
        val response = ServiceDiscoveryResponse(profile)
            .parse(Control.ServiceDiscoveryResponse.newBuilder())
            .build()

        val input = response.servicesList
            .first { it.id == Channel.ID_INP }
            .inputSourceService
        assertFalse(input.hasTouchscreen())
    }

    @Test
    fun advertisesManualLandscapeHdVideoAndMatchingTouchSurface() {
        assertManualSource(
            preset = AndroidAutoVideoPreset.LANDSCAPE_1280X720,
            expectedProtocol = Control.Service.MediaSinkService.VideoConfiguration
                .VideoCodecResolutionType._1280x720,
            expectedWidth = 1280,
            expectedHeight = 720,
            expectedDensity = 160
        )
    }

    @Test
    fun advertisesManualPortraitHdVideoAndMatchingTouchSurface() {
        assertManualSource(
            preset = AndroidAutoVideoPreset.PORTRAIT_1080X1920,
            expectedProtocol = Control.Service.MediaSinkService.VideoConfiguration
                .VideoCodecResolutionType._1080x1920,
            expectedWidth = 1080,
            expectedHeight = 1920,
            expectedDensity = 240
        )
    }

    private fun assertManualSource(
        preset: AndroidAutoVideoPreset,
        expectedProtocol: Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType,
        expectedWidth: Int,
        expectedHeight: Int,
        expectedDensity: Int
    ) {
        val profile = AndroidAutoCapabilityProfiles.select(null, preset)
        val response = ServiceDiscoveryResponse(profile)
            .parse(Control.ServiceDiscoveryResponse.newBuilder())
            .build()
        val video = response.servicesList
            .first { it.id == Channel.ID_VID }
            .mediaSinkService
            .videoConfigsList
            .single()
        val touch = response.servicesList
            .first { it.id == Channel.ID_INP }
            .inputSourceService
            .touchscreen

        assertEquals(expectedProtocol, video.codecResolution)
        assertEquals(expectedDensity, video.density)
        assertEquals(expectedWidth, touch.width)
        assertEquals(expectedHeight, touch.height)
    }
}
