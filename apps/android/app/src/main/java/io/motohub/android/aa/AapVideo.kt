// Adapted from headunit-revived (AGPLv3): aap/AapVideo.kt (H.264 only, no Settings)
// Reassembles AA video-channel messages (fragment flags 11/9/8/10), strips the AAP media
// header (2- or 10-byte), and feeds Annex-B access units to the H.264 VideoDecoder.
package io.motohub.android.aa

import java.nio.ByteBuffer

internal class AapVideo(
    private val videoDecoder: VideoDecoder,
    private val onFrameCorrupted: () -> Unit
) {
    // ~2 MiB is ample for access units from the supported standard AA source profiles.
    private val messageBuffer = ByteBuffer.allocate(Messages.DEF_BUFFER_LENGTH * 16)
    private var isFrameCorrupt = false
    private var lastKeyframeRequestMs = 0L

    private fun markCorruptAndRequestRecovery() {
        if (!isFrameCorrupt) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastKeyframeRequestMs > 1000) {
                lastKeyframeRequestMs = now
                AaLog.w("AapVideo: Frame corrupted, requesting keyframe to recover stream")
                onFrameCorrupted()
            }
        }
        isFrameCorrupt = true
    }

    private fun findStartCode(buf: ByteArray, offset: Int): Int {
        if (offset + 3 > buf.size) return -1
        if (buf[offset].toInt() == 0 && buf[offset + 1].toInt() == 0) {
            if (buf[offset + 2].toInt() == 1) return 3
            if (offset + 4 <= buf.size && buf[offset + 2].toInt() == 0 && buf[offset + 3].toInt() == 1) return 4
        }
        return -1
    }

    fun process(message: AapMessage): Boolean {
        val flags = message.flags.toInt()
        val buf = message.data
        val len = message.size

        when (flags) {
            11 -> { // Single-fragment frame
                isFrameCorrupt = false
                messageBuffer.clear()
                val sc10 = findStartCode(buf, 10)
                if (len > 10 + sc10 && sc10 > 0) {
                    videoDecoder.decode(buf, 10, len - 10)
                    return true
                }
                val sc2 = findStartCode(buf, 2)
                if (len > 2 + sc2 && sc2 > 0) {
                    videoDecoder.decode(buf, 2, len - 2)
                    return true
                }
                AaLog.w("AapVideo: Dropped Flag 11 packet. len=$len")
            }
            9 -> { // First fragment
                isFrameCorrupt = false
                messageBuffer.clear()
                val sc10 = findStartCode(buf, 10)
                if (len > 10 + sc10 && sc10 > 0) {
                    messageBuffer.put(message.data, 10, message.size - 10)
                    return true
                }
                val sc2 = findStartCode(buf, 2)
                if (len > 2 + sc2 && sc2 > 0) {
                    messageBuffer.put(message.data, 2, message.size - 2)
                    return true
                }
            }
            8 -> { // Middle fragment
                if (isFrameCorrupt) return true
                if (messageBuffer.remaining() >= message.size) {
                    messageBuffer.put(message.data, 0, message.size)
                } else {
                    AaLog.e("AapVideo: Fragment overflow (Flag 8)! Invalidating frame.")
                    markCorruptAndRequestRecovery()
                    messageBuffer.clear()
                }
                return true
            }
            10 -> { // Last fragment → assemble + decode
                if (isFrameCorrupt) return true
                if (messageBuffer.remaining() >= message.size) {
                    messageBuffer.put(message.data, 0, message.size)
                } else {
                    AaLog.e("AapVideo: Final fragment overflow (Flag 10)! Invalidating frame.")
                    markCorruptAndRequestRecovery()
                    messageBuffer.clear()
                    return true
                }
                messageBuffer.flip()
                val assembledSize = messageBuffer.limit()
                videoDecoder.decode(messageBuffer.array(), 0, assembledSize)
                messageBuffer.clear()
                return true
            }
        }
        return false
    }

    fun release() { /* synchronous decode; nothing to release */ }
}
