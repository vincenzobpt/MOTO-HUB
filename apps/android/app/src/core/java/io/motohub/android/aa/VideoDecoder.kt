// Adapted from headunit-revived (AGPLv3): decoder/VideoDecoder.kt
// Trimmed to the H.264 (video/avc) hardware/software MediaCodec path only. HUR's H.265/HEVC,
// bundled FFmpeg, software-YUV GL sink, and Settings dependencies are removed. MOTO-HUB requests
// only H.264 in service discovery and renders straight to a caller-supplied Surface.
package io.motohub.android.aa

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import java.nio.ByteBuffer
import java.util.Locale

interface VideoDimensionsListener {
    fun onVideoDimensionsChanged(width: Int, height: Int)
}

class VideoDecoder {
    companion object {
        private const val TIMEOUT_US = 10000L
    }

    private var codec: MediaCodec? = null
    private var codecBufferInfo: MediaCodec.BufferInfo? = null
    private var mSurface: Surface? = null
    private var outputThread: Thread? = null
    @Volatile private var running = false
    private var startTime = 0L

    private var mWidth = 0
    private var mHeight = 0
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var codecConfigured = false
    private var currentCodecName: String? = null

    /** Fallback dimensions used if SPS parsing fails (set to the negotiated AA resolution). */
    @Volatile var fallbackWidth = 0
    @Volatile var fallbackHeight = 0

    var dimensionsListener: VideoDimensionsListener? = null
    var onFpsChanged: ((Int) -> Unit)? = null
    private var frameCount = 0
    private var lastFpsLogTime = 0L
    @Volatile var onFirstFrameListener: (() -> Unit)? = null
    @Volatile var lastFrameRenderedMs: Long = 0L

    @Volatile private var decoderNeedsRestart = false
    @Volatile private var decoderRestartReason: String? = null
    /** When we last fed an input buffer — used to tell a real decoder stall (input flowing, no output)
     *  from Android Auto simply pausing video (no input) during a UI transition or call. */
    @Volatile private var lastInputMs = 0L

    /** Invoked when the decoder must be re-primed — the transport requests a fresh keyframe. */
    var onDecoderError: (() -> Unit)? = null

    val videoWidth: Int get() = mWidth
    val videoHeight: Int get() = mHeight

    private fun handleOutputFormatChange(format: MediaFormat) {
        AaLog.i("Output Format Changed: $format")
        val newWidth = try { format.getInteger(MediaFormat.KEY_WIDTH) } catch (e: Exception) { mWidth }
        val newHeight = try { format.getInteger(MediaFormat.KEY_HEIGHT) } catch (e: Exception) { mHeight }
        if (mWidth != newWidth || mHeight != newHeight) {
            AaLog.i("Video dimensions changed via format: ${newWidth}x$newHeight")
            mWidth = newWidth
            mHeight = newHeight
            dimensionsListener?.onVideoDimensionsChanged(mWidth, mHeight)
        }
        try { codec?.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT) } catch (e: Exception) {}
    }

    fun setSurface(surface: Surface?) {
        synchronized(this) {
            if (mSurface === surface) return
            AaLog.i("New surface set: $surface")
            if (codec != null) stop("New surface")
            mSurface = surface
            lastFrameRenderedMs = 0L
        }
    }

    fun stop(reason: String = "unknown") {
        synchronized(this) {
            running = false
            try {
                if (outputThread != null && outputThread != Thread.currentThread()) {
                    outputThread?.interrupt()
                    outputThread?.join(500)
                }
            } catch (e: Exception) {}
            outputThread = null

            try { codec?.stop() } catch (e: Exception) {}
            try { codec?.release() } catch (e: Exception) { AaLog.e("Error releasing decoder", e) }

            codec = null
            codecBufferInfo = null
            codecConfigured = false
            if (!reason.startsWith("restart")) {
                sps = null
                pps = null
                mWidth = 0
                mHeight = 0
            }
            lastFrameRenderedMs = 0L
            AaLog.i("Decoder stopped: $reason")
        }
    }

    private fun scheduleRestart(reason: String) {
        decoderRestartReason = reason
        decoderNeedsRestart = true
    }

    fun decode(buffer: ByteArray, offset: Int, size: Int) {
        synchronized(this) {
            if (decoderNeedsRestart) {
                AaLog.w("Decoder restart requested: $decoderRestartReason")
                stop("restart: $decoderRestartReason")
                decoderNeedsRestart = false
                decoderRestartReason = null
                onDecoderError?.invoke()
            }

            if (codec == null) {
                if (!codecConfigured) {
                    scanAndApplyConfig(buffer, offset, size)
                    if (mWidth == 0 && fallbackWidth > 0 && fallbackHeight > 0) {
                        AaLog.i("Fallback to negotiated dimensions: ${fallbackWidth}x$fallbackHeight")
                        mWidth = fallbackWidth
                        mHeight = fallbackHeight
                        dimensionsListener?.onVideoDimensionsChanged(mWidth, mHeight)
                    }
                }

                if (mSurface == null || !mSurface!!.isValid) return
                if (mWidth == 0 || mHeight == 0) return

                start(mWidth, mHeight)
            }

            if (codec == null) return

            val buf = ByteBuffer.wrap(buffer, offset, size)
            while (buf.hasRemaining()) {
                if (!feedInputBuffer(buf)) return
            }
        }
    }

    private fun forEachNalUnit(buffer: ByteArray, offset: Int, size: Int, callback: (ByteArray, Int) -> Unit) {
        var currentPos = offset
        val limit = offset + size

        while (currentPos < limit - 3) {
            var nalStart = -1
            var startCodeLen = 0
            for (i in currentPos until limit - 3) {
                if (buffer[i].toInt() == 0 && buffer[i + 1].toInt() == 0) {
                    if (buffer[i + 2].toInt() == 0 && buffer[i + 3].toInt() == 1) { nalStart = i; startCodeLen = 4; break }
                    else if (buffer[i + 2].toInt() == 1) { nalStart = i; startCodeLen = 3; break }
                }
            }
            if (nalStart != -1) {
                var nalEnd = limit
                for (j in (nalStart + startCodeLen) until limit - 3) {
                    if (buffer[j].toInt() == 0 && buffer[j + 1].toInt() == 0 &&
                        (buffer[j + 2].toInt() == 1 || (buffer[j + 2].toInt() == 0 && buffer[j + 3].toInt() == 1))) {
                        nalEnd = j; break
                    }
                }
                val rawNal = buffer.copyOfRange(nalStart, nalEnd)
                val fixedNal = if (startCodeLen == 3) {
                    ByteArray(rawNal.size + 1).apply { this[0] = 0; System.arraycopy(rawNal, 0, this, 1, rawNal.size) }
                } else rawNal
                callback(fixedNal, 4)
                currentPos = nalEnd
            } else break
        }
    }

    private fun scanAndApplyConfig(buffer: ByteArray, offset: Int, size: Int) {
        forEachNalUnit(buffer, offset, size) { nalData, headerLen ->
            val nalFirstByte = nalData[headerLen].toInt()
            val nalType = nalFirstByte and 0x1F
            if (nalType == 7) { // SPS
                sps = nalData
                try {
                    val offsetInNal = if (sps!![2].toInt() == 1) 3 else 4
                    SpsParser.parse(sps!!, offsetInNal, sps!!.size - offsetInNal)?.let {
                        if (mWidth != it.width || mHeight != it.height) {
                            AaLog.i("H.264 SPS parsed: ${it.width}x${it.height}")
                            mWidth = it.width; mHeight = it.height
                            dimensionsListener?.onVideoDimensionsChanged(mWidth, mHeight)
                        }
                    }
                } catch (e: Exception) { AaLog.e("Failed to parse SPS data", e) }
            } else if (nalType == 8) pps = nalData
            if (sps != null) codecConfigured = true
        }
    }

    private fun start(width: Int, height: Int) {
        try {
            startTime = System.nanoTime()
            val mimeType = "video/avc"
            val bestCodec = findBestCodec(mimeType, true)
                ?: throw IllegalStateException("No decoder available for $mimeType")
            this.currentCodecName = bestCodec

            codec = MediaCodec.createByCodecName(bestCodec)
            codecBufferInfo = MediaCodec.BufferInfo()

            val format = MediaFormat.createVideoFormat(mimeType, width, height)
            if (sps != null) format.setByteBuffer("csd-0", ByteBuffer.wrap(sps!!))
            if (pps != null) format.setByteBuffer("csd-1", ByteBuffer.wrap(pps!!))
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)

            if (!mSurface!!.isValid) throw IllegalStateException("Surface not valid")

            AaLog.i("Configuring decoder: $bestCodec for ${width}x${height}")
            codec?.configure(format, mSurface, null, 0)
            try { codec?.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT) } catch (e: Exception) {}
            codec?.start()

            running = true
            outputThread = Thread {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
                outputThreadLoop()
            }.apply { name = "AaVideoDecoder-Output"; start() }

            AaLog.i("Codec initialized: $bestCodec")
        } catch (e: Exception) {
            AaLog.e("Failed to start decoder", e)
            codec = null; running = false
        }
    }

    private fun shouldAlwaysFlagConfig(): Boolean {
        val name = currentCodecName?.lowercase(Locale.ROOT) ?: return false
        return name.contains(".rk.") || name.contains("allwinner") || name.contains(".tcc.")
    }

    private fun isCodecConfigData(data: ByteArray, offset: Int, size: Int): Boolean {
        if (size < 5) return false
        for (i in offset until (offset + size - 4).coerceAtMost(offset + 32)) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                val headerPos: Int
                if (data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) headerPos = i + 4
                else if (data[i + 2].toInt() == 1) headerPos = i + 3
                else continue
                if (headerPos >= offset + size) return false
                val b = data[headerPos].toInt()
                val nalType = b and 0x1F
                return nalType == 7 || nalType == 8
            }
        }
        return false
    }

    private fun feedInputBuffer(buffer: ByteBuffer): Boolean {
        val currentCodec = codec ?: return false
        try {
            var inputIndex = -1
            var attempts = 0
            while (attempts < 30) {
                inputIndex = currentCodec.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) break
                attempts++
            }
            if (inputIndex < 0) {
                AaLog.e("Input buffer feed failed (full)")
                return false
            }

            val inputBuffer = currentCodec.getInputBuffer(inputIndex) ?: return false
            inputBuffer.clear()
            val capacity = inputBuffer.capacity()

            val isConfig = buffer.hasArray() && isCodecConfigData(buffer.array(), buffer.position(), buffer.remaining())
            val flags = if (isConfig && (shouldAlwaysFlagConfig() || !codecConfigured)) {
                MediaCodec.BUFFER_FLAG_CODEC_CONFIG
            } else 0

            if (buffer.remaining() <= capacity) {
                inputBuffer.put(buffer)
            } else {
                AaLog.w("Frame too large: ${buffer.remaining()} > $capacity. Truncating!")
                val limit = buffer.limit()
                buffer.limit(buffer.position() + capacity)
                inputBuffer.put(buffer)
                buffer.limit(limit)
            }

            inputBuffer.flip()
            val pts = (System.nanoTime() - startTime) / 1000
            currentCodec.queueInputBuffer(inputIndex, 0, inputBuffer.limit(), pts, flags)
            lastInputMs = SystemClock.elapsedRealtime()
            return true
        } catch (e: Exception) {
            AaLog.e("Error feeding input buffer", e)
            return false
        }
    }

    private fun outputThreadLoop() {
        AaLog.i("Output thread started")
        var consecutiveErrors = 0
        var lastOutputMs = 0L

        while (running) {
            val currentCodec = codec
            val bufferInfo = codecBufferInfo
            if (currentCodec == null || bufferInfo == null) {
                try { Thread.sleep(10) } catch (e: InterruptedException) { break }
                continue
            }
            try {
                val outputIndex = currentCodec.dequeueOutputBuffer(bufferInfo, 10000L)
                if (outputIndex >= 0) {
                    currentCodec.releaseOutputBuffer(outputIndex, true) // render=true → to Surface
                    lastFrameRenderedMs = SystemClock.elapsedRealtime()
                    lastOutputMs = lastFrameRenderedMs
                    consecutiveErrors = 0
                    onFirstFrameListener?.let { it(); onFirstFrameListener = null }

                    frameCount++
                    val now = System.currentTimeMillis()
                    val elapsed = now - lastFpsLogTime
                    if (elapsed >= 1000) {
                        if (lastFpsLogTime != 0L) onFpsChanged?.invoke((frameCount * 1000 / elapsed).toInt())
                        frameCount = 0
                        lastFpsLogTime = now
                    }
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    handleOutputFormatChange(currentCodec.outputFormat)
                }

                if (lastOutputMs > 0) {
                    val now = SystemClock.elapsedRealtime()
                    val stallGap = now - lastOutputMs
                    val inputGap = now - lastInputMs
                    // Real stall = we're actively feeding input but getting no output → restart.
                    // If no input is arriving, Android Auto has just paused video (UI transition, call,
                    // decoder recovery) — stay idle and let it resume; the compositor keep-alive holds
                    // the bike connection meanwhile. This avoids tearing down a healthy decoder and
                    // fighting AA's own Media Stop/Start sequence.
                    if (stallGap > 3000L && inputGap < 1000L) {
                        AaLog.w("Decoder stall detected (no output for ${stallGap}ms, input ${inputGap}ms ago). Forcing restart.")
                        scheduleRestart("sync_stall")
                        break
                    }
                }
            } catch (e: Exception) {
                if (running) {
                    consecutiveErrors++
                    AaLog.w("Codec exception in output thread (attempt $consecutiveErrors): ${e.message}")
                    if (consecutiveErrors >= 3) {
                        AaLog.e("Too many consecutive exceptions in output thread. Forcing restart.")
                        scheduleRestart("sync_consecutive_errors")
                        break
                    }
                    try { Thread.sleep(50) } catch (ignore: Exception) {}
                }
            }
        }
        AaLog.i("Output thread stopped")
    }

    private fun findBestCodec(mimeType: String, preferHardware: Boolean): String? {
        val codecInfos = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.toList()
        val infos = codecInfos.filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mimeType, true) } }
        val hw = infos.find { isHardwareAccelerated(it) }
        val sw = infos.find { !isHardwareAccelerated(it) }
        val selected = if (preferHardware && hw != null) hw.name else sw?.name ?: hw?.name
        AaLog.i("findBestCodec: hw=${hw?.name}, sw=${sw?.name}, preferHardware=$preferHardware, selected=$selected")
        return selected
    }

    private fun isHardwareAccelerated(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return info.isHardwareAccelerated
        val lower = info.name.lowercase(Locale.ROOT)
        return !(lower.startsWith("omx.google.") || lower.startsWith("c2.android.") ||
                lower.startsWith("omx.ffmpeg.") || lower.contains(".sw.") || lower.contains("software"))
    }
}

private class BitReader(private val buffer: ByteArray, private val offset: Int, private val size: Int) {
    private var bitPosition = offset * 8
    private val bitLimit = (offset + size) * 8

    fun readBit(): Int {
        if (bitPosition >= bitLimit) return 0
        return (buffer[bitPosition / 8].toInt() shr (7 - (bitPosition++ % 8))) and 1
    }

    fun readBits(count: Int): Int {
        var res = 0
        repeat(count) { res = (res shl 1) or readBit() }
        return res
    }

    fun readUE(): Int {
        var zeros = 0
        while (readBit() == 0 && bitPosition < bitLimit) zeros++
        return if (zeros == 0) 0 else (1 shl zeros) - 1 + readBits(zeros)
    }
}

data class SpsData(val width: Int, val height: Int)

private object SpsParser {
    fun parse(sps: ByteArray, offset: Int, size: Int): SpsData? {
        try {
            val reader = BitReader(sps, offset, size)
            reader.readBits(8)
            val profileIdc = reader.readBits(8)
            reader.readBits(16)
            reader.readUE()
            if (profileIdc in listOf(100, 110, 122, 244, 44, 83, 86, 118, 128)) {
                val chroma = reader.readUE()
                if (chroma == 3) reader.readBit()
                reader.readUE(); reader.readUE(); reader.readBit()
                if (reader.readBit() == 1) {
                    repeat(if (chroma != 3) 8 else 12) {
                        if (reader.readBit() == 1) {
                            var last = 8; var next = 8
                            repeat(if (it < 6) 16 else 64) {
                                if (next != 0) next = (last + reader.readUE() + 256) % 256
                                if (next != 0) last = next
                            }
                        }
                    }
                }
            }
            reader.readUE()
            if (reader.readUE() == 0) reader.readUE()
            reader.readUE(); reader.readBit()
            val w = (reader.readUE() + 1) * 16
            val hMap = reader.readUE()
            val mbs = reader.readBit()
            val h = (2 - mbs) * (hMap + 1) * 16
            if (mbs == 0) reader.readBit()
            reader.readBit()
            if (reader.readBit() == 1) {
                val l = reader.readUE(); val r = reader.readUE()
                val t = reader.readUE(); val b = reader.readUE()
                return SpsData(w - (l + r) * 2, h - (t + b) * 2)
            }
            return SpsData(w, h)
        } catch (e: Exception) { return null }
    }
}
