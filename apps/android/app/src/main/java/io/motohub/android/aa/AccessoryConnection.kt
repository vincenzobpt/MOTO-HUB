// Ported from headunit-revived (AGPLv3): connection/AccessoryConnection.kt
package io.motohub.android.aa

interface AccessoryConnection {
    val isSingleMessage: Boolean
    fun sendBlocking(buf: ByteArray, length: Int, timeout: Int): Int
    fun recvBlocking(buf: ByteArray, length: Int, timeout: Int, readFully: Boolean): Int
    val isConnected: Boolean
    fun connect(): Boolean
    fun disconnect()
}
