// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// Adapted from headunit-revived (AGPLv3): aap/protocol/messages/ServiceDiscoveryResponse.kt
// Video-only Android Auto receiver profile. These identity values match the proven-compatible
// OpenCfMoto profile; the decoder output is composed into the T-Box canvas negotiated at runtime.
package io.motohub.android.aa

import com.google.protobuf.Message
import io.motohub.android.androidauto.AndroidAutoCapabilityProfile
import io.motohub.android.androidauto.AndroidAutoCapabilityProfiles
import io.motohub.android.androidauto.AndroidAutoVideoPreset
import io.motohub.android.aa.proto.Common
import io.motohub.android.aa.proto.Control
import io.motohub.android.aa.proto.Media
import io.motohub.android.aa.proto.Sensors

class ServiceDiscoveryResponse(
    profile: AndroidAutoCapabilityProfile = AndroidAutoCapabilityProfiles.fallback(),
    /**
     * Whether to ask Android Auto for its music and speech as well as its picture.
     *
     * False keeps the phone playing them itself. True is only right when something on our side
     * will actually play what arrives - Android Auto stops routing those streams to the phone's
     * own output the moment a head unit claims them.
     */
    audioSinks: Boolean = false
) : AapMessage(
    Channel.ID_CTR,
    Control.ControlMsgType.MESSAGE_SERVICE_DISCOVERY_RESPONSE_VALUE,
    makeProto(profile, audioSinks)
) {

    companion object {
        private fun makeProto(profile: AndroidAutoCapabilityProfile, audioSinks: Boolean): Message {
            val services = mutableListOf<Control.Service>()

            // --- Sensor service (driving status + night) ---
            services.add(Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_SEN
                service.sensorSourceService = Control.Service.SensorSourceService.newBuilder().also { s ->
                    s.addSensors(makeSensorType(Sensors.SensorType.DRIVING_STATUS))
                    s.addSensors(makeSensorType(Sensors.SensorType.NIGHT))
                }.build()
            }.build())

            // --- Video service: standard AA source selected from the learned T-Box orientation. ---
            services.add(Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_VID
                service.mediaSinkService = Control.Service.MediaSinkService.newBuilder().also { sink ->
                    sink.availableType = Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP
                    sink.audioType = Media.AudioStreamType.NONE
                    sink.availableWhileInCall = true
                    sink.addVideoConfigs(
                        Control.Service.MediaSinkService.VideoConfiguration.newBuilder().apply {
                            codecResolution = profile.videoPreset.toProtocolResolution()
                            frameRate = Control.Service.MediaSinkService.VideoConfiguration.VideoFrameRateType._30
                            setDensity(profile.densityDpi)
                            setMarginWidth(profile.marginWidth)
                            setMarginHeight(profile.marginHeight)
                            setVideoCodecType(Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP)
                        }.build()
                    )
                }.build()
            }.build())

            // --- Input service ---
            services.add(Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_INP
                service.inputSourceService = Control.Service.InputSourceService.newBuilder().also { inp ->
                    AaInput.SUPPORTED_KEYCODES.forEach(inp::addKeycodesSupported)
                    if (profile.touchEnabled) {
                        inp.touchscreen = Control.Service.InputSourceService.TouchConfig.newBuilder().apply {
                            // Android Auto lays out its controls inside the declared video margins.
                            // The T-Box touch controller sees only that effective UI surface, so
                            // advertising the full source here introduces a second, wrong scale.
                            setWidth(profile.touchSurface.width)
                            setHeight(profile.touchSurface.height)
                        }.build()
                    }
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

            // --- Media and speech sinks, only when the companion has somewhere to put them. ---
            //     Claiming these is what moves Spotify, YouTube Music and the navigator's voice off
            //     the phone's own output and onto the AAP link as plain PCM - no capture consent,
            //     no per-app opt-out. Same formats headunit-revived negotiates: media 48 kHz
            //     stereo, speech 16 kHz mono.
            if (audioSinks) {
                services.add(Control.Service.newBuilder().also { service ->
                    service.id = Channel.ID_AUD
                    service.mediaSinkService = Control.Service.MediaSinkService.newBuilder().also { sink ->
                        sink.availableType = Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM
                        sink.audioType = Media.AudioStreamType.MEDIA
                        sink.addAudioConfigs(
                            Media.AudioConfiguration.newBuilder().apply {
                                sampleRate = AaAudioTap.MEDIA_SAMPLE_RATE
                                numberOfBits = 16
                                numberOfChannels = AaAudioTap.MEDIA_CHANNELS
                            }.build()
                        )
                    }.build()
                }.build())
                services.add(Control.Service.newBuilder().also { service ->
                    service.id = Channel.ID_AU1
                    service.mediaSinkService = Control.Service.MediaSinkService.newBuilder().also { sink ->
                        sink.availableType = Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM
                        sink.audioType = Media.AudioStreamType.SPEECH
                        sink.addAudioConfigs(
                            Media.AudioConfiguration.newBuilder().apply {
                                sampleRate = AaAudioTap.SPEECH_SAMPLE_RATE
                                numberOfBits = 16
                                numberOfChannels = AaAudioTap.SPEECH_CHANNELS
                            }.build()
                        )
                    }.build()
                }.build())
            }

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

            // --- Navigation status service (instrument-cluster turn-by-turn feed) ---
            // Nav apps (Google Maps, Waze, …) stream the next maneuver, distances and ETA on
            // this channel; AapControlNavigation parses it into AaNavigationGuidance for the
            // Ride Dashboard's Navigation widget. ImageCodesOnly: the widget draws its own
            // maneuver arrow, so the phone is never asked to rasterize turn icons for us.
            services.add(Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_NAV
                service.navigationStatusService = Control.Service.NavigationStatusService.newBuilder().apply {
                    minimumIntervalMs = 1000
                    type = Control.Service.NavigationStatusService.ClusterType.ImageCodesOnly
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

        private fun AndroidAutoVideoPreset.toProtocolResolution():
            Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType = when (this) {
            AndroidAutoVideoPreset.LANDSCAPE_800X480 ->
                Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._800x480
            AndroidAutoVideoPreset.LANDSCAPE_1280X720 ->
                Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1280x720
            AndroidAutoVideoPreset.LANDSCAPE_1920X1080 ->
                Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1920x1080
            AndroidAutoVideoPreset.LANDSCAPE_2560X1440 ->
                Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._2560x1440
            AndroidAutoVideoPreset.LANDSCAPE_3840X2160 ->
                Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._3840x2160
            AndroidAutoVideoPreset.PORTRAIT_720X1280 ->
                Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._720x1280
            AndroidAutoVideoPreset.PORTRAIT_1080X1920 ->
                Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1080x1920
            AndroidAutoVideoPreset.PORTRAIT_1440X2560 ->
                Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1440x2560
            AndroidAutoVideoPreset.PORTRAIT_2160X3840 ->
                Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._2160x3840
        }

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
