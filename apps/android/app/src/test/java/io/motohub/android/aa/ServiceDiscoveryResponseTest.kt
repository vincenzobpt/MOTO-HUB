package io.motohub.android.aa

import io.motohub.android.aa.proto.Control
import io.motohub.android.androidauto.ActiveAndroidAutoDisplayProfile
import io.motohub.android.androidauto.DisplayGeometry
import org.junit.Assert.assertEquals
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
    }

    @Test
    fun tftGeometryCannotChangeTheAapCompatibilityContract() {
        ActiveAndroidAutoDisplayProfile.configure(DisplayGeometry(800, 386))

        val response = ServiceDiscoveryResponse()
            .parse(Control.ServiceDiscoveryResponse.newBuilder())
            .build()
        val video = response.servicesList
            .first { it.id == Channel.ID_VID }
            .mediaSinkService
            .videoConfigsList
            .single()

        assertEquals(0, video.marginWidth)
        assertEquals(0, video.marginHeight)
    }
}
