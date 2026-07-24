package io.motohub.android.tbox

import io.motohub.android.encoding.VideoAccessUnitSink

class TBoxVideoSink(
    private val handle: TBoxSessionHandle
) : VideoAccessUnitSink {
    override fun offerAccessUnit(accessUnit: ByteArray): Boolean =
        handle.transport.offerAccessUnit(accessUnit)
}
