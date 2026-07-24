package io.motohub.android.feature.home

import android.app.Application
import io.motohub.android.i18n.motoHubText
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.motohub.android.data.MotorcycleProfileStore
import io.motohub.android.session.HubSessionState
import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.session.SessionPhase
import io.motohub.android.session.ProjectionRuntime
import io.motohub.android.session.ProjectionRuntimeState
import io.motohub.android.session.ProjectionEventLog
import io.motohub.android.session.TBoxConnectionMode
import io.motohub.android.session.withMotorcycle
import io.motohub.android.feature.pairing.TBoxQrPayload
import io.motohub.android.androidauto.AndroidAutoRuntime
import io.motohub.android.tbox.RideDaemonTransport
import io.motohub.android.tbox.TBoxCapabilityStore
import io.motohub.android.tbox.TBoxLinkResolver
import io.motohub.android.tbox.TBoxModelProfile
import io.motohub.android.tbox.ProfileOverride
import io.motohub.android.tbox.TBoxNetworkConnector
import io.motohub.android.tbox.TBoxNetworkEvent
import io.motohub.android.tbox.TBoxSessionHandle
import io.motohub.android.tbox.TBoxSessionRegistry
import io.motohub.android.tbox.TBoxVpnDiagnostics
import io.motohub.android.tbox.TBoxConflictDiagnostics
import io.motohub.android.tbox.WifiGate
import io.motohub.android.tbox.OfficialCfmotoClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class HubUiState(
    val session: HubSessionState = HubSessionState(),
    val motorcycles: List<MotorcycleProfile> = emptyList(),
    val ssid: String = "",
    val password: String = "",
    val connectionMode: TBoxConnectionMode = TBoxConnectionMode.AUTO,
    val formError: String? = null
)

class HubViewModel(application: Application) : AndroidViewModel(application) {
    private val profileStore = MotorcycleProfileStore(application)
    private val restoredProfiles = profileStore.loadAll()
    private val mutableUiState = MutableStateFlow(
        restoredUiState(
            profiles = restoredProfiles,
            profile = profileStore.load(),
            projectionRuntime = ProjectionRuntime.state.value
        )
    )
    val uiState: StateFlow<HubUiState> = mutableUiState.asStateFlow()
    private val networkConnector = TBoxNetworkConnector(application)
    private val transport = RideDaemonTransport(application)
    private val capabilityStore = TBoxCapabilityStore(application)
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
                    val projectionActive = isNativeStreamActive()
                    if (!projectionActive) {
                        transport.stop()
                        networkConnector.disconnect()
                        TBoxSessionRegistry.clear()
                        mutableUiState.value = mutableUiState.value.copy(
                            session = mutableUiState.value.session.copy(
                                phase = SessionPhase.NETWORK_SETUP_REQUIRED,
                                message = motoHubText("T-Box connection lost. Reconnect to the motorcycle network.")
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
                        motoHubText("Starting the projection pipeline.")
                    )
                    ProjectionRuntimeState.Streaming -> updateProjectionState(
                        SessionPhase.CAPTURING,
                        motoHubText("Streaming active to the motorcycle TFT.")
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

    fun onConnectionModeChanged(value: TBoxConnectionMode) {
        mutableUiState.value = mutableUiState.value.copy(connectionMode = value, formError = null)
    }

    /** @return true once the profile is saved and [HubUiState.formError] is clear. */
    fun saveMotorcycle(): Boolean {
        val current = mutableUiState.value
        val normalizedSsid = current.ssid.trim()
        if (normalizedSsid.isEmpty()) {
            ProjectionEventLog.warning("PAIRING", "Manual profile save rejected because the SSID is empty.")
            mutableUiState.value = current.copy(formError = "Enter the motorcycle Wi-Fi network name.")
            return false
        }

        val profile = current.motorcycles.firstOrNull { it.ssid == normalizedSsid }
            ?.copy(password = current.password, connectionMode = current.connectionMode)
            ?: MotorcycleProfile(
                ssid = normalizedSsid,
                password = current.password,
                connectionMode = current.connectionMode
            )
        val persistenceFailure = profileStore.save(profile).exceptionOrNull()
        if (persistenceFailure != null) {
            ProjectionEventLog.error("PAIRING", "Unable to save manual profile.", persistenceFailure)
            mutableUiState.value = current.copy(
                formError = "Unable to save the T-Box profile: ${persistenceFailure.message}"
            )
            return false
        }

        mutableUiState.value = current.copy(
            motorcycles = current.motorcycles.replaceProfile(profile),
            session = current.session.withMotorcycle(profile),
            formError = null
        )
        ProjectionEventLog.record(
            "PAIRING",
            "Manual motorcycle profile saved for SSID $normalizedSsid; mode=${current.connectionMode}; " +
                "passwordPresent=${current.password.isNotEmpty()}."
        )
        return true
    }

    /** Clears the pairing form fields - called before showing manual pairing so it never
     *  starts pre-filled with a different (already active) motorcycle's credentials. */
    fun resetManualPairingForm() {
        mutableUiState.value = mutableUiState.value.copy(
            ssid = "",
            password = "",
            connectionMode = TBoxConnectionMode.AUTO,
            formError = null
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
                    motoHubText("T-Box QR code scanned and saved. %1\$s is ready to connect.", payload.ssid)
                } else {
                    motoHubText("QR code scanned, but the profile was not saved: %1\$s", persistenceFailure.message.orEmpty())
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
        if (isNativeStreamActive()) {
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
            connectionMode = profile.connectionMode,
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
            password = if (activeId == profile.id) profile.password else current.password,
            connectionMode = if (activeId == profile.id) profile.connectionMode else current.connectionMode
        )
        ProjectionEventLog.record("GARAGE", "Motorcycle profile updated for ${profile.ssid}.")
        return true
    }

    fun deleteMotorcycle(profileId: String) {
        val current = mutableUiState.value
        if (isNativeStreamActive()) {
            ProjectionEventLog.warning("GARAGE", "Motorcycle deletion ignored during an active projection.")
            return
        }
        val profile = current.motorcycles.firstOrNull { it.id == profileId } ?: return
        profileStore.delete(profileId).onFailure {
            ProjectionEventLog.error("GARAGE", "Unable to delete motorcycle ${profile.ssid}.", it)
            return
        }
        capabilityStore.delete(profileId)
        val remaining = current.motorcycles.filterNot { it.id == profileId }
        val active = profileStore.load()
        mutableUiState.value = if (active == null) {
            HubUiState(motorcycles = remaining)
        } else {
            val restored = restoredUiState(
                profiles = remaining,
                profile = active,
                projectionRuntime = ProjectionRuntime.state.value
            )
            restored.copy(
                session = restored.session.copy(
                    message = motoHubText("%1\$s is ready to connect.", active.displayName ?: motoHubText("Motorcycle"))
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
        if (!WifiGate.isWifiEnabled(getApplication())) {
            ProjectionEventLog.warning("CONNECTION", "Connection request blocked: phone Wi-Fi is off.")
            showError(WifiGate.WIFI_OFF_MESSAGE)
            return
        }
        ProjectionEventLog.record("CONNECTION", "Connecting to saved T-Box AP ${profile.ssid}.")
        mutableUiState.value = mutableUiState.value.copy(
            session = mutableUiState.value.session.copy(
                phase = SessionPhase.CONNECTING_NETWORK,
                message = motoHubText("Android is requesting a connection to %1\$s.", profile.ssid)
            )
        )
        connectJob = viewModelScope.launch {
            var establishedLink: io.motohub.android.tbox.TBoxLink? = null
            var sessionInstalled = false
            try {
                // The official CFMOTO app can keep its EasyConn/PXC service alive after logout
                // and while it is only in the recent-apps list. Release its background process
                // before MOTO-HUB starts its own local 10920-10922 listeners.
                if (OfficialCfmotoClient.isInstalled(getApplication())) {
                    OfficialCfmotoClient.closeBestEffort(getApplication())
                    ProjectionEventLog.record(
                        "CONNECTION",
                        "Requested background shutdown of the official CFMOTO app before EasyConn connection."
                    )
                    delay(300)
                }
                val connected = TBoxLinkResolver.connect(getApplication(), networkConnector, profile)
                val networkFailure = connected.exceptionOrNull()
                if (networkFailure != null) {
                    ProjectionEventLog.error("NETWORK", "T-Box AP connection failed.", networkFailure)
                    // activeVpnLabel omitted: see TBoxNetworkConnector.connect() for why merely having a VPN active isn't evidence.
                    showError(
                        TBoxVpnDiagnostics.userFacingMessage(
                            error = networkFailure,
                            activeVpnLabel = null
                        ) ?: "Unable to connect to the T-Box network: ${networkFailure.message}"
                    )
                    return@launch
                }

                establishedLink = connected.getOrThrow()
                ProjectionEventLog.record("NETWORK", "T-Box link established (${establishedLink.label}).")

                mutableUiState.value = mutableUiState.value.copy(
                    session = mutableUiState.value.session.copy(
                        phase = SessionPhase.DISCOVERING_TBOX,
                        message = motoHubText("Network connected. Searching for the EasyConn service.")
                    )
                )
                transport.configureProtocolProfile(
                    TBoxModelProfile.resolve(
                        profile.modelId,
                        null,
                        ProfileOverride.byKey(profile.profileOverrideKey)
                    )
                )
                val discovered = transport.discover(establishedLink, profile.modelId)
                val discoveryFailure = discovered.exceptionOrNull()
                if (discoveryFailure != null) {
                    ProjectionEventLog.error("DISCOVERY", "EasyConn service discovery failed.", discoveryFailure)
                    transport.stop()
                    establishedLink.disconnect()
                    networkConnector.disconnect()
                    TBoxSessionRegistry.clear()
                    showError(motoHubText("T-Box not found: %1\$s", discoveryFailure.message.orEmpty()))
                    return@launch
                }
                val host = discovered.getOrThrow()
                capabilityStore.recordDiscovery(profile, host)
                ProjectionEventLog.record(
                    "DISCOVERY",
                    "EasyConn service found at ${host.ipAddress}:${host.port}; package=${host.packageName}."
                )
                TBoxSessionRegistry.install(
                    TBoxSessionHandle(transport, host, networkConnector, profile, establishedLink)
                )
                sessionInstalled = true
                ProjectionEventLog.record("SESSION", "T-Box session handle installed; state is READY.")

                mutableUiState.value = mutableUiState.value.copy(
                    session = mutableUiState.value.session.copy(
                        phase = SessionPhase.READY,
                        message = motoHubText("T-Box found. You can now choose an app or screen to share.")
                    )
                )
            } finally {
                // Cancellation after a P2P join but before registry installation otherwise leaves
                // the group alive because it has no ConnectivityManager callback to release it.
                if (!sessionInstalled) establishedLink?.disconnect()
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
                    message = motoHubText("Connection cancelled. You can try again at any time.")
                )
            )
        }
    }

    /**
     * Leaves an established T-Box connection (session phase READY, mode-selection screen) and
     * returns to the pre-connect state. Distinct from [cancelConnection], which only cancels a
     * connection attempt still in flight - once that attempt succeeds there is no [connectJob]
     * left to cancel, so without this the rider had no way back from "what to show?" except
     * force-stopping the app.
     */
    fun disconnect() {
        ProjectionEventLog.record("CONNECTION", "User disconnected from the T-Box.")
        viewModelScope.launch {
            transport.stop()
            networkConnector.disconnect()
            TBoxSessionRegistry.clear()
            mutableUiState.value = mutableUiState.value.copy(
                session = mutableUiState.value.session.copy(
                    phase = SessionPhase.NETWORK_SETUP_REQUIRED,
                    message = motoHubText("Disconnected.")
                )
            )
        }
    }

    fun onProjectionRequested() {
        ProjectionEventLog.record("MIRROR", "Android granted screen capture permission.")
        mutableUiState.value = mutableUiState.value.copy(
            session = mutableUiState.value.session.copy(
                phase = SessionPhase.REQUESTING_PROJECTION,
                message = motoHubText("Permission granted. Starting the capture session.")
            )
        )
    }

    fun onProjectionCancelled() {
        ProjectionEventLog.warning("MIRROR", "Screen capture permission was cancelled or denied.")
        mutableUiState.value = mutableUiState.value.copy(
            session = mutableUiState.value.session.copy(
                phase = SessionPhase.READY,
                message = motoHubText("Sharing cancelled by the user.")
            )
        )
    }

    fun onNearbyWifiPermissionDenied() {
        ProjectionEventLog.warning("PERMISSION", "Nearby Wi-Fi or Location permission denied.")
        showError(motoHubText("Allow Nearby devices and Location to detect the T-Box Wi-Fi network."))
    }

    fun onNotificationPermissionDenied() {
        ProjectionEventLog.warning("PERMISSION", "Notification permission denied.")
        showError(motoHubText("Allow MOTO-HUB notifications to keep streaming visible and controllable."))
    }

    fun onCameraPermissionDenied() {
        ProjectionEventLog.warning("PERMISSION", "Camera permission denied.")
        showError(motoHubText("Camera permission is required to read the T-Box QR code."))
    }

    fun onQrImportFailed(message: String) {
        ProjectionEventLog.warning("PAIRING", message)
        showError(message)
    }

    override fun onCleared() {
        ProjectionEventLog.debug("STATE", "HubViewModel cleared.")
        if (!isNativeStreamActive()) {
            networkConnector.disconnect()
            TBoxSessionRegistry.clear()
        }
        super.onCleared()
    }

    private fun showError(message: String) {
        val userFacingMessage = TBoxConflictDiagnostics.userFacingMessage(message)
        ProjectionEventLog.error("STATE", userFacingMessage)
        mutableUiState.value = mutableUiState.value.copy(
            session = mutableUiState.value.session.copy(
                phase = SessionPhase.ERROR,
                message = userFacingMessage
            )
        )
    }

    private fun updateProjectionState(phase: SessionPhase, message: String) {
        ProjectionEventLog.record("STATE", "Session phase changed to $phase: $message")
        mutableUiState.value = mutableUiState.value.copy(
            session = mutableUiState.value.session.copy(phase = phase, message = message)
        )
    }

    private fun isNativeStreamActive(): Boolean =
        ProjectionRuntime.state.value is ProjectionRuntimeState.Starting ||
            ProjectionRuntime.state.value is ProjectionRuntimeState.Streaming ||
            AndroidAutoRuntime.isActive()
}

private fun restoredUiState(
    profiles: List<MotorcycleProfile>,
    profile: MotorcycleProfile?,
    projectionRuntime: ProjectionRuntimeState = ProjectionRuntimeState.Idle
): HubUiState = profile?.let { active -> HubUiState(
    motorcycles = profiles,
    session = HubSessionState().withMotorcycle(active).copy(
        phase = projectionRuntime.restoredSessionPhase(),
        message = projectionRuntime.restoredSessionMessage()
    ),
    ssid = active.ssid,
    password = active.password
) } ?: HubUiState(motorcycles = profiles)

private fun List<MotorcycleProfile>.replaceProfile(profile: MotorcycleProfile): List<MotorcycleProfile> =
    filterNot { it.id == profile.id } + profile

private fun ProjectionRuntimeState.restoredSessionPhase(): SessionPhase = when (this) {
    ProjectionRuntimeState.Starting -> SessionPhase.REQUESTING_PROJECTION
    ProjectionRuntimeState.Streaming -> SessionPhase.CAPTURING
    else -> SessionPhase.NETWORK_SETUP_REQUIRED
}

private fun ProjectionRuntimeState.restoredSessionMessage(): String = when (this) {
    ProjectionRuntimeState.Starting -> "Mirroring is already starting on the motorcycle TFT."
    ProjectionRuntimeState.Streaming -> "Mirroring is already active on the motorcycle TFT."
    else -> "T-Box profile restored. You can connect and find the motorcycle without scanning the QR code."
}
