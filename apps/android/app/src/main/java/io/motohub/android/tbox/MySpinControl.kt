// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import org.json.JSONException
import org.json.JSONObject

/**
 * The Bluetooth control dialogue of a mySPIN dash, which hands the phone the Wi-Fi credentials
 * before any projection starts. Pure: the RFCOMM socket lives elsewhere and feeds [receive] one
 * line at a time, writing every line [send] produces followed by `\n`.
 *
 * The phone opens: detect, then init. The dash answers init with how it wants the Wi-Fi made
 * (its own access point, or Wi-Fi Direct by push-button or PIN). The phone then asks for the
 * credentials, but only once the dash says its Wi-Fi is `READY`; asking earlier is what the
 * reference client explicitly avoids.
 *
 * A push-button Wi-Fi Direct request has to carry the phone's own P2P device name, which only the
 * Android side knows, so that one waits for [requestWifiDirectPushButton].
 */
class MySpinControl(
    private val regionCode: String,
    private val send: (String) -> Unit,
    private val newId: () -> String = { UUID.randomUUID().toString() }
) {

    enum class PhoneWlan { DISABLED, READY, CONNECTED }

    /** How the dash wants the Wi-Fi made, from its init response. */
    enum class WirelessMode { ACCESS_POINT, WIFI_DIRECT_PUSH_BUTTON, WIFI_DIRECT_PIN }

    sealed interface Event {
        data class Capabilities(
            val customerId: String,
            val connectionType: String,
            val authenticationType: String,
            val mode: WirelessMode
        ) : Event

        data class VehicleWlan(val state: String) : Event
        data class AccessPoint(val ssid: String, val password: String, val frequencyBand: String) : Event
        data class WifiDirect(val deviceName: String, val pin: String?) : Event

        /** The dialogue cannot go on; [reason] is for the log and the rider. */
        data class Failed(val reason: String) : Event
    }

    var mode: WirelessMode? = null
        private set
    // READY from the start, as the Bosch launcher (once its Wi-Fi Direct is up) and MTScreen do:
    // MOTO-HUB never starts a connection with the phone's Wi-Fi off. DISABLED told a Suzuki dash the
    // phone could not join, and it dropped its Wi-Fi Direct 5 s after READY (303A-85C3-874E, E1FC-EB05-E824).
    var phoneWlan = PhoneWlan.READY
        private set

    private val pending = LinkedHashMap<String, String>()
    private var vehicleReady = false
    private var queuedRequest: (() -> Unit)? = null
    private var failed = false

    /** How many requests are waiting for an answer; the transport times out on this. */
    val pendingRequests: Int get() = pending.size

    /** First line of the dialogue, once the dash has connected its RFCOMM socket. */
    fun begin() {
        // The detect request is the one message without a payload, carrying the version at top level.
        val id = newId()
        pending[id] = IVI_DETECT_RESPONSE
        send(JSONObject().put("id", id).put("type", PHONE_DETECT_REQUEST).put("version", PROTOCOL_VERSION.toString()).toString())
    }

    fun receive(line: String): List<Event> {
        if (failed) return emptyList()
        val message = try {
            JSONObject(line)
        } catch (malformed: JSONException) {
            return fail("unreadable control message: ${malformed.message}")
        }
        return try {
            handle(message)
        } catch (missing: JSONException) {
            fail("control message ${message.optString("type")} is missing a field: ${missing.message}")
        }
    }

    /** Tells the dash where the phone's Wi-Fi stands; CONNECTED once the phone has joined. */
    fun reportPhoneWlan(state: PhoneWlan) {
        phoneWlan = state
        notify(PHONE_WLAN_STATE_NOTIFICATION, JSONObject().put("phoneWlanState", state.name))
    }

    /** Push-button Wi-Fi Direct only: [deviceName] is this phone's own Wi-Fi Direct name. */
    fun requestWifiDirectPushButton(deviceName: String) {
        check(mode == WirelessMode.WIFI_DIRECT_PUSH_BUTTON) { "the dash did not ask for push-button Wi-Fi Direct" }
        whenVehicleReady {
            request(PHONE_P2P_PBC_REQUEST, IVI_P2P_PBC_RESPONSE, JSONObject().put("deviceName", deviceName))
        }
    }

    private fun handle(message: JSONObject): List<Event> {
        val type = message.getString("type")
        val id = message.getString("id")
        val payload = message.optJSONObject("payload") ?: JSONObject()
        if (type.endsWith("Response")) {
            val expected = pending.remove(id)
            if (expected != type) return fail("unexpected control response $type")
        }
        return when (type) {
            IVI_DETECT_RESPONSE -> {
                request(
                    PHONE_INIT_REQUEST,
                    IVI_INIT_RESPONSE,
                    JSONObject().put("regionCode", regionCode).put("version", PROTOCOL_VERSION).put("phoneOS", "Android")
                )
                emptyList()
            }
            IVI_INIT_RESPONSE -> onInit(payload)
            // A response without the state field is only an acknowledgement; the notification follows.
            IVI_WLAN_STATE_RESPONSE, IVI_WLAN_STATE_NOTIFICATION ->
                payload.optString("iviWlanState").takeIf { it.isNotEmpty() }?.let(::onVehicleWlan) ?: emptyList()
            IVI_WLAN_STATE_REQUEST -> {
                send(message(id, PHONE_WLAN_STATE_RESPONSE, JSONObject().put("phoneWlanState", phoneWlan.name)))
                emptyList()
            }
            IVI_AP_RESPONSE -> listOf(
                Event.AccessPoint(payload.getString("ssid"), payload.getString("password"), payload.optString("frequencyBand"))
            )
            IVI_P2P_PBC_RESPONSE -> listOf(Event.WifiDirect(payload.getString("deviceName"), null))
            IVI_P2P_PIN_RESPONSE -> listOf(Event.WifiDirect(payload.getString("deviceName"), payload.getString("pin")))
            IVI_ERROR_NOTIFICATION -> fail("the dash reported a Wi-Fi error: ${payload.optString("iviError")}")
            IVI_UNKNOWN_TYPE_NOTIFICATION -> fail("the dash does not understand ${payload.optString("unknownType")}")
            else -> {
                notify(PHONE_UNKNOWN_TYPE_NOTIFICATION, JSONObject().put("unknownType", type))
                emptyList()
            }
        }
    }

    private fun onInit(payload: JSONObject): List<Event> {
        val version = payload.getInt("version")
        if (version != PROTOCOL_VERSION) return fail("the dash speaks control protocol $version, not $PROTOCOL_VERSION")
        val connectionType = payload.getString("connectionType")
        val authenticationType = payload.getString("authenticationType")
        val chosen = when {
            connectionType == "AP" && authenticationType == "PSK" -> WirelessMode.ACCESS_POINT
            connectionType == "P2P" && authenticationType == "PBC" -> WirelessMode.WIFI_DIRECT_PUSH_BUTTON
            connectionType == "P2P" && authenticationType == "PIN" -> WirelessMode.WIFI_DIRECT_PIN
            else -> return fail("the dash asks for Wi-Fi $connectionType/$authenticationType, which is not supported")
        }
        mode = chosen
        reportPhoneWlan(phoneWlan)
        request(PHONE_WLAN_STATE_REQUEST, IVI_WLAN_STATE_RESPONSE, null)
        when (chosen) {
            WirelessMode.ACCESS_POINT -> whenVehicleReady { request(PHONE_AP_REQUEST, IVI_AP_RESPONSE, null) }
            WirelessMode.WIFI_DIRECT_PIN -> whenVehicleReady { request(PHONE_P2P_PIN_REQUEST, IVI_P2P_PIN_RESPONSE, null) }
            WirelessMode.WIFI_DIRECT_PUSH_BUTTON -> Unit
        }
        return listOf(Event.Capabilities(payload.optString("customerID"), connectionType, authenticationType, chosen))
    }

    private fun onVehicleWlan(state: String): List<Event> {
        vehicleReady = state == "READY"
        if (vehicleReady) {
            queuedRequest?.also { queuedRequest = null }?.invoke()
        }
        return listOf(Event.VehicleWlan(state))
    }

    private fun whenVehicleReady(action: () -> Unit) {
        if (vehicleReady) action() else queuedRequest = action
    }

    private fun request(type: String, expectedResponse: String, payload: JSONObject?) {
        val id = newId()
        pending[id] = expectedResponse
        send(message(id, type, payload ?: JSONObject()))
    }

    private fun notify(type: String, payload: JSONObject) {
        send(message(newId(), type, payload))
    }

    private fun message(id: String, type: String, payload: JSONObject): String =
        JSONObject().put("id", id).put("type", type).put("payload", payload).toString()

    private fun fail(reason: String): List<Event> {
        failed = true
        return listOf(Event.Failed(reason))
    }

    companion object {
        const val PROTOCOL_VERSION = 3

        /** Longest control line accepted; the reference client uses the same cap. */
        const val MAX_LINE_BYTES = 16 * 1024

        /**
         * The next control line from the RFCOMM stream, without its `\n` (and any `\r`), or null at
         * a clean end of stream. Empty lines are skipped.
         */
        fun readLine(input: InputStream, maxBytes: Int = MAX_LINE_BYTES): String? {
            val buffer = ByteArrayOutputStream()
            while (true) {
                val next = input.read()
                if (next < 0) {
                    if (buffer.size() == 0) return null
                    throw EOFException("mySPIN control line cut off after ${buffer.size()} bytes")
                }
                if (next == '\n'.code) {
                    val line = String(buffer.toByteArray(), Charsets.UTF_8).trimEnd('\r')
                    if (line.isNotEmpty()) return line
                    buffer.reset()
                    continue
                }
                if (buffer.size() >= maxBytes) throw IOException("mySPIN control line over $maxBytes bytes")
                buffer.write(next)
            }
        }

        const val PHONE_DETECT_REQUEST = "phoneDetectRequest"
        const val IVI_DETECT_RESPONSE = "iviDetectResponse"
        const val PHONE_INIT_REQUEST = "phoneInitRequest"
        const val IVI_INIT_RESPONSE = "iviInitResponse"
        const val PHONE_WLAN_STATE_REQUEST = "phoneWlanStateRequest"
        const val IVI_WLAN_STATE_RESPONSE = "iviWlanStateResponse"
        const val IVI_WLAN_STATE_REQUEST = "iviWlanStateRequest"
        const val PHONE_WLAN_STATE_RESPONSE = "phoneWlanStateResponse"
        const val PHONE_WLAN_STATE_NOTIFICATION = "phoneWlanStateNotification"
        const val IVI_WLAN_STATE_NOTIFICATION = "iviWlanStateNotification"
        const val PHONE_AP_REQUEST = "phoneWlanAPConnectionRequest"
        const val IVI_AP_RESPONSE = "iviWlanAPConnectionResponse"
        const val PHONE_P2P_PBC_REQUEST = "phoneWlanP2PPbcConnectionRequest"
        const val IVI_P2P_PBC_RESPONSE = "iviWlanP2PPbcConnectionResponse"
        const val PHONE_P2P_PIN_REQUEST = "phoneWlanP2PPinConnectionRequest"
        const val IVI_P2P_PIN_RESPONSE = "iviWlanP2PPinConnectionResponse"
        const val PHONE_UNKNOWN_TYPE_NOTIFICATION = "phoneUnknownTypeNotification"
        const val IVI_UNKNOWN_TYPE_NOTIFICATION = "iviUnknownTypeNotification"
        const val IVI_ERROR_NOTIFICATION = "iviErrorNotification"
    }
}
