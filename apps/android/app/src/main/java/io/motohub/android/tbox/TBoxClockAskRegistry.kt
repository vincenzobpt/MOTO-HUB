// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import android.content.Context
import org.json.JSONObject

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
    private fun stored(context: Context, key: String = KEY_FINGERPRINTS): Set<String> =
        preferences(context).getStringSet(key, emptySet())?.toSet() ?: emptySet()

    // --- Dashboards that throw the answer away ---------------------------------------------

    private const val KEY_DISCARDING = "discarding"
    private const val KEY_PROBE_PREFIX = "probe:"

    /**
     * Above this, `currentHUTime` is a date and not a counter. 1e11 ms is March 1973, so any real
     * wall clock clears it by decades while a dash that has been up for a day reports ~8.6e7. The
     * same number, for the same reason, gates the unsolicited push inside the daemon.
     */
    internal const val UPTIME_CEILING_MILLIS = 100_000_000_000L

    /**
     * What a dashboard said its clock was at one CLIENT_INFO, and whether MOTO-HUB answered a
     * QUERY_TIME after that point. Two of these from the same power cycle are the whole verdict.
     */
    internal data class ClockProbe(
        val uptimeMillis: Long,
        val seenAtMillis: Long,
        val answered: Boolean
    )

    /** True when this dashboard has been caught discarding the time it asked for. */
    fun discardsTime(context: Context, fingerprint: String?): Boolean =
        !fingerprint.isNullOrBlank() && fingerprint in stored(context, KEY_DISCARDING)

    /**
     * Records that the daemon answered this dashboard's QUERY_TIME. Call it only when the answer
     * actually carried a clock: with the rider's Wi-Fi clock switch off the reply goes out empty,
     * and a dash that still reports a counter afterwards has discarded nothing.
     */
    fun noteAnswered(context: Context, fingerprint: String?) {
        val probe = probe(context, fingerprint) ?: return
        if (probe.answered) return
        saveProbe(context, fingerprint, probe.copy(answered = true))
    }

    /**
     * Reads this handshake's `currentHUTime` and files the verdict. Returns true the first time a
     * dashboard earns it, so the caller can say so in the log once per firmware.
     *
     * The rule is deliberately narrow, because the obvious one is wrong. "It still reports a
     * counter after we answered" also describes a dash that took the clock and was then switched
     * off, which is most of them: a cluster with no RTC loses the time with the ignition and is
     * back to counting the next morning through no fault of ours. So the two readings must come
     * from the SAME power cycle - the uptime has to have grown by about the wall-clock time that
     * passed between them - and only then does a counter mean the answer was thrown away.
     *
     * Measured on a Cyclone RX2 (51/37516/V0.0.1, support id C1FD-A8CF-9389, 2026-09-11): asks at
     * 00:16:52, is answered with `11.09.2026 00:16:52` local time, and at the next CLIENT_INFO
     * 85 seconds later reports `currentHUTime` 161474 against the previous 74626 - grown by
     * exactly the elapsed time, so no reboot, and still a counter.
     */
    fun onDashboardClockSeen(
        context: Context,
        fingerprint: String?,
        uptimeMillis: Long?,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (fingerprint.isNullOrBlank() || uptimeMillis == null) return false
        val discarded = discardedTheAnswer(probe(context, fingerprint), uptimeMillis, nowMillis)
        saveProbe(context, fingerprint, ClockProbe(uptimeMillis, nowMillis, answered = false))
        if (!discarded) return false
        val updated = withFingerprint(stored(context, KEY_DISCARDING), fingerprint) ?: return false
        preferences(context).edit().putStringSet(KEY_DISCARDING, updated).apply()
        return true
    }

    /**
     * The decision, without a [Context] so it can be checked against field logs. True only when
     * the previous reading was answered, this one is still a counter, and the uptime between them
     * grew by at least the elapsed wall time - [TBoxWireLadder.REBOOT_UPTIME_SLACK_MILLIS] of
     * slack for the two clocks disagreeing. Anything else is a dashboard that restarted in
     * between, and a restart explains the counter without any help from us.
     */
    internal fun discardedTheAnswer(
        previous: ClockProbe?,
        uptimeMillis: Long,
        nowMillis: Long
    ): Boolean {
        if (previous == null || !previous.answered) return false
        if (!looksLikeCounter(uptimeMillis)) return false
        val growth = uptimeMillis - previous.uptimeMillis
        if (growth <= 0) return false
        val elapsed = nowMillis - previous.seenAtMillis
        // A phone clock that moved backwards between the two readings makes the comparison
        // meaningless; no verdict is better than one drawn from an hour that never passed.
        if (elapsed < 0) return false
        return growth >= elapsed - TBoxWireLadder.REBOOT_UPTIME_SLACK_MILLIS
    }

    /** Whether this `currentHUTime` is a counter rather than a date. */
    internal fun looksLikeCounter(uptimeMillis: Long): Boolean =
        uptimeMillis in 0 until UPTIME_CEILING_MILLIS

    private fun probe(context: Context, fingerprint: String?): ClockProbe? {
        if (fingerprint.isNullOrBlank()) return null
        val raw = preferences(context).getString(KEY_PROBE_PREFIX + fingerprint, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            ClockProbe(
                uptimeMillis = json.getLong("up"),
                seenAtMillis = json.getLong("at"),
                answered = json.optBoolean("ans")
            )
        }.getOrNull()
    }

    private fun saveProbe(context: Context, fingerprint: String?, probe: ClockProbe) {
        if (fingerprint.isNullOrBlank()) return
        val json = JSONObject()
            .put("up", probe.uptimeMillis)
            .put("at", probe.seenAtMillis)
            .put("ans", probe.answered)
        preferences(context).edit().putString(KEY_PROBE_PREFIX + fingerprint, json.toString()).apply()
    }
}
