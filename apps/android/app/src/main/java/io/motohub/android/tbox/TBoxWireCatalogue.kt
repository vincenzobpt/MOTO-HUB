// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

/**
 * Wires that riders with the same dashboard have already confirmed, shipped with the app.
 *
 * [TBoxWireLadder] learns one motorcycle at a time and forgets that anyone else exists: every
 * rider who meets an unidentified dashboard walks the same rungs from scratch, and one wrong
 * answer moves them off a wire that works. Rider 738a2340 (2026-09-05) was asked the question
 * after a session whose Android Auto half had been failing on Wi-Fi, answered the only honest
 * thing - "the screen showed nothing" - and was moved from rung 0 onto rung 1, where his
 * dashboard renders a green screen. Two other riders on the *identical* dashboard fingerprint
 * had already confirmed rung 0 in the collector.
 *
 * So this table is the ladder's institutional memory. It is not a [TBoxModelProfile]: a profile
 * is somebody holding the hardware and measuring panel size, touch policy and Android Auto
 * preset, and inventing one from telemetry would be a guess wearing a measurement's clothes.
 * A rung is one bit of the wire and nothing else.
 *
 * **Entry rule, and it is not negotiable: at least two independent riders, on the same
 * fingerprint, with the same rung CONFIRMED in the collector.** One rider is an anecdote - the
 * very failure mode above. Rows are read off `wire_observations` with
 * `tools/diag-case.sh --sql`, and the comment on each entry says who they were, so the next
 * person can re-check the claim instead of trusting this file.
 */
object TBoxWireCatalogue {

    /**
     * Bumped whenever [CONFIRMED_RUNGS] changes what an existing rider should be on.
     *
     * A motorcycle carries the revision it was last seeded at, so one update re-seeds a bike
     * once and never again - the rider stays free to walk away from the catalogue afterwards.
     */
    const val REVISION = 1

    /**
     * Dashboard fingerprint (as [TBoxWireLadder.fingerprintOf] builds it) to the rung index its
     * riders confirmed.
     *
     * Every row here is rung 0 today, which is the default - so this table changes nothing for a
     * dashboard meeting MOTO-HUB for the first time. What it does is put back the riders whose
     * ladder walked away from a wire the community had already confirmed, and make the next
     * denial ask twice before it walks away again.
     */
    val CONFIRMED_RUNGS: Map<String, Int> = mapOf(
        // 4d8a4c5b and c639a558, both RIDER_CONFIRMED (2026-08-28 / 08-29). Read 2026-09-05.
        "EASYCONN_5G/49/66660005/5.0" to 0,
        // f014ce61 and 78c9a48d, both RIDER_CONFIRMED on a Voge (2026-09-01 / 09-03).
        "51/37504/V0.0.1" to 0,
        // 0df154af and 94b0a3da, both RIDER_CONFIRMED (2026-08-30 / 09-02).
        "SSDQ01/51/37501/1.0.0" to 0
    )

    /** The confirmed rung for this dashboard, or null when nobody has agreed on one yet. */
    fun rungFor(fingerprint: String?): Int? = fingerprint?.let { CONFIRMED_RUNGS[it] }
}
