package io.motohub.android.externaldisplay

import io.motohub.android.encoding.VideoAccessUnitSink

class AoaAccessoryVideoSink(
    private val session: AoaAccessorySession
) : VideoAccessUnitSink {
    override fun offerAccessUnit(accessUnit: ByteArray): Boolean {
        session.write(accessUnit)
        return true
    }

    override fun close() {
        session.close()
    }
}
