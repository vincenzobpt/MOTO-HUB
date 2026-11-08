// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import io.motohub.android.tbox.BmwIceWire.Identity
import io.motohub.android.tbox.BmwIceWire.Message
import io.motohub.android.tbox.BmwIceWire.MessageId
import io.motohub.android.tbox.BmwIceWire.MySpinIceMsg
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Ice wire pieces the BMW TFT speaks, checked against bytes written by hand from the protocol. */
class BmwIceWireTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)

    @Test
    fun validateConnectionIsTheBareHeader() {
        assertArrayEquals(
            bytes(0x49, 0x63, 0x65, 0x50, 1, 0, 1, 0, 3, 0, 14, 0, 0, 0),
            BmwIceWire.frame(BmwIceWire.VALIDATE_CONNECTION)
        )
        assertEquals(Message.ValidateConnection, BmwIceWire.parse(BmwIceWire.frame(BmwIceWire.VALIDATE_CONNECTION)))
    }

    @Test
    fun aRequestIsLaidOutAsTheProtocolSays() {
        val frame = BmwIceWire.request(1, Identity("SessionFactory", "ConnectedRide"), "ice_ping")
        val body = bytes(1, 0, 0, 0) +
            bytes(14) + ascii("SessionFactory") + bytes(13) + ascii("ConnectedRide") +
            bytes(0) + // facet
            bytes(8) + ascii("ice_ping") +
            bytes(0) + // mode
            bytes(0) + // context
            bytes(6, 0, 0, 0, 1, 1) // empty encapsulation, encoding 1.1
        assertArrayEquals(bytes(0x49, 0x63, 0x65, 0x50, 1, 0, 1, 0, 0, 0, 14 + body.size, 0, 0, 0) + body, frame)

        val parsed = BmwIceWire.parse(frame) as Message.Request
        assertEquals(Identity("SessionFactory", "ConnectedRide"), parsed.target)
        assertEquals("ice_ping", parsed.operation)
        assertEquals(11, parsed.encoding)
        assertEquals(0, parsed.params.remaining)
    }

    @Test
    fun aMySpinIceMsgKeepsTheSliceFieldOrder() {
        val message = MySpinIceMsg(MessageId.COUNTRY_CODE, "s", val0 = 5, str0 = "DE")
        val written = BmwIceWire.Writer().also { message.write(it) }.toByteArray()
        val expected = bytes(2) + bytes(1) + ascii("s") +
            bytes(5, 0, 0, 0, 0, 0, 0, 0) + ByteArray(32) +
            bytes(2) + ascii("DE") + bytes(0, 0, 0, 0)
        assertArrayEquals(expected, written)
        assertEquals(message, MySpinIceMsg.read(BmwIceWire.Reader(written)))
    }

    @Test
    fun aReplyCarriesItsResultInAnEncapsulation() {
        val frame = BmwIceWire.reply(7) { int(0) }
        val reply = BmwIceWire.parse(frame) as Message.Reply
        assertEquals(7, reply.requestId)
        assertEquals(BmwIceWire.REPLY_OK, reply.status)
        assertEquals(0, reply.result!!.int())
    }

    @Test
    fun proxiesRoundTripAndANullProxyReadsAsNull() {
        val identity = Identity("1b2c", "")
        val written = BmwIceWire.Writer().proxy(identity).toByteArray()
        assertEquals(identity, BmwIceWire.Reader(written).proxy(11))
        assertNull(BmwIceWire.Reader(BmwIceWire.Writer().proxy(null).toByteArray()).proxy(11))
    }

    @Test
    fun aProxyWithAnEndpointIsSkippedPastItsEncapsulation() {
        // As the TFT may write its own session object: one opaque endpoint, then more data.
        val written = BmwIceWire.Writer()
            .identity(Identity("veh", "ConnectedRide")).strings(emptyList()).byte(0).bool(false)
            .byte(1).byte(0).byte(1).byte(1)
            .size(1).short(6).int(10).byte(1).byte(1).raw(bytes(9, 9, 9, 9))
            .string("after")
            .toByteArray()
        val reader = BmwIceWire.Reader(written)
        assertEquals(Identity("veh", "ConnectedRide"), reader.proxy(11))
        assertEquals("after", reader.string())
    }

    @Test
    fun longSizesUseTheEscapeByte() {
        val text = "x".repeat(300)
        val written = BmwIceWire.Writer().string(text).toByteArray()
        assertEquals(255, written[0].toInt() and 0xFF)
        assertEquals(text, BmwIceWire.Reader(written).string())
    }

    @Test
    fun credentialFieldsAreReadUpToTheNextSpace() {
        assertEquals("2W-Wifi-ab", BmwIceWire.field("DeviceName=2W-Wifi-ab Other=1", "DeviceName"))
        assertEquals("12345678", BmwIceWire.field("Pin=12345678", "Pin"))
        assertNull(BmwIceWire.field("Something=else", "Pin"))
    }

    @Test
    fun somethingThatIsNotIceIsRefused() {
        val failure = runCatching { BmwIceWire.frameSize(ascii("{\"id\":\"x\"}0000")) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().startsWith("not an Ice message"))
    }
}
