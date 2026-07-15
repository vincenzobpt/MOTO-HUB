package io.motohub.android.feature.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.motohub.android.data.MotorcycleProfileStore
import io.motohub.android.session.HubSessionState
import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.session.SessionPhase
import io.motohub.android.session.ProjectionRuntime
import io.motohub.android.session.ProjectionRuntimeState
import io.motohub.android.session.ProjectionEventLog
import io.motohub.android.session.withMotorcycle
import io.motohub.android.feature.pairing.TBoxQrPayload
import io.motohub.android.tbox.RideDaemonTransport
import io.motohub.android.tbox.TBoxNetworkConnector
import io.motohub.android.tbox.TBoxNetworkEvent
import io.motohub.android.tbox.TBoxSessionHandle
import io.motohub.android.tbox.TBoxSessionRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class HubUiState(
    val session: HubSessionState = HubSessionState(),
    val motorcycles: List<MotorcycleProfile> = emptyList(),
    val ssid: String = "",
    val password: String = "",
    val formError: String? = null
)

class HubViewModel(application: Application) : AndroidViewModel(application) {
    private val profileStore = MotorcycleProfileStore(application)
    private val restoredProfiles = profileStore.loadAll()
    private val mutableUiState = MutableStateFlow(
        restoredUiState(restoredProfiles, profileStore.load())
    )
    val uiState: StateFlow<HubUiState> = mutableUiState.asStateFlow()
    private val networkConnector = TBoxNetworkConnector(application)
    private val transport = RideDaemonTransport(application)
    private var connectJob: Job? = null

    init {
        ProjectionEventLog.record(
            "STATE",
            if (mutableUiState.value.session.motorcycle == null) {
                "No saved motorcycle profile was found."
            } else {
                "Saved motorcycle profile restored for SSID ${mutableUiState.value.session.motorcycle?.ssid}."
            }
        )
        viewModelScope.launch {
            networkConnector.events.collect { event ->
                if (event is TBoxNetworkEvent.Lost) {
                    ProjectionEventLog.warning("NETWORK", "Android reported that the T-Box network was lost.")
                    val projectionActive = ProjectionRuntime.state.value is ProjectionRuntimeState.Starting ||
                        ProjectionRuntime.state.value is ProjectionRuntimeState.Streaming
                    if (!projectionActive) {
                        transport.stop()
                        networkConnector.disconnect()
                        TBoxSessionRegistry.clear()
                        mutableUiState.value = mutableUiState.value.copy(
                            session = mutableUiState.value.session.copy(
                                phase = SessionPhase.NETWORK_SETUP_REQUIRED,
                                message = "T-Box connection lost. Reconnect to the motorcycle network."
                            )
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            ProjectionRuntime.state.collect { runtime ->
                when (runtime) {
                    ProjectionRuntimeState.Starting -> updateProjectionState(
                        SessionPhase.REQUESTING_PROJECTION,
                        "Starting the projection pipeline."
                    )
                    ProjectionRuntimeState.Streaming -> updateProjectionState(
                        SessionPhase.CAPTURING,
                        "Streaming active to the motorcycle TFT."
                    )
                    is ProjectionRuntimeState.Stopped -> updateProjectionState(
                        SessionPhase.NETWORK_SETUP_REQUIRED,
                        runtime.reason
                    )
                    is ProjectionRuntimeState.Failed -> showError(runtime.message)
                    ProjectionRuntimeState.Idle -> Unit
                }
            }
        }
    }

    fun onSsidChanged(value: String) {
        mutableUiState.value = mutableUiState.value.copy(ssid = value, formError = null)
    }

    fun onPasswordChanged(value: String) {
        mutableUiState.value = mutableUiState.value.copy(password = value, formError = null)
    }

    fun saveMotorcycle() {
        val current = mutableUiState.value
        val normalizedSsid = current.ssid.trim()
        if (normalizedSsid.isEmpty()) {
            ProjectionEventLog.warning("PAIRING", "Manual profile save rejected because the SSID is empty.")
            mutableUiState.value = current.copy(formError = "Enter the motorcycle Wi-Fi network name.")
            return
        }

        val profile = current.motorcycles.firstOrNull { it.ssid == normalizedSsid }
            ?.copy(password = current.password)
            ?: MotorcycleProfile(ssid = normalizedSsid, password = current.password)
        val persistenceFailure = profileStore.save(profile).exceptionOrNull()
        if (persistenceFailure != null) {
            ProjectionEventLog.error("PAIRING", "Unable to save manual profile.", persistenceFailure)
            mutableUiState.value = current.copy(
                formError = "Unable to save the T-Box profile: ${persistenceFailure.message}"
            )
            return
        }

        mutableUiState.value = current.copy(
            motorcycles = current.motorcycles.replaceProfile(profile),
            session = current.session.withMotorcycle(profile),
            formError = null
        )
        ProjectionEventLog.record(
            "PAIRING",
            "Manual motorcycle profile saved for SSID $normalizedSsid; passwordPresent=${current.password.isNotEmpty()}."
        )
    }

    fun applyQrPairing(payload: TBoxQrPayload) {
        ProjectionEventLog.record(
            "PAIRING",
            "Valid T-Box QR decoded: ssid=${payload.ssid}, modelId=${payload.modelId ?: "not provided"}, " +
                "passwordPresent=${payload.password.isNotEmpty()}."
        )
        val existing = mutableUiState.value.motorcycles.firstOrNull { it.ssid == payload.ssid }
        val profile = existing?.copy(
            password = payload.password,
            modelId = payload.modelId ?: existing.modelId,
            displayName = payload.displayName ?: existing.displayName
        ) ?: MotorcycleProfile(
            ssid = payload.ssid,
            password = payload.password,
            modelId = payload.modelId,
            displayName = payload.displayName
        )
        val persistenceFailure = profileStore.save(profile).exceptionOrNull()
        mutableUiState.value = mutableUiState.value.copy(
            motorcycles = mutableUiState.value.motorcycles.replaceProfile(profile),
            ssid = payload.ssid,
            password = payload.password,
            formError = null,
            session = mutableUiState.value.session.withMotorcycle(profile).copy(
                message = if (persistenceFailure == null) {
                    "T-Box QR code scanned and saved. ${payload.ssid} is ready to connect."
                } else {
                    "QR code scanned, but the profile was not saved: ${persistenceFailure.message}"
                }
            )
        )
        if (persistenceFailure != null) {
            ProjectionEventLog.error("PAIRING", "QR profile could not be persisted.", persistenceFailure)
        } else {
            ProjectionEventLog.record("PAIRING", "QR motorcycle profile persisted successfully.")
        }
    }

    fun selectMotorcycle(profileId: String) {
        val current = mutableUiState.value
        val profile = current.motorcycles.firstOrNull { it.id == profileId } ?: return
        if (ProjectionRuntime.state.value is ProjectionRuntimeState.Starting ||
            ProjectionRuntime.state.value is ProjectionRuntimeState.Streaming
        ) {
            ProjectionEventLog.warning("GARAGE", "Motorcycle selection ignored during an active projection.")
            return
        }
        profileStore.setActive(profile.id).onFailure {
            ProjectionEventLog.error("GARAGE", "Unable to activate motorcycle ${profile.ssid}.", it)
            return
        }
        viewModelScope.launch {
            transport.stop()
            networkConnector.disconnect()
            TBoxSessionRegistry.clear()
        }
        mutableUiState.value = current.copy(
            session = HubSessionState().withMotorcycle(profile),
            ssid = profile.ssid,
            password = profile.password,
            formError = null
        )
        ProjectionEventLog.record("GARAGE", "Active motorcycle changed to ${profile.ssid}.")
    }

    fun updateMotorcycle(profile: MotorcycleProfile): Boolean {
        val current = mutableUiState.value
        val activeId = current.session.motorcycle?.id
        val saved = profileStore.save(profile, makeActive = profile.id == activeId)
        saved.onFailure {
            ProjectionEventLog.error("GARAGE", "Unable to update motorcycle ${profile.ssid}.", it)
            return false
        }
        mutableUiState.value = current.copy(
            motorcycles = current.motorcycles.replaceProfile(profile),
            session = if (activeId == profile.id) {
                current.session.copy(motorcycle = profile)
            } else {
                current.session
            },
            ssid = if (activeId == profile.id) profile.ssid else current.ssid,
            password = if (activeId == profile.id) profile.password else current.password
        )
        ProjectionEventLog.record("GARAGE", "Motorcycle profile updated for ${profile.ssid}.")
        return true
    }

    fun deleteMotorcycle(profileId: String) {
        val current = mutableUiState.value
        if (ProjectionRuntime.state.value is ProjectionRuntimeState.Starting ||
            ProjectionRuntime.state.value is ProjectionRuntimeState.Streaming
        ) {
            ProjectionEventLog.warning("GARAGE", "Motorcycle deletion ignored during an active projection.")
            return
        }
        val profile = current.motorcycles.firstOrNull { it.id == profileId } ?: return
        profileStore.delete(profileId).onFailure {
            ProjectionEventLog.error("GARAGE", "Unable to delete motorcycle ${profile.ssid}.", it)
            return
        }
        val remaining = current.motorcycles.filterNot { it.id == profileId }
        val active = profileStore.load()
        mutableUiState.value = if (active == null) {
            HubUiState(motorcycles = remaining)
        } else {
            restoredUiState(remaining, active).copy(
                session = restoredUiState(remaining, active).session.copy(
                    message = "${active.displayName ?: "Motorcycle"} is ready to connect."
                )
            )
        }
        ProjectionEventLog.record("GARAGE", "Motorcycle profile deleted for ${profile.ssid}.")
    }

    fun connectAndDiscover() {
        val profile = mutableUiState.value.session.motorcycle ?: run {
            ProjectionEventLog.warning("CONNECTION", "Connection request ignored because no profile is configured.")
            return
        }
        val phase = mutableUiState.value.session.phase
        if (connectJob?.isActive == true ||
            (phase != SessionPhase.NETWORK_SETUP_REQUIRED && phase != SessionPhase.ERROR)
        ) {
            ProjectionEventLog.warning(
                "CONNECTION",
                "Duplicate connection request ignored; phase=$phase, activeJob=${connectJob?.isActive == true}."
            )
            return
        }
        ProjectionEventLog.record("CONNECTION", "Connecting to saved T-Box AP ${profile.ssid}.")
        mutableUiState.value = mutableUiState.value.copy(
            session = mutableUiState.value.session.copy(
                phase = SessionPhase.CONNECTING_NETWORK,
                message = "Android is requesting a connection to ${profile.ssid}."
            )
        )
        connectJob = viewModelScope.launch {
            try {
                val connected = networkConnector.connect(profile)
                val networkFailure = connected.exceptionOrNull()
                if (networkFailure != null) {
                    ProjectionEventLog.error("NETWORK", "T-Box AP connection failed.", networkFailure)
                    showError("Unable to connect to the T-Box network: ${networkFailure.message}")
                    return@launch
                }

                ProjectionEventLog.record(
                    "NETWORK",
                    "T-Box AP connected and assigned network=${connected.getOrThrow()}."
                )

                mutableUiState.value = mutableUiState.value.copy(
                    session = mutableUiState.value.session.copy(
                        phase = SessionPhase.DISCOVERING_TBOX,
                        message = "Network connected. Searching for the EasyConn service."
                    )
                )
                val discovered = transport.discover(connected.getOrThrow())
                val discoveryFailure = discovered.exceptionOrNull()
                if (discoveryFailure != null) {
                    ProjectionEventLog.error("DISCOVERY", "EasyConn service discovery failed.", discoveryFailure)
                    transport.stop()
                    networkConnector.disconnect()
                    TBoxSessionRegistry.clear()
                    showError("T-Box not found: ${discoveryFailure.message}")
                    return@launch
                }
                val host = discovered.getOrThrow()
                ProjectionEventLog.record(
                    "DISCOVERY",
                    "EasyConn service found at ${host.ipAddress}:${host.port}; package=${host.packageName}."
                )
                TBoxSessionRegistry.install(TBoxSessionHandle(transport, host, networkConnector, profile))
                ProjectionEventLog.record("SESSION", "T-Box session handle installed; state is READY.")

                mutableUiState.value = mutableUiState.value.copy(
                    session = mutableUiState.value.session.copy(
                        phase = SessionPhase.READY,
                        message = "T-Box found. You can now choose an app or screen to share."
                    )
                )
            } finally {
                connectJob = null
                ProjectionEventLog.debug("CONNECTION", "Connection coroutine completed.")
            }
        }
    }

    fun cancelConnection() {
        val activeJob = connectJob ?: run {
            ProjectionEventLog.debug("CONNECTION", "Cancel request ignored because no connection is active.")
            return
        }
        ProjectionEventLog.record("CONNECTION", "User cancelled the connection attempt.")
        viewModelScope.launch {
            activeJob.cancelAndJoin()
            transport.stop()
            networkConnector.disconnect()
            TBoxSessionRegistry.clear()
            mutableUiState.value = mutableUiState.value.copy(
                session = mutableUiState.value.session.copy(
                    phase = SessionPhase.NETWORK_SETUP_REQUIRED,
                    message = "Connection cancelled. You can try again at any time."
                )
            )
        }
    }

    fun onProjectionRequested() {
        ProjectionEventLog.record("MIRROR", "Android granted screen capture permission.")
        mutableUiState.value = mutableUiState.value.copy(
            session = mutableUiState.value.session.copy(
                phase = SessionPhase.REQUESTING_PROJECTION,
                message = "Permission granted. Starting the capture session."
            )
        )
    }

    fun onProjectionCancelled() {
        ProjectionEventLog.warning("MIRROR", "Screen capture permission was cancelled or denied.")
        mutableUiState.value = mutableUiState.value.copy(
            session = mutableUiState.value.session.copy(
                phase = SessionPhase.READY,
                message = "Sharing cancelled by the user."
            )
        )
    }

    fun onNearbyWifiPermissionDenied() {
        ProjectionEventLog.warning("PERMISSION", "Nearby Wi-Fi or Location permission denied.")
        showError("Allow Nearby devices and Location to detect the T-Box Wi-Fi network.")
    }

    fun onNotificationPermissionDenied() {
        ProjectionEventLog.warning("PERMISSION", "Notification permission denied.")
        showError("Allow MOTO-HUB notifications to keep streaming visible and controllable.")
    }

    fun onCameraPermissionDenied() {
        ProjectionEventLog.warning("PERMISSION", "Camera permission denied.")
        showError("Camera permission is required to read the T-Box QR code.")
    }

    fun onQrImportFailed(message: String) {
        ProjectionEventLog.warning("PAIRING", message)
        showError(message)
    }

    override fun onCleared() {
        ProjectionEventLog.debug("STATE", "HubViewModel cleared.")
        if (ProjectionRuntime.state.value !is ProjectionRuntimeState.Starting &&
            ProjectionRuntime.state.value !is ProjectionRuntimeState.Streaming
        ) {
            networkConnector.disconnect()
            TBoxSessionRegistry.clear()
        }
        super.onCleared()
    }

    private fun showError(message: String) {
        ProjectionEventLog.error("STATE", message)
        mutableUiState.value = mutableUiState.value.copy(
            session = mutableUiState.value.session.copy(
                phase = SessionPhase.ERROR,
                message = message
            )
        )
    }

    private fun updateProjectionState(phase: SessionPhase, message: String) {
        ProjectionEventLog.record("STATE", "Session phase changed to $phase: $message")
        mutableUiState.value = mutableUiState.value.copy(
            session = mutableUiState.value.session.copy(phase = phase, message = message)
        )
    }
}

private fun restoredUiState(
    profiles: List<MotorcycleProfile>,
    profile: MotorcycleProfile?
): HubUiState = profile?.let { active -> HubUiState(
    motorcycles = profiles,
    session = HubSessionState().withMotorcycle(active).copy(
        message = "T-Box profile restored. You can connect and find the motorcycle without scanning the QR code."
    ),
    ssid = active.ssid,
    password = active.password
) } ?: HubUiState(motorcycles = profiles)

private fun List<MotorcycleProfile>.replaceProfile(profile: MotorcycleProfile): List<MotorcycleProfile> =
    filterNot { it.id == profile.id } + profile
