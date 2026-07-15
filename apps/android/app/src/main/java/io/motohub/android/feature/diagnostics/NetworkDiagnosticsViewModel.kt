package io.motohub.android.feature.diagnostics

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.motohub.android.session.ProjectionRuntime
import io.motohub.android.session.ProjectionRuntimeState
import io.motohub.android.session.ProjectionEventLog
import io.motohub.android.tbox.TBoxSessionRegistry
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class NetworkDiagnosticId {
    TBOX_TCP,
    MOBILE_SOCKET_FACTORY_TCP,
    MOBILE_BIND_SOCKET_TCP,
    MOBILE_BIND_SOCKET_UDP
}

enum class NetworkDiagnosticStatus {
    NOT_RUN,
    RUNNING,
    PASSED,
    FAILED,
    SKIPPED
}

data class NetworkDiagnosticCheck(
    val id: NetworkDiagnosticId,
    val title: String,
    val status: NetworkDiagnosticStatus = NetworkDiagnosticStatus.NOT_RUN,
    val detail: String = "Waiting to run."
)

data class NetworkDiagnosticsUiState(
    val running: Boolean = false,
    val checks: List<NetworkDiagnosticCheck> = initialChecks(),
    val networkSnapshot: List<String> = emptyList(),
    val conclusion: String = "Connect to the T-Box before running the tests."
)

class NetworkDiagnosticsViewModel(application: Application) : AndroidViewModel(application) {
    private val connectivityManager = application.getSystemService(ConnectivityManager::class.java)
    private val mutableUiState = MutableStateFlow(NetworkDiagnosticsUiState())
    val uiState: StateFlow<NetworkDiagnosticsUiState> = mutableUiState.asStateFlow()

    fun runTests() {
        if (mutableUiState.value.running) {
            ProjectionEventLog.debug("DIAGNOSTICS", "Duplicate network test request ignored.")
            return
        }
        ProjectionEventLog.record("DIAGNOSTICS", "Network diagnostic suite started.")
        viewModelScope.launch {
            mutableUiState.value = NetworkDiagnosticsUiState(
                running = true,
                checks = initialChecks().map {
                    it.copy(status = NetworkDiagnosticStatus.RUNNING, detail = "Preparing test.")
                },
                networkSnapshot = snapshotNetworks(),
                conclusion = "Tests are running: no VPN is started and no network is changed."
            )

            val tBoxCheck = withContext(Dispatchers.IO) { testTBoxTcp() }
            updateCheck(tBoxCheck)

            val cellular = withContext(Dispatchers.IO) { acquireCellularNetwork() }
            val cellularNetwork = cellular.getOrNull()
            if (cellularNetwork == null) {
                val reason = cellular.exceptionOrNull()?.message ?: "Cellular network unavailable"
                updateCheck(
                    check(NetworkDiagnosticId.MOBILE_SOCKET_FACTORY_TCP, NetworkDiagnosticStatus.FAILED, reason)
                )
                updateCheck(
                    check(NetworkDiagnosticId.MOBILE_BIND_SOCKET_TCP, NetworkDiagnosticStatus.SKIPPED, reason)
                )
                updateCheck(
                    check(NetworkDiagnosticId.MOBILE_BIND_SOCKET_UDP, NetworkDiagnosticStatus.SKIPPED, reason)
                )
            } else {
                updateCheck(withContext(Dispatchers.IO) { testMobileSocketFactoryTcp(cellularNetwork) })
                updateCheck(withContext(Dispatchers.IO) { testMobileBindSocketTcp(cellularNetwork) })
                updateCheck(withContext(Dispatchers.IO) { testMobileBindSocketUdp(cellularNetwork) })
            }

            val finished = mutableUiState.value.checks
            mutableUiState.value = mutableUiState.value.copy(
                running = false,
                networkSnapshot = snapshotNetworks(),
                conclusion = buildConclusion(finished)
            )
            ProjectionEventLog.record(
                "DIAGNOSTICS",
                "Network diagnostic suite completed: ${mutableUiState.value.conclusion}"
            )
        }
    }

    private fun updateCheck(check: NetworkDiagnosticCheck) {
        val level = if (check.status == NetworkDiagnosticStatus.FAILED) {
            io.motohub.android.session.LogLevel.WARNING
        } else {
            io.motohub.android.session.LogLevel.INFO
        }
        ProjectionEventLog.record(
            "DIAGNOSTICS",
            "${check.id}: ${check.status} - ${check.detail}",
            level
        )
        mutableUiState.value = mutableUiState.value.copy(
            checks = mutableUiState.value.checks.map {
                if (it.id == check.id) check else it
            }
        )
    }

    private fun testTBoxTcp(): NetworkDiagnosticCheck {
        if (ProjectionRuntime.state.value is ProjectionRuntimeState.Starting ||
            ProjectionRuntime.state.value is ProjectionRuntimeState.Streaming
        ) {
            return check(
                NetworkDiagnosticId.TBOX_TCP,
                NetworkDiagnosticStatus.SKIPPED,
                "Stop streaming before probing the EasyConn port."
            )
        }

        val handle = TBoxSessionRegistry.current()
            ?: return check(
                NetworkDiagnosticId.TBOX_TCP,
                NetworkDiagnosticStatus.SKIPPED,
                "T-Box not discovered. First tap CONNECT AND FIND T-BOX."
            )
        val network = handle.networkConnector.currentNetwork()
            ?: return check(
                NetworkDiagnosticId.TBOX_TCP,
                NetworkDiagnosticStatus.FAILED,
                "The T-Box session exists, but Android no longer exposes its Wi-Fi network."
            )

        return runCatching {
            network.socketFactory.createSocket().use { socket ->
                socket.connect(InetSocketAddress(handle.host.ipAddress, handle.host.port), SOCKET_TIMEOUT_MS)
            }
            check(
                NetworkDiagnosticId.TBOX_TCP,
                NetworkDiagnosticStatus.PASSED,
                "TCP connection opened to the EasyConn service on the T-Box network."
            )
        }.getOrElse { failure ->
            check(NetworkDiagnosticId.TBOX_TCP, NetworkDiagnosticStatus.FAILED, readableFailure(failure))
        }
    }

    private fun testMobileSocketFactoryTcp(network: Network): NetworkDiagnosticCheck = runCatching {
        network.socketFactory.createSocket().use { socket ->
            socket.connect(InetSocketAddress(TEST_HOST, TEST_HTTPS_PORT), SOCKET_TIMEOUT_MS)
        }
        check(
            NetworkDiagnosticId.MOBILE_SOCKET_FACTORY_TCP,
            NetworkDiagnosticStatus.PASSED,
            "Internet TCP succeeded with a cellular-bound SocketFactory (${describeNetwork(network)})."
        )
    }.getOrElse { failure ->
        check(
            NetworkDiagnosticId.MOBILE_SOCKET_FACTORY_TCP,
            NetworkDiagnosticStatus.FAILED,
            readableFailure(failure)
        )
    }

    private fun testMobileBindSocketTcp(network: Network): NetworkDiagnosticCheck = runCatching {
        Socket().use { socket ->
            network.bindSocket(socket)
            socket.connect(InetSocketAddress(TEST_HOST, TEST_HTTPS_PORT), SOCKET_TIMEOUT_MS)
        }
        check(
            NetworkDiagnosticId.MOBILE_BIND_SOCKET_TCP,
            NetworkDiagnosticStatus.PASSED,
            "Cellular bindSocket() TCP succeeded. This is the key test for the VPN bridge."
        )
    }.getOrElse { failure ->
        check(
            NetworkDiagnosticId.MOBILE_BIND_SOCKET_TCP,
            NetworkDiagnosticStatus.FAILED,
            readableFailure(failure)
        )
    }

    private fun testMobileBindSocketUdp(network: Network): NetworkDiagnosticCheck = runCatching {
        DatagramSocket(null).use { socket ->
            network.bindSocket(socket)
            socket.soTimeout = SOCKET_TIMEOUT_MS
            socket.connect(InetSocketAddress(TEST_HOST, TEST_DNS_PORT))
            val query = buildDnsQuery()
            socket.send(DatagramPacket(query, query.size))

            val response = ByteArray(512)
            val packet = DatagramPacket(response, response.size)
            socket.receive(packet)
            check(packet.length >= DNS_HEADER_LENGTH) { "DNS response too short" }
            check(response[0] == query[0] && response[1] == query[1]) { "DNS response does not match the query" }
        }
        check(
            NetworkDiagnosticId.MOBILE_BIND_SOCKET_UDP,
            NetworkDiagnosticStatus.PASSED,
            "Cellular bindSocket() UDP and DNS succeeded. QUIC/UDP remain feasible in the bridge."
        )
    }.getOrElse { failure ->
        check(
            NetworkDiagnosticId.MOBILE_BIND_SOCKET_UDP,
            NetworkDiagnosticStatus.FAILED,
            readableFailure(failure)
        )
    }

    private suspend fun acquireCellularNetwork(): Result<Network> = runCatching {
        withTimeout(CELLULAR_REQUEST_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val completed = AtomicBoolean(false)
                lateinit var callback: ConnectivityManager.NetworkCallback

                fun finish(result: Result<Network>) {
                    if (!completed.compareAndSet(false, true)) return
                    callback.runCatching { connectivityManager.unregisterNetworkCallback(this) }
                    continuation.resumeWith(result)
                }

                fun tryAccept(network: Network, capabilities: NetworkCapabilities?) {
                    if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true &&
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    ) {
                        finish(Result.success(network))
                    }
                }

                callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        tryAccept(network, connectivityManager.getNetworkCapabilities(network))
                    }

                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities
                    ) {
                        tryAccept(network, networkCapabilities)
                    }

                    override fun onUnavailable() {
                        finish(Result.failure(IllegalStateException("Android did not provide a cellular network.")))
                    }
                }

                continuation.invokeOnCancellation {
                    callback.runCatching { connectivityManager.unregisterNetworkCallback(this) }
                }
                runCatching {
                    connectivityManager.requestNetwork(
                        android.net.NetworkRequest.Builder()
                            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            .build(),
                        callback
                    )
                }.onFailure { finish(Result.failure(it)) }
            }
        }
    }.recoverCatching { failure ->
        if (failure is TimeoutCancellationException) {
            throw IllegalStateException("Timed out waiting for the cellular network (${CELLULAR_REQUEST_TIMEOUT_MS / 1_000}s).")
        }
        throw failure
    }

    @Suppress("DEPRECATION")
    private fun snapshotNetworks(): List<String> = connectivityManager.allNetworks.map { network ->
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        val linkProperties = connectivityManager.getLinkProperties(network)
        val transports = buildList {
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("Wi-Fi")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("Cellular")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("VPN")
        }.ifEmpty { listOf("other") }.joinToString("+")
        val validation = if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) {
            "validated"
        } else {
            "not validated"
        }
        "$transports, $validation, interface ${linkProperties?.interfaceName ?: "n/a"}"
    }

    private fun describeNetwork(network: Network): String {
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        return if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) {
            "validated network"
        } else {
            "network not validated"
        }
    }

    private fun buildConclusion(checks: List<NetworkDiagnosticCheck>): String {
        val cellularTcp = checks.first { it.id == NetworkDiagnosticId.MOBILE_BIND_SOCKET_TCP }
        val cellularUdp = checks.first { it.id == NetworkDiagnosticId.MOBILE_BIND_SOCKET_UDP }
        return when {
            cellularTcp.status == NetworkDiagnosticStatus.PASSED &&
                cellularUdp.status == NetworkDiagnosticStatus.PASSED ->
                "This OnePlus allows cellular-bound TCP and UDP sockets. A local VPN bridge is technically feasible; a VPN is still required for internet access in other apps."
            cellularTcp.status == NetworkDiagnosticStatus.PASSED ->
                "Cellular-bound TCP works, but UDP/DNS does not. Do not implement the full VPN yet: the UDP path must be fixed first."
            else ->
                "Android/OxygenOS did not allow cellular TCP binding in this configuration. A local VPN is not yet reliable on this phone."
        }
    }

    private fun check(
        id: NetworkDiagnosticId,
        status: NetworkDiagnosticStatus,
        detail: String
    ): NetworkDiagnosticCheck = initialChecks().first { it.id == id }.copy(status = status, detail = detail)

    private fun readableFailure(failure: Throwable): String =
        failure.message?.takeIf { it.isNotBlank() } ?: failure::class.java.simpleName

    companion object {
        private const val TEST_HOST = "1.1.1.1"
        private const val TEST_HTTPS_PORT = 443
        private const val TEST_DNS_PORT = 53
        private const val SOCKET_TIMEOUT_MS = 8_000
        private const val CELLULAR_REQUEST_TIMEOUT_MS = 15_000L
        private const val DNS_HEADER_LENGTH = 12
    }
}

private fun initialChecks(): List<NetworkDiagnosticCheck> = listOf(
    NetworkDiagnosticCheck(
        NetworkDiagnosticId.TBOX_TCP,
        "T-Box TCP on motorcycle Wi-Fi"
    ),
    NetworkDiagnosticCheck(
        NetworkDiagnosticId.MOBILE_SOCKET_FACTORY_TCP,
        "Cellular TCP with SocketFactory"
    ),
    NetworkDiagnosticCheck(
        NetworkDiagnosticId.MOBILE_BIND_SOCKET_TCP,
        "Cellular TCP with bindSocket()"
    ),
    NetworkDiagnosticCheck(
        NetworkDiagnosticId.MOBILE_BIND_SOCKET_UDP,
        "Cellular UDP/DNS with bindSocket()"
    )
)

private fun buildDnsQuery(): ByteArray = byteArrayOf(
    0x4d, 0x48, // MOTO-HUB transaction ID
    0x01, 0x00, // Standard recursive query
    0x00, 0x01, // One question
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x03, 'o'.code.toByte(), 'n'.code.toByte(), 'e'.code.toByte(),
    0x03, 'o'.code.toByte(), 'n'.code.toByte(), 'e'.code.toByte(),
    0x03, 'o'.code.toByte(), 'n'.code.toByte(), 'e'.code.toByte(),
    0x03, 'o'.code.toByte(), 'n'.code.toByte(), 'e'.code.toByte(),
    0x00,
    0x00, 0x01, // A
    0x00, 0x01  // IN
)
