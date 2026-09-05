// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.session

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicReference

/**
 * Whether the projection stream was in trouble during a session, for the one caller that has to
 * know: `TBoxWireLadder`, which asks the rider "did the dashboard show it?" and reads a "no" as a
 * verdict on the video format.
 *
 * That question only means something when the phone was actually sending a live picture. Rider
 * 738a2340's log (2026-09-05) is the counter-example: the dashboard's Wi-Fi kept going away, two
 * auto-recoveries burned their full 120s, the Android Auto decoder sat stalled for 20-45s at a
 * time - and the ladder still filed a STREAMED verdict, asked the question, and took the honest
 * "nothing on screen" as proof against a wire that two other riders with the same dashboard had
 * confirmed. A session like that says nothing about the bytes.
 *
 * Only *failed* recovery is recorded, not every recovery: one attempt that succeeds immediately
 * is a hiccup the rider never saw, and treating it as trouble would stall the search on links
 * that are merely imperfect.
 *
 * Deliberately a marker and not a counter: the ladder asks one question - "was there trouble
 * inside this session's window?" - and [SystemClock.elapsedRealtime] is the clock the transport
 * already times sessions with.
 */
object ProjectionSourceHealth {

    private val lastTrouble = AtomicReference<Trouble?>(null)

    private data class Trouble(val atElapsedRealtime: Long, val reason: String)

    /** A recovery attempt failed, or recovery gave up: the stream was not delivering a picture. */
    fun noteTrouble(reason: String) {
        lastTrouble.set(Trouble(SystemClock.elapsedRealtime(), reason))
    }

    /**
     * Why this session was not a clean run, or null when nothing went wrong inside it.
     *
     * [sessionStartedElapsedRealtime] is the session's own start on the same clock; trouble from
     * an earlier session is not this session's problem.
     */
    fun troubleSince(sessionStartedElapsedRealtime: Long): String? =
        lastTrouble.get()
            ?.takeIf { it.atElapsedRealtime >= sessionStartedElapsedRealtime }
            ?.reason

    /** Test seam; production never needs to forget. */
    internal fun reset() = lastTrouble.set(null)
}
