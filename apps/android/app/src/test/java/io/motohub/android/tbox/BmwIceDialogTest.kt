// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import io.motohub.android.tbox.BmwIceDialog.Event
import io.motohub.android.tbox.BmwIceWire.Identity
import io.motohub.android.tbox.BmwIceWire.Message
import io.motohub.android.tbox.BmwIceWire.MessageId
import io.motohub.android.tbox.BmwIceWire.MySpinIceMsg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The MySpinICE dialogue against a TFT that follows the order the official BMW app expects. */
class BmwIceDialogTest {

    private val sent = mutableListOf<ByteArray>()
    private var ids = 0
    private val dialog = BmwIceDialog(send = { sent += it }, newId = { "id${ids++}" })
    private val vehicle = Identity("veh-1", "")

    private fun requests() = sent.map { BmwIceWire.parse(it) }.filterIsInstance<Message.Request>()
    private fun replies() = sent.map { BmwIceWire.parse(it) }.filterIsInstance<Message.Reply>()

    private fun tftCalls(requestId: Int, operation: String, message: MySpinIceMsg) =
        dialog.receive(BmwIceWire.request(requestId, dialog.appIdentity, operation) { message.write(this) })

    private fun tftReplies(requestId: Int, result: BmwIceWire.Writer.() -> Unit) =
        dialog.receive(BmwIceWire.reply(requestId, result))

    private fun messageOf(request: Message.Request) = MySpinIceMsg.read(request.params)

    /** What the phone sent to the TFT's session object, decoded once (a Reader is read only once). */
    private fun toVehicle() = requests().filter { it.target == vehicle }.map { it.operation to messageOf(it) }

    @Test
    fun theWholeDialogueEndsWithTheWifiDirectCredentials() {
        dialog.receive(BmwIceWire.frame(BmwIceWire.VALIDATE_CONNECTION))
        val create = requests().single()
        assertEquals("createMySpinICESession", create.operation)
        assertEquals(BmwIceDialog.SESSION_FACTORY, create.target)
        assertEquals(dialog.appIdentity, create.params.proxy(11))
        assertEquals(0, create.params.bytes().size)

        tftReplies(create.requestId) { proxy(vehicle) }

        tftCalls(100, "getMySpinICEapp", MySpinIceMsg(MessageId.HELLO, val0 = 5))
        val hello = MySpinIceMsg.read(replies().last().result!!)
        assertEquals(MessageId.HELLO, hello.msgId)
        assertEquals("", hello.sessionId)
        assertEquals("Android", hello.str1)
        val session = hello.str0

        tftCalls(101, "setMySpinICEapp", MySpinIceMsg(MessageId.HELLO_ACK, session, val0 = 0))
        assertEquals(0, replies().last().result!!.int())
        val sentToVehicle = toVehicle()
        assertEquals("DE", sentToVehicle.first { it.second.msgId == MessageId.COUNTRY_CODE }.second.str0)
        assertEquals(2L, sentToVehicle.first { it.second.msgId == MessageId.PHONE_WLAN_STATE }.second.val0)

        // Wi-Fi was already on at Hello: the credentials are asked at once.
        assertEquals("getMySpinICEveh" to MessageId.WLAN_CREDENTIALS_P2P, sentToVehicle.last().let { it.first to it.second.msgId })
        val ask = requests().last()

        val events = tftReplies(ask.requestId) {
            MySpinIceMsg(MessageId.WLAN_CREDENTIALS_P2P, session, str0 = "DeviceName=2W-Wifi-ab", str4 = "Pin=12345678").write(this)
        }
        assertEquals(Event.WifiDirect("2W-Wifi-ab", "12345678"), events.single { it is Event.WifiDirect })
    }

    @Test
    fun aHelloBeforeTheSessionReplyStillSendsTheCountryCode() {
        dialog.receive(BmwIceWire.frame(BmwIceWire.VALIDATE_CONNECTION))
        val create = requests().single()
        tftCalls(100, "getMySpinICEapp", MySpinIceMsg(MessageId.HELLO, val0 = 3))
        val session = MySpinIceMsg.read(replies().last().result!!).str0
        tftCalls(101, "setMySpinICEapp", MySpinIceMsg(MessageId.HELLO_ACK, session))
        assertTrue(requests().none { it.operation == "setMySpinICEveh" })

        tftReplies(create.requestId) { proxy(vehicle) }
        assertEquals("DE", messageOf(requests().first { it.operation == "setMySpinICEveh" }).str0)

        // Wi-Fi comes on later: only then are the credentials asked.
        assertTrue(requests().none { it.operation == "getMySpinICEveh" })
        tftCalls(102, "setMySpinICEapp", MySpinIceMsg(MessageId.CLUSTER_WLAN_STATE, session, val0 = 5))
        assertEquals("getMySpinICEveh", requests().last().operation)
    }

    @Test
    fun aRefusingTftEndsTheDialogue() {
        dialog.receive(BmwIceWire.frame(BmwIceWire.VALIDATE_CONNECTION))
        tftReplies(requests().single().requestId) { proxy(vehicle) }
        tftCalls(100, "getMySpinICEapp", MySpinIceMsg(MessageId.HELLO))
        val session = MySpinIceMsg.read(replies().last().result!!).str0
        tftCalls(101, "setMySpinICEapp", MySpinIceMsg(MessageId.HELLO_ACK, session))
        val events = tftCalls(102, "setMySpinICEapp", MySpinIceMsg(MessageId.CLUSTER_WLAN_STATE, session, val0 = 8))
        assertTrue(events.single() is Event.Failed)
    }

    @Test
    fun aTftWithoutMapModeSaysSoClearly() {
        dialog.receive(BmwIceWire.frame(BmwIceWire.VALIDATE_CONNECTION))
        val create = requests().single()
        val events = dialog.receive(
            BmwIceWire.replyNotExist(create.requestId, BmwIceWire.REPLY_OPERATION_NOT_EXIST, create.target, emptyList(), create.operation)
        )
        assertTrue((events.single() as Event.Failed).reason.contains("Map mode"))
    }

    @Test
    fun theAppObjectAnswersPingAndIsA() {
        dialog.receive(BmwIceWire.request(5, dialog.appIdentity, "ice_isA") { string("::ConnectedRide::MySpinICE_App") })
        assertTrue(replies().last().result!!.bool())
        dialog.receive(BmwIceWire.request(6, Identity("nobody"), "ice_ping"))
        assertEquals(BmwIceWire.REPLY_OBJECT_NOT_EXIST, replies().last().status)
    }
}
