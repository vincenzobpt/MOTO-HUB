// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MySpinDiscoveryTest {

    private fun announce(text: String, source: String = "192.168.1.1") = MySpinDiscovery.parse(text.toByteArray(), source)

    @Test
    fun anAnnouncementNamesTheEndpoint() {
        val endpoint = announce(
            """{"hostname":"192.168.1.1","port":2001,"version":2,"identification":{"vendor":"Ducati"}}""" + "\r\n\u0000"
        )!!
        assertEquals("192.168.1.1", endpoint.host)
        assertEquals(2001, endpoint.port)
        assertEquals(2, endpoint.version)
        assertEquals("Ducati", JSONObject(endpoint.identificationJson).getString("vendor"))
        assertEquals("192.168.1.1", endpoint.sourceAddress)
    }

    @Test
    fun anIpv6OnlyAnnouncementIsUsable() {
        val endpoint = announce("""{"hostname":"","hostname_v6":"fe80::1","port":2001,"version":1,"identification":{}}""")!!
        assertEquals("fe80::1", endpoint.host)
    }

    @Test
    fun unusableAnnouncementsAreIgnored() {
        assertNull(announce("hello"))
        assertNull(announce("""{"hostname":"192.168.1.1","version":1,"identification":{}}"""))
        assertNull(announce("""{"hostname":"192.168.1.1","port":0,"version":1,"identification":{}}"""))
        assertNull(announce("""{"port":2001,"version":1,"identification":{}}"""))
        assertNull(announce("""{"hostname":"192.168.1.1","port":2001,"version":1,"identification":{}}""", source = ""))
        assertNull(MySpinDiscovery.parse(ByteArray(MySpinDiscovery.MAX_ANNOUNCEMENT_BYTES + 1) { ' '.code.toByte() }, "192.168.1.1"))
    }
}
