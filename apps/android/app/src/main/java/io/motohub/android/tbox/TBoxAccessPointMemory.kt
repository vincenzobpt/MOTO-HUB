// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import android.content.Context
import io.motohub.android.session.TBoxConnectionMode

/**
 * What this phone has learned, across sessions, about a motorcycle having an access point of its
 * own - and how many joins have failed since the last one that worked.
 *
 * Exists for the offer in [io.motohub.android.feature.home.HubViewModel]: a failed access-point
 * join used to be enough, on its own, to propose "My phone hosts the hotspot" to the rider. That
 * offer is a guess made under uncertainty, and saving it is a one-way door - see
 * [TBoxLinkResolver.accessPointFallback] for the long way back out. This is what removes the
 * uncertainty.
 *
 * Keyed by SSID rather than profile id: a rider who deletes and re-pairs the same motorcycle is
 * the rider most likely to be mid-struggle with it, and the dash's SSID is what stayed the same.
 */
internal class TBoxAccessPointMemory(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /**
     * Records that this dash was reached on its own access point. Permanent, and deliberately so:
     * a dash that has an access point does not become a Wi-Fi client later, so nothing this phone
     * sees afterwards should be allowed to argue that it did.
     */
    fun rememberReached(ssid: String) {
        preferences.edit()
            .putBoolean(REACHED_PREFIX + ssid, true)
            .remove(FAILURES_PREFIX + ssid)
            .apply()
    }

    fun hasBeenReached(ssid: String): Boolean = preferences.getBoolean(REACHED_PREFIX + ssid, false)

    /** Counts this failed join and returns how many have now failed in a row. */
    fun noteFailedJoin(ssid: String): Int {
        val failures = preferences.getInt(FAILURES_PREFIX + ssid, 0) + 1
        preferences.edit().putInt(FAILURES_PREFIX + ssid, failures).apply()
        return failures
    }

    companion object {
        private const val PREFERENCES = "tbox_access_point_memory"
        private const val REACHED_PREFIX = "reached_"
        private const val FAILURES_PREFIX = "failures_"
    }
}

/**
 * Whether a failed access-point join should offer the rider "My phone hosts the hotspot".
 *
 * The offer's own reasoning is sound as far as it goes: a dash that is itself a Wi-Fi client has
 * no access point to join, so a join that never associates is the only failure it can produce -
 * and that failure is indistinguishable from a dash that is simply off. What was missing is that
 * the app usually knows which of the two it is looking at, and used to ask anyway.
 *
 * Case 94e45e62 is the cost of asking anyway. A Zontes 125X joined ZT663590's access point in
 * 4796ms on 2026-09-08. On 2026-09-09 two joins failed while the dash was missing from a
 * truncated scan list, the offer appeared both times, the rider took it - and every connect for
 * the next three days went looking for a hotspot that was never going to exist, on a motorcycle
 * that had been broadcasting at -46dBm the whole time.
 *
 * @param reachedBefore this phone has joined that dash's own access point at some point in the
 *   past. Decisive: a motorcycle with an access point is not a Wi-Fi client, whatever today's
 *   join did.
 * @param broadcastingNow the scan's answer at the moment of the failure, with null for "no usable
 *   scan". Decisive when positive, for the same reason - and worth nothing otherwise, which is
 *   exactly the mistake this rule exists to stop making.
 * @param consecutiveFailures failed joins since the last success, this one included. With no
 *   proof either way the offer is still the right help to give; [OFFERS_AFTER_FAILED_JOINS] is
 *   just how long the app waits before guessing out loud.
 */
internal fun shouldOfferPhoneHotspot(
    mode: TBoxConnectionMode,
    reachedBefore: Boolean,
    broadcastingNow: Boolean?,
    consecutiveFailures: Int
): Boolean = when {
    // Already there; there is nothing to offer.
    mode == TBoxConnectionMode.PHONE_HOTSPOT -> false
    // A P2P Group Owner hosts the network; a phone-hotspot dash joins one. Opposite topologies,
    // and this mode is set by a code that said so ([TBoxQrTopology]), so the offer would
    // contradict the dash's own claim. The QJ rider of field log 6b345de4 said exactly that back
    // to us: "non e' il modo in cui posso connettere la moto".
    mode == TBoxConnectionMode.WIFI_DIRECT -> false
    // The two proofs. Either one settles the question the offer is guessing at.
    reachedBefore -> false
    broadcastingNow == true -> false
    // Genuinely unknown, so it comes down to how much of a pattern this is. Two, matching
    // [TBoxLinkResolver.AP_FALLBACKS_BEFORE_REWRITE] on the way back out: the app should need as
    // much evidence to push a rider through this door as it needs to pull them back out.
    else -> consecutiveFailures >= OFFERS_AFTER_FAILED_JOINS
}

/** Consecutive failed access-point joins before the phone-hotspot mode is offered at all. */
internal const val OFFERS_AFTER_FAILED_JOINS = 2
