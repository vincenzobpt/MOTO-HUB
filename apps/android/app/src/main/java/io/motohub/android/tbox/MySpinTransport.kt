// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import io.motohub.android.feature.controls.MediaButtonBridge
import io.motohub.android.session.ProjectionEventLog
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * [TBoxTransport] for Bosch mySPIN dashes (Ducati Multistrada V4 "Ducati Connect").
 *
 * By the time [discover] runs, the Bluetooth step has already handed over the Wi-Fi credentials
 * and the phone has joined the dash network: that happens in the link step, which leaves the
 * still-open control link in [MySpinBluetoothLink.current] so the dash can be told the phone is
 * connected. [stop] closes it. Then:
 * - [discover] waits for the dash's UDP announcement and returns the TCP endpoint it names;
 * - [start] connects, runs the handshake, and emits the dash's screen as the video area;
 * - frames arrive as JPEG stills through [offerStillFrame] and are served when the dash asks.
 *
 * There is no H.264 path: [offerAccessUnit] always refuses.
 */
class MySpinTransport : TBoxTransport {

    private val mutableEvents = MutableSharedFlow<TBoxEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val events: Flow<TBoxEvent> = mutableEvents.asSharedFlow()

    @Volatile private var link: TBoxLink? = null
    @Volatile private var session: MySpinSession? = null
    @Volatile private var phaseOrigin = 0L
    private val refusedAccessUnitLogged = AtomicBoolean()

    override suspend fun discover(link: TBoxLink, expectedModelId: String?): Result<TBoxHost> =
        withContext(Dispatchers.IO) {
            runCatching {
                closeSession()
                this@MySpinTransport.link = link
                phaseOrigin = System.currentTimeMillis()
                // Listen before telling the dash, so an announcement sent right away is not missed.
                MySpinAnnouncementListener(bindToNetwork = { socket -> link.network?.bindSocket(socket) }).use { listener ->
                    MySpinBluetoothLink.current?.reportPhoneWlan(MySpinControl.PhoneWlan.CONNECTED)
                        ?: ProjectionEventLog.warning(TAG, "No Bluetooth control link; the dash was not told the phone joined.")
                    phase("waiting for the UDP announcement on port ${MySpinDiscovery.PORT}")
                    val endpoint = listener.await(ANNOUNCEMENT_TIMEOUT_MS)
                        ?: error("the dash did not announce mySPIN on its Wi-Fi in ${ANNOUNCEMENT_TIMEOUT_MS / 1000} s")
                    phase("announcement from ${endpoint.sourceAddress}: ${endpoint.host}:${endpoint.port} v${endpoint.version} ${endpoint.identificationJson}")
                    TBoxHost(endpoint.host, endpoint.port, PACKAGE_TAG)
                }
            }
        }

    override suspend fun start(host: TBoxHost): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            closeSession()
            val activeLink = link ?: error("discover() must run before start()")
            val ready = CompletableDeferred<MySpinProtocol.Screen>()
            val socket = activeLink.createSocket()
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host.ipAddress, host.port), CONNECT_TIMEOUT_MS)
                phase("TCP connected to ${host.ipAddress}:${host.port}")
                val opened = MySpinSession(socket, sessionListener(ready))
                session = opened
                opened.start()
                val screen = withTimeout(HANDSHAKE_TIMEOUT_MS) { ready.await() }
                phase("session ready: ${screen.width}x${screen.height} \"${screen.name}\", pixel ${screen.pixelFormat}/${screen.pixelEndianness}, compression pref ${screen.preferredCompression} caps ${screen.compressionCapabilities}")
                mutableEvents.tryEmit(TBoxEvent.VideoArea(screen.width, screen.height))
                mutableEvents.tryEmit(TBoxEvent.VideoStreamStart)
                Unit
            } catch (failure: Throwable) {
                closeSession()
                runCatching { socket.close() }
                throw failure
            }
        }
    }

    private fun sessionListener(ready: CompletableDeferred<MySpinProtocol.Screen>) = object : MySpinSession.Listener {
        private var firstRequestLogged = false
        private var firstFrameLogged = false
        private val handlebar = MySpinHandlebar()

        override fun onEvent(event: MySpinProtocol.Event) {
            when (event) {
                is MySpinProtocol.Event.FrameRequested -> if (!firstRequestLogged) {
                    firstRequestLogged = true
                    phase("first frame requested: ${event.request}")
                }
                is MySpinProtocol.Event.FrameSent -> if (!firstFrameLogged) {
                    firstFrameLogged = true
                    phase("first frame sent (${event.bytes} bytes)")
                }
                is MySpinProtocol.Event.KeyInput -> Unit
                else -> ProjectionEventLog.debug(TAG, "Event: $event")
            }
        }

        override fun onReady(screen: MySpinProtocol.Screen) {
            ready.complete(screen)
        }

        override fun onKey(event: MySpinProtocol.Event.KeyInput) {
            val gesture = handlebar.gestureFor(event.key, event.action)
            val delivered = gesture?.let(MediaButtonBridge::injectGestureToActive) ?: false
            ProjectionEventLog.record(
                TAG,
                "Dash key ${event.key ?: event.rawKey}/${event.action ?: event.rawAction} -> " +
                    (gesture?.label ?: "ignored") + if (gesture != null && !delivered) " (no live handlebar bridge)" else ""
            )
        }

        override fun onClosed(error: Throwable?) {
            ready.completeExceptionally(error ?: IllegalStateException("the dash closed the mySPIN session"))
            if (error != null) {
                ProjectionEventLog.warning(TAG, "mySPIN session ended: ${error.message}", error)
                mutableEvents.tryEmit(TBoxEvent.FatalError("Ducati Connect: ${error.message}"))
            }
            mutableEvents.tryEmit(TBoxEvent.Stopped)
        }
    }

    override fun offerStillFrame(jpeg: ByteArray, frameId: Int): Boolean = session?.offerFrame(jpeg) ?: false

    override fun offerAccessUnit(avcc: ByteArray): Boolean {
        if (refusedAccessUnitLogged.compareAndSet(false, true)) {
            ProjectionEventLog.warning(TAG, "mySPIN takes JPEG stills only; an H.264 access unit was refused.")
        }
        return false
    }

    override suspend fun stop() {
        closeSession()
        link = null
        // Every session end reaches here, on every bike; with no Ducati connected this is a no-op.
        MySpinBluetoothLink.closeCurrent()
    }

    private fun closeSession() {
        session?.close()
        session = null
    }

    private fun phase(message: String) {
        ProjectionEventLog.record(TAG, "MYSPIN phase +${System.currentTimeMillis() - phaseOrigin}ms: $message")
    }

    companion object {
        const val TAG = MySpinBluetoothLink.TAG
        const val PACKAGE_TAG = "mySPIN"
        const val ANNOUNCEMENT_TIMEOUT_MS = 60_000L
        const val CONNECT_TIMEOUT_MS = 10_000
        const val HANDSHAKE_TIMEOUT_MS = 15_000L
    }
}
