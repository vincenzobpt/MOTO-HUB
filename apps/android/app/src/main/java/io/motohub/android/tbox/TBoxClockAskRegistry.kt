// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import android.content.Context

/**
 * Which dashboards have been seen asking for the time, remembered across sessions.
 *
 * The daemon pushes an unsolicited clock JSON to a dash that never sends `QUERY_TIME` (0x10450),
 * waiting a grace period first so a dash that does ask is never handed a second packet. That wait
 * is a race, and it was measured losing: a VOGE-040785 log (2026-09-09) has the same dashboard
 * asking at +1.964s through +2.465s after CLIENT_INFO across seven handshakes, so six of the seven
 * beat the two-second window by 78-465 ms and received the push on top of the answer they had
 * asked for. A wider window is a better bet, not a decision - how long a handshake takes is a
 * property of the phone and the link, not of the firmware, so any fixed number eventually loses.
 *
 * Whether a dash asks at all *is* a property of the firmware: seen once, it holds. A session
 * cannot know it, because a session only sees itself; this is the memory that lets the next one
 * skip the push entirely.
 *
 * Keyed by dashboard fingerprint ([TBoxWireLadder.fingerprintOf]), not by motorcycle: the same
 * firmware behaves the same way on every bike carrying it, and a rider who re-pairs or has a dash
 * replaced under warranty must not inherit a verdict reached against different firmware. That is
 * the same key and the same reasoning as [TBoxWireCatalogue].
 *
 * One-way on purpose. A dash that asked once and stays quiet in a later session has not stopped
 * asking - far likelier the handshake died before it got there - and forgetting on that evidence
 * would put the race straight back. The only cost of a stale entry is that a dashboard which
 * genuinely stopped asking would no longer be pushed to; it is still answered whenever it asks.
 */
object TBoxClockAskRegistry {
    private const val PREFERENCES_NAME = "tbox_clock_ask"
    private const val KEY_FINGERPRINTS = "fingerprints"

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** True when this dashboard has sent QUERY_TIME in some earlier session. */
    fun asksForTime(context: Context, fingerprint: String?): Boolean =
        asksForTime(stored(context), fingerprint)

    /**
     * Records that this dashboard asked. Returns true the first time, so the caller can say so in
     * the log once per dashboard instead of once per session.
     */
    fun recordAsked(context: Context, fingerprint: String?): Boolean {
        val updated = withFingerprint(stored(context), fingerprint) ?: return false
        preferences(context).edit().putStringSet(KEY_FINGERPRINTS, updated).apply()
        return true
    }

    /**
     * The decision, without a [Context] so it can be tested. A dashboard whose CLIENT_INFO carried
     * none of the fields the fingerprint is built from reads as null here, and null must never
     * match: one such dash would otherwise silence the push for every other unidentified dash.
     */
    fun asksForTime(known: Set<String>, fingerprint: String?): Boolean =
        !fingerprint.isNullOrBlank() && fingerprint in known

    /**
     * The set to store, or null when there is nothing to write - an unusable fingerprint, or one
     * already known. Returning null rather than an unchanged set is what keeps the caller's log
     * line to once per dashboard instead of once per session.
     */
    fun withFingerprint(known: Set<String>, fingerprint: String?): Set<String>? {
        if (fingerprint.isNullOrBlank()) return null
        if (fingerprint in known) return null
        return known + fingerprint
    }

    // getStringSet hands back the live set it holds; mutating it is documented as undefined, so
    // every read is copied before it leaves this file.
    private fun stored(context: Context): Set<String> =
        preferences(context).getStringSet(KEY_FINGERPRINTS, emptySet())?.toSet() ?: emptySet()
}
