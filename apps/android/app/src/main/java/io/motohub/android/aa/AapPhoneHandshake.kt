package io.motohub.android.aa

import android.content.Context
import io.motohub.android.aa.proto.Control
import io.motohub.android.session.ProjectionEventLog
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal AAP "phone" role handshake: MOTO-HUB is the video SOURCE, the physical USB head unit
 * is the video SINK - the inverse of AapTransport/AaReceiver, which make MOTO-HUB masquerade as
 * a head unit for Google's own Android Auto app (see SocketAccessoryConnection). This stage only
 * proves out version exchange, SSL, and service discovery - enough to
 * confirm the physical dongle accepts MOTO-HUB's identity before building an outbound video
 * channel on top. Deliberately does not reuse AapTransport: its handshake()/AapMessageHandlerType
 * are hardwired for the head-unit role (sends VersionRequest first, TLS client, dispatches
 * inbound ServiceDiscoveryRequest/MediaSetupRequest) - every one of those steps is inverted here.
 */
object AapPhoneHandshake {
    private const val TAG = "AAP_PHONE"
    private const val MSG_VERSION_REQUEST = 1
    private const val MSG_VERSION_RESPONSE = 2
    private const val READ_TIMEOUT_MS = 8000

    class Outcome(val success: Boolean, val detail: String)

    fun run(context: Context, connection: AccessoryConnection): Outcome {
        val pump = AtomicBoolean(false)
        var pumpThread: Thread? = null
        try {
            // Attempt 2: MOTO-HUB is the side that opened the AOA accessory, so it sends
            // VERSION_REQUEST first here. Attempt 1 (wait for the dongle to speak first,
            // mirroring the head-unit/phone role inversion literally) timed out after 8s with
            // zero bytes from the dongle, so that assumption was wrong.
            ProjectionEventLog.record(TAG, "Sending VERSION_REQUEST to the head unit.")
            val request = Messages.versionRequest
            if (connection.sendBlocking(request, request.size, 2000) < 0) {
                return Outcome(false, "Failed to send VERSION_REQUEST.")
            }
            ProjectionEventLog.record(TAG, "Waiting for VERSION_RESPONSE from the head unit.")
            val header = ByteArray(6)
            if (connection.recvBlocking(header, 6, READ_TIMEOUT_MS, true) != 6) {
                return Outcome(false, "Timed out waiting for VERSION_RESPONSE header.")
            }
            val length = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
            val type = ((header[4].toInt() and 0xFF) shl 8) or (header[5].toInt() and 0xFF)
            if (type != MSG_VERSION_RESPONSE) {
                return Outcome(false, "Expected VERSION_RESPONSE (type=2), got type=$type.")
            }
            val payloadLen = (length - 2).coerceAtLeast(0)
            if (payloadLen > 0) {
                connection.recvBlocking(ByteArray(payloadLen), payloadLen, 2000, true)
            }
            ProjectionEventLog.record(TAG, "Received VERSION_RESPONSE.")

            // Same reasoning as sending VERSION_REQUEST first: MOTO-HUB is the side that opened
            // the accessory, so it drives both the version exchange and the TLS handshake, same
            // as AapTransport's head-unit role does against Google's Android Auto app.
            val ssl = AapSslContext(SingleKeyKeyManager(context), useClientMode = true)
            ProjectionEventLog.record(TAG, "Starting SSL handshake (TLS client mode).")
            if (!ssl.performHandshake(connection)) {
                return Outcome(false, "SSL handshake failed - the dongle likely rejected MOTO-HUB's certificate.")
            }
            ssl.postHandshakeReset()
            ProjectionEventLog.record(TAG, "SSL handshake complete.")

            val inbox = LinkedBlockingQueue<AapMessage>()
            val handler = object : AapMessageHandler {
                override fun handle(message: AapMessage) {
                    inbox.put(message)
                }
            }
            val reader = AapReadMultipleMessages(connection, ssl, handler)
            pump.set(true)
            pumpThread = Thread({
                while (pump.get()) {
                    if (reader.read() < 0) { pump.set(false) }
                }
            }, "AapPhoneHandshake-pump").apply { isDaemon = true; start() }

            val authMsg = inbox.poll(READ_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
                ?: return Outcome(false, "Timed out waiting for AUTH_COMPLETE.")
            ProjectionEventLog.record(TAG, "Received message type=${authMsg.type} after SSL (expected AUTH_COMPLETE=4).")

            val discoveryRequest = Control.ServiceDiscoveryRequest.newBuilder()
                .setPhoneName("MOTO-HUB")
                .setPhoneBrand("MOTO-HUB")
                .build()
            val discoveryMessage = AapMessage(
                Channel.ID_CTR,
                Control.ControlMsgType.MESSAGE_SERVICE_DISCOVERY_REQUEST_VALUE,
                discoveryRequest
            )
            if (!sendEncrypted(connection, ssl, discoveryMessage)) {
                return Outcome(false, "Failed to send SERVICE_DISCOVERY_REQUEST.")
            }
            ProjectionEventLog.record(TAG, "Sent SERVICE_DISCOVERY_REQUEST.")

            val discoveryResponse = inbox.poll(READ_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
                ?: return Outcome(false, "Timed out waiting for SERVICE_DISCOVERY_RESPONSE.")
            if (discoveryResponse.type != Control.ControlMsgType.MESSAGE_SERVICE_DISCOVERY_RESPONSE_VALUE) {
                return Outcome(false, "Expected SERVICE_DISCOVERY_RESPONSE, got type=${discoveryResponse.type}.")
            }
            val parsed = discoveryResponse.parse(Control.ServiceDiscoveryResponse.newBuilder())
            ProjectionEventLog.record(
                TAG,
                "SERVICE_DISCOVERY_RESPONSE: make=${parsed.make} model=${parsed.model} " +
                    "headUnitMake=${parsed.headUnitMake} headUnitModel=${parsed.headUnitModel} " +
                    "services=${parsed.servicesCount}."
            )
            return Outcome(true, "Handshake and service discovery succeeded.")
        } catch (e: Exception) {
            return Outcome(false, "Exception during phone handshake: ${e.message}")
        } finally {
            pump.set(false)
            pumpThread?.interrupt()
        }
    }

    private fun sendEncrypted(connection: AccessoryConnection, ssl: AapSsl, message: AapMessage): Boolean {
        val ba = ssl.encrypt(AapMessage.HEADER_SIZE, message.size - AapMessage.HEADER_SIZE, message.data) ?: return false
        ba.data[0] = message.data[0]
        ba.data[1] = message.data[1]
        Utils.intToBytes(ba.limit - AapMessage.HEADER_SIZE, 2, ba.data)
        return connection.sendBlocking(ba.data, ba.limit, 2000) >= 0
    }
}
