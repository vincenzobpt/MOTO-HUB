// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MySpinAnnouncementListenerTest {

    private fun broadcastTo(port: Int, text: String) {
        DatagramSocket().use { sender ->
            val bytes = text.toByteArray()
            sender.send(DatagramPacket(bytes, bytes.size, InetAddress.getLoopbackAddress(), port))
        }
    }

    @Test
    fun theFirstUsableAnnouncementWinsAndGarbageIsSkipped() {
        MySpinAnnouncementListener(port = 0).use { listener ->
            broadcastTo(listener.localPort, "not an announcement")
            broadcastTo(listener.localPort, """{"hostname":"192.168.1.1","port":2001,"version":2,"identification":{}}""")
            val endpoint = listener.await(3_000)!!
            assertEquals("192.168.1.1", endpoint.host)
            assertEquals(2001, endpoint.port)
        }
    }

    @Test
    fun noAnnouncementIsATimeoutNotAnError() {
        MySpinAnnouncementListener(port = 0).use { listener ->
            assertNull(listener.await(200))
        }
    }

    @Test
    fun closingEndsAWaitInProgress() {
        val listener = MySpinAnnouncementListener(port = 0)
        thread {
            Thread.sleep(100)
            listener.close()
        }
        val started = System.currentTimeMillis()
        // The socket closing mid-receive surfaces as an exception or a null; either way it returns.
        runCatching { listener.await(10_000) }
        assertTrue(System.currentTimeMillis() - started < 5_000)
    }
}
