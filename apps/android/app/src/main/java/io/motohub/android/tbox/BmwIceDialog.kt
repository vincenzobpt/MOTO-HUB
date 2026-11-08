// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import io.motohub.android.tbox.BmwIceWire.Identity
import io.motohub.android.tbox.BmwIceWire.Message
import io.motohub.android.tbox.BmwIceWire.MessageId
import io.motohub.android.tbox.BmwIceWire.MySpinIceMsg
import java.util.UUID

/**
 * The BMW TFT's MySpinICE dialogue on the Ice channel, as a pure state machine fed whole Ice
 * frames. It ends where the Suzuki's Bosch dialogue ends, with the dash's Wi-Fi credentials; the
 * rest is the shared mySPIN transport.
 *
 * The phone is the Ice client and also serves `::ConnectedRide::MySpinICE_App` on the same
 * connection, which is where the TFT says Hello and reports its Wi-Fi state.
 */
class BmwIceDialog(
    private val send: (ByteArray) -> Unit,
    private val newId: () -> String = { UUID.randomUUID().toString() }
) {
    sealed interface Event {
        data class Note(val text: String) : Event
        data class WifiDirect(val deviceName: String, val pin: String) : Event
        data class AccessPoint(val ssid: String, val passphrase: String) : Event
        /** The dialogue cannot go on; [reason] is for the log and the rider. */
        data class Failed(val reason: String) : Event
    }

    /** `ConnectedRide::cltWlanState`, the TFT's own Wi-Fi as it reports it. */
    object ClusterWlan {
        const val DISABLED = 3
        const val ENABLED = 5
        const val CONNECTED = 6
        const val UNAUTHORISED = 8
        const val NOT_PAIRED = 9
    }

    /** `ConnectedRide::phWlanState`, what the phone tells the TFT about its own Wi-Fi. */
    object PhoneWlan {
        const val DISABLED = 0
        const val ENABLED = 2
        const val CONNECTED = 4
    }

    val appIdentity = Identity(newId())
    var clusterWlanState: Int? = null
        private set

    private var started = false
    private var nextRequestId = 1
    private val pending = HashMap<Int, String>()
    private var vehicle: Identity? = null
    private var sessionId: String? = null
    private var sessionOpen = false
    private var credentialsAsked = false
    private var countryPending = false
    private var failed = false

    /** Called once the TFT has validated the connection: opens the MySpinICE session. */
    private fun start(): List<Event> {
        started = true
        call(SESSION_FACTORY, "createMySpinICESession", "create") {
            proxy(appIdentity)
            bytes(ByteArray(0)) // credentials: the official app sends none either
        }
        return listOf(Event.Note("asked the TFT for a mySPIN session"))
    }

    fun receive(frame: ByteArray): List<Event> {
        if (failed) return emptyList()
        return when (val message = BmwIceWire.parse(frame)) {
            Message.ValidateConnection -> if (started) emptyList() else start()
            Message.CloseConnection -> fail("the TFT closed the Ice connection")
            is Message.Other -> listOf(Event.Note("ignored Ice message type ${message.type}"))
            is Message.Reply -> onReply(message)
            is Message.Request -> onRequest(message)
        }
    }

    /** Tells the TFT where the phone's Wi-Fi stands; [state] is a [PhoneWlan] value. */
    fun reportPhoneWlan(state: Int) {
        val session = sessionId ?: return
        if (!sessionOpen) return
        callVehicle("setMySpinICEveh", "phoneWlan") {
            MySpinIceMsg(MessageId.PHONE_WLAN_STATE, session, val0 = state.toLong()).write(this)
        }
    }

    private fun onRequest(request: Message.Request): List<Event> {
        if (request.target != appIdentity) {
            send(BmwIceWire.replyNotExist(request.requestId, BmwIceWire.REPLY_OBJECT_NOT_EXIST, request.target, request.facet, request.operation))
            return listOf(Event.Note("the TFT called ${request.operation} on an unknown object ${request.target}"))
        }
        return when (request.operation) {
            "getMySpinICEapp" -> onGet(request, MySpinIceMsg.read(request.params))
            "setMySpinICEapp" -> onSet(request, MySpinIceMsg.read(request.params))
            "ice_ping" -> { reply(request) {}; emptyList() }
            "ice_isA" -> {
                val type = request.params.string()
                reply(request) { bool(type in APP_TYPE_IDS) }
                emptyList()
            }
            "ice_id" -> { reply(request) { string(APP_TYPE_IDS.first()) }; emptyList() }
            "ice_ids" -> { reply(request) { strings(APP_TYPE_IDS.sorted()) }; emptyList() }
            else -> {
                send(BmwIceWire.replyNotExist(request.requestId, BmwIceWire.REPLY_OPERATION_NOT_EXIST, request.target, request.facet, request.operation))
                listOf(Event.Note("the TFT called an unknown operation ${request.operation}"))
            }
        }
    }

    private fun onGet(request: Message.Request, message: MySpinIceMsg): List<Event> {
        if (message.msgId != MessageId.HELLO) {
            reply(request) { message.write(this) }
            return listOf(Event.Note("unexpected getMySpinICEapp ${message.msgId}; echoed"))
        }
        // A new Hello is a new session, as in the official app.
        clusterWlanState = message.val0.toInt()
        val session = newId()
        sessionId = session
        sessionOpen = false
        credentialsAsked = false
        reply(request) { MySpinIceMsg(MessageId.HELLO, str0 = session, str1 = PHONE_TYPE).write(this) }
        return listOf(Event.Note("Hello from the TFT (Wi-Fi state ${message.val0}); session $session"))
    }

    private fun onSet(request: Message.Request, message: MySpinIceMsg): List<Event> {
        reply(request) { int(ACK_OK) }
        val ours = sessionId
        if (ours == null || message.sessionId != ours) {
            return listOf(Event.Note("ignored ${message.msgId} for session \"${message.sessionId}\""))
        }
        return when (message.msgId) {
            MessageId.HELLO_ACK -> {
                val ack = message.val0.toInt()
                if (ack == ACK_OK || ack == ACK_PHONE_TYPE_ERROR) {
                    sessionOpen = true
                    sendCountryCode()
                    // The official app reports its Wi-Fi as soon as it is on; the Suzuki showed a
                    // dash that drops its Wi-Fi for a phone that says nothing or "disabled".
                    reportPhoneWlan(PhoneWlan.ENABLED)
                    listOf(Event.Note("mySPIN session open (ack $ack); sent the country code")) + maybeAskCredentials()
                } else {
                    fail("the TFT refused the mySPIN session (ack $ack)")
                }
            }
            MessageId.CLUSTER_WLAN_STATE -> {
                val state = message.val0.toInt()
                clusterWlanState = state
                when (state) {
                    ClusterWlan.UNAUTHORISED -> fail("the TFT says this phone is not authorised for its Wi-Fi (state 8)")
                    ClusterWlan.NOT_PAIRED -> fail("the TFT says this phone is not paired (state 9)")
                    ClusterWlan.DISABLED -> {
                        // The country code is what switches the TFT's Wi-Fi on.
                        if (sessionOpen) sendCountryCode()
                        listOf(Event.Note("TFT Wi-Fi is off; sent the country code again"))
                    }
                    else -> listOf(Event.Note("TFT Wi-Fi state $state")) + maybeAskCredentials()
                }
            }
            MessageId.CLUSTER_WLAN_ERROR -> listOf(Event.Note("TFT Wi-Fi error ${message.val0}"))
            else -> listOf(Event.Note("TFT sent ${message.msgId} val0=${message.val0}"))
        }
    }

    private fun maybeAskCredentials(): List<Event> {
        val session = sessionId ?: return emptyList()
        if (!sessionOpen || credentialsAsked || clusterWlanState != ClusterWlan.ENABLED || vehicle == null) return emptyList()
        credentialsAsked = true
        callVehicle("getMySpinICEveh", "credentials") {
            MySpinIceMsg(MessageId.WLAN_CREDENTIALS_P2P, session).write(this)
        }
        return listOf(Event.Note("asked the TFT for its Wi-Fi Direct credentials"))
    }

    private fun onReply(reply: Message.Reply): List<Event> {
        val what = pending.remove(reply.requestId) ?: return listOf(Event.Note("reply to an unknown request ${reply.requestId}"))
        if (reply.status != BmwIceWire.REPLY_OK) {
            val reason = when (reply.status) {
                BmwIceWire.REPLY_USER_EXCEPTION -> "the TFT raised an exception"
                BmwIceWire.REPLY_OBJECT_NOT_EXIST -> "the TFT has no such object"
                BmwIceWire.REPLY_OPERATION_NOT_EXIST -> "the TFT does not support it (no Map mode on this TFT?)"
                else -> "Ice reply status ${reply.status}"
            }
            return if (what == "create" || what == "credentials") fail("$what: $reason") else listOf(Event.Note("$what: $reason"))
        }
        val result = reply.result ?: return listOf(Event.Note("$what: empty reply"))
        return when (what) {
            "create" -> {
                val proxy = result.proxy(reply.encoding)
                    ?: return fail("the TFT returned no mySPIN session")
                vehicle = proxy
                // The TFT may have said Hello before this reply: what waited for it goes now.
                if (countryPending) {
                    countryPending = false
                    sendCountryCode()
                    reportPhoneWlan(PhoneWlan.ENABLED)
                }
                listOf(Event.Note("mySPIN session object ${proxy.category}/${proxy.name}")) + maybeAskCredentials()
            }
            "credentials" -> {
                val message = MySpinIceMsg.read(result)
                val device = BmwIceWire.field(message.str0, "DeviceName")
                val pin = BmwIceWire.field(message.str4, "Pin")
                if (device.isNullOrEmpty() || pin.isNullOrEmpty()) {
                    fail("the TFT's Wi-Fi Direct credentials were incomplete")
                } else {
                    listOf(Event.WifiDirect(device, pin))
                }
            }
            else -> listOf(Event.Note("$what acknowledged (${result.int()})"))
        }
    }

    private fun sendCountryCode() {
        val session = sessionId ?: return
        if (vehicle == null) {
            countryPending = true
            return
        }
        callVehicle("setMySpinICEveh", "country") {
            MySpinIceMsg(MessageId.COUNTRY_CODE, session, str0 = COUNTRY_CODE).write(this)
        }
    }

    private fun callVehicle(operation: String, what: String, params: BmwIceWire.Writer.() -> Unit) {
        val target = vehicle ?: return
        call(target, operation, what, params)
    }

    private fun call(target: Identity, operation: String, what: String, params: BmwIceWire.Writer.() -> Unit) {
        val id = nextRequestId++
        pending[id] = what
        send(BmwIceWire.request(id, target, operation, params))
    }

    private fun reply(request: Message.Request, result: BmwIceWire.Writer.() -> Unit) {
        send(BmwIceWire.reply(request.requestId, result))
    }

    private fun fail(reason: String): List<Event> {
        failed = true
        return listOf(Event.Failed(reason))
    }

    companion object {
        val SESSION_FACTORY = Identity("SessionFactory", "ConnectedRide")
        val APP_TYPE_IDS = listOf("::ConnectedRide::MySpinICE_App", "::Ice::Object")
        /** What the official app sends when the phone has Wi-Fi Direct, as every Android phone does. */
        const val PHONE_TYPE = "Android"
        /** Always Germany in the official app: it is what switches the TFT's Wi-Fi on. */
        const val COUNTRY_CODE = "DE"
        private const val ACK_OK = 0
        private const val ACK_PHONE_TYPE_ERROR = 2
    }
}
