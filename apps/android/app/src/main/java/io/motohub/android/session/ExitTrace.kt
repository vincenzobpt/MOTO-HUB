// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.session

import java.io.InputStream

/**
 * Turns the trace Android keeps with a process death into the few lines a report needs.
 *
 * WB-22: every native crash in the field arrived as "a native crash ... status=6" and nothing else,
 * thirteen riders of it, because the record's trace was never read. For a native crash that trace
 * is the tombstone - since Android 12 a protobuf (AOSP system/core/debuggerd/proto/tombstone.proto)
 * carrying the signal, the abort message and the crashing thread's backtrace with library names.
 * For an ANR it is the text dump of every thread, of which the main thread is the one that matters.
 *
 * Pure Kotlin on bytes so it can be tested without a phone; nothing here may throw, because it
 * runs at startup on whatever an OEM build hands back.
 */
object ExitTrace {
    /** Tombstones carry memory maps and log buffers; the parts read here sit well inside this. */
    private const val MAX_TRACE_BYTES = 4 * 1024 * 1024
    private const val MAX_FRAMES = 32
    private const val MAX_ANR_LINES = 40
    private const val MAX_ABORT_CHARS = 600

    /** Reads at most [MAX_TRACE_BYTES]; null when there is nothing or the stream fails. */
    fun read(stream: InputStream?): ByteArray? = runCatching {
        stream?.use { input ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            while (buffer.size() < MAX_TRACE_BYTES) {
                val read = input.read(chunk, 0, minOf(chunk.size, MAX_TRACE_BYTES - buffer.size()))
                if (read < 0) break
                buffer.write(chunk, 0, read)
            }
            buffer.toByteArray().takeIf { it.isNotEmpty() }
        }
    }.getOrNull()

    /** What a tombstone says about the crash, or null when it cannot be read as one. */
    data class NativeCrash(
        val signal: String?,
        val cause: String?,
        val abortMessage: String?,
        val threadName: String?,
        val frames: List<String>
    ) {
        /**
         * One line that names where it died. An abort's first frames are always libc's own
         * `abort`/`raise`, which says nothing; the first frame outside the C runtime is the code
         * that asked for it.
         */
        val headline: String
            get() = buildString {
                append(signal ?: "unknown signal")
                val located = frames.firstOrNull { frameFile(it) !in RUNTIME_LIBRARIES } ?: frames.firstOrNull()
                located?.let { append(" in ").append(it.split(' ', limit = 3).getOrElse(2) { it }) }
                threadName?.let { append(" on thread '").append(it).append("'") }
                cause?.let { append(" - ").append(it) }
            }

        fun lines(): List<String> = buildList {
            abortMessage?.let { add("abort: $it") }
            frames.forEachIndexed { index, frame -> add("#%02d %s".format(index, frame)) }
        }
    }

    fun parseTombstone(bytes: ByteArray): NativeCrash? = runCatching {
        val tombstone = Proto(bytes).fields()
        val crashingTid = tombstone.varint(TOMBSTONE_TID)
        val signal = tombstone.message(TOMBSTONE_SIGNAL)?.let { Proto(it).fields() }
        val signalText = signal?.let { fields ->
            val name = fields.string(SIGNAL_NAME)
            val code = fields.string(SIGNAL_CODE_NAME)
            val fault = if (fields.varint(SIGNAL_HAS_FAULT_ADDRESS) == 1L) {
                fields.varint(SIGNAL_FAULT_ADDRESS)?.let { " fault addr 0x%x".format(it) }
            } else {
                null
            }
            listOfNotNull(name, code?.let { "($it)" }).joinToString(" ").ifBlank { null }
                ?.plus(fault.orEmpty())
        }
        val cause = tombstone.messages(TOMBSTONE_CAUSES)
            .firstNotNullOfOrNull { Proto(it).fields().string(CAUSE_HUMAN_READABLE) }
        val abort = tombstone.string(TOMBSTONE_ABORT_MESSAGE)
            ?.trim()?.take(MAX_ABORT_CHARS)?.takeIf(String::isNotBlank)

        // threads is a map<uint32, Thread>: each entry a message with the key at 1, value at 2.
        val threads = tombstone.messages(TOMBSTONE_THREADS).mapNotNull { entry ->
            val fields = Proto(entry).fields()
            val thread = fields.message(MAP_VALUE)?.let { Proto(it).fields() } ?: return@mapNotNull null
            (fields.varint(MAP_KEY) ?: thread.varint(THREAD_ID)) to thread
        }
        val crashing = threads.firstOrNull { it.first == crashingTid }?.second
            ?: threads.firstOrNull()?.second
        val frames = crashing?.messages(THREAD_BACKTRACE).orEmpty()
            .take(MAX_FRAMES)
            .map { frame -> describeFrame(Proto(frame).fields()) }

        NativeCrash(
            signal = signalText,
            cause = cause,
            abortMessage = abort,
            threadName = crashing?.string(THREAD_NAME),
            frames = frames
        ).takeIf { it.signal != null || it.frames.isNotEmpty() || it.abortMessage != null }
    }.getOrNull()

    private fun describeFrame(frame: Fields): String {
        val relPc = frame.varint(FRAME_REL_PC) ?: 0L
        val file = frame.string(FRAME_FILE_NAME)?.substringAfterLast('/') ?: "?"
        val function = frame.string(FRAME_FUNCTION_NAME)?.takeIf(String::isNotBlank)
        val offset = frame.varint(FRAME_FUNCTION_OFFSET) ?: 0L
        // The build id is what symbolicates a stripped library later; a short prefix is enough to
        // match it against the right release of MapLibre, WebView or a GPU driver.
        val buildId = frame.string(FRAME_BUILD_ID)?.takeIf(String::isNotBlank)
            ?: frame.bytes(FRAME_BUILD_ID)?.joinToString("") { "%02x".format(it) }
        return buildString {
            append("pc %08x %s".format(relPc, file))
            if (function != null) append(" ($function+$offset)")
            if (function == null && buildId != null) append(" [build ${buildId.take(16)}]")
        }
    }

    /**
     * The main thread's block of an ANR trace: from its `"main"` header to the blank line that ends
     * it. Null when the trace has no such block.
     */
    fun anrMainThread(bytes: ByteArray): List<String>? = runCatching {
        val lines = String(bytes, Charsets.UTF_8).lineSequence()
        val block = lines
            .dropWhile { !it.startsWith("\"main\"") }
            .takeWhile { it.isNotBlank() }
            .take(MAX_ANR_LINES)
            .map(String::trimEnd)
            .toList()
        block.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private val RUNTIME_LIBRARIES = setOf("libc.so", "libc++.so", "libbase.so", "linker64", "libdl.so")

    /** The library of a frame line as [describeFrame] writes it: `pc <hex> <file> ...`. */
    private fun frameFile(frame: String): String = frame.split(' ').getOrElse(2) { "" }

    // ── tombstone.proto field numbers ───────────────────────────────────────────────────────────
    private const val TOMBSTONE_TID = 6
    private const val TOMBSTONE_SIGNAL = 10
    private const val TOMBSTONE_ABORT_MESSAGE = 14
    private const val TOMBSTONE_CAUSES = 15
    private const val TOMBSTONE_THREADS = 16
    private const val SIGNAL_NAME = 2
    private const val SIGNAL_CODE_NAME = 4
    private const val SIGNAL_HAS_FAULT_ADDRESS = 8
    private const val SIGNAL_FAULT_ADDRESS = 9
    private const val CAUSE_HUMAN_READABLE = 1
    private const val MAP_KEY = 1
    private const val MAP_VALUE = 2
    private const val THREAD_ID = 1
    private const val THREAD_NAME = 2
    private const val THREAD_BACKTRACE = 4
    private const val FRAME_REL_PC = 1
    private const val FRAME_FUNCTION_NAME = 4
    private const val FRAME_FUNCTION_OFFSET = 5
    private const val FRAME_FILE_NAME = 6
    private const val FRAME_BUILD_ID = 8

    /** The fields of one protobuf message, by number; repeated fields keep every occurrence. */
    private class Fields(private val values: Map<Int, List<Any>>) {
        fun varint(number: Int): Long? = values[number]?.firstOrNull() as? Long
        fun bytes(number: Int): ByteArray? = values[number]?.firstOrNull() as? ByteArray
        fun message(number: Int): ByteArray? = bytes(number)
        fun messages(number: Int): List<ByteArray> = values[number].orEmpty().filterIsInstance<ByteArray>()
        fun string(number: Int): String? = bytes(number)?.let { String(it, Charsets.UTF_8) }
    }

    /** Just enough of the protobuf wire format to walk a tombstone without generated classes. */
    private class Proto(private val data: ByteArray) {
        private var position = 0

        fun fields(): Fields {
            val values = HashMap<Int, MutableList<Any>>()
            while (position < data.size) {
                val key = readVarint()
                val number = (key ushr 3).toInt()
                when ((key and 0x7).toInt()) {
                    WIRE_VARINT -> values.getOrPut(number, ::mutableListOf).add(readVarint())
                    WIRE_FIXED64 -> { values.getOrPut(number, ::mutableListOf).add(readFixed(8)) }
                    WIRE_LENGTH -> {
                        val length = readVarint().toInt()
                        require(length >= 0 && position + length <= data.size) { "truncated field $number" }
                        values.getOrPut(number, ::mutableListOf).add(data.copyOfRange(position, position + length))
                        position += length
                    }
                    WIRE_FIXED32 -> { values.getOrPut(number, ::mutableListOf).add(readFixed(4)) }
                    else -> error("unsupported wire type in field $number")
                }
            }
            return Fields(values)
        }

        private fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (shift < 64) {
                require(position < data.size) { "truncated varint" }
                val byte = data[position++].toInt()
                result = result or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
            }
            error("varint too long")
        }

        private fun readFixed(size: Int): Long {
            require(position + size <= data.size) { "truncated fixed$size" }
            var result = 0L
            for (index in 0 until size) {
                result = result or ((data[position + index].toLong() and 0xFF) shl (8 * index))
            }
            position += size
            return result
        }

        private companion object {
            const val WIRE_VARINT = 0
            const val WIRE_FIXED64 = 1
            const val WIRE_LENGTH = 2
            const val WIRE_FIXED32 = 5
        }
    }
}
