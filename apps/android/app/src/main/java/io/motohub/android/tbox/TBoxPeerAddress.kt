// Pure IPv4 subnet math for deriving the T-Box peer address. Not GPL (no hudlib) — moved here
// from RideDaemonTransport so it stays available to both flavors (TBoxPortScanner uses it in
// src/main, and RideDaemonTransport uses it in the CORE-only source set).
package io.motohub.android.tbox

import java.net.Inet4Address
import java.net.InetAddress

internal fun deriveTBoxPeerIpv4(
    gateways: List<InetAddress>,
    dnsServers: List<InetAddress>,
    localAddresses: List<Pair<InetAddress, Int>>
): Inet4Address? {
    val localIpv4 = localAddresses.mapNotNull { (address, prefixLength) ->
        (address as? Inet4Address)?.let { it to prefixLength }
    }.filter { (address, _) -> isUsableTBoxIpv4Address(address) }
    if (localIpv4.isEmpty()) return null

    val routedCandidate = (gateways + dnsServers)
        .filterIsInstance<Inet4Address>()
        .firstOrNull { candidate ->
            isUsableTBoxIpv4Address(candidate) && localIpv4.any { (local, prefixLength) ->
                candidate != local && isSameIpv4Subnet(candidate, local, prefixLength)
            }
        }
    if (routedCandidate != null) return routedCandidate

    val (local, prefixLength) = localIpv4.firstOrNull { (_, prefix) -> prefix in 1..31 }
        ?: return null
    val octets = local.address
    val ip = ((octets[0].toInt() and 0xFF) shl 24) or
        ((octets[1].toInt() and 0xFF) shl 16) or
        ((octets[2].toInt() and 0xFF) shl 8) or
        (octets[3].toInt() and 0xFF)
    val mask = -1 shl (32 - prefixLength)
    val groupOwner = (ip and mask) or 1
    if (groupOwner == ip) return null
    return InetAddress.getByAddress(
        byteArrayOf(
            (groupOwner ushr 24).toByte(),
            (groupOwner ushr 16).toByte(),
            (groupOwner ushr 8).toByte(),
            groupOwner.toByte()
        )
    ) as Inet4Address
}

private fun isSameIpv4Subnet(first: Inet4Address, second: Inet4Address, prefixLength: Int): Boolean {
    if (prefixLength !in 1..32) return false
    val fullBytes = prefixLength / 8
    val remainingBits = prefixLength % 8
    val firstBytes = first.address
    val secondBytes = second.address
    for (index in 0 until fullBytes) {
        if (firstBytes[index] != secondBytes[index]) return false
    }
    if (remainingBits == 0) return true
    val mask = (0xFF shl (8 - remainingBits)) and 0xFF
    return (firstBytes[fullBytes].toInt() and mask) ==
        (secondBytes[fullBytes].toInt() and mask)
}
