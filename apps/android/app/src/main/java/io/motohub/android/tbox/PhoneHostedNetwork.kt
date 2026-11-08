// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay

/**
 * A network this app brings up itself for a dash that is told over Bluetooth how to join it
 * ([EcBtpNetLink]). Two ways to make one exist: [PhoneHostedHotspot] (a local-only hotspot) and
 * [PhoneHostedP2pGroup] (a Wi-Fi Direct group the phone owns). Both end in the same place - an
 * SSID and passphrase the dash is sent, and a private subnet on one of the phone's interfaces.
 */
internal interface PhoneHostedNetwork {

    /** What the dash has to be told to join this network. */
    data class Credentials(val ssid: String, val passphrase: String, val auth: String)

    /**
     * Brings the network up and reads back what it was given. [frequencyMhz] is the channel the
     * dash asked for, 0 when it did not; only a network that can choose its channel honours it.
     */
    suspend fun start(timeoutMillis: Long, frequencyMhz: Int = 0): Result<Credentials>

    /** Takes the network down. Idempotent; safe to call while [start] is still running. */
    fun close()
}

/**
 * A hotspot that has just started does not have an interface yet. Polling beats guessing a fixed
 * delay: the address is what the dash is about to be told to talk to.
 */
internal suspend fun awaitHostedSubnet(context: Context, timeoutMillis: Long = 15_000L): TBoxHotspotScan.Subnet? {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
        val subnet = TBoxHotspotScan.tetheringSubnets(
            TBoxHotspotScan.snapshotInterfaces(),
            TBoxHotspotScan.addressesInUse(context)
        ).firstOrNull()
        if (subnet != null) return subnet
        delay(250L)
    }
    return null
}

/**
 * Picks how to host, once, at the moment the dash asks for a network.
 *
 * Phone Wi-Fi ON: a Wi-Fi Direct group. A local-only hotspot running beside an enabled Wi-Fi
 * station is what One UI keeps offering to switch off - every ~10s on rider 0fedb1ad's
 * SM-S938B (2026-09-24) - and accepting takes the network down with no onStopped, so the dash
 * vanishes mid-discovery (WB-17). A group is what the Carbit app itself hosts on every phone from
 * Android 10 with 5GHz, and it raises no tethering dialog.
 *
 * Phone Wi-Fi OFF: the local-only hotspot, exactly as before. That is the path already proven on
 * a KOVE 510X (HONOR) and on this rider's 450RR, and nothing about it changes.
 *
 * A group that cannot be formed falls back to the hotspot, so this is never worse than before.
 */
internal class PreferredHostedNetwork(
    context: Context,
    private val log: (String) -> Unit
) : PhoneHostedNetwork {
    private val appContext = context.applicationContext

    @Volatile
    private var active: PhoneHostedNetwork? = null
    private val closed = AtomicBoolean(false)

    /** Set before the check: a close() that lands either side of it still reaches [network]. */
    private fun <T : PhoneHostedNetwork> adopt(network: T): T {
        active = network
        if (closed.get()) network.close()
        return network
    }

    override suspend fun start(timeoutMillis: Long, frequencyMhz: Int): Result<PhoneHostedNetwork.Credentials> {
        if (WifiGate.isWifiEnabled(appContext)) {
            log(
                "Phone Wi-Fi is on, so this phone hosts a Wi-Fi Direct group for the dash rather " +
                    "than a local-only hotspot, which some phones keep offering to switch off " +
                    "while Wi-Fi is on."
            )
            val group = adopt(PhoneHostedP2pGroup(appContext, log))
            val formed = group.start(timeoutMillis, frequencyMhz)
            if (formed.isSuccess) {
                return formed
            }
            log(
                "Could not host a Wi-Fi Direct group (${formed.exceptionOrNull()?.message}); " +
                    "falling back to a local-only hotspot."
            )
            group.close()
        }
        return adopt(PhoneHostedHotspot(appContext, log)).start(timeoutMillis, frequencyMhz)
    }

    override fun close() {
        closed.set(true)
        active?.close()
    }
}
