// MOTO-HUB receiver glue (uses AGPLv3 code ported from headunit-revived). Orchestrates the loopback
// "self-mode" Android Auto Projection receiver:
//   1. Listen on TCP 127.0.0.1:5288 (+ NSD _aawireless._tcp).
//   2. Launch Google Android Auto's WirelessStartupActivity pointed at 127.0.0.1:5288 (no VPN).
//   3. Accept the inbound socket, run the AAP version+SSL handshake, point the H.264 decoder at
//      the supplied encoder Surface, and start the message loop → AA video flows into the encoder.
package io.motohub.android.aa

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.view.Surface
import io.motohub.android.androidauto.AndroidAutoCapabilityProfile
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class AaReceiver(
    private val context: Context,
    private val encoderSurface: Surface,
    private val log: (String) -> Unit,
    private val onVideoReady: () -> Unit,
    private val onSessionEnded: (clean: Boolean) -> Unit,
    private val mapTouchToSource: (Int, Int) -> Pair<Int, Int>?,
    private val capabilityProfile: AndroidAutoCapabilityProfile,
) {
    companion object {
        const val PORT = 5288
    }

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    @Volatile private var transport: AapTransport? = null
    @Volatile private var connection: SocketAccessoryConnection? = null
    @Volatile private var videoReadyFired = false
    @Volatile private var input: AaInput? = null
    private val videoDecoder = VideoDecoder().apply {
        fallbackWidth = capabilityProfile.video.width
        fallbackHeight = capabilityProfile.video.height
        onFirstFrameListener = {
            if (!videoReadyFired) {
                videoReadyFired = true
                log("[AA] first decoded video frame received — signalling ready for bike hand-off")
                try { onVideoReady() } catch (failure: Exception) {
                    log("[AA] bike hand-off callback failed: ${failure.message}")
                }
            }
        }
        onFpsChanged = { fps ->
            log("[AA] decode fps=$fps")
        }
    }

    /** Ensure Conscrypt/AAP logging are wired before anything touches SSL. */
    fun start(): Boolean {
        if (running) { log("[AA] already running"); return true }
        if (!SingleKeyKeyManager.isAvailable(context)) {
            log("[AA] Android Auto identity is not included in this build")
            return false
        }
        running = true
        AaLog.sink = log
        ConscryptInitializer.initialize()

        try {
            serverSocket = ServerSocket(PORT).apply { reuseAddress = true }
            log("[AA] WirelessServer listening on :$PORT")
        } catch (e: Exception) {
            log("[AA] failed to bind :$PORT — ${e.message}")
            running = false
            return false
        }

        registerNsd()

        acceptThread = thread(name = "aa-accept", isDaemon = true) { acceptLoop() }
        // Self-mode (launching Google Android Auto) is triggered by MainActivity from the
        // foreground, via AaSelfMode.trigger(), to satisfy background-activity-launch rules.
        return true
    }

    fun stop() {
        running = false
        input = null
        try { transport?.stop() } catch (_: Exception) {
            try { transport?.quit() } catch (_: Exception) {}
        }
        transport = null
        try { connection?.disconnect() } catch (_: Exception) {}
        connection = null
        try { videoDecoder.stop("AaReceiver.stop") } catch (_: Exception) {}
        unregisterNsd()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptThread?.interrupt(); acceptThread = null
        AaLog.sink = null
        log("[AA] receiver stopped")
    }

    private fun acceptLoop() {
        val ss = serverSocket ?: return
        while (running) {
            val client = try {
                ss.accept()
            } catch (e: Exception) {
                if (running) log("[AA] accept ended: ${e.message}")
                break
            }
            log("[AA] <<< Android Auto connected from ${client.inetAddress?.hostAddress}")
            if (transport != null) {
                log("[AA] already have a session — dropping extra connection")
                try { client.close() } catch (_: Exception) {}
                continue
            }
            thread(name = "aa-session", isDaemon = true) { handleConnection(client) }
        }
    }

    private fun handleConnection(client: Socket) {
        val conn = SocketAccessoryConnection(client)
        connection = conn
        val t = AapTransport(
            videoDecoder = videoDecoder,
            context = context,
            androidAutoCapabilityProfile = capabilityProfile
        )
        t.onQuit = { clean ->
            log("[AA] transport quit (clean=$clean, userExit=${t.wasUserExit})")
            input = null
            transport = null
            try { conn.disconnect() } catch (_: Exception) {}
            connection = null
            onSessionEnded(clean)
        }
        transport = t

        // Bike touchscreen → Android Auto: EasyConnProber decodes dash touches (PXC cmdType 32) and
        // calls this sink with raw bike-canvas coords + a normalised action. Letterbox-map into AA
        // video space and forward over the AAP INPUT channel. Dropped if the point is in a black bar.
        input = AaInput(t, log)

        log("[AA] starting AAP handshake (version + SSL)…")
        if (!t.startHandshake(conn)) {
            log("[AA] handshake FAILED")
            transport = null
            try { conn.disconnect() } catch (_: Exception) {}
            connection = null
            return
        }
        log("[AA] handshake OK — pointing decoder at encoder surface and starting read loop")
        videoDecoder.setSurface(encoderSurface)
        t.startReading()
        log("[AA] read loop started — expecting ServiceDiscovery then video")
    }

    fun sendTouch(action: Int, canvasX: Int, canvasY: Int) {
        val activeInput = input ?: return
        val mapped = mapTouchToSource(canvasX, canvasY) ?: return
        if (action != AaInput.ACTION_MOVE) {
            log("[AA] touch action=$action bike=($canvasX,$canvasY) → AA=(${mapped.first},${mapped.second})")
        }
        activeInput.sendTouch(action, mapped.first, mapped.second)
    }

    fun sendSourceTouch(action: Int, sourceX: Int, sourceY: Int) {
        val activeInput = input ?: return
        activeInput.sendTouch(action, sourceX, sourceY)
    }

    private fun registerNsd() {
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            if (nsdManager == null) { log("[AA] NSD unavailable"); return }
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "AAWireless"
                serviceType = "_aawireless._tcp"
                port = PORT
            }
            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) = log("[AA] NSD registered: ${info.serviceName}")
                override fun onRegistrationFailed(info: NsdServiceInfo, err: Int) = log("[AA] NSD reg fail: $err")
                override fun onServiceUnregistered(info: NsdServiceInfo) = log("[AA] NSD unregistered")
                override fun onUnregistrationFailed(info: NsdServiceInfo, err: Int) = log("[AA] NSD unreg fail: $err")
            }
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            log("[AA] NSD register error: ${e.message}")
        }
    }

    private fun unregisterNsd() {
        try { registrationListener?.let { nsdManager?.unregisterService(it) } } catch (_: Exception) {}
        registrationListener = null
    }
}
