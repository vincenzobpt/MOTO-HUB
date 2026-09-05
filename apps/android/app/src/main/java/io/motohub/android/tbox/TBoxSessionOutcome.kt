// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

/** The protocol facts a finished session leaves behind, as [TBoxWireLadder] needs to read them. */
data class TBoxSessionFacts(
    val durationMillis: Long,
    val mediaControlEvents: Long,
    val framesOffered: Long,
    val frameTimeouts: Long,
    val frameRejections: Long,
    /** True when the dashboard ended the session; false when MOTO-HUB or the rider did. */
    val endedByDashboard: Boolean,
    /**
     * Why the projection stream was not delivering a picture for part of this session, or null
     * when it ran clean. See [io.motohub.android.session.ProjectionSourceHealth]: frames kept
     * flowing to the dashboard while the source behind them was stalled or reconnecting, so what
     * the rider saw - or did not - is not evidence about the wire.
     */
    val trouble: String? = null
)

/**
 * What a finished session says about the wire format it ran on.
 *
 * Deliberately conservative about blame. Only [REJECTED] is evidence *against* the wire; everything
 * else either says nothing ([INCONCLUSIVE], [NEVER_NEGOTIATED]) or says the dashboard was content
 * with the bytes ([STREAMED]) - which, on the failure this whole ladder exists for, is not the same
 * as saying the rider could see anything.
 */
enum class TBoxSessionOutcome {
    /**
     * The dashboard never asked for video: no MEDIA_CONTROL at all. Observed on a Zontes 368G whose
     * EasyConn side stayed wedged after an earlier failure and answered nothing until the ignition
     * was cycled. Says nothing about the frame format - the format never got used - so it must not
     * move the ladder, or a wedged dash would walk the whole thing in an afternoon.
     */
    NEVER_NEGOTIATED,

    /**
     * The dashboard accepted frames and then closed the session itself, early. This is the one
     * shape that indicts the wire: a Zontes 368G on indexed framing dropped the video socket at 6s
     * and 17s where the plain stream had held its full 30s timeout.
     */
    REJECTED,

    /**
     * Frames flowed for long enough that the firmware was clearly willing to keep taking them.
     * NOT proof the rider saw anything: the same dash streamed 3900 frames over four minutes with
     * its panel still showing the pairing QR. [TBoxWireLadder] treats this as a candidate awaiting
     * the one question the protocol cannot answer.
     */
    STREAMED,

    /** Too short, ended by us, or run on a stream that was not healthy, to read anything into. */
    INCONCLUSIVE;

    companion object {
        /** Under this, a dashboard-initiated stop is a rejection rather than a normal end of ride. */
        const val REJECTION_CEILING_MILLIS = 25_000L

        /** Over this with frames flowing, the firmware is taking the stream rather than tolerating it. */
        const val STREAMED_FLOOR_MILLIS = 45_000L

        fun of(facts: TBoxSessionFacts): TBoxSessionOutcome = when {
            facts.mediaControlEvents == 0L -> NEVER_NEGOTIATED
            facts.framesOffered == 0L -> INCONCLUSIVE
            facts.endedByDashboard && facts.durationMillis < REJECTION_CEILING_MILLIS -> REJECTED
            // Trouble does not excuse a rejection - the dashboard closed a socket over bytes it
            // had already received, and stalled content is still valid H.264 - but it does stop
            // the rider being asked. STREAMED is the only outcome that leads to that question.
            facts.trouble != null -> INCONCLUSIVE
            facts.durationMillis >= STREAMED_FLOOR_MILLIS -> STREAMED
            else -> INCONCLUSIVE
        }
    }
}
