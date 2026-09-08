// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Selects an Internet-capable network for a single call and releases any
 * temporary cellular reservation afterward.
 *
 * The T-Box Wi-Fi network has no Internet access, so an HTTP call made during a session must not
 * assume the process default network can reach the Internet. This mirrors the binding principle
 * already used by the map tile provider, adapted to occasional one-shot requests instead of a
 * long-lived session.
 *
 * Moved out of the navigation package when the diagnostics collector reached the CORE edition:
 * uploading a report has exactly the problem this was written for, and hits it harder. A rider
 * sends one while connected to the dashboard, which is to say while bound to a network with no
 * route to anywhere - so without this, the report that describes a bad session is the one that
 * cannot leave the phone.
 */
suspend fun <T> withCellularNetwork(
    context: Context,
    cellularOnly: Boolean,
    timeoutMillis: Long = 6_000L,
    // Suspending so a caller can run several requests concurrently on the chosen
    // network and still have the reservation held until the last one finishes.
    // Every existing caller passes a plain lambda, which is still valid here.
    block: suspend (Network?) -> T
): T {
    val connectivityManager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    if (!cellularOnly) {
        // The process may be bound to the motorcycle's local Wi-Fi, which is
        // intentionally not Internet-capable. Use a validated network directly
        // so a simulator/phone Wi-Fi connection keeps working, while a real
        // motorcycle session naturally selects validated cellular data.
        return runWithBindFallback(context, findValidatedInternetNetwork(connectivityManager), block)
    }
    val reservation = requestCellularNetworkOrNull(connectivityManager, timeoutMillis)
    return try {
        runWithBindFallback(context, reservation?.network, block)
    } finally {
        // Release only after the HTTP call is done. Unregistering as soon as
        // onAvailable fires tells Android the network is no longer needed,
        // which can tear it down mid-request and abort the in-flight socket.
        reservation?.let { runCatching { connectivityManager.unregisterNetworkCallback(it.callback) } }
    }
}

/**
 * Runs [block] on [network], retrying once through [NetworkBindFallback.fallbackNetwork] when a
 * VPN lockdown refuses the bind - the request itself never made it out, so a retry is safe.
 */
private suspend fun <T> runWithBindFallback(
    context: Context,
    network: Network?,
    block: suspend (Network?) -> T
): T = try {
    block(network)
} catch (failure: Throwable) {
    if (network == null || !NetworkBindFallback.isBindRefusal(failure)) throw failure
    NetworkBindFallback.noteBindRefusal()
    block(NetworkBindFallback.fallbackNetwork(context))
}

/**
 * The network [withCellularNetwork] would choose for a non-cellular-only call, or null when there
 * is none - so a caller can ask what a request is ABOUT to cost before making it.
 *
 * Internal and read-only: the selection lives in one place, and the update dialog's "this will use
 * mobile data" warning must name the same network the download then takes, or it is a lie.
 */
internal fun validatedInternetNetwork(context: Context): Network? {
    val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return null
    return runCatching { findValidatedInternetNetwork(connectivityManager) }.getOrNull()
}

private fun findValidatedInternetNetwork(connectivityManager: ConnectivityManager): Network? =
    connectivityManager.allNetworks
        .asSequence()
        .mapNotNull { network ->
            connectivityManager.getNetworkCapabilities(network)?.let { capabilities ->
                network to capabilities
            }
        }
        .filter { (_, capabilities) ->
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        // Prefer Wi-Fi when it genuinely has Internet; on the motorcycle the
        // T-Box Wi-Fi is not validated, so cellular wins automatically.
        .sortedBy { (_, capabilities) ->
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) 0 else 1
        }
        .map { (network, _) -> network }
        .firstOrNull()

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
