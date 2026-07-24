package io.motohub.android.tbox

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.util.Log
import io.motohub.android.feature.settings.MotoHubSettings
import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.session.ProjectionEventLog
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch

sealed interface TBoxNetworkEvent {
    data class Lost(val network: Network) : TBoxNetworkEvent
    data class Reacquired(val network: Network) : TBoxNetworkEvent
}

/** Requests the T-Box AP explicitly and binds the process for its reverse TCP servers. */
class TBoxNetworkConnector(context: Context) {
    private val appContext = context.applicationContext
    private val reconnectScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectivityManager = appContext.getSystemService(
        ConnectivityManager::class.java
    )
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private val mutableEvents = MutableSharedFlow<TBoxNetworkEvent>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<TBoxNetworkEvent> = mutableEvents.asSharedFlow()

    @Volatile
    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile
    private var activeNetwork: Network? = null
    @Volatile
    private var processBoundNetwork: Network? = null
    @Volatile
    private var activeProfile: MotorcycleProfile? = null
    @Volatile
    private var connectedOnce = false
    @Volatile
    private var rejoinJob: Job? = null
    @Volatile
    private var simulatorMonitorJob: Job? = null

    suspend fun connect(profile: MotorcycleProfile): Result<Network> {
        disconnect()
        activeProfile = profile
        connectedOnce = false
        return try {
        if (TBoxModelProfile.fromModelId(profile.modelId) == TBoxModelProfile.MOTO_HUB_SIMULATOR) {
            ProjectionEventLog.record(
                "NETWORK",
                "Simulator profile detected for SSID ${profile.ssid}; reusing the phone's existing Wi-Fi " +
                    "instead of requesting a WifiNetworkSpecifier."
            )
            val network = withTimeout(CONNECTION_TIMEOUT_MS) { findExistingWifi(profile.ssid) }
            connectedOnce = true
            startSimulatorMonitor(profile)
            Result.success(network)
        } else {
            ProjectionEventLog.record(
                "NETWORK",
                "Requesting Android Wi-Fi network for SSID ${profile.ssid}; passwordPresent=${profile.password.isNotEmpty()}."
            )
            Result.success(withTimeout(CONNECTION_TIMEOUT_MS) { requestNetwork(profile) })
        }
        } catch (_: TimeoutCancellationException) {
        // No network was ever established, so there is no evidence to blame a VPN here (unlike the bindProcessToNetwork rejection below).
        val isSimulator = TBoxModelProfile.fromModelId(profile.modelId) == TBoxModelProfile.MOTO_HUB_SIMULATOR
        val message = if (isSimulator) {
            "The simulator requires the phone and Mac to be connected to the same Wi-Fi network " +
                "with a usable IPv4 address."
        } else {
            "Android did not obtain a usable IPv4 address from the requested T-Box AP within " +
                "${CONNECTION_TIMEOUT_MS}ms."
        }
        ProjectionEventLog.error("NETWORK", "Wi-Fi setup timed out after ${CONNECTION_TIMEOUT_MS}ms.")
        disconnect()
        Result.failure(IllegalStateException(message))
        } catch (cancelled: CancellationException) {
        // A real cancellation (user cancel, scope teardown) must propagate, not become a Result.
        throw cancelled
        } catch (failure: Throwable) {
        ProjectionEventLog.error("NETWORK", "T-Box AP request failed.", failure)
        // activeVpnLabel() omitted here: merely having a VpnService-based app present isn't evidence this failure caused it.
        val vpnMessage = TBoxVpnDiagnostics.userFacingMessage(failure, activeVpnLabel = null)
        Result.failure(vpnMessage?.let { IllegalStateException(it, failure) } ?: failure)
        }
    }

    fun disconnect() {
        rejoinJob?.cancel()
        rejoinJob = null
        simulatorMonitorJob?.cancel()
        simulatorMonitorJob = null
        activeProfile = null
        connectedOnce = false
        clearCurrentNetworkRequest()
    }

    private fun clearCurrentNetworkRequest() {
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

    /** Current Wi-Fi network confirmed by the SSID-specific request callback, if still active. */
    fun currentNetwork(): Network? = activeNetwork

    /** Waits for the persistent network request to reacquire the T-Box AP. */
    suspend fun awaitNetworkAvailable(timeoutMillis: Long): Network? =
        withTimeoutOrNull(timeoutMillis) {
            var network: Network? = currentNetwork()
            while (network == null) {
                delay(NETWORK_POLL_MS)
                network = currentNetwork()
            }
            network
        }

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

    /**
     * Restores the process route after a local projection has released it. Android can briefly
     * clear the callback's network while the T-Box AP is still being reacquired, so wait for the
     * persistent request instead of failing immediately on a transient null network.
     */
    suspend fun rebindProcessToTBoxWhenAvailable(timeoutMillis: Long): Result<Network> {
        if (awaitNetworkAvailable(timeoutMillis) == null) {
            val failure = IllegalStateException(
                "The T-Box network did not become available within ${timeoutMillis}ms."
            )
            ProjectionEventLog.error(
                "NETWORK",
                "Unable to restore T-Box process binding: network wait timed out.",
                failure
            )
            return Result.failure(failure)
        }
        return rebindProcessToTBox()
    }

    private suspend fun requestNetwork(profile: MotorcycleProfile): Network =
        suspendCancellableCoroutine { continuation ->
            clearCurrentNetworkRequest()
            val completed = AtomicBoolean(false)
            lateinit var networkCallback: ConnectivityManager.NetworkCallback

            fun finish(result: Result<Network>, keepCallback: Boolean) {
                if (!completed.compareAndSet(false, true)) return
                if (!keepCallback) {
                    // Only clear the shared field if it still points at this attempt's callback;
                    // a late failure must not orphan a newer attempt's registration.
                    if (callback === networkCallback) callback = null
                    networkCallback.runCatching {
                        connectivityManager.unregisterNetworkCallback(this)
                    }
                }
                continuation.resumeWith(result)
            }

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    val addresses = linkProperties.linkAddresses.mapNotNull { it.address.hostAddress }
                    val gateways = linkProperties.routes.mapNotNull { it.gateway?.hostAddress }.distinct()
                    Log.d(TAG, "Wi-Fi addresses=$addresses")
                    ProjectionEventLog.debug(
                        "NETWORK",
                        "Link properties changed: network=$network, interface=${linkProperties.interfaceName}, " +
                            "addresses=$addresses, gateways=$gateways."
                    )
                    val isTBoxNetwork = linkProperties.linkAddresses
                        .any { isUsableTBoxIpv4Address(it.address) }
                    if (isTBoxNetwork) {
                        val bindFailure = runCatching {
                            check(connectivityManager.bindProcessToNetwork(network)) {
                                "Android cannot bind MOTO-HUB to the T-Box network."
                            }
                        }.exceptionOrNull()
                        if (bindFailure != null) {
                            val activeVpn = activeVpnLabel()
                            val message = TBoxVpnDiagnostics.userFacingMessage(bindFailure, activeVpn)
                                ?: bindFailure.message.orEmpty()
                            Log.e(TAG, "T-Box process binding rejected; activeVpn=$activeVpn", bindFailure)
                            ProjectionEventLog.error(
                                "NETWORK",
                                "Process binding rejected for network=$network; activeVpn=${activeVpn ?: "none"}; " +
                                    "reason=$message."
                            )
                            finish(
                                Result.failure(
                                    IllegalStateException(message, bindFailure)
                                ),
                                keepCallback = false
                            )
                            return
                        }
                        processBoundNetwork = network
                        activeNetwork = network
                        connectedOnce = true
                        Log.i(TAG, "T-Box Wi-Fi is active: ${profile.ssid}, addresses=$addresses")
                        ProjectionEventLog.record(
                            "NETWORK",
                            "T-Box Wi-Fi validated and process-bound: ssid=${profile.ssid}, network=$network, addresses=$addresses."
                        )
                        if (MotoHubSettings.verboseTBoxLogging(appContext)) {
                            runCatching { wifiManager.connectionInfo }.getOrNull()?.let { info ->
                                ProjectionEventLog.debug(
                                    "NETWORK",
                                    "Wi-Fi link (verbose): frequency=${info.frequency}MHz, " +
                                        "rssi=${info.rssi}dBm, linkSpeed=${info.linkSpeed}Mbps."
                                )
                            }
                        }
                        finish(Result.success(network), keepCallback = true)
                    } else if (network == activeNetwork) {
                        // OnePlus can briefly publish incomplete LinkProperties while the AP stays
                        // associated. Only onLost is a real disconnect signal for the active network.
                        Log.w(TAG, "T-Box network address update is temporarily incomplete")
                        ProjectionEventLog.warning(
                            "NETWORK",
                            "Active T-Box network temporarily has no usable IPv4 address; " +
                                "waiting for onLost before disconnecting."
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
                    scheduleRejoin()
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

            continuation.invokeOnCancellation { clearCurrentNetworkRequest() }
            runCatching {
                connectivityManager.requestNetwork(request, networkCallback)
            }.onFailure {
                ProjectionEventLog.error("NETWORK", "ConnectivityManager.requestNetwork threw an exception.", it)
                finish(Result.failure(it), keepCallback = false)
            }
        }

    private fun scheduleRejoin() {
        val profile = activeProfile ?: return
        if (!connectedOnce || rejoinJob?.isActive == true) return
        rejoinJob = reconnectScope.launch {
            var attempt = 0
            try {
                while (activeProfile != null && connectedOnce && activeNetwork == null) {
                    attempt++
                    delay(
                        if (attempt == 1) REJOIN_FIRST_DELAY_MS
                        else (REJOIN_BASE_DELAY_MS * (attempt - 1)).coerceAtMost(REJOIN_MAX_DELAY_MS)
                    )
                    if (activeNetwork != null) break
                    // Android may never call onUnavailable for a specifier request; without this
                    // timeout a single silent attempt would block the rejoin loop forever.
                    val result = try {
                        Result.success(withTimeout(CONNECTION_TIMEOUT_MS) { requestNetwork(profile) })
                    } catch (timeout: TimeoutCancellationException) {
                        Result.failure(timeout)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        Result.failure(failure)
                    }
                    result.onSuccess { network ->
                        ProjectionEventLog.record(
                            "NETWORK",
                            "T-Box Wi-Fi automatically reacquired on attempt $attempt: network=$network."
                        )
                        mutableEvents.tryEmit(TBoxNetworkEvent.Reacquired(network))
                        return@launch
                    }.onFailure { failure ->
                        ProjectionEventLog.warning(
                            "NETWORK",
                            "T-Box Wi-Fi rejoin attempt $attempt failed: ${failure.message}"
                        )
                    }
                }
            } finally {
                rejoinJob = null
            }
        }
    }

    private suspend fun findExistingWifi(ssid: String): Network {
        while (true) {
            val network = findMatchingWifi(ssid)
            if (network != null) {
                activeNetwork = network
                ProjectionEventLog.record(
                    "NETWORK",
                    "Existing Wi-Fi validated for simulator: ssid=${normalizeSsid(ssid)}, network=$network, " +
                        "addresses=${usableIpv4Addresses(network)}."
                )
                return network
            }
            delay(EXISTING_WIFI_POLL_MS)
        }
    }

    /** Polls the already-connected Wi-Fi used by the Mac simulator, which has no specifier callback. */
    private fun startSimulatorMonitor(profile: MotorcycleProfile) {
        if (simulatorMonitorJob?.isActive == true) return
        simulatorMonitorJob = reconnectScope.launch {
            try {
                while (activeProfile == profile && connectedOnce) {
                    delay(SIMULATOR_MONITOR_POLL_MS)
                    val matching = findMatchingWifi(profile.ssid)
                    val current = activeNetwork
                    when {
                        current != null && matching == null -> {
                            activeNetwork = null
                            ProjectionEventLog.warning(
                                "NETWORK",
                                "Simulator Wi-Fi disappeared; waiting for it to become available again."
                            )
                            mutableEvents.tryEmit(TBoxNetworkEvent.Lost(current))
                        }
                        current == null && matching != null -> {
                            activeNetwork = matching
                            ProjectionEventLog.record(
                                "NETWORK",
                                "Simulator Wi-Fi automatically reacquired: network=$matching."
                            )
                            mutableEvents.tryEmit(TBoxNetworkEvent.Reacquired(matching))
                        }
                    }
                }
            } finally {
                simulatorMonitorJob = null
            }
        }
    }

    private fun findMatchingWifi(expectedSsid: String): Network? {
        val normalizedExpected = normalizeSsid(expectedSsid)
        val connectedSsid = runCatching { normalizeSsid(wifiManager.connectionInfo?.ssid.orEmpty()) }
            .getOrDefault("")
        val candidates = connectivityManager.allNetworks.asSequence()
            .filter { network ->
                connectivityManager.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            .filter { network -> usableIpv4Addresses(network).isNotEmpty() }
            .toList()
        val exactMatch = if (connectedSsid == normalizedExpected) {
            val active = connectivityManager.activeNetwork
            candidates.firstOrNull { it == active } ?: candidates.firstOrNull()
        } else {
            null
        }
        if (exactMatch != null) return exactMatch

        // Some Android builds hide WifiInfo.ssid despite the granted Wi-Fi permissions. When there
        // is exactly one usable Wi-Fi network, it is still safe to use it for the simulator.
        if (connectedSsid.isBlank() || connectedSsid == "<unknown ssid>") {
            return candidates.singleOrNull()?.also {
                ProjectionEventLog.warning(
                    "NETWORK",
                    "Android did not expose the current Wi-Fi SSID; using the only usable Wi-Fi network " +
                        "for the simulator."
                )
            }
        }
        return null
    }

    private fun usableIpv4Addresses(network: Network): List<String> =
        connectivityManager.getLinkProperties(network)?.linkAddresses
            ?.mapNotNull { it.address }
            ?.filter(::isUsableTBoxIpv4Address)
            ?.mapNotNull { it.hostAddress }
            .orEmpty()

    private fun normalizeSsid(value: String): String = value.trim().removeSurrounding("\"")

    internal fun activeVpnLabel(): String? {
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
        const val EXISTING_WIFI_POLL_MS = 250L
        const val NETWORK_POLL_MS = 250L
        const val SIMULATOR_MONITOR_POLL_MS = 1_000L
        const val REJOIN_FIRST_DELAY_MS = 300L
        const val REJOIN_BASE_DELAY_MS = 2_500L
        const val REJOIN_MAX_DELAY_MS = 15_000L
    }

    private val contextPackageManager = context.applicationContext.packageManager
}

internal fun isUsableTBoxIpv4Address(address: InetAddress): Boolean =
    address is Inet4Address &&
        !address.isAnyLocalAddress &&
        !address.isLoopbackAddress &&
        !address.isLinkLocalAddress &&
        !address.isMulticastAddress
