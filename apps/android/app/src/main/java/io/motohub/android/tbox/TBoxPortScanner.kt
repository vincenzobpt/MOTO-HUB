package io.motohub.android.tbox

import android.content.Context
import android.net.ConnectivityManager
import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.session.ProjectionEventLog
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

enum class TBoxPortStatus { OPEN, REFUSED, NO_RESPONSE }

data class TBoxPortScanEntry(val port: Int, val status: TBoxPortStatus, val detail: String?)

data class TBoxPortScanResult(val peerIp: String?, val entries: List<TBoxPortScanEntry>)

/**
 * Best-effort TCP probe of the T-Box AP's well-known EasyConn ports. Exists because a wake
 * probe to the OpenCfMoto/OpenMoto reference port (10930) can come back ECONNREFUSED on a T-Box
 * variant those projects never reverse-engineered - REFUSED there means the peer IP is reachable
 * and alive, just not listening on that specific port. This narrows down what port actually is
 * open, instead of guessing another one blind. Opens its own short-lived Wi-Fi connection,
 * independent of any active EasyConn session, and always disconnects when done.
 */
object TBoxPortScanner {
    // The only ports any reference EasyConn implementation (OpenCfMoto, OpenMoto, OpenCfLink)
    // documents - PXC ctrl/media (10920-10922) and the mDNS-respond probe (10930) - plus a
    // narrow neighborhood in case this firmware shifted the whole block.
    private val CANDIDATE_PORTS = (10915..10935).toList()
    private const val CONNECT_TIMEOUT_MS = 1_200

    suspend fun scan(context: Context, profile: MotorcycleProfile): Result<TBoxPortScanResult> =
        withContext(Dispatchers.IO) {
            val connector = TBoxNetworkConnector(context)
            val result = runCatching {
                val link = TBoxLinkResolver.connect(context, connector, profile).getOrThrow()
                val peerIp = link.peerHint?.hostAddress ?: link.network?.let { network ->
                    val connectivityManager = context.applicationContext
                        .getSystemService(ConnectivityManager::class.java)
                    connectivityManager.getLinkProperties(network)?.let { properties ->
                        deriveTBoxPeerIpv4(
                            gateways = properties.routes
                                .filter { route -> route.isDefaultRoute }
                                .mapNotNull { route -> route.gateway },
                            dnsServers = properties.dnsServers,
                            localAddresses = properties.linkAddresses
                                .map { linkAddress -> linkAddress.address to linkAddress.prefixLength }
                        )
                    }?.hostAddress
                }
                if (peerIp == null) {
                    ProjectionEventLog.warning("DIAGNOSTICS", "Port scan: no usable peer IPv4 could be derived.")
                    TBoxPortScanResult(peerIp = null, entries = emptyList())
                } else {
                    ProjectionEventLog.record(
                        "DIAGNOSTICS",
                        "Port scan starting against $peerIp (${link.label}), " +
                            "ports ${CANDIDATE_PORTS.first()}-${CANDIDATE_PORTS.last()}."
                    )
                    val entries = CANDIDATE_PORTS
                        .map { port -> async { probe(link, peerIp, port) } }
                        .awaitAll()
                        .sortedBy { it.port }
                    val open = entries.filter { it.status == TBoxPortStatus.OPEN }
                    ProjectionEventLog.record(
                        "DIAGNOSTICS",
                        if (open.isEmpty()) {
                            "Port scan complete: none of ${entries.size} candidate ports responded as open."
                        } else {
                            "Port scan complete: open=${open.joinToString { it.port.toString() }}."
                        }
                    )
                    TBoxPortScanResult(peerIp, entries)
                }
            }
            connector.disconnect()
            result
        }

    private fun probe(link: TBoxLink, host: String, port: Int): TBoxPortScanEntry = try {
        link.createSocket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        }
        TBoxPortScanEntry(port, TBoxPortStatus.OPEN, null)
    } catch (failure: ConnectException) {
        TBoxPortScanEntry(port, TBoxPortStatus.REFUSED, failure.message)
    } catch (failure: SocketTimeoutException) {
        TBoxPortScanEntry(port, TBoxPortStatus.NO_RESPONSE, failure.message)
    } catch (failure: Exception) {
        TBoxPortScanEntry(port, TBoxPortStatus.NO_RESPONSE, failure.message)
    }
}
