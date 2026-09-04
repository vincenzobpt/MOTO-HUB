// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.SystemClock
import android.util.Log
import io.motohub.android.feature.settings.MotoHubSettings
import io.motohub.android.session.LogLevel
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
import java.net.DatagramSocket
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch

sealed interface TBoxNetworkEvent {
    data class Lost(val network: Network) : TBoxNetworkEvent
    data class Reacquired(val network: Network) : TBoxNetworkEvent

    /**
     * Android granted the requested network AFTER [TBoxNetworkConnector.awaitRequestedNetwork]
     * had already reported the join as failed. The request outlives the wait on purpose, so this
     * is not an edge case: the wait's budget runs from the moment the specifier was submitted,
     * while Android's own runs from the moment the rider approves the picker, and a rider who
     * takes a few seconds to find that dialog spends them inside our budget rather than theirs.
     *
     * Field log 6662-E47B-06D0 (samsung SM-A556B, CFMOTO8436, 2026-08-21): the wait gave up at
     * 21:56:01.292 and Android granted the network at 21:56:03.235 - 1.9s later. The phone was
     * then associated, validated and process-bound to the dash's access point at -28dBm while
     * the app showed "the phone never joined". Nothing re-drove the connection, and the rider
     * went off to change his profile to a transport his bike does not use.
     */
    data class ArrivedLate(val network: Network, val ssid: String) : TBoxNetworkEvent
}

/** What the T-Box Wi-Fi rejoin ladder does next. */
internal sealed interface TBoxRejoinStep {
    /**
     * Serve this attempt's backoff, then come back here and decide again.
     *
     * Deciding again is the point, and it is not free bookkeeping: the wait is a suspension of up
     * to [TBoxNetworkConnector.REJOIN_MAX_DELAY_MS], and the process can leave the foreground
     * inside it. This step used to mean "wait, then submit", so the submission was authorised by
     * an importance reading up to fifteen seconds stale - see [nextTBoxRejoinStep].
     */
    data class WaitThenRetry(val delayMillis: Long) : TBoxRejoinStep

    /**
     * Android would drop a specifier request made from where this process currently sits, so the
     * ladder waits instead of spending an attempt on a refusal that never reaches the radio.
     */
    data class WaitForForeground(val delayMillis: Long) : TBoxRejoinStep

    /** The backoff is served and this process may ask: submit, and spend the attempt. */
    data object SubmitNow : TBoxRejoinStep
    data object GiveUp : TBoxRejoinStep
}

/**
 * Decides the ladder's next move: a quick first retry for the ordinary blip, then a growing and
 * capped wait, and eventually surrender.
 *
 * The budget is what stops a bike that was simply switched off from leaving an exclusive
 * WifiNetworkSpecifier request open for as long as the app lives.
 *
 * [submissionWouldBeRefused] is the difference between a rejoin that could work and one that
 * cannot: `WifiNetworkFactory` drops a specifier request from a process past
 * `IMPORTANCE_FOREGROUND_SERVICE` without ever looking for the AP. A session teardown destroys
 * its foreground service *before* the ladder starts (support 87bc5a7c, 2026-08-25: the Android
 * Auto service's onDestroy at 16:36:03.653, the first rejoin submission 261ms later at 400 =
 * cached), so all four attempts were refused in 11-28ms each and the ladder surrendered three
 * minutes later having never once asked the radio. Waiting spends the same budget on the only
 * thing that can change the answer - the rider opening the app.
 *
 * [backoffElapsed] is why a submission is a step of its own rather than something the caller does
 * after sleeping on [TBoxRejoinStep.WaitThenRetry]. The caller sleeps and comes back, so the
 * background rule above is re-applied to a *fresh* reading immediately before the submission it
 * governs. When it was applied once and then slept on, the ladder could - and for rider 4d8a4c5b
 * on 2026-08-26 did - announce "back in the foreground; resuming", wait ten seconds, drop to the
 * background inside them, and submit anyway, logging "Android will refuse this request" as it did
 * so. That refusal came back in 19ms and was counted as the fifth and last attempt.
 *
 * A backoff already served is not served again: it is spent, and any foreground wait stacks on
 * top of it. Making the rider who has just opened the app sit through the backoff a second time
 * would punish exactly the action being waited for.
 */
internal fun nextTBoxRejoinStep(
    attempt: Int,
    elapsedMillis: Long,
    budgetMillis: Long,
    firstDelayMillis: Long,
    baseDelayMillis: Long,
    maxDelayMillis: Long,
    submissionWouldBeRefused: Boolean = false,
    backgroundPollMillis: Long = 0L,
    backoffElapsed: Boolean = false
): TBoxRejoinStep {
    // Checked before the background rule on purpose: a phone that stays in the rider's pocket
    // must still let the ladder go, or the exclusive request outlives every session it could
    // have served.
    if (elapsedMillis >= budgetMillis) return TBoxRejoinStep.GiveUp
    // Above the submission on purpose: this is the reading that authorises it, and it is only
    // worth anything because nothing suspends between here and the submission itself.
    if (submissionWouldBeRefused) return TBoxRejoinStep.WaitForForeground(backgroundPollMillis)
    if (backoffElapsed) return TBoxRejoinStep.SubmitNow
    val delay = if (attempt <= 1) {
        firstDelayMillis
    } else {
        (baseDelayMillis * (attempt - 1)).coerceAtMost(maxDelayMillis)
    }
    return TBoxRejoinStep.WaitThenRetry(delay)
}

/** Requests the T-Box AP explicitly and binds the process for its reverse TCP servers. */
class TBoxNetworkConnector(context: Context) {
    private val appContext = context.applicationContext
    private val reconnectScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectivityManager = appContext.getSystemService(
        ConnectivityManager::class.java
    )
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    @Volatile
    private var hiddenSsidFallbackLogged = false
    private val mutableEvents = MutableSharedFlow<TBoxNetworkEvent>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<TBoxNetworkEvent> = mutableEvents.asSharedFlow()

    /**
     * Guards the whole network-request lifecycle: clearing the previous registration, storing the
     * new one and handing it to ConnectivityManager have to happen as one step.
     *
     * They did not, and two callers - a connect() from the UI or the AIDL bridge, and the rejoin
     * ladder below - could interleave their clear/store pairs. Both callbacks ended up registered
     * with ConnectivityManager while the connector tracked only the last one. The untracked one
     * was unreachable forever: it kept receiving onLost, kept calling [scheduleRejoin], and no
     * disconnect() could ever release it. A rider's diagnostics showed two rejoin ladders running
     * in lockstep for nineteen minutes, each new exclusive WifiNetworkSpecifier request tearing
     * down the network the other had just been granted - and still fighting the rider's own manual
     * reconnect at the end of it.
     */
    private val requestLock = Any()

    /**
     * Every callback currently registered with ConnectivityManager. [callback] is the one the
     * current attempt owns; this set is what guarantees none of the others can be orphaned.
     */
    private val registeredCallbacks = mutableSetOf<ConnectivityManager.NetworkCallback>()
    /** Whether this connector is one of [liveRequesters]; see syncLiveRequesterCount. */
    private var countedAsLiveRequester = false

    @Volatile
    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile
    private var activeNetwork: Network? = null
    @Volatile
    private var processBoundNetwork: Network? = null
    // Radio trail for the active network; see sampleLinkQuality. All written from the network
    // callback thread and read from it or from onLost, which the framework serialises.
    private var lastLinkSampleAtMs = 0L
    private var lastSampleTakenAtMs = 0L
    private var lastSampledRssi = UNSAMPLED_RSSI
    private var lastSampleDescription: String? = null
    @Volatile
    private var activeProfile: MotorcycleProfile? = null
    @Volatile
    private var connectedOnce = false
    /**
     * True between [releaseProcessBinding] and [rebindProcessToTBox]: the route was released on
     * purpose (a local projection start needs the internet route for Google's servers) and the
     * persistent callback must not re-bind it on ordinary DHCP/IPv6 link updates.
     */
    @Volatile
    private var processBindingSuspended = false

    /**
     * Set when Android refused the process binding AND a VPN was demonstrably holding the route to
     * the dash; null otherwise, including when the binding was refused for some other reason.
     *
     * The refusal itself no longer fails the connection - see `onLinkPropertiesChanged` - so this
     * exists for the failure that may come *later*: if the dash then turns out to be unreachable,
     * whoever reports that has the one sentence that explains why, instead of a timeout that
     * looks like a dash which is switched off.
     */
    @Volatile
    private var processBindingRefusal: String? = null

    /**
     * The VPN diagnosis behind a refused process binding, when there is one. Null means nothing
     * observed points at a VPN - which is not the same as "no VPN is installed", and is
     * deliberately not reported as one.
     */
    fun vpnRoutingDiagnosis(): String? = processBindingRefusal

    @Volatile
    private var rejoinJob: Job? = null

    /**
     * One rejoin ladder at a time. `rejoinJob?.isActive` was a check-then-act across threads:
     * two onLost callbacks 6ms apart both passed it and started their own ladder.
     */
    private val rejoinActive = AtomicBoolean(false)

    /**
     * Identifies the ladder that currently owns [rejoinJob] and [rejoinActive], so a cancelled
     * ladder's `finally` cannot clear state that already belongs to its successor.
     */
    private val ladderToken = java.util.concurrent.atomic.AtomicReference<Any?>(null)
    @Volatile
    private var simulatorMonitorJob: Job? = null

    /**
     * SSID of the specifier request currently registered with ConnectivityManager, whether or
     * not a network has been granted yet. What lets a retry for the same bike join the hunt
     * already in progress instead of restarting it — see [connect].
     */
    @Volatile
    private var pendingRequestSsid: String? = null
    /** When the live specifier request was submitted, so onUnavailable can say how fast it came. */
    @Volatile
    private var specifierSubmittedAt = 0L

    /**
     * Process importance at submit time. Android refuses a specifier request from a process that
     * is neither a foreground app nor a foreground service, and the refusal is indistinguishable
     * from "the dash is not broadcasting" unless this number is in the log next to it.
     */
    @Volatile
    private var specifierSubmitImportance = 0

    /**
     * When Android first said the network was AVAILABLE for the live request, so the join can be
     * split into "the phone associated" and "the phone got an address". They are different
     * failures and, on a slow join, different suspects.
     */
    @Volatile
    private var specifierAssociatedAt = 0L

    /**
     * SSID whose join [awaitRequestedNetwork] reported as failed while its specifier request was
     * still registered - so Android can still answer it, and [markConnected] has to say so
     * instead of connecting in silence behind a failure banner. Null whenever nothing has been
     * abandoned: set on give-up, cleared the moment it is announced or a new wait begins.
     */
    @Volatile
    private var abandonedJoinSsid: String? = null

    /**
     * Whether the join this request produced has already been timed. Link properties change again
     * during a session (DHCP renewals, an IPv6 address arriving late) and every one of them
     * re-enters the same branch: without this the log would carry a fresh "joined in" line, each
     * measuring from the same old submit, for a network that joined once.
     */
    @Volatile
    private var joinTimingReported = false

    /**
     * Who held the network request when the specifier was submitted - the interest ledger's own
     * words, e.g. "hub-ui, pro-establisher" for a join this app's screen asked for, or a list
     * containing "aidl-bridge" for one the companion app asked for over IPC.
     *
     * The whole point of recording it: in the pair, EVERY specifier request is submitted by CORE,
     * so a log cannot otherwise tell a join the rider started here from one ADVANCED delegated -
     * and a tester reporting "CORE connects instantly, ADVANCED takes forever" (2026-08-25) is a
     * claim about exactly that difference, with nothing in any log to weigh it against. Read
     * before taking [requestLock]: TBoxNetworkConnectors.release() holds its own lock while
     * calling in here, so asking it anything from under [requestLock] would close the cycle.
     */
    @Volatile
    private var specifierRequestedBy = ""

    /** Terminal failure produced by the registered callback, observed by [awaitRequestedNetwork]. */
    @Volatile
    private var pendingFailure: Throwable? = null

    /**
     * Whether Android ever granted a network for the request currently registered - set by
     * `onAvailable`, which fires on association, well before any IP address exists.
     *
     * Separates the two ways a join can run out of time: the phone never associated to the AP at
     * all, or it associated and the dash never handed out a usable IPv4. They have different
     * causes and different remedies, and used to share one message that named only the second.
     */
    @Volatile
    private var networkGranted = false

    @Volatile
    private var pendingGiveUpJob: Job? = null

    suspend fun connect(profile: MotorcycleProfile): Result<Network> {
        if (TBoxModelProfile.fromModelId(profile.modelId) == TBoxModelProfile.MOTO_HUB_SIMULATOR) {
            disconnect()
            activeProfile = profile
            connectedOnce = false
            processBindingSuspended = false
            return try {
                ProjectionEventLog.record(
                    "NETWORK",
                    "Simulator profile detected for SSID ${profile.ssid}; reusing the phone's existing Wi-Fi " +
                        "instead of requesting a WifiNetworkSpecifier."
                )
                val network = withTimeout(CONNECTION_TIMEOUT_MS) { findExistingWifi(profile.ssid) }
                connectedOnce = true
                startSimulatorMonitor(profile)
                Result.success(network)
            } catch (_: TimeoutCancellationException) {
                ProjectionEventLog.error("NETWORK", "Wi-Fi setup timed out after ${CONNECTION_TIMEOUT_MS}ms.")
                disconnect()
                Result.failure(
                    IllegalStateException(
                        "The simulator requires the phone and Mac to be connected to the same Wi-Fi network " +
                            "with a usable IPv4 address."
                    )
                )
            } catch (cancelled: CancellationException) {
                // A real cancellation (user cancel, scope teardown) must propagate, not become a Result.
                throw cancelled
            } catch (failure: Throwable) {
                ProjectionEventLog.error("NETWORK", "T-Box AP request failed.", failure)
                // routing omitted here: there is no granted network to test a VPN's routes against,
                // so only the error itself can carry the evidence.
                val vpnMessage = TBoxVpnDiagnostics.userFacingMessage(failure, routing = null)
                Result.failure(vpnMessage?.let { IllegalStateException(it, failure) } ?: failure)
            }
        }

        // Session watchdogs retry through here every ~35s while recovering a dropped ride, and
        // with the screen off Android scans for Wi-Fi so rarely that a 30s window may not contain
        // a single scan. Tearing the exclusive request down and re-submitting on every attempt
        // kept resetting that hunt right before it could succeed (road test 2026-07-29: four
        // consecutive "Wi-Fi setup timed out" while recovering the CFDL16 mid-ride). Reuse what
        // is already there instead: first the granted network, then the still-pending request.
        if (activeProfile?.ssid == profile.ssid) {
            activeNetwork?.let { existing ->
                // A refused binding is not a reason to throw this network away and start the join
                // again - the sockets are bound to the network itself, not to the process route.
                // It used to be, and on a phone whose VPN refuses every binding that turned one
                // reusable network into an endless re-request loop.
                if (!processBindingSuspended && processBoundNetwork == null) {
                    if (runCatching { connectivityManager.bindProcessToNetwork(existing) }
                            .getOrDefault(false)
                    ) {
                        processBoundNetwork = existing
                    } else {
                        ProjectionEventLog.warning(
                            "NETWORK",
                            "Reusing the active T-Box network $existing unbound: Android refused to " +
                                "restore the process binding."
                        )
                    }
                }
                ProjectionEventLog.record(
                    "NETWORK",
                    "Reusing the active T-Box network $existing for ${profile.ssid}."
                )
                return Result.success(existing)
            }
            if (pendingRequestSsid == profile.ssid) {
                ProjectionEventLog.record(
                    "NETWORK",
                    "Joining the pending Wi-Fi request for ${profile.ssid} instead of re-submitting it."
                )
                return awaitRequestedNetwork(profile)
            }
        }

        disconnect()
        activeProfile = profile
        connectedOnce = false
        processBindingSuspended = false
        ProjectionEventLog.record(
            "NETWORK",
            "Requesting Android Wi-Fi network for SSID ${profile.ssid}; " +
                "passwordPresent=${profile.password.isNotEmpty()}; " +
                "the phone is currently ${currentWifiDescription()}."
        )
        submitSpecifierRequest(profile)
        return awaitRequestedNetwork(profile)
    }

    fun disconnect() {
        // Invalidate the token first: the cancelled ladder's finally then finds the gate no
        // longer its own and leaves whatever comes next untouched.
        ladderToken.set(null)
        rejoinJob?.cancel()
        rejoinJob = null
        rejoinActive.set(false)
        simulatorMonitorJob?.cancel()
        simulatorMonitorJob = null
        activeProfile = null
        connectedOnce = false
        clearCurrentNetworkRequest()
    }

    private fun clearCurrentNetworkRequest() {
        synchronized(requestLock) { clearCurrentNetworkRequestLocked() }
    }

    /** Releases the process binding and *every* registered callback. Call under [requestLock]. */
    private fun clearCurrentNetworkRequestLocked() {
        ProjectionEventLog.debug(
            "NETWORK",
            "Disconnect requested; callbacks=${registeredCallbacks.size}, " +
                "activeNetwork=$activeNetwork, processBound=$processBoundNetwork."
        )
        callback = null
        pendingRequestSsid?.let { TBoxRequestGiveUpAlarm.disarm(appContext, it) }
        pendingRequestSsid = null
        pendingFailure = null
        networkGranted = false
        abandonedJoinSsid = null
        pendingGiveUpJob?.cancel()
        pendingGiveUpJob = null
        if (processBoundNetwork != null) {
            connectivityManager.bindProcessToNetwork(null)
            processBoundNetwork = null
        }
        activeNetwork = null
        val released = registeredCallbacks.toList()
        registeredCallbacks.clear()
        syncLiveRequesterCount()
        released.forEach { unregister(it) }
    }

    /** Releases one attempt's registration without touching a newer attempt's state. */
    private fun releaseCallback(target: ConnectivityManager.NetworkCallback) {
        synchronized(requestLock) {
            if (callback === target) {
                callback = null
                // This registration was the pending request; without it there is nothing left
                // for a retry to join.
                pendingRequestSsid = null
            }
            if (registeredCallbacks.remove(target)) unregister(target)
            syncLiveRequesterCount()
        }
    }

    /**
     * Keeps the process-wide count of connectors that currently hold a Wi-Fi request, and says so
     * the moment there is more than one.
     *
     * Two live connectors mean two `WifiNetworkSpecifier` requests for the same SSID, each with
     * its own rejoin ladder - and releasing either one tears down the association the other is
     * using. The phone then disconnects itself from the dash at full signal, which is
     * indistinguishable from the dash hanging up unless you happen to notice that every network
     * line in the log appears two or three times. That is how it was found (OnePlus, 1.1.24,
     * 2026-07-30, after the companion app's process was killed mid-session), and it is not
     * something a rider can reproduce on request: this line exists so the next log states it
     * outright instead of requiring someone to spot duplicated timestamps by eye.
     *
     * Counted per connector rather than per registration: one connector legitimately holds a
     * second callback for a moment while a rejoin attempt overlaps the previous one.
     */
    private fun syncLiveRequesterCount() {
        val holdsRequest = registeredCallbacks.isNotEmpty()
        if (holdsRequest == countedAsLiveRequester) return
        countedAsLiveRequester = holdsRequest
        val live = if (holdsRequest) {
            liveRequesters.incrementAndGet()
        } else {
            liveRequesters.decrementAndGet()
        }
        if (holdsRequest && live > 1) {
            // ERROR, because this is a fault in this app and nothing about it is the rider's
            // doing - and because at WARNING it never left the phone, so a bug we can already
            // detect exactly had no fleet numbers behind it at all. Only the first occurrence
            // per process is reported: once two connectors are fighting they re-register for as
            // long as the thrash lasts (thirteen times in the 2026-07-31 OnePlus log), and the
            // repeats say nothing the first one did not.
            val firstInProcess = duplicateRequestReported.compareAndSet(false, true)
            ProjectionEventLog.record(
                "NETWORK",
                "$live T-Box network connectors now hold a Wi-Fi request at the same time. They " +
                    "compete for the same association and releasing one drops the others, so " +
                    "expect connections that are granted and lost within a second. This is a " +
                    "MOTO-HUB fault, not the dash.",
                LogLevel.ERROR,
                reportToTelemetry = firstInProcess
            )
        }
    }

    private fun unregister(target: ConnectivityManager.NetworkCallback) {
        runCatching { connectivityManager.unregisterNetworkCallback(target) }
            .onFailure {
                ProjectionEventLog.warning("NETWORK", "Network callback unregister failed.", it)
            }
    }

    /** Current Wi-Fi network confirmed by the SSID-specific request callback, if still active. */
    fun currentNetwork(): Network? = activeNetwork

    /**
     * Whether this connector's live-or-pending Wi-Fi request already targets [ssid] - the same
     * check [connect] uses internally to decide whether a retry can reuse it instead of resetting
     * Android's join hunt from zero. Exposed so a caller that owns *this connector's identity*
     * (deciding whether to hand it back out for another attempt, rather than building a new one)
     * can make that call before [connect] ever runs.
     */
    fun isHuntingFor(ssid: String): Boolean = activeProfile?.ssid == ssid

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
        processBindingSuspended = true
        ProjectionEventLog.record("NETWORK", "Process binding released; result=$released. T-Box request remains active.")
    }

    /**
     * Rebinds reverse EasyConn sockets to the still-requested T-Box network.
     *
     * Losing the network is a failure; being refused the binding on a network that is still there
     * is not. The dashboard resumes over network-bound sockets either way, and failing here used
     * to end a ride that was about to carry on perfectly well.
     */
    @Synchronized
    fun rebindProcessToTBox(): Result<Network> = runCatching {
        processBindingSuspended = false
        val network = checkNotNull(activeNetwork) { "The T-Box network is no longer available." }
        if (runCatching { connectivityManager.bindProcessToNetwork(network) }.getOrDefault(false)) {
            processBoundNetwork = network
            ProjectionEventLog.record("NETWORK", "Process rebound to T-Box network=$network.")
        } else {
            processBoundNetwork = null
            ProjectionEventLog.warning(
                "NETWORK",
                "Android refused to rebind the process to T-Box network=$network; carrying on with " +
                    "network-bound sockets."
            )
        }
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

    /**
     * Records what the radio can actually see, immediately before the request is submitted.
     *
     * This is the datum a rider log was missing. When Android never grants the network there is no
     * `onAvailable` and no link properties either, so the log says only that nothing happened - and
     * "the dash is not broadcasting", "the dash is on a channel this phone will not join" and "the
     * saved password is wrong" all look identical. A VOGE dash (SSID `VOGE-5G-58e4`, 2026-07-30)
     * cost a full log analysis and a round trip to the rider to get no further than that.
     *
     * The band matters on its own: an SSID advertising 5G is a hint, not evidence, and a dash whose
     * only AP sits on a 5GHz channel the phone's regulatory domain forbids can never be joined. The
     * sibling scan exists for the same reason - several of these dashboards broadcast a 2.4GHz twin
     * of the same network, and if one is in range it is almost certainly the one to pair with.
     *
     * BSSIDs are deliberately not logged: they are stable hardware identifiers and these logs get
     * pasted into public threads.
     */
    /**
     * Follows the radio for as long as the T-Box network lives, so a session that dies has a
     * signal trail behind it instead of a single reading taken at association.
     *
     * This is the datum that separates the two ways a ride session ends, which a rider log could
     * not tell apart: a link that fades (RSSI walking down over seconds, link speed collapsing)
     * versus an AP that simply vanishes at full strength - the dash rebooting its hotspot, or
     * handing the radio to something else. Both surface identically upstream, as a dead TCP
     * connection and an `onLost` a few seconds later. Zontes log 2026-07-30: the dash measured
     * -50dBm at 5180MHz when joined, then the session died 56s later with nothing recorded in
     * between.
     *
     * Driven by `onCapabilitiesChanged`, which Android already delivers on signal changes, so
     * there is no timer to own and nothing to stop when the session ends. The rate limit is
     * deliberately two-sided: at most one line per [LINK_SAMPLE_INTERVAL_MS] while the link is
     * steady, but every change of [LINK_SAMPLE_RSSI_STEP_DBM] or more regardless of how recently
     * one was logged - dense exactly while the link is moving, near-silent when it is not. The
     * lesson from `decode fps=` is that an unconditional per-event line costs more diagnostic
     * value than it adds.
     */
    private fun sampleLinkQuality(capabilities: NetworkCapabilities) {
        val wifiInfo = capabilities.transportInfo as? android.net.wifi.WifiInfo ?: return
        val rssi = wifiInfo.rssi
        // The framework redacts WifiInfo from callers it does not trust with location, and hands
        // back its "no reading" sentinel otherwise. Logging that value would read as a link on
        // the brink, which is the opposite of "we do not know".
        if (rssi <= INVALID_RSSI_DBM) return
        val now = SystemClock.elapsedRealtime()
        val moved = lastSampledRssi != UNSAMPLED_RSSI &&
            kotlin.math.abs(rssi - lastSampledRssi) >= LINK_SAMPLE_RSSI_STEP_DBM
        val due = lastLinkSampleAtMs == 0L ||
            now - lastLinkSampleAtMs >= LINK_SAMPLE_INTERVAL_MS
        lastSampledRssi = rssi
        lastSampleDescription = "rssi=${rssi}dBm, frequency=${wifiInfo.frequency}MHz, " +
            "linkSpeed=${wifiInfo.linkSpeed}Mbps"
        lastSampleTakenAtMs = now
        if (!due && !moved) return
        lastLinkSampleAtMs = now
        ProjectionEventLog.debug("NETWORK", "T-Box link: $lastSampleDescription.")
    }

    /**
     * The single most useful line in a log of a session that died: what the radio looked like the
     * last time anyone measured it, and how stale that measurement was. A strong final sample
     * taken a moment earlier means the AP went away rather than faded.
     */
    private fun logLastLinkSample() {
        val description = lastSampleDescription ?: run {
            ProjectionEventLog.debug(
                "NETWORK",
                "No T-Box link measurement was available before the network was lost."
            )
            return
        }
        val age = SystemClock.elapsedRealtime() - lastSampleTakenAtMs
        if (age > LINK_SAMPLE_STALE_AGE_MS) {
            // Rider dc735158 lost a session with a 39-minute-old -39dBm sample on the books:
            // Android delivers capability callbacks only when the signal moves, so a rock-steady
            // link goes unmeasured for as long as it stays put. An old sample cannot separate a
            // fade from a vanish, but the silence itself can - a fade would have produced
            // callbacks, so a long-unmeasured link was steady until it went.
            ProjectionEventLog.warning(
                "NETWORK",
                "The T-Box link went unmeasured for the ${age}ms before the loss - Android " +
                    "samples only on change, so the link held steady ($description) until it " +
                    "vanished outright."
            )
            return
        }
        ProjectionEventLog.warning(
            "NETWORK",
            "Last T-Box link measurement before the loss: $description, taken ${age}ms earlier."
        )
    }

    /**
     * What the phone is associated to RIGHT NOW, for the line that submits the specifier.
     *
     * A phone with one Wi-Fi radio has to leave whatever it is on before it can honour a
     * [WifiNetworkSpecifier], and "I was still on my home Wi-Fi" is a completely different
     * failure from "the dash was not broadcasting" - yet both print as a 30s timeout. Rider
     * 36a3fd37 had to tell us in words which of the two it was, because no log line in two days
     * of reports carried it.
     *
     * The SSID needs location permission and CORE does not hold it, so the name is often
     * withheld. Whether the phone is on SOME Wi-Fi is the half that matters and
     * [ConnectivityManager] answers it without any permission at all.
     */
    @SuppressLint("MissingPermission")
    private fun currentWifiDescription(): String {
        val onWifi = connectivityManager.activeNetwork
            ?.let { connectivityManager.getNetworkCapabilities(it) }
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        if (!onWifi) return "not on any Wi-Fi"
        val ssid = runCatching { normalizeSsid(wifiManager.connectionInfo?.ssid.orEmpty()) }
            .getOrDefault("")
        return when {
            ssid.isBlank() || ssid == "<unknown ssid>" ->
                "on another Wi-Fi whose name Android withholds - Android has to leave it first"
            else -> "on Wi-Fi $ssid - Android has to leave it first"
        }
    }

    private fun ScanResult.ssidText(): String =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) wifiSsid?.toString() else null)
            ?.removeSurrounding("\"")
            ?: @Suppress("DEPRECATION") SSID.orEmpty().removeSurrounding("\"")

    /**
     * Whether the dash is broadcasting its own SSID right now - **null when that cannot be said**.
     *
     * Tri-state is the whole point. A dash that is visibly on the air proves a PHONE_HOTSPOT
     * profile is aimed at the wrong transport, and [TBoxLinkResolver] uses exactly that to recover
     * instead of dead-ending. But an absent or empty scan is not evidence of absence - the long
     * comment in [logVisibleApSnapshot] lists the four ways a phone hands back nothing while the
     * dash is measurably there - and a false "not broadcasting" would send a rider whose hotspot
     * simply is not on down a join that cannot work. Only a definite sighting returns true.
     */
    @SuppressLint("MissingPermission")
    fun isDashBroadcasting(profile: MotorcycleProfile): Boolean? {
        val target = profile.ssid.trim().removeSurrounding("\"")
        if (target.isEmpty()) return null
        val results = runCatching { wifiManager.scanResults }.getOrNull() ?: return null
        if (results.isEmpty()) return null
        // AGE is the fourth way this list lies, and the one that cost a rider his ride
        // (36a3fd37, 2026-09-01). Android throttles getScanResults hard and nothing in the app
        // refreshes it, so the list can predate the moment the dash was switched on. That rider
        // pressed start while still on his home Wi-Fi, then powered the dash up and came back to
        // MOTO-HUB twice - 13:32:46 and 13:33:18 - and both times the retry was vetoed by a list
        // that had been taken before the dash existed. Ten minutes later the same dash joined in
        // 5110ms at -40dBm. A list too old to have seen the dash come up says nothing about it.
        val newest = results.maxOf { it.timestamp }
        if (!scanEvidenceIsFresh(newest, SystemClock.elapsedRealtime())) return null
        return results.any { it.ssidText().equals(target, ignoreCase = true) }
    }

    /**
     * Internal rather than private because the phone-hosted road needs it too: when that mode
     * declines the access-point fallback, this snapshot is the only record of why - see
     * [TBoxLinkResolver.accessPointFallback].
     */
    @SuppressLint("MissingPermission")
    internal fun logVisibleApSnapshot(profile: MotorcycleProfile) {
        val target = profile.ssid.trim().removeSurrounding("\"")
        val results = runCatching { wifiManager.scanResults }.getOrNull()
        if (results == null) {
            ProjectionEventLog.debug(
                "NETWORK",
                "Wi-Fi scan results are unavailable, so it cannot be said whether $target is in range."
            )
            publishScanFacts(visibility = "scan_unavailable")
            return
        }
        // An EMPTY result list is not evidence about the dash: it means this phone handed back no
        // scan at all - a cache the platform has not refreshed, scan throttling, or the
        // location/permission gate on getScanResults() - and a phone that can see nothing
        // whatsoever is describing itself, not the air. Saying "$target is NOT in the scan" there
        // convicts the dash of being silent on no evidence. A rider's log (Zontes ZT_…,
        // 2026-07-30) printed that verdict four times while the same dash measured -50dBm on
        // 5180MHz two minutes later.
        if (results.isEmpty()) {
            ProjectionEventLog.debug(
                "NETWORK",
                "The phone's Wi-Fi scan came back empty (0 networks), so it says nothing about " +
                    "whether $target is in range."
            )
            publishScanFacts(visibility = "scan_empty")
            return
        }
        val match = results.firstOrNull { it.ssidText().equals(target, ignoreCase = true) }
        // What the phone can see AT ALL is the other half of the answer, and the half that was
        // missing. "Not in the scan" convicts the dash; it stops doing so the moment the same
        // line shows the phone saw no 5GHz network whatsoever, and it convicts the dash's channel
        // rather than the dash when the phone demonstrably reaches the top of the band. A
        // China-market unit parked on channel 149-165 is invisible to a phone in an EU
        // regulatory domain, and no rider log had been able to tell that apart from a dash
        // that was simply switched off (VOGE 2026-07-30, QJ 2026-07-31 - both never once seen).
        //
        // A third cause outranks both, and is the one to rule out first because it is free to
        // check: some dashes never broadcast anything, because they are Wi-Fi CLIENTS waiting to
        // join a hotspot the phone hosts. Confirmed 2026-08-02 - a rider hit exactly this warning
        // at 11:00, switched to PHONE_HOTSPOT five minutes later, and streamed 9360 frames. The
        // scan evidence for that dash was indistinguishable from VOGE's and QJ's, so neither of
        // those is safely attributed to WPA3 or to the regulatory domain yet.
        val perBand = results.groupingBy { bandName(it.frequency) }.eachCount()
        val bandSummary = perBand.entries
            .sortedByDescending { it.value }
            .joinToString { (band, count) -> "$count on $band" }
        val topFiveGhzMhz = results.map { it.frequency }.filter { it in FIVE_GHZ_MHZ }.maxOrNull()
        val reach = topFiveGhzMhz?.let { ", highest 5GHz channel seen ${it}MHz" }.orEmpty()
        if (match == null) {
            ProjectionEventLog.warning(
                "NETWORK",
                "$target is NOT in the phone's latest Wi-Fi scan (${results.size} networks seen: " +
                    "$bandSummary$reach). Either the dash is not broadcasting it right now, the " +
                    "phone cannot see that channel, or this dash never broadcasts at all and " +
                    "expects your phone to HOST the network - check whether its pairing screen " +
                    "says \"open Android hotspot\", and if so pair it again with the " +
                    "\"My phone hosts the hotspot\" mode." +
                    scanBlindSpotHint(target, topFiveGhzMhz, results.size)
            )
        } else {
            // The security line is the one that can convict us rather than the dash. The specifier
            // below only ever offers setWpa2Passphrase, so an AP that requires WPA3/SAE can never
            // be matched no matter how correct the password is, and the failure is indistinguishable
            // from a dash that is not broadcasting. Logging what the AP actually advertises is what
            // separates those two, and nothing in a rider log has been able to so far.
            ProjectionEventLog.record(
                "NETWORK",
                "$target is in range: ${bandName(match.frequency)} (${match.frequency}MHz), " +
                    "rssi=${match.level}dBm, security=${securityName(match.capabilities)}."
            )
        }
        // A twin on the other band shares the dash's serial-looking last token.
        val tail = target.substringAfterLast('-', "").takeIf { it.length >= 3 }
        val siblings = if (tail == null) {
            emptyList()
        } else {
            results
                .map { it.ssidText() to it.frequency }
                .filter { (ssid, _) ->
                    ssid.isNotEmpty() &&
                        !ssid.equals(target, ignoreCase = true) &&
                        ssid.endsWith(tail, ignoreCase = true)
                }
                .distinctBy { it.first }
                .take(SIBLING_AP_LOG_LIMIT)
        }
        if (siblings.isNotEmpty()) {
            ProjectionEventLog.record(
                "NETWORK",
                "The same dash also broadcasts: " +
                    siblings.joinToString { (ssid, frequency) -> "$ssid on ${bandName(frequency)}" } +
                    ". If $target will not join, one of these may."
            )
        }
        publishScanFacts(
            visibility = if (match == null) "no" else "yes",
            match = match,
            fiveGhzSeen = perBand["5GHz"] ?: 0,
            topFiveGhzMhz = topFiveGhzMhz,
            siblingBand = siblings.firstOrNull()?.let { (_, frequency) -> bandName(frequency) }
        )
    }

    /**
     * Sends the SHAPE of the scan - never an SSID, never a BSSID - to telemetry, so the question
     * this snapshot exists to answer gets settled on fleet numbers instead of one shared log at a
     * time. Riders paste these logs into public threads and the fleet has no business knowing a
     * neighbour's network name; every value here is a bucket for the same reason.
     */
    private fun publishScanFacts(
        visibility: String,
        match: ScanResult? = null,
        fiveGhzSeen: Int? = null,
        topFiveGhzMhz: Int? = null,
        siblingBand: String? = null
    ) {
        ProjectionEventLog.setTelemetryFacts(
            mapOf(
                "tbox.ap_visible" to visibility,
                "tbox.ap_band" to (match?.let { bandName(it.frequency) } ?: "none"),
                "tbox.ap_security" to (match?.let { securityName(it.capabilities) } ?: "none"),
                "tbox.ap_rssi" to (match?.let { rssiBand(it.level) } ?: "none"),
                "tbox.scan_5ghz" to (fiveGhzSeen?.let(::scanCountBand) ?: "unknown"),
                "tbox.scan_5ghz_reach" to regulatoryReach(topFiveGhzMhz),
                "tbox.ap_sibling_band" to (siblingBand ?: "none")
            )
        )
    }

    /**
     * Registers the exclusive specifier request and returns immediately; the callback drives the
     * shared connection state, and [awaitRequestedNetwork] observes the outcome. Deliberately not
     * a suspend-until-connected call: the registration must be able to outlive any single
     * caller's patience, because Android keeps matching a live request against every later Wi-Fi
     * scan — that background hunt is exactly what a screen-off recovery needs.
     */
    /**
     * How long this join actually took, once, in one line - or null when there is nothing new to
     * measure (already reported, or a network reused rather than requested).
     *
     * Written because "ADVANCED is slower to connect than CORE" could not be checked. Every
     * specifier request in the pair is submitted by CORE, whether the rider tapped Connect in CORE
     * or in ADVANCED, so a report's log shows the same lines either way and the only evidence was
     * an impression. This says which of the two asked ([specifierRequestedBy]), at what process
     * importance, and splits the wait into association and address - so the two paths can be
     * compared as numbers, and a slow join can be blamed on the right half of it.
     *
     * Deliberately not an average or a verdict: one line per join, and the reader does the
     * arithmetic. A single sample is not a claim about a build.
     */
    private fun joinTimingSummary(profile: MotorcycleProfile): String? {
        if (joinTimingReported) return null
        val submittedAt = specifierSubmittedAt.takeIf { it > 0L } ?: return null
        joinTimingReported = true
        val now = SystemClock.elapsedRealtime()
        val total = now - submittedAt
        val associated = specifierAssociatedAt.takeIf { it > 0L }?.let { it - submittedAt }
        return joinTimingLine(
            ssid = profile.ssid,
            totalMs = total,
            associatedMs = associated,
            askedBy = specifierRequestedBy,
            importance = specifierSubmitImportance
        )
    }

    private fun submitSpecifierRequest(profile: MotorcycleProfile) {
        logVisibleApSnapshot(profile)
        lateinit var networkCallback: ConnectivityManager.NetworkCallback
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Association only - there is no address yet, so this is not success. It is the
                // one signal that separates "never joined the AP" from "joined it and got no IP",
                // and its absence across a whole rider log is itself the diagnosis.
                networkGranted = true
                if (specifierAssociatedAt == 0L) specifierAssociatedAt = SystemClock.elapsedRealtime()
                ProjectionEventLog.debug(
                    "NETWORK",
                    "Android granted network=$network for ${profile.ssid}; awaiting a usable IPv4 address."
                )
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                if (network != activeNetwork) return
                sampleLinkQuality(networkCapabilities)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                val addresses = linkProperties.linkAddresses.mapNotNull { it.address.hostAddress }
                val gateways = linkProperties.routes.mapNotNull { it.gateway?.hostAddress }.distinct()
                // Dev-only raw logcat line (leaks the phone's Wi-Fi IPs) - the real,
                // production diagnostic record is ProjectionEventLog.debug() below, gated
                // by the runtime "Enable logging" setting regardless of build type.
                if (io.motohub.android.BuildConfig.DEBUG) Log.d(TAG, "Wi-Fi addresses=$addresses")
                ProjectionEventLog.debug(
                    "NETWORK",
                    "Link properties changed: network=$network, interface=${linkProperties.interfaceName}, " +
                        "addresses=$addresses, gateways=$gateways."
                )
                val isTBoxNetwork = linkProperties.linkAddresses
                    .any { isUsableTBoxIpv4Address(it.address) }
                if (isTBoxNetwork) {
                    if (processBindingSuspended) {
                        // The binding was released on purpose while a local projection
                        // starts. Re-binding here on a routine DHCP/IPv6 link update would
                        // cut Google Android Auto off the internet mid-handshake; the
                        // projection flow rebinds explicitly when it is ready.
                        markConnected(network)
                        ProjectionEventLog.debug(
                            "NETWORK",
                            "T-Box link update accepted without re-binding: the process " +
                                "binding is deliberately released."
                        )
                        return
                    }
                    val bindFailure = runCatching {
                        check(connectivityManager.bindProcessToNetwork(network)) {
                            "Android cannot bind MOTO-HUB to the T-Box network."
                        }
                    }.exceptionOrNull()
                    // bindProcessToNetwork answers a bare false, so the failure above is a
                    // sentence this file wrote - it carries no errno and therefore no evidence of
                    // WHY. Under a VPN in lockdown the reason is knowable right here, and knowing
                    // it now is the difference between a diagnosis and three EasyConn retries
                    // ending in a stack trace: bind an unconnected datagram socket, which is the
                    // same netd operation every socket to the dash will attempt, and read the
                    // errno. Nothing is sent and nothing is connected, so a phone where this
                    // works pays a socket open and close for it.
                    val bindEvidence = bindFailure?.let { refusal ->
                        runCatching { DatagramSocket().use { probe -> network.bindSocket(probe) } }
                            .exceptionOrNull()
                            ?.takeIf { TBoxVpnDiagnostics.isVpnBindBlocked(it) }
                            ?: refusal
                    }
                    if (bindFailure != null) {
                        // NOT fatal, and this used to be. The process binding only moves this
                        // process's DEFAULT route; every socket that actually talks to the dash is
                        // bound to the network explicitly (TBoxLink.Infrastructure.createSocket
                        // uses network.socketFactory) and the reverse servers the dash dials back
                        // listen on the wildcard address, so neither needs it. Aborting here threw
                        // away a network Android had just granted, complete with a usable IPv4 and
                        // a gateway: a rider with a Tailscale exit node up got twelve of these in
                        // forty seconds (OnePlus CPH2653, 1.1.73, 2026-08-15), each one a granted
                        // network discarded before a single packet was sent to the dash. Carry on
                        // unbound and let the connection fail on its own merits if the VPN really
                        // is in the way - with LAN access allowed, it is not.
                        val routing = TBoxVpnDiagnostics.inspect(connectivityManager, firstIpv4Gateway(linkProperties))
                        processBoundNetwork = null
                        // No `takeIf { capturesTBox }` any more, and that guard is exactly what
                        // cost 2026-08-26: it was added so a VPN would not be blamed for merely
                        // existing, and it also threw away the case where the ERROR is the
                        // evidence rather than the routes. userFacingMessage already refuses to
                        // answer without one or the other, so the guard only ever removed true
                        // diagnoses.
                        processBindingRefusal = TBoxVpnDiagnostics
                            .userFacingMessage(bindEvidence, routing)
                        Log.w(TAG, "T-Box process binding rejected; continuing unbound", bindFailure)
                        val boundSocketsRefused = TBoxVpnDiagnostics.isVpnBindBlocked(bindEvidence)
                        ProjectionEventLog.warning(
                            "NETWORK",
                            "Process binding rejected for network=$network (${bindFailure.message}); " +
                                "vpn=${routing?.describe() ?: "none"}. " +
                                if (boundSocketsRefused) {
                                    "Network-bound sockets are refused too " +
                                        "(${bindEvidence?.message}) - a VPN in lockdown blocks " +
                                        "every socket to this link, so the dash is unreachable " +
                                        "until it is turned off."
                                } else {
                                    "Continuing with network-bound sockets instead - this is " +
                                        "only fatal if the dash turns out to be unreachable."
                                }
                        )
                        markConnected(network)
                        return
                    }
                    processBindingRefusal = null
                    processBoundNetwork = network
                    markConnected(network)
                    Log.i(TAG, "T-Box Wi-Fi is active: ${profile.ssid}, addresses=$addresses")
                    ProjectionEventLog.record(
                        "NETWORK",
                        "T-Box Wi-Fi validated and process-bound: ssid=${profile.ssid}, network=$network, addresses=$addresses."
                    )
                    joinTimingSummary(profile)?.let { ProjectionEventLog.record("NETWORK", it) }
                    if (MotoHubSettings.verboseTBoxLogging(appContext)) {
                        runCatching { wifiManager.connectionInfo }.getOrNull()?.let { info ->
                            ProjectionEventLog.debug(
                                "NETWORK",
                                "Wi-Fi link (verbose): frequency=${info.frequency}MHz, " +
                                    "rssi=${info.rssi}dBm, linkSpeed=${info.linkSpeed}Mbps."
                            )
                        }
                    }
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
                logLastLinkSample()
                if (processBoundNetwork == network) {
                    connectivityManager.bindProcessToNetwork(null)
                    processBoundNetwork = null
                }
                activeNetwork = null
                mutableEvents.tryEmit(TBoxNetworkEvent.Lost(network))
                scheduleRejoin()
            }

            override fun onUnavailable() {
                // The elapsed time is the diagnosis, not decoration. Android takes seconds to scan
                // for a network it is genuinely hunting; a verdict inside a few tens of
                // milliseconds means the request was refused outright rather than attempted, and
                // reading that off two timestamps by hand is how it gets missed.
                val elapsed = specifierSubmittedAt
                    .takeIf { it > 0L }
                    ?.let { "${SystemClock.elapsedRealtime() - it}ms after the request" }
                    ?: "with no recorded request time"
                // Refused for being in the background, not for anything about the dash: telling
                // this rider to rescan the QR code sends them to fix a profile that is fine.
                val refusedAsBackground = !networkGranted &&
                    specifierSubmitImportance > FOREGROUND_SERVICE_IMPORTANCE
                ProjectionEventLog.error(
                    "NETWORK",
                    "Android reported the requested T-Box Wi-Fi as unavailable $elapsed; " +
                        "granted=$networkGranted, importanceAtRequest=$specifierSubmitImportance."
                )
                pendingFailure = IllegalStateException(
                    when {
                        networkGranted ->
                            "Android dropped the ${profile.ssid} network before it became usable."
                        refusedAsBackground ->
                            "Android refused the request for ${profile.ssid} without trying it: " +
                                "MOTO-HUB was in the background when it was made. Open MOTO-HUB " +
                                "and tap Connect again."
                        else ->
                            "Android gave up connecting to ${profile.ssid}: either the dash was " +
                                "not broadcasting it, the saved password no longer matches, or " +
                                "the connection dialog was dismissed. Rescan the dash QR code " +
                                "and retry."
                    }
                )
                releaseCallback(networkCallback)
            }
        }
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

        val importanceNow = processImportance()
        // Outside the lock on purpose - see specifierRequestedBy.
        val requestedBy = TBoxNetworkConnectors.describeOwners()
        ProjectionEventLog.debug(
            "NETWORK",
            "Submitting WifiNetworkSpecifier request for ${profile.ssid} without INTERNET " +
                "capability; process importance=$importanceNow" +
                if (importanceNow > FOREGROUND_SERVICE_IMPORTANCE) {
                    " (background - Android will refuse this request)."
                } else {
                    "."
                }
        )

        // Drop the previous registration, reset this attempt's shared state, take ownership and
        // register - with no window in between for a concurrent caller to slip its own pair
        // through. The resets belong in here too: run outside the lock (as they were), the
        // rejoin ladder and a foreground connect() could interleave badly enough for one
        // attempt's verdict to be wiped by the other's reset, leaving awaitRequestedNetwork()
        // waiting out its whole timeout on an answer that had already arrived.
        val requestFailure = synchronized(requestLock) {
            clearCurrentNetworkRequestLocked()
            pendingFailure = null
            networkGranted = false
            // Per join, not per connector: a stale "last measurement before the loss" carried
            // over from the previous session would be read as this one's, which is worse than
            // none.
            lastLinkSampleAtMs = 0L
            lastSampleTakenAtMs = 0L
            lastSampledRssi = UNSAMPLED_RSSI
            lastSampleDescription = null
            specifierSubmittedAt = SystemClock.elapsedRealtime()
            specifierAssociatedAt = 0L
            joinTimingReported = false
            specifierRequestedBy = requestedBy
            specifierSubmitImportance = importanceNow
            callback = networkCallback
            registeredCallbacks += networkCallback
            syncLiveRequesterCount()
            pendingRequestSsid = profile.ssid
            runCatching {
                connectivityManager.requestNetwork(request, networkCallback)
            }.exceptionOrNull()
        }
        if (requestFailure != null) {
            ProjectionEventLog.error(
                "NETWORK",
                "ConnectivityManager.requestNetwork threw an exception.",
                requestFailure
            )
            pendingFailure = requestFailure
            releaseCallback(networkCallback)
            return
        }
        schedulePendingGiveUp(profile)
    }

    /**
     * How close to the user this process is, on Android's own scale (smaller is closer). Recorded
     * per request because it is the difference between "the dash is not there" and "we asked from
     * the background", which the failure itself cannot tell apart.
     */
    private fun processImportance(): Int {
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        return state.importance
    }

    /** Success bookkeeping shared by both callback paths (bound and deliberately unbound). */
    private fun markConnected(network: Network) {
        // Read and cleared before anything else: this runs again on every routine link update of
        // an already-connected network, and the announcement below must happen exactly once.
        val abandoned = abandonedJoinSsid
        abandonedJoinSsid = null
        activeNetwork = network
        connectedOnce = true
        pendingFailure = null
        pendingGiveUpJob?.cancel()
        pendingGiveUpJob = null
        // The registration deliberately outlives the join, but nothing needs to give it up any
        // more - and an alarm left armed would fire mid-ride.
        pendingRequestSsid?.let { TBoxRequestGiveUpAlarm.disarm(appContext, it) }
        if (abandoned != null) {
            val late = specifierSubmittedAt
                .takeIf { it > 0L }
                ?.let { " ${SystemClock.elapsedRealtime() - it}ms after the request was submitted" }
                .orEmpty()
            ProjectionEventLog.record(
                "NETWORK",
                "Android granted $abandoned$late, after this app had already reported the join " +
                    "as failed. The phone is on the motorcycle network now; resuming the " +
                    "connection instead of leaving the failure on screen."
            )
            mutableEvents.tryEmit(TBoxNetworkEvent.ArrivedLate(network, abandoned))
        }
    }

    /**
     * Waits for [submitSpecifierRequest]'s callback to produce a usable network. On timeout the
     * registration is deliberately left in place: Android keeps matching it against every later
     * scan, so a recovery retry joins a hunt that has been running the whole time instead of
     * restarting it from zero. [schedulePendingGiveUp] still bounds how long the radio can be
     * held by a request nothing ever answers.
     *
     * The wait can run [UNAVAILABLE_GRACE_MS] past [CONNECTION_TIMEOUT_MS], but only while
     * nothing has been granted - the window exists to catch Android's own late verdict.
     */
    private suspend fun awaitRequestedNetwork(profile: MotorcycleProfile): Result<Network> {
        // A wait that is starting owns the outcome; whatever an earlier one abandoned is answered
        // by this one, and leaving the flag set would make its success read as a late arrival.
        abandonedJoinSsid = null
        try {
            pollForOutcome(SystemClock.elapsedRealtime() + CONNECTION_TIMEOUT_MS)?.let { return it }
            if (!networkGranted) {
                ProjectionEventLog.debug(
                    "NETWORK",
                    "Nothing granted for ${profile.ssid} within ${CONNECTION_TIMEOUT_MS}ms; waiting up " +
                        "to ${UNAVAILABLE_GRACE_MS}ms for Android's own verdict."
                )
                pollForOutcome(SystemClock.elapsedRealtime() + UNAVAILABLE_GRACE_MS)?.let { return it }
            }
        } catch (cancelled: CancellationException) {
            // A real cancellation (user cancel, scope teardown) must release the exclusive
            // request and propagate, not become a Result.
            clearCurrentNetworkRequest()
            throw cancelled
        }
        // Two things this line used to state as fact and could not know. Whether the request is
        // still pending: [schedulePendingGiveUp] may have released it while this wait was
        // running, and a 2026-07-31 rider log has "Releasing the pending request" printed
        // immediately above "the request stays pending for the next attempt". And how long the
        // wait actually took: a cached process is frozen by Android, every coroutine delay inside
        // it stops, and this loop only notices once the app is thawed - in that same log a 6s
        // grace took 505s and the elapsed time was reported as the 30s budget. That gap is the
        // difference between "the dash never answered" and "this app was not running to hear it".
        val stillPending = pendingRequestSsid == profile.ssid
        val elapsed = specifierSubmittedAt.takeIf { it > 0L }
            ?.let { SystemClock.elapsedRealtime() - it }
        val frozen = elapsed != null && elapsed > (CONNECTION_TIMEOUT_MS + UNAVAILABLE_GRACE_MS) * 2
        ProjectionEventLog.setTelemetryFacts(mapOf("tbox.wait_frozen" to if (frozen) "yes" else "no"))
        // Only while the registration survives. A released request can never be answered, so
        // arming the late-arrival path for it would leave a flag nothing ever clears.
        abandonedJoinSsid = profile.ssid.takeIf { stillPending }
        ProjectionEventLog.error(
            "NETWORK",
            "Wi-Fi setup timed out after ${CONNECTION_TIMEOUT_MS}ms with " +
                (if (networkGranted) "the network granted but no usable IPv4 address" else "no network granted") +
                "; " +
                (if (stillPending) {
                    "the request stays pending for the next attempt."
                } else {
                    "the request has already been released."
                }) +
                (if (frozen) " The wait itself took ${elapsed}ms: this process was frozen while it ran." else "")
        )
        return Result.failure(IllegalStateException(setupTimeoutMessage(profile)))
    }

    /**
     * Polls the shared connection state until [deadline], returning null if it runs out with
     * neither a network nor a failure - the caller decides whether that is worth waiting past.
     */
    private suspend fun pollForOutcome(deadline: Long): Result<Network>? {
        while (SystemClock.elapsedRealtime() < deadline) {
            currentNetwork()?.let { return Result.success(it) }
            pendingFailure?.let { failure ->
                pendingFailure = null
                // routing omitted here: there is no granted network to test a VPN's routes against,
                // so only the error itself can carry the evidence.
                val vpnMessage = TBoxVpnDiagnostics.userFacingMessage(failure, routing = null)
                return Result.failure(vpnMessage?.let { IllegalStateException(it, failure) } ?: failure)
            }
            delay(NETWORK_POLL_MS)
        }
        return null
    }

    /**
     * Names which half of the join ran out of time. One message used to cover both, and it named
     * only the second: a rider whose phone never associated at all was told Android had not got
     * an IP address *from the AP*, which points at the bike when the AP was never reached.
     */
    private fun setupTimeoutMessage(profile: MotorcycleProfile): String = if (networkGranted) {
        "The phone joined ${profile.ssid} but Android never obtained a usable IPv4 address from " +
            "it within ${CONNECTION_TIMEOUT_MS}ms. Switch the dash off and on again, then retry."
    } else {
        "The phone never joined ${profile.ssid}: Android did not associate to it within " +
            "${CONNECTION_TIMEOUT_MS}ms. Check that ${profile.ssid} is listed in the phone's Wi-Fi " +
            "settings while the dash shows its pairing screen - if it is not, the dash is not " +
            "broadcasting. If Android showed a dialog asking to connect to it, accept it and retry."
    }

    /**
     * A pending request left in place by [awaitRequestedNetwork] must not outlive every recovery
     * budget: past that point it only takes the Wi-Fi radio away from whoever asks next —
     * including the rider reconnecting by hand — so release it once nothing has connected for
     * [REJOIN_GIVE_UP_MS].
     */
    private fun schedulePendingGiveUp(profile: MotorcycleProfile) {
        pendingGiveUpJob?.cancel()
        pendingGiveUpJob = reconnectScope.launch {
            delay(REJOIN_GIVE_UP_MS)
            releasePendingRequest(profile.ssid, wokenByAlarm = false)
        }
        // The coroutine above is only as awake as the process. A rider who leaves the pairing
        // screen has the app cached within seconds, Android freezes it, and this budget stops
        // counting: the 2026-07-31 QJ log shows the 180s give-up landing at 528s, the exact
        // moment the rider reopened the app, with the exclusive request holding the radio for
        // the whole interval - and Sentry has reports of 727s. An alarm is the one timer the
        // freezer honours, because delivering it thaws the process.
        TBoxRequestGiveUpAlarm.arm(appContext, profile.ssid, REJOIN_GIVE_UP_MS) {
            releasePendingRequest(profile.ssid, wokenByAlarm = true)
        }
    }

    /**
     * Drops a request nothing ever answered. Idempotent, because the in-process timer and the
     * alarm both aim at it and either may get there first.
     */
    private fun releasePendingRequest(ssid: String, wokenByAlarm: Boolean) {
        // Decide and act as ONE step under the lock. markConnected() cancels the job, but a job
        // already past its own check cannot be cancelled out of the clear that follows: a grant
        // landing in that window had its brand-new network released immediately. Re-reading
        // activeNetwork inside the lock closes it, because markConnected() runs from the network
        // callback and its write is visible here.
        synchronized(requestLock) {
            if (activeNetwork != null || pendingRequestSsid != ssid) return
            ProjectionEventLog.warning(
                "NETWORK",
                "Releasing the pending T-Box Wi-Fi request for $ssid: nothing connected within " +
                    "${REJOIN_GIVE_UP_MS / 1_000L}s" +
                    (if (wokenByAlarm) "; the app's own timer was frozen, so the wake-up alarm did it" else "") +
                    "."
            )
            clearCurrentNetworkRequestLocked()
        }
    }

    private fun scheduleRejoin() {
        val profile = activeProfile ?: return
        if (!connectedOnce) return
        // Exactly one ladder, whatever raced its way in here.
        if (!rejoinActive.compareAndSet(false, true)) return
        val token = Any()
        ladderToken.set(token)
        rejoinJob = reconnectScope.launch {
            var attempt = 0
            // Logged on entering and leaving the wait rather than per poll: the whole point is
            // to stop filling a rider's log with refusals that say nothing about the dash.
            var waitingForForeground = false
            // This attempt's backoff has been served. Survives a foreground wait on purpose - see
            // nextTBoxRejoinStep - and is cleared once the attempt it belongs to has been spent.
            var backoffElapsed = false
            val startedAt = SystemClock.elapsedRealtime()
            try {
                ladder@ while (activeProfile != null && connectedOnce && activeNetwork == null) {
                    val importanceNow = processImportance()
                    val step = nextTBoxRejoinStep(
                        attempt = attempt + 1,
                        elapsedMillis = SystemClock.elapsedRealtime() - startedAt,
                        budgetMillis = REJOIN_GIVE_UP_MS,
                        firstDelayMillis = REJOIN_FIRST_DELAY_MS,
                        baseDelayMillis = REJOIN_BASE_DELAY_MS,
                        maxDelayMillis = REJOIN_MAX_DELAY_MS,
                        submissionWouldBeRefused = importanceNow > FOREGROUND_SERVICE_IMPORTANCE,
                        backgroundPollMillis = REJOIN_BACKGROUND_POLL_MS,
                        backoffElapsed = backoffElapsed
                    )
                    if (step is TBoxRejoinStep.WaitForForeground) {
                        if (!waitingForForeground) {
                            waitingForForeground = true
                            ProjectionEventLog.warning(
                                "NETWORK",
                                "Not asking Android for ${profile.ssid} yet: MOTO-HUB is in the " +
                                    "background (importance=$importanceNow), and a request made " +
                                    "from there is refused without the AP ever being looked " +
                                    "for. Waiting up to ${REJOIN_GIVE_UP_MS / 1_000L}s for " +
                                    "MOTO-HUB to come back to the foreground - open it to " +
                                    "reconnect now."
                            )
                        }
                        delay(step.delayMillis)
                        continue@ladder
                    }
                    if (step is TBoxRejoinStep.GiveUp) {
                        // Every downstream recovery budget is shorter than this, so past the
                        // deadline there is no session left for a reacquired AP to serve. Holding
                        // an exclusive WifiNetworkSpecifier request open past that point only
                        // takes the Wi-Fi radio away from whoever asks next - including the rider
                        // reconnecting by hand.
                        ProjectionEventLog.warning(
                            "NETWORK",
                            if (attempt == 0) {
                                "Giving up on the T-Box Wi-Fi after " +
                                    "${REJOIN_GIVE_UP_MS / 1_000L}s without ever being able to " +
                                    "ask: MOTO-HUB stayed in the background the whole time, " +
                                    "where Android refuses the request. Releasing the network " +
                                    "request; open MOTO-HUB and tap Connect."
                            } else {
                                "Giving up on the T-Box Wi-Fi after $attempt rejoin attempt(s) " +
                                    "over ${REJOIN_GIVE_UP_MS / 1_000L}s; releasing the network " +
                                    "request."
                            }
                        )
                        clearCurrentNetworkRequest()
                        break@ladder
                    }
                    if (step is TBoxRejoinStep.WaitThenRetry) {
                        delay(step.delayMillis)
                        // Nothing is submitted on the way out of this branch. The loop reads the
                        // process importance again at the top, so the rule that authorises a
                        // submission is always applied to a reading taken after the last thing
                        // that could suspend - which a delay of up to REJOIN_MAX_DELAY_MS very
                        // much is. The while condition catches an AP reacquired during it too.
                        backoffElapsed = true
                        continue@ladder
                    }
                    // Said here rather than before the wait: "resuming" belongs next to the
                    // submission it announces, or it can be followed by "giving up, it was in the
                    // background the whole time" - two lines that contradict each other.
                    if (waitingForForeground) {
                        waitingForForeground = false
                        ProjectionEventLog.record(
                            "NETWORK",
                            "MOTO-HUB is back in the foreground; resuming the T-Box Wi-Fi rejoin."
                        )
                    }
                    attempt++
                    backoffElapsed = false
                    // A fresh submission per attempt: the ladder exists for devices whose stale
                    // specifier registration never reconnects on its own, so unlike the connect()
                    // retry path it deliberately does NOT join the previous pending request.
                    submitSpecifierRequest(profile)
                    val network = awaitLadderNetwork()
                    if (network != null) {
                        ProjectionEventLog.record(
                            "NETWORK",
                            "T-Box Wi-Fi automatically reacquired on attempt $attempt: network=$network."
                        )
                        mutableEvents.tryEmit(TBoxNetworkEvent.Reacquired(network))
                        return@launch
                    }
                    ProjectionEventLog.warning(
                        "NETWORK",
                        "T-Box Wi-Fi rejoin attempt $attempt failed."
                    )
                }
            } finally {
                // Only the ladder that still OWNS the gate may clear it. disconnect() cancels a
                // ladder and reopens the gate itself, but this block runs asynchronously
                // afterwards - so a NEW ladder could already have been started by then, and the
                // old one's finally would null out its job handle and reopen the gate under it,
                // leaving a running, uncancellable ladder. The token makes that clear a no-op:
                // whoever holds the current token owns the state, everyone else keeps its hands
                // off (same rule as bridges.remove(key, this) elsewhere).
                if (ladderToken.compareAndSet(token, null)) {
                    rejoinJob = null
                    rejoinActive.set(false)
                }
            }
        }
    }

    /** One ladder attempt's wait: no timeout error spam, no teardown — the loop resubmits anyway. */
    private suspend fun awaitLadderNetwork(): Network? {
        val deadline = SystemClock.elapsedRealtime() + CONNECTION_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            currentNetwork()?.let { return it }
            pendingFailure?.let { failure ->
                pendingFailure = null
                ProjectionEventLog.warning(
                    "NETWORK",
                    "T-Box Wi-Fi rejoin request failed: ${failure.message}"
                )
                return null
            }
            delay(NETWORK_POLL_MS)
        }
        return null
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
                // This runs on a per-second poll, and the hidden SSID is a stable property of
                // the build, not an event: logging it every call buried real entries under one
                // warning per second for the whole session. Report the transition only.
                if (!hiddenSsidFallbackLogged) {
                    hiddenSsidFallbackLogged = true
                    ProjectionEventLog.warning(
                        "NETWORK",
                        "Android did not expose the current Wi-Fi SSID; using the only usable Wi-Fi network " +
                            "for the simulator. Further occurrences are not logged."
                    )
                }
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

    /**
     * The dash's own address on the network just joined, so a VPN's routes can be tested against
     * something real rather than against the assumption that any VPN blocks everything.
     */
    private fun firstIpv4Gateway(linkProperties: LinkProperties): InetAddress? =
        linkProperties.routes.asSequence()
            .mapNotNull { it.gateway }
            .firstOrNull { it is Inet4Address && !it.isAnyLocalAddress }

    private companion object {
        const val TAG = "TBoxNetwork"
        const val CONNECTION_TIMEOUT_MS = 30_000L

        /**
         * Extra wait for Android's own `onUnavailable` verdict once [CONNECTION_TIMEOUT_MS] has
         * run out with nothing granted. Android times a specifier request out 30s after the rider
         * approves the picker, so its verdict always lands a few seconds after ours - in a rider
         * log of twelve consecutive failures it arrived 2.8-4.4s late every single time, which
         * meant the specific "Android could not deliver this network" reason was never the one
         * reported. Bounded, because a rider who leaves the picker open pushes it out of reach.
         */
        const val UNAVAILABLE_GRACE_MS = 6_000L
        /**
         * `ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE` - the ceiling
         * AOSP's `WifiNetworkFactory.isRequestFromForegroundAppOrService` accepts. Anything
         * higher (larger number = further from the user) has its specifier request dropped.
         */
        const val FOREGROUND_SERVICE_IMPORTANCE =
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
        const val EXISTING_WIFI_POLL_MS = 250L
        const val NETWORK_POLL_MS = 250L
        const val SIMULATOR_MONITOR_POLL_MS = 1_000L
        const val REJOIN_FIRST_DELAY_MS = 300L
        const val REJOIN_BASE_DELAY_MS = 2_500L
        const val REJOIN_MAX_DELAY_MS = 15_000L

        /**
         * How long the ladder keeps chasing a vanished T-Box AP. Deliberately longer than every
         * downstream recovery budget (mirroring and Android Auto both give up after 120s), so a
         * dropout short enough for a session to survive is still covered - and finite, so a bike
         * that is simply switched off does not leave the radio under an exclusive request.
         */
        const val REJOIN_GIVE_UP_MS = 180_000L

        /**
         * How often the ladder re-checks process importance while a submission would be refused.
         * Cheap (`getMyMemoryState` reads this process, no binder call) and short enough that a
         * rider who opens the app gets their rejoin within a couple of seconds.
         */
        const val REJOIN_BACKGROUND_POLL_MS = 2_000L
        /** Enough to show a band twin without turning a busy scan into a wall of text. */
        const val SIBLING_AP_LOG_LIMIT = 4

        /** Steady-link cadence for the radio trail; a moving link logs sooner (sampleLinkQuality). */
        const val LINK_SAMPLE_INTERVAL_MS = 15_000L

        /**
         * Older than this and the loss-time sample stops being quoted as a measurement: a fading
         * link produces callbacks well inside a minute, so a sample this old means the link never
         * moved, not that it looked like the sample says when it died.
         */
        const val LINK_SAMPLE_STALE_AGE_MS = 60_000L

        /**
         * RSSI change that logs a sample regardless of the cadence. 6dB is a quarter of the
         * received power - large enough that Wi-Fi's own noise does not trip it, small enough
         * that a link on its way out produces several lines before it goes.
         */
        const val LINK_SAMPLE_RSSI_STEP_DBM = 6

        /** No sample taken yet; not a valid RSSI, so it cannot be mistaken for a reading. */
        const val UNSAMPLED_RSSI = Int.MIN_VALUE

        /**
         * How many connectors in this process currently hold a Wi-Fi request. Process-wide on
         * purpose: [requestLock] already makes one connector's own registrations safe, and cannot
         * see a second connector doing the same thing beside it - which is the failure this
         * counts. See syncLiveRequesterCount.
         */
        private val liveRequesters = java.util.concurrent.atomic.AtomicInteger(0)

        /** Keeps the duplicate-requester fault to one telemetry report per process. */
        private val duplicateRequestReported = AtomicBoolean(false)

        /**
         * "No reading" from the framework. `WifiInfo.INVALID_RSSI` is -127 but is not public API,
         * so the value is spelled out; anything at or below it is a sentinel, not a measurement.
         */
        const val INVALID_RSSI_DBM = -127
    }
}

/**
 * Summarises what an AP requires to be joined, from the raw `ScanResult.capabilities` string.
 *
 * WPA3 is called out on its own because it is the one answer that indicts this app: the specifier
 * only offers a WPA2 passphrase, so a dash that requires SAE cannot be joined at all.
 */
/**
 * The sentence [TBoxNetworkConnector.joinTimingSummary] logs, without a Context so it can be
 * tested.
 *
 * @param associatedMs time to Android's AVAILABLE callback, or null when none was seen - which is
 *   itself an answer (an address appeared without this app ever being told the phone associated)
 *   and must not be reported as an association at 0 ms.
 */
internal fun joinTimingLine(
    ssid: String,
    totalMs: Long,
    associatedMs: Long?,
    askedBy: String,
    importance: Int
): String {
    val asked = askedBy.takeIf { it.isNotBlank() } ?: "nobody on the ledger"
    val phases = associatedMs
        ?.let { ": associated after ${it}ms, address ${totalMs - it}ms later" }
        ?: " (no AVAILABLE callback was seen for it)"
    return "Joined $ssid in ${totalMs}ms$phases; asked by $asked at process importance $importance."
}

internal fun securityName(capabilities: String?): String {
    val caps = capabilities.orEmpty().uppercase()
    val schemes = buildList {
        if (caps.contains("SAE")) add("WPA3/SAE")
        if (caps.contains("RSN") || caps.contains("WPA2")) add("WPA2")
        if (caps.contains("WPA-") || caps.contains("WPA_") || Regex("(^|[^23A-Z])WPA($|[^23])").containsMatchIn(caps)) {
            add("WPA")
        }
        if (caps.contains("WEP")) add("WEP")
    }
    return when {
        schemes.isNotEmpty() -> schemes.joinToString("+")
        caps.isBlank() -> "not reported"
        else -> "open or unrecognised ($caps)"
    }
}

/**
 * How far up the 5GHz band this phone demonstrably reaches, from the highest channel its own scan
 * came back with.
 *
 * This is the fact that separates a silent dash from one on a channel the phone's regulatory
 * domain forbids. Channels 149-165 (5745MHz and up) are ordinary in the Chinese market and not
 * available to a phone operating under EU rules, so a dash sitting there is as invisible as a dash
 * that is switched off - and until now the log said the same words for both.
 *
 * Read as evidence about the PHONE, and only alongside the scan size: seeing nothing above
 * 5320MHz in a car park proves nothing, seeing it in a street full of routers is a strong hint.
 */
internal fun regulatoryReach(topFiveGhzMhz: Int?): String = when {
    topFiveGhzMhz == null -> "no 5GHz seen"
    topFiveGhzMhz >= 5745 -> "up to UNII-3"
    topFiveGhzMhz >= 5500 -> "up to UNII-2C"
    else -> "up to UNII-1/2A"
}

/**
 * A scan this small is not evidence. Android throttles `getScanResults` hard, and a QJ rider's
 * eight attempts came back with 1, 2, 2, 2, 3 and 10 networks (log 2026-08-12) - on the low
 * readings "the dash is not in the scan" says more about the scan than it does about the dash.
 */
/**
 * Is a Wi-Fi scan list recent enough to be evidence about the dash?
 *
 * [newestResultTimestampMicros] is [ScanResult.timestamp] - microseconds since boot at which that
 * AP was last seen - so the newest entry dates the list as a whole. Compared against
 * [SystemClock.elapsedRealtime], which shares that clock.
 *
 * A list older than [SCAN_EVIDENCE_MAX_AGE_MS] is not a smaller amount of evidence, it is none:
 * the rider may have switched the dash on since it was taken. Returning false there sends
 * [TBoxNetworkConnector.isDashBroadcasting] to null - "cannot be said" - which is the only answer
 * that neither convicts the dash nor invents a sighting. A future timestamp (a clock the platform
 * has stepped) is treated as fresh: it is not evidence of age either.
 */
internal fun scanEvidenceIsFresh(newestResultTimestampMicros: Long, nowMillis: Long): Boolean {
    val ageMs = nowMillis - newestResultTimestampMicros / 1_000L
    return ageMs <= SCAN_EVIDENCE_MAX_AGE_MS
}

/**
 * How old a scan list may be and still answer "is the dash on the air?".
 *
 * 30s is picked off the failure it exists to stop: the gap between a rider powering the dash up
 * and coming back to MOTO-HUB is seconds, not minutes, so anything older cannot have seen the
 * dash come up. Long enough that a list refreshed while the rider walks to the bike still counts.
 */
internal const val SCAN_EVIDENCE_MAX_AGE_MS = 30_000L

private const val SPARSE_SCAN_NETWORKS = 3

/**
 * The part of a "not in the scan" warning that names what the rider can actually do about it.
 *
 * [regulatoryReach] has existed for a while but only ever reached telemetry, so the rider-facing
 * line offered `highest 5GHz channel seen 5220MHz` and left them to draw the conclusion. Two
 * conclusions are worth spelling out, and both are about the PHONE rather than the dash:
 *
 *  - An SSID that advertises 5GHz, on a phone whose own scan never reached UNII-3, has the shape
 *    of a dash sitting on a Chinese channel (149-165) that EU rules put out of reach. Invisible
 *    and switched off look identical from here, and hosted-hotspot mode does not need to see it.
 *  - A scan with almost nothing in it cannot support any conclusion at all.
 *
 * Returns the empty string when neither applies, so the warning keeps the wording it has today.
 */
internal fun scanBlindSpotHint(targetSsid: String, topFiveGhzMhz: Int?, networksSeen: Int): String {
    val hints = buildList {
        if (targetSsid.contains("5G", ignoreCase = true) &&
            regulatoryReach(topFiveGhzMhz) != "up to UNII-3"
        ) {
            val ceiling = topFiveGhzMhz?.let { "above ${it}MHz" } ?: "any 5GHz channel"
            add(
                "This network's name says 5GHz, and this phone's own scan never reached $ceiling: " +
                    "a dash on a Chinese 5GHz channel (149-165, 5745MHz and up) is invisible to a " +
                    "phone on EU rules however well the dash is working. Hosted-hotspot mode does " +
                    "not depend on seeing it."
            )
        }
        if (networksSeen <= SPARSE_SCAN_NETWORKS) {
            add(
                "Only $networksSeen network(s) were in that scan, so it may be a throttled or " +
                    "stale one rather than a true picture of the air."
            )
        }
    }
    return if (hints.isEmpty()) "" else " " + hints.joinToString(" ")
}

/** Coarse RSSI buckets: a tag carrying an exact dBm reading is a new tag value per rider. */
internal fun rssiBand(levelDbm: Int): String = when {
    levelDbm >= -60 -> "strong"
    levelDbm >= -75 -> "ok"
    else -> "weak"
}

/** Bucketed scan counts, for the same reason [rssiBand] is bucketed. */
internal fun scanCountBand(count: Int): String = when {
    count <= 0 -> "0"
    count <= 3 -> "1-3"
    else -> "4+"
}

/** The one definition of 5GHz in this file, so [bandName] and the scan snapshot cannot disagree. */
internal val FIVE_GHZ_MHZ = 4900..5900

/** Names the band a scan frequency sits in; "?" rather than a guess when it is out of range. */
internal fun bandName(frequencyMhz: Int): String = when (frequencyMhz) {
    in 2400..2500 -> "2.4GHz"
    in FIVE_GHZ_MHZ -> "5GHz"
    in 5925..7125 -> "6GHz"
    else -> "an unknown band"
}

internal fun isUsableTBoxIpv4Address(address: InetAddress): Boolean =
    address is Inet4Address &&
        !address.isAnyLocalAddress &&
        !address.isLoopbackAddress &&
        !address.isLinkLocalAddress &&
        !address.isMulticastAddress
