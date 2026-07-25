// Ported from headunit-revived (AGPLv3): aap/protocol/messages/MediaAck.kt
package io.motohub.android.aa

import com.google.protobuf.Message
import io.motohub.android.aa.proto.Media

class MediaAck(channel: Int, sessionId: Int)
    : AapMessage(channel, Media.MsgType.MEDIA_MESSAGE_ACK_VALUE, makeProto(sessionId)) {

    companion object {
        private fun makeProto(sessionId: Int): Message =
            Media.Ack.newBuilder().apply {
                this.sessionId = sessionId
                this.ack = 1
            }.build()
    }
}
