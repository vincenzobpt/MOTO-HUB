// Ported from headunit-revived (AGPLv3): aap/AapSslContext.kt
// Pure-JVM SSLEngine driver for the AAP TLS handshake tunnelled inside AAP messages.
// No native code. Uses Conscrypt as provider when available (falls back to platform TLS).
package io.motohub.android.aa

import java.nio.ByteBuffer
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult

class AapSslContext(keyManager: SingleKeyKeyManager) : AapSsl {
    private val sslContext: SSLContext = createSslContext(keyManager)
    private lateinit var sslEngine: SSLEngine
    private lateinit var txBuffer: ByteBuffer
    private lateinit var rxBuffer: ByteBuffer

    @Volatile var isUserDisconnect = false

    override fun performHandshake(connection: AccessoryConnection): Boolean {
        if (prepare() < 0) return false

        var pendingTlsData = ByteArray(0)
        val deadline = android.os.SystemClock.elapsedRealtime() + SSL_HANDSHAKE_TIMEOUT_MS

        while (getHandshakeStatus() != SSLEngineResult.HandshakeStatus.FINISHED &&
            getHandshakeStatus() != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {

            if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                AaLog.e("SSL Handshake: Timed out after ${SSL_HANDSHAKE_TIMEOUT_MS}ms")
                return false
            }

            when (getHandshakeStatus()) {
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    if (pendingTlsData.isEmpty()) {
                        val messageData = readAapMessage(connection) ?: return false
                        pendingTlsData = messageData
                    }

                    rxBuffer.clear()
                    val data = ByteBuffer.wrap(pendingTlsData)
                    val result = sslEngine.unwrap(data, rxBuffer)
                    runDelegatedTasks(result, sslEngine)

                    when (result.status) {
                        SSLEngineResult.Status.OK -> {
                            pendingTlsData = if (data.hasRemaining())
                                ByteArray(data.remaining()).also { data.get(it) }
                            else ByteArray(0)
                        }
                        SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                            val nextMessage = readAapMessage(connection) ?: return false
                            pendingTlsData += nextMessage
                            AaLog.d("SSL Handshake: buffered ${pendingTlsData.size} B after underflow")
                        }
                        else -> {
                            AaLog.e("SSL Handshake: unwrap failed with status ${result.status}")
                            return false
                        }
                    }
                }

                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    val handshakeData = handshakeRead()
                    val bio = Messages.createRawMessage(0, 3, 3, handshakeData)
                    if (connection.sendBlocking(bio, bio.size, 2000) < 0) {
                        AaLog.e("SSL Handshake: Send failed")
                        return false
                    }
                }

                SSLEngineResult.HandshakeStatus.NEED_TASK -> runDelegatedTasks()

                else -> {
                    AaLog.e("SSL Handshake: Unexpected status ${getHandshakeStatus()}")
                    return false
                }
            }
        }

        val sessionId = sslEngine.session?.id
        if (sessionId != null && sessionId.isNotEmpty()) {
            AaLog.i("SSL handshake complete. Session id: ${android.util.Base64.encodeToString(sessionId, android.util.Base64.NO_WRAP)}")
        } else {
            AaLog.i("SSL handshake complete. No session id (full handshake).")
        }
        return true
    }

    /** Reads a single complete AAP message from the connection (respects AAP framing). */
    private fun readAapMessage(connection: AccessoryConnection): ByteArray? {
        val header = ByteArray(6)
        if (connection.recvBlocking(header, 6, 2000, true) != 6) {
            AaLog.e("SSL Handshake: Failed to read AAP header")
            return null
        }
        // Header: [0]=Channel, [1]=Flags, [2..3]=Length (BE), [4..5]=Type.
        val totalLength = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
        val payloadLength = totalLength - 2 // minus the 2-byte type field
        if (payloadLength < 0 || payloadLength > Messages.DEF_BUFFER_LENGTH) {
            AaLog.e("SSL Handshake: Invalid AAP payload length: $payloadLength")
            return null
        }
        val payload = ByteArray(payloadLength)
        if (connection.recvBlocking(payload, payloadLength, 2000, true) != payloadLength) {
            AaLog.e("SSL Handshake: Failed to read AAP payload ($payloadLength bytes)")
            return null
        }
        return payload
    }

    private fun prepare(): Int {
        sslEngine = sslContext.createSSLEngine("android-auto", 5277).apply {
            useClientMode = true
            session.also {
                val appBufferMax = it.applicationBufferSize
                val netBufferMax = it.packetBufferSize
                txBuffer = ByteBuffer.allocateDirect(netBufferMax)
                rxBuffer = ByteBuffer.allocateDirect(Messages.DEF_BUFFER_LENGTH.coerceAtLeast(appBufferMax + 50))
            }
        }
        sslEngine.beginHandshake()
        return 0
    }

    override fun postHandshakeReset() {
        txBuffer.clear()
        rxBuffer.clear()
    }

    override fun release() { /* SSLEngine is GC'd */ }

    private fun getHandshakeStatus(): SSLEngineResult.HandshakeStatus = sslEngine.handshakeStatus

    private fun runDelegatedTasks() {
        if (sslEngine.handshakeStatus === SSLEngineResult.HandshakeStatus.NEED_TASK) {
            var runnable: Runnable? = sslEngine.delegatedTask
            while (runnable != null) {
                runnable.run()
                runnable = sslEngine.delegatedTask
            }
            if (sslEngine.handshakeStatus === SSLEngineResult.HandshakeStatus.NEED_TASK) {
                throw Exception("handshake shouldn't need additional tasks")
            }
        }
    }

    private fun handshakeRead(): ByteArray {
        txBuffer.clear()
        val result = sslEngine.wrap(emptyArray(), txBuffer)
        runDelegatedTasks(result, sslEngine)
        val resultBuffer = ByteArray(result.bytesProduced())
        txBuffer.flip()
        txBuffer.get(resultBuffer)
        return resultBuffer
    }

    override fun decrypt(start: Int, length: Int, buffer: ByteArray): ByteArrayWithLimit? {
        synchronized(this) {
            if (!::sslEngine.isInitialized || !::rxBuffer.isInitialized) {
                AaLog.w("SSL Decrypt: Not initialized yet")
                return null
            }
            try {
                rxBuffer.clear()
                val encrypted = ByteBuffer.wrap(buffer, start, length)
                val result = sslEngine.unwrap(encrypted, rxBuffer)
                runDelegatedTasks(result, sslEngine)
                if (AaLog.LOG_VERBOSE || result.bytesProduced() == 0) {
                    AaLog.d("SSL Decrypt Status: ${result.status}, Produced: ${result.bytesProduced()}, Consumed: ${result.bytesConsumed()}")
                }
                val resultBuffer = ByteArray(result.bytesProduced())
                rxBuffer.flip()
                rxBuffer.get(resultBuffer)
                return ByteArrayWithLimit(resultBuffer, resultBuffer.size)
            } catch (e: Exception) {
                // Detect the "magic garbage" (all-0xFF) clean-disconnect signal.
                if (length >= 16) {
                    var allFF = true
                    for (i in 0 until 16) {
                        if (buffer[start + i] != 0xFF.toByte()) { allFF = false; break }
                    }
                    if (allFF) {
                        AaLog.i("SSL Decrypt: Magic Garbage detected. Marking as clean user disconnect.")
                        isUserDisconnect = true
                    }
                }
                if (!isUserDisconnect) AaLog.e("SSL Decrypt failed", e)
                return null
            }
        }
    }

    override fun encrypt(offset: Int, length: Int, buffer: ByteArray): ByteArrayWithLimit? {
        synchronized(this) {
            if (!::sslEngine.isInitialized || !::txBuffer.isInitialized) {
                AaLog.w("SSL Encrypt: Not initialized yet")
                return null
            }
            try {
                txBuffer.clear()
                val byteBuffer = ByteBuffer.wrap(buffer, offset, length)
                val result = sslEngine.wrap(byteBuffer, txBuffer)
                runDelegatedTasks(result, sslEngine)
                val resultBuffer = ByteArray(result.bytesProduced() + offset)
                txBuffer.flip()
                txBuffer.get(resultBuffer, offset, result.bytesProduced())
                return ByteArrayWithLimit(resultBuffer, resultBuffer.size)
            } catch (e: Exception) {
                AaLog.e("SSL Encrypt failed", e)
                return null
            }
        }
    }

    private fun runDelegatedTasks(result: SSLEngineResult, engine: SSLEngine) {
        if (result.handshakeStatus === SSLEngineResult.HandshakeStatus.NEED_TASK) {
            var runnable: Runnable? = engine.delegatedTask
            while (runnable != null) {
                runnable.run()
                runnable = engine.delegatedTask
            }
            if (engine.handshakeStatus === SSLEngineResult.HandshakeStatus.NEED_TASK) {
                throw Exception("handshake shouldn't need additional tasks")
            }
        }
    }

    companion object {
        private const val SSL_HANDSHAKE_TIMEOUT_MS = 15_000L

        private fun createSslContext(keyManager: SingleKeyKeyManager): SSLContext {
            val providerName = ConscryptInitializer.getProviderName()
            val sslContext = if (providerName != null) {
                try {
                    AaLog.d("Creating SSLContext with Conscrypt provider")
                    SSLContext.getInstance("TLS", providerName)
                } catch (e: Exception) {
                    AaLog.w("Failed to create SSLContext with Conscrypt, using default", e)
                    SSLContext.getInstance("TLS")
                }
            } else {
                AaLog.d("Creating SSLContext with default provider")
                SSLContext.getInstance("TLS")
            }
            return sslContext.apply {
                init(arrayOf(keyManager), arrayOf(NoCheckTrustManager()), null)
            }
        }
    }
}
