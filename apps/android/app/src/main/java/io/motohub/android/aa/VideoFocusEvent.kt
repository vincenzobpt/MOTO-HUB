// Ported from headunit-revived (AGPLv3): aap/protocol/messages/VideoFocusEvent.kt
package io.motohub.android.aa

import com.google.protobuf.Message
import io.motohub.android.aa.proto.Media

class VideoFocusEvent(gain: Boolean, unsolicited: Boolean)
    : AapMessage(Channel.ID_VID, Media.MsgType.MEDIA_MESSAGE_VIDEO_FOCUS_NOTIFICATION_VALUE, makeProto(gain, unsolicited)) {

    companion object {
        private fun makeProto(gain: Boolean, unsolicited: Boolean): Message =
            Media.VideoFocusNotification.newBuilder().apply {
                mode = if (gain) Media.VideoFocusMode.VIDEO_FOCUS_PROJECTED else Media.VideoFocusMode.VIDEO_FOCUS_NATIVE
                this.unsolicited = unsolicited
            }.build()
    }
}
