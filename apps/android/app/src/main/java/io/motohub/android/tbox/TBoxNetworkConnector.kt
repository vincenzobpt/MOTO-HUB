package io.motohub.android.tbox

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.util.Log
import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.session.ProjectionEventLog
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

sealed interface TBoxNetworkEvent {
    data class Lost(val network: Network) : TBoxNetworkEvent
}

/** Requests the T-Box AP explicitly and binds the process for its reverse TCP servers. */
class TBoxNetworkConnector(context: Context) {
    private val connectivityManager = context.applicationContext.getSystemService(
        ConnectivityManager::class.java
    )
    private val mutableEvents = MutableSharedFlow<TBoxNetworkEvent>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<TBoxNetworkEvent> = mutableEvents.asSharedFlow()

    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile
    private var activeNetwork: Network? = null
    @Volatile
    private var processBoundNetwork: Network? = null

    suspend fun connect(profile: MotorcycleProfile): Result<Network> = try {
        ProjectionEventLog.record(
            "NETWORK",
            "Requesting Android Wi-Fi network for SSID ${profile.ssid}; passwordPresent=${profile.password.isNotEmpty()}."
        )
        Result.success(withTimeout(CONNECTION_TIMEOUT_MS) { requestNetwork(profile) })
    } catch (_: TimeoutCancellationException) {
        ProjectionEventLog.error("NETWORK", "T-Box AP request timed out after ${CONNECTION_TIMEOUT_MS}ms.")
        disconnect()
        Result.failure(
            IllegalStateException(
                "Android did not connect to the T-Box AP $TBOX_SUBNET_PREFIX* within 30 seconds."
            )
        )
    } catch (failure: Throwable) {
        ProjectionEventLog.error("NETWORK", "T-Box AP request failed.", failure)
        Result.failure(failure)
    }

    fun disconnect() {
        ProjectionEventLog.debug(
            "NETWORK",
            "Disconnect requested; callback=${callback != null}, activeNetwork=$activeNetwork, processBound=$processBoundNetwork."
        )
        val releasedCallback = callback
        callback = null
        if (processBoundNetwork != null) {
            connectivityManager.bindProcessToNetwork(null)
            processBoundNetwork = null
        }
        activeNetwork = null
        releasedCallback?.runCatching { connectivityManager.unregisterNetworkCallback(this) }
            ?.onFailure { ProjectionEventLog.warning("NETWORK", "Network callback unregister failed.", it) }
    }

    /** Current Wi-Fi network confirmed by the T-Box subnet callback, if still active. */
    fun currentNetwork(): Network? = activeNetwork

    /** Keeps the requested T-Box network alive but restores Android's normal process route. */
    @Synchronized
    fun releaseProcessBinding() {
        if (processBoundNetwork == null) return
        val released = connectivityManager.bindProcessToNetwork(null)
        processBoundNetwork = null
        ProjectionEventLog.record("NETWORK", "Process binding released; result=$released. T-Box request remains active.")
    }

    /** Rebinds reverse EasyConn sockets to the still-requested T-Box network. */
    @Synchronized
    fun rebindProcessToTBox(): Result<Network> = runCatching {
        val network = checkNotNull(activeNetwork) { "The T-Box network is no longer available." }
        check(connectivityManager.bindProcessToNetwork(network)) {
            "Android cannot restore the binding to the T-Box network."
        }
        processBoundNetwork = network
        ProjectionEventLog.record("NETWORK", "Process rebound to T-Box network=$network.")
        network
    }.onFailure { ProjectionEventLog.error("NETWORK", "Unable to restore T-Box process binding.", it) }

    private suspend fun requestNetwork(profile: MotorcycleProfile): Network =
        suspendCancellableCoroutine { continuation ->
            disconnect()
            val completed = AtomicBoolean(false)
            lateinit var networkCallback: ConnectivityManager.NetworkCallback

            fun finish(result: Result<Network>, keepCallback: Boolean) {
                if (!completed.compareAndSet(false, true)) return
                if (!keepCallback) {
                    callback = null
                    networkCallback.runCatching {
                        connectivityManager.unregisterNetworkCallback(this)
                    }
                }
                continuation.resumeWith(result)
            }

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    val addresses = linkProperties.linkAddresses.mapNotNull { it.address.hostAddress }
                    Log.d(TAG, "Wi-Fi addresses=$addresses")
                    ProjectionEventLog.debug("NETWORK", "Link properties changed: network=$network, addresses=$addresses.")
                    val isTBoxNetwork = addresses.any(::isTBoxLinkAddress)
                    if (isTBoxNetwork) {
                        if (!connectivityManager.bindProcessToNetwork(network)) {
                            val activeVpn = activeVpnLabel()
                            val message = if (activeVpn != null) {
                                "Android cannot bind MOTO-HUB to the T-Box while " +
                                    "$activeVpn is intercepting the app. Disconnect the VPN or " +
                                    "exclude MOTO-HUB from its tunnel, then try again."
                            } else {
                                "Android cannot bind MOTO-HUB to the T-Box network."
                            }
                            Log.e(TAG, "T-Box process binding rejected; activeVpn=$activeVpn")
                            ProjectionEventLog.error(
                                "NETWORK",
                                "Process binding rejected for network=$network; activeVpn=${activeVpn ?: "none"}."
                            )
                            finish(
                                Result.failure(
                                    IllegalStateException(message)
                                ),
                                keepCallback = false
                            )
                            return
                        }
                        processBoundNetwork = network
                        activeNetwork = network
                        Log.i(TAG, "T-Box Wi-Fi is active: ${profile.ssid}, addresses=$addresses")
                        ProjectionEventLog.record(
                            "NETWORK",
                            "T-Box Wi-Fi validated and process-bound: ssid=${profile.ssid}, network=$network, addresses=$addresses."
                        )
                        finish(Result.success(network), keepCallback = true)
                    } else if (network == activeNetwork) {
                        // OnePlus can briefly publish incomplete LinkProperties while the AP stays
                        // associated. Only onLost is a real disconnect signal for the active network.
                        Log.w(TAG, "T-Box network address update is temporarily incomplete")
                        ProjectionEventLog.warning(
                            "NETWORK",
                            "Active T-Box network temporarily has no 192.168.0.* address; waiting for onLost before disconnecting."
                        )
                    }
                }

                override fun onLost(network: Network) {
                    if (network != activeNetwork) return
                    ProjectionEventLog.warning("NETWORK", "Android onLost received for active T-Box network=$network.")
                    if (processBoundNetwork == network) {
                        connectivityManager.bindProcessToNetwork(null)
                        processBoundNetwork = null
                    }
                    activeNetwork = null
                    mutableEvents.tryEmit(TBoxNetworkEvent.Lost(network))
                }

                override fun onUnavailable() {
                    ProjectionEventLog.error("NETWORK", "Android reported the requested T-Box Wi-Fi as unavailable.")
                    finish(
                        Result.failure(
                            IllegalStateException("Android did not make the requested T-Box AP available.")
                        ),
                        keepCallback = false
                    )
                }
            }
            callback = networkCallback

            val specifier = WifiNetworkSpecifier.Builder()
                .setSsid(profile.ssid)
                .apply {
                    if (profile.password.isNotBlank()) setWpa2Passphrase(profile.password)
                }
                .build()
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()

            ProjectionEventLog.debug(
                "NETWORK",
                "Submitting WifiNetworkSpecifier request for ${profile.ssid} without INTERNET capability."
            )

            continuation.invokeOnCancellation { disconnect() }
            runCatching {
                connectivityManager.requestNetwork(request, networkCallback)
            }.onFailure {
                ProjectionEventLog.error("NETWORK", "ConnectivityManager.requestNetwork threw an exception.", it)
                finish(Result.failure(it), keepCallback = false)
            }
        }

    private fun activeVpnLabel(): String? {
        val capabilities = connectivityManager.allNetworks.asSequence()
            .mapNotNull { connectivityManager.getNetworkCapabilities(it) }
            .firstOrNull { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
            ?: return null
        val ownerUid = capabilities.ownerUid
        val packageName = if (ownerUid >= 0) {
            contextPackageManager.getPackagesForUid(ownerUid)?.firstOrNull()
        } else {
            null
        }
        val applicationLabel = packageName?.let { name ->
            runCatching {
                val info = contextPackageManager.getApplicationInfo(name, 0)
                contextPackageManager.getApplicationLabel(info).toString()
            }.getOrNull()
        }
        return applicationLabel?.takeIf { it.isNotBlank() } ?: "active"
    }

    private companion object {
        const val TAG = "TBoxNetwork"
        const val CONNECTION_TIMEOUT_MS = 30_000L
        const val TBOX_SUBNET_PREFIX = TBOX_LINK_PREFIX
    }

    private val contextPackageManager = context.applicationContext.packageManager
}

internal const val TBOX_LINK_PREFIX = "192.168.0."

internal fun isTBoxLinkAddress(address: String): Boolean = address.startsWith(TBOX_LINK_PREFIX)
