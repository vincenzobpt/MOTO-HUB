package io.motohub.android.tbox

import android.content.Context
import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.session.ProjectionEventLog
import io.motohub.android.session.TBoxConnectionMode

/**
 * Single decision point for how to reach a T-Box. A profile can explicitly select Auto, AP, or
 * Wi-Fi Direct, which is essential for dashboards that act as P2P Group Owners without exposing
 * a conventional access point.
 */
object TBoxLinkResolver {

    suspend fun connect(
        context: Context,
        networkConnector: TBoxNetworkConnector,
        profile: MotorcycleProfile
    ): Result<TBoxLink> =
        if (profile.usesWifiDirect()) {
            ProjectionEventLog.record(
                "NETWORK",
                "Connecting to ${profile.ssid} through Wi-Fi Direct (${profile.connectionMode})."
            )
            TBoxWifiDirectConnector(context).connect(profile).map { it }
        } else {
            ProjectionEventLog.record(
                "NETWORK",
                "Connecting to ${profile.ssid} through the Wi-Fi access-point transport (${profile.connectionMode})."
            )
            networkConnector.connect(profile).map { TBoxLink.Infrastructure(it) }
        }

    /** Recovery variant: reuse a still-alive infrastructure network before reconnecting. */
    suspend fun reacquire(
        context: Context,
        networkConnector: TBoxNetworkConnector,
        profile: MotorcycleProfile,
        awaitNetworkMillis: Long
    ): TBoxLink {
        if (profile.usesWifiDirect()) {
            // A P2P group has no ConnectivityManager-visible network to await; rejoin directly.
            return TBoxWifiDirectConnector(context).connect(profile).getOrThrow()
        }
        val network = networkConnector.currentNetwork()
            ?: networkConnector.awaitNetworkAvailable(awaitNetworkMillis)
            ?: networkConnector.connect(profile).getOrThrow()
        return TBoxLink.Infrastructure(network)
    }

    private fun MotorcycleProfile.usesWifiDirect(): Boolean = when (connectionMode) {
        TBoxConnectionMode.WIFI_DIRECT -> true
        TBoxConnectionMode.ACCESS_POINT -> false
        TBoxConnectionMode.AUTO -> TBoxWifiDirectConnector.isWifiDirectSsid(ssid)
    }
}
