// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// CORE-only: runs the actual T-Box connection (Wi-Fi join + EasyConn discovery via the GPL
// hudlib transport) and installs the session, so a companion app (PRO) can trigger it over AIDL
// without containing any of this GPL code itself. Mirrors HubViewModel.connectAndDiscover()'s
// flow (UI-free). When the flavor split lands, this file moves to the core-only source set
// alongside RideDaemonTransport.
package io.motohub.android.ipc

import android.content.Context
import io.motohub.android.data.MotorcycleProfileStore
import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.session.ProjectionEventLog
import io.motohub.android.session.TBoxConnectionMode
import io.motohub.android.tbox.FormedP2pGroup
import io.motohub.android.tbox.ProfileOverride
import io.motohub.android.tbox.SelectingTBoxTransport
import io.motohub.android.tbox.TBoxCapabilityStore
import io.motohub.android.tbox.TBoxLinkResolver
import io.motohub.android.tbox.TBoxModelProfile
import io.motohub.android.tbox.TBoxProtocolMemory
import io.motohub.android.tbox.TBoxNetworkConnectors
import io.motohub.android.tbox.TBoxSessionHandle
import io.motohub.android.tbox.TBoxSessionRegistry

/**
 * Why the last AIDL connect failed, kept where the bridge can read it after [CoreTBoxConnector.connect]
 * has already answered its bare boolean.
 *
 * Process-wide rather than per-connector because the connector that failed may already have been
 * released by the time the caller asks - and because there is only ever one connect in flight
 * (CoreTBoxConnectors hands out one connector per SSID and the bridge blocks on it).
 */
internal object CoreConnectFailureRecord {
    @Volatile
    private var reason: String? = null

    @Volatile
    private var stage: Int = IpcBridgeContract.CONNECT_STAGE_UNKNOWN

    fun clear() {
        reason = null
        stage = IpcBridgeContract.CONNECT_STAGE_UNKNOWN
    }

    fun record(stage: Int, reason: String?) {
        this.stage = stage
        this.reason = reason?.takeIf { it.isNotBlank() }
    }

    fun reason(): String? = reason

    fun stage(): Int = stage
}

/** Establishes and tears down a T-Box session on behalf of an AIDL caller. */
class CoreTBoxConnector(private val context: Context) {

    // The process's one shared connector (see TBoxNetworkConnectors): an AIDL connect beside a
    // UI-established session used to put a second exclusive Wi-Fi request on the air.
    private val networkConnector = TBoxNetworkConnectors.shared(context)
    private val transport = SelectingTBoxTransport(context)
    private val capabilityStore = TBoxCapabilityStore(context)

    /**
     * The session THIS connector put in the registry, or null while it has none. Held as the
     * handle rather than a bare flag because every teardown here has to be able to name what it
     * owns: the registry's `clear()` with no argument ends whatever session happens to be
     * installed, which on this path is routinely somebody else's.
     */
    private var installedHandle: TBoxSessionHandle? = null

    /**
     * Whether an AIDL retry for [ssid] can keep using this connector instead of being handed a
     * fresh one. Only true before this connector has ever installed a session: once one is live,
     * calling [connect] again would re-run EasyConn discovery underneath an already-streaming
     * session, which nothing downstream expects. A connector that never got that far - still
     * mid Wi-Fi join, or one whose join already failed - is exactly what a retry should keep
     * using, so the WifiNetworkSpecifier hunt it holds does not get torn down and restarted.
     */
    fun isReusableFor(ssid: String): Boolean =
        installedHandle == null && networkConnector.isHuntingFor(ssid)

    /**
     * @param formedGroup set when the caller has already formed the Wi-Fi Direct group and is
     *   handing it over with its addresses; this process then adopts it instead of attempting a
     *   join the framework refuses a backgrounded Core anyway.
     */
    suspend fun connect(
        requested: MotorcycleProfile,
        formedGroup: FormedP2pGroup? = null
    ): Boolean {
        // A session CORE started for itself outlives the activity on purpose - a projection has to
        // survive the screen going away - and a companion app asking to connect in that moment used
        // to build a SECOND TBoxNetworkConnector beside it. Two exclusive WifiNetworkSpecifier
        // requests for the same SSID do not queue, they fight: each grant drops the other's network.
        // Field log 2026-07-31 (OnePlus CPH2653, EASYCONN_5G-F3116E): the rider left an Android Auto
        // session running, started the Ride Dashboard from the companion app, and got networks
        // 202 through 207 granted and lost within a second each, one dashboard frame, a broken pipe,
        // then eleven rejoin attempts refused by Android in 2-10ms before it gave up 3.5 minutes
        // later. Refusing here costs that rider one clear sentence instead.
        CoreConnectFailureRecord.clear()
        // Asked of the CONSUMERS, not of the connector identity. The first version of this guard
        // compared holder.networkConnector against ours, which can never differ: both come from
        // TBoxNetworkConnectors.shared(), one instance per process. So the refusal never fired -
        // in exactly the case it was written for. Field log adb68a95 (Pixel 7, KOVE 450 Rally,
        // 2026-08-31): the rider's Android Auto session was streaming to the TFT when a companion
        // connect walked straight past this guard, ran EasyConn discovery underneath it, and on
        // failing took the session's registry entry with it.
        val holder = TBoxSessionRegistry.current()
        val consumers = TBoxSessionRegistry.activeConsumersOtherThan(BRIDGE_SESSION_CONSUMER)
        if (holder != null && consumers.isNotEmpty()) {
            val refusal = "MOTO-HUB Core is already using this dash for $consumers. Stop that " +
                "session first, then connect again."
            ProjectionEventLog.error(
                "IPC_TBOX",
                "AIDL connect refused: MOTO-HUB Core is already using this dash for $consumers. " +
                    "Connecting again would re-run discovery underneath a session that is " +
                    "already streaming, and the dash only holds one. Stop that session first, " +
                    "then connect again."
            )
            CoreConnectFailureRecord.record(IpcBridgeContract.CONNECT_STAGE_REFUSED, refusal)
            return false
        }
        // Before the Wi-Fi join, not after: the completed connectionMode decides which transport
        // TBoxLinkResolver takes, so filling it in once the link is already up would only ever
        // half-fix the problem.
        val profile = requested.completedFrom(storedProfileFor(requested.ssid))
        if (profile != requested) {
            ProjectionEventLog.record(
                "PROFILE",
                "AIDL connect: the companion app knows this dash only as a network name. Core's " +
                    "own garage entry for ${profile.ssid} fills the rest in: " +
                    "modelId=${profile.modelId ?: "none"}, connectionMode=${profile.connectionMode}."
            )
        }
        TBoxNetworkConnectors.acquire(context, AIDL_NETWORK_OWNER)
        val connected = TBoxLinkResolver.connect(context, networkConnector, profile, formedGroup)
        val link = connected.getOrElse {
            // The lease is kept on a network failure: the specifier request deliberately
            // outlives its timeout (v1.1.17) and the next AIDL retry joins that hunt.
            ProjectionEventLog.error("IPC_TBOX", "AIDL connect: T-Box network connection failed.", it)
            CoreConnectFailureRecord.record(
                IpcBridgeContract.CONNECT_STAGE_NETWORK,
                it.message ?: "The phone could not reach the motorcycle's network."
            )
            return false
        }
        ProjectionEventLog.record("IPC_TBOX", "AIDL connect: T-Box link established (${link.label}).")
        val requestedOverride = ProfileOverride.byKey(profile.profileOverrideKey)
        ProjectionEventLog.record(
            "PROFILE",
            "AIDL connect: resolving protocol profile: modelId=${profile.modelId ?: "none"}, " +
                "override=${requestedOverride.key}, connectionMode=${profile.connectionMode}."
        )
        // A dash whose family we already learned is routed straight there. Discovery can answer
        // this, but only by letting EasyConn fail first, which costs two 15s NSD windows and the
        // wake probes before anything else is tried - the difference a rider sees between pinning
        // the profile by hand and leaving it on Auto. Only ever a shortcut: a pinned override wins,
        // and only non-EasyConn families are ever remembered.
        val learnedProfile = if (requestedOverride == ProfileOverride.AUTO) {
            TBoxProtocolMemory(context).learnedFamily(profile.ssid)
                ?.let { family -> TBoxModelProfile.entries.firstOrNull { it.transportFamily == family } }
        } else {
            null
        }
        val resolvedProfile = learnedProfile ?: TBoxModelProfile.resolve(profile.modelId, null, requestedOverride)
        learnedProfile?.let {
            ProjectionEventLog.record(
                "PROFILE",
                "This motorcycle was already seen speaking ${it.transportFamily}; going straight " +
                    "to that transport instead of letting EasyConn discovery time out first."
            )
        }
        transport.configureProtocolProfile(resolvedProfile, profile)
        val discovered = transport.discover(link, profile.modelId)
        val host = discovered.getOrElse {
            // Named after the transport that actually ran. Saying "EasyConn" whatever the family
            // was is not cosmetic: it is the first line a reader meets in a failing log, and on a
            // ThinkerRide or Yunmo bike it sends them looking for a fault in a stack that never
            // executed. Two of us lost the opening minutes of case 2e3b10d2 to exactly that.
            ProjectionEventLog.error(
                "IPC_TBOX",
                "AIDL connect: ${resolvedProfile.transportFamily} discovery failed.",
                it
            )
            // A dash that never answers because the packets never left the phone is not a
            // discovery problem, and saying "the dash did not answer" sends the rider to the
            // bike. When the process binding was refused with a VPN demonstrably holding the
            // route to the dash, that is the failure - reported as a network one, because it is.
            val routingDiagnosis = networkConnector.vpnRoutingDiagnosis()
            if (routingDiagnosis != null) {
                ProjectionEventLog.record(
                    "IPC_TBOX",
                    "AIDL connect: discovery had no route to the dash - the process binding was " +
                        "refused earlier and a VPN holds that route."
                )
                CoreConnectFailureRecord.record(IpcBridgeContract.CONNECT_STAGE_NETWORK, routingDiagnosis)
            } else {
                CoreConnectFailureRecord.record(
                    IpcBridgeContract.CONNECT_STAGE_DISCOVERY,
                    it.message ?: "The motorcycle did not answer on its own network."
                )
            }
            transport.stop()
            link.disconnect()
            // Deliberately NOT TBoxSessionRegistry.clear(): this attempt never reached install, so
            // it has nothing of its own in there, and the argument-less clear() ends whatever
            // session IS installed. That is how a failing companion connect used to disarm one of
            // Core's own running sessions - it dropped the session's interest in the shared
            // network connector, and the release() below then found itself the last holder and
            // took the Wi-Fi down under a live stream (field log adb68a95, 2026-08-31: registry
            // cleared 20:47:44, network dropped 20:49:05.755, dash video aborted 48ms later).
            TBoxNetworkConnectors.release(AIDL_NETWORK_OWNER)
            return false
        }
        // Record what discovery settled on, so the next ride skips the slow path. Read off the
        // switch itself and not off activeProtocolProfile, which now also carries a pin: what is
        // worth remembering is what the DASH answered unasked, never what the rider tried.
        transport.discoverySwitchedProfile?.let { discoveredProfile ->
            TBoxProtocolMemory(context).remember(profile.ssid, discoveredProfile.transportFamily)
        }
        capabilityStore.recordDiscovery(profile, host)
        val handle = TBoxSessionHandle(transport, host, networkConnector, profile, link)
        TBoxSessionRegistry.install(handle)
        installedHandle = handle
        ProjectionEventLog.record("IPC_TBOX", "AIDL connect: session installed; READY.")
        return true
    }

    /**
     * Cleanup for a cancelled connect(): unlike [disconnect] (which looks up the *registry's*
     * active handle), this tears down THIS connector's own transport/networkConnector directly —
     * needed because cancellation can land before TBoxSessionRegistry.install() ever ran, when
     * the registry wouldn't yet reference this attempt's (possibly half-open) link.
     */
    /**
     * Core's own garage entry for [ssid], or null when it has none. A blank SSID matches nothing:
     * a dash reached over Wi-Fi Direct or Bluetooth is keyed by id, and letting two blanks find
     * each other would hand one bike's model to another.
     */
    private fun storedProfileFor(ssid: String): MotorcycleProfile? {
        if (ssid.isBlank()) return null
        return runCatching { MotorcycleProfileStore(context).loadAll() }
            .getOrElse { emptyList() }
            .firstOrNull { it.ssid.equals(ssid, ignoreCase = true) }
    }

    suspend fun cancel() {
        transport.stop()
        // Only ever this connector's own session, for the reason spelled out on the failure
        // branch of connect(): a cancel that lands before install has nothing in the registry,
        // and clearing it regardless would end a session that belongs to somebody else.
        installedHandle?.let { TBoxSessionRegistry.clear(it) }
        TBoxNetworkConnectors.release(AIDL_NETWORK_OWNER)
    }

    suspend fun disconnect() = disconnectActiveSession()

    companion object {
        /** The AIDL bridge's name in [TBoxNetworkConnectors]' interest ledger. */
        private const val AIDL_NETWORK_OWNER = "aidl-bridge"

        /**
         * The bridge's name in [TBoxSessionRegistry]'s consumer list ([IpcBridgeService] claims
         * the session under it on the companion's behalf).
         *
         * Declared here, and used by the bridge from here, because [connect]'s refusal has to
         * tell the companion's own claim apart from a mode running inside Core - and a name only
         * one of the two files can see cannot answer that question.
         */
        const val BRIDGE_SESSION_CONSUMER = "companion-app"

        /**
         * Tears down whatever session the registry holds, whoever established it.
         *
         * Instance-independent by nature - it reads the registry, not this object - so it is
         * exposed here rather than forcing a caller to build a connector just to reach it.
         * Constructing one had a cost that was not obvious: every throwaway connector brought its
         * own [TBoxNetworkConnector][io.motohub.android.tbox.TBoxNetworkConnector] and its own
         * exclusive Wi-Fi request, so a disconnect could leave behind exactly the orphan it was
         * supposed to be clearing up.
         */
        suspend fun disconnectActiveSession() {
            val handle = TBoxSessionRegistry.current() ?: return
            handle.transport.stop()
            // No direct connector teardown: clear() releases the session's own lease in the
            // shared-connector ledger, and the bridge's lease goes with CoreTBoxConnectors.clear()
            // (its sole caller pairs the two). The network drops when the last of them is gone -
            // never out from under a lease the UI still holds.
            TBoxSessionRegistry.clear(handle)
        }
    }
}

/**
 * Fills in what a companion app could not know about this dashboard from Core's own garage entry
 * for the same network.
 *
 * MOTO-HUB has two garages - Core keeps one, the companion app keeps another - and only Core's
 * has ever been through a pairing that identifies the dash. A profile the rider typed into
 * ADVANCED by hand carries no [MotorcycleProfile.modelId] at all
 * ([io.motohub.android.feature.home.HubViewModel.saveMotorcycle] does not mint one), so a connect
 * arriving over the bridge resolved to the generic EasyConn profile even for a dash Core itself
 * routes over BLE. Field log adb68a95 (Pixel 7, KOVE 450 Rally, 2026-08-31): Core, asked directly,
 * logged `modelId=THINKERRIDE, connectionMode=THINKERRIDE` and was READY in two seconds; asked over
 * the bridge for the same SSID in the same minute, `modelId=none, connectionMode=AUTO` sent it
 * hunting for an `_EasyConn._tcp.` advertisement a ThinkerRide dash never makes - two 15s NSD
 * windows, three wake probes, an empty port sweep, six times over.
 *
 * Keyed on the SSID, for the reason [io.motohub.android.tbox.TBoxWireLadder.keyFor] already
 * documents: the profile id is a UUID minted per garage, so the two entries for one physical
 * dashboard never share it.
 *
 * Only ever fills blanks. A companion that pinned a profile has said something deliberate and is
 * left alone entirely, and a field it did populate is never overwritten - the credentials and the
 * choices belong to the caller.
 */
internal fun MotorcycleProfile.completedFrom(stored: MotorcycleProfile?): MotorcycleProfile {
    if (stored == null) return this
    if (ProfileOverride.byKey(profileOverrideKey) != ProfileOverride.AUTO) return this
    val knownModelId = modelId?.takeIf { it.isNotBlank() }
    val modeIsUnset = connectionMode == TBoxConnectionMode.AUTO
    if (knownModelId != null && !modeIsUnset) return this
    return copy(
        modelId = knownModelId ?: stored.modelId?.takeIf { it.isNotBlank() },
        connectionMode = if (modeIsUnset) stored.connectionMode else connectionMode
    )
}

/** Builds a MotorcycleProfile from an AIDL connect request (the caller owns these credentials). */
internal fun MotorcycleConnectRequest.toProfile(): MotorcycleProfile = MotorcycleProfile(
    ssid = ssid,
    password = password,
    id = id,
    modelId = modelId,
    displayName = displayName,
    profileOverrideKey = profileOverrideKey,
    connectionMode = runCatching { TBoxConnectionMode.valueOf(connectionMode) }
        .getOrDefault(TBoxConnectionMode.AUTO)
)
