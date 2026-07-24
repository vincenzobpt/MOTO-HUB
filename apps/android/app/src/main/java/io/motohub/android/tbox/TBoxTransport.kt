package io.motohub.android.tbox

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

data class TBoxHost(
    val ipAddress: String,
    val port: Int,
    val packageName: String
)

sealed interface TBoxEvent {
    data class Capabilities(val value: TBoxCapabilities) : TBoxEvent
    data class VideoArea(val width: Int, val height: Int) : TBoxEvent
    data class Touch(val action: Int, val pointerId: Int, val x: Int, val y: Int) : TBoxEvent
    data object VideoStreamStart : TBoxEvent
    data class Warning(val message: String) : TBoxEvent
    data class FatalError(val message: String) : TBoxEvent
    data object Stopped : TBoxEvent
}

sealed interface TBoxTransportStatus {
    data object Unavailable : TBoxTransportStatus
    data object Ready : TBoxTransportStatus
    data class Failure(val reason: String) : TBoxTransportStatus
}

interface TBoxTransport {
    /** Selects the profile whose wire-level capabilities will be advertised for the next session. */
    fun configureProtocolProfile(profile: TBoxModelProfile) = Unit
    suspend fun discover(link: TBoxLink, expectedModelId: String? = null): Result<TBoxHost>
    suspend fun start(host: TBoxHost): Result<Unit>
    fun offerAccessUnit(avcc: ByteArray): Boolean
    suspend fun stop()
    val events: Flow<TBoxEvent>
}

/** Keeps UI and session code honest until the GPL transport AAR is packaged. */
class UnavailableTBoxTransport : TBoxTransport {
    override suspend fun discover(link: TBoxLink, expectedModelId: String?): Result<TBoxHost> = Result.failure(
        IllegalStateException("hudlib.aar is not integrated")
    )

    override suspend fun start(host: TBoxHost): Result<Unit> = Result.failure(
        IllegalStateException("hudlib.aar is not integrated")
    )

    override fun offerAccessUnit(avcc: ByteArray): Boolean = false

    override suspend fun stop() = Unit

    override val events: Flow<TBoxEvent> = emptyFlow()
}
