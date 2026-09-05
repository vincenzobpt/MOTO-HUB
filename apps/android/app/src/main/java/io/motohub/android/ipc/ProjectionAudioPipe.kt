// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.ipc

import android.os.ParcelFileDescriptor
import io.motohub.android.aa.AaAudioSink
import io.motohub.android.aa.AaAudioStream
import io.motohub.android.session.ProjectionEventLog
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Carries Android Auto's audio to the companion app, the way the video pipe carries frames the
 * other way: one [ParcelFileDescriptor] pipe, framed by [ProjectionAudioFraming].
 *
 * Installed on [io.motohub.android.aa.AaAudioTap] for as long as the companion wants the audio,
 * whether or not a pipe is open: wanting it is what makes the next service discovery claim the
 * streams, and a pipe can come and go across a companion restart without Android Auto noticing.
 * With no pipe open the packets are dropped, and a summary says how many.
 *
 * The transport thread only ever enqueues. A single writer thread drains into the pipe, so a
 * companion slow to read costs dropped packets here, never a stalled AAP session.
 */
internal class ProjectionAudioPipe : AaAudioSink {

    private class Outbound(val stream: Int, val kind: Int, val rate: Int, val channels: Int, val pcm: ByteArray?)

    private val queue = ArrayBlockingQueue<Outbound>(QUEUE_CAPACITY)
    private val lock = Any()
    private var writeEnd: ParcelFileDescriptor? = null
    private var writer: Thread? = null
    private val dropped = AtomicLong()
    private val delivered = AtomicLong()

    /** Opens a fresh pipe, closing any previous one, and returns the end the companion reads. */
    fun open(): ParcelFileDescriptor? = synchronized(lock) {
        closeLocked()
        runCatching {
            val pipe = ParcelFileDescriptor.createPipe()
            writeEnd = pipe[1]
            queue.clear()
            writer = Thread({ pump(pipe[1]) }, "ProjectionAudioPipe").apply {
                isDaemon = true
                start()
            }
            pipe[0]
        }.onFailure {
            ProjectionEventLog.error(TAG, "Unable to open the companion audio pipe.", it)
        }.getOrNull()
    }

    fun close() = synchronized(lock) { closeLocked() }

    private fun closeLocked() {
        val end = writeEnd ?: return
        writeEnd = null
        // Closing the descriptor is what ends the writer: its next write fails and it exits.
        runCatching { end.close() }
        writer = null
        val lost = dropped.getAndSet(0)
        val sent = delivered.getAndSet(0)
        if (sent > 0 || lost > 0) {
            ProjectionEventLog.record(TAG, "Companion audio pipe closed after $sent packets, $lost dropped.")
        }
    }

    // --- AaAudioSink: on the AAP transport thread -------------------------------------------

    override fun onStreamStarted(stream: AaAudioStream, sampleRateHz: Int, channels: Int) {
        offer(Outbound(stream.code(), ProjectionAudioFraming.KIND_STARTED, sampleRateHz, channels, null))
    }

    override fun onPcm(stream: AaAudioStream, pcm: ByteArray, offset: Int, length: Int) {
        if (writeEnd == null) {
            dropped.incrementAndGet()
            return
        }
        offer(Outbound(stream.code(), ProjectionAudioFraming.KIND_PCM, 0, 0, pcm.copyOfRange(offset, offset + length)))
    }

    override fun onStreamStopped(stream: AaAudioStream) {
        offer(Outbound(stream.code(), ProjectionAudioFraming.KIND_STOPPED, 0, 0, null))
    }

    private fun offer(item: Outbound) {
        if (!queue.offer(item)) {
            // Newest audio is the only audio anyone wants; the backlog is what gets lost.
            queue.poll()
            dropped.incrementAndGet()
            queue.offer(item)
        }
    }

    private fun pump(end: ParcelFileDescriptor) {
        try {
            DataOutputStream(BufferedOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(end), PIPE_BUFFER_BYTES)).use { out ->
                while (true) {
                    val item = queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS)
                    if (item == null) {
                        if (writeEnd !== end) return
                        continue
                    }
                    when (item.kind) {
                        ProjectionAudioFraming.KIND_STARTED ->
                            ProjectionAudioFraming.writeStarted(out, item.stream, item.rate, item.channels)
                        ProjectionAudioFraming.KIND_STOPPED ->
                            ProjectionAudioFraming.writeStopped(out, item.stream)
                        else -> {
                            val pcm = item.pcm ?: continue
                            ProjectionAudioFraming.writePcm(out, item.stream, pcm, 0, pcm.size)
                            delivered.incrementAndGet()
                        }
                    }
                    // Per frame, not per buffer: a 20 ms packet held back for buffering is 20 ms
                    // of latency the rider hears.
                    out.flush()
                }
            }
        } catch (_: InterruptedException) {
            // Closed from our side.
        } catch (failure: Exception) {
            // The companion closed its end, or died. Nothing to do but stop writing; it reopens
            // when it comes back.
            ProjectionEventLog.record(TAG, "Companion audio pipe ended: ${failure.javaClass.simpleName} ${failure.message}")
            synchronized(lock) { if (writeEnd === end) writeEnd = null }
        }
    }

    private fun AaAudioStream.code(): Int = when (this) {
        AaAudioStream.MEDIA -> ProjectionAudioFraming.STREAM_MEDIA
        AaAudioStream.GUIDANCE -> ProjectionAudioFraming.STREAM_GUIDANCE
        AaAudioStream.SYSTEM -> ProjectionAudioFraming.STREAM_SYSTEM
    }

    private companion object {
        const val TAG = "IPC_AA"
        const val QUEUE_CAPACITY = 64
        const val POLL_MILLIS = 100L
        const val PIPE_BUFFER_BYTES = 64 * 1024
    }
}
