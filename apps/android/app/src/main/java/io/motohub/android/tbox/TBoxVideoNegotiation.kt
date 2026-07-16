package io.motohub.android.tbox

import io.motohub.android.encoding.EncoderProfile
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

enum class TBoxVideoAreaSource {
    LIVE,
    SAVED
}

data class TBoxVideoConfiguration(
    val rawArea: TBoxEvent.VideoArea,
    val encoderProfile: EncoderProfile,
    val source: TBoxVideoAreaSource
)

/** Starts EasyConn while already listening for the runtime TFT capture dimensions. */
suspend fun TBoxTransport.negotiateVideoConfiguration(
    host: TBoxHost,
    savedArea: TBoxEvent.VideoArea?,
    timeoutMillis: Long
): Result<TBoxVideoConfiguration> = coroutineScope {
    val liveArea = async(start = CoroutineStart.UNDISPATCHED) {
        withTimeoutOrNull(timeoutMillis) {
            events.filterIsInstance<TBoxEvent.VideoArea>().first()
        }
    }
    val startResult = start(host)
    startResult.exceptionOrNull()?.let { failure ->
        liveArea.cancel()
        return@coroutineScope Result.failure(failure)
    }

    selectVideoConfiguration(liveArea.await(), savedArea)
}

internal fun selectVideoConfiguration(
    liveArea: TBoxEvent.VideoArea?,
    savedArea: TBoxEvent.VideoArea?
): Result<TBoxVideoConfiguration> {
    val area = liveArea ?: savedArea ?: return Result.failure(
        IllegalStateException(
            "The T-Box did not provide a valid video area and no saved geometry is available."
        )
    )
    return runCatching {
        TBoxVideoConfiguration(
            rawArea = area,
            encoderProfile = EncoderProfile.forTBoxArea(area.width, area.height),
            source = if (liveArea != null) TBoxVideoAreaSource.LIVE else TBoxVideoAreaSource.SAVED
        )
    }
}
