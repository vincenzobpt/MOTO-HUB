// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.ipc

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Framing for the audio pipe opened by `IAndroidAutoReceiverService.openProjectionAudioStream()`.
 *
 * Core's Android Auto receiver decodes nothing: the phone hands the head unit plain PCM, one
 * packet per media message, on a channel per kind of sound. The pipe carries exactly that, plus
 * the two moments a player needs and a packet cannot express - a stream starting in a given
 * format, and a stream ending - so the companion never has to guess a sample rate:
 *
 * ```
 *     byte   stream        // one of the STREAM_ constants
 *     byte   kind          // one of the KIND_ constants
 *     int    sampleRateHz  // KIND_STARTED only; 0 otherwise
 *     byte   channels      // KIND_STARTED only; 0 otherwise
 *     int    size          // KIND_PCM only; 0 otherwise
 *     byte   payload[size] // 16-bit little-endian PCM, interleaved
 * ```
 *
 * Fixed-size header on purpose: a reader can always resynchronise on a frame boundary, and a
 * writer built later can add kinds without changing what an older reader skips.
 */
object ProjectionAudioFraming {

    /** Music, podcasts - whatever the projected player is playing. 48 kHz stereo as advertised. */
    const val STREAM_MEDIA = 0

    /** Spoken turn-by-turn directions and the assistant's voice. 16 kHz mono as advertised. */
    const val STREAM_GUIDANCE = 1

    /** Chimes and notification sounds. */
    const val STREAM_SYSTEM = 2

    const val KIND_PCM = 0
    const val KIND_STARTED = 1
    const val KIND_STOPPED = 2

    /** Upper bound on one packet; the phone sends a few kilobytes at a time, never near this. */
    const val MAX_PACKET_BYTES = 256 * 1024

    class Frame(
        val stream: Int,
        val kind: Int,
        val sampleRateHz: Int,
        val channels: Int,
        val payload: ByteArray
    )

    fun writeStarted(out: DataOutputStream, stream: Int, sampleRateHz: Int, channels: Int) {
        out.writeByte(stream)
        out.writeByte(KIND_STARTED)
        out.writeInt(sampleRateHz)
        out.writeByte(channels)
        out.writeInt(0)
    }

    fun writeStopped(out: DataOutputStream, stream: Int) {
        out.writeByte(stream)
        out.writeByte(KIND_STOPPED)
        out.writeInt(0)
        out.writeByte(0)
        out.writeInt(0)
    }

    fun writePcm(out: DataOutputStream, stream: Int, pcm: ByteArray, offset: Int, length: Int) {
        require(length in 1..MAX_PACKET_BYTES) { "Audio packet size out of range: $length" }
        out.writeByte(stream)
        out.writeByte(KIND_PCM)
        out.writeInt(0)
        out.writeByte(0)
        out.writeInt(length)
        out.write(pcm, offset, length)
    }

    /** Reads one frame, or throws [java.io.EOFException] when the writer closed the pipe. */
    fun read(input: DataInputStream): Frame {
        val stream = input.readUnsignedByte()
        val kind = input.readUnsignedByte()
        val sampleRateHz = input.readInt()
        val channels = input.readUnsignedByte()
        val size = input.readInt()
        require(size in 0..MAX_PACKET_BYTES) { "Invalid audio packet size: $size" }
        val payload = ByteArray(size)
        if (size > 0) input.readFully(payload)
        return Frame(stream, kind, sampleRateHz, channels, payload)
    }
}
