// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import io.motohub.android.session.MotorcycleProfile

/**
 * The one thing about a KOVE dashboard the app has to ask the rider.
 *
 * Every ThinkerRide dash answers the same QR, broadcasts the same `CQKY_` network and speaks the
 * same wire, and the wire never reports a panel size: the phone declares it. So nothing tells an
 * 800X (600x1024 portrait) from a 450 Rally (1280x640 landscape) before the picture is already on
 * the wrong panel. Unpinned, the resolver picks [TBoxModelProfile.KOVE_800X] - and case 74149ea9
 * (2026-09-14) is a 450 Rally that lost its pin the moment the rider deleted and re-added the bike.
 *
 * Asked on Connect rather than at pairing because that is the one step every road into a KOVE
 * profile goes through: the QR, the typed credentials, and a garage saved by an older version
 * that the `CQKY_` prefix now recognises on load.
 */
object KoveModelChoice {

    /** What the rider is offered, in the order the dialog shows it. */
    val options: List<ProfileOverride> = listOf(
        ProfileOverride.KOVE_450_RALLY,
        ProfileOverride.KOVE_800X
    )

    /**
     * True when [profile] is about to run the ThinkerRide transport on a panel size nobody chose.
     * A pin of any kind answers it, including GENERIC: that rider has already told the app what
     * they want.
     */
    fun isNeeded(profile: MotorcycleProfile): Boolean {
        val override = ProfileOverride.byKey(profile.profileOverrideKey)
        if (override != ProfileOverride.AUTO) return false
        return TBoxModelProfile.resolve(profile.modelId, null, override).transportFamily ==
            TBoxTransportFamily.THINKERRIDE
    }
}
