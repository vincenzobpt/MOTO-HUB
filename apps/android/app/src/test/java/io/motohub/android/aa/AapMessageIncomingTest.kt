// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.aa

import org.junit.Assert.assertEquals
import org.junit.Test

class AapMessageIncomingTest {
    private fun header(flags: Int) = AapMessageIncoming.EncryptedHeader().apply {
        chan = 3
        this.flags = flags
    }

    @Test
    fun aOneByteLastFragmentIsKeptWithAnUnknownType() {
        // WB-48: the last fragment of a video frame can be a single byte; it has no type to read.
        val msg = AapMessageIncoming(header(0x0a), ByteArrayWithLimit(byteArrayOf(0x42), 1))
        assertEquals(-1, msg.type)
        assertEquals(1, msg.size)
        assertEquals(0x42.toByte(), msg.data[0])
    }

    @Test
    fun aFirstFragmentStillReadsItsType() {
        val msg = AapMessageIncoming(header(0x09), ByteArrayWithLimit(byteArrayOf(0x00, 0x01, 0x05), 3))
        assertEquals(1, msg.type)
    }
}
