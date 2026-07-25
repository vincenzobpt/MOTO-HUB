package io.motohub.android.feature.controls

import io.motohub.android.session.ProjectionEventLog
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.json.JSONObject

/**
 * Debug-only control socket used by the macOS T-Box simulator to inject logical
 * handlebar gestures. Real motorcycles still use Bluetooth AVRCP/media events.
 */
class SimulatorHandlebarBridge(
    private val targetName: String,
    private val logTag: String
) {
    private val stopped = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var worker: Thread? = null

    fun start() {
        if (worker != null) return
        stopped.set(false)
        worker = thread(name = "MotoHubSimulatorHandlebar", isDaemon = true) {
            runServer()
        }
    }

    fun stop() {
        stopped.set(true)
        runCatching { serverSocket?.close() }
        serverSocket = null
        worker = null
    }

    private fun runServer() {
        runCatching {
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(SIMULATOR_HANDLEBAR_PORT))
                socket.soTimeout = ACCEPT_TIMEOUT_MILLIS
                serverSocket = socket
                ProjectionEventLog.record(
                    logTag,
                    "Simulator handlebar bridge listening on TCP $SIMULATOR_HANDLEBAR_PORT for $targetName."
                )
                while (!stopped.get()) {
                    try {
                        socket.accept().use(::handleClient)
                    } catch (_: SocketTimeoutException) {
                        Unit
                    } catch (failure: Exception) {
                        // A single flaky client (reset connection, malformed request) must
                        // not take down the whole debug listener - log and keep accepting.
                        if (!stopped.get()) {
                            ProjectionEventLog.warning(
                                logTag,
                                "Simulator handlebar client error: ${failure.message}."
                            )
                        }
                    }
                }
            }
        }.onFailure { failure ->
            if (!stopped.get()) {
                ProjectionEventLog.warning(
                    logTag,
                    "Simulator handlebar bridge unavailable: ${failure.message}."
                )
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.soTimeout = CLIENT_TIMEOUT_MILLIS
        val reader = BufferedReader(InputStreamReader(client.getInputStream()))
        val requestLine = reader.readLine().orEmpty()
        val parts = requestLine.split(" ")
        if (parts.size < 2 || parts[0] != "POST" || parts[1] != "/handlebar") {
            writeResponse(client, 404, "Not found")
            return
        }

        var contentLength = 0
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            if (line.substring(0, separator).equals("Content-Length", ignoreCase = true)) {
                contentLength = line.substring(separator + 1).trim().toIntOrNull() ?: 0
            }
        }
        if (contentLength <= 0 || contentLength > MAX_BODY_BYTES) {
            writeResponse(client, 400, "Invalid body")
            return
        }
        val body = CharArray(contentLength)
        val read = reader.read(body)
        if (read != contentLength) {
            writeResponse(client, 400, "Incomplete body")
            return
        }
        val gestureId = runCatching { JSONObject(String(body)).optString("gesture") }
            .getOrDefault("")
            .trim()
        val gesture = handlebarGestureForSimulatorId(gestureId)
        if (gesture == null) {
            writeResponse(client, 400, "Unknown gesture")
            return
        }
        val injected = MediaButtonBridge.injectGesture(targetName, gesture)
        if (!injected) {
            writeResponse(client, 409, "No active target")
            return
        }
        ProjectionEventLog.record(logTag, "Simulator handlebar gesture injected: ${gesture.label}.")
        writeResponse(client, 204, "")
    }

    private fun writeResponse(client: Socket, status: Int, body: String) {
        val reason = when (status) {
            204 -> "No Content"
            400 -> "Bad Request"
            404 -> "Not Found"
            409 -> "Conflict"
            else -> "Error"
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Connection: close\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("\r\n")
        }.toByteArray(Charsets.UTF_8)
        client.getOutputStream().apply {
            write(headers)
            if (bytes.isNotEmpty()) write(bytes)
            flush()
        }
    }

    companion object {
        const val SIMULATOR_HANDLEBAR_PORT = 42081
        private const val ACCEPT_TIMEOUT_MILLIS = 500
        private const val CLIENT_TIMEOUT_MILLIS = 1_500
        private const val MAX_BODY_BYTES = 512
    }
}

internal fun handlebarGestureForSimulatorId(value: String): HandlebarGesture? {
    val id = value.trim()
    return HandlebarGesture.entries.firstOrNull { it.id == id }
}
