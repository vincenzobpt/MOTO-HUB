// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.home

import android.app.Application
import io.motohub.android.i18n.motoHubText
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.motohub.android.data.MotorcycleProfileStore
import io.motohub.android.feature.pairing.withModelIdForConnectionMode
import io.motohub.android.session.ConnectionProgressNotification
import io.motohub.android.session.HubSessionState
import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.session.SessionPhase
import io.motohub.android.session.ProjectionRuntime
import io.motohub.android.session.ProjectionRuntimeState
import io.motohub.android.session.DashboardDeliveryMonitor
import io.motohub.android.tbox.ProfileSuggestions
import io.motohub.android.session.ProjectionEventLog
import io.motohub.android.session.TBoxConnectionMode
import io.motohub.android.session.withMotorcycle
import io.motohub.android.feature.pairing.TBoxQrPayload
import io.motohub.android.androidauto.AndroidAutoRuntime
import io.motohub.android.tbox.SelectingTBoxTransport
import io.motohub.android.tbox.TBoxCapabilityStore
import io.motohub.android.tbox.TBoxLinkResolver
import io.motohub.android.tbox.TBoxModelProfile
import io.motohub.android.tbox.TBoxProtocolMemory
import io.motohub.android.tbox.ThinkerRideGate
import io.motohub.android.tbox.ProfileOverride
import io.motohub.android.tbox.TBoxNetworkConnectors
import io.motohub.android.tbox.TBoxNetworkEvent
import io.motohub.android.tbox.TBoxSessionHandle
import io.motohub.android.tbox.TBoxSessionRegistry
import io.motohub.android.tbox.TBoxVpnDiagnostics
import io.motohub.android.tbox.TBoxConflictDiagnostics
import io.motohub.android.tbox.TBoxLadderState
import io.motohub.android.tbox.TBoxWireLadder
import io.motohub.android.tbox.WifiGate
import io.motohub.android.tbox.CompanionAppRegistry
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
    val connectionMode: TBoxConnectionMode = TBoxConnectionMode.AUTO,
    val formError: String? = null,
    /**
     * The motorcycle whose last session streamed happily and now needs the one thing the protocol
     * cannot report: whether anything appeared on the dashboard. Null unless the wire ladder is
     * waiting on that answer.
     */
    val wireQuestionFor: MotorcycleProfile? = null,
    /**
     * Set when the search is stuck because every session so far ran the Ride Dashboard, which
     * sends its own video format and so teaches the search nothing.
     */
    val wireNeedsAndroidAutoFor: MotorcycleProfile? = null,
    /**
     * What to offer a rider whose dashboard is connected and refusing the picture, already
     * ranked. Empty unless [HubSessionState.deliveryWarning] is set.
     *
     * Ranked here rather than in the screen because the ranking's best signal after the active
     * profile is the dashboard's stored CLIENT_INFO capabilities, and the capability store is a
     * ViewModel dependency. Threading it through the composables would put a store lookup in a
     * recomposition.
     */
    val profileSuggestions: List<ProfileSuggestions.Suggestion> = emptyList(),
    /** A profile the rider picked and is waiting on. See [PendingProfileTrial]. */
    val pendingTrial: PendingProfileTrial? = null,
    /**
     * A trial the dashboard has now accepted, waiting for the rider to say whether to keep it.
     *
     * Kept apart from [pendingTrial] rather than flagged inside it so the two states cannot be
     * confused at a glance: one means "watching", the other means "a question is on screen".
     */
    val trialToConfirm: PendingProfileTrial? = null
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
    // The process's one shared connector: constructing a private instance here is what put two
    // exclusive Wi-Fi requests on the air whenever the companion app connected beside this UI.
    // Teardown goes through TBoxNetworkConnectors.release, never connector.disconnect().
    private val networkConnector = TBoxNetworkConnectors.shared(application)
    private val transport = SelectingTBoxTransport(application)
    private val capabilityStore = TBoxCapabilityStore(application)
    private var connectJob: Job? = null

    /**
     * The rider's own "no", remembered until a connection genuinely starts again.
     *
     * [cancelConnection] leaves the phase at NETWORK_SETUP_REQUIRED, which is exactly the phase
     * auto-connect waits for, so a cancel used to be answered by a fresh automatic attempt one
     * 5s cooldown later - see [autoConnectDecision], which reads this. Not saved state on
     * purpose: it dies with the ViewModel, so a relaunch starts willing to try again.
     */
    var riderCancelledConnect: Boolean = false
        private set

    /**
     * Whether the active motorcycle is visibly on the air - tri-state, see
     * [TBoxNetworkConnector.isDashBroadcasting]. Null when no profile is selected, too, since
     * that is equally "cannot be said".
     */
    fun isDashBroadcasting(): Boolean? =
        mutableUiState.value.session.motorcycle?.let(networkConnector::isDashBroadcasting)

    init {
        ProjectionEventLog.record(
            "STATE",
            if (mutableUiState.value.session.motorcycle == null) {
                "No saved motorcycle profile was found."
            } else {
                "Saved motorcycle profile restored for SSID ${mutableUiState.value.session.motorcycle?.ssid}."
            }
        )
        // In this edition the video pipelines run in this very process, so the verdict is simply
        // observable. The companion app has to ask Core for it over the bridge - see the same
        // block in the ADVANCED HubViewModel.
        viewModelScope.launch {
            DashboardDeliveryMonitor.current.collect { report ->
                val current = mutableUiState.value
                val settled = current.pendingTrial?.let { trial ->
                    ProfileTrialPolicy.outcome(trial, report)
                }
                mutableUiState.value = current.copy(
                    session = current.session.copy(deliveryWarning = report),
                    profileSuggestions = report?.let { suggestionsFor(it) }.orEmpty(),
                    // A settled trial stops being pending whichever way it went. A failed one
                    // simply leaves the warning standing, which puts the rider back in front of
                    // the list - the honest outcome, and not one to dress up.
                    pendingTrial = if (settled == ProfileTrialPolicy.Outcome.PENDING) {
                        current.pendingTrial
                    } else {
                        null
                    },
                    trialToConfirm = if (settled == ProfileTrialPolicy.Outcome.CONFIRMED) {
                        current.pendingTrial
                    } else {
                        current.trialToConfirm
                    }
                )
                if (settled == ProfileTrialPolicy.Outcome.CONFIRMED) {
                    ProjectionEventLog.record(
                        "PROFILE",
                        "The dashboard accepted the picture on the " +
                            "${current.pendingTrial?.override?.label} profile; asking the rider " +
                            "whether to keep it."
                    )
                }
            }
        }
        viewModelScope.launch {
            networkConnector.events.collect { event ->
                if (event is TBoxNetworkEvent.Lost) {
                    ProjectionEventLog.warning("NETWORK", "Android reported that the T-Box network was lost.")
                    val projectionActive = isNativeStreamActive()
                    if (!projectionActive) {
                        transport.stop()
                        TBoxSessionRegistry.clear()
                        TBoxNetworkConnectors.release(HUB_UI_NETWORK_OWNER)
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

        // Keeps the stored spelling rather than the typed one: this name has been connected with,
        // and a rider retyping it in another case is correcting nothing.
        val profile = (
            current.motorcycles.bySsidIgnoringCase(normalizedSsid)
                ?.copy(password = current.password, connectionMode = current.connectionMode)
                ?: MotorcycleProfile(
                    ssid = normalizedSsid,
                    password = current.password,
                    connectionMode = current.connectionMode
                )
            ).withModelIdForConnectionMode()
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
            "Manual motorcycle profile saved for SSID $normalizedSsid; " +
                "mode=${profile.connectionMode}; modelId=${profile.modelId ?: "none"}; " +
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

    /**
     * True when the scanned code identified the dash but carried no network to join, because the
     * dash expects the phone to host one. There is nothing to save yet: Android will not let an app
     * create a hotspot with credentials someone else dictates, so the rider has to read the SSID
     * and password off the dash and type them. Answered here so the caller can send them to the
     * manual form instead of the "saved, ready to connect" path.
     */
    fun needsPhoneHotspotCredentials(payload: TBoxQrPayload): Boolean =
        payload.ssid.isBlank() && payload.suggestedConnectionMode == TBoxConnectionMode.PHONE_HOTSPOT

    /** Pre-fills the manual form for a phone-hotspot code, which named a dash but no network. */
    fun prepareQrPhoneHotspotSetup(payload: TBoxQrPayload) {
        ProjectionEventLog.record(
            "PAIRING",
            "QR identified a dash that expects the phone to host the network " +
                "(mac=${payload.dashMacAddress ?: "not provided"}, " +
                "modelId=${payload.modelId ?: "not provided"}); asking the rider for the " +
                "credentials the dash prints on its own screen."
        )
        mutableUiState.value = mutableUiState.value.copy(
            ssid = "",
            password = "",
            connectionMode = TBoxConnectionMode.PHONE_HOTSPOT,
            formError = null
        )
    }

    fun applyQrPairing(payload: TBoxQrPayload) {
        if (needsPhoneHotspotCredentials(payload)) {
            prepareQrPhoneHotspotSetup(payload)
            return
        }
        ProjectionEventLog.record(
            "PAIRING",
            "Valid T-Box QR decoded: ssid=${payload.ssid}, modelId=${payload.modelId ?: "not provided"}, " +
                "passwordPresent=${payload.password.isNotEmpty()}, " +
                // What the dash says about how it can be reached, which is the first thing worth
                // knowing when a rider reports "it connects but the screen stays on the QR code".
                // It was parsed all along and never written down: a Zontes 368G (modelId 21334)
                // joined its access point perfectly on 2026-08-22 and then refused every port,
                // and nothing in that log says whether the code had claimed an access point, a
                // Wi-Fi Direct group, both, or neither.
                "advertises=${payload.topology.describe()}, " +
                "dashMac=${payload.dashMacAddress ?: "not provided"}."
        )
        val existing = mutableUiState.value.motorcycles.bySsidIgnoringCase(payload.ssid)
        val profile = (
            existing?.copy(
                // Unlike the typed path, the spelling here comes from the dash's own QR, so it wins.
                ssid = payload.ssid,
                password = payload.password,
                modelId = payload.modelId ?: existing.modelId,
                displayName = payload.displayName ?: existing.displayName,
                connectionMode = payload.suggestedConnectionMode ?: existing.connectionMode
            ) ?: MotorcycleProfile(
                ssid = payload.ssid,
                password = payload.password,
                modelId = payload.modelId,
                displayName = payload.displayName,
                connectionMode = payload.suggestedConnectionMode ?: TBoxConnectionMode.AUTO
            )
            // A rider who had guessed the ThinkerRide chip by hand and then scanned a code that
            // moves the dash to another mode must not keep the pseudo modelId that guess stamped.
            ).withModelIdForConnectionMode()
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
            TBoxSessionRegistry.clear()
            TBoxNetworkConnectors.release(HUB_UI_NETWORK_OWNER)
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
        // A connection is genuinely starting - manual Connect, the permission-grant retry, the
        // reconnect after a mode stops, or an auto-connect the policy did let through. Whichever
        // it was, the earlier cancel has been answered and must not keep suppressing anything.
        riderCancelledConnect = false
        // "Is Wi-Fi on" is the wrong question for a dash that joins a network the phone hosts:
        // tethering turns the station radio off, so that check reports false for the whole life
        // of a working PHONE_HOTSPOT session and used to block every connect through here -
        // manual and automatic alike. See WifiGate.isHostingANetwork for the field log.
        if (profile.connectionMode == TBoxConnectionMode.BLE_PROVISIONED) {
            // Neither gate applies: this dash is reached by creating a network, not by joining
            // one, and creating it takes the station radio down. Asking for Wi-Fi to be on would
            // block the connect that is about to turn it off, and asking for a hotspot to already
            // exist would block the one path that makes one. Bluetooth is the real precondition
            // and EcBtpNetLink checks it, with a message that names it.
            ProjectionEventLog.debug(
                "CONNECTION",
                "${profile.ssid} is set up over Bluetooth; the Wi-Fi checks do not apply to it."
            )
        } else if (profile.connectionMode == TBoxConnectionMode.PHONE_HOTSPOT) {
            // No hotspot is not automatically the end of the road any more. Some dashes in this
            // mode print no credentials for the rider to enter and can only be put on a network
            // over Bluetooth, so when a scan is possible the connect is allowed through to try
            // it; the same message still comes back seconds later if nothing answers. When it is
            // not possible, the instant answer is the better one.
            if (!WifiGate.isHostingANetwork()) {
                if (!ThinkerRideGate.bluetoothReady(getApplication())) {
                    ProjectionEventLog.warning(
                        "CONNECTION",
                        "Connection request blocked: ${profile.ssid} expects the phone to host the " +
                            "network, no hotspot subnet exists on this phone, and Bluetooth is not " +
                            "available to set the dash up over instead."
                    )
                    showError(WifiGate.HOTSPOT_OFF_MESSAGE)
                    return
                }
                ProjectionEventLog.record(
                    "CONNECTION",
                    "No hotspot is running for ${profile.ssid}; trying to set the dash up over " +
                        "Bluetooth before telling the rider to turn one on."
                )
            }
        } else if (!WifiGate.isWifiEnabled(getApplication())) {
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
        // The only sign outside this screen that anything is happening. It matters because the
        // work does not survive the screen: see ConnectionProgressNotification.
        ConnectionProgressNotification.show(getApplication(), profile.ssid, searching = false)
        connectJob = viewModelScope.launch {
            var establishedLink: io.motohub.android.tbox.TBoxLink? = null
            var sessionInstalled = false
            try {
                // An OEM companion app can keep its EasyConn/PXC service alive after logout and
                // while it is only in the recent-apps list. Android 14+ offers no way to close
                // another app's process, so this is recorded as a risk factor only; the error
                // banner guides the user to force-stop it when the conflict actually bites.
                CompanionAppRegistry.installed(getApplication())?.let { companion ->
                    ProjectionEventLog.debug(
                        "CONNECTION",
                        "${companion.displayName} (${companion.packageName}) is installed; it may " +
                            "hold the T-Box session if it was recently used."
                    )
                }
                // Interest registered per attempt and idempotent, so auto-connect firing on
                // every resume holds one lease, not a pile. A failed attempt keeps it: the
                // specifier request deliberately outlives its 30s timeout (v1.1.17) and the
                // retry must join that hunt, not restart it.
                TBoxNetworkConnectors.acquire(getApplication(), HUB_UI_NETWORK_OWNER)
                val connected = TBoxLinkResolver.connect(getApplication(), networkConnector, profile)
                val networkFailure = connected.exceptionOrNull()
                if (networkFailure != null) {
                    ProjectionEventLog.error("NETWORK", "T-Box AP connection failed.", networkFailure)
                    // routing omitted: the connector already tested the VPN's routes against the
                    // network it was granted and put its verdict in the message, if it had one.
                    showError(
                        TBoxVpnDiagnostics.userFacingMessage(
                            error = networkFailure,
                            routing = null
                        ) ?: "Unable to connect to the T-Box network: ${networkFailure.message}",
                        // Android never joined an access point. On a dash that is itself a Wi-Fi
                        // client there is no access point to join, so this is the only failure it
                        // can ever produce - and it is indistinguishable from a dash that is off.
                        // Offer the other mode instead of leaving the rider to find it.
                        //
                        // Not on Wi-Fi Direct, though. A P2P Group Owner hosts the network; a
                        // phone-hotspot dash joins one. They are opposite topologies, and this
                        // mode is now set by a code that said so ([TBoxQrTopology]), so the offer
                        // would contradict the dash's own claim. The QJ rider of field log
                        // 6b345de4 said exactly that back to us: "non e' il modo in cui posso
                        // connettere la moto".
                        offerPhoneHotspotRetry = profile.connectionMode !=
                            TBoxConnectionMode.PHONE_HOTSPOT &&
                            profile.connectionMode != TBoxConnectionMode.WIFI_DIRECT
                    )
                    return@launch
                }

                establishedLink = connected.getOrThrow()
                ProjectionEventLog.record("NETWORK", "T-Box link established (${establishedLink.label}).")

                ConnectionProgressNotification.show(getApplication(), profile.ssid, searching = true)
                mutableUiState.value = mutableUiState.value.copy(
                    session = mutableUiState.value.session.copy(
                        phase = SessionPhase.DISCOVERING_TBOX,
                        message = when (profile.connectionMode) {
                            TBoxConnectionMode.THINKERRIDE ->
                                motoHubText("Network connected. Pairing over Bluetooth and waiting for the dashboard.")
                            // Naming the cost is the point. On a hosted network discovery ends in
                            // a 253-address sweep that can run for a minute, and a rider with no
                            // idea of that reads the spinner as a hang and closes the app - which
                            // is exactly what ends the search.
                            TBoxConnectionMode.PHONE_HOTSPOT ->
                                motoHubText(
                                    "Hotspot is up. Searching it for the dashboard - this can take " +
                                        "up to 90 seconds, so keep MOTO-HUB open."
                                )
                            else -> motoHubText("Network connected. Searching for the EasyConn service.")
                        }
                    )
                )
                val requestedOverride = ProfileOverride.byKey(profile.profileOverrideKey)
                ProjectionEventLog.record(
                    "PROFILE",
                    "Resolving protocol profile: modelId=${profile.modelId ?: "none"}, " +
                        "override=${requestedOverride.key}, connectionMode=${profile.connectionMode}."
                )
                // A dash whose family we already learned is routed straight there, instead of
                // waiting for EasyConn discovery to time out first (two 15s NSD windows plus wake
                // probes). A pinned override always wins, and only non-EasyConn families are ever
                // remembered, so this can only ever save time.
                val protocolMemory = TBoxProtocolMemory(getApplication())
                val learnedProfile = if (requestedOverride == ProfileOverride.AUTO) {
                    protocolMemory.learnedFamily(profile.ssid)
                        ?.let { family -> TBoxModelProfile.entries.firstOrNull { it.transportFamily == family } }
                } else {
                    null
                }
                learnedProfile?.let {
                    ProjectionEventLog.record(
                        "PROFILE",
                        "This motorcycle was already seen speaking ${it.transportFamily}; going " +
                            "straight to that transport instead of letting EasyConn time out first."
                    )
                }
                val resolvedProfile =
                    learnedProfile ?: TBoxModelProfile.resolve(profile.modelId, null, requestedOverride)
                transport.configureProtocolProfile(resolvedProfile, profile)
                val discovered = transport.discover(establishedLink, profile.modelId)
                val discoveryFailure = discovered.exceptionOrNull()
                if (discoveryFailure != null) {
                    // The family that actually ran, not always EasyConn - see CoreTBoxConnector.
                    ProjectionEventLog.error(
                        "DISCOVERY",
                        "${resolvedProfile.transportFamily} service discovery failed.",
                        discoveryFailure
                    )
                    // The link was up and the dash did not answer on it. Another EasyConn app
                    // holding the session is a real explanation here, and only here.
                    val routingDiagnosis = networkConnector.vpnRoutingDiagnosis()
                    transport.stop()
                    establishedLink.disconnect()
                    TBoxSessionRegistry.clear()
                    TBoxNetworkConnectors.release(HUB_UI_NETWORK_OWNER)
                    if (routingDiagnosis != null) {
                        // Nothing this app sent ever left the phone: report the route, not the dash.
                        showError(routingDiagnosis)
                    } else {
                        showError(
                            motoHubText("T-Box not found: %1\$s", discoveryFailure.message.orEmpty()),
                            offerOfficialAppHelp = true
                        )
                    }
                    return@launch
                }
                val host = discovered.getOrThrow()
                // Record what discovery settled on, so the next ride skips the slow path. Read off
                // the switch itself and not off activeProtocolProfile, which now also carries a
                // pin: what is worth remembering is what the DASH answered unasked, never what the
                // rider tried.
                transport.discoverySwitchedProfile?.let { discoveredProfile ->
                    protocolMemory.remember(profile.ssid, discoveredProfile.transportFamily)
                }
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
                ConnectionProgressNotification.clear(getApplication())
                // A cancelled attempt used to end on the same "coroutine completed" debug line as
                // a finished one, and that cost a diagnosis: the 2026-08-23 QJ log ends four
                // seconds into a hosted-network sweep with no hint that the sweep was killed
                // rather than answered, so the one fact the whole log was collected for - whether
                // anything is on that hotspot - was simply absent and read as absence.
                val cancelled =
                    kotlinx.coroutines.currentCoroutineContext()[Job]?.isCancelled == true
                if (cancelled && !sessionInstalled) {
                    ProjectionEventLog.warning(
                        "CONNECTION",
                        "Connection attempt was cancelled during " +
                            "${mutableUiState.value.session.phase}, either by the rider or by " +
                            "MOTO-HUB being closed. The search does not outlive the app, so " +
                            "whatever this step was about to report is missing from this log."
                    )
                }
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
        riderCancelledConnect = true
        viewModelScope.launch {
            activeJob.cancelAndJoin()
            transport.stop()
            TBoxSessionRegistry.clear()
            TBoxNetworkConnectors.release(HUB_UI_NETWORK_OWNER)
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
    /**
     * The ranked offer for a failing session, with the dashboard's own stored capabilities fed in
     * so a dash that identifies itself is proposed first.
     */
    private fun suggestionsFor(report: io.motohub.android.session.DashboardDeliveryReport):
        List<ProfileSuggestions.Suggestion> {
        val motorcycle = mutableUiState.value.session.motorcycle
        return ProfileSuggestions.forFailingSession(
            activeProfileKey = report.profileKey,
            currentKey = motorcycle?.profileOverrideKey,
            modelId = motorcycle?.modelId,
            capabilities = motorcycle?.let {
                runCatching { capabilityStore.load(it)?.capabilities }.getOrNull()
            }
        )
    }

    /**
     * Applies a profile the rider picked from the trial screen and rebuilds the session on it.
     *
     * Saved before reconnecting, not after it works, because the profile is what the connect is
     * made OF - it selects the transport, the encoder settings and the touch policy, so there is
     * no way to try one without writing it down first. What that costs is a rider who tries three
     * profiles and leaves the last one pinned; the confirmation step is what settles that.
     */
    fun tryProfile(override: ProfileOverride) {
        val motorcycle = mutableUiState.value.session.motorcycle ?: return
        ProjectionEventLog.record(
            "PROFILE",
            "Rider is trying the ${override.label} profile for ${motorcycle.ssid} after the " +
                "dashboard refused most of the picture on the current one."
        )
        val previousKey = motorcycle.profileOverrideKey
        if (!updateMotorcycle(motorcycle.copy(profileOverrideKey = override.key))) return
        // The stale verdict must go now rather than when the next session raises its own: it is
        // the thing keeping the offer on screen, and leaving it up while reconnecting would show
        // the rider a complaint about a session that no longer exists.
        DashboardDeliveryMonitor.clear()
        mutableUiState.value = mutableUiState.value.copy(
            session = mutableUiState.value.session.copy(deliveryWarning = null),
            profileSuggestions = emptyList(),
            trialToConfirm = null,
            pendingTrial = PendingProfileTrial(
                ssid = motorcycle.ssid,
                override = override,
                previousKey = previousKey
            )
        )
        viewModelScope.launch {
            transport.stop()
            TBoxSessionRegistry.clear()
            TBoxNetworkConnectors.release(HUB_UI_NETWORK_OWNER)
            connectAndDiscover()
        }
    }

    /** The rider keeps what they picked. Nothing to write - it is already pinned. */
    fun keepTrialledProfile() {
        val trial = mutableUiState.value.trialToConfirm ?: return
        ProjectionEventLog.record(
            "PROFILE",
            "Rider kept the ${trial.override.label} profile for ${trial.ssid} after seeing it work."
        )
        mutableUiState.value = mutableUiState.value.copy(trialToConfirm = null)
    }

    /**
     * The rider declines, so the pin goes back exactly as it was - including back to Auto when
     * there was no pin at all, which is the commonest case and the one a naive "restore" would
     * get wrong by leaving the trial pinned forever.
     *
     * The session is NOT rebuilt here. It is working: that is the entire reason this question was
     * asked. Tearing down a dashboard the rider can see, to apply a profile they have just been
     * shown is worse, would be the app punishing them for answering honestly - the old profile
     * comes back on the next connect.
     */
    fun discardTrialledProfile() {
        val trial = mutableUiState.value.trialToConfirm ?: return
        val motorcycle = mutableUiState.value.session.motorcycle
        ProjectionEventLog.record(
            "PROFILE",
            "Rider declined to keep the ${trial.override.label} profile for ${trial.ssid}; " +
                "restoring the previous setting from the next connection."
        )
        if (motorcycle != null && motorcycle.ssid == trial.ssid) {
            updateMotorcycle(motorcycle.copy(profileOverrideKey = trial.previousKey))
        }
        mutableUiState.value = mutableUiState.value.copy(trialToConfirm = null)
    }

    fun disconnect() {
        ProjectionEventLog.record("CONNECTION", "User disconnected from the T-Box.")
        viewModelScope.launch {
            transport.stop()
            TBoxSessionRegistry.clear()
            TBoxNetworkConnectors.release(HUB_UI_NETWORK_OWNER)
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

    private companion object {
        /** This ViewModel's name in [TBoxNetworkConnectors]' interest ledger. */
        const val HUB_UI_NETWORK_OWNER = "hub-ui"
    }

    override fun onCleared() {
        ProjectionEventLog.debug("STATE", "HubViewModel cleared.")
        if (!isNativeStreamActive()) {
            TBoxSessionRegistry.clear()
        }
        // Unconditional, unlike the clear above: this owner is going away either way. During a
        // native stream the session's own lease (and the bridge's, when the companion drives it)
        // keeps the network - the request no longer survives on a skipped disconnect. On
        // 2026-08-04 this exact teardown released the UI's private request and dropped the AP
        // under the companion session that was streaming over it.
        TBoxNetworkConnectors.release(HUB_UI_NETWORK_OWNER)
        super.onCleared()
    }

    /**
     * Opens manual pairing already filled in for the phone-hosted transport, keeping the SSID and
     * password the rider already entered. Retyping them is the whole reason the offer would go
     * unused: the credentials are the dash's, printed on its screen, and nobody wants to copy
     * them twice to test a theory.
     */
    fun preparePhoneHotspotRetry() {
        val profile = mutableUiState.value.session.motorcycle
        ProjectionEventLog.record(
            "PAIRING",
            "Rider is retrying ${profile?.ssid ?: "the motorcycle"} as a phone-hosted network " +
                "after the access-point join failed."
        )
        mutableUiState.value = mutableUiState.value.copy(
            ssid = profile?.ssid.orEmpty(),
            password = profile?.password.orEmpty(),
            connectionMode = TBoxConnectionMode.PHONE_HOTSPOT,
            formError = null
        )
    }

    /**
     * Asked when the app comes back to the foreground, not the moment a session ends: the rider is
     * on a motorcycle when it ends, and the question is about something they have to look at.
     */
    fun refreshWireQuestion() {
        val motorcycle = profileStore.load()
        val pending = motorcycle?.takeIf {
            TBoxWireLadder.load(getApplication(), it).state == TBoxLadderState.AWAITING_RIDER
        }
        // The nudge only matters while nothing is being asked: one question at a time.
        val nudge = motorcycle?.takeIf {
            pending == null && TBoxWireLadder.needsAndroidAutoNudge(getApplication(), it)
        }
        if (pending?.id != mutableUiState.value.wireQuestionFor?.id ||
            nudge?.id != mutableUiState.value.wireNeedsAndroidAutoFor?.id
        ) {
            mutableUiState.value = mutableUiState.value.copy(
                wireQuestionFor = pending,
                wireNeedsAndroidAutoFor = nudge
            )
        }
    }

    fun dismissWireAndroidAutoNudge() {
        val motorcycle = mutableUiState.value.wireNeedsAndroidAutoFor ?: return
        TBoxWireLadder.markAndroidAutoNudgeShown(getApplication(), motorcycle)
        mutableUiState.value = mutableUiState.value.copy(wireNeedsAndroidAutoFor = null)
    }

    fun answerWireQuestion(projectionSeen: Boolean) {
        val motorcycle = mutableUiState.value.wireQuestionFor ?: return
        TBoxWireLadder.onRiderVerdict(getApplication(), motorcycle, projectionSeen)
        mutableUiState.value = mutableUiState.value.copy(wireQuestionFor = null)
    }

    /**
     * @param offerOfficialAppHelp only for failures a busy EasyConn session could have caused -
     *   see [HubSessionState.offerOfficialAppHelp]. Defaults to false so a new failure path has to
     *   claim that help deliberately rather than inherit it.
     */
    private fun showError(
        message: String,
        offerPhoneHotspotRetry: Boolean = false,
        offerOfficialAppHelp: Boolean = false
    ) {
        val userFacingMessage = TBoxConflictDiagnostics.userFacingMessage(
            message,
            // Named only when it is really on this phone, so the banner never sends a rider
            // after an app they do not have - the failure this whole path exists to avoid.
            companionAppName = CompanionAppRegistry.installedName(getApplication())
        )
        // Recorded as a warning, not an error: this only puts a banner on screen. Whatever
        // actually failed was already reported at ERROR by the layer that detected it, and
        // logging it again here sent every failure to telemetry twice - while turning purely
        // user-recoverable prompts ("Wi-Fi is off", "grant Nearby devices") into fault reports.
        ProjectionEventLog.warning("STATE", userFacingMessage)
        mutableUiState.value = mutableUiState.value.copy(
            session = mutableUiState.value.session.copy(
                phase = SessionPhase.ERROR,
                message = userFacingMessage,
                offerPhoneHotspotRetry = offerPhoneHotspotRetry,
                offerOfficialAppHelp = offerOfficialAppHelp
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

/**
 * The saved motorcycle for a network name, ignoring case - because the half of the app that
 * actually joins the network already does.
 *
 * TBoxNetworkConnector matches a scan result with `equalsIgnoreCase`, so `cqky_1234` and
 * `CQKY_1234` are one network to everything below the garage. Matching them exactly here made
 * them two motorcycles, and the second one is worse than a duplicate: it is created with no
 * modelId and no override, so it resolves to the GENERIC EasyConn profile, which on a ThinkerRide
 * or Yunmo dash is not a milder answer but a broken one. Rider 2e3b10d2 has both entries for one
 * KOVE, the second sitting on GENERIC, waiting to be tapped.
 */
internal fun List<MotorcycleProfile>.bySsidIgnoringCase(ssid: String): MotorcycleProfile? =
    firstOrNull { it.ssid.equals(ssid, ignoreCase = true) }

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
