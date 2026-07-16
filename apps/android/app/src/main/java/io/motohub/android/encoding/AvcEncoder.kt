package io.motohub.android.encoding

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import io.motohub.android.session.ProjectionEventLog
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

data class EncoderProfile(
    val width: Int,
    val height: Int,
    val frameRate: Int = 30,
    val bitRate: Int = 2_500_000
) {
    companion object {
        fun forTBoxArea(width: Int, height: Int): EncoderProfile = EncoderProfile(
            width = alignTBoxEncoderDimension(width),
            height = alignTBoxEncoderDimension(height)
        )
    }
}

internal fun alignTBoxEncoderDimension(value: Int): Int =
    (value and 0xFFF0).coerceAtLeast(16)

/** Owns the AVC codec and emits complete AVCC access units from a surface input. */
class AvcEncoder(
    private val profile: EncoderProfile,
    private val onAccessUnit: (ByteArray) -> Unit,
    private val onFailure: (Throwable) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var codec: MediaCodec? = null
    private var drainThread: Thread? = null
    var inputSurface: Surface? = null
        private set

    fun start() {
        check(running.compareAndSet(false, true)) { "Encoder is already running" }
        ProjectionEventLog.record(
            "ENCODER",
            "Configuring AVC encoder ${profile.width}x${profile.height}@${profile.frameRate}, " +
                "bitrate=${profile.bitRate}, I-frame interval=0."
        )
        try {
            val configuredCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec = configuredCodec
            fun encoderFormat(forceBaseline: Boolean) = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                profile.width,
                profile.height
            ).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, profile.bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, profile.frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0)
                setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
                if (forceBaseline) {
                    setInteger(
                        MediaFormat.KEY_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                    )
                    setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
                }
            }
            try {
                configuredCodec.configure(
                    encoderFormat(forceBaseline = true),
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE
                )
                ProjectionEventLog.record("ENCODER", "Configured H.264 Baseline profile at level 3.1.")
            } catch (baselineFailure: Throwable) {
                ProjectionEventLog.warning(
                    "ENCODER",
                    "H.264 Baseline profile is unavailable; retrying the default codec profile.",
                    baselineFailure
                )
                configuredCodec.reset()
                configuredCodec.configure(
                    encoderFormat(forceBaseline = false),
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE
                )
            }
            inputSurface = configuredCodec.createInputSurface()
            configuredCodec.start()
            ProjectionEventLog.record("ENCODER", "AVC codec ${configuredCodec.name} started with surface input.")
            drainThread = Thread(::drainLoop, "MotoHubAvcDrain").also { it.start() }
        } catch (failure: Throwable) {
            ProjectionEventLog.error("ENCODER", "AVC encoder startup failed.", failure)
            running.set(false)
            releaseCodec()
            onFailure(failure)
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        ProjectionEventLog.record("ENCODER", "Stopping AVC encoder.")
        val activeDrainThread = drainThread
        if (activeDrainThread != null && activeDrainThread !== Thread.currentThread()) {
            activeDrainThread.join(1_500)
        }
        drainThread = null
        releaseCodec()
    }

    private fun drainLoop() {
        val bufferInfo = MediaCodec.BufferInfo()
        try {
            while (running.get()) {
                val activeCodec = codec ?: break
                when (val index = activeCodec.dequeueOutputBuffer(bufferInfo, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> ProjectionEventLog.record(
                        "ENCODER",
                        "AVC output format changed: ${activeCodec.outputFormat}."
                    )
                    else -> if (index >= 0) {
                        try {
                            if (bufferInfo.size > 0) {
                                val sample = activeCodec.getOutputBuffer(index)?.copyRange(
                                    bufferInfo.offset,
                                    bufferInfo.size
                                )
                                if (sample != null) {
                                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                        continue
                                    }
                                    // Match the reference app: the Go binding expects one native
                                    // MediaCodec AVCC access unit and performs Annex-B conversion.
                                    onAccessUnit(sample)
                                }
                            }
                        } finally {
                            activeCodec.releaseOutputBuffer(index, false)
                        }
                    }
                }
            }
        } catch (failure: Throwable) {
            if (running.get()) {
                ProjectionEventLog.error("ENCODER", "AVC drain loop failed.", failure)
                onFailure(failure)
            }
        } finally {
            ProjectionEventLog.debug("ENCODER", "AVC drain loop ended.")
        }
    }

    private fun releaseCodec() {
        inputSurface?.release()
        inputSurface = null
        codec?.runCatching { stop() }
        codec?.release()
        codec = null
    }

    private fun ByteBuffer.copyRange(offset: Int, size: Int): ByteArray {
        val duplicate = duplicate()
        duplicate.position(offset)
        duplicate.limit(offset + size)
        return ByteArray(size).also(duplicate::get)
    }

}
