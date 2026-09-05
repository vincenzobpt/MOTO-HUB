// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.ipc

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProjectionAudioFramingTest {

    private fun written(block: (DataOutputStream) -> Unit): DataInputStream {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use(block)
        return DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
    }

    @Test
    fun aStartThenPacketsThenAStopComeBackInOrderWithTheirFormat() {
        val pcm = ByteArray(960) { it.toByte() }
        val input = written { out ->
            ProjectionAudioFraming.writeStarted(out, ProjectionAudioFraming.STREAM_MEDIA, 48_000, 2)
            ProjectionAudioFraming.writePcm(out, ProjectionAudioFraming.STREAM_MEDIA, pcm, 0, pcm.size)
            ProjectionAudioFraming.writePcm(out, ProjectionAudioFraming.STREAM_GUIDANCE, pcm, 100, 320)
            ProjectionAudioFraming.writeStopped(out, ProjectionAudioFraming.STREAM_MEDIA)
        }

        val started = ProjectionAudioFraming.read(input)
        assertEquals(ProjectionAudioFraming.KIND_STARTED, started.kind)
        assertEquals(ProjectionAudioFraming.STREAM_MEDIA, started.stream)
        assertEquals(48_000, started.sampleRateHz)
        assertEquals(2, started.channels)
        assertEquals(0, started.payload.size)

        val media = ProjectionAudioFraming.read(input)
        assertEquals(ProjectionAudioFraming.KIND_PCM, media.kind)
        assertArrayEquals(pcm, media.payload)

        val guidance = ProjectionAudioFraming.read(input)
        assertEquals(ProjectionAudioFraming.STREAM_GUIDANCE, guidance.stream)
        assertArrayEquals(pcm.copyOfRange(100, 420), guidance.payload)

        val stopped = ProjectionAudioFraming.read(input)
        assertEquals(ProjectionAudioFraming.KIND_STOPPED, stopped.kind)
        assertEquals(ProjectionAudioFraming.STREAM_MEDIA, stopped.stream)

        // The writer closing the pipe is the reader's end-of-stream, nothing more dramatic.
        assertThrows(EOFException::class.java) { ProjectionAudioFraming.read(input) }
    }

    @Test
    fun anEmptyPacketIsRefusedAtTheWriterNotDiscoveredAtTheReader() {
        assertThrows(IllegalArgumentException::class.java) {
            written { out -> ProjectionAudioFraming.writePcm(out, ProjectionAudioFraming.STREAM_MEDIA, ByteArray(4), 0, 0) }
        }
    }
}
