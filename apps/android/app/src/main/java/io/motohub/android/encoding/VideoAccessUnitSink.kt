package io.motohub.android.encoding

interface VideoAccessUnitSink {
    fun offerAccessUnit(accessUnit: ByteArray): Boolean
    fun close() = Unit
}
