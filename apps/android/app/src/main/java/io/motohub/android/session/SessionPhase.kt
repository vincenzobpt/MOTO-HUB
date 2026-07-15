package io.motohub.android.session

import java.util.UUID

enum class SessionPhase {
    SETUP_REQUIRED,
    CONNECTING_NETWORK,
    DISCOVERING_TBOX,
    READY,
    NETWORK_SETUP_REQUIRED,
    REQUESTING_PROJECTION,
    CAPTURING,
    ERROR
}

data class MotorcycleProfile(
    val ssid: String,
    val password: String,
    val id: String = UUID.randomUUID().toString(),
    // Raw identifier supplied by the pairing QR; not a motorcycle model name.
    val modelId: String? = null,
    val displayName: String? = null,
    val photoPath: String? = null
)

data class HubSessionState(
    val phase: SessionPhase = SessionPhase.SETUP_REQUIRED,
    val motorcycle: MotorcycleProfile? = null,
    val message: String = "Set up the motorcycle network to get started."
)

fun HubSessionState.withMotorcycle(profile: MotorcycleProfile): HubSessionState = copy(
    phase = SessionPhase.NETWORK_SETUP_REQUIRED,
    motorcycle = profile,
    message = "Profile saved. Connect to the T-Box network and start discovery."
)
