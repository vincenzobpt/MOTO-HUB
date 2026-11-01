// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.session

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExitTraceTest {
    /** A minimal protobuf writer, field numbers as in AOSP tombstone.proto. */
    private class Pb {
        private val out = ByteArrayOutputStream()
        private fun varint(value: Long) {
            var v = value
            while (true) {
                if (v and 0x7FL.inv() == 0L) { out.write(v.toInt()); return }
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
        }
        fun int(field: Int, value: Long) = apply { varint((field shl 3).toLong()); varint(value) }
        fun bytes(field: Int, value: ByteArray) = apply {
            varint(((field shl 3) or 2).toLong()); varint(value.size.toLong()); out.write(value)
        }
        fun str(field: Int, value: String) = bytes(field, value.toByteArray())
        fun msg(field: Int, value: Pb) = bytes(field, value.build())
        fun build(): ByteArray = out.toByteArray()
    }

    private fun frame(relPc: Long, file: String, function: String? = null, offset: Long = 0) =
        Pb().int(1, relPc).apply { function?.let { str(4, it); int(5, offset) } }.str(6, file)

    private fun thread(id: Long, name: String, vararg frames: Pb) =
        Pb().int(1, id).str(2, name).apply { frames.forEach { msg(4, it) } }

    private fun mapEntry(key: Long, thread: Pb) = Pb().int(1, key).msg(2, thread)

    @Test
    fun anAbortIsReportedAtTheFirstFrameOutsideTheCRuntime() {
        val tombstone = Pb()
            .int(5, 4242).int(6, 4250)
            .msg(10, Pb().int(1, 6).str(2, "SIGABRT").int(3, -1).str(4, "SI_TKILL"))
            .str(14, "Check failed: renderer != nullptr")
            // A thread that did not crash, listed first, must not be the one reported.
            .msg(16, mapEntry(4242, thread(4242, "main", frame(0x10, "/apex/libc.so", "epoll_wait", 8))))
            .msg(
                16,
                mapEntry(
                    4250,
                    thread(
                        4250, "TextureViewRend",
                        frame(0x5a2b4, "/apex/com.android.runtime/lib64/bionic/libc.so", "abort", 164),
                        frame(0x1f00a0, "/data/app/~~x/lib/arm64/libmaplibre.so", "mbgl::android::MapRenderer::resetRenderer()", 72),
                        frame(0x99, "/system/lib64/libGLESv2.so")
                    )
                )
            )
            .build()

        val crash = ExitTrace.parseTombstone(tombstone)!!
        assertEquals("SIGABRT (SI_TKILL)", crash.signal)
        assertEquals("TextureViewRend", crash.threadName)
        assertEquals("Check failed: renderer != nullptr", crash.abortMessage)
        assertEquals(3, crash.frames.size)
        assertEquals(
            "SIGABRT (SI_TKILL) in libmaplibre.so (mbgl::android::MapRenderer::resetRenderer()+72) on thread 'TextureViewRend'",
            crash.headline
        )
        assertEquals("#00 pc 0005a2b4 libc.so (abort+164)", crash.lines()[1])
    }

    @Test
    fun aSegfaultCarriesItsFaultAddressAndCause() {
        val tombstone = Pb()
            .int(6, 7)
            .msg(10, Pb().int(1, 11).str(2, "SIGSEGV").str(4, "SEGV_MAPERR").int(8, 1).int(9, 0xfc))
            .msg(15, Pb().str(1, "null pointer dereference"))
            .msg(16, mapEntry(7, thread(7, "GLThread 12", frame(0x400, "libGLES_mali.so"))))
            .build()

        val crash = ExitTrace.parseTombstone(tombstone)!!
        assertEquals("SIGSEGV (SEGV_MAPERR) fault addr 0xfc", crash.signal)
        assertEquals("null pointer dereference", crash.cause)
        assertTrue(crash.headline.contains("in libGLES_mali.so"))
    }

    @Test
    fun bytesThatAreNotATombstoneGiveNullInsteadOfThrowing() {
        assertNull(ExitTrace.parseTombstone(byteArrayOf(0x0A, 0x7F, 0x01)))
        assertNull(ExitTrace.parseTombstone(ByteArray(0)))
    }

    @Test
    fun anAnrKeepsOnlyTheMainThreadBlock() {
        val trace = """
            ----- pid 123 at 2026-09-26 -----
            "Signal Catcher" daemon prio=10 tid=2 Runnable
              at nothing

            "main" prio=5 tid=1 Blocked
              at io.motohub.android.Foo.bar(Foo.kt:12)
              - waiting to lock <0x1> held by thread 23

            "other" prio=5 tid=3 Native
        """.trimIndent().toByteArray()

        assertEquals(
            listOf(
                "\"main\" prio=5 tid=1 Blocked",
                "  at io.motohub.android.Foo.bar(Foo.kt:12)",
                "  - waiting to lock <0x1> held by thread 23"
            ),
            ExitTrace.anrMainThread(trace)
        )
    }

    @Test
    fun readingStopsAtTheCapAndToleratesNoStream() {
        assertNull(ExitTrace.read(null))
        assertEquals(3, ExitTrace.read(ByteArrayInputStream(byteArrayOf(1, 2, 3)))!!.size)
    }
}
