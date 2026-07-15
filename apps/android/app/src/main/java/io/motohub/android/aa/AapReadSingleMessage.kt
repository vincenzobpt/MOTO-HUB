// Ported from headunit-revived (AGPLv3): aap/AapReadSingleMessage.kt
package io.motohub.android.aa

internal class AapReadSingleMessage(connection: AccessoryConnection, ssl: AapSsl, handler: AapMessageHandler)
    : AapRead.Base(connection, ssl, handler) {

    private val recvHeader = AapMessageIncoming.EncryptedHeader()
    // 4MB to handle large I-frames.
    private val msgBuffer = ByteArray(4 * 1024 * 1024)
    private val fragmentSizeBuffer = ByteArray(4)

    override fun doRead(connection: AccessoryConnection): Int {
        try {
            val isSocket = connection is SocketAccessoryConnection
            val timeout = if (isSocket) 15000 else 0
            val headerSize = connection.recvBlocking(recvHeader.buf, recvHeader.buf.size, timeout, true)
            if (headerSize != AapMessageIncoming.EncryptedHeader.SIZE) {
                if (headerSize == -1) {
                    AaLog.i("AapRead: Connection closed (EOF). Disconnecting.")
                    return -1
                } else if (headerSize == 0) {
                    if (isSocket) {
                        AaLog.w("AapRead: WiFi read timeout (15s) - connection lost.")
                        return -1
                    }
                    return 0
                } else {
                    AaLog.e("AapRead: Partial header read. Expected ${AapMessageIncoming.EncryptedHeader.SIZE}, got $headerSize. Skipping.")
                    return 0
                }
            }

            recvHeader.decode()

            if (isMagicGarbage(recvHeader.buf, 0, recvHeader.buf.size)) {
                AaLog.i("AapRead: Magic Garbage detected in header. Clean disconnect.")
                return -2
            }

            if (recvHeader.flags == 0x09) {
                val readSize = connection.recvBlocking(fragmentSizeBuffer, 4, 10000, true)
                if (readSize != 4) {
                    AaLog.e("AapRead: Failed to read fragment total size. Skipping.")
                    return 0
                }
            }

            if (recvHeader.enc_len > msgBuffer.size || recvHeader.enc_len < 0) {
                AaLog.e("AapRead: Invalid message size (${recvHeader.enc_len} bytes). Skipping.")
                return 0
            }

            val msgSize = connection.recvBlocking(msgBuffer, recvHeader.enc_len, 10000, true)
            if (msgSize != recvHeader.enc_len) {
                if (msgSize == -1) {
                    AaLog.i("AapRead: Connection closed during body read.")
                    return -1
                }
                AaLog.e("AapRead: Failed to read full message body. Expected ${recvHeader.enc_len}, got $msgSize. Skipping.")
                return 0
            }

            val msg = AapMessageIncoming.decrypt(recvHeader, 0, msgBuffer, ssl)
            if (msg == null) {
                if (ssl is AapSslContext && ssl.isUserDisconnect) {
                    AaLog.i("AapRead: Magic Garbage detected in decryption. Triggering clean disconnect.")
                    return -2
                }
                return 0
            }

            handler.handle(msg)
            return 0
        } catch (e: Exception) {
            AaLog.e("AapRead: Error in read loop (ignored): ${e.message}")
            return 0
        }
    }

    private fun isMagicGarbage(buffer: ByteArray, start: Int, length: Int): Boolean {
        if (length < 4) return false
        for (i in 0 until 4.coerceAtMost(length)) {
            if (buffer[start + i] != 0xFF.toByte()) return false
        }
        return true
    }
}
