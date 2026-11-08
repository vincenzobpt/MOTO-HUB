// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A Wi-Fi Direct group this phone owns, hosted for a dash that joins it as an ordinary WPA2
 * client - see [PreferredHostedNetwork] for when this is used instead of [PhoneHostedHotspot].
 *
 * The phone is the group owner, so it sits at 192.168.49.1 on a `p2p-…` interface, which
 * [TBoxHotspotScan.tetheringSubnets] already counts as a hosted network. The group name has to
 * start with `DIRECT-` plus two alphanumerics (the platform rejects anything else); the dash
 * accepts whatever SSID it is told over Bluetooth, so that costs nothing.
 */
@SuppressLint("MissingPermission")
internal class PhoneHostedP2pGroup(
    context: Context,
    private val log: (String) -> Unit
) : PhoneHostedNetwork {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(WifiP2pManager::class.java)
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private val closed = AtomicBoolean(false)

    @Volatile
    private var channel: WifiP2pManager.Channel? = null

    /** Set once createGroup succeeded: only a group this object made is ever removed. */
    @Volatile
    private var formed = false

    override suspend fun start(timeoutMillis: Long, frequencyMhz: Int): Result<PhoneHostedNetwork.Credentials> {
        val p2p = manager ?: return failure("This phone exposes no Wi-Fi Direct service.")
        if (closed.get()) return failure("The Wi-Fi Direct group was already released.")
        val opened = runCatching { p2p.initialize(appContext, Looper.getMainLooper(), null) }.getOrNull()
            ?: return failure("Android would not open a Wi-Fi Direct channel.")
        channel = opened
        return withTimeoutOrNull(timeoutMillis) { form(p2p, opened, frequencyMhz) }
            ?: failure("The Wi-Fi Direct group did not come up within ${timeoutMillis / 1000}s.")
    }

    private suspend fun form(
        p2p: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        frequencyMhz: Int
    ): Result<PhoneHostedNetwork.Credentials> {
        groupInfo(p2p, channel)?.let { existing ->
            // Ours from an attempt that died without cleaning up: safe to take down. Anything
            // else belongs to someone - a dash's own group, another app - and is not ours to end.
            if (existing.isGroupOwner && existing.networkName.orEmpty().startsWith(NAME_PREFIX)) {
                log("Removing a Wi-Fi Direct group an earlier attempt left behind (${existing.networkName}).")
                action { listener -> p2p.removeGroup(channel, listener) }
            } else {
                return failure("this phone is already in another Wi-Fi Direct group (${existing.networkName})")
            }
        }

        val random = SecureRandom()
        val chosenPassphrase = token(random, PASSPHRASE_LENGTH)
        val builder = WifiP2pConfig.Builder()
            .setNetworkName(groupName(token(random, 4)))
            .setPassphrase(chosenPassphrase)
            .enablePersistentMode(false)
        // The dash's own request wins. Otherwise 5GHz, as the Carbit app asks for whenever the
        // phone has it; 2.4GHz only on a phone without.
        val placement = when {
            frequencyMhz > 0 -> {
                builder.setGroupOperatingFrequency(frequencyMhz)
                "${frequencyMhz}MHz, as the dash asked"
            }
            wifiManager?.is5GHzBandSupported == true -> {
                builder.setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
                "5GHz band"
            }
            else -> {
                builder.setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                "2.4GHz band"
            }
        }
        val config = runCatching { builder.build() }.getOrElse { failure ->
            return failure("the group configuration was refused (${failure.message})")
        }

        log("Asking Android for a Wi-Fi Direct group on the $placement.")
        val refused = action { listener -> p2p.createGroup(channel, config, listener) }
        if (refused != null) return failure("Android refused to create it (${describeReason(refused)})")
        formed = true
        if (closed.get()) {
            removeFormedGroup(p2p, channel)
            return failure("The Wi-Fi Direct group was released while starting.")
        }

        // createGroup's success means "accepted", not "up": the interface and its address come a
        // moment later, and the dash must not be told a network that does not exist yet.
        while (true) {
            val group = groupInfo(p2p, channel)
            val name = group?.networkName
            if (group != null && group.isGroupOwner && group.`interface` != null && !name.isNullOrBlank()) {
                val passphrase = group.passphrase ?: chosenPassphrase
                log(
                    "Phone-hosted Wi-Fi Direct group is up: \"$name\" (WPA2, ${group.frequency}MHz) " +
                        "on ${group.`interface`}."
                )
                return Result.success(PhoneHostedNetwork.Credentials(name, passphrase, "WPA2"))
            }
            delay(GROUP_POLL_MS)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val p2p = manager ?: return
        val opened = channel ?: return
        if (formed) {
            log("Releasing the phone-hosted Wi-Fi Direct group.")
            removeFormedGroup(p2p, opened)
        }
        runCatching { opened.close() }
    }

    private fun removeFormedGroup(p2p: WifiP2pManager, opened: WifiP2pManager.Channel) {
        formed = false
        runCatching { p2p.removeGroup(opened, null) }
    }

    private suspend fun groupInfo(p2p: WifiP2pManager, channel: WifiP2pManager.Channel): WifiP2pGroup? =
        withTimeoutOrNull(FRAMEWORK_CALL_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                runCatching {
                    p2p.requestGroupInfo(channel) { group -> if (continuation.isActive) continuation.resume(group) }
                }.onFailure { if (continuation.isActive) continuation.resume(null) }
            }
        }

    /** Runs one ActionListener call; null when it succeeded, else the framework's reason. */
    private suspend fun action(call: (WifiP2pManager.ActionListener) -> Unit): Int? {
        // A sentinel, not null, for success: withTimeoutOrNull answers null for a timeout, and a
        // null success would be indistinguishable from it.
        val outcome = withTimeoutOrNull(FRAMEWORK_CALL_TIMEOUT_MS) {
            suspendCancellableCoroutine<Int> { continuation ->
                val listener = object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (continuation.isActive) continuation.resume(ACTION_SUCCEEDED)
                    }

                    override fun onFailure(reason: Int) {
                        if (continuation.isActive) continuation.resume(reason)
                    }
                }
                runCatching { call(listener) }
                    .onFailure { if (continuation.isActive) continuation.resume(WifiP2pManager.ERROR) }
            }
        } ?: WifiP2pManager.BUSY
        return outcome.takeUnless { it == ACTION_SUCCEEDED }
    }

    private fun failure(message: String): Result<PhoneHostedNetwork.Credentials> =
        Result.failure(IllegalStateException(message))

    companion object {
        /** Every group this app forms starts with this; nothing else is ever removed. */
        const val NAME_PREFIX = "DIRECT-MH-"
        const val PASSPHRASE_LENGTH = 12
        private const val FRAMEWORK_CALL_TIMEOUT_MS = 5_000L
        private const val GROUP_POLL_MS = 250L
        private const val ACTION_SUCCEEDED = -1
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"

        /** `DIRECT-` + two alphanumerics is the platform's rule; [NAME_PREFIX] satisfies it. */
        internal fun groupName(suffix: String): String = NAME_PREFIX + suffix

        internal fun token(random: SecureRandom, length: Int): String =
            buildString(length) { repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

        private fun describeReason(reason: Int): String = when (reason) {
            WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct unsupported"
            WifiP2pManager.BUSY -> "busy"
            WifiP2pManager.NO_SERVICE_REQUESTS -> "no service requests"
            WifiP2pManager.ERROR -> "internal error"
            else -> "reason $reason"
        }
    }
}
