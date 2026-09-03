// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.pairing

import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.session.TBoxConnectionMode
import io.motohub.android.tbox.TBoxModelProfile
import io.motohub.android.tbox.ThinkerRideProtocol

/**
 * Keeps a hand-made profile's [MotorcycleProfile.modelId] in step with the connection mode the
 * rider picked, for the one mode that implies a whole protocol family.
 *
 * [TBoxConnectionMode.THINKERRIDE] is not just a way of joining the network - it selects the
 * ThinkerRide transport - but nothing downstream reads it to decide that.
 * [io.motohub.android.tbox.TBoxModelProfile.resolve] answers from the modelId and the rider's pin
 * alone, so a profile that says THINKERRIDE and carries no modelId still resolves to the generic
 * EasyConn profile and goes looking for an `_EasyConn._tcp.` advertisement no ThinkerRide dash
 * ever makes. Scanning the ThinkerRide QR never hit this because
 * [TBoxQrPayload.parseThinkerRideUrl] stamps the pseudo modelId alongside the mode; picking the
 * same dash from the manual-pairing chips stamped nothing, which made that menu entry a trap for
 * exactly the riders who need it - the ones whose rebadged unit shows an OEM QR, or no QR at all.
 *
 * Symmetrical on purpose. Moving the mode back off THINKERRIDE removes the pseudo id again, so a
 * rider correcting a wrong guess is not left with a profile that still claims to be a KOVE. Only
 * ever the pseudo id: a real modelId came from a dash or a code and is none of this function's
 * business.
 *
 * A profile that still has no modelId after that is given the one its network name earns, if
 * any ([TBoxModelProfile.modelIdForSsid]). The KOVE 625X is the case: its QR names no model,
 * so the `KY_ADV_…` SSID is the only thing that tells it apart from a generic EasyConn dash
 * before the first connect — and both the QR path and the typed path end up here.
 */
internal fun MotorcycleProfile.withModelIdForConnectionMode(): MotorcycleProfile {
    val known = modelId?.takeIf { it.isNotBlank() }
    val isThinkerRide = connectionMode == TBoxConnectionMode.THINKERRIDE
    return when {
        isThinkerRide && known == null -> copy(modelId = ThinkerRideProtocol.PROVISIONING_MODEL_ID)
        !isThinkerRide && known == ThinkerRideProtocol.PROVISIONING_MODEL_ID ->
            copy(modelId = null).withModelIdForSsid()
        known == null -> withModelIdForSsid()
        else -> this
    }
}

private fun MotorcycleProfile.withModelIdForSsid(): MotorcycleProfile =
    TBoxModelProfile.modelIdForSsid(ssid)?.let { copy(modelId = it) } ?: this
