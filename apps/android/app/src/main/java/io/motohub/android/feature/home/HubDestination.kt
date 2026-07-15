package io.motohub.android.feature.home

import io.motohub.android.session.HubSessionState
import io.motohub.android.session.SessionPhase

internal enum class HubDestination {
    PAIRING,
    CONNECTING,
    CONNECTION,
    MODE_SELECTION,
    ACTIVE_SESSION
}

internal fun resolveHubDestination(
    session: HubSessionState,
    androidAutoActive: Boolean
): HubDestination = when {
    session.motorcycle == null -> HubDestination.PAIRING
    session.phase == SessionPhase.CONNECTING_NETWORK ||
        session.phase == SessionPhase.DISCOVERING_TBOX -> HubDestination.CONNECTING
    androidAutoActive ||
        session.phase == SessionPhase.REQUESTING_PROJECTION ||
        session.phase == SessionPhase.CAPTURING -> HubDestination.ACTIVE_SESSION
    session.phase == SessionPhase.READY -> HubDestination.MODE_SELECTION
    else -> HubDestination.CONNECTION
}
