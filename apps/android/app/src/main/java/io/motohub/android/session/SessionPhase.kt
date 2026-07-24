package io.motohub.android.session

import io.motohub.android.i18n.motoHubText
import java.util.UUID

/** Explicit transport selected for the motorcycle T-Box Wi-Fi network. */
enum class TBoxConnectionMode {
    /** Infer P2P from a DIRECT- SSID; otherwise request the dashboard access point. */
    AUTO,
    /** Always use Android's regular Wi-Fi access-point request. */
    ACCESS_POINT,
    /** Always join through Wi-Fi Direct (P2P), including dashes acting as Group Owners. */
    WIFI_DIRECT
}

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
    val modelId: String? = null,
    val displayName: String? = null,
    val photoPath: String? = null,
    val fuelTankRangeKm: Double? = null,
    val profileOverrideKey: String? = null,
    val connectionMode: TBoxConnectionMode = TBoxConnectionMode.AUTO
)

data class HubSessionState(
    val phase: SessionPhase = SessionPhase.SETUP_REQUIRED,
    val motorcycle: MotorcycleProfile? = null,
    val message: String = motoHubText("Set up the motorcycle network to get started.")
)

fun HubSessionState.withMotorcycle(profile: MotorcycleProfile): HubSessionState = copy(
    phase = SessionPhase.NETWORK_SETUP_REQUIRED,
    motorcycle = profile,
    message = motoHubText("Profile saved. Connect to the T-Box network and start discovery.")
)
