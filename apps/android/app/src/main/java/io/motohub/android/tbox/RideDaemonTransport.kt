// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import api.Api
import api.MobileCallback
import api.MobileSession
import io.motohub.android.feature.settings.MotoHubSettings
import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.session.ProjectionEventLog
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val MOTO_HUB_SIMULATOR_MODEL_ID = "MOTO-HUB-SIMULATOR"
internal const val RIDE_DAEMON_STARTUP_TIMEOUT_SEC = 25L
private const val PXC_STALL_WARNING_MS = 6_000L
/**
 * How long the dash may say nothing at all on the PXC control link, while we are still feeding it
 * video, before the session is declared dead. The reverse channel keepalive runs every 2s, so this
 * is ten missed beats; a rider log that showed a 16.6s gap had the dash tear down all three
 * sockets straight afterwards, so nothing shorter than that gap is worth waiting for.
 */
private const val PXC_STALL_FATAL_MS = 20_000L
/** How often the watchdog looks; a fraction of the budget above, not a precise alarm. */
private const val PXC_WATCHDOG_INTERVAL_MS = 2_000L
/**
 * A frame offered more recently than this means we are actively streaming, which is the only state
 * where silence is a fault worth killing the session over: a paused dashboard is not a dead dash,
 * and it is not telling the rider anything untrue either.
 */
private const val PXC_STALL_STREAMING_WINDOW_MS = 5_000L
/**
 * A PXC event only counts as a "beat" — evidence the dash keeps a control-link cadence — when it
 * arrives while video is already flowing and stands at least this far from the previous PXC
 * event. Handshake traffic is a burst milliseconds apart before the first frame; a keepalive
 * cadence is one event every ~2s during streaming. This gap is what tells them apart.
 */
internal const val PXC_STREAMING_BEAT_MIN_GAP_MS = 1_000L
/**
 * How many streaming-time beats the dash must have shown before its PXC silence is allowed to
 * kill the session. A CFDL16 (field log 2026-07-31) sends six PXC events in the first ~3s —
 * only one of them during streaming — and then nothing, while its TFT keeps displaying video
 * for another 25 minutes; a dash with a real keepalive cadence reaches three beats within ~6s.
 * Three is the smallest count that separates the two shapes.
 */
internal const val PXC_STREAMING_CADENCE_MIN_BEATS = 3L
private const val PUSH_FRAME_TIMEOUT_MS = 5_000L
private const val PUSH_FRAME_SUBMIT_WAIT_MS = 1_000L
private const val PUSH_FRAME_SUBMIT_RETRY_DELAY_MS = 5L
private const val REJECTED_FRAME_LOG_INTERVAL = 100L

internal fun isCurrentRideDaemonSession(callbackGeneration: Long, activeGeneration: Long): Boolean =
    callbackGeneration != 0L && callbackGeneration == activeGeneration

/** Kotlin boundary around the GPL gomobile binding. Network selection stays outside this class. */
class RideDaemonTransport(
    context: Context
) : TBoxTransport {
    private val appContext = context.applicationContext
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
    private val nsdManager = appContext.getSystemService(NsdManager::class.java)
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private val callbackExecutor = ContextCompat.getMainExecutor(appContext)
    // Keep only one access unit queued behind the native call. A zero-capacity
    // SynchronousQueue made a short pushFrame() overlap look like a dead session to PRO.
    // The bounded queue retains the watchdog below without allowing an unbounded backlog.
    private val pushFrameExecutor = java.util.concurrent.ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(1)
    ) { runnable -> Thread(runnable, "MotoHubPushFrame").apply { isDaemon = true } }
    private val mutableEvents = MutableSharedFlow<TBoxEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val events: Flow<TBoxEvent> = mutableEvents.asSharedFlow()
    @Volatile
    private var session: MobileSession? = null
    @Volatile
    private var sessionLink: TBoxLink? = null
    private val sessionLock = Any()
    private val nextSessionGeneration = AtomicLong(0L)
    @Volatile
    private var activeSessionGeneration = 0L
    @Volatile
    private var protocolProfile: TBoxModelProfile = TBoxModelProfile.GENERIC
    /**
     * The motorcycle this transport is serving, when the caller knows it. Only [TBoxWireLadder]
     * needs it - the ladder's memory is per motorcycle - and a caller that has none (the capability
     * inspector, a test) simply gets the profile's own wire.
     */
    @Volatile
    private var motorcycleProfile: MotorcycleProfile? = null
    /** Elapsed-time mark for the running session, so its length can be judged when it ends. */
    private val sessionStartedElapsed = AtomicLong(0L)
    /**
     * Set the instant before the socket is handed to Go, cleared when the stop is recorded. It is
     * the only reliable way to tell "our sockets are closing" from "we never opened any" - see
     * [markNativeSessionStopped].
     */
    private val nativeStartAttempted = AtomicBoolean(false)
    /** One ladder verdict per session, whoever ends it first. */
    private val ladderVerdictFiled = AtomicBoolean(false)
    private val pxcEvents = AtomicLong(0L)
    private val mediaControlEvents = AtomicLong(0L)
    private val framesOffered = AtomicLong(0L)
    private val framesTimedOut = AtomicLong(0L)
    private val framesRejected = AtomicLong(0L)
    private val lastPxcEventElapsed = AtomicLong(0L)
    private val lastMediaControlEventElapsed = AtomicLong(0L)
    private val lastFrameOfferedElapsed = AtomicLong(0L)
    /** Streaming-time PXC beats seen so far (see [isStreamingPxcBeat]); the silence watchdog's
     *  fatal verdict is gated on this reaching [PXC_STREAMING_CADENCE_MIN_BEATS]. */
    private val pxcStreamingBeats = AtomicLong(0L)
    /**
     * How many video frames the DASHBOARD has asked for, reported by the daemon.
     *
     * The only counter in this class that describes the far end. [framesOffered],
     * [framesTimedOut] and [framesRejected] all describe the pipe from the encoder into the
     * daemon's ring buffer, and every one of them looks perfect while a dash sits there never
     * asking for a byte - which is exactly the state several riders have reported for months as
     * "it connects and the screen stays black". The daemon counts the 0x0072 pulls on :10920 and
     * now forwards them; without this the two faults cannot be told apart from a rider's log.
     */
    private val dashVideoPulls = AtomicLong(0L)
    /** Whether the dash ever opened the video socket at all - the case before the one above. */
    private val dashVideoSocketOpened = AtomicBoolean(false)
    private val pxcWatchdogExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "MotoHubPxcWatchdog").apply { isDaemon = true }
    }
    @Volatile
    private var pxcWatchdogTask: ScheduledFuture<*>? = null
    /** One report per session: the rider needs the failure once, not every tick. */
    private val pxcStallReported = AtomicBoolean(false)
    /** One log per session for the opposite outcome: silence observed on a dash that never
     *  showed a cadence, so the watchdog stood down instead of killing the session. */
    private val pxcQuietDashReported = AtomicBoolean(false)
    /** Distinct (source, command) pairs already dumped this session for opcode identification. */
    private val unknownCommandsLogged =
        java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<Long, Long>>()
    /** Keeps a dash's keepalive traffic from spending the whole log ring on itself. */
    private val beatCollapser = ProtocolBeatCollapser()

    override fun configureProtocolProfile(profile: TBoxModelProfile, motorcycle: MotorcycleProfile?) {
        protocolProfile = profile
        motorcycleProfile = motorcycle
    }

    /**
     * What this session should put on the wire: the profile's own settings when something
     * recognised it, otherwise whichever rung [TBoxWireLadder] has reached for this motorcycle.
     */
    private fun wireConfigFor(profile: TBoxModelProfile): TBoxWireConfig {
        val motorcycle = motorcycleProfile ?: return profile.wireConfig
        return TBoxWireLadder.configFor(appContext, motorcycle, profile)
    }

    override suspend fun discover(link: TBoxLink, expectedModelId: String?): Result<TBoxHost> = withContext(Dispatchers.IO) {
        ProjectionEventLog.record("DISCOVERY", "Starting Android NSD discovery on T-Box link (${link.label}).")
        runCatching {
            stopSession()
            resetProtocolStats()
            val host = discoverWithRetry(link, expectedModelId)
            val profile = protocolProfile.takeIf { it != TBoxModelProfile.GENERIC }
                ?: TBoxModelProfile.resolve(expectedModelId, null)
            val wire = wireConfigFor(profile)
            val mobileConfig = Api.newMobileConfig(
                ByteArray(0),
                30L,
                RIDE_DAEMON_STARTUP_TIMEOUT_SEC,
                5L,
                10L,
                3L
            ).apply {
                setSupportFunction(profile.advertisedSupportFunction.toLong())
                setProactivePxcHeartbeatEnabled(wire.requiresProactivePxcHeartbeat)
                // Only a dashboard that no profile claims - or a framing experiment the rider
                // pinned by hand - is allowed to renegotiate the video frame format from its
                // own supportExtendProtocol byte. Every recognised unit keeps the indexed
                // framing it already displays.
                setPlainVideoFramingAllowed(wire.allowsPlainVideoFraming)
                // The dash asks for wall-clock time over PXC and the daemon answers it,
                // but only Android knows the zone: Go's local location on a device is
                // UTC and carries no usable name. The id alone was not enough - it only
                // labelled the reply while the times inside it stayed on UTC, so a
                // rider's Voge dash was being set two hours wrong (log 2026-08-06, app
                // 1.1.45). The offset is what actually moves the clock, and Android is
                // the only side that knows it with DST applied. Both are read per
                // session, so a rider who crosses a border gets the new zone on the
                // next connect.
                setTimeZoneID(java.util.TimeZone.getDefault().id)
                setTimeZoneOffsetSeconds(
                    java.util.TimeZone.getDefault()
                        .getOffset(System.currentTimeMillis())
                        .toLong() / 1000L
                )
            }
            // A companion-driven session pushes its settings only after this point, so the channel
            // re-evaluates itself again from IpcBridgeService; see EcBtpClockChannel.
            EcBtpClockChannel.onSessionStarted(appContext, profile.transportFamily)
            val generation = nextSessionGeneration.incrementAndGet()
            val createdSession = Api.newMobileSession(
                mobileConfig,
                SessionCallback(generation)
            )
            synchronized(sessionLock) {
                session = createdSession
                sessionLink = link
                activeSessionGeneration = generation
            }
            createdSession.setECHost(
                Api.newStreamHost(host.ipAddress, host.port.toString(), host.packageName)
            )
            ProjectionEventLog.record(
                "DISCOVERY",
                "RideDaemon live-only session configured for ${host.ipAddress}:${host.port}; " +
                    "package=${host.packageName}; profile=${profile.key}; " +
                    "supportFunction=${profile.advertisedSupportFunction}; " +
                    "wire=${wire.signature}; " +
                    "proactivePxcHeartbeat=${wire.requiresProactivePxcHeartbeat}; " +
                    "plainVideoFramingAllowed=${wire.allowsPlainVideoFraming}; " +
                    "timeZone=${java.util.TimeZone.getDefault().id}."
            )
            host
        }.onFailure { failure ->
            stopSession()
            // User/scope cancellation is not a discovery failure; clean up and propagate it.
            if (failure is CancellationException) throw failure
            ProjectionEventLog.error("DISCOVERY", "RideDaemon discovery/configuration failed.", failure)
        }
    }

    override suspend fun start(host: TBoxHost): Result<Unit> =
        withContext(Dispatchers.IO) {
            val activeSession = session
            val activeLink = sessionLink
            if (activeSession == null || activeLink == null) {
                return@withContext Result.failure(
                    IllegalStateException("Call discover() with an active T-Box link before starting the session")
                )
            }
            runCatching {
                // ensureReversePortsAvailable() is the first thing each attempt does now, inside
                // startWithNetworkSocket - the ports an attempt needs are the ones the previous
                // attempt's native session has just released.
                ProjectionEventLog.record(
                    "TBOX",
                    "Starting EasyConn handshake to ${host.ipAddress}:${host.port}; " +
                        "waiting for the TFT video area. This dash has " +
                        "${RIDE_DAEMON_STARTUP_TIMEOUT_SEC}s to answer before the native session " +
                        "gives up - the wait a rider sees here is that one, not any shorter " +
                        "timeout named on the calling side."
                )
                startWithNetworkSocket(activeSession, host, activeLink)
                ProjectionEventLog.record("TBOX", "RideDaemon startSessionWithSocketFd returned successfully.")
                sessionStartedElapsed.set(SystemClock.elapsedRealtime())
                ladderVerdictFiled.set(false)
                armPxcWatchdog(activeSessionGeneration)
            }.onFailure {
                // The native call may already have opened 10920/10921/10922 before it
                // reports a timeout. Stop that session before the next user attempt.
                activeSession.runCatching { stopSession() }
                    .onFailure { stopFailure ->
                        ProjectionEventLog.warning("TBOX", "Failed to clean up the failed native session.", stopFailure)
                    }
                markNativeSessionStopped()
                ProjectionEventLog.error("TBOX", "EasyConn handshake failed.", it)
            }
        }

    /**
     * Waits for the phone-side EasyConn listeners before handing them to the native session.
     *
     * Failing on the first probe made a routine hand-off look like a hard conflict: a rider log
     * showed the ports still held 10s after MOTO-HUB asked the OEM companion app to stop, the
     * Android Auto hand-off aborted with EADDRINUSE, and the very next manual attempt ~20s later
     * connected normally. killBackgroundProcesses() cannot touch a foreground service and the
     * kernel releases the sockets asynchronously either way, so the only correct behaviour is to
     * wait a bounded time and only then report the conflict.
     *
     * How long that bounded time is, though, depends on who can possibly be holding them. The
     * hand-off story above only applies when a native session of OURS was just stopped; when none
     * was, the sockets belong to another app and no amount of waiting will change that. Support
     * case 36A3-FD37-1DD7 is the proof: eight full waits in eight minutes, not one release, 86
     * seconds spent re-learning something the first probe already knew.
     */
    private suspend fun ensureReversePortsAvailable() {
        var busy = ReversePortProbe.busyPorts()
        if (busy.isEmpty()) return
        // Nothing can close another app's sockets on Android 14+; the bounded wait below is the
        // part that actually resolves the routine hand-off case (kernel releases asynchronously).
        // One clock reading for both, so the log line can never explain a budget it did not get.
        val decidedAt = SystemClock.elapsedRealtime()
        val budgetMs = ReversePortProbe.waitBudgetMs(decidedAt)
        val because = if (ReversePortProbe.waitingOnOurOwnHandoff(decidedAt)) {
            "a MOTO-HUB session was stopped moments ago, so these are probably its own sockets " +
                "still closing"
        } else {
            "no MOTO-HUB session of ours has stopped recently, so another app is holding them " +
                "and waiting cannot change that"
        }
        ProjectionEventLog.warning(
            "TBOX",
            "Local reverse ports ${busy.joinToString()} are still held; $because - waiting up to " +
                "${budgetMs}ms for them to be released."
        )
        val deadline = SystemClock.elapsedRealtime() + budgetMs
        while (busy.isNotEmpty() && SystemClock.elapsedRealtime() < deadline) {
            delay(ReversePortProbe.POLL_MS)
            busy = ReversePortProbe.busyPorts()
        }
        if (busy.isNotEmpty()) {
            // Wording matters beyond the log: TBoxConflictDiagnostics.isPortConflict() reads this
            // message to decide whether the rider gets the force-stop help, so "still holds",
            // the port numbers and "address already in use" all have to survive any edit here.
            throw IllegalStateException(
                "Another EasyConn session still holds local reverse ports " +
                    "${busy.joinToString()} after ${budgetMs}ms " +
                    "(address already in use). Force-stop your motorcycle's own companion app " +
                    "and retry."
            )
        }
        ProjectionEventLog.record("TBOX", "Local reverse ports 10920-10922 were released; continuing.")
    }

    /**
     * Tells [ReversePortProbe] that a session which really did own the reverse ports has been
     * asked to stop, so the next attempt gets the patient hand-off wait.
     *
     * Guarded by [nativeStartAttempted] because the failure paths that call this also run when
     * [ensureReversePortsAvailable] itself threw - a handshake that never reached Go opened no
     * ports, and marking that as a hand-off would re-arm the long wait for sockets that were
     * never ours.
     */
    private fun markNativeSessionStopped() {
        if (nativeStartAttempted.getAndSet(false)) {
            ReversePortProbe.onNativeSessionStopped()
        }
    }

    override fun offerAccessUnit(avcc: ByteArray): Boolean {
        val activeSession = session ?: return false
        if (!activeSession.isRunning) return false
        val future = submitPushFrame(activeSession, avcc) ?: return false
        return try {
            future.get(PUSH_FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            framesOffered.incrementAndGet()
            lastFrameOfferedElapsed.set(SystemClock.elapsedRealtime())
            true
        } catch (timeout: java.util.concurrent.TimeoutException) {
            framesTimedOut.incrementAndGet()
            ProjectionEventLog.warning(
                "TBOX",
                "AVC frame dropped: pushFrame() exceeded ${PUSH_FRAME_TIMEOUT_MS}ms timeout. " +
                    "The T-Box may be unresponsive. Timeouts: ${framesTimedOut.get()}"
            )
            false
        } catch (failure: Throwable) {
            Log.w(TAG, "Unable to offer AVC access unit", failure)
            ProjectionEventLog.error("TBOX", "Unable to push an AVC access unit to RideDaemon.", failure)
            false
        }
    }

    /**
     * A transient overlap is recoverable: wait briefly for the bounded queue to accept the
     * access unit. Only a queue that remains blocked for the grace period is reported as a
     * transport failure to the caller.
     */
    private fun submitPushFrame(activeSession: MobileSession, avcc: ByteArray): java.util.concurrent.Future<*>? {
        val deadline = SystemClock.elapsedRealtime() + PUSH_FRAME_SUBMIT_WAIT_MS
        while (true) {
            try {
                return pushFrameExecutor.submit {
                    activeSession.pushFrame(avcc)
                }
            } catch (_: RejectedExecutionException) {
                val rejections = framesRejected.incrementAndGet()
                if (rejections == 1L || rejections % REJECTED_FRAME_LOG_INTERVAL == 0L) {
                    ProjectionEventLog.warning(
                        "TBOX",
                        "AVC frame submission temporarily delayed; waiting for the previous " +
                            "pushFrame() call. Rejections so far: $rejections."
                    )
                }
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) {
                    ProjectionEventLog.error(
                        "TBOX",
                        "AVC frame submission stayed blocked for ${PUSH_FRAME_SUBMIT_WAIT_MS}ms."
                    )
                    return null
                }
                try {
                    Thread.sleep(PUSH_FRAME_SUBMIT_RETRY_DELAY_MS.coerceAtMost(remaining))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        stopSession()
    }

    private fun stopSession() {
        EcBtpClockChannel.onSessionStopped()
        cancelPxcWatchdog()
        val sessionToStop: MobileSession?
        synchronized(sessionLock) {
            // Invalidate callbacks before asking the native session to stop. RideDaemon can
            // report the socket close asynchronously after stopSession() has been called.
            activeSessionGeneration = 0L
            sessionToStop = session
            session = null
            sessionLink = null
        }
        if (sessionToStop != null) {
            ProjectionEventLog.record("TBOX", "Stopping RideDaemon session. ${protocolSnapshot()}")
            // A ride the rider ended is the ladder's best evidence: it is the only way a rung
            // that works reaches TBoxSessionOutcome.STREAMED, because a dashboard that is happy
            // never stops anything. Ending it ourselves is not held against the wire.
            fileLadderVerdict(endedByDashboard = false)
        }
        sessionToStop?.runCatching { stopSession() }
            ?.onFailure { ProjectionEventLog.warning("TBOX", "RideDaemon stopSession failed.", it) }
        markNativeSessionStopped()
    }

    /**
     * The link this attempt should actually open its socket on.
     *
     * [TBoxLink.Infrastructure] holds one immutable [Network], captured when `discover()` ran.
     * Android replaces that `Network` whenever the association is rebuilt - a specifier network
     * that drops and is re-granted arrives as a *different* object - and binding a socket to the
     * old one fails with `EPERM`. The retry loop above was therefore structurally unable to
     * recover from the one failure it exists to absorb: it re-sent the identical dead handle
     * until it ran out of attempts.
     *
     * Field log 2026-07-30 (Zontes `ZT_…`): network 206 granted, lost 233ms later, 207 granted
     * two seconds after that - while all three handshake attempts bound to network 204, an even
     * older handle, and failed with EPERM in ~10ms each. The rider was then told the TFT had
     * refused the video stream.
     *
     * The process binding is the authority, not this class's own bookkeeping:
     * [TBoxNetworkConnector] binds the process on every successful join and clears the binding in
     * `onLost`, so it is exactly "the network T-Box traffic egresses over right now". A null
     * binding is deliberately NOT treated as fatal - the connector also unbinds briefly on
     * purpose - so the attempt falls back to the captured link and the retry gets another chance
     * once the replacement network is bound.
     */
    private fun linkForThisAttempt(link: TBoxLink): TBoxLink {
        if (link !is TBoxLink.Infrastructure) return link
        val bound = connectivityManager?.boundNetworkForProcess
        if (bound == null) {
            ProjectionEventLog.debug(
                "TBOX",
                "No T-Box network is bound to this process right now; the handshake keeps using " +
                    "the link from discovery (${link.label})."
            )
            return link
        }
        if (bound == link.network) return link
        ProjectionEventLog.warning(
            "TBOX",
            "The T-Box network was replaced during the EasyConn handshake " +
                "(${link.label} -> network=$bound); reopening the command socket on the current one."
        )
        // The session's link genuinely moved: leaving the dead handle in place would make every
        // later call on this session repeat the same EPERM.
        val refreshed = TBoxLink.Infrastructure(bound)
        sessionLink = refreshed
        return refreshed
    }

    /**
     * Opens the EasyConn command socket over the established T-Box link and hands it to the
     * native session - both inside the retry, because both are what the retry was written for.
     *
     * The native handshake used to sit OUTSIDE this loop, so "EasyConn attempt 1/3" counted TCP
     * connects and nothing else. [isTransientEasyConnFailure] gives that away: `context deadline
     * exceeded`, `unsuccessful ec response`, `failed to decode response`, `initialize easyconn
     * stream` are Go handshake errors, and not one of them could ever reach the classifier -
     * `socket.connect` only ever throws `IOException`, which the chain check catches on its own.
     * A handshake that failed fast was therefore never retried, and the log said 1/3 while
     * promising three of something else.
     *
     * A handshake that fails SLOW still must not be retried here, and
     * [EC_HANDSHAKE_TOTAL_BUDGET_MS] is what stops it: one attempt that burns the entire native
     * startup budget is a dash that is not answering at all, and re-dialling it on the same
     * session would only double the rider's wait. That case belongs to the caller's
     * re-discover-and-retry, which is a genuinely different attempt - fresh discovery, fresh
     * native session - not a louder version of this one.
     */
    private suspend fun startWithNetworkSocket(
        activeSession: MobileSession,
        host: TBoxHost,
        link: TBoxLink
    ) {
        val policy = EasyConnRetryPolicy(totalBudgetMillis = EC_HANDSHAKE_TOTAL_BUDGET_MS)
        val startedOnAttempt = retryEasyConnStart(
            policy = policy,
            shouldRetry = ::isTransientEasyConnFailure,
            onRetry = { failedAttempt, delayMillis, failure ->
                ProjectionEventLog.warning(
                    "TBOX",
                    "EasyConn attempt $failedAttempt/${policy.maxAttempts} failed: " +
                        "${failure.message.orEmpty()}. Retrying in ${delayMillis}ms."
                )
                // The failed attempt may already have opened 10920/10921/10922. Without this the
                // next one meets "already running", which this policy reads as permanent - the
                // retry would end on an error that says nothing about the dash.
                activeSession.runCatching { stopSession() }
                    .onFailure { stopFailure ->
                        ProjectionEventLog.warning(
                            "TBOX",
                            "Failed to clean up the native session before the next EasyConn attempt.",
                            stopFailure
                        )
                    }
                markNativeSessionStopped()
            },
            onBudgetSpent = { failedAttempt, spentMillis, _ ->
                ProjectionEventLog.warning(
                    "TBOX",
                    "EasyConn attempt $failedAttempt/${policy.maxAttempts} used ${spentMillis}ms of " +
                        "the ${EC_HANDSHAKE_TOTAL_BUDGET_MS}ms this handshake is allowed, so there " +
                        "is no time for another one here. A dash that stays silent for the whole " +
                        "${RIDE_DAEMON_STARTUP_TIMEOUT_SEC}s startup budget needs a fresh session, " +
                        "not another dial on this one."
                )
            },
            elapsedMillis = SystemClock::elapsedRealtime
        ) { attempt ->
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            // Re-checked per attempt now that a whole handshake precedes the next one: the ports
            // this attempt needs are the ones the previous attempt's native session just gave back.
            ensureReversePortsAvailable()
            val attemptLink = linkForThisAttempt(link)
            ProjectionEventLog.debug(
                "TBOX",
                "EasyConn attempt $attempt/${policy.maxAttempts}: opening link-bound command " +
                    "socket to ${host.ipAddress}:${host.port} (${attemptLink.label})."
            )
            val socket = attemptLink.createSocket()
            try {
                socket.connect(InetSocketAddress(host.ipAddress, host.port), EC_CONNECT_TIMEOUT_MS)
                ProjectionEventLog.record("TBOX", "EasyConn TCP command socket connected.")
                socket.use { connected ->
                    ParcelFileDescriptor.fromSocket(connected).use { descriptor ->
                        val fd = descriptor.detachFd().toLong()
                        // ParcelFileDescriptor duplicates the socket descriptor. Go owns and
                        // closes the detached duplicate; the outer use{} closes the original
                        // Java socket.
                        // Marked before the call, not after: a start that times out may well have
                        // opened the reverse ports before giving up, and that case is exactly the
                        // hand-off the patient wait exists for.
                        nativeStartAttempted.set(true)
                        activeSession.startSessionWithSocketFd(fd)
                    }
                }
                attempt
            } catch (failure: Throwable) {
                // Socket.close() is idempotent, so the use{} above having already closed it is fine.
                runCatching { socket.close() }
                throw failure
            }
        }
        if (startedOnAttempt > 1) {
            ProjectionEventLog.record(
                "TBOX",
                "EasyConn handshake recovered on attempt " +
                    "$startedOnAttempt/${policy.maxAttempts}."
            )
        }
    }

    // Catches only the withTimeout-specific subtype so a real user cancellation (plain
    // CancellationException) still propagates immediately instead of being retried; ensureActive
    // rethrows when the TimeoutCancellationException actually belongs to an enclosing withTimeout.
    private suspend fun discoverWithRetry(link: TBoxLink, expectedModelId: String?): TBoxHost {
        // A Wi-Fi Direct group has no bindable Network, so NSD cannot resolve the service over it.
        // Skip the (useless) discovery windows and probe the group owner directly, immediately after
        // the join while the p2p source address is still fresh - waiting 30s for NSD to fail was what
        // let the address go stale and made the probe socket bind fail with EADDRNOTAVAIL.
        if (link is TBoxLink.WifiDirect) return discoverOverWifiDirect(link)
        // The phone is the gateway here, so there is no advertised dash AP and no `.1` to aim at:
        // the dash is a DHCP client somewhere on our own tethering subnet. NSD is tried anyway
        // (cheap, and the dash may well advertise once it has an address) before sweeping.
        if (link is TBoxLink.PhoneHotspot) return discoverOverPhoneHotspot(link, expectedModelId)

        repeat(DISCOVERY_MAX_ATTEMPTS - 1) { attempt ->
            try {
                return withTimeout(DISCOVERY_TIMEOUT_MS) { discoverWithAndroidNsd(link, expectedModelId) }
            } catch (timeout: TimeoutCancellationException) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                ProjectionEventLog.warning(
                    "DISCOVERY",
                    "No EasyConn advertisement seen within ${DISCOVERY_TIMEOUT_MS}ms " +
                        "(attempt ${attempt + 1}/$DISCOVERY_MAX_ATTEMPTS); restarting NSD discovery."
                )
                delay(DISCOVERY_RETRY_DELAY_MS)
            }
        }
        try {
            return withTimeout(DISCOVERY_TIMEOUT_MS) { discoverWithAndroidNsd(link, expectedModelId) }
        } catch (timeout: TimeoutCancellationException) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            ProjectionEventLog.warning(
                "DISCOVERY",
                "No EasyConn advertisement seen in $DISCOVERY_MAX_ATTEMPTS windows of " +
                    "${DISCOVERY_TIMEOUT_MS / 1000}s each; the T-Box may still be starting up. " +
                    "Sending an active wake probe."
            )
        }

        // Infrastructure fallback: a probe ACK on an AP link is first spent re-arming one more
        // NSD window, because a resolved advertisement carries the package name too. When that
        // window stays empty as well, the ACK itself is the endpoint: a completed CMD_MDNS_RESPOND
        // handshake is the same proof the Wi-Fi Direct and phone-hotspot paths accept, and the
        // identity the dash acknowledged is the name the EC init command would carry anyway.
        // Field case (Zontes 368G, 2026-09-03): the dash acknowledged 10930 five times out of five
        // while never advertising, and the sweep below skips 10930 by construction - so a dash that
        // had answered every single time was reported as "not found".
        val peerIp = peerIpv4For(link)
        val peerAddress = peerIp?.hostAddress
        val acknowledged = sendEasyConnWakeProbe(link)
        if (acknowledged != null) {
            try {
                return withTimeout(DISCOVERY_TIMEOUT_MS) { discoverWithAndroidNsd(link, expectedModelId) }
            } catch (timeout: TimeoutCancellationException) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
            }
            if (peerAddress != null) {
                ProjectionEventLog.record(
                    "DISCOVERY",
                    "EasyConn endpoint confirmed by the wake probe at $peerAddress:$WAKE_PROBE_PORT " +
                        "(acknowledged as \"$acknowledged\") after NSD stayed empty; using it directly."
                )
                return TBoxHost(peerAddress, WAKE_PROBE_PORT, acknowledged)
            }
        }

        // Last resort on an AP link, and the reason this exists: field logs from Zontes, VOGE and
        // QJ dashes show NSD staying empty and 10930 refused while the dash is plainly up. Sweeping
        // the EasyConn neighborhood and re-running the probe there is the only way to tell "the
        // dash speaks EasyConn on an unusual port" apart from "the dash never answered at all".
        // The endpoint is used ONLY when the full CMD_MDNS_RESPOND handshake completes on it - an
        // open TCP port alone is never promoted to an EC endpoint, so the "no invented port"
        // rule in TBOX_STREAMING_CONTRACT.md still holds.
        if (peerIp != null && peerAddress != null) {
            val fallback = probeFallbackEasyConnPort(link, peerIp)
            if (fallback != null) {
                val (fallbackPort, fallbackIdentity) = fallback
                ProjectionEventLog.record(
                    "DISCOVERY",
                    "EasyConn endpoint confirmed by wake probe on fallback port " +
                        "$peerAddress:$fallbackPort after NSD stayed empty."
                )
                return TBoxHost(peerAddress, fallbackPort, fallbackIdentity)
            }
        }
        throw IllegalStateException(
            "The EasyConn service was not advertised in $DISCOVERY_MAX_ATTEMPTS discovery windows of " +
                "${DISCOVERY_TIMEOUT_MS / 1000}s each. This can happen when your motorcycle's own " +
                "companion app is already connected to it, or when the T-Box is still starting up " +
                "after Wi-Fi association."
        )
    }

    /**
     * Discovery for a Wi-Fi Direct group owner dash. NSD is skipped (no bindable Network to run it
     * on); instead the group owner is asked directly with an EasyConn wake probe. A completed ACK is
     * a full CMD_MDNS_RESPOND handshake, so the group owner IS the confirmed EC endpoint - not an
     * invented one - matching what every reference implementation does for P2P dashes.
     */
    private suspend fun discoverOverWifiDirect(link: TBoxLink.WifiDirect): TBoxHost {
        val peerAddress = link.gatewayIp.hostAddress
        val acknowledged = sendEasyConnWakeProbe(link)
        if (acknowledged != null && peerAddress != null) {
            ProjectionEventLog.record(
                "DISCOVERY",
                "Wi-Fi Direct EasyConn endpoint confirmed at $peerAddress:$WAKE_PROBE_PORT."
            )
            return TBoxHost(peerAddress, WAKE_PROBE_PORT, acknowledged)
        }
        // Some firmware variants refuse 10930 outright (observed as ECONNREFUSED on T-Boxes the
        // reference projects never reverse-engineered) while answering the same handshake on a
        // nearby port. Before giving up, sweep the known EasyConn neighborhood for open TCP
        // ports and retry the ACK-verified wake probe there - the endpoint is only ever used
        // when the full CMD_MDNS_RESPOND handshake completed, never invented from an open port.
        if (peerAddress != null) {
            val fallback = probeFallbackEasyConnPort(link, link.gatewayIp)
            if (fallback != null) {
                val (fallbackPort, fallbackIdentity) = fallback
                ProjectionEventLog.record(
                    "DISCOVERY",
                    "Wi-Fi Direct EasyConn endpoint confirmed on fallback port " +
                        "$peerAddress:$fallbackPort."
                )
                return TBoxHost(peerAddress, fallbackPort, fallbackIdentity)
            }
        }
        throw IllegalStateException(
            "The Wi-Fi Direct dash did not answer an EasyConn wake probe at " +
                "${link.gatewayIp.hostAddress}:$WAKE_PROBE_PORT (or on any nearby fallback port). " +
                "The dash may still be starting up, or its own companion app may already be " +
                "connected to it."
        )
    }

    /**
     * Discovery when the phone hosts the network. NSD is given one window first - it costs a few
     * seconds and would hand back the service package too, which the sweep cannot - then every
     * address on the tethering subnet is probed on the well-known port, nearest the phone first.
     *
     * The endpoint is adopted only when the full CMD_MDNS_RESPOND handshake completes, exactly as
     * on the other two transports: an open TCP port is never promoted on its own.
     */
    private suspend fun discoverOverPhoneHotspot(
        link: TBoxLink.PhoneHotspot,
        expectedModelId: String?
    ): TBoxHost {
        try {
            return withTimeout(DISCOVERY_TIMEOUT_MS) { discoverWithAndroidNsd(link, expectedModelId) }
        } catch (timeout: TimeoutCancellationException) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            ProjectionEventLog.record(
                "DISCOVERY",
                "No EasyConn advertisement on the hosted network; sweeping " +
                    "${link.subnet.localAddress.hostAddress}/${link.subnet.prefixLength} for the dash."
            )
        }
        val found = probeHostedSubnet(link)
            ?: throw IllegalStateException(
                "No motorcycle answered on the hotspot your phone is hosting. Check that the dash " +
                    "shows it is connected, and that the hotspot Ssid and Password match exactly " +
                    "what the dash is asking for."
            )
        val (host, identity) = found
        val address = host.hostAddress
            ?: throw IllegalStateException("The dash answered but its address could not be read.")
        ProjectionEventLog.record(
            "DISCOVERY",
            "EasyConn endpoint confirmed on the hosted network at $address:$WAKE_PROBE_PORT."
        )
        return TBoxHost(address, WAKE_PROBE_PORT, identity)
    }

    /**
     * Walks the hosted subnet looking for a dash. Two passes on purpose: a cheap connect to the
     * well-known port narrows 253 addresses down to the handful that answer at all, and only those
     * pay for the full ACK-verified probe. One pass of full probes over a /24 would take minutes.
     */
    private suspend fun probeHostedSubnet(link: TBoxLink.PhoneHotspot): Pair<Inet4Address, String>? =
        withContext(Dispatchers.IO) {
            // A dash that reported its own address is not a candidate among 253, it is THE
            // candidate; probing it first turns a sweep into one connect. It still only leads the
            // list rather than replacing it, because a lease can change and the address the dash
            // announced a minute ago may no longer be the one it holds.
            val announced = link.peerHint
            val candidates = listOfNotNull(announced) +
                TBoxHotspotScan.candidateHosts(link.subnet).filterNot { it == announced }
            val reachable = mutableListOf<Inet4Address>()
            // A sweep that announces its own length is a sweep a truncated log can still be read
            // against. The 2026-08-23 QJ log stopped 44 seconds into this loop - the rider closed
            // the app - and carried no line between "sweeping" and nothing at all, so it could not
            // even be said how far it had got or whether it had been given time to finish.
            val sweepStartedAtMs = SystemClock.elapsedRealtime()
            ProjectionEventLog.record(
                "DISCOVERY",
                "Hosted-network sweep: ${candidates.size} addresses to try on port " +
                    "$WAKE_PROBE_PORT, up to " +
                    "${candidates.size * HOSTED_SWEEP_CONNECT_TIMEOUT_MS / 1000}s if every one of " +
                    "them stays silent. Leaving MOTO-HUB now ends it."
            )
            for ((index, candidate) in candidates.withIndex()) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (index > 0 && index % HOSTED_SWEEP_PROGRESS_STRIDE == 0) {
                    ProjectionEventLog.record(
                        "DISCOVERY",
                        "Hosted-network sweep: $index of ${candidates.size} addresses tried in " +
                            "${SystemClock.elapsedRealtime() - sweepStartedAtMs}ms, " +
                            if (reachable.isEmpty()) {
                                "none answering so far."
                            } else {
                                "${reachable.size} answering so far."
                            }
                    )
                }
                val open = runCatching {
                    link.createSocket().use { socket ->
                        socket.connect(
                            InetSocketAddress(candidate, WAKE_PROBE_PORT),
                            HOSTED_SWEEP_CONNECT_TIMEOUT_MS
                        )
                    }
                    true
                }.getOrDefault(false)
                if (!open) continue
                ProjectionEventLog.record(
                    "DISCOVERY",
                    "Hosted-network sweep: ${candidate.hostAddress} accepted $WAKE_PROBE_PORT."
                )
                reachable += candidate
                // The dash is the only device on a hotspot opened for it, so the first responder
                // is almost certainly it - verify immediately rather than sweeping the whole /24.
                for (identity in EasyConnClientIdentity.probeOrder()) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val acknowledged = runCatching {
                        link.createSocket().use { socket ->
                            socket.connect(
                                InetSocketAddress(candidate, WAKE_PROBE_PORT),
                                HOSTED_SWEEP_CONNECT_TIMEOUT_MS
                            )
                            socket.soTimeout = WAKE_PROBE_READ_TIMEOUT_MS
                            writeWakeProbeFrame(socket.getOutputStream(), identity)
                            readWakeProbeAck(socket.getInputStream())
                        }
                    }.getOrDefault(false)
                    if (acknowledged) {
                        EasyConnClientIdentity.remember(identity)
                        return@withContext candidate to identity
                    }
                }
            }
            ProjectionEventLog.record(
                "DISCOVERY",
                if (reachable.isEmpty()) {
                    "Hosted-network sweep: nothing answered $WAKE_PROBE_PORT on " +
                        "${candidates.size} addresses in " +
                        "${SystemClock.elapsedRealtime() - sweepStartedAtMs}ms. Either the dash " +
                        "has not joined the hotspot yet, or it speaks on a port MOTO-HUB does " +
                        "not know."
                } else {
                    "Hosted-network sweep: ${reachable.joinToString { it.hostAddress.orEmpty() }} " +
                        "accepted $WAKE_PROBE_PORT but none completed the EasyConn handshake."
                }
            )
            // Which of those two it was is decided by whether anything is on the subnet at all,
            // so the neighbour table is read again here rather than only at link time: a dash
            // that joins slowly is not on it when the hotspot comes up and is by the time the
            // sweep gives up.
            ProjectionEventLog.record(
                "DISCOVERY",
                TBoxHotspotScan.describeNeighbours(link.subnet.interfaceName)
            )
            null
        }

    /**
     * Sweeps the candidate EasyConn ports over the P2P link and retries the wake probe on any
     * that accept a TCP connection. Returns the first port whose CMD_MDNS_RESPOND handshake
     * completes together with the client identity that earned the acknowledgement, or null when
     * no combination answers.
     */
    private suspend fun probeFallbackEasyConnPort(
        link: TBoxLink,
        peerIp: Inet4Address
    ): Pair<Int, String>? =
        withContext(Dispatchers.IO) {
            val openPorts = FALLBACK_EC_PORTS.filter { port ->
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                runCatching {
                    link.createSocket().use { socket ->
                        socket.connect(
                            InetSocketAddress(peerIp, port),
                            FALLBACK_PORT_CONNECT_TIMEOUT_MS
                        )
                    }
                    true
                }.getOrDefault(false)
            }
            if (openPorts.isEmpty()) {
                ProjectionEventLog.record(
                    "DISCOVERY",
                    "Fallback port sweep found no open EasyConn candidates on " +
                        "${peerIp.hostAddress}."
                )
                return@withContext null
            }
            ProjectionEventLog.record(
                "DISCOVERY",
                "Fallback port sweep: open candidates ${openPorts.joinToString()} on " +
                    "${peerIp.hostAddress}; retrying the wake probe on each."
            )
            // Identity first, ports second: sweeping every open port with the leading identity
            // before reaching for an alternate keeps the common case as quick as it was, and the
            // port a dash answers on is far less predictable than the name it accepts.
            for (identity in EasyConnClientIdentity.probeOrder()) {
                for (port in openPorts) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    try {
                        link.createSocket().use { socket ->
                            socket.connect(
                                InetSocketAddress(peerIp, port),
                                FALLBACK_PORT_CONNECT_TIMEOUT_MS
                            )
                            socket.soTimeout = WAKE_PROBE_READ_TIMEOUT_MS
                            writeWakeProbeFrame(socket.getOutputStream(), identity)
                            if (readWakeProbeAck(socket.getInputStream())) {
                                ProjectionEventLog.record(
                                    "DISCOVERY",
                                    "Fallback wake probe on port $port acknowledged as " +
                                        "\"$identity\"; later probes will lead with it."
                                )
                                EasyConnClientIdentity.remember(identity)
                                return@withContext port to identity
                            }
                        }
                    } catch (failure: Throwable) {
                        ProjectionEventLog.debug(
                            "DISCOVERY",
                            "Fallback wake probe on port $port as \"$identity\" failed: " +
                                "${failure.message}."
                        )
                    }
                }
            }
            null
        }

    /**
     * Actively asks the T-Box to respond instead of waiting for it to broadcast on its own.
     * Some Wi-Fi Direct group-owner T-Boxes never advertise `_EasyConn._tcp.` proactively; a
     * direct probe on the well-known port 10930 is what OpenCfMoto/OpenMoto observed working
     * for that case. A completed ACK is a full EasyConn CMD_MDNS_RESPOND handshake, so on a
     * Wi-Fi Direct group (where NSD has no bindable Network) the ACK-confirmed endpoint is used
     * directly as the EC host/port; on infrastructure links it only re-arms one more NSD window.
     */
    /**
     * @return the client identity the dash acknowledged, or null when none of them was answered.
     *
     * The leading identity keeps the whole retry budget to itself, because those retries exist for
     * a dash that is merely still booting and swapping names between them would answer a slow dash
     * with a name it never accepts. Only once that identity has been given every chance do the
     * alternates get one attempt each — extra time paid solely by riders the proven name failed.
     */
    /**
     * The T-Box address to aim a direct probe at, without waiting for discovery: the link's own
     * hint on a P2P group, otherwise derived from the AP's routes/DNS. Extracted so the wake probe
     * and the fallback port sweep aim at the same peer instead of deriving it twice.
     */
    private fun peerIpv4For(link: TBoxLink): Inet4Address? =
        link.peerHint ?: link.network?.let { network ->
            connectivityManager.getLinkProperties(network)?.let { properties ->
                deriveTBoxPeerIpv4(
                    gateways = properties.routes.filter { route -> route.isDefaultRoute }.mapNotNull { route -> route.gateway },
                    dnsServers = properties.dnsServers,
                    localAddresses = properties.linkAddresses.map { linkAddress -> linkAddress.address to linkAddress.prefixLength }
                )
            }
        }

    private suspend fun sendEasyConnWakeProbe(link: TBoxLink): String? = withContext(Dispatchers.IO) {
        val peerIp = peerIpv4For(link)
        if (peerIp == null) {
            ProjectionEventLog.debug("DISCOVERY", "Wake probe skipped: no usable peer IPv4 could be derived.")
            return@withContext null
        }
        val identities = EasyConnClientIdentity.probeOrder()
        ProjectionEventLog.record(
            "DISCOVERY",
            "Sending an EasyConn wake probe to ${peerIp.hostAddress}:$WAKE_PROBE_PORT " +
                "(identities: ${identities.joinToString()})."
        )
        identities.forEachIndexed { position, identity ->
            val budget = if (position == 0) WAKE_PROBE_ATTEMPTS else 1
            repeat(budget) { attempt ->
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                try {
                    link.createSocket().use { socket ->
                        socket.connect(InetSocketAddress(peerIp, WAKE_PROBE_PORT), WAKE_PROBE_CONNECT_TIMEOUT_MS)
                        socket.soTimeout = WAKE_PROBE_READ_TIMEOUT_MS
                        writeWakeProbeFrame(socket.getOutputStream(), identity)
                        if (readWakeProbeAck(socket.getInputStream())) {
                            ProjectionEventLog.record(
                                "DISCOVERY",
                                "T-Box acknowledged the wake probe as \"$identity\" on attempt " +
                                    "${attempt + 1}/$budget."
                            )
                            EasyConnClientIdentity.remember(identity)
                            return@withContext identity
                        }
                    }
                    ProjectionEventLog.debug(
                        "DISCOVERY",
                        "Wake probe attempt ${attempt + 1}/$budget as \"$identity\": " +
                            "no acknowledgement."
                    )
                } catch (failure: Throwable) {
                    ProjectionEventLog.debug(
                        "DISCOVERY",
                        "Wake probe attempt ${attempt + 1}/$budget as \"$identity\" to " +
                            "${peerIp.hostAddress}:$WAKE_PROBE_PORT failed: ${failure.message}."
                    )
                }
                if (attempt < budget - 1) delay(WAKE_PROBE_RETRY_DELAY_MS)
            }
        }
        null
    }

    /** 16-byte little-endian header (cmd, totalLen, cmd xor totalLen, reserved) plus JSON payload. */
    private fun writeWakeProbeFrame(out: OutputStream, identity: String) {
        val payload = EasyConnClientIdentity.probeBody(identity).toByteArray(Charsets.UTF_8)
        val totalLen = WAKE_PROBE_HEADER_SIZE + payload.size
        val header = ByteBuffer.allocate(WAKE_PROBE_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(0, CMD_MDNS_RESPOND)
        header.putInt(4, totalLen)
        header.putInt(8, CMD_MDNS_RESPOND xor totalLen)
        out.write(header.array())
        if (payload.isNotEmpty()) out.write(payload)
        out.flush()
    }

    private fun readWakeProbeAck(input: InputStream): Boolean {
        val header = ByteArray(WAKE_PROBE_HEADER_SIZE)
        if (!readFullyOrFalse(input, header)) return false
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = buffer.getInt(0)
        val totalLen = buffer.getInt(4)
        val magic = buffer.getInt(8)
        if ((cmd xor totalLen) != magic || cmd != CMD_MDNS_RESPOND_ACK) return false
        val payloadLen = (totalLen - WAKE_PROBE_HEADER_SIZE).coerceAtLeast(0)
        val payload = ByteArray(payloadLen)
        if (payloadLen > 0 && !readFullyOrFalse(input, payload)) return false
        return payload.toString(Charsets.UTF_8).contains("true")
    }

    private fun readFullyOrFalse(input: InputStream, buffer: ByteArray): Boolean {
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n <= 0) return false
            read += n
        }
        return true
    }

    private suspend fun discoverWithAndroidNsd(
        link: TBoxLink,
        expectedModelId: String?
    ): TBoxHost = suspendCancellableCoroutine { continuation ->
        val completed = AtomicBoolean(false)
        val multicastLock = wifiManager.createMulticastLock("$TAG.mDns").apply {
            setReferenceCounted(false)
            acquire()
        }
        ProjectionEventLog.debug("DISCOVERY", "mDNS multicast lock acquired.")
        lateinit var listener: NsdManager.DiscoveryListener
        var serviceCallback: NsdManager.ServiceInfoCallback? = null
        // The <API-34 resolution slot: NsdManager.resolveService is one-shot, so at most one
        // legacy resolve may be in flight, mirroring the single-ServiceInfoCallback rule above.
        val legacyResolveActive = AtomicBoolean(false)
        val discoveryStopped = AtomicBoolean(false)

        fun stopDiscovery() {
            if (!discoveryStopped.compareAndSet(false, true)) return
            // serviceCallback is only ever assigned on API 34+, but the call itself must stay
            // behind the gate too or NewApi flags it at minSdk 31.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                serviceCallback?.let { callback ->
                    runCatching { nsdManager.unregisterServiceInfoCallback(callback) }
                }
            }
            runCatching { nsdManager.stopServiceDiscovery(listener) }
            if (multicastLock.isHeld) multicastLock.release()
            ProjectionEventLog.debug("DISCOVERY", "NSD discovery stopped and multicast lock released.")
        }

        fun finish(result: Result<TBoxHost>) {
            if (!completed.compareAndSet(false, true)) return
            stopDiscovery()
            continuation.resumeWith(result)
        }

        listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) {
                Log.d(TAG, "Android NSD discovery started: $serviceType")
                ProjectionEventLog.record("DISCOVERY", "Android NSD started for serviceType=$serviceType.")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                if (serviceInfo == null || !serviceInfo.serviceType.endsWith(SERVICE_TYPE)) return

                // Shared acceptance logic for both resolution paths. The API-34 path
                // (ServiceInfoCallback) carries the resolved Network and every host address;
                // the legacy resolveService() used on Android 12/13 has a single host and no
                // Network attached, so [networkAware] is false there and acceptance falls back
                // to the address checks alone - exactly the pre-API-33 NSD world.
                fun handleResolved(
                    resolved: NsdServiceInfo,
                    resolvedNetwork: Network?,
                    resolvedHostAddresses: List<InetAddress>,
                    networkAware: Boolean,
                    releaseSlot: () -> Unit
                ) {
                    if (networkAware && !link.matchesResolvedNetwork(resolvedNetwork)) {
                        // Only ONE candidate can hold the resolution slot (see onServiceFound).
                        // A candidate pinned to the WRONG network will never migrate to the
                        // T-Box link, so keeping the slot occupied silently blocked every
                        // later (correct) candidate until the discovery window expired. A
                        // null network is different: with network-scoped discovery it can be
                        // a transient of the resolution in progress, so that candidate keeps
                        // the slot and the next update decides.
                        if (resolvedNetwork != null) {
                            ProjectionEventLog.warning(
                                "DISCOVERY",
                                "Candidate ${resolved.serviceName} resolved on the wrong " +
                                    "network ($resolvedNetwork); releasing the resolution " +
                                    "slot for the next candidate."
                            )
                            releaseSlot()
                        }
                        return
                    }
                    val attributes = resolved.attributes
                    val simulatorProfileRequested =
                        TBoxModelProfile.fromModelId(expectedModelId) == TBoxModelProfile.MOTO_HUB_SIMULATOR
                    val advertisedModelId = attributes[MODEL_ID_ATTRIBUTE]
                        ?.toString(Charsets.UTF_8)
                        ?.trim()
                    if (
                        simulatorProfileRequested &&
                        !isMotoHubSimulatorAdvertisement(resolved.serviceName, advertisedModelId)
                    ) {
                        ProjectionEventLog.warning(
                            "DISCOVERY",
                            "Ignoring EasyConn candidate ${resolved.serviceName}: " +
                                "it is not an identified MOTO-HUB simulator preset (modelId=$advertisedModelId)."
                        )
                        releaseSlot()
                        return
                    }
                    // A Zontes 125X (modelId 21340, field log 2026-08-19) advertises
                    // _EasyConn._tcp with a usable host and port but no packagename in its TXT
                    // record. Rejecting the resolution over that one missing key threw away an
                    // endpoint that was otherwise complete - and the reject did not release the
                    // resolution slot, so the same candidate went on blocking every later one
                    // until the window expired. Discovery then reported "never advertised" for a
                    // dash it had just resolved four times, on a bike whose wake probe was being
                    // acknowledged the whole time.
                    //
                    // The name is not ours to learn from the advertisement anyway: it travels
                    // back to the dash in the EC init command, which is precisely what the wake
                    // probe negotiates. So the probe's ladder answers it - the identity already
                    // acknowledged on this device if one has been, the leading candidate
                    // otherwise, which is the same value the Wi-Fi Direct path records where TXT
                    // metadata does not exist at all.
                    val advertisedPackage = decodeEasyConnPackage(attributes[PACKAGE_ATTRIBUTE])
                    val packageName = advertisedPackage ?: EasyConnClientIdentity.probeOrder().first()
                    if (advertisedPackage == null) {
                        Log.w(TAG, "EasyConn service resolved without package metadata")
                        ProjectionEventLog.warning(
                            "DISCOVERY",
                            "Resolved EasyConn service has no package metadata; continuing with " +
                                "the probe identity \"$packageName\"."
                        )
                    }

                    val advertisedIp = attributes[IP_ATTRIBUTE]
                        ?.toString(Charsets.UTF_8)
                        ?.let(::parseUsableEasyConnIpv4Literal)
                    val resolvedIp = resolvedHostAddresses
                        .filterIsInstance<Inet4Address>()
                        .firstOrNull(::isUsableTBoxIpv4Address)
                        ?.hostAddress
                    val unusableResolvedIp = resolvedHostAddresses
                        .filterIsInstance<Inet4Address>()
                        .firstOrNull()
                        ?.hostAddress
                    val derivedIp = if (!simulatorProfileRequested && advertisedIp == null && resolvedIp == null) {
                        link.peerHint?.hostAddress ?: link.network?.let { activeNetwork ->
                            connectivityManager.getLinkProperties(activeNetwork)?.let { linkProperties ->
                                deriveTBoxPeerIpv4(
                                    gateways = linkProperties.routes
                                        .filter { it.isDefaultRoute }
                                        .mapNotNull { it.gateway },
                                    dnsServers = linkProperties.dnsServers,
                                    localAddresses = linkProperties.linkAddresses
                                        .map { it.address to it.prefixLength }
                                )
                            }?.hostAddress
                        }
                    } else {
                        null
                    }
                    val ipAddress = advertisedIp ?: resolvedIp ?: derivedIp
                    val port = resolved.port
                    if (ipAddress.isNullOrBlank() || port !in 1..65535) {
                        Log.w(TAG, "EasyConn service resolved without a usable host")
                        ProjectionEventLog.warning(
                            "DISCOVERY",
                            "Resolved EasyConn service has invalid endpoint: " +
                                "advertisedIp=${attributes[IP_ATTRIBUTE]?.toString(Charsets.UTF_8)}, " +
                                "resolvedIp=$unusableResolvedIp, port=$port."
                        )
                        return
                    }
                    if (derivedIp != null) {
                        ProjectionEventLog.warning(
                            "DISCOVERY",
                            "EasyConn advertised no IPv4 host; using network-derived peer $derivedIp."
                        )
                    }
                    ProjectionEventLog.record(
                        "DISCOVERY",
                        "NSD resolution accepted: $ipAddress:$port, package=$packageName, network=$resolvedNetwork."
                    )
                    finish(Result.success(TBoxHost(ipAddress, port, packageName)))
                }

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    // registerServiceInfoCallback is API 34; Android 12/13 resolve the old way.
                    if (!legacyResolveActive.compareAndSet(false, true)) return
                    ProjectionEventLog.record(
                        "DISCOVERY",
                        "NSD candidate found: name=${serviceInfo.serviceName}, type=${serviceInfo.serviceType}."
                    )
                    @Suppress("DEPRECATION")
                    nsdManager.resolveService(
                        serviceInfo,
                        object : NsdManager.ResolveListener {
                            override fun onServiceResolved(resolved: NsdServiceInfo) {
                                @Suppress("DEPRECATION")
                                val addresses = listOfNotNull(resolved.host)
                                handleResolved(
                                    resolved,
                                    resolvedNetwork = null,
                                    resolvedHostAddresses = addresses,
                                    networkAware = false,
                                    releaseSlot = { legacyResolveActive.set(false) }
                                )
                            }

                            override fun onResolveFailed(failed: NsdServiceInfo?, errorCode: Int) {
                                legacyResolveActive.set(false)
                                Log.w(TAG, "Legacy NSD resolve failed: $errorCode")
                                ProjectionEventLog.warning(
                                    "DISCOVERY",
                                    "Legacy NSD resolve failed: code=$errorCode."
                                )
                            }
                        }
                    )
                    return
                }

                if (serviceCallback != null) return
                ProjectionEventLog.record(
                    "DISCOVERY",
                    "NSD candidate found: name=${serviceInfo.serviceName}, type=${serviceInfo.serviceType}."
                )
                val callback = object : NsdManager.ServiceInfoCallback {
                    override fun onServiceUpdated(resolved: NsdServiceInfo) {
                        handleResolved(
                            resolved,
                            resolvedNetwork = resolved.network,
                            resolvedHostAddresses = resolved.hostAddresses,
                            networkAware = true,
                            releaseSlot = {
                                serviceCallback = null
                                runCatching { nsdManager.unregisterServiceInfoCallback(this) }
                            }
                        )
                    }

                    override fun onServiceLost() = Unit

                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                        serviceCallback = null
                        Log.w(TAG, "EasyConn service callback failed: $errorCode")
                        ProjectionEventLog.warning("DISCOVERY", "Service info callback registration failed: $errorCode.")
                    }

                    override fun onServiceInfoCallbackUnregistered() = Unit
                }
                serviceCallback = callback
                runCatching {
                    nsdManager.registerServiceInfoCallback(serviceInfo, callbackExecutor, callback)
                }.onFailure {
                    serviceCallback = null
                    Log.w(TAG, "Unable to register EasyConn service callback", it)
                    ProjectionEventLog.warning("DISCOVERY", "Unable to register NSD service info callback.", it)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                ProjectionEventLog.warning("DISCOVERY", "NSD service lost: ${serviceInfo?.serviceName}.")
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                ProjectionEventLog.debug("DISCOVERY", "Android NSD stopped for serviceType=$serviceType.")
            }

            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                ProjectionEventLog.error("DISCOVERY", "Android NSD start failed: type=$serviceType, code=$errorCode.")
                finish(Result.failure(IllegalStateException("Android NSD start failed: $errorCode")))
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.w(TAG, "Android NSD stop failed: $errorCode")
                ProjectionEventLog.warning("DISCOVERY", "Android NSD stop failed: code=$errorCode.")
            }
        }

        continuation.invokeOnCancellation { stopDiscovery() }
        runCatching {
            link.startNsdDiscovery(nsdManager, SERVICE_TYPE, callbackExecutor, listener)
        }.onFailure { finish(Result.failure(it)) }
    }

    private inner class SessionCallback(
        private val generation: Long
    ) : MobileCallback {
        override fun onError(message: String?, fatal: Boolean) {
            Log.w(TAG, "T-Box error fatal=$fatal: ${message.orEmpty()}")
            val detail = message.orEmpty().ifBlank { "EasyConn error without details." }
            if (!isCurrentRideDaemonSession(generation, activeSessionGeneration)) {
                Log.i(TAG, "Ignoring RideDaemon callback from an inactive session: $detail")
                ProjectionEventLog.debug("TBOX", "Ignored stale RideDaemon callback: $detail")
                return
            }
            if (fatal) {
                ProjectionEventLog.error("TBOX", "RideDaemon fatal callback: $detail")
            } else {
                ProjectionEventLog.warning("TBOX", "RideDaemon warning callback: $detail")
            }
            if (fatal) {
                mutableEvents.tryEmit(TBoxEvent.FatalError(detail))
            } else {
                mutableEvents.tryEmit(TBoxEvent.Warning(detail))
            }
        }

        override fun onEvent(time: Long, type: Long, command: Long, payload: ByteArray?) {
            // Both guarded: this runs for EVERY protocol event, touch moves included, and an
            // unconditional interpolated string here is paid whether or not anything reads it.
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "T-Box event type=$type command=$command bytes=${payload?.size ?: 0}")
            }
            if (type == TRANSPORT_EVENT_SOURCE) {
                // The daemon's own decisions, not dash traffic. Logged at INFO because a field
                // log must be able to say which video frame format was on the wire - a framing
                // experiment whose outcome only exists in the daemon's stdout cannot be read.
                if (command == TRANSPORT_VIDEO_PULLS_COMMAND) {
                    val phase = payload?.getOrNull(0)?.toInt() ?: -1
                    val pulls = decodeVideoPullCount(payload)
                    dashVideoPulls.set(pulls)
                    when (phase) {
                        VIDEO_PULL_SOCKET_OPEN -> {
                            dashVideoSocketOpened.set(true)
                            ProjectionEventLog.record(
                                "TBOX",
                                "The dashboard opened the video socket."
                            )
                        }
                        VIDEO_PULL_FIRST -> ProjectionEventLog.record(
                            "TBOX",
                            "The dashboard asked for its first video frame; it is consuming the " +
                                "stream. Anything wrong from here is what it does with the " +
                                "picture, not whether it is being sent one."
                        )
                        // Running totals only keep the counter fresh for [protocolSnapshot];
                        // logging every one of them would spend the rider's whole log ring on a
                        // number that is printed with every snapshot anyway.
                        VIDEO_PULL_PROGRESS -> Unit
                        VIDEO_PULL_SOCKET_CLOSED -> if (pulls == 0L) {
                            ProjectionEventLog.warning(
                                "TBOX",
                                "The dashboard opened the video socket and closed it without " +
                                    "ever asking for a single frame. Everything we send is being " +
                                    "queued and dropped on this side, so no video format, " +
                                    "bitrate or frame rate can change what the rider sees - the " +
                                    "dash is not reading the stream at all."
                            )
                        } else {
                            ProjectionEventLog.record(
                                "TBOX",
                                "The dashboard pulled $pulls video frames on this socket."
                            )
                        }
                    }
                    return
                }
                if (command == TRANSPORT_VIDEO_FRAMING_COMMAND) {
                    val extendByte = payload?.getOrNull(0)?.toInt() ?: -1
                    val plainApplied = payload?.getOrNull(1)?.toInt() == 1
                    ProjectionEventLog.record(
                        "TBOX",
                        "Video framing negotiated: dash supportExtendProtocol=$extendByte; " +
                            if (plainApplied) {
                                "frame index DROPPED (plain framing) for this session."
                            } else {
                                "frame index kept (indexed framing, the format every CFMOTO uses)."
                            }
                    )
                }
                return
            }
            val verbose = MotoHubSettings.verboseTBoxLogging(appContext)
            val now = SystemClock.elapsedRealtime()
            val sequence = when (type) {
                PXC_EVENT_SOURCE -> {
                    // The dash sends CLOCK_KEEPALIVE about every 2s. A long gap is the only
                    // warning that the link is dying: a rider log went silent for 16.6s and the
                    // T-Box then tore down all three sockets at once. Recording the gap is what
                    // separates "the bike gave up" from "the app stopped sending".
                    val previous = lastPxcEventElapsed.getAndSet(now)
                    if (previous > 0L && now - previous >= PXC_STALL_WARNING_MS) {
                        ProjectionEventLog.warning(
                            "TBOX",
                            "PXC keepalive gap of ${now - previous}ms before this event; the " +
                                "T-Box control link went quiet."
                        )
                    }
                    if (isStreamingPxcBeat(previous, lastFrameOfferedElapsed.get(), now)) {
                        pxcStreamingBeats.incrementAndGet()
                    }
                    pxcEvents.incrementAndGet()
                }
                MEDIA_CONTROL_EVENT_SOURCE -> {
                    lastMediaControlEventElapsed.set(now)
                    mediaControlEvents.incrementAndGet()
                }
                else -> 0L
            }
            if (type == PXC_EVENT_SOURCE || type == MEDIA_CONTROL_EVENT_SOURCE) {
                val commandName = protocolCommandName(type, command)
                // A dash's keepalive beats are folded into one line a minute (see
                // ProtocolBeatCollapser); everything else is written as it arrives, after any
                // open run of beats has been reported.
                val decision = beatCollapser.onEvent(type, commandName, payload?.size ?: 0, now)
                decision.rollup?.let { ProjectionEventLog.debug("TBOX", it) }
                // Lambda form: this is the single highest-volume log line in the app (one per
                // protocol event, and a drag on the TFT is a stream of them), so the string is
                // not built at all when logging is off.
                if (decision is BeatDecision.Write) {
                    ProjectionEventLog.debug("TBOX") {
                        "${protocolSourceName(type)} RX #$sequence command=" +
                            "0x${command.toString(16)} ($commandName) " +
                            "bytes=${payload?.size ?: 0}."
                    }
                }
                // Any control message that carries a body is worth dumping, named or not.
                // This used to fire only on UNKNOWN opcodes, which tied the evidence to the
                // gaps in the name table: naming a command would have silently switched off
                // the dump of the one payload we can actually read - the media CAPTURE_CONFIG,
                // whose bytes are how the video framing work was diagnosed. The control plane
                // is a handful of messages per session, so dumping all of them costs nothing.
                // With verbose logging every occurrence is dumped in full; without it, the
                // first occurrence of each distinct command is still dumped (truncated) so a
                // normal user's problem report already carries the evidence.
                if (payload != null && payload.isNotEmpty()) {
                    if (verbose) {
                        ProjectionEventLog.debug(
                            "TBOX",
                            "$commandName command 0x${command.toString(16)} payload (verbose): " +
                                payload.toDiagnosticHex() + "."
                        )
                    } else if (
                        unknownCommandsLogged.size < UNKNOWN_COMMAND_LOG_LIMIT &&
                        unknownCommandsLogged.add(type to command)
                    ) {
                        val preview = payload.copyOfRange(
                            0,
                            payload.size.coerceAtMost(UNKNOWN_COMMAND_PREVIEW_BYTES)
                        )
                        val truncated = if (payload.size > preview.size) "…(+${payload.size - preview.size}B)" else ""
                        ProjectionEventLog.record(
                            "TBOX",
                            "${protocolSourceName(type)} command 0x${command.toString(16)} " +
                                "($commandName) first seen; payload=${preview.toDiagnosticHex()}$truncated."
                        )
                    }
                }
            }
            if (type == PXC_EVENT_SOURCE && command == PXC_HUD_CONFIG_COMMAND) {
                val capabilities = payload?.let(::decodeTBoxCapabilities)
                if (capabilities == null) {
                    // An empty CLIENT_INFO and an unparseable one are different faults and lead
                    // to the same place - the GENERIC profile - so the log has to tell them
                    // apart. A Zontes dash sends this command with a zero-length body (field log
                    // 2026-07-30), and "unable to decode" sent us looking for a parser bug that
                    // was never there: there was simply nothing to parse.
                    if (payload == null || payload.isEmpty()) {
                        ProjectionEventLog.warning(
                            "TBOX",
                            "The T-Box announced CLIENT_INFO with an empty body; it reports no " +
                                "capabilities at all, so the generic dashboard profile applies."
                        )
                    } else {
                        ProjectionEventLog.warning(
                            "TBOX",
                            "Unable to decode the T-Box CLIENT_INFO payload (${payload.size} bytes); " +
                                "the generic dashboard profile applies."
                        )
                    }
                } else {
                    // Full raw CLIENT_INFO, not just the few fields TBoxCapabilities extracts -
                    // ProjectionEventLog.redact() strips password/pin-shaped fields (btPin) and,
                    // since verbose became the default, the stable hardware identifiers too
                    // (HUID/uuid) - that redaction is what made defaulting verbose on safe to
                    // do. The gate is now about volume, not identifiers: one JSON blob per
                    // handshake is fine, and a rider who turns verbose off still gets the
                    // whitelisted subset from the unrecognised-dashboard branch below.
                    if (verbose) {
                        val rawJson = payload.toString(Charsets.UTF_8).trim().trimEnd('\u0000')
                        ProjectionEventLog.debug("TBOX", "CLIENT_INFO raw (verbose): $rawJson")
                    }
                    ProjectionEventLog.record(
                        "TBOX",
                        "T-Box capabilities received: hu=${capabilities.huName ?: "not reported"}, " +
                            "pxc=${capabilities.pxcVersion ?: "not reported"}, " +
                            "touch=${capabilities.screenTouch ?: "not reported"}."
                    )
                    // Brand identity, always. Carbit licenses the same dashboard stack well beyond
                    // CFMOTO and its SDK pairs each manufacturer's flavor with the phone package
                    // name it expects the companion app to advertise, so a rebadged dash can
                    // complete the whole handshake and still refuse to project. Two short fields,
                    // and the first thing worth knowing about an unfamiliar dashboard.
                    ProjectionEventLog.record(
                        "TBOX",
                        "Dashboard identity: flavor=${capabilities.flavor ?: "not reported"}, " +
                            "channel=${capabilities.channel ?: "not reported"}, " +
                            "brand=${capabilities.carBrand ?: "not reported"}, " +
                            "model=${capabilities.carModel ?: "not reported"}, " +
                            "profile=${protocolProfile.key}."
                    )
                    // Nothing claimed this dashboard, so no profile knows its geometry, touch
                    // behaviour or firmware quirks - the one case a rider cannot diagnose from
                    // the outside. Report the whitelisted CLIENT_INFO subset and every candidate
                    // profile's score unconditionally, the same rule AndroidAutoSessionService
                    // already applies to the scores. Every field here is one TBoxCapabilities
                    // already keeps, so this adds no identifier the log did not carry before.
                    if (protocolProfile == TBoxModelProfile.GENERIC) {
                        ProjectionEventLog.record(
                            "TBOX",
                            "Unrecognised dashboard: package=${capabilities.packageName ?: "?"}, " +
                                "version=${capabilities.versionName ?: "?"}" +
                                "(${capabilities.versionCode ?: "?"}), " +
                                "sdk=${capabilities.sdkVersion ?: "?"}, " +
                                "dashSupportFunction=${capabilities.supportFunction ?: "?"}, " +
                                "socketTimeoutWifi=${capabilities.socketTimeoutPeriodWifi ?: "?"}ms, " +
                                "sockAuth=${capabilities.socketServerAuth ?: "?"}, " +
                                "dpi=${capabilities.dpi ?: "?"}, " +
                                "productType=${capabilities.productType ?: "?"}, " +
                                "screenType=${capabilities.screenType ?: "?"}, " +
                                "landscapeAdaptive=${capabilities.landscapeAdaptive ?: "?"}, " +
                                "mirrorOverlayTouch=${capabilities.mirrorOverlayTouch ?: "?"}."
                        )
                        ProjectionEventLog.record(
                            "TBOX",
                            "Profile scores: ${TBoxModelProfile.scoreBreakdown(capabilities)}."
                        )
                    }
                    motorcycleProfile?.let { motorcycle ->
                        // Kept HERE, not only in the session services that observe this event:
                        // when the companion app drives the session over the AIDL bridge none of
                        // them is running, and CLIENT_INFO - the only thing that can identify a
                        // dash whose QR carries no model id - was decoded and then dropped by
                        // both processes. This is where it arrives, so this is where it is kept;
                        // the observers' own write becomes a harmless second copy of the same
                        // snapshot.
                        TBoxCapabilityStore(appContext).recordCapabilities(motorcycle, capabilities)
                        TBoxWireLadder.onDashboardIdentified(appContext, motorcycle, protocolProfile, capabilities)
                    }
                    mutableEvents.tryEmit(TBoxEvent.Capabilities(capabilities))
                }
                return
            }
            if (type != MEDIA_CONTROL_EVENT_SOURCE) return
            if (command == MEDIA_STREAM_START_COMMAND) {
                ProjectionEventLog.record(
                    "TBOX",
                    "TFT video consumer is ready; requesting a fresh decoder sync frame."
                )
                mutableEvents.tryEmit(TBoxEvent.VideoStreamStart)
                return
            }
            val eventPayload = payload ?: return
            if (command == MEDIA_TOUCH_COMMAND) {
                decodeTBoxTouch(eventPayload)?.let(mutableEvents::tryEmit)
                return
            }
            if (command == MEDIA_CAPTURE_CONFIG_COMMAND) {
                describeTBoxCaptureRequest(eventPayload)?.let { fields ->
                    ProjectionEventLog.record("TBOX", "TFT capture request: $fields.")
                }
                decodeTBoxVideoArea(eventPayload)?.let { area ->
                    ProjectionEventLog.record(
                        "TBOX",
                        "TFT capture area requested: ${area.width}x${area.height}."
                    )
                    mutableEvents.tryEmit(area)
                }
                return
            }
            runCatching {
                val safeArea = org.json.JSONObject(eventPayload.toString(Charsets.UTF_8))
                    .optJSONObject("viewAreaConfig")
                    ?.optJSONArray("viewAreas")
                    ?.optJSONObject(0)
                    ?.optJSONObject("safeArea")
                    ?: return@runCatching
                val width = safeArea.optInt("width")
                val height = safeArea.optInt("height")
                if (width > 0 && height > 0) {
                    ProjectionEventLog.record("TBOX", "TFT safe area received: ${width}x$height.")
                    mutableEvents.tryEmit(TBoxEvent.VideoArea(width, height))
                }
            }.onFailure {
                Log.w(TAG, "Invalid EasyConn screen configuration", it)
                ProjectionEventLog.warning("TBOX", "Invalid EasyConn screen configuration payload.", it)
            }
        }

        override fun onStopped() {
            Log.i(TAG, "T-Box session stopped")
            if (!isCurrentRideDaemonSession(generation, activeSessionGeneration)) {
                ProjectionEventLog.debug("TBOX", "Ignored stale RideDaemon stopped callback.")
                return
            }
            ProjectionEventLog.warning(
                "TBOX",
                "RideDaemon reported that the T-Box session stopped. ${protocolSnapshot()}"
            )
            fileLadderVerdict(endedByDashboard = true)
            mutableEvents.tryEmit(TBoxEvent.Stopped)
        }

    }

    /**
     * Starts watching the PXC control link for silence.
     *
     * The gap check in [SessionCallback.onEvent] only fires when the *next* event arrives, which
     * makes it useless for the failure riders actually hit: the dash stops talking and never comes
     * back, so there is no next event to carry the warning. A Zontes dash (field log 2026-07-30)
     * sent its last heartbeat 3s into the session, stayed silent for 96s while we pushed 1857
     * frames at it, and only then closed the socket - and for that whole minute and a half the app
     * told the rider "streaming is active on the motorcycle TFT". That claim is what this timer
     * exists to stop making.
     *
     * [PXC_STALL_FATAL_MS] of silence ends the session as a failure rather than trying to recover
     * in place: the caller's own retry path re-runs discovery and the handshake, which is the only
     * thing that has ever brought one of these links back.
     *
     * The verdict is gated on [pxcStreamingBeats]: a CFDL16 field log (2026-07-31) proved some
     * dashes go PXC-silent right after the handshake *by design*, with the TFT happily displaying
     * video for 25 more minutes — the timer must never fire on those. Only a dash that first
     * demonstrated a streaming-time keepalive cadence ([PXC_STREAMING_CADENCE_MIN_BEATS] beats,
     * see [isStreamingPxcBeat]) has its later silence treated as death.
     */
    private fun armPxcWatchdog(generation: Long) {
        cancelPxcWatchdog()
        if (generation == 0L) return
        pxcWatchdogTask = runCatching {
            pxcWatchdogExecutor.scheduleWithFixedDelay(
                { checkPxcLiveness(generation) },
                PXC_WATCHDOG_INTERVAL_MS,
                PXC_WATCHDOG_INTERVAL_MS,
                TimeUnit.MILLISECONDS
            )
        }.getOrNull()
    }

    private fun cancelPxcWatchdog() {
        pxcWatchdogTask?.cancel(false)
        pxcWatchdogTask = null
    }

    private fun checkPxcLiveness(generation: Long) {
        // Generation-scoped: a tick that was already queued when the session was replaced must not
        // be able to kill its successor.
        if (!isCurrentRideDaemonSession(generation, activeSessionGeneration)) return
        if (session?.isRunning != true) return
        val now = SystemClock.elapsedRealtime()
        val lastFrame = lastFrameOfferedElapsed.get()
        if (lastFrame <= 0L || now - lastFrame > PXC_STALL_STREAMING_WINDOW_MS) return
        val lastPxc = lastPxcEventElapsed.get()
        // Never received anything: that is a handshake that did not complete, not a link that
        // died, and start() already reports it.
        if (lastPxc <= 0L) return
        val silence = now - lastPxc
        if (silence < PXC_STALL_FATAL_MS) return
        // Silence only means death on a dash that talks. A CFDL16 (field log 2026-07-31) sends
        // six PXC events in the first three seconds and then nothing, while its TFT keeps
        // displaying video for another 25 minutes — on that shape this timer used to kill every
        // healthy Android Auto session at the ~23s mark. Demand a demonstrated streaming-time
        // cadence before trusting its absence; a dash that never talked still gets caught by
        // socket errors and frame rejections when it actually dies.
        if (pxcStreamingBeats.get() < PXC_STREAMING_CADENCE_MIN_BEATS) {
            if (pxcQuietDashReported.compareAndSet(false, true)) {
                ProjectionEventLog.record(
                    "TBOX",
                    "PXC control link quiet for ${silence}ms while streaming, but this dash " +
                        "never kept a control-link cadence " +
                        "(streamingBeats=${pxcStreamingBeats.get()}); not treating silence as " +
                        "a fault. ${protocolSnapshot()}"
                )
            }
            return
        }
        if (!pxcStallReported.compareAndSet(false, true)) return
        ProjectionEventLog.error(
            "TBOX",
            "The T-Box control link has been silent for ${silence}ms while video was still being " +
                "sent; treating the session as dead. ${protocolSnapshot()}"
        )
        mutableEvents.tryEmit(
            TBoxEvent.FatalError(
                "The dash stopped responding while MOTO-HUB was still sending video. Put the bike " +
                    "on its phone-connection screen, make sure no other app is connected to the " +
                    "T-Box, and connect again."
            )
        )
    }

    private fun resetProtocolStats() {
        pxcEvents.set(0L)
        mediaControlEvents.set(0L)
        framesOffered.set(0L)
        framesTimedOut.set(0L)
        framesRejected.set(0L)
        lastPxcEventElapsed.set(0L)
        lastMediaControlEventElapsed.set(0L)
        lastFrameOfferedElapsed.set(0L)
        pxcStreamingBeats.set(0L)
        dashVideoPulls.set(0L)
        dashVideoSocketOpened.set(false)
        pxcStallReported.set(false)
        pxcQuietDashReported.set(false)
        unknownCommandsLogged.clear()
        // The tally belongs to the session that produced it: reported before it is dropped, so a
        // log does not end on beats that were counted and never mentioned, and so the next
        // session writes each beat command's first occurrence again rather than folding it into
        // a run the previous dash opened.
        beatCollapser.close(SystemClock.elapsedRealtime())
            ?.let { ProjectionEventLog.debug("TBOX", it) }
        beatCollapser.reset()
    }

    /**
     * Hands a finished session to [TBoxWireLadder], exactly once. Both ends of a session race to
     * report it - the dashboard's own close callback and our teardown - and the second one through
     * must not count as a second attempt.
     */
    private fun fileLadderVerdict(endedByDashboard: Boolean) {
        val motorcycle = motorcycleProfile ?: return
        val startedAt = sessionStartedElapsed.get()
        if (startedAt <= 0L) return
        if (!ladderVerdictFiled.compareAndSet(false, true)) return
        // Only Android Auto runs the format the ladder chose. Ride Dashboard sends its own, so a
        // mirroring session would otherwise promote or condemn a rung that never reached the wire
        // - a rider testing through the Ride Dashboard would have silently ended the search on a
        // format nobody tried.
        if (!TBoxSessionRegistry.everClaimed(ANDROID_AUTO_CONSUMER)) {
            TBoxWireLadder.onSessionIgnored(appContext, motorcycle, protocolProfile)
            return
        }
        TBoxWireLadder.onSessionFinished(
            context = appContext,
            motorcycle = motorcycle,
            modelProfile = protocolProfile,
            facts = TBoxSessionFacts(
                durationMillis = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L),
                mediaControlEvents = mediaControlEvents.get(),
                framesOffered = framesOffered.get(),
                frameTimeouts = framesTimedOut.get(),
                frameRejections = framesRejected.get(),
                endedByDashboard = endedByDashboard
            )
        )
    }

    private fun protocolSnapshot(): String {
        val now = SystemClock.elapsedRealtime()
        fun age(last: AtomicLong): String = last.get().takeIf { it > 0L }?.let {
            "${(now - it).coerceAtLeast(0L)}ms ago"
        } ?: "never"
        return "protocolStats=" +
            "pxcRx=${pxcEvents.get()} (last=${age(lastPxcEventElapsed)}, " +
            "streamingBeats=${pxcStreamingBeats.get()}), " +
            "mediaCtrlRx=${mediaControlEvents.get()} (last=${age(lastMediaControlEventElapsed)}), " +
            "framesOffered=${framesOffered.get()} (last=${age(lastFrameOfferedElapsed)}), " +
            "frameTimeouts=${framesTimedOut.get()}, frameRejections=${framesRejected.get()}, " +
            // Last on purpose: it is the only number here the dashboard produced, so it is the
            // one to read first when everything else looks healthy and the screen is black.
            "dashPulls=${dashVideoPulls.get()}" +
            if (dashVideoSocketOpened.get()) "" else " (the dash never opened the video socket)"
    }

    private companion object {
        /** AndroidAutoSessionService's own consumer name in TBoxSessionRegistry. */
        const val ANDROID_AUTO_CONSUMER = "android-auto"
        const val TAG = "RideDaemonTransport"
        const val SERVICE_TYPE = "_EasyConn._tcp."
        const val PACKAGE_ATTRIBUTE = "packagename"
        const val MODEL_ID_ATTRIBUTE = "modelid"
        const val SIMULATOR_MODEL_ID = MOTO_HUB_SIMULATOR_MODEL_ID
        const val IP_ATTRIBUTE = "ip"
        const val DISCOVERY_TIMEOUT_MS = 15_000L
        const val DISCOVERY_MAX_ATTEMPTS = 2
        const val DISCOVERY_RETRY_DELAY_MS = 500L
        const val EC_CONNECT_TIMEOUT_MS = 10_000

        /**
         * Wall clock the whole EasyConn handshake gets, retries included.
         *
         * Derived, not picked: one attempt that has already spent the entire native startup
         * budget leaves, by definition, nothing for a second. That keeps every cheap failure
         * retried exactly as before - three `connection refused` attempts cost ~2s, three
         * connect timeouts ~22s, both comfortably inside - while a dash that simply says nothing
         * fails once here, in the same 25s it always took, and is handed to the caller's
         * re-discover-and-retry instead of being dialled again on a session it never answered.
         */
        const val EC_HANDSHAKE_TOTAL_BUDGET_MS = RIDE_DAEMON_STARTUP_TIMEOUT_SEC * 1_000L
        // Wake-probe fallback (see sendEasyConnWakeProbe): well-known port and frame layout
        // reverse-engineered by OpenCfMoto/OpenMoto, not part of the advertised EasyConn contract.
        const val WAKE_PROBE_PORT = 10930
        const val WAKE_PROBE_ATTEMPTS = 3
        const val WAKE_PROBE_CONNECT_TIMEOUT_MS = 3_000
        const val WAKE_PROBE_READ_TIMEOUT_MS = 5_000
        const val WAKE_PROBE_RETRY_DELAY_MS = 1_000L
        // Fallback sweep for firmware that refuses 10930: the only ports any reference EasyConn
        // implementation documents (PXC 10920-10922, probe 10930) plus a narrow neighborhood in
        // case the whole block shifted (same range TBoxPortScanner uses for diagnostics).
        val FALLBACK_EC_PORTS: List<Int> = (10915..10935).filter { it != WAKE_PROBE_PORT }
        const val FALLBACK_PORT_CONNECT_TIMEOUT_MS = 800
        // A hosted subnet is a /24 in the worst case, so this multiplies by 253 - it has to stay
        // short. Everything on it is one Wi-Fi hop away with no router in between, so a dash that
        // is going to answer answers well inside this; the budget is for the silent addresses.
        const val HOSTED_SWEEP_CONNECT_TIMEOUT_MS = 250
        // Roughly every six seconds of a silent sweep, so a log that ends mid-sweep still says
        // how far it got, and one that ran to the end shows it moving rather than hung.
        const val HOSTED_SWEEP_PROGRESS_STRIDE = 24
        const val WAKE_PROBE_HEADER_SIZE = 16
        const val CMD_MDNS_RESPOND = 0x70000010
        const val CMD_MDNS_RESPOND_ACK = 0x70000011
        // The identity presented in the probe body - and, on a Wi-Fi Direct group where NSD has no
        // bindable Network to resolve a package from, the one recorded on the resulting TBoxHost -
        // is whichever candidate the dash acknowledged. See EasyConnClientIdentity.
        const val MEDIA_CONTROL_EVENT_SOURCE = 3L
        const val PXC_EVENT_SOURCE = 2L

        // Daemon-originated events (hud/core EventSourceTransport): the transport reporting
        // its own decisions, currently only the negotiated video frame format.
        const val TRANSPORT_EVENT_SOURCE = 4L
        const val TRANSPORT_VIDEO_FRAMING_COMMAND = 1L
        /** Payload: [phase, 8 bytes big-endian pull count]; phases below. */
        const val TRANSPORT_VIDEO_PULLS_COMMAND = 2L
        const val VIDEO_PULL_SOCKET_OPEN = 0
        const val VIDEO_PULL_FIRST = 1
        const val VIDEO_PULL_PROGRESS = 2
        const val VIDEO_PULL_SOCKET_CLOSED = 3
        /** Bounds for the always-on first-occurrence dump of unknown protocol commands. */
        const val UNKNOWN_COMMAND_LOG_LIMIT = 32
        const val UNKNOWN_COMMAND_PREVIEW_BYTES = 64
        const val PXC_HEARTBEAT_COMMAND = 0x70000000L
        const val PXC_HEARTBEAT_ACK_COMMAND = 0x70000001L
        const val PXC_CLOCK_KEEPALIVE_COMMAND = 0x10600L
        const val MEDIA_CONTROL_PING_COMMAND = 64L
        const val PXC_HUD_CONFIG_COMMAND = 65_552L
        const val MEDIA_CAPTURE_CONFIG_COMMAND = 16L
        const val MEDIA_TOUCH_COMMAND = 32L
        const val MEDIA_STREAM_START_COMMAND = 112L

        fun protocolSourceName(type: Long): String = when (type) {
            PXC_EVENT_SOURCE -> "PXC"
            MEDIA_CONTROL_EVENT_SOURCE -> "MEDIA_CONTROL"
            else -> "UNKNOWN"
        }

        /**
         * Opcodes named by the open-cflink/open-cfmoto reverse-engineering work
         * (refs/open-cflink PxcFrame.kt and PxcHandshake.kt). Naming them here
         * only changes what the log reads like, but a field log full of
         * "UNKNOWN" hides which of these a dash did and did not send - which is
         * exactly the question a T-Box investigation starts from.
         *
         * QUERY_SPEED is a trap worth keeping labelled: it carries
         * {usbSpeed, wifiSpeed}, the link rate, and has nothing to do with how
         * fast the motorcycle is going.
         */
        private val PXC_COMMAND_NAMES = mapOf(
            PXC_HEARTBEAT_COMMAND to "HEARTBEAT",
            PXC_HEARTBEAT_ACK_COMMAND to "HEARTBEAT_ACK",
            PXC_CLOCK_KEEPALIVE_COMMAND to "CLOCK_KEEPALIVE",
            0x10601L to "CLOCK_KEEPALIVE_ACK",
            PXC_HUD_CONFIG_COMMAND to "CLIENT_INFO",
            0x10011L to "CLIENT_INFO_RLY",
            0x10020L to "MEDIA_FEATURE_CFG",
            0x10690L to "QUERY_SPEED",
            0x103a0L to "OTA_FTP_INFO",
            0x103e0L to "CHECK_SN",
            0x10780L to "LOG_REPORT",
            // The periodic pair on an easyride-flavour dash: 113 of each in a four-minute rider
            // session (2026-08-02), both empty. They are that dash's keepalive beat, in the place
            // CLOCK_KEEPALIVE occupies on a CFMOTO unit - which is the point of naming them.
            // Nothing should be gated on a particular opcode being "the" keepalive: the same log
            // carries zero 0x10600, while a CFDL16 sends six PXC messages in total and then stops.
            0x10630L to "PERIODIC_NOTIFY",
            0x10430L to "PERIODIC_NOTIFY_ALT",
            // Seen twice each in the same session, both empty; named only so a field log stops
            // reading as a wall of UNKNOWN. open-cfmoto's notes list 0x10450 as empty too, and
            // 0x10040 as carrying {maxNaviIcon, supportFunction}.
            //
            // 0x10450 turned out to be the OTHER clock question, answered with JSON rather than
            // the binary stamp 0x10600 wants. A dash sends one or the other, never both: a Voge
            // log (DIRECT-VOGE-034672, 2026-08-02) has one 0x10450 right after the handshake and
            // zero 0x10600 across five days, which is why its clock was never set.
            0x10450L to "QUERY_TIME",
            0x10451L to "QUERY_TIME_ACK",
            0x104a0L to "NOTIFY_104A0",
            0x10040L to "NAVI_CAPS"
        )

        private val MEDIA_CONTROL_COMMAND_NAMES = mapOf(
            MEDIA_CONTROL_PING_COMMAND to "PING",
            MEDIA_STREAM_START_COMMAND to "STREAM_START",
            MEDIA_CAPTURE_CONFIG_COMMAND to "CAPTURE_CONFIG",
            MEDIA_TOUCH_COMMAND to "TOUCH"
        )

        fun protocolCommandName(type: Long, command: Long): String = when (type) {
            PXC_EVENT_SOURCE -> PXC_COMMAND_NAMES[command]
            MEDIA_CONTROL_EVENT_SOURCE -> MEDIA_CONTROL_COMMAND_NAMES[command]
            else -> null
        } ?: "UNKNOWN"
    }
}

/**
 * Whether a PXC event, arriving now, counts as a streaming-time keepalive beat (see
 * [PXC_STREAMING_CADENCE_MIN_BEATS]). Three conditions, each excluding a shape that must not
 * count: no frame offered yet excludes the handshake exchange; no previous event excludes the
 * very first message; a gap under [PXC_STREAMING_BEAT_MIN_GAP_MS] excludes the members of a
 * same-burst flurry, which prove one transmission, not a cadence.
 */
internal fun isStreamingPxcBeat(
    previousPxcEventElapsed: Long,
    lastFrameOfferedElapsed: Long,
    now: Long
): Boolean =
    lastFrameOfferedElapsed > 0L &&
        previousPxcEventElapsed > 0L &&
        now - previousPxcEventElapsed >= PXC_STREAMING_BEAT_MIN_GAP_MS

/**
 * The empty control-plane messages a dash repeats for as long as the link is up: its heartbeat
 * and whichever keepalive dialect it speaks (see the opcode names in PXC_COMMAND_NAMES). Matched
 * by name so this list stays readable, and so an opcode nobody has named yet is never folded -
 * an UNKNOWN arriving every two seconds is a finding, not noise.
 *
 * Commands that carry a body are absent on purpose, touch included: a drag is bounded by the
 * rider's finger, while these run for the whole ride.
 */
internal val PROTOCOL_BEAT_COMMAND_NAMES = setOf(
    "HEARTBEAT",
    "HEARTBEAT_ACK",
    "CLOCK_KEEPALIVE",
    "CLOCK_KEEPALIVE_ACK",
    "PERIODIC_NOTIFY",
    "PERIODIC_NOTIFY_ALT",
    "PING"
)

/** How long a run of folded beats may stay unreported. */
internal const val PROTOCOL_BEAT_ROLLUP_INTERVAL_MS = 60_000L

internal sealed interface BeatDecision {
    /** The line that reports a run of beats ending here, if one did; written before the event. */
    val rollup: String?

    /** Write this event's own line. */
    data class Write(override val rollup: String?) : BeatDecision

    /** Counted into the open run instead of written. */
    data class Fold(override val rollup: String?) : BeatDecision
}

/**
 * Folds a dash's repeating keepalive traffic into one line a minute.
 *
 * [RepeatCollapser] already folds consecutive identical lines, and cannot help here: these beats
 * interleave (HEARTBEAT_ACK, PERIODIC_NOTIFY, PERIODIC_NOTIFY_ALT, round again) and each line
 * carries its own sequence number, so no two in a row are ever equal. The result is a log that
 * holds only its last few minutes - a VOGE rider's report (support 0df154af, 2026-08-27) spent
 * all 1500 CORE entries on 8 minutes of beats, and the handlebar presses he was reporting had
 * long since fallen out of the ring. Every rider with logging on has this, verbose or not: these
 * lines are gated on the master switch alone.
 *
 * The first occurrence of each beat command is always written, so "did this dash ever send X"
 * stays answerable from the log; the rest become a tally. A run is closed - and its line
 * emitted - either when the interval elapses or when any other event arrives, so a rollup never
 * separates an event from the traffic that preceded it.
 *
 * Free of Android types so the rule can be unit tested, and synchronized because the transport
 * callback that drives it is not documented to be single-threaded (a field log shows PXC events
 * arriving out of sequence order).
 */
internal class ProtocolBeatCollapser(
    private val rollupIntervalMillis: Long = PROTOCOL_BEAT_ROLLUP_INTERVAL_MS
) {
    private val lock = Any()
    private val writtenOnce = mutableSetOf<Pair<Long, String>>()
    private val folded = LinkedHashMap<String, Int>()
    private var runStartedAt = 0L

    fun onEvent(type: Long, commandName: String, payloadSize: Int, now: Long): BeatDecision =
        synchronized(lock) {
            val isBeat = payloadSize == 0 && commandName in PROTOCOL_BEAT_COMMAND_NAMES
            if (!isBeat || writtenOnce.add(type to commandName)) {
                return BeatDecision.Write(closeRun(now))
            }
            if (runStartedAt == 0L) runStartedAt = now
            folded[commandName] = (folded[commandName] ?: 0) + 1
            val elapsed = now - runStartedAt
            return BeatDecision.Fold(if (elapsed >= rollupIntervalMillis) closeRun(now) else null)
        }

    /** Ends the open run, for a teardown that would otherwise drop its tally unreported. */
    fun close(now: Long): String? = synchronized(lock) { closeRun(now) }

    fun reset() = synchronized(lock) {
        writtenOnce.clear()
        folded.clear()
        runStartedAt = 0L
    }

    private fun closeRun(now: Long): String? {
        if (folded.isEmpty()) {
            runStartedAt = 0L
            return null
        }
        val seconds = ((now - runStartedAt).coerceAtLeast(0L) + 500L) / 1000L
        val total = folded.values.sum()
        val breakdown = folded.entries.joinToString(", ") { "${it.key} ×${it.value}" }
        folded.clear()
        runStartedAt = 0L
        return "$total keepalive beat${if (total == 1) "" else "s"} folded over ${seconds}s: " +
            "$breakdown. Each was empty and identical to the first of its kind, logged above."
    }
}

internal fun decodeEasyConnPackage(value: ByteArray?): String? = value
    ?.toString(Charsets.UTF_8)
    ?.trim()
    ?.takeIf(String::isNotBlank)

internal fun decodeTBoxVideoArea(payload: ByteArray): TBoxEvent.VideoArea? {
    if (payload.size < 4) return null
    val body = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
    val width = body.getShort(0).toInt() and 0xFFFF
    val height = body.getShort(2).toInt() and 0xFFFF
    return if (width > 0 && height > 0) TBoxEvent.VideoArea(width, height) else null
}

/**
 * Render the dash's REQ_RV_CONFIG_CAPTURE body for the log. Layout (little endian), from the
 * EasyConn reverse-engineering notes:
 *
 * ```
 * deviceWidth s16@0   deviceHeight s16@2   fps i32@4      wantEncoder i32@8
 * supportCodec i32@12 minQuality s16@16    maxQuality s16@18
 * bitRate i32@20      capScreenMode b@24   touchMode b@25 orientation b@26
 * displayId b@27      videoType b@28       supportExtendProtocol b@29
 * ```
 *
 * Only [decodeTBoxVideoArea] drives behaviour. Everything else is logged because the fields the
 * transport ignores are exactly the ones that differ on non-CFMOTO firmware, and a dash that
 * negotiates fine yet shows nothing can only be told apart from a working one here.
 */
internal fun describeTBoxCaptureRequest(payload: ByteArray): String? {
    if (payload.size < 4) return null
    val body = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
    fun u16(at: Int): Any = if (payload.size >= at + 2) body.getShort(at).toInt() and 0xFFFF else "?"
    fun i32(at: Int): Any = if (payload.size >= at + 4) body.getInt(at) else "?"
    fun u8(at: Int): Any = if (payload.size > at) payload[at].toInt() and 0xFF else "?"
    return "size=${payload.size}B, device=${u16(0)}x${u16(2)}, fps=${i32(4)}, " +
        "encoder=${i32(8)}, supportCodec=${i32(12)}, bitrate=${i32(20)}, " +
        "capScreenMode=${u8(24)}, touchMode=${u8(25)}, orientation=${u8(26)}, " +
        "videoType=${u8(28)}, supportExtendProtocol=${u8(29)}"
}

internal fun decodeTBoxTouch(payload: ByteArray): TBoxEvent.Touch? {
    if (payload.size < 8) return null
    val body = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
    val action = when (body.getShort(0).toInt() and 0xFFFF) {
        2 -> 0 // DOWN
        1 -> 1 // UP
        3 -> 2 // MOVE
        else -> return null
    }
    val x = body.getShort(2).toInt() and 0xFFFF
    val y = body.getShort(4).toInt() and 0xFFFF
    val pointerId = body.getShort(6).toInt() and 0xFFFF
    return TBoxEvent.Touch(action, pointerId, x, y)
}

// deriveTBoxPeerIpv4 + isSameIpv4Subnet moved to the shared src/main TBoxPeerAddress.kt (pure IP
// math, not GPL) so both flavors can use them.

internal fun parseIpv4Literal(value: String): String? {
    val octets = value.trim().split('.')
    if (octets.size != 4) return null
    val numbers = octets.map { part ->
        if (part.isEmpty() || part.any { !it.isDigit() }) return null
        part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
    }
    return numbers.joinToString(".")
}

internal fun parseUsableEasyConnIpv4Literal(value: String): String? {
    val literal = parseIpv4Literal(value) ?: return null
    val address = InetAddress.getByName(literal)
    return literal.takeIf { isUsableTBoxIpv4Address(address) }
}

internal fun isMotoHubSimulatorAdvertisement(serviceName: String?, modelId: String?): Boolean {
    val normalizedName = serviceName?.trim().orEmpty()
    val normalizedModelId = modelId?.trim().orEmpty()
	if (normalizedModelId == MOTO_HUB_SIMULATOR_MODEL_ID) return true
    if (normalizedName.startsWith("MOTO-HUB T-Box Simulator")) return true
    return normalizedModelId in setOf(
        "37416",
        "37426",
        "66660703",
        "66660721",
        "66660732",
        "66660742"
    ) && (
        normalizedName.startsWith("CFDL") ||
            normalizedName.startsWith("CFMOTO-") ||
            normalizedName.startsWith("800NK")
        )
}

/** Space-separated lowercase hex, e.g. "7b 0a 20 20" - only ever used behind verbose logging. */
private fun ByteArray.toDiagnosticHex(): String = joinToString(" ") { byte -> "%02x".format(byte) }

/**
 * Reads the daemon's 8-byte big-endian pull count, which follows the one-byte phase.
 *
 * Returns 0 for a payload that is too short rather than throwing: a truncated event must cost a
 * number in a log line, never a live projection session.
 */
internal fun decodeVideoPullCount(payload: ByteArray?): Long {
    if (payload == null || payload.size < 9) return 0L
    var value = 0L
    for (index in 1 until 9) {
        value = (value shl 8) or (payload[index].toLong() and 0xFF)
    }
    return value
}
