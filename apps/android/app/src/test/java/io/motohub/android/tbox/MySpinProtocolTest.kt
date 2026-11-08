// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import io.motohub.android.tbox.MySpinProtocol.Event
import io.motohub.android.tbox.MySpinWire.Packet
import io.motohub.android.tbox.MySpinWire.Writer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MySpinProtocolTest {

    private val sent = mutableListOf<Packet>()
    private val protocol = MySpinProtocol(send = { sent += it }, clock = { 1_700_000_000_000L })

    private fun dash(type: Int, writer: Writer = Writer()) = protocol.receive(Packet(type, writer.build()))

    private fun version(major: Int, minor: Int) =
        dash(MySpinProtocol.TYPE_CLIENT_VERSION, Writer().u16(major).u16(minor).u16(3))

    /** The Multistrada V4 test bike's projection area, JPEG preferred. */
    private fun clientInit(width: Int = 848, height: Int = 304, preferred: Int = 1, capabilities: Int = 1) =
        Writer().string("TFT").u16(width).u16(height).u16(150).u16(54).i32(0)
            .u8(2).u8(2).i32(preferred).u8(80).i32(capabilities)
            .u8(1).u16(7)
            .u8(2).u8(1).u8(2)

    private fun readySession(major: Int = 1, minor: Int = 2) {
        version(major, minor)
        dash(MySpinProtocol.TYPE_CLIENT_INIT, clientInit())
        dash(MySpinProtocol.TYPE_CLIENT_READY, Writer().u8(1))
        sent.clear()
    }

    @Test
    fun theVersionIsAcceptedAndTheSessionStartsInitialising() {
        val events = version(1, 2)
        assertEquals(listOf(Event.StateChanged(MySpinProtocol.State.INITIALIZING, MySpinProtocol.Generation.V1_2)), events)
        assertEquals(MySpinProtocol.TYPE_VERSION_ACCEPTED, sent.single().type)
        assertArrayEquals(byteArrayOf(1), sent.single().payload)
    }

    @Test(expected = MySpinProtocolException::class)
    fun anUnknownVersionEndsTheSession() {
        version(3, 0)
    }

    @Test(expected = MySpinProtocolException::class)
    fun anythingButTheVersionFirstEndsTheSession() {
        dash(MySpinProtocol.TYPE_CLIENT_INIT, clientInit())
    }

    @Test
    fun theClientInitIsAnsweredAndTheScreenIsReported() {
        version(1, 2)
        sent.clear()
        val events = dash(MySpinProtocol.TYPE_CLIENT_INIT, clientInit())

        val screen = (events.single() as Event.ScreenChanged).screen
        assertEquals(848, screen.width)
        assertEquals(304, screen.height)
        assertEquals(listOf(12, 14, 18), sent.map { it.type })
        // A 1.2 ServerInit opens with the server name.
        val serverInit = MySpinWire.Reader(sent[1].payload)
        assertEquals(MySpinProtocol.SERVER_NAME, serverInit.string())
        assertEquals(MySpinProtocol.State.INITIALIZING, protocol.state)
    }

    @Test
    fun bothSidesReadyEntersReadyAndSendsThePhoneTime() {
        version(1, 2)
        dash(MySpinProtocol.TYPE_CLIENT_INIT, clientInit())
        sent.clear()
        val events = dash(MySpinProtocol.TYPE_CLIENT_READY, Writer().u8(1))

        assertEquals(listOf(Event.StateChanged(MySpinProtocol.State.READY, MySpinProtocol.Generation.V1_2)), events)
        assertEquals(MySpinProtocol.TYPE_PHONE_TIME, sent.single().type)
        assertArrayEquals(Writer().u8(2).u16(4).u16(8).i64(1_700_000_000_000L).build(), sent.single().payload)
    }

    @Test
    fun theDashMayDeclareItselfReadyBeforeDescribingItsScreen() {
        version(1, 2)
        assertTrue(dash(MySpinProtocol.TYPE_CLIENT_READY, Writer().u8(1)).isEmpty())
        dash(MySpinProtocol.TYPE_CLIENT_INIT, clientInit())
        assertEquals(MySpinProtocol.State.READY, protocol.state)
    }

    @Test(expected = MySpinProtocolException::class)
    fun aDashThatRejectsTheInitialisationEndsTheSession() {
        version(1, 2)
        dash(MySpinProtocol.TYPE_CLIENT_READY, Writer().u8(0))
    }

    @Test
    fun aRequestedFrameGoesOutWithTheFullScreenGeometry() {
        readySession()
        val requested = dash(MySpinProtocol.TYPE_FRAME_REQUEST, Writer().u16(0).u16(0).u16(848).u16(304).u8(1))
        assertTrue(requested.single() is Event.FrameRequested)
        assertTrue(protocol.hasPendingFrameRequest)

        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 0xFF.toByte(), 0xD9.toByte())
        assertEquals(Event.FrameSent(jpeg.size), protocol.sendFrame(jpeg))

        val expected = Writer().u8(1).u16(0).u16(0).u16(848).u16(304).u8(2).u8(2)
            .i32(MySpinProtocol.COMPRESSION_JPEG).u8(80).u8(100).u8(100).i32(jpeg.size).bytes(jpeg).build()
        assertEquals(MySpinProtocol.TYPE_FRAME, sent.single().type)
        assertArrayEquals(expected, sent.single().payload)
        assertFalse(protocol.hasPendingFrameRequest)
    }

    @Test
    fun noFrameIsSentUnlessTheDashAskedForOne() {
        readySession()
        assertNull(protocol.sendFrame(byteArrayOf(1)))
        assertTrue(sent.isEmpty())

        dash(MySpinProtocol.TYPE_FRAME_REQUEST, Writer().u16(0).u16(0).u16(848).u16(304).u8(0))
        protocol.sendFrame(byteArrayOf(1))
        assertNull("one request buys one frame", protocol.sendFrame(byteArrayOf(2)))
        assertEquals(1, sent.size)
    }

    @Test
    fun aSecondRequestBeforeTheAnswerIsAbsorbed() {
        readySession()
        dash(MySpinProtocol.TYPE_FRAME_REQUEST, Writer().u16(0).u16(0).u16(848).u16(304).u8(0))
        assertTrue(dash(MySpinProtocol.TYPE_FRAME_REQUEST, Writer().u16(0).u16(0).u16(10).u16(10).u8(1)).isEmpty())
    }

    @Test
    fun aVersionTwoFrameCarriesTheScreenId() {
        readySession(major = 2, minor = 0)
        dash(MySpinProtocol.TYPE_FRAME_REQUEST, Writer().i32(0).u16(0).u16(0).u16(848).u16(304).u8(0))
        protocol.sendFrame(byteArrayOf(9))
        val frame = MySpinWire.Reader(sent.single().payload)
        assertEquals(0, frame.i32())
        assertEquals(1, frame.u8())
    }

    @Test
    fun aVersionTwoServerInitHasNoServerName() {
        version(2, 0)
        sent.clear()
        dash(MySpinProtocol.TYPE_CLIENT_INIT, clientInit())
        assertArrayEquals(
            Writer().u8(1).u8(1).i32(14).string("Android").string("").build(),
            sent.single { it.type == MySpinProtocol.TYPE_SERVER_INIT }.payload
        )
    }

    @Test(expected = MySpinProtocolException::class)
    fun aSecondaryScreenIsNotSupported() {
        readySession(major = 2, minor = 0)
        dash(MySpinProtocol.TYPE_FRAME_REQUEST, Writer().i32(1).u16(0).u16(0).u16(848).u16(304).u8(0))
    }

    @Test
    fun aPingIsAnsweredWithTheSameSequence() {
        readySession()
        assertTrue(dash(MySpinProtocol.TYPE_PING, Writer().i32(77)).isEmpty())
        assertEquals(MySpinProtocol.TYPE_PONG, sent.single().type)
        assertArrayEquals(Writer().i32(77).build(), sent.single().payload)
    }

    @Test
    fun aHandlebarClickIsAKeyEvent() {
        readySession()
        val events = dash(MySpinProtocol.TYPE_CUSTOM_DATA, Writer().u8(5).u16(4).u16(1).u8(12))
        assertEquals(listOf(Event.KeyInput(MySpinProtocol.Key.OK, MySpinProtocol.KeyAction.CLICK, 12, 4)), events)
    }

    @Test
    fun theRotaryKeysAreKnown() {
        assertEquals(MySpinProtocol.Key.ROTATE_CLOCKWISE, MySpinProtocol.Key.of(20))
        assertEquals(MySpinProtocol.Key.ROTATE_COUNTERCLOCKWISE, MySpinProtocol.Key.of(21))
        assertEquals(MySpinProtocol.KeyAction.LONG_PRESS, MySpinProtocol.KeyAction.of(3))
    }

    @Test
    fun anUnknownKeyKeepsItsRawValues() {
        readySession()
        val event = dash(MySpinProtocol.TYPE_CUSTOM_DATA, Writer().u8(5).u16(9).u16(1).u8(99)).single() as Event.KeyInput
        assertNull(event.key)
        assertNull(event.action)
        assertEquals(99, event.rawKey)
        assertEquals(9, event.rawAction)
    }

    @Test
    fun aVehicleDataValueKeepsItsUnsignedKey() {
        readySession()
        val events = dash(MySpinProtocol.TYPE_VEHICLE_DATA, Writer().i32(2_201_960_939L.toInt()).string("87").i32(0))
        assertEquals(listOf(Event.VehicleData(2_201_960_939L, "87", 0)), events)
    }

    @Test
    fun subscribingWritesTheCountThenEachKey() {
        readySession()
        protocol.subscribeVehicleData(listOf(2_201_960_939L, 1L))
        assertArrayEquals(
            Writer().u16(2).i32(2_201_960_939L.toInt()).i32(1).build(),
            sent.single { it.type == MySpinProtocol.TYPE_SUBSCRIBE_VEHICLE_DATA }.payload
        )
    }

    @Test
    fun theLanguageArrivesEvenBeforeTheSessionIsReady() {
        version(1, 2)
        assertEquals(listOf(Event.Language("ita")), dash(MySpinProtocol.TYPE_LANGUAGE, Writer().bytes("ita".toByteArray())))
    }

    @Test
    fun displayReadyIsAnnouncedOnce() {
        readySession()
        protocol.announceDisplayReady()
        protocol.announceDisplayReady()
        assertEquals(listOf(36, 36), sent.map { it.type })
        assertArrayEquals(byteArrayOf(2, 0), sent[0].payload)
        assertArrayEquals(byteArrayOf(4, 1), sent[1].payload)
    }

    @Test
    fun aPixelFormatChangeIsAcknowledged() {
        readySession()
        val events = dash(MySpinProtocol.TYPE_SET_PIXEL_FORMAT, Writer().u8(4).u8(1).u8(60))
        assertEquals(4, (events.single() as Event.ScreenChanged).screen.pixelFormat)
        assertEquals(MySpinProtocol.TYPE_SET_PIXEL_FORMAT_ACK, sent.single().type)
    }

    @Test(expected = MySpinProtocolException::class)
    fun aDashThatWantsOnlyZlibIsRefusedClearly() {
        version(1, 2)
        dash(MySpinProtocol.TYPE_CLIENT_INIT, clientInit(preferred = 2, capabilities = 2))
    }

    @Test(expected = MySpinProtocolException::class)
    fun switchingAwayFromJpegMidSessionIsRefused() {
        readySession()
        dash(MySpinProtocol.TYPE_SET_COMPRESSION, Writer().i32(4))
    }

    @Test
    fun jpegIsAcceptedWhenPreferredListedOrDefault() {
        fun screen(preferred: Int, caps: Int) = MySpinProtocol.Screen("t", 1, 1, 0, 0, 2, 2, preferred, 0, caps)
        assertTrue(MySpinProtocol.acceptsJpeg(screen(1, 0)))
        assertTrue(MySpinProtocol.acceptsJpeg(screen(0, 0)))
        assertTrue(MySpinProtocol.acceptsJpeg(screen(0, 3)))
        assertFalse(MySpinProtocol.acceptsJpeg(screen(0, 6)))
        assertFalse(MySpinProtocol.acceptsJpeg(screen(2, 3)))
    }

    @Test(expected = MySpinProtocolException::class)
    fun anOversizedScreenIsRefused() {
        version(1, 2)
        dash(MySpinProtocol.TYPE_CLIENT_INIT, clientInit(width = 4096, height = 4096))
    }

    @Test
    fun anUnknownPacketIsReportedNotFatal() {
        readySession()
        assertEquals(listOf(Event.Unhandled(200, 2)), dash(200, Writer().u16(1)))
    }
}
