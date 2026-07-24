package io.motohub.android.tbox

import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.session.ProjectionEventLog

data class TBoxSessionHandle(
    val transport: TBoxTransport,
    val host: TBoxHost,
    val networkConnector: TBoxNetworkConnector,
    val motorcycle: MotorcycleProfile,
    val link: TBoxLink
)

/** In-process handoff from connection UI to the foreground projection service. */
object TBoxSessionRegistry {
    private var activeHandle: TBoxSessionHandle? = null

    @Synchronized
    fun install(handle: TBoxSessionHandle) {
        activeHandle = handle
        val modelProfile = TBoxModelProfile.fromModelId(handle.motorcycle.modelId)
        ProjectionEventLog.record(
            "SESSION",
            "Registry installed T-Box ${handle.host.ipAddress}:${handle.host.port} for " +
                "${handle.motorcycle.ssid}; model=${modelProfile.displayName}, " +
                "modelId=${handle.motorcycle.modelId ?: "unknown"}."
        )
    }

    @Synchronized
    fun current(): TBoxSessionHandle? = activeHandle

    @Synchronized
    fun clear(handle: TBoxSessionHandle? = null) {
        if (handle == null || activeHandle === handle) {
            val previous = activeHandle
            activeHandle = null
            if (previous != null) {
                previous.link.disconnect()
                ProjectionEventLog.record("SESSION", "T-Box registry cleared.")
            }
        }
    }
}
