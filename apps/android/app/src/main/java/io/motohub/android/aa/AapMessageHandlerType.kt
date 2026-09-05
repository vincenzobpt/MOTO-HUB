// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// Adapted from headunit-revived (AGPLv3): aap/AapMessageHandlerType.kt (video + control only)
package io.motohub.android.aa

internal class AapMessageHandlerType(
    private val transport: AapTransport,
    private val aapVideo: AapVideo
) : AapMessageHandler {

    private val aapControl: AapControl = AapControlGateway(transport)

    @Throws(AapMessageHandler.HandleException::class)
    override fun handle(message: AapMessage) {
        val msgType = message.type

        // 1. Video stream first (highest priority for smooth display).
        if (message.channel == Channel.ID_VID) {
            if (aapVideo.process(message)) {
                if (msgType == 0 || msgType == 1) transport.sendMediaAck(message.channel)
                return
            }
        }

        // 2. Audio. With nobody listening we advertise one sink only to keep AA happy, and its
        //    PCM is dropped here: music and directions keep playing through the phone's own
        //    output → paired BT helmet. When the companion has asked for the audio, AA was asked
        //    for the media and speech streams at discovery and they are handed over before the
        //    ACK. ACK either way, so AA's unacked window never stalls.
        if (message.isAudio && (msgType == 0 || msgType == 1)) {
            AaAudioTap.deliver(message)
            transport.sendMediaAck(message.channel)
            return
        }

        // 3. Control message fallback.
        if (msgType in 0..31 || msgType in 32768..32799 || msgType in 65504..65535) {
            try {
                aapControl.execute(message)
            } catch (e: Exception) {
                AaLog.e(e)
                throw AapMessageHandler.HandleException(e)
            }
        } else {
            AaLog.e("Unknown msg_type: %d, flags: %d, channel: %d", msgType, message.flags, message.channel)
        }
    }
}
