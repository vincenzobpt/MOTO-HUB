// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.encoding

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface
import io.motohub.android.session.ProjectionEventLog
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class EncoderProfile(
    val width: Int,
    val height: Int,
    val frameRate: Int = 30,
    val bitRate: Int = 2_500_000,
    /** 0 keeps the legacy all-intra stream (every frame an IDR keyframe), which tolerates
     *  arbitrary output-side frame dropping but spends the bitrate like per-frame JPEG.
     *  A positive value enables a GOP of that many seconds; P-frames then carry most of the
     *  stream, so the input surface must be paced upstream because P-frames cannot be
     *  dropped mid-GOP without corrupting the decode until the next keyframe. */
    val keyframeIntervalSeconds: Int = 0,
    /** Keeps a GOP stream on plain periodic IDRs instead of the intra-refresh this encoder
     *  normally prefers. Only a transport whose own framing depends on seeing real keyframes
     *  should set it: Yunmo splits each keyframe into standalone SPS / PPS / picture frames, and
     *  intra refresh makes full keyframes rare enough that the split path would seldom run. The
     *  dash it targets is also known to receive a plain 2-second GOP from its OEM app. */
    val plainGopWithoutIntraRefresh: Boolean = false
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

/**
 * The stream shape the encoder actually runs, which is not always the one the caller asked for.
 *
 * A GOP survives a lossy link only because intra refresh repairs it: the rolling intra columns heal
 * a dropped frame within one cycle, without a keyframe burst. On a codec that has no intra refresh
 * the GOP is left with no repair path at all - every frame the transport drops smears the TFT until
 * the next scheduled IDR, and the recovery IDRs answering those drops feed the very congestion that
 * caused them. A rider's CFMOTO MTX800 on 2026-08-17 showed exactly that: `c2.mtk.avc.encoder`
 * reported no intra refresh, ~280 of 2342 dashboard frames died in the CORE video pipe, the TFT
 * filled with green macroblocks and both sessions ended on a broken pipe - while Android Auto, all
 * -intra on the same phone and the same bike, stayed clean throughout.
 *
 * All-intra spends the bitrate like per-frame JPEG but tolerates arbitrary frame loss, so it is the
 * right stream whenever the repair path is missing.
 *
 * [plainGopWithoutIntraRefresh] profiles turned intra refresh down on purpose - Yunmo's framing
 * splits real keyframes, KOVE's decoder froze on refresh - so their GOP is kept exactly as asked.
 */
internal fun effectiveKeyframeIntervalSeconds(
    requestedSeconds: Int,
    plainGopWithoutIntraRefresh: Boolean,
    intraRefreshAvailable: Boolean
): Int = if (
    requestedSeconds > 0 && !plainGopWithoutIntraRefresh && !intraRefreshAvailable
) {
    0
} else {
    requestedSeconds
}

/** Owns the AVC codec and emits complete AVCC access units from a surface input. */
class AvcEncoder(
    private val profile: EncoderProfile,
    private val onAccessUnit: (ByteArray) -> Boolean,
    private val onFailure: (Throwable) -> Unit
) {
    private val running = AtomicBoolean(false)
    @Volatile private var codec: MediaCodec? = null
    private var drainThread: Thread? = null
    /** Set by [stop] when the drain thread didn't terminate within its join timeout, so
     *  [drainLoop] releases the codec itself instead of racing a concurrent release. */
    @Volatile private var selfReleaseOnExit = false
    private val rejectedAccessUnits = AtomicLong(0L)

    /** Frames the transport discarded on its own, reported through [noteTransportFrameDrop]. They
     *  never surface as rejections - the offer still returns accepted - so without this counter the
     *  congestion they signal is invisible to [AdaptiveVideoController]. */
    private val transportDroppedFrames = AtomicLong(0L)

    /** The interval the codec was actually configured with; see [effectiveKeyframeIntervalSeconds].
     *  Everything downstream of configure() reads the real stream shape, not the requested one. */
    @Volatile private var streamKeyframeIntervalSeconds = profile.keyframeIntervalSeconds

    /** Keyframes that carried a prepended SPS/PPS; reported once at [stop], never per frame. */
    private val prependedKeyframes = AtomicLong(0L)
    @Volatile private var frameCap = profile.frameRate
    @Volatile private var frameCapListener: ((Int) -> Unit)? = null
    @Volatile private var lastRecoverySyncNanos = 0L
    @Volatile private var intraRefreshActive = false

    // A run of recovery sync-frame requests, coalesced for the log. Only ever touched on the
    // drain thread. See [recordRecoverySyncRequest].
    private var recoverySyncRunCount = 0
    private var recoverySyncRunStartedNanos = 0L
    private var recoverySyncLastLogNanos = 0L
    private var nextFrameDeadlineNanos = 0L
    var inputSurface: Surface? = null
        private set

    val baseFrameRate: Int get() = profile.frameRate

    fun targetBitrate(): Int = profile.bitRate

    fun rejectedAccessUnitsTotal(): Long = rejectedAccessUnits.get()

    fun transportDroppedFramesTotal(): Long = transportDroppedFrames.get()

    /** A frame the transport discarded after accepting it - the CORE video pipe drops the oldest
     *  queued frame under congestion and still reports the offer as accepted. Counted whatever the
     *  stream shape, because the congestion it signals is what [AdaptiveVideoController] must back
     *  off from; the recovery IDR is only relevant to a GOP that has no other repair path. */
    fun noteTransportFrameDrop(reason: String) {
        transportDroppedFrames.incrementAndGet()
        requestRecoverySyncFrame(reason)
    }

    fun start() {
        check(running.compareAndSet(false, true)) { "Encoder is already running" }
        recoverySyncRunCount = 0
        ProjectionEventLog.record(
            "ENCODER",
            "Configuring AVC encoder ${profile.width}x${profile.height}@${profile.frameRate}, " +
                "bitrate=${profile.bitRate}, I-frame interval=${profile.keyframeIntervalSeconds}s" +
                if (profile.keyframeIntervalSeconds == 0) " (all-intra)." else " (GOP)."
        )
        try {
            val configuredCodec = configureFirstWillingEncoder()
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

    /**
     * Walks the phone's AVC encoders in [avcEncoderOrder] until one accepts a format, and rethrows
     * the last configure() failure when none does.
     *
     * `createEncoderByType` hands out one encoder, and that one used to be the only one ever asked.
     * A rider's Huawei (Kirin, `OMX.hisi.*`) refused both of its combinations with CodecException
     * 0x80001001 on every one of ~15 sessions between 2026-08-31 and 2026-09-04 - mirroring and
     * Android Auto alike, all-intra and 2s GOP alike - so each session died before a single frame,
     * and the log did not even name the codec that said no. The software encoder on the same phone
     * was never tried. Now every encoder gets the whole combination ladder, a bare format closes it,
     * and a total failure spells out what the phone has and what each encoder claims to support.
     */
    private fun configureFirstWillingEncoder(): MediaCodec {
        val defaultName = runCatching {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).let { probe ->
                probe.name.also { probe.release() }
            }
        }.getOrNull()
        val advertised = listAvcEncoders()
        val order = avcEncoderOrder(defaultName, advertised)
        ProjectionEventLog.record(
            "ENCODER",
            "AVC encoders on this phone (default first): " +
                order.joinToString { name ->
                    name + (advertised.firstOrNull { it.name == name }?.let {
                        if (it.hardware) " (hw)" else " (sw)"
                    } ?: "")
                }.ifEmpty { "none advertised" } + "."
        )
        var lastFailure: Throwable? = null
        for (name in order) {
            val created = try {
                MediaCodec.createByCodecName(name)
            } catch (failure: Throwable) {
                ProjectionEventLog.warning(
                    "ENCODER",
                    "AVC encoder $name could not be created; trying the next encoder.",
                    failure
                )
                lastFailure = failure
                continue
            }
            codec = created
            val failure = configureOnCodec(created) ?: return created
            lastFailure = failure
            ProjectionEventLog.warning(
                "ENCODER",
                "AVC encoder $name rejected every configuration; trying the next encoder.",
                failure
            )
            codec = null
            runCatching { created.release() }
        }
        ProjectionEventLog.error(
            "ENCODER",
            "No AVC encoder on this phone accepted ${profile.width}x${profile.height}@" +
                "${profile.frameRate} at ${profile.bitRate}bps. " + describeAvcEncoders()
        )
        throw lastFailure ?: IllegalStateException("This phone advertises no AVC encoder.")
    }

    /**
     * Offers [configuredCodec] every combination in [avcConfigureAttempts]; returns null once one
     * is accepted - with the stream shape recorded - or the last rejection when none is.
     */
    private fun configureOnCodec(configuredCodec: MediaCodec): Throwable? {
        val capabilities = runCatching {
            configuredCodec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        }.getOrNull()
        // Periodic IDRs are the remaining burst on a GOP stream, and answering congestion
        // drops with recovery IDRs feeds the very congestion that caused them. Intra
        // refresh removes the burst entirely: intra macroblocks are spread over a full
        // refresh cycle, every frame stays route-sized, and a lost frame heals itself
        // progressively within one cycle without any recovery signaling.
        val useIntraRefresh = profile.keyframeIntervalSeconds > 0 &&
            !profile.plainGopWithoutIntraRefresh &&
            runCatching {
                capabilities?.isFeatureSupported(
                    MediaCodecInfo.CodecCapabilities.FEATURE_IntraRefresh
                )
            }.getOrNull() == true
        // A GOP this codec cannot repair is worse than no GOP at all.
        val streamInterval = effectiveKeyframeIntervalSeconds(
            requestedSeconds = profile.keyframeIntervalSeconds,
            plainGopWithoutIntraRefresh = profile.plainGopWithoutIntraRefresh,
            intraRefreshAvailable = useIntraRefresh
        )
        // GOP streams ride links with shallow queues (the CORE video pipe): VBR spikes on
        // keyframes and fast map motion overflow them and every dropped frame smears the
        // picture until the next IDR. CBR bounds the per-frame burst instead.
        val useCbr = streamInterval > 0 && runCatching {
            capabilities?.encoderCapabilities
                ?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        }.getOrNull() == true
        if (profile.keyframeIntervalSeconds > 0) {
            if (streamInterval == 0) {
                ProjectionEventLog.record(
                    "ENCODER",
                    "${configuredCodec.name} has no intra refresh, so nothing would repair the " +
                        "${profile.keyframeIntervalSeconds}s GOP between keyframes when the link " +
                        "drops a frame: encoding an all-intra stream instead, where every frame " +
                        "decodes on its own."
                )
            } else {
                // Report what was actually configured. This line used to say "enabled"
                // unconditionally, which read as a bug in the Voge experiment: that
                // profile disables intra refresh on purpose and the log denied it.
                ProjectionEventLog.record(
                    "ENCODER",
                    "GOP stream rate control on ${configuredCodec.name}: " +
                        (if (useCbr) "CBR" else "codec default (CBR unsupported)") +
                        ", intra refresh: " +
                        if (useIntraRefresh) "enabled." else "disabled (plain periodic IDRs)."
                )
            }
        }
        fun encoderFormat(attempt: AvcConfigureAttempt) = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            profile.width,
            profile.height
        ).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, profile.bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, profile.frameRate)
            if (attempt.intraRefresh) {
                // A full picture refresh every ~1s of frames; scheduled IDRs become a rare
                // safety net because the rolling refresh keeps the stream healthy on its own.
                setInteger(MediaFormat.KEY_INTRA_REFRESH_PERIOD, profile.frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, INTRA_REFRESH_IDR_INTERVAL_SECONDS)
            } else {
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, streamInterval)
            }
            // The bare attempt stops here: size, bitrate, frame rate and keyframe interval are
            // the least any encoder must take, and everything below is a key some vendor codec
            // may refuse outright. The drain loop caches SPS/PPS from the output format and
            // prepends them itself, so losing KEY_PREPEND_HEADER_TO_SYNC_FRAMES costs nothing
            // on the wire; losing the repeat-frame floor only shows on a stalled source.
            if (attempt.bare) return@apply
            if (useCbr) {
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                )
            }
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
            // The floor under a stalled pixel source, not idle pacing. When the source stops
            // - an Android Auto decoder stall, seconds at a time, is the common case - this
            // interval is the only thing still feeding the dash, because the codec re-submits
            // the last surface buffer and nothing else arrives. The compositor cannot cover
            // the gap either: its own idle redraw is slower still.
            //
            // What a repeat costs depends on the stream shape, so the interval follows it:
            //
            //  - GOP: the repeat is a skip-macroblock P-frame, tens of bytes. 100 ms buys a
            //    10 fps floor for nothing, and it is the value ThinkerRide, green_trip and
            //    cn_thinkerride all ship. At 900 ms a stalled source left barely 1 fps on the
            //    wire and a KOVE 800X reset the video socket after ~8s of it (rider log,
            //    2026-08-20). 10 fps also stays under AdaptiveVideoController.MIN_FRAME_RATE,
            //    so the repeat can never outpace the frame cap.
            //
            //  - all-intra: every frame is a full IDR, and shouldForwardFrame lets them all
            //    through because they are all keyframes. A 100 ms repeat would mean 10 IDRs a
            //    second of an unchanged picture - the rate controller holds the bitrate, so
            //    each IDR just gets a ninth of the bits and the still image gets worse, at
            //    nine times the encoding work. All-intra also tolerates arbitrary frame loss
            //    by design, which is exactly the starvation the short interval exists to
            //    prevent. It keeps the interval it has always had.
            //
            // Keyed on streamInterval, not profile.keyframeIntervalSeconds: a requested GOP
            // degrades to all-intra on a codec without intra refresh, and it is the shape the
            // stream really has that decides what a repeated frame costs.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, repeatFrameAfterUs(streamInterval))
            if (attempt.forceBaseline) {
                setInteger(
                    MediaFormat.KEY_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                )
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
            }
        }
        var lastFailure: Throwable? = null
        for (attempt in avcConfigureAttempts(useIntraRefresh)) {
            try {
                configuredCodec.configure(
                    encoderFormat(attempt),
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE
                )
            } catch (failure: Throwable) {
                lastFailure = failure
                ProjectionEventLog.warning(
                    "ENCODER",
                    "${configuredCodec.name} rejected AVC configure (${attempt.describe()}); " +
                        "trying the next combination.",
                    failure
                )
                if (runCatching { configuredCodec.reset() }.isFailure) return failure
                continue
            }
            // Only the stream shape that was actually accepted may drive the drain loop.
            streamKeyframeIntervalSeconds = streamInterval
            intraRefreshActive = attempt.intraRefresh
            ProjectionEventLog.record(
                "ENCODER",
                "Configured H.264 ${attempt.describe()} on ${configuredCodec.name}."
            )
            return null
        }
        return lastFailure ?: IllegalStateException("No AVC configuration was attempted.")
    }

    private fun listAvcEncoders(): List<AvcEncoderCandidate> = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { info ->
                info.isEncoder && info.supportedTypes.any {
                    it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true)
                }
            }
            .map { AvcEncoderCandidate(it.name, hardware = it.isHardwareAccelerated) }
    }.getOrDefault(emptyList())

    /**
     * One line per advertised AVC encoder with what it claims for this profile's size, so a phone
     * that refuses everything explains itself in the diagnostics report instead of in a guess.
     */
    private fun describeAvcEncoders(): String {
        val encoders = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
                info.isEncoder && info.supportedTypes.any {
                    it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true)
                }
            }
        }.getOrDefault(emptyList())
        if (encoders.isEmpty()) return "The phone advertises no AVC encoder at all."
        return "Advertised: " + encoders.joinToString("; ") { info ->
            val caps = runCatching { info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC) }
                .getOrNull()
            val video = caps?.videoCapabilities
            val encoder = caps?.encoderCapabilities
            buildString {
                append(info.name)
                append(if (info.isHardwareAccelerated) " (hw" else " (sw")
                if (video != null) {
                    append(", widths=").append(video.supportedWidths)
                    append(", heights=").append(video.supportedHeights)
                    append(", size ok=").append(
                        runCatching { video.isSizeSupported(profile.width, profile.height) }
                            .getOrDefault(false)
                    )
                    append(", fps@size=").append(
                        runCatching {
                            video.getSupportedFrameRatesFor(profile.width, profile.height)
                        }.getOrNull() ?: "n/a"
                    )
                    append(", bitrate=").append(video.bitrateRange)
                }
                if (encoder != null) {
                    append(", CBR=").append(
                        runCatching {
                            encoder.isBitrateModeSupported(
                                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                            )
                        }.getOrDefault(false)
                    )
                }
                if (caps != null) {
                    append(", intraRefresh=").append(
                        runCatching {
                            caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_IntraRefresh)
                        }.getOrDefault(false)
                    )
                    append(", surface=").append(
                        caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    )
                }
                append(")")
            }
        } + "."
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        ProjectionEventLog.record(
            "ENCODER",
            "Stopping AVC encoder. prependedKeyframes=${prependedKeyframes.get()}, " +
                "rejectedAccessUnits=${rejectedAccessUnits.get()}, " +
                "transportDroppedFrames=${transportDroppedFrames.get()}."
        )
        val activeDrainThread = drainThread
        drainThread = null
        if (activeDrainThread != null && activeDrainThread !== Thread.currentThread()) {
            activeDrainThread.join(1_500)
            if (activeDrainThread.isAlive) {
                // The drain thread is still inside a blocking call (most likely
                // onAccessUnit()/offerAccessUnit() congested on the T-Box link) and may
                // still be calling releaseOutputBuffer() on this codec. Do not race a
                // concurrent MediaCodec.release() against it - let drainLoop()'s own
                // finally block release the codec once it unblocks and observes
                // running=false, instead of releasing it here.
                selfReleaseOnExit = true
                ProjectionEventLog.warning(
                    "ENCODER",
                    "AVC drain thread did not stop within 1500ms; deferring codec release to it."
                )
                return
            }
        }
        releaseCodec()
    }

    fun requestSyncFrame(reason: String) {
        val activeCodec = codec ?: return
        applySyncFrameRequest(activeCodec)
            .onSuccess {
                ProjectionEventLog.record("ENCODER", "Requested AVC sync frame: $reason.")
            }
            .onFailure {
                ProjectionEventLog.warning("ENCODER", "AVC sync-frame request failed: $reason.", it)
            }
    }

    private fun applySyncFrameRequest(activeCodec: MediaCodec): Result<Unit> = runCatching {
        activeCodec.setParameters(Bundle().apply {
            putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
        })
    }

    /** Adjust the running H.264 bitrate without recreating the codec. */
    fun setEncoderBitrate(bitRate: Int) {
        val activeCodec = codec ?: return
        val clamped = bitRate.coerceIn(1, profile.bitRate)
        runCatching {
            activeCodec.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, clamped)
            })
            ProjectionEventLog.record("ENCODER", "AVC bitrate adjusted to ${clamped / 1000}kbps.")
        }.onFailure {
            ProjectionEventLog.warning("ENCODER", "AVC bitrate adjustment failed.", it)
        }
    }

    /** Cap forwarded access units; the encoder remains surface-driven and the cap is live. */
    fun setFrameCap(frameRate: Int) {
        frameCap = frameRate.coerceIn(1, profile.frameRate)
        nextFrameDeadlineNanos = 0L
        frameCapListener?.invoke(frameCap)
    }

    fun setFrameCapListener(listener: (Int) -> Unit) {
        frameCapListener = listener
        listener(frameCap)
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
                                    if (accessUnit != null && shouldForwardFrame(isKeyFrame)) {
                                        if (accessUnitAssembler.prependedCodecConfig) {
                                            // Once per session, then counted. On an all-intra
                                            // stream every frame is a keyframe, so this fired
                                            // 30 times a second and wiped the 800-entry
                                            // diagnostic ring every 42s: a rider chasing
                                            // Ride Dashboard dropouts sent two logs on
                                            // 2026-07-31 where 1307 of 1600 entries were this
                                            // one line, and neither could contain the failure
                                            // he was reporting. That it happens at all is
                                            // worth one line; that it happened 667 times is
                                            // worth a number at the end.
                                            if (prependedKeyframes.getAndIncrement() == 0L) {
                                                ProjectionEventLog.record(
                                                    "ENCODER",
                                                    "Prepending the cached SPS/PPS to AVC keyframes; " +
                                                        "the total is reported when the encoder stops."
                                                )
                                            }
                                        }
                                        if (!onAccessUnit(accessUnit)) {
                                            rejectedAccessUnits.incrementAndGet()
                                            requestRecoverySyncFrame(
                                                "access unit rejected on a GOP stream"
                                            )
                                        } else {
                                            noteAccessUnitAccepted()
                                        }
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
            if (selfReleaseOnExit) {
                selfReleaseOnExit = false
                releaseCodec()
            }
        }
    }

    /** On a GOP stream a lost access unit (sink rejection, or a transport-side drop reported
     *  via its drop listener) leaves the T-Box decoder without reference history, so request
     *  an IDR to restore the picture — throttled so a congested link is not fed even more
     *  keyframe bits. All-intra streams need no recovery: every forwarded frame already
     *  decodes on its own. */
    fun requestRecoverySyncFrame(reason: String) {
        if (streamKeyframeIntervalSeconds <= 0) return
        // With intra refresh the rolling intra columns repair a lost frame within one refresh
        // cycle on their own; injecting IDRs here would reintroduce the very keyframe bursts
        // that congest the link and drop frames in the first place.
        if (intraRefreshActive) return
        val now = System.nanoTime()
        if (now - lastRecoverySyncNanos < RECOVERY_SYNC_THROTTLE_NANOS) return
        lastRecoverySyncNanos = now
        val activeCodec = codec ?: return
        val applied = applySyncFrameRequest(activeCodec)
        recordRecoverySyncRequest(now, reason, applied.exceptionOrNull())
    }

    /**
     * Keeps a run of recovery requests to three log lines - opened, still going, over - instead of
     * one every 500ms.
     *
     * A transport that stops accepting frames entirely rejects every access unit until it is
     * repaired, so this used to emit two lines a second for as long as the outage lasted. In a
     * rider's diagnostics of a broken session, 187 of the 800 retained entries were this one
     * sentence, and they had evicted the very events that would have explained how the session
     * broke in the first place.
     */
    private fun recordRecoverySyncRequest(nowNanos: Long, reason: String, failure: Throwable?) {
        recoverySyncRunCount++
        if (recoverySyncRunCount == 1) {
            recoverySyncRunStartedNanos = nowNanos
            recoverySyncLastLogNanos = nowNanos
            if (failure != null) {
                ProjectionEventLog.warning(
                    "ENCODER",
                    "AVC sync-frame request failed: $reason.",
                    failure
                )
            } else {
                ProjectionEventLog.record("ENCODER", "Requested AVC sync frame: $reason.")
            }
            return
        }
        if (nowNanos - recoverySyncLastLogNanos < RECOVERY_SYNC_LOG_INTERVAL_NANOS) return
        recoverySyncLastLogNanos = nowNanos
        ProjectionEventLog.record(
            "ENCODER",
            "Still requesting AVC sync frames ($reason): $recoverySyncRunCount request(s) over " +
                "${(nowNanos - recoverySyncRunStartedNanos).nanosAsSeconds()}s."
        )
    }

    /** Closes an open run of recovery requests once the transport takes a frame again. */
    private fun noteAccessUnitAccepted() {
        if (recoverySyncRunCount == 0) return
        val requests = recoverySyncRunCount
        val elapsed = (System.nanoTime() - recoverySyncRunStartedNanos).nanosAsSeconds()
        recoverySyncRunCount = 0
        ProjectionEventLog.record(
            "ENCODER",
            "AVC access units accepted again after $requests sync-frame request(s) over ${elapsed}s."
        )
    }

    private fun Long.nanosAsSeconds(): Long = this / 1_000_000_000L

    private fun releaseCodec() {
        inputSurface?.release()
        inputSurface = null
        codec?.runCatching { stop() }
        codec?.release()
        codec = null
    }

    private fun shouldForwardFrame(isKeyFrame: Boolean): Boolean {
        // GOP streams must forward every frame: dropping a P-frame corrupts the decode until
        // the next keyframe. Their frame pacing happens at the input surface instead, via the
        // frame-cap listener (dashboard renderer / AA compositor).
        if (streamKeyframeIntervalSeconds > 0) return true
        // Never pace out a sync frame: after a reconnect the T-Box decoder needs the next keyframe
        // immediately or the resumed stream can remain black until the next codec refresh.
        if (isKeyFrame) {
            nextFrameDeadlineNanos = System.nanoTime() +
                1_000_000_000L / frameCap.coerceIn(1, profile.frameRate)
            return true
        }
        val cap = frameCap.coerceIn(1, profile.frameRate)
        if (cap >= profile.frameRate) return true
        val now = System.nanoTime()
        if (now < nextFrameDeadlineNanos) return false
        nextFrameDeadlineNanos = now + 1_000_000_000L / cap
        return true
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

/** Minimum spacing between congestion-recovery IDR requests. Half the dashboard GOP: quick
 *  enough that smearing clears visibly faster than the scheduled keyframe, spaced enough that
 *  sustained congestion doesn't devolve into an all-keyframe stream. */
private const val RECOVERY_SYNC_THROTTLE_NANOS = 500_000_000L

/** How often an ongoing run of recovery sync-frame requests may repeat itself in the log. */
private const val RECOVERY_SYNC_LOG_INTERVAL_NANOS = 15_000_000_000L

/** Scheduled-IDR spacing when intra refresh carries the stream. The rolling refresh keeps the
 *  picture healthy, so full keyframes are only a rare resync safety net. Shortening this to 4s
 *  was tried on the bike (2026-07-28) and made the picture worse, so periodic IDR bursts are
 *  still what this link cannot absorb — keep them rare. */
private const val INTRA_REFRESH_IDR_INTERVAL_SECONDS = 10

/** Repeat-frame interval for a GOP stream: a 10 fps floor built out of skip-macroblock
 *  P-frames, which is what the ThinkerRide, green_trip and cn_thinkerride encoders all use. */
internal const val GOP_REPEAT_FRAME_AFTER_US = 100_000L

/** Repeat-frame interval for an all-intra stream, where every repeat is a full IDR and the
 *  cheap 10 fps floor does not exist. Unchanged from before the GOP split. */
internal const val ALL_INTRA_REPEAT_FRAME_AFTER_US = 900_000L

/**
 * How long the codec waits before re-sending the last surface buffer, chosen from the shape the
 * stream actually has. Takes the *effective* interval, not the requested one: a GOP degrades to
 * all-intra on a codec without intra refresh, and the cost of a repeated frame follows the real
 * shape. See the call site for why the two values differ.
 */
internal fun repeatFrameAfterUs(effectiveKeyframeIntervalSeconds: Int): Long =
    if (effectiveKeyframeIntervalSeconds > 0) GOP_REPEAT_FRAME_AFTER_US
    else ALL_INTRA_REPEAT_FRAME_AFTER_US

private const val AVC_NAL_IDR = 5
private const val AVC_NAL_SPS = 7
private const val AVC_NAL_PPS = 8
