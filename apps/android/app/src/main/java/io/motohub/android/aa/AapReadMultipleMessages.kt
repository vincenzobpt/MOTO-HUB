// Ported from headunit-revived (AGPLv3): aap/AapReadMultipleMessages.kt
package io.motohub.android.aa

import java.nio.ByteBuffer

internal class AapReadMultipleMessages(
    connection: AccessoryConnection,
    ssl: AapSsl,
    handler: AapMessageHandler
) : AapRead.Base(connection, ssl, handler) {

    private val fifo = ByteBuffer.allocate(4 * 1024 * 1024)
    private val recvBuffer = ByteArray(Messages.DEF_BUFFER_LENGTH)
    private val recvHeader = AapMessageIncoming.EncryptedHeader()
    private val msgBuffer = ByteArray(4 * 1024 * 1024)
    private val skipBuffer = ByteArray(4)

    override fun doRead(connection: AccessoryConnection): Int {
        val size = try {
            connection.recvBlocking(recvBuffer, recvBuffer.size, 5000, false)
        } catch (e: Exception) {
            AaLog.e("AapRead: Fatal read error: ${e.message}")
            return -1
        }

        if (size < 0) {
            if (!connection.isConnected) {
                AaLog.e("AapRead: Connection lost. Stopping read loop.")
                fifo.clear()
                return -1
            }
            return 0
        }
        if (size == 0) return 0

        try {
            if (fifo.remaining() < size) {
                AaLog.w("AapRead: FIFO overflow! Size: $size, Remaining: ${fifo.remaining()}. Clearing buffer.")
                fifo.clear()
            }
            fifo.put(recvBuffer, 0, size)
            processBulk()
        } catch (e: Exception) {
            AaLog.e("AapRead: Error in processBulk: ${e.message}")
            fifo.clear()
        }
        return 0
    }

    private fun processBulk() {
        fifo.flip()
        while (fifo.remaining() >= AapMessageIncoming.EncryptedHeader.SIZE) {
            fifo.mark()
            fifo.get(recvHeader.buf, 0, recvHeader.buf.size)
            recvHeader.decode()

            if (recvHeader.flags == 0x09) {
                if (fifo.remaining() < 4) { fifo.reset(); break }
                fifo.get(skipBuffer, 0, 4)
            }

            if (recvHeader.enc_len > msgBuffer.size || recvHeader.enc_len < 0) {
                AaLog.e("AapRead: Invalid message length (${recvHeader.enc_len}). Resetting FIFO.")
                fifo.clear()
                return
            }

            if (fifo.remaining() < recvHeader.enc_len) { fifo.reset(); break }

            fifo.get(msgBuffer, 0, recvHeader.enc_len)

            try {
                val msg = AapMessageIncoming.decrypt(recvHeader, 0, msgBuffer, ssl)
                if (msg != null) handler.handle(msg)
            } catch (e: Exception) {
                AaLog.e("AapRead: Decryption/Handling error: ${e.message}")
            }
        }
        fifo.compact()
    }
}
