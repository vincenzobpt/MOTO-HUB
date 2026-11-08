// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import org.json.JSONException
import org.json.JSONObject

/**
 * The UDP announcement a mySPIN dash broadcasts once the phone has joined its Wi-Fi: a JSON object
 * naming the TCP endpoint the phone must connect to. Pure parsing; the socket lives elsewhere.
 */
object MySpinDiscovery {

    /** The UDP port the phone listens on for announcements. */
    const val PORT = 8158

    const val MAX_ANNOUNCEMENT_BYTES = 4096

    data class Endpoint(
        val host: String,
        val port: Int,
        val version: Int,
        val identificationJson: String,
        val sourceAddress: String
    )

    /** The endpoint in one datagram, or null when it is not a usable mySPIN announcement. */
    fun parse(datagram: ByteArray, sourceAddress: String): Endpoint? {
        if (datagram.size > MAX_ANNOUNCEMENT_BYTES || sourceAddress.isBlank()) return null
        val text = String(datagram, Charsets.UTF_8).trim { it == '\u0000' || it.isWhitespace() }
        return try {
            val json = JSONObject(text)
            val host = json.optString("hostname").ifBlank { json.optString("hostname_v6") }
            val port = json.getInt("port")
            if (host.isBlank() || port !in 1..65535) return null
            Endpoint(host, port, json.getInt("version"), json.getJSONObject("identification").toString(), sourceAddress)
        } catch (notAnnouncement: JSONException) {
            null
        }
    }
}
