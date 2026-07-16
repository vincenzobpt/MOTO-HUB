package io.motohub.android.encoding

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
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
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
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

    fun requestSyncFrame(reason: String) {
        val activeCodec = codec ?: return
        runCatching {
            activeCodec.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
            ProjectionEventLog.record("ENCODER", "Requested AVC sync frame: $reason.")
        }.onFailure {
            ProjectionEventLog.warning("ENCODER", "AVC sync-frame request failed: $reason.", it)
        }
    }

    private fun drainLoop() {
        val bufferInfo = MediaCodec.BufferInfo()
        val accessUnitAssembler = AvcAccessUnitAssembler()
        try {
            while (running.get()) {
                val activeCodec = codec ?: break
                when (val index = activeCodec.dequeueOutputBuffer(bufferInfo, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = activeCodec.outputFormat
                        accessUnitAssembler.updateCodecConfig(outputFormat.codecConfigBuffers())
                        ProjectionEventLog.record("ENCODER", "AVC output format changed: $outputFormat.")
                    }
                    else -> if (index >= 0) {
                        try {
                            if (bufferInfo.size > 0) {
                                val sample = activeCodec.getOutputBuffer(index)?.copyRange(
                                    bufferInfo.offset,
                                    bufferInfo.size
                                )
                                if (sample != null) {
                                    val isCodecConfig = bufferInfo.flags and
                                        MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                                    val isKeyFrame = bufferInfo.flags and
                                        MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                                    val accessUnit = accessUnitAssembler.consume(
                                        sample = sample,
                                        isCodecConfig = isCodecConfig,
                                        isKeyFrame = isKeyFrame
                                    )
                                    if (isCodecConfig) {
                                        ProjectionEventLog.debug(
                                            "ENCODER",
                                            "Cached AVC codec configuration (${sample.size} bytes)."
                                        )
                                    }
                                    if (accessUnit != null) {
                                        if (accessUnitAssembler.prependedCodecConfig) {
                                            ProjectionEventLog.record(
                                                "ENCODER",
                                                "Prepended cached SPS/PPS to AVC keyframe."
                                            )
                                        }
                                        onAccessUnit(accessUnit)
                                    }
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

    private fun MediaFormat.codecConfigBuffers(): List<ByteArray> = listOf("csd-0", "csd-1")
        .mapNotNull { key ->
            runCatching { getByteBuffer(key)?.copyRemaining() }.getOrNull()
        }

    private fun ByteBuffer.copyRemaining(): ByteArray {
        val duplicate = duplicate()
        return ByteArray(duplicate.remaining()).also(duplicate::get)
    }

}

internal class AvcAccessUnitAssembler {
    private val parameterSets = linkedMapOf<Int, ByteArray>()

    var prependedCodecConfig: Boolean = false
        private set

    fun updateCodecConfig(samples: Iterable<ByteArray>) {
        samples.forEach(::updateCodecConfig)
    }

    fun updateCodecConfig(sample: ByteArray) {
        parseAvcNals(sample).orEmpty().forEach { nal ->
            val type = nal.firstOrNull()?.toInt()?.and(0x1F) ?: return@forEach
            if (type == AVC_NAL_SPS || type == AVC_NAL_PPS) {
                parameterSets[type] = nal.copyOf()
            }
        }
    }

    fun consume(sample: ByteArray, isCodecConfig: Boolean, isKeyFrame: Boolean): ByteArray? {
        prependedCodecConfig = false
        val sampleNals = parseAvcNals(sample)
        val containsIdr = sampleNals?.any { it.avcNalType() == AVC_NAL_IDR } == true

        if (isCodecConfig) {
            updateCodecConfig(sample)
            if (!isKeyFrame && !containsIdr) return null
        }
        if (!isKeyFrame && !containsIdr) return sample
        if (sampleNals == null) return sample

        val existingTypes = sampleNals.mapTo(mutableSetOf(), ByteArray::avcNalType)
        val missingParameterSets = listOf(AVC_NAL_SPS, AVC_NAL_PPS)
            .filterNot(existingTypes::contains)
            .mapNotNull(parameterSets::get)
        if (missingParameterSets.isEmpty()) return sample
        if ((existingTypes + missingParameterSets.map(ByteArray::avcNalType)).let {
                AVC_NAL_SPS !in it || AVC_NAL_PPS !in it
            }
        ) {
            return sample
        }

        prependedCodecConfig = true
        return buildAvccAccessUnit(missingParameterSets + sampleNals)
    }
}

internal fun parseAvcNals(sample: ByteArray): List<ByteArray>? =
    parseAnnexBNals(sample) ?: parseAvccNals(sample) ?: parseAvcDecoderConfiguration(sample)

internal fun buildAvccAccessUnit(nals: List<ByteArray>): ByteArray {
    val output = ByteBuffer.allocate(nals.sumOf { Int.SIZE_BYTES + it.size })
    nals.forEach { nal ->
        output.putInt(nal.size)
        output.put(nal)
    }
    return output.array()
}

private fun parseAnnexBNals(sample: ByteArray): List<ByteArray>? {
    val starts = mutableListOf<Pair<Int, Int>>()
    var index = 0
    while (index + 3 <= sample.size) {
        val length = when {
            index + 4 <= sample.size && sample[index] == 0.toByte() &&
                sample[index + 1] == 0.toByte() && sample[index + 2] == 0.toByte() &&
                sample[index + 3] == 1.toByte() -> 4
            sample[index] == 0.toByte() && sample[index + 1] == 0.toByte() &&
                sample[index + 2] == 1.toByte() -> 3
            else -> 0
        }
        if (length > 0) {
            starts += index to length
            index += length
        } else {
            index++
        }
    }
    if (starts.isEmpty()) return null
    if (sample.copyOfRange(0, starts.first().first).any { it != 0.toByte() }) return null
    return starts.mapIndexedNotNull { position, (start, startCodeLength) ->
        val nalStart = start + startCodeLength
        val nalEnd = starts.getOrNull(position + 1)?.first ?: sample.size
        if (nalStart < nalEnd) sample.copyOfRange(nalStart, nalEnd) else null
    }.takeIf(List<ByteArray>::isNotEmpty)
}

private fun parseAvccNals(sample: ByteArray): List<ByteArray>? {
    val nals = mutableListOf<ByteArray>()
    var offset = 0
    while (offset + Int.SIZE_BYTES <= sample.size) {
        val nalSize = ByteBuffer.wrap(sample, offset, Int.SIZE_BYTES).int
        offset += Int.SIZE_BYTES
        if (nalSize <= 0 || offset + nalSize > sample.size) return null
        nals += sample.copyOfRange(offset, offset + nalSize)
        offset += nalSize
    }
    return nals.takeIf { offset == sample.size && it.isNotEmpty() }
}

private fun parseAvcDecoderConfiguration(sample: ByteArray): List<ByteArray>? {
    if (sample.size < 7 || sample[0] != 1.toByte()) return null
    var offset = 6
    val nals = mutableListOf<ByteArray>()
    val spsCount = sample[5].toInt() and 0x1F
    repeat(spsCount) {
        val parsed = sample.readAvcConfigNal(offset) ?: return null
        nals += parsed.first
        offset = parsed.second
    }
    if (offset >= sample.size) return null
    val ppsCount = sample[offset].toInt() and 0xFF
    offset++
    repeat(ppsCount) {
        val parsed = sample.readAvcConfigNal(offset) ?: return null
        nals += parsed.first
        offset = parsed.second
    }
    return nals.takeIf(List<ByteArray>::isNotEmpty)
}

private fun ByteArray.readAvcConfigNal(offset: Int): Pair<ByteArray, Int>? {
    if (offset + 2 > size) return null
    val nalSize = ((this[offset].toInt() and 0xFF) shl 8) or
        (this[offset + 1].toInt() and 0xFF)
    val nalStart = offset + 2
    val nalEnd = nalStart + nalSize
    if (nalSize <= 0 || nalEnd > size) return null
    return copyOfRange(nalStart, nalEnd) to nalEnd
}

private fun ByteArray.avcNalType(): Int = firstOrNull()?.toInt()?.and(0x1F) ?: -1

private const val AVC_NAL_IDR = 5
private const val AVC_NAL_SPS = 7
private const val AVC_NAL_PPS = 8
