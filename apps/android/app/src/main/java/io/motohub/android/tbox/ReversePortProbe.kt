// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import android.os.SystemClock
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicLong

/**
 * The three local ports every EasyConn client binds, and the one honest way to ask whether they
 * are free: try to bind them.
 *
 * This lives here, outside the transport, because the transport is not the only caller any more.
 * Android has no API that names the owner of a socket - `/proc/net` has been per-app since Q - so
 * "who is holding them" is unanswerable, but "are they held right now" costs three binds and is
 * the only thing the rider actually needs to be told. The UI asks before it starts a projection
 * (see [CompanionConflictGate]); the transport asks again immediately before handing them to Go.
 */
internal object ReversePortProbe {

    /** PXC control, media control and media stream, in the order RideDaemon binds them. */
    val PORTS = intArrayOf(10920, 10921, 10922)

    /**
     * What a hand-off is worth waiting for: a native session of ours that has just been stopped
     * releases its sockets asynchronously, and a rider log once showed them still held 10s later
     * with the very next attempt connecting normally.
     */
    const val HANDOFF_WAIT_MS = 12_000L

    /**
     * What a foreign holder is worth waiting for. Nothing, near enough: no MOTO-HUB session of
     * ours has stopped recently, so the sockets belong to another app, and Android gives us no
     * way to close them. Support case 36A3-FD37-1DD7 paid [HANDOFF_WAIT_MS] eight times in eight
     * minutes and the ports were never released once - 86s of a rider staring at a spinner for an
     * answer that was already known at the first probe.
     */
    const val FOREIGN_WAIT_MS = 1_000L

    /** How long after our own native stop a busy port can still plausibly be our own socket. */
    const val HANDOFF_WINDOW_MS = 15_000L

    const val POLL_MS = 400L

    private val lastNativeStopElapsed = AtomicLong(0L)

    /**
     * Called by the transport when it has asked the native session to stop - and only when that
     * session had actually been handed a socket. A handshake that never reached Go opened no
     * ports, so recording it here would hand the next attempt the patient wait for sockets that
     * were never ours, which is precisely the bug this class exists to end.
     */
    fun onNativeSessionStopped(atElapsed: Long = SystemClock.elapsedRealtime()) {
        lastNativeStopElapsed.set(atElapsed)
    }

    fun waitBudgetMs(nowElapsed: Long = SystemClock.elapsedRealtime()): Long =
        reversePortWaitMillis(nowElapsed, lastNativeStopElapsed.get())

    /** True when the budget in force is the patient one, for the log line that says why. */
    fun waitingOnOurOwnHandoff(nowElapsed: Long = SystemClock.elapsedRealtime()): Boolean =
        waitBudgetMs(nowElapsed) == HANDOFF_WAIT_MS

    /** Probes 10920-10922 exactly as the native reverse server will bind them. */
    fun busyPorts(): List<Int> {
        val probes = mutableListOf<ServerSocket>()
        val busy = mutableListOf<Int>()
        try {
            PORTS.forEach { port ->
                val probe = ServerSocket()
                try {
                    // SO_REUSEADDR before bind, like the Go listener: sockets the previous
                    // session left in TIME_WAIT are ours to reuse and must not read as a
                    // foreign conflict. A live listener in another process still fails here.
                    probe.reuseAddress = true
                    probe.bind(InetSocketAddress(port), 1)
                    probes += probe
                } catch (_: IOException) {
                    runCatching { probe.close() }
                    busy += port
                }
            }
        } finally {
            probes.forEach { runCatching { it.close() } }
        }
        return busy
    }
}

/**
 * How long a busy reverse port is worth waiting on.
 *
 * Pure so it can be tested without an Android runtime: the interesting part is the rule, and the
 * rule is one comparison that decides whether a rider waits 12 seconds or one.
 *
 * A clock that appears to run backwards keeps the patient budget. `elapsedRealtime` is monotonic,
 * so this cannot happen; being wrong in the patient direction costs a rider 11 seconds, being
 * wrong in the other direction tells them another app is at fault when it is us.
 */
internal fun reversePortWaitMillis(
    nowElapsed: Long,
    lastNativeStopElapsed: Long,
    handoffWindowMs: Long = ReversePortProbe.HANDOFF_WINDOW_MS,
    handoffWaitMs: Long = ReversePortProbe.HANDOFF_WAIT_MS,
    foreignWaitMs: Long = ReversePortProbe.FOREIGN_WAIT_MS
): Long {
    if (lastNativeStopElapsed <= 0L) return foreignWaitMs
    val sinceStop = nowElapsed - lastNativeStopElapsed
    if (sinceStop < 0L) return handoffWaitMs
    return if (sinceStop <= handoffWindowMs) handoffWaitMs else foreignWaitMs
}
