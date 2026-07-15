// Adapted from headunit-revived (AGPLv3): aap/protocol/messages/ServiceDiscoveryResponse.kt
// Video-only Android Auto receiver profile. These identity values match the proven-compatible
// OpenCfMoto profile; the decoder output is composed into the T-Box canvas negotiated at runtime.
package io.motohub.android.aa

import com.google.protobuf.Message
import io.motohub.android.aa.proto.Common
import io.motohub.android.aa.proto.Control
import io.motohub.android.aa.proto.Media
import io.motohub.android.aa.proto.Sensors

class ServiceDiscoveryResponse
    : AapMessage(Channel.ID_CTR, Control.ControlMsgType.MESSAGE_SERVICE_DISCOVERY_RESPONSE_VALUE, makeProto()) {

    companion object {
        const val AA_WIDTH = 800
        const val AA_HEIGHT = 480
        private const val AA_DENSITY = 160

        private fun makeProto(): Message {
            val services = mutableListOf<Control.Service>()

            // --- Sensor service (driving status + night) ---
            services.add(Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_SEN
                service.sensorSourceService = Control.Service.SensorSourceService.newBuilder().also { s ->
                    s.addSensors(makeSensorType(Sensors.SensorType.DRIVING_STATUS))
                    s.addSensors(makeSensorType(Sensors.SensorType.NIGHT))
                }.build()
            }.build())

            // --- Video service: fixed Android Auto source canvas, composed into T-Box VideoArea. ---
            services.add(Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_VID
                service.mediaSinkService = Control.Service.MediaSinkService.newBuilder().also { sink ->
                    sink.availableType = Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP
                    sink.audioType = Media.AudioStreamType.NONE
                    sink.availableWhileInCall = true
                    sink.addVideoConfigs(
                        Control.Service.MediaSinkService.VideoConfiguration.newBuilder().apply {
                            codecResolution = Control.Service.MediaSinkService.VideoConfiguration
                                .VideoCodecResolutionType._800x480
                            frameRate = Control.Service.MediaSinkService.VideoConfiguration.VideoFrameRateType._30
                            setDensity(AA_DENSITY)
                            // Keep the AAP contract byte-compatible with the proven OpenCfMoto
                            // profile. TFT-specific fill/crop belongs exclusively to AaCompositor.
                            setMarginWidth(0)
                            setMarginHeight(0)
                            setVideoCodecType(Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP)
                        }.build()
                    )
                }.build()
            }.build())

            // --- Input service ---
            services.add(Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_INP
                service.inputSourceService = Control.Service.InputSourceService.newBuilder().also { inp ->
                    inp.touchscreen = Control.Service.InputSourceService.TouchConfig.newBuilder().apply {
                        setWidth(AA_WIDTH)
                        setHeight(AA_HEIGHT)
                    }.build()
                }.build()
            }.build())

            // --- Audio2 sink (system sounds). Android Auto rejects a head unit that advertises
            //     no audio sink and drops the connection right after service discovery, so we
            //     always advertise this even though the PCM is discarded — nav audio plays via the
            //     phone's own output → BT helmet, not through us. See AapMessageHandlerType. ---
            services.add(Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_AU2
                service.mediaSinkService = Control.Service.MediaSinkService.newBuilder().also { sink ->
                    sink.availableType = Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM
                    sink.audioType = Media.AudioStreamType.SYSTEM
                    sink.addAudioConfigs(
                        Media.AudioConfiguration.newBuilder().apply {
                            sampleRate = 16000
                            numberOfBits = 16
                            numberOfChannels = 1
                        }.build()
                    )
                }.build()
            }.build())

            // --- Microphone service (required for AA connection / Assistant) ---
            services.add(Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_MIC
                service.mediaSourceService = Control.Service.MediaSourceService.newBuilder().also { src ->
                    src.type = Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM
                    src.audioConfig = Media.AudioConfiguration.newBuilder().apply {
                        sampleRate = 16000
                        numberOfBits = 16
                        numberOfChannels = 1
                    }.build()
                }.build()
            }.build())

            return Control.ServiceDiscoveryResponse.newBuilder().apply {
                make = VEHICLE_MAKE
                model = VEHICLE_MODEL
                year = VEHICLE_YEAR
                vehicleId = VEHICLE_ID
                headUnitModel = HEAD_UNIT_MODEL
                headUnitMake = HEAD_UNIT_MAKE
                headUnitSoftwareBuild = HEAD_UNIT_BUILD
                headUnitSoftwareVersion = HEAD_UNIT_VERSION
                driverPosition = Control.DriverPosition.DRIVER_POSITION_LEFT
                canPlayNativeMediaDuringVr = false
                hideProjectedClock = false
                setDisplayName(VEHICLE_MAKE)
                setHeadunitInfo(Common.HeadUnitInfo.newBuilder().apply {
                    setHeadUnitMake(HEAD_UNIT_MAKE)
                    setHeadUnitModel(HEAD_UNIT_MODEL)
                    setMake(VEHICLE_MAKE)
                    setModel(VEHICLE_MODEL)
                    setYear(VEHICLE_YEAR)
                    setVehicleId(VEHICLE_ID)
                    setHeadUnitSoftwareBuild(HEAD_UNIT_BUILD)
                    setHeadUnitSoftwareVersion(HEAD_UNIT_VERSION)
                }.build())
                addAllServices(services)
            }.build()
        }

        private fun makeSensorType(type: Sensors.SensorType): Control.Service.SensorSourceService.Sensor =
            Control.Service.SensorSourceService.Sensor.newBuilder().setType(type).build()

        private const val VEHICLE_MAKE = "OpenCfMoto"
        private const val VEHICLE_MODEL = "MotoPlay"
        private const val VEHICLE_YEAR = "2024"
        private const val VEHICLE_ID = "opencfmoto"
        private const val HEAD_UNIT_MAKE = "CFMoto"
        private const val HEAD_UNIT_MODEL = "CFDL16-6GUV"
        private const val HEAD_UNIT_BUILD = "1"
        private const val HEAD_UNIT_VERSION = "0.1.0"
    }
}
