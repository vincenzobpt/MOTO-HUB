package io.motohub.android.tbox

import android.net.Network
import android.net.nsd.NsdManager
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executor

/**
 * The transport-independent view of an established link to a T-Box, so discovery, the EasyConn
 * command socket, and diagnostics don't have to know whether they are riding on a normal Wi-Fi
 * AP or a Wi-Fi Direct group.
 *
 * Two shapes exist because CFMoto dashes come in two connection styles:
 *  - [Infrastructure]: a classic WPA2 access point joined via `WifiNetworkSpecifier`. There is a
 *    real [Network] to bind sockets and NSD to.
 *  - [WifiDirect]: the dash runs as a Wi-Fi Direct Group Owner (SSID `DIRECT-...`, e.g. CL-C450).
 *    A P2P group has no `ConnectivityManager.Network`, so sockets are bound to the phone's P2P
 *    source IP instead and the group owner (`192.168.49.1`) is known up front.
 */
sealed interface TBoxLink {
    /** The bound network for the infrastructure path; null on a Wi-Fi Direct group. */
    val network: Network?

    /** The known/derived T-Box peer address, when one is available without discovery. */
    val peerHint: Inet4Address?

    /** A short human tag for logs. */
    val label: String

    /** Creates an unconnected socket that will egress over this link. */
    fun createSocket(): Socket

    /** Releases link-specific resources. The AP request itself remains owned by TBoxNetworkConnector. */
    fun disconnect()

    /** Starts NSD discovery over this link, hiding the network-bound vs. default-network overload. */
    fun startNsdDiscovery(
        nsdManager: NsdManager,
        serviceType: String,
        executor: Executor,
        listener: NsdManager.DiscoveryListener
    )

    /** Whether a resolved NSD service on [resolvedNetwork] belongs to this link. */
    fun matchesResolvedNetwork(resolvedNetwork: Network?): Boolean

    class Infrastructure(override val network: Network) : TBoxLink {
        override val peerHint: Inet4Address? = null
        override val label: String get() = "network=$network"

        override fun createSocket(): Socket = network.socketFactory.createSocket()

        override fun disconnect() = Unit

        override fun startNsdDiscovery(
            nsdManager: NsdManager,
            serviceType: String,
            executor: Executor,
            listener: NsdManager.DiscoveryListener
        ) {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, network, executor, listener)
        }

        override fun matchesResolvedNetwork(resolvedNetwork: Network?): Boolean =
            resolvedNetwork == network
    }

    class WifiDirect(
        val bindIp: Inet4Address,
        val gatewayIp: Inet4Address,
        private val leaveGroup: () -> Unit
    ) : TBoxLink {
        override val network: Network? = null
        override val peerHint: Inet4Address = gatewayIp
        override val label: String get() = "p2p ${bindIp.hostAddress}->${gatewayIp.hostAddress}"

        override fun createSocket(): Socket {
            // The group's 192.168.49.0/24 subnet is on-link on the p2p interface, so even an
            // unbound socket egresses there by destination route. Pin the source address only
            // when it is still assigned: the p2p address can be reassigned by DHCP between the
            // join and later socket use, and binding a stale address throws EADDRNOTAVAIL. If
            // pinning is not possible, an unbound socket still reaches 192.168.49.1.
            val source = currentP2pSourceIp()
            if (source != null) {
                runCatching { return Socket().apply { bind(InetSocketAddress(source, 0)) } }
            }
            return Socket()
        }

        override fun disconnect() = leaveGroup()

        /** The p2p source address assigned right now — the captured one if still present, else any. */
        private fun currentP2pSourceIp(): Inet4Address? = runCatching {
            val captured = bindIp.hostAddress
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces().toList()
            interfaces.asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.hostAddress == captured }
                ?.let { return it }
            interfaces.asSequence()
                .filter { it.isUp && !it.isLoopback && it.name.startsWith("p2p") }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { addr ->
                    val host = addr.hostAddress
                    host != null && host.startsWith("192.168.49.") && host != "192.168.49.1"
                }
        }.getOrNull()

        override fun startNsdDiscovery(
            nsdManager: NsdManager,
            serviceType: String,
            executor: Executor,
            listener: NsdManager.DiscoveryListener
        ) {
            // A P2P group exposes no bindable Network, so fall back to default-network discovery.
            // Best-effort only: on many devices the default network stays cellular over P2P, in
            // which case discovery yields nothing and the caller relies on [peerHint] + wake probe.
            @Suppress("DEPRECATION")
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        }

        override fun matchesResolvedNetwork(resolvedNetwork: Network?): Boolean = true
    }
}
