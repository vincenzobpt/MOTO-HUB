// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MySpinSessionTest {

    private val dash = FakeMySpinDash()
    private val ready = CountDownLatch(1)
    private val closed = CountDownLatch(1)
    private val keyed = CountDownLatch(1)

    @Volatile private var screen: MySpinProtocol.Screen? = null
    @Volatile private var closeError: Throwable? = null
    @Volatile private var key: MySpinProtocol.Event.KeyInput? = null

    private val listener = object : MySpinSession.Listener {
        override fun onReady(screen: MySpinProtocol.Screen) {
            this@MySpinSessionTest.screen = screen
            ready.countDown()
        }

        override fun onKey(event: MySpinProtocol.Event.KeyInput) {
            key = event
            keyed.countDown()
        }

        override fun onClosed(error: Throwable?) {
            closeError = error
            closed.countDown()
        }
    }

    private var session: MySpinSession? = null

    private fun open(
        idleTimeoutMillis: Long = MySpinSession.IDLE_TIMEOUT_MS,
        pingIntervalMillis: Long = MySpinSession.PING_INTERVAL_MS,
        staleFrameResendMillis: Long = MySpinSession.STALE_FRAME_RESEND_MS
    ): MySpinSession {
        val opened = MySpinSession(
            dash.connectPhone(),
            listener,
            idleTimeoutMillis = idleTimeoutMillis,
            pingIntervalMillis = pingIntervalMillis,
            staleFrameResendMillis = staleFrameResendMillis,
            tickMillis = 20
        )
        session = opened
        opened.start()
        dash.handshake()
        assertTrue("session never became ready", ready.await(3, TimeUnit.SECONDS))
        return opened
    }

    @After
    fun tearDown() {
        session?.close()
        dash.close()
    }

    @Test
    fun theHandshakeReportsTheScreenAndAnnouncesTheDisplay() {
        open()
        assertEquals(848, screen!!.width)
        assertEquals(304, screen!!.height)
        dash.expect(MySpinProtocol.TYPE_SERVER_STATE)
        dash.expect(MySpinProtocol.TYPE_SERVER_STATE)
    }

    @Test
    fun aFrameOfferedBeforeTheRequestIsServedWhenAsked() {
        val opened = open()
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 0xFF.toByte(), 0xD9.toByte())
        assertTrue(opened.offerFrame(jpeg))
        dash.requestFrame()
        assertArrayEquals(jpeg, dash.frameBytes(dash.expect(MySpinProtocol.TYPE_FRAME)))
    }

    @Test
    fun aRequestWaitsForTheNextOffer() {
        val opened = open()
        dash.requestFrame()
        Thread.sleep(100)
        assertEquals(0, opened.sentFrameCount)
        opened.offerFrame(byteArrayOf(7, 7))
        assertArrayEquals(byteArrayOf(7, 7), dash.frameBytes(dash.expect(MySpinProtocol.TYPE_FRAME)))
    }

    @Test
    fun onlyTheNewestOfferedFrameIsSent() {
        val opened = open()
        opened.offerFrame(byteArrayOf(1))
        opened.offerFrame(byteArrayOf(2))
        dash.requestFrame()
        assertArrayEquals(byteArrayOf(2), dash.frameBytes(dash.expect(MySpinProtocol.TYPE_FRAME)))
    }

    @Test
    fun aStillScreenIsSentAgainAfterTheStaleInterval() {
        val opened = open(staleFrameResendMillis = 100)
        opened.offerFrame(byteArrayOf(5))
        dash.requestFrame()
        dash.expect(MySpinProtocol.TYPE_FRAME)
        dash.requestFrame()
        // No new picture: the same one comes back rather than leaving the dash waiting.
        assertArrayEquals(byteArrayOf(5), dash.frameBytes(dash.expect(MySpinProtocol.TYPE_FRAME)))
        assertEquals(2, opened.sentFrameCount)
    }

    @Test
    fun dashKeysReachTheListener() {
        open()
        dash.pressKey(MySpinProtocol.Key.ROTATE_CLOCKWISE, MySpinProtocol.KeyAction.CLICK)
        assertTrue(keyed.await(3, TimeUnit.SECONDS))
        assertEquals(MySpinProtocol.Key.ROTATE_CLOCKWISE, key!!.key)
        assertEquals(MySpinProtocol.KeyAction.CLICK, key!!.action)
    }

    @Test
    fun thePhonePingsWhileReady() {
        open(pingIntervalMillis = 50)
        dash.expect(MySpinProtocol.TYPE_PING)
    }

    @Test
    fun aSilentDashEndsTheSessionWithATimeout() {
        open(idleTimeoutMillis = 300)
        assertTrue(closed.await(3, TimeUnit.SECONDS))
        assertTrue(closeError is SocketTimeoutException)
    }

    @Test
    fun aDashThatHangsUpEndsTheSession() {
        val opened = open()
        // Closing with the phone's packets still unread makes TCP reset rather than end cleanly,
        // as a dash switched off mid-session can: either way the session must end.
        dash.close()
        assertTrue(closed.await(3, TimeUnit.SECONDS))
        assertFalse(opened.isOpen)
        assertFalse("frames are refused once the session is over", opened.offerFrame(byteArrayOf(1)))
    }
}
