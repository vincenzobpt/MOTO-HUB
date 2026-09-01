// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.os.Process
import io.motohub.android.feature.settings.MotoHubSettings
import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.session.ProjectionEventLog
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Joins a CFMoto dash that runs as a **Wi-Fi Direct Group Owner** (SSID `DIRECT-...`, e.g.
 * CL-C450 / some "go" units) instead of a normal WPA2 access point. [TBoxNetworkConnector]'s
 * `WifiNetworkSpecifier` cannot associate to a P2P Group Owner as a proper client, so those
 * dashes need this path.
 *
 * Adapted from OpenCfMoto's `BikeWifiP2p`, with extra robustness: a peer-discovery kick before
 * connect (required on some devices), fast failure when P2P is off, an immediate check for a
 * pre-existing group, and a single-settle guard against duplicate connection broadcasts.
 *
 * Joins by credentials (`setNetworkName` + passphrase from the saved [MotorcycleProfile]) as a
 * legacy P2P client, then resolves:
 *  - the bike gateway (the Group Owner, always `192.168.49.1` by Android's P2P convention), and
 *  - the phone's own `192.168.49.x` address on the `p2p-*` interface.
 *
 * A P2P group produces no `ConnectivityManager.Network`; the caller binds its sockets to the
 * returned phone address instead (see [TBoxLink.WifiDirect]).
 */
class TBoxWifiDirectConnector(
    context: Context,
    private val log: (String) -> Unit = { ProjectionEventLog.record("WIFI_DIRECT", it) }
) {
    private val appContext = context.applicationContext

    /** True when the profile's SSID is a Wi-Fi Direct group name. */
    fun isWifiDirectProfile(profile: MotorcycleProfile): Boolean = isWifiDirectSsid(profile.ssid)

    suspend fun connect(profile: MotorcycleProfile): Result<TBoxLink.WifiDirect> =
        withContext(Dispatchers.IO) {
            // Before the framework, not after: a process without NEARBY_WIFI_DEVICES gets the same
            // bare "internal error" from discoverPeers() and connect() that a wedged P2P stack
            // gives, and no amount of retrying or state-clearing can tell the two apart or fix
            // either. Checked here rather than in the UI because the join also runs headless, on
            // behalf of a companion app whose own grant says nothing about this process's.
            if (!WifiDirectGate.hasNearbyDevicesPermission(appContext)) {
                log(
                    "Wi-Fi Direct join refused before it started: this app has no " +
                        "NEARBY_WIFI_DEVICES permission, so the framework would reject every " +
                        "discoverPeers()/connect() with a bare internal error."
                )
                return@withContext Result.failure(
                    IllegalStateException(WifiDirectGate.missingPermissionMessage(appName()))
                )
            }
            val manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
                ?: return@withContext Result.failure(
                    IllegalStateException("This device has no Wi-Fi Direct (P2P) support.")
                )
            val channel = manager.initialize(appContext, Looper.getMainLooper(), null)
                ?: return@withContext Result.failure(
                    IllegalStateException("Wi-Fi Direct is unavailable (channel could not be initialized).")
                )
            var receiver: BroadcastReceiver? = null
            var handedOff = false
            val footprint = P2pJoinFootprint()
            // Settled exactly once: CompletableDeferred.complete() ignores every later call, so
            // racing connection-changed broadcasts (each spawning an async requestConnectionInfo)
            // cannot resume this twice.
            val outcome = CompletableDeferred<Result<TBoxLink.WifiDirect>>()
            try {
                val link = withTimeout(CONNECT_TIMEOUT_MS) {
                    receiver = registerReceiver(manager, channel, profile, outcome)
                    // Adopting comes before joining, and the check is awaited rather than left to
                    // race the join: asking the framework to form a group that is already up is
                    // how a working link gets torn back down.
                    if (adoptsExistingGroup(manager, channel, profile, outcome)) {
                        footprint.adoptedExistingGroup = true
                    } else {
                        join(manager, channel, profile, outcome, footprint)
                    }
                    outcome.await()
                }.getOrThrow()
                handedOff = true
                Result.success(link)
            } catch (timeout: TimeoutCancellationException) {
                Result.failure(
                    IllegalStateException(
                        "No Wi-Fi Direct group formed within ${CONNECT_TIMEOUT_MS / 1000}s for ${profile.ssid}. " +
                            "Make sure the dash screen is on and, if the phone shows a Wi-Fi Direct invitation, accept it."
                    )
                )
            } catch (cancelled: CancellationException) {
                // A user cancel or scope teardown must stay a cancellation - turning it into a
                // Result.failure made the UI flash a spurious error after "Annulla".
                throw cancelled
            } catch (failure: Throwable) {
                Result.failure(failure)
            } finally {
                receiver?.let { runCatching { appContext.unregisterReceiver(it) } }
                // The group must outlive connect(): EasyConn discovery and the three reverse
                // sockets run over it. Removing it here made every successful P2P join look like
                // a dead dash a few milliseconds later. Failed/cancelled joins are still cleaned
                // up immediately; successful ones are released by TBoxSessionRegistry.clear(),
                // whose leaveGroup closure also closes the channel.
                //
                // NonCancellable because the cancellation paths - the rider tapping Annulla, the
                // session scope going away - are precisely the ones that must still hand the
                // framework back: a suspending cleanup on a cancelled coroutine would return at
                // its first suspension point and leak everything below it.
                if (!handedOff) {
                    withContext(NonCancellable) { releaseP2pState(manager, channel, footprint) }
                }
            }
        }

    /**
     * Takes over a group the COMPANION APP formed, using the addresses it resolved there.
     *
     * The local 192.168.49.x address of a group is not readable from a process that did not form
     * it - `NetworkInterface` simply never shows it. Field log, samsung SM-S918B on Android 16
     * (2026-08-06): 35 of 44 handovers died with "no usable 192.168.49.x address appeared on
     * null" after the full 10s poll, in the same second the companion app had printed its own
     * address; the handful that worked were the ones where Core had itself just formed a group.
     * So Core stops looking and trusts the addresses the forming process passes across.
     *
     * What is still verified is that the group is really there and really the dash's: the
     * framework answers [WifiP2pManager.requestConnectionInfo] for any process. The group is
     * never released from here - the process that formed it owns it.
     */
    @SuppressLint("MissingPermission")
    suspend fun adoptFormedGroup(
        profile: MotorcycleProfile,
        localIpv4: Inet4Address,
        groupOwnerIpv4: Inet4Address
    ): Result<TBoxLink.WifiDirect> = withContext(Dispatchers.IO) {
        val manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            ?: return@withContext Result.failure(
                IllegalStateException("This device has no Wi-Fi Direct (P2P) support.")
            )
        val channel = manager.initialize(appContext, Looper.getMainLooper(), null)
            ?: return@withContext Result.failure(
                IllegalStateException("Wi-Fi Direct is unavailable (channel could not be initialized).")
            )
        try {
            // Polled, not asked once: the handover crosses a Binder call, and the framework can
            // still be publishing the group to this process when the request lands.
            val deadline = System.nanoTime() + ADOPT_VERIFY_TIMEOUT_MS * 1_000_000
            var lastState = "no answer from the framework"
            while (System.nanoTime() < deadline) {
                val info = awaitQuery<WifiP2pInfo> { resume ->
                    manager.requestConnectionInfo(channel) { resume(it) }
                }
                val group = awaitQuery<WifiP2pGroup> { resume ->
                    manager.requestGroupInfo(channel) { resume(it) }
                }
                when {
                    info == null || !info.groupFormed ->
                        lastState = "no Wi-Fi Direct group is formed on this phone any more"
                    info.isGroupOwner ->
                        lastState = "this phone is the Group Owner, so the dash is not"
                    !groupBelongsToProfile(group?.networkName, group?.owner?.deviceName, profile.ssid) ->
                        lastState = "the formed group is '${group?.networkName}' " +
                            "(owner '${group?.owner?.deviceName}'), not ${profile.ssid}"
                    else -> {
                        val gateway = info.groupOwnerAddress as? Inet4Address ?: groupOwnerIpv4
                        log(
                            "Adopting the Wi-Fi Direct group ${profile.ssid} formed by the " +
                                "companion app: phone=${localIpv4.hostAddress}, " +
                                "dash(GO)=${gateway.hostAddress}. Its addresses come from that " +
                                "app; this process never releases the group."
                        )
                        return@withContext Result.success(
                            TBoxLink.WifiDirect(
                                bindIp = localIpv4,
                                gatewayIp = gateway,
                                leaveGroup = {},
                                appContext = appContext,
                                formedElsewhere = true
                            )
                        )
                    }
                }
                delay(ADOPT_VERIFY_POLL_MS)
            }
            Result.failure(
                IllegalStateException(
                    "The Wi-Fi Direct group for ${profile.ssid} was gone by the time Core looked: " +
                        "$lastState. Retry the connection with the dash screen on."
                )
            )
        } finally {
            runCatching { channel.close() }
        }
    }

    private fun registerReceiver(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        profile: MotorcycleProfile,
        outcome: CompletableDeferred<Result<TBoxLink.WifiDirect>>
    ): BroadcastReceiver {
        fun settle(result: Result<TBoxLink.WifiDirect>) {
            outcome.complete(result)
        }

        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                            WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        if (!enabled) {
                            settle(
                                Result.failure(
                                    IllegalStateException("Wi-Fi Direct is off; enable Wi-Fi and retry.")
                                )
                            )
                        }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        checkForFormedGroup(manager, channel, profile, ::settle)
                    }
                }
            }
        }
        registerSystemReceiver(receiver, filter)

        // A leftover/persistent group may already be formed before this receiver saw any broadcast.
        checkForFormedGroup(manager, channel, profile, ::settle)
        return receiver
    }

    /**
     * True when this phone is already a client in the dash's group, so there is nothing to join -
     * the group check [registerReceiver] fired is on its way to resolving it into a link.
     *
     * This is what lets the join work from a process with no screen. Android answers
     * `connect()` with a bare `ERROR` when the caller has no visible activity, and Core is
     * exactly that whenever it is only servicing the companion app's bridge: riders' logs show
     * every AIDL-driven join refused in milliseconds - eight riders, seven phone models - while
     * the same phone joined the same dash fine from Core's own screen. The companion app now
     * forms the group in its own foreground process and Core adopts it here.
     *
     * Core's own Android Auto recovery gains the same thing: when the dash drops the session but
     * the group survives, recovery reuses it instead of asking a backgrounded Core for a join the
     * framework will not grant.
     *
     * The accept condition deliberately mirrors [checkForFormedGroup]'s, including its treatment
     * of an unreadable group name, so the two cannot disagree about the same group.
     */
    @SuppressLint("MissingPermission")
    private suspend fun adoptsExistingGroup(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        profile: MotorcycleProfile,
        outcome: CompletableDeferred<Result<TBoxLink.WifiDirect>>
    ): Boolean {
        val info = awaitQuery<WifiP2pInfo> { resume ->
            manager.requestConnectionInfo(channel) { resume(it) }
        } ?: return false
        // An inverted group (this phone as owner) is a failure, not something to adopt;
        // checkForFormedGroup settles it as one and join() then returns on the completed outcome.
        if (!info.groupFormed || info.isGroupOwner) return false
        val group = awaitQuery<WifiP2pGroup> { resume ->
            manager.requestGroupInfo(channel) { resume(it) }
        }
        if (!groupBelongsToProfile(group?.networkName, group?.owner?.deviceName, profile.ssid)) return false
        log(
            "Wi-Fi Direct group ${group?.networkName ?: profile.ssid} is already formed and this " +
                "phone is a client in it; adopting it instead of joining again."
        )
        // Resolve it here rather than leaning on the opportunistic check [registerReceiver] fires:
        // skipping the join means no connection-changed broadcast is coming, so if that one call
        // had been dropped nothing else would ever settle and the adopted group would sit out the
        // whole 35s budget. Settling twice is free - CompletableDeferred keeps the first result.
        checkForFormedGroup(manager, channel, profile) { outcome.complete(it) }
        return true
    }

    /**
     * Joins the dash, giving a wedged P2P stack time to come back rather than reporting its
     * first refusal as the dash's fault.
     *
     * A framework that refuses `discoverPeers()` and rejects `connect()` in milliseconds is not
     * answering about the dash at all - it is a stack that has not finished letting go of the
     * group we just tore down. One round of [attemptJoin] takes about two and a half seconds
     * there, so the old single round turned a 35s budget into a 2.5s one, and the watchdog above
     * spent the rider's whole recovery window re-asking the same question 41 times.
     * Field log 7c7e9e44 (CFMOTO 800MT-X, POCO/HyperOS, 2026-08-24): the video pipe died at
     * 18:31:32, and every one of the 41 recovery attempts that followed over the next five
     * minutes was refused this way; a join an hour later was accepted at once.
     *
     * So a refused round is now retried inside the same budget, with a settle in between, and
     * only the last one reports a failure - one that names the fix (toggle Wi-Fi) instead of
     * repeating the framework's bare "internal error".
     */
    private suspend fun join(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        profile: MotorcycleProfile,
        outcome: CompletableDeferred<Result<TBoxLink.WifiDirect>>,
        footprint: P2pJoinFootprint
    ) {
        val startedAt = System.nanoTime()
        var round = 1
        var waitingForWindow = false
        while (true) {
            if (outcome.isCompleted) return
            if (!hasNoVisibleWindow(processImportance())) footprint.everHadAWindow = true
            val reason = attemptJoin(manager, channel, profile, outcome, footprint)
                ?: return // Accepted, pending, or a failure this round already published.
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
            if (!shouldSettleAndRetryJoin(elapsedMillis, CONNECT_TIMEOUT_MS)) {
                // The permission is already ruled out by connect()'s preflight, so the remaining
                // rider-fixable cause of a bare rejection is the phone-wide location toggle.
                // Named only when it is actually off - a hint that appears on every failure is
                // one riders learn to skip.
                val hint = if (WifiDirectGate.isLocationEnabled(appContext)) {
                    ""
                } else {
                    " ${WifiDirectGate.LOCATION_OFF_HINT}"
                }
                // The counts go to the log, not into the message: a companion app compares
                // consecutive failure reasons to tell "the same answer again" from "something
                // changed", and a message carrying elapsed seconds is never twice the same.
                log(
                    "Giving up this Wi-Fi Direct join: $round round(s) refused outright in " +
                        "${elapsedMillis / 1_000}s."
                )
                // The toggle advice belongs to a wedged stack, and only to one. A stack that
                // scanned, found the dash and named it is working; telling that rider to
                // restart Wi-Fi sends them to do again what they have very likely already done
                // - 94b0a3da did, between two identical failures - and hides the real answer,
                // which is that the phone refuses to join this dash by address. Advice that
                // cannot apply is not neutral: it becomes the thing investigated instead of
                // the cause.
                val diagnosis = joinRefusalAdvice(
                    ssid = profile.ssid,
                    appName = appName(),
                    peerSeen = footprint.peerSeen,
                    peerListClearedOnStop = footprint.peerListClearedOnStop,
                    everHadAWindow = footprint.everHadAWindow
                )
                outcome.complete(
                    Result.failure(
                        IllegalStateException(
                            "Wi-Fi Direct connect() failed: ${reasonName(reason)}. $diagnosis$hint"
                        )
                    )
                )
                return
            }
            round++
            // A process with no window on screen is refused for a reason no settle can change,
            // and sleeping 6s before asking again only spends the budget that the rider opening
            // the app would have used. Waiting for the window IS the retry here - see
            // [hasNoVisibleWindow] for why a foreground service does not count as one.
            if (hasNoVisibleWindow(processImportance())) {
                if (!waitingForWindow) {
                    waitingForWindow = true
                    log(
                        "The phone refused this join while ${appName()} had no window on " +
                            "screen; every round so far was rejected in milliseconds. Waiting " +
                            "for the app to come back on screen instead of settling - open " +
                            "${appName()} to reconnect now (round $round)."
                    )
                }
                val remaining = CONNECT_TIMEOUT_MS - (System.nanoTime() - startedAt) / 1_000_000
                if (awaitVisibleWindow(remaining - WEDGE_ROUND_COST_MS)) {
                    waitingForWindow = false
                    footprint.everHadAWindow = true
                    log("${appName()} is back on screen; asking for the Wi-Fi Direct join again.")
                }
                continue
            }
            log(
                "The phone's Wi-Fi Direct stack refused this join outright; letting it settle " +
                    "for ${WEDGE_SETTLE_MS / 1_000}s and trying again (round $round)."
            )
            delay(WEDGE_SETTLE_MS)
        }
    }

    /**
     * Polls until this process has a window on screen again, or until [budgetMillis] runs out.
     *
     * Polled rather than observed: the connector has no lifecycle owner of its own and runs on
     * behalf of a companion process too, where there is no activity of ours to listen to.
     */
    private suspend fun awaitVisibleWindow(budgetMillis: Long): Boolean {
        if (budgetMillis <= 0) return false
        val deadline = System.nanoTime() + budgetMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            delay(WINDOW_POLL_INTERVAL_MS)
            if (!hasNoVisibleWindow(processImportance())) return true
        }
        return false
    }

    /**
     * How close to the rider this process is, on Android's own scale (smaller is closer). The
     * same reading [TBoxNetworkConnector] takes for its specifier requests, for the same reason:
     * a refusal cannot say by itself whether the dash was missing or the asker was.
     */
    private fun processImportance(): Int {
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        return state.importance
    }

    /**
     * The app-op MODE behind a permission, which is not the same thing as the grant.
     *
     * The grant is what the rider sees in Settings and what [WifiDirectGate] already reports; the
     * mode is what the framework actually consults, and it flips to `MODE_IGNORED` on its own for
     * a process the platform considers backgrounded. That difference is invisible in every log we
     * have: they all say "granted" next to a refusal.
     */
    private fun appOpMode(op: String): String = runCatching {
        val ops = appContext.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        when (ops.unsafeCheckOpNoThrow(op, Process.myUid(), appContext.packageName)) {
            AppOpsManager.MODE_ALLOWED -> "allowed"
            AppOpsManager.MODE_IGNORED -> "IGNORED"
            AppOpsManager.MODE_ERRORED -> "ERRORED"
            AppOpsManager.MODE_DEFAULT -> "default"
            AppOpsManager.MODE_FOREGROUND -> "foreground-only"
            else -> "unknown"
        }
    }.getOrElse { "unreadable" }

    /**
     * One round of the join the OEM EasyConn app performs, which is the counterpart the dash was
     * built for: discover the peer, stop discovery, clear a half-open invitation, and only then
     * connect. Firing `connect()` straight after `discoverPeers()` - which is what this connector
     * used to do - leaves a scan running and a stale invitation in place, and the framework
     * answers with a bare `ERROR` ("internal error") or forms no group at all.
     *
     * Returns null when the round handed the outcome over - `connect()` accepted, a group
     * pending, or a failure already published - and the rejection reason when every `connect()`
     * of the round came back refused, which is [join]'s cue to settle and try again.
     */
    private suspend fun attemptJoin(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        profile: MotorcycleProfile,
        outcome: CompletableDeferred<Result<TBoxLink.WifiDirect>>,
        footprint: P2pJoinFootprint
    ): Int? {
        // A group that was already formed settles through the receiver; joining again would tear
        // down the link the caller is about to use.
        if (outcome.isCompleted) return null
        val peer = discoverPeer(manager, channel, profile, outcome, footprint)
        if (outcome.isCompleted) return null

        // Discovery and connect() contend for the same radio state machine; leaving the scan
        // running is a known way to get connect() rejected.
        awaitAction { listener -> manager.stopPeerDiscovery(channel, listener) }

        // Read back before connect(), not only after it was refused. Some frameworks empty
        // their peer list the moment discovery stops (see [connectWithRetry]), which turns the
        // address just discovered into an address the framework no longer knows - and the only
        // answer it has for that is a bare ERROR in single-digit milliseconds. The post-mortem
        // snapshot could never say whether the peer went before the call or because of it; this
        // one can. Logged only when it happened: a line on every join is noise.
        if (peer != null &&
            requestPeers(manager, channel).none { candidate ->
                candidate.deviceAddress.equals(peer.deviceAddress, ignoreCase = true)
            }
        ) {
            footprint.peerListClearedOnStop = true
            log(
                "The dash left the Wi-Fi Direct peer list as soon as discovery stopped, so a " +
                    "connect() by its address is about to be refused outright on this phone."
            )
        }

        if (peer?.status == WifiP2pDevice.INVITED || footprint.discoveryRefused) {
            // A half-open invitation keeps failing every new connect() until it is cancelled, and
            // it is not necessarily ours: the OEM companion app leaves them behind too. EasyConn
            // cancels and waits before retrying; so do we. A framework that would not even start
            // discovery is the same wedged stack seen from the other side - riders' logs show
            // "peer discovery could not start" and an instant connect() rejection together - so
            // it gets the same clear before we add a request of our own to the pile.
            log("Clearing a pending Wi-Fi Direct invitation before joining ${profile.ssid}.")
            awaitAction { listener -> manager.cancelConnect(channel, listener) }
            delay(CANCEL_SETTLE_MS)
        }
        if (outcome.isCompleted) return null

        return connectWithRetry(manager, channel, profile, peer, outcome, footprint)
    }

    /**
     * Looks for the dash among the discovered P2P peers. A group named `DIRECT-xy-<name>` is
     * Android's own convention, so the dash's P2P device name is recoverable from the saved SSID
     * and no MAC address has to be stored in the profile. Returns null when the peer never shows
     * up, in which case the caller falls back to joining by credentials.
     */
    @SuppressLint("MissingPermission")
    private suspend fun discoverPeer(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        profile: MotorcycleProfile,
        outcome: CompletableDeferred<Result<TBoxLink.WifiDirect>>,
        footprint: P2pJoinFootprint
    ): WifiP2pDevice? {
        val expectedName = expectedPeerName(profile.ssid)
        if (expectedName != peerNameFromGroupSsid(profile.ssid)) {
            log(
                "${profile.ssid} is not a DIRECT-xy-<name> group; looking for a Wi-Fi Direct " +
                    "peer named '$expectedName' instead."
            )
        }
        if (!awaitAction { listener -> manager.discoverPeers(channel, listener) }) {
            footprint.discoveryRefused = true
            log("Wi-Fi Direct peer discovery could not start; joining by credentials instead.")
            return null
        }
        footprint.discoveryStarted = true
        val deadline = System.nanoTime() + PEER_DISCOVERY_TIMEOUT_MS * 1_000_000
        while (System.nanoTime() < deadline && !outcome.isCompleted) {
            val match = requestPeers(manager, channel).firstOrNull { peer ->
                peer.deviceName.equals(expectedName, ignoreCase = true)
            }
            if (match != null) {
                footprint.peerSeen = true
                log(
                    "Found the dash as Wi-Fi Direct peer '${match.deviceName}' " +
                        "(${match.deviceAddress}), status ${statusName(match.status)}."
                )
                return match
            }
            delay(PEER_POLL_INTERVAL_MS)
        }
        log("The dash did not appear in Wi-Fi Direct discovery; joining ${profile.ssid} by credentials.")
        return null
    }

    /**
     * Issues `connect()`, retrying a rejection once. The first attempt joins by peer address
     * (what the OEM app does); the retry drops to the credentials join instead of repeating the
     * identical config. Some frameworks (seen on Xiaomi HyperOS / Android 16) clear their peer
     * list the moment discovery stops and then reject the address-based config with a bare
     * `ERROR` in ~2ms - the rejection snapshot shows "dash peer=not in the peer list" - so
     * re-sending the same address can never succeed there, while the credentials join carries
     * no peer address and is accepted by those same frameworks.
     *
     * Returns null when the outcome was handed over (accepted, pending, or a published failure)
     * and the last rejection reason when both attempts were refused - a refusal is [join]'s to
     * judge, because only it knows how much of the join budget is left to wait out a wedged
     * stack.
     */
    private suspend fun connectWithRetry(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        profile: MotorcycleProfile,
        peer: WifiP2pDevice?,
        outcome: CompletableDeferred<Result<TBoxLink.WifiDirect>>,
        footprint: P2pJoinFootprint
    ): Int? {
        var lastReason: Int? = null
        for (attempt in 0 until CONNECT_ATTEMPTS) {
            if (outcome.isCompleted) return null
            val joinPeer = peer?.takeIf { attempt == 0 }
            // buildConfig(profile, null) is the credentials form; it returns null when the SSID
            // or passphrase cannot express one (setNetworkName rejects non-"DIRECT-" names,
            // build() rejects a bad passphrase length).
            var addressPeer = joinPeer
            var rediscovered = false
            var config = buildConfig(profile, joinPeer)
            if (config == null) {
                // No credentials form for this profile. Falling back to the address config
                // unchanged - which is what this code used to do - re-sends the request that
                // was just refused, and the retry that exists precisely for a flushed peer list
                // becomes a second copy of the failure. Worse, it was logged as "as a legacy
                // client": field log 94b0a3da has four rounds of a fallback that never ran.
                //
                // Find the dash again instead and connect with the scan STILL RUNNING. That is
                // the one form that carries an address the framework currently knows, and on a
                // stack that empties its list when discovery stops it is the only one left.
                if (peer == null) {
                    outcome.complete(
                        Result.failure(
                            IllegalStateException(
                                "Wi-Fi Direct join is not possible for ${profile.ssid}: the dash " +
                                    "did not answer Wi-Fi Direct discovery, and its name cannot " +
                                    "be used as a group name either. Make sure the dash screen " +
                                    "is on and showing its connection page, then retry."
                            )
                        )
                    )
                    return null
                }
                addressPeer = discoverPeer(manager, channel, profile, outcome, footprint) ?: peer
                if (outcome.isCompleted) return null
                rediscovered = true
                // The peer form of buildConfig cannot fail, but a return that settles nothing
                // would leave the join sitting out the whole 35s budget for a question already
                // answered - so the impossible branch still publishes a failure.
                config = buildConfig(profile, addressPeer) ?: run {
                    outcome.complete(
                        Result.failure(
                            IllegalStateException(
                                "Wi-Fi Direct join is not possible for ${profile.ssid}: its name " +
                                    "cannot be used as a group name, and no peer address for the " +
                                    "dash could be built either."
                            )
                        )
                    )
                    return null
                }
            }
            log(
                when {
                    rediscovered ->
                        "Joining the dash at ${addressPeer?.deviceAddress} with discovery still " +
                            "running (attempt ${attempt + 1}): ${profile.ssid} is not a group " +
                            "name, so there is no credentials join to fall back to."
                    joinPeer != null ->
                        "Joining the dash at ${joinPeer.deviceAddress} (attempt ${attempt + 1})."
                    else ->
                        "Joining Wi-Fi Direct group ${profile.ssid} as a legacy client " +
                            "(attempt ${attempt + 1})."
                }
            )
            // Set before the call, not after: a rejected connect() can still have left an
            // invitation behind on some frameworks, and the cleanup must know to cancel it.
            footprint.connectIssued = true
            when (val reason = issueConnect(manager, channel, config)) {
                null -> {
                    log("Wi-Fi Direct connect() accepted; waiting for the group to form.")
                    return null
                }
                WifiP2pManager.BUSY -> {
                    // A stale group is being torn down; the connection-changed broadcast still fires.
                    log("Wi-Fi Direct connect() busy; waiting for the pending group.")
                    return null
                }
                else -> {
                    lastReason = reason
                    if (attempt == CONNECT_ATTEMPTS - 1) {
                        logConnectRejectionDiagnostics(manager, channel, profile, addressPeer ?: peer)
                        return reason
                    }
                    log("Wi-Fi Direct connect() failed (${reasonName(reason)}); retrying.")
                    logConnectRejectionDiagnostics(manager, channel, profile, addressPeer ?: peer)
                    delay(CONNECT_RETRY_DELAY_MS)
                }
            }
        }
        return lastReason
    }

    /**
     * One bounded snapshot of the P2P stack, taken when `connect()` was rejected. The bare
     * `ERROR` reason carries no context at all: whether a group already exists (and who owns
     * it), whether discovery is still running, and what state the dash peer is in is exactly
     * what separates "another app holds the P2P state machine" from "this framework refuses
     * P2P outright" - the two causes riders' diagnostic logs could not distinguish so far.
     */
    // Same permission gate as the rest of this connector; see issueConnect.
    @SuppressLint("MissingPermission")
    private suspend fun logConnectRejectionDiagnostics(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        profile: MotorcycleProfile,
        peer: WifiP2pDevice?
    ) {
        val line = withTimeoutOrNull(FRAMEWORK_CALL_TIMEOUT_MS) {
            val p2pState = awaitQuery<Int> { resume ->
                manager.requestP2pState(channel) { state -> resume(state) }
            }
            val discoveryState = awaitQuery<Int> { resume ->
                manager.requestDiscoveryState(channel) { state -> resume(state) }
            }
            val connection = awaitQuery<WifiP2pInfo> { resume ->
                manager.requestConnectionInfo(channel) { info -> resume(info) }
            }
            val group = awaitQuery<WifiP2pGroup> { resume ->
                manager.requestGroupInfo(channel) { info -> resume(info) }
            }
            // expectedPeerName, not peerNameFromGroupSsid: the latter is null for a profile
            // saved under the dash's device name, so on exactly the dashes this snapshot was
            // needed for the name branch never ran and only the address could ever match.
            val expectedName = expectedPeerName(profile.ssid)
            val dash = requestPeers(manager, channel).firstOrNull { candidate ->
                (peer != null && candidate.deviceAddress.equals(peer.deviceAddress, ignoreCase = true)) ||
                    candidate.deviceName.equals(expectedName, ignoreCase = true)
            }
            val groupDescription = if (group == null) {
                "none"
            } else {
                // An existing group here is the smoking gun: this join never formed one, so it
                // belongs to someone else (another app's session, or a leftover the phone owns).
                "'${group.networkName}' (owner=${group.owner?.deviceName?.takeIf(String::isNotBlank) ?: group.owner?.deviceAddress ?: "?"}, " +
                    "ownedByPhone=${group.isGroupOwner}, clients=${group.clientList?.size ?: 0})"
            }
            "P2P state after the rejection: p2p=${p2pStateName(p2pState)}, " +
                "discovery=${discoveryStateName(discoveryState)}, " +
                "groupFormed=${connection?.groupFormed == true}, group=$groupDescription, " +
                "dash peer=${dash?.let { statusName(it.status) } ?: "not in the peer list"}."
        } ?: "P2P state after the rejection: the framework did not answer the state queries."
        // Appended even when the framework went silent: these two are what it consults before
        // answering at all, and reading them costs no framework call. Without them a bare ERROR
        // is indistinguishable from a hardware fault in a mailed-in log.
        val importance = processImportance()
        log(
            "$line Permission gate: nearbyDevices=" +
                (if (WifiDirectGate.hasNearbyDevicesPermission(appContext)) "granted" else "DENIED") +
                ", locationServices=" +
                (if (WifiDirectGate.isLocationEnabled(appContext)) "on" else "OFF") +
                // Grants alone have never explained one of these refusals. What the framework
                // consults is the app-op mode and how close to the rider this process is, and
                // both change without the grant changing - which is why they are read here and
                // not left to be inferred from a log that only ever says "granted".
                ", app=${importanceName(importance)} ($importance)" +
                ", window=${if (hasNoVisibleWindow(importance)) "NONE" else "on screen"}" +
                ", nearbyDevicesOp=${appOpMode(OPSTR_NEARBY_WIFI_DEVICES)}" +
                ", fineLocationOp=${appOpMode(AppOpsManager.OPSTR_FINE_LOCATION)}."
        )
    }

    /** This app's own display name, so a permission message names the app the rider must open. */
    private fun appName(): String = runCatching {
        appContext.applicationInfo.loadLabel(appContext.packageManager).toString()
    }.getOrDefault("MOTO-HUB")

    /** Runs one framework state query and waits briefly for its callback; null when it never answers. */
    private suspend fun <T> awaitQuery(query: (resume: (T?) -> Unit) -> Unit): T? =
        withTimeoutOrNull(FRAMEWORK_CALL_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                runCatching { query { value -> if (continuation.isActive) continuation.resume(value) } }
                    .onFailure { if (continuation.isActive) continuation.resume(null) }
            }
        }

    /**
     * Prefers the peer-address form the dash's own companion app uses; falls back to the
     * credential join when discovery never surfaced the peer. Returns null once the failure has
     * been reported by the caller.
     */
    private fun buildConfig(profile: MotorcycleProfile, peer: WifiP2pDevice?): WifiP2pConfig? {
        if (peer != null) {
            return WifiP2pConfig().apply {
                deviceAddress = peer.deviceAddress
                // The dash must own the group: at 0 this phone asks to be the least likely
                // Group Owner, which is exactly the role split checkForFormedGroup enforces.
                groupOwnerIntent = 0
                wps.setup = when {
                    peer.wpsPbcSupported() -> WpsInfo.PBC
                    peer.wpsKeypadSupported() -> WpsInfo.KEYPAD
                    peer.wpsDisplaySupported() -> WpsInfo.DISPLAY
                    else -> wps.setup
                }
            }
        }
        return runCatching {
            WifiP2pConfig.Builder()
                .setNetworkName(profile.ssid)
                .setPassphrase(profile.password)
                .enablePersistentMode(false)
                .build()
        }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestPeers(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel
    ): Collection<WifiP2pDevice> = withTimeoutOrNull(FRAMEWORK_CALL_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            runCatching {
                manager.requestPeers(channel) { peers ->
                    if (continuation.isActive) continuation.resume(peers.deviceList)
                }
            }.onFailure { if (continuation.isActive) continuation.resume(emptyList()) }
        }
    } ?: emptyList()

    // Same permission gate as the rest of this connector: NEARBY_WIFI_DEVICES/ACCESS_FINE_LOCATION
    // are requested by the connection UI before any T-Box join starts.
    /** Returns null when the request was accepted, otherwise the framework's rejection reason. */
    @SuppressLint("MissingPermission")
    private suspend fun issueConnect(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        config: WifiP2pConfig
    ): Int? {
        val answer = withTimeoutOrNull(CONNECT_CALL_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (continuation.isActive) continuation.resume(CONNECT_ACCEPTED)
                    }
                    override fun onFailure(reason: Int) {
                        if (continuation.isActive) continuation.resume(reason)
                    }
                }
                runCatching { manager.connect(channel, config, listener) }
                    .onFailure { if (continuation.isActive) continuation.resume(WifiP2pManager.ERROR) }
            }
        }
        // A silent framework is treated as a rejection, not as an acceptance: the caller then
        // retries rather than sitting out the whole join budget waiting for a group nobody asked for.
        return when (answer) {
            null -> WifiP2pManager.ERROR
            CONNECT_ACCEPTED -> null
            else -> answer
        }
    }

    /**
     * Runs a fire-and-forget framework call and waits for its ActionListener. Bounded, because a
     * few devices never answer at all and the rider should not sit through the whole join budget
     * waiting on a preparation step.
     */
    private suspend fun awaitAction(
        action: (WifiP2pManager.ActionListener) -> Unit
    ): Boolean = withTimeoutOrNull(FRAMEWORK_CALL_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            val listener = object : WifiP2pManager.ActionListener {
                override fun onSuccess() { if (continuation.isActive) continuation.resume(true) }
                override fun onFailure(reason: Int) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }
            runCatching { action(listener) }
                .onFailure { if (continuation.isActive) continuation.resume(false) }
        }
    } ?: false

    // NEARBY_WIFI_DEVICES/ACCESS_FINE_LOCATION are requested by the connection UI before any
    // T-Box join starts (same gate as the WifiNetworkSpecifier path).
    @SuppressLint("MissingPermission")
    private fun checkForFormedGroup(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        profile: MotorcycleProfile,
        settle: (Result<TBoxLink.WifiDirect>) -> Unit
    ) {
        manager.requestConnectionInfo(channel) { info ->
            if (info == null || !info.groupFormed) return@requestConnectionInfo
            val gateway = info.groupOwnerAddress as? Inet4Address
            if (gateway == null) {
                settle(Result.failure(IllegalStateException("Wi-Fi Direct group formed without an IPv4 group owner.")))
                return@requestConnectionInfo
            }
            if (info.isGroupOwner) {
                // The dash must be the GO: with the roles inverted, 192.168.49.1 is the phone
                // itself and every probe would just talk to the phone. Fail loudly; the caller's
                // cleanup removes the inverted group so the next attempt negotiates fresh.
                settle(
                    Result.failure(
                        IllegalStateException(
                            "The phone became the Wi-Fi Direct Group Owner instead of joining " +
                                "${profile.ssid}. The group was released; retry the connection " +
                                "with the dash screen on."
                        )
                    )
                )
                return@requestConnectionInfo
            }
            manager.requestGroupInfo(channel) { group ->
                // A formed group is not necessarily OUR group: a leftover/persistent group
                // toward another DIRECT- device (a different bike, a cast dongle) also reports
                // groupFormed. Joining it would make discovery fail with a misleading "dash did
                // not answer". Remove the stale group and keep waiting for the requested one.
                //
                // Only on proof that it belongs to someone else, though - see
                // [groupBelongsToProfile]. This is the branch that removed a rider's working
                // link because his dash's group is named `DIRECT-iY` and his profile is named
                // after the dash's P2P device, two strings that can never be equal.
                val groupName = group?.networkName
                val ownerName = group?.owner?.deviceName
                if (!groupBelongsToProfile(groupName, ownerName, profile.ssid)) {
                    log(
                        "Ignoring formed Wi-Fi Direct group '$groupName' (owner '$ownerName'): " +
                            "it is not ${profile.ssid}. Removing the stale group and waiting " +
                            "for the join."
                    )
                    removeGroup(manager, channel, closeChannelAfter = false)
                    return@requestGroupInfo
                }
                resolveLocalAddress(
                    iface = group?.`interface`,
                    gateway = gateway,
                    leaveGroup = { removeGroup(manager, channel, closeChannelAfter = true) },
                    settle = settle
                )
            }
        }
    }

    private fun resolveLocalAddress(
        iface: String?,
        gateway: Inet4Address,
        leaveGroup: () -> Unit,
        settle: (Result<TBoxLink.WifiDirect>) -> Unit
    ) {
        // DHCP on the p2p link can lag the "group formed" event; poll off the main thread.
        Thread({
            val bindIp = pollLocalP2pIpv4(iface)
            if (bindIp == null) {
                // What the process could actually see, not just that it saw nothing: this poll
                // comes up empty for two very different reasons - DHCP still pending on a group
                // this process formed, or a group formed by ANOTHER process, whose address never
                // becomes visible here at all. Without the interface list they read identically,
                // which is exactly how the companion-app handover failure hid for weeks.
                log("Interfaces visible while waiting for the p2p address: ${visibleInterfaces()}")
                settle(
                    Result.failure(
                        IllegalStateException(
                            "Wi-Fi Direct group formed but no usable 192.168.49.x address appeared on $iface."
                        )
                    )
                )
            } else {
                log("Wi-Fi Direct connected: phone=${bindIp.hostAddress}, dash(GO)=${gateway.hostAddress}.")
                settle(
                    Result.success(
                        TBoxLink.WifiDirect(
                            bindIp = bindIp,
                            gatewayIp = gateway,
                            leaveGroup = leaveGroup,
                            appContext = appContext,
                            releaseGroupOnDisconnect =
                                !MotoHubSettings.keepWifiDirectAfterDisconnect(appContext)
                        )
                    )
                )
            }
        }, "tbox-p2p-ip").apply { isDaemon = true }.start()
    }

    private fun pollLocalP2pIpv4(iface: String?): Inet4Address? {
        val deadline = System.nanoTime() + IP_POLL_TIMEOUT_MS * 1_000_000
        while (System.nanoTime() < deadline) {
            localP2pIpv4(iface)?.let { return it }
            try {
                Thread.sleep(IP_POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return null
            }
        }
        return localP2pIpv4(iface)
    }

    /** Every up, non-loopback interface with its IPv4 addresses - or why the list is unavailable. */
    private fun visibleInterfaces(): String = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .toList()
            .filter { it.isUp && !it.isLoopback }
            .joinToString("; ") { nic ->
                val addresses = nic.inetAddresses
                    .toList()
                    .filterIsInstance<Inet4Address>()
                    .mapNotNull { it.hostAddress }
                "${nic.name}=[${addresses.joinToString(",")}]"
            }
            .ifBlank { "none" }
    }.getOrElse { "unreadable (${it.javaClass.simpleName}: ${it.message})" }

    private fun localP2pIpv4(iface: String?): Inet4Address? = runCatching {
        for (nic in NetworkInterface.getNetworkInterfaces()) {
            if (!nic.isUp || nic.isLoopback) continue
            val nameMatches = iface == null || nic.name == iface || nic.name.startsWith("p2p")
            for (address in nic.inetAddresses) {
                if (address !is Inet4Address || address.isLoopbackAddress) continue
                val host = address.hostAddress ?: continue
                // Never accept the GO's own address as the phone's source: that only happens
                // when the phone ended up as Group Owner, which the join already rejects.
                if (host == GROUP_OWNER_IP) continue
                if (nic.name == iface) return address
                if (nameMatches && host.startsWith("192.168.49.")) return address
            }
        }
        null
    }.getOrNull()

    private fun registerSystemReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }
    }

    private fun reasonName(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P unsupported"
        WifiP2pManager.ERROR -> "internal error"
        WifiP2pManager.BUSY -> "busy"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "no service requests"
        else -> "reason $reason"
    }

    private fun p2pStateName(state: Int?): String = when (state) {
        WifiP2pManager.WIFI_P2P_STATE_ENABLED -> "enabled"
        WifiP2pManager.WIFI_P2P_STATE_DISABLED -> "disabled"
        null -> "unknown"
        else -> "state $state"
    }

    private fun discoveryStateName(state: Int?): String = when (state) {
        WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED -> "running"
        WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED -> "stopped"
        null -> "unknown"
        else -> "state $state"
    }

    private fun statusName(status: Int): String = when (status) {
        WifiP2pDevice.CONNECTED -> "connected"
        WifiP2pDevice.INVITED -> "invited"
        WifiP2pDevice.FAILED -> "failed"
        WifiP2pDevice.AVAILABLE -> "available"
        WifiP2pDevice.UNAVAILABLE -> "unavailable"
        else -> "status $status"
    }

    /**
     * Hands the P2P state machine back after a join that did not produce a link, in the order the
     * framework wants it: stop the scan this join started, cancel the invitation it left
     * outstanding, remove any group it formed, and only then close the channel.
     *
     * The invitation is the part that used to leak, and [removeGroup] alone cannot clear it: when
     * `connect()` was accepted and no group ever formed - the 35s timeout, which is the failure
     * riders actually report - there is no group to remove and the request stays pending in the
     * framework. The next attempt then meets a stack that will not start peer discovery and that
     * rejects `connect()` with a bare `ERROR` in milliseconds, so one failed join used to poison
     * every retry until Wi-Fi was toggled off and on. Field log 2026-07-30: a 35s timeout at
     * 10:18:09, and the 10:19:09 attempt never got past "peer discovery could not start".
     */
    private suspend fun releaseP2pState(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        footprint: P2pJoinFootprint
    ) {
        // Bounded as a whole, not per call: this runs while the rider waits for the error message,
        // and a framework that has stopped answering must not get to add up its timeouts.
        withTimeoutOrNull(P2P_RELEASE_TIMEOUT_MS) {
            // Only our own scan: P2P discovery is global, and tearing down a failed join is no
            // reason to stop one another app started.
            if (footprint.discoveryStarted) {
                awaitAction { listener -> manager.stopPeerDiscovery(channel, listener) }
            }
            if (footprint.connectIssued) {
                // A refusal here is the framework saying nothing was pending, which is the
                // ordinary case; the line worth having in a rider's log is the one that says we
                // cleared something, because that is the leak this teardown exists for.
                if (awaitAction { listener -> manager.cancelConnect(channel, listener) }) {
                    log("Cancelled the pending Wi-Fi Direct invitation.")
                }
                // Same settle the OEM app allows itself: the cancel has to land before the next
                // framework call, or the removeGroup below races it.
                delay(CANCEL_SETTLE_MS)
            }
        }
        if (footprint.adoptedExistingGroup) {
            // See P2pJoinFootprint.adoptedExistingGroup: the group outlives this attempt, so all
            // that is left to hand back is the channel.
            log("Leaving the Wi-Fi Direct group up: this attempt adopted it rather than forming it.")
            runCatching { channel.close() }
            return
        }
        // Outside the budget above: it is fire-and-forget, and it is also what closes the channel.
        removeGroup(manager, channel, closeChannelAfter = true)
    }

    private fun removeGroup(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        closeChannelAfter: Boolean
    ) {
        // The channel must stay open until removeGroup has completed, so it is closed from the
        // callbacks - closing it earlier silently cancels the pending framework call.
        fun finish() {
            if (closeChannelAfter) runCatching { channel.close() }
        }
        runCatching {
            manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    log("Wi-Fi Direct group released.")
                    finish()
                }
                override fun onFailure(reason: Int) = finish()
            })
        }.onFailure { finish() }
    }

    /**
     * What one join actually asked of the P2P state machine, so [releaseP2pState] undoes exactly
     * that and nothing else. Written by the join, read by the cleanup - which is why it is a
     * mutable holder rather than a return value: the join can end at a timeout or a cancellation,
     * both of which skip whatever it would have returned.
     */
    private class P2pJoinFootprint {
        /** `discoverPeers()` was accepted, so a scan of ours may still be running. */
        var discoveryStarted = false

        /**
         * `discoverPeers()` was rejected outright - the strongest signal available that something
         * else already holds the P2P state machine, which is why the join clears an invitation
         * before adding its own request.
         */
        var discoveryRefused = false

        /** `connect()` was called at all, accepted or not, so an invitation may be outstanding. */
        var connectIssued = false

        /**
         * Discovery surfaced the dash at least once during this join.
         *
         * Read only to decide what to tell the rider when the join gives up. A stack that ran a
         * scan and named the dash is not the wedged stack the "turn Wi-Fi off and on" advice was
         * written for, and rider 94b0a3da had already done exactly that - his 19:10 attempt, on
         * a Wi-Fi he had just toggled, failed identically to the three before it.
         */
        var peerSeen = false

        /** The dash fell out of the peer list when discovery was stopped. See [attemptJoin]. */
        var peerListClearedOnStop = false

        /**
         * This process had a window on screen at least once while this join was running.
         *
         * False is the whole finding of support case f014ce61 (VOGE 800 Rally, 2026-08-31): five
         * joins in one ride, and the three that were refused are exactly the three made after
         * `Main activity destroyed`. What the rider must do about that is open the app, which is
         * the opposite of the advice a wedged stack earns.
         */
        var everHadAWindow = false

        /**
         * The group was already up and this attempt adopted it instead of forming one.
         *
         * Nothing here may then remove it. Whoever formed it - the companion app, or an earlier
         * session of this process - is still counting on it, and a removed group is not a group
         * that can be joined again: field log 90438e1e (Voge, 2026-08-25) has Core adopting the
         * companion's group, failing to read its address, removing it on the way out, and then
         * being refused every join for the rest of the ride.
         */
        var adoptedExistingGroup = false
    }

    companion object {
        /**
         * Whole-join budget: peer discovery, the preparation calls, and the group forming. The
         * OEM app allows itself the same 35s, and the 25s this used to be expired while the
         * group was still negotiating on the riders who reported the failure.
         */
        private const val CONNECT_TIMEOUT_MS = 35_000L
        private const val PEER_DISCOVERY_TIMEOUT_MS = 6_000L
        private const val PEER_POLL_INTERVAL_MS = 600L
        private const val FRAMEWORK_CALL_TIMEOUT_MS = 3_000L
        private const val CONNECT_CALL_TIMEOUT_MS = 6_000L
        /** Distinguishes "connect() was accepted" from "the framework never answered". */
        private const val CONNECT_ACCEPTED = Int.MIN_VALUE
        private const val CANCEL_SETTLE_MS = 500L
        /** Whole-teardown budget, spent on a path where the rider is already waiting for an error. */
        private const val P2P_RELEASE_TIMEOUT_MS = 4_000L
        private const val CONNECT_ATTEMPTS = 2
        private const val CONNECT_RETRY_DELAY_MS = 1_200L

        /**
         * How long a stack that refused a whole join round is left alone before the next one.
         *
         * Long enough to be a different question than the one just refused - the refusals come
         * back in single-digit milliseconds - and short enough that four rounds still fit the
         * 35s budget, so a dash that is merely slow to answer is not starved of attempts.
         */
        private const val WEDGE_SETTLE_MS = 6_000L

        /**
         * How often a join that is waiting for the app to come back on screen looks again.
         *
         * The rider is tapping a notification or the launcher icon, so seconds is the resolution
         * that matters; polling faster would only wake the CPU on a phone strapped to a bike.
         */
        private const val WINDOW_POLL_INTERVAL_MS = 1_000L

        /**
         * `AppOpsManager.OPSTR_NEARBY_WIFI_DEVICES`, which the public SDK does not export.
         *
         * Passed as a literal on purpose: [appOpMode] reads it through `runCatching`, so on a
         * platform that does not know this op the log says "unreadable" rather than failing.
         */
        private const val OPSTR_NEARBY_WIFI_DEVICES = "android:nearby_wifi_devices"

        /**
         * The importance a process has while one of its activities is the app the rider is
         * looking at. Anything larger is further away.
         *
         * NOT [TBoxNetworkConnector.FOREGROUND_SERVICE_IMPORTANCE], and the difference is the
         * whole point. A specifier request is accepted from a foreground service (125), so that
         * connector's gate passes for a projection that is running with its UI closed - and in
         * support case f014ce61 the Wi-Fi Direct join made from exactly that state was refused in
         * four milliseconds, five rounds running, while `nearbyDevices=granted` and
         * `locationServices=on`. Wi-Fi Direct wants a window, not a service.
         */
        private const val TOP_APP_IMPORTANCE =
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND

        /** True when this process has no activity on screen. See [TOP_APP_IMPORTANCE]. */
        internal fun hasNoVisibleWindow(importance: Int): Boolean = importance > TOP_APP_IMPORTANCE

        internal fun importanceName(importance: Int): String = when (importance) {
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "on screen"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE ->
                "foreground service only"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "perceptible"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "service"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "cached"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "gone"
            else -> "importance $importance"
        }

        /**
         * What to tell the rider when a whole join was refused - the one sentence they will act
         * on, so the branch that owns it is decided here and tested rather than inlined.
         *
         * Order is deliberate. A stack that ran a scan and named the dash is working, whatever
         * else went wrong, so [peerSeen] keeps the first two branches it already had. Only then
         * comes the window: a join that never once ran with the app on screen is the case where
         * every framework call is rejected in milliseconds, and telling that rider to restart
         * Wi-Fi sends them to do something that cannot help - support case f014ce61, where four
         * recovery attempts spent the whole 120s watchdog on a phone whose Wi-Fi was fine.
         */
        internal fun joinRefusalAdvice(
            ssid: String,
            appName: String,
            peerSeen: Boolean,
            peerListClearedOnStop: Boolean,
            everHadAWindow: Boolean
        ): String = when {
            peerSeen && peerListClearedOnStop ->
                "The phone found $ssid but refused every request to join it, dropping it from " +
                    "its Wi-Fi Direct peer list each time. Try joining the dash from the " +
                    "phone's own Wi-Fi Direct screen first, then reconnect here."
            peerSeen ->
                "The phone found $ssid but refused every request to join it. Make sure the dash " +
                    "is showing its connection page, then retry."
            !everHadAWindow ->
                "The phone refused every attempt of this join while $appName had no window on " +
                    "screen, which is when it refuses them. Open $appName and it will reconnect."
            else ->
                "The phone's Wi-Fi Direct stack refused every attempt of this join. Turn Wi-Fi " +
                    "off and on again on the phone, then reconnect."
        }

        /** What one refused round costs, measured on the field logs: discovery refusal, the
         * preparation calls, two instant rejections and the retry delay between them. */
        private const val WEDGE_ROUND_COST_MS = 3_000L
        private const val IP_POLL_TIMEOUT_MS = 10_000L
        private const val IP_POLL_INTERVAL_MS = 500L
        private const val ADOPT_VERIFY_TIMEOUT_MS = 3_000L
        private const val ADOPT_VERIFY_POLL_MS = 300L
        private const val GROUP_OWNER_IP = "192.168.49.1"
        private const val DIRECT_PREFIX = "DIRECT-"

        /**
         * Whether a join round that was refused outright should be retried after a settle,
         * given how much of the whole-join budget it has already spent.
         *
         * The settle and one more round have to fit, or the retry would be cut off mid-flight by
         * [CONNECT_TIMEOUT_MS] and the rider would get "no group formed in 35s" - a message about
         * the dash - for what is a refusal by their own phone.
         */
        internal fun shouldSettleAndRetryJoin(
            elapsedMillis: Long,
            budgetMillis: Long,
            settleMillis: Long = WEDGE_SETTLE_MS,
            roundCostMillis: Long = WEDGE_ROUND_COST_MS
        ): Boolean = elapsedMillis + settleMillis + roundCostMillis <= budgetMillis

        /** Wi-Fi Direct group names always start with "DIRECT-" (Android convention). */
        fun isWifiDirectSsid(ssid: String): Boolean =
            ssid.trim().removeSurrounding("\"").startsWith(DIRECT_PREFIX, ignoreCase = true)

        /**
         * Recovers the dash's P2P device name from a group SSID. Android names a group
         * `DIRECT-<two random chars>-<device name>`, so `DIRECT-go-CFMOTO-EF7198` belongs to the
         * peer called `CFMOTO-EF7198`. Returns null when the SSID does not follow the convention,
         * which simply means the join falls back to credentials.
         */
        internal fun peerNameFromGroupSsid(ssid: String): String? {
            val normalized = ssid.trim().removeSurrounding("\"")
            if (!normalized.startsWith(DIRECT_PREFIX, ignoreCase = true)) return null
            val afterPrefix = normalized.substring(DIRECT_PREFIX.length)
            val separator = afterPrefix.indexOf('-')
            if (separator <= 0 || separator == afterPrefix.lastIndex) return null
            return afterPrefix.substring(separator + 1)
        }

        /**
         * The P2P device name to look for when joining [ssid].
         *
         * Two shapes reach this. A group SSID (`DIRECT-go-CFMOTO-EF7198`) names the peer inside
         * itself, so the name is recovered from it. Everything else is taken to BE the peer name
         * already: a rider's Voge pairs under `VOGE-5G-4474`, and Android's own Wi-Fi Direct
         * screen lists exactly that string as the dash's device name - the official app reaches
         * it that way, and there is no group SSID anywhere in the picture. Searching for it is
         * also the only way in, because the credentials join cannot express such a name at all:
         * `WifiP2pConfig.Builder.setNetworkName` rejects anything not starting with `DIRECT-`,
         * whereas a peer found by name is joined by its device address, which has no such rule.
         */
        internal fun expectedPeerName(ssid: String): String =
            peerNameFromGroupSsid(ssid) ?: ssid.trim().removeSurrounding("\"")

        /**
         * Whether a formed group is the profile's dash.
         *
         * Two shapes of profile reach this, and only one of them can ever match by group name.
         * A `DIRECT-…` profile IS a group name, so the names are compared directly. A profile
         * saved under the dash's P2P *device* name - `VOGE-5G-9fab`, the form
         * [expectedPeerName] exists for - never can be: Android names every group `DIRECT-…`,
         * so a name comparison against it is guaranteed to fail. Field log 94b0a3da (Voge
         * Valico 900 DSX, OnePlus CPH2663 / Android 16, 2026-08-26): the rider had joined the
         * dash by hand and this check called the resulting group `DIRECT-iY` a stale group
         * toward another device and removed the working link.
         *
         * So the group owner's device name is consulted too - the GO *is* the dash, and its
         * name is the same string the profile holds - as is the `DIRECT-xy-<name>` shape.
         *
         * The last rule is the one that matters most: **a group is only ever rejected on
         * positive proof that it belongs to another device.** When nothing readable can settle
         * it, the group is accepted. Removing on absence of evidence is what cost 94b0a3da his
         * link, and the cost of the other mistake is smaller - a foreign group we adopt fails
         * discovery a few seconds later, which is recoverable, while a removed group is not.
         */
        internal fun groupBelongsToProfile(
            groupName: String?,
            ownerDeviceName: String?,
            profileSsid: String
        ): Boolean {
            val group = groupName?.trim()?.removeSurrounding("\"").orEmpty()
            val owner = ownerDeviceName?.trim()?.removeSurrounding("\"").orEmpty()
            val normalizedProfile = profileSsid.trim().removeSurrounding("\"")
            val expected = expectedPeerName(profileSsid)
            return when {
                // A DIRECT- profile is a group name: compare them directly.
                group.equals(normalizedProfile, ignoreCase = true) -> true
                // The Group Owner is the dash, and a peer-name profile holds exactly its name.
                owner.isNotEmpty() && owner.equals(expected, ignoreCase = true) -> true
                // Android's own convention, when the framework does name the group after it.
                group.endsWith("-$expected", ignoreCase = true) -> true
                // Nothing readable to judge by - some frameworks withhold both from a client.
                group.isEmpty() && owner.isEmpty() -> true
                // A peer-name profile with an unreadable owner: the name mismatch above is not
                // evidence of anything, because it could not have matched in the first place.
                owner.isEmpty() && !isWifiDirectSsid(normalizedProfile) -> true
                else -> false
            }
        }
    }
}
