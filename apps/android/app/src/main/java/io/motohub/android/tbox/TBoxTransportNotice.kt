// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the transport wants the rider told while a connection is being established: a step on the
 * dash that nothing on the phone can do for them. Null means nothing to add to the phase itself.
 */
object TBoxTransportNotice {
    private val mutableLatest = MutableStateFlow<String?>(null)
    val latest: StateFlow<String?> = mutableLatest.asStateFlow()

    fun publish(message: String) {
        mutableLatest.value = message.takeIf { it.isNotBlank() }
    }

    fun clear() {
        mutableLatest.value = null
    }
}
