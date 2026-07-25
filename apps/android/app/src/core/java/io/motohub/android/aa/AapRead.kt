// Ported from headunit-revived (AGPLv3): aap/AapRead.kt (trimmed to video-only)
package io.motohub.android.aa

internal interface AapRead {
    fun read(): Int

    abstract class Base internal constructor(
        private val connection: AccessoryConnection?,
        internal val ssl: AapSsl,
        internal val handler: AapMessageHandler
    ) : AapRead {

        override fun read(): Int {
            if (connection == null) {
                AaLog.e("No connection.")
                return -1
            }
            return doRead(connection)
        }

        protected abstract fun doRead(connection: AccessoryConnection): Int
    }

    object Factory {
        fun create(
            connection: AccessoryConnection,
            transport: AapTransport,
            aapVideo: AapVideo
        ): AapRead {
            val handler = AapMessageHandlerType(transport, aapVideo)
            return if (connection.isSingleMessage)
                AapReadSingleMessage(connection, transport.ssl, handler)
            else
                AapReadMultipleMessages(connection, transport.ssl, handler)
        }
    }
}
