package io.motohub.android.feature.ridedashboard.nav

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Requests the cellular network for a single call and releases it afterward.
 *
 * The T-Box Wi-Fi network has no Internet access, so navigation HTTP calls
 * must not assume the process default network can reach the Internet. This
 * mirrors the binding principle already used by [io.motohub.android.feature.ridedashboard.OpenStreetMapTileProvider],
 * adapted to occasional one-shot requests instead of a long-lived session.
 */
internal suspend fun <T> withCellularNetwork(
    context: Context,
    cellularOnly: Boolean,
    timeoutMillis: Long = 6_000L,
    block: (Network?) -> T
): T {
    if (!cellularOnly) return block(null)
    val connectivityManager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    val reservation = requestCellularNetworkOrNull(connectivityManager, timeoutMillis)
    return try {
        block(reservation?.network)
    } finally {
        // Release only after the HTTP call is done. Unregistering as soon as
        // onAvailable fires tells Android the network is no longer needed,
        // which can tear it down mid-request and abort the in-flight socket.
        reservation?.let { runCatching { connectivityManager.unregisterNetworkCallback(it.callback) } }
    }
}

private class CellularNetworkReservation(
    val network: Network,
    val callback: ConnectivityManager.NetworkCallback
)

private suspend fun requestCellularNetworkOrNull(
    connectivityManager: ConnectivityManager,
    timeoutMillis: Long
): CellularNetworkReservation? = suspendCancellableCoroutine { continuation ->
    val request = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()
    lateinit var callback: ConnectivityManager.NetworkCallback
    callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (continuation.isActive) continuation.resume(CellularNetworkReservation(network, callback))
        }

        override fun onUnavailable() {
            if (continuation.isActive) continuation.resume(null)
        }
    }
    runCatching {
        connectivityManager.requestNetwork(request, callback, timeoutMillis.toInt())
    }.onFailure {
        if (continuation.isActive) continuation.resume(null)
    }
    continuation.invokeOnCancellation {
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
    }
}
