// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log

/**
 * Binds to Core's IpcBridgeService for T-Box transport access. The caller's OWN manifest must
 * still declare (neither can be enforced from here — omitting either fails differently):
 *   <queries><package android:name="io.motohub.android"/></queries>            (or bindService() silently returns false)
 *   <uses-permission android:name="io.motohub.android.permission.BIND_CORE_SERVICE"/>  (or bindService() throws SecurityException)
 */
class TBoxTransportClient(
    private val context: Context,
    private val corePackage: String = "io.motohub.android",
    private val onSessionReady: () -> Unit = {},
    private val onSessionLost: () -> Unit = {},
    /**
     * Something Core's transport wants the rider told mid-session; see
     * ITBoxSessionListener.onTransportNotice. Arrives on a Binder thread, so a handler that
     * touches UI state has to hop.
     */
    private val onTransportNotice: (String) -> Unit = {}
) {
    @Volatile
    private var service: ITBoxTransportService? = null
    private var bound = false

    private val sessionListener = object : ITBoxSessionListener.Stub() {
        override fun onSessionReady() = this@TBoxTransportClient.onSessionReady()
        override fun onSessionLost() = this@TBoxTransportClient.onSessionLost()
        override fun onTransportNotice(message: String?) {
            message?.takeIf { it.isNotBlank() }?.let { this@TBoxTransportClient.onTransportNotice(it) }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val bound = ITBoxTransportService.Stub.asInterface(binder)
            service = bound
            bound.registerSessionListener(sessionListener)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    /** Returns false if the bind call itself failed (see manifest requirements above). */
    fun bind(): Boolean {
        val intent = Intent(IpcBridgeContract.BIND_ACTION_TBOX_TRANSPORT).apply {
            setPackage(corePackage)
        }
        return try {
            // See the identical flag on AndroidAutoReceiverClient.bind() - Pro and Core share one
            // process-wide background-activity-launch exemption while Pro stays bound, needed for
            // RideDashboardTrampolineActivity regardless of which client happens to be bound when
            // Core's IpcBridgeService is asked to promote the dashboard to a location-typed FGS.
            val ok = context.bindService(
                intent,
                connection,
                Context.BIND_AUTO_CREATE or Context.BIND_ALLOW_ACTIVITY_STARTS
            )
            bound = ok
            ok
        } catch (e: SecurityException) {
            Log.e(TAG, "bind denied - is BIND_CORE_SERVICE declared in the caller's manifest? ${e.message}")
            false
        }
    }

    fun unbind() {
        if (!bound) return
        runCatching { service?.unregisterSessionListener(sessionListener) }
        runCatching { context.unbindService(connection) }
        service = null
        bound = false
    }

    /**
     * Runs one call against the bound Core, or answers null when Core is not there any more.
     *
     * Every method below crosses into a SEPARATE PROCESS that Android kills whenever it likes -
     * on rider a7cda9d1's Redmi, HyperOS's "OneKeyClean" killed one half or the other ten times
     * in twenty days. A transaction into a corpse throws DeadObjectException, and [connect] was
     * making that call bare from a coroutine on Dispatchers.Main.immediate with no catch above
     * it: on 2026-08-25 05:54:48 the rider closed the app while a connect was in flight and
     * ADVANCED 1.1.90 died with it. Losing Core has to read as "Core is not there", never as a
     * crash.
     *
     * [isConnected] cannot be trusted as a pre-check for this - the binder can die in the gap
     * between the two - so the reference is dropped HERE. onServiceDisconnected() says the same
     * thing, but later and only on the main looper; without this the next caller transacts into
     * the same corpse instead of re-binding.
     */
    private fun <T> onCore(what: String, call: (ITBoxTransportService) -> T): T? {
        val live = service ?: return null
        return try {
            call(live)
        } catch (dead: RemoteException) {
            if (service === live) service = null
            Log.w(TAG, "Core died during $what: $dead")
            null
        }
    }

    /** True once the bound-service connection has actually been established (bind() is async). */
    val isConnected: Boolean get() = service != null

    fun isSessionReady(): Boolean = onCore("isSessionReady") { it.isSessionReady() } ?: false

    fun getActiveMotorcycle(): MotorcycleSummary? = onCore("getActiveMotorcycle") { it.getActiveMotorcycle() }

    /**
     * Asks Core to start the T-Box video session (EasyConn video + TFT area negotiation) and
     * returns the negotiated capture area, or null if it failed / the service isn't bound.
     * Must succeed before offerAccessUnit() delivers any frames.
     */
    fun startVideoSession(): EncoderProfileParcel? = onCore("startVideoSession") { it.startVideoSession() }

    /** Opens the high-rate video data plane; null means the older Binder-only Core is in use. */
    fun openVideoStream(): ParcelFileDescriptor? = onCore("openVideoStream") { it.openVideoStream() }

    fun closeVideoStream() {
        onCore("closeVideoStream") { it.closeVideoStream() }
    }

    fun offerAccessUnit(accessUnit: ByteArray): Boolean =
        onCore("offerAccessUnit") { it.offerAccessUnit(accessUnit) } ?: false

    /**
     * Whether the live session's dash wants JPEG stills instead of H.264. Only ask a Core at
     * [IpcBridgeContract.CONTRACT_VERSION_VIDEO_STILLS] or later; an older one does not implement
     * the call, and its answer - false - is right for it anyway, since it has no still path.
     */
    fun videoWantsStills(): Boolean =
        runCatching { service?.videoWantsStills() }.getOrNull() ?: false

    /** Asks Core to establish the T-Box connection in its own process (it owns the GPL transport). */
    fun connect(request: MotorcycleConnectRequest): Boolean =
        onCore("connect") { it.connect(request) } ?: false

    /**
     * Which revision of the contract the bound Core implements, or 0 when it is not bound or is
     * old enough not to know the call at all. Gate an appended call on this rather than calling
     * it and reading the answer: a dead transaction returns the same `false` a real refusal does.
     */
    fun contractVersion(): Int = runCatching { service?.getContractVersion() }.getOrNull() ?: 0

    /**
     * Tells Core to forget its own garage entry for [ssid], because this app's rider just deleted
     * or re-paired that motorcycle here.
     *
     * Returns false when the bound Core predates
     * [IpcBridgeContract.CONTRACT_VERSION_FORGET_MOTORCYCLE], or when nothing is bound at all -
     * i.e. "Core may still hold an entry", which is a thing the caller has to be able to say out
     * loud. It does NOT mean an entry existed and survived: a Core new enough to take the call
     * answers the same way whether it deleted a row or had none, and neither is a failure.
     *
     * Gated on the version rather than called blind for the reason [contractVersion] gives: a
     * dead transaction is indistinguishable from a Core that ran the call and found nothing.
     */
    fun forgetMotorcycle(ssid: String): Boolean {
        if (ssid.isBlank()) return false
        if (contractVersion() < IpcBridgeContract.CONTRACT_VERSION_FORGET_MOTORCYCLE) return false
        return runCatching { service?.forgetMotorcycle(ssid) }.isSuccess && service != null
    }

    /**
     * Hands Core a Wi-Fi Direct group THIS process formed, with the addresses already resolved
     * here - see ITBoxTransportService.aidl for why Core cannot resolve them itself. Only call
     * it when [contractVersion] is at least [IpcBridgeContract.CONTRACT_VERSION_FORMED_GROUP];
     * an older Core answers false without ever running a connect.
     */
    fun connectOverFormedGroup(
        request: MotorcycleConnectRequest,
        localIpv4: String,
        groupOwnerIpv4: String
    ): Boolean =
        runCatching { service?.connectOverFormedGroup(request, localIpv4, groupOwnerIpv4) }
            .getOrNull() ?: false

    /**
     * Why Core's last connect failed, as Core would put it to a rider, or null when Core is not
     * bound, succeeded, or is too old to answer. Only meaningful right after a false [connect] /
     * [connectOverFormedGroup]; nothing here clears it.
     */
    fun lastConnectFailure(): String? =
        runCatching { service?.getLastConnectFailure() }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * Which half of the connect failed, as one of `IpcBridgeContract.CONNECT_STAGE_*`.
     * [IpcBridgeContract.CONNECT_STAGE_UNKNOWN] when unbound or on a Core older than
     * [IpcBridgeContract.CONTRACT_VERSION_CONNECT_FAILURE_REASON]. The two are indistinguishable
     * on purpose: both mean "this app cannot tell", and both leave the caller on the behaviour it
     * had before this call existed rather than inventing a stage.
     */
    fun lastConnectFailureStage(): Int =
        runCatching { service?.getLastConnectFailureStage() }.getOrNull()
            ?: IpcBridgeContract.CONNECT_STAGE_UNKNOWN

    /**
     * The key of the profile Core's transport switched to during discovery, or null when it
     * switched to none, no session is active, Core is not bound, or Core predates
     * [IpcBridgeContract.CONTRACT_VERSION_ACTIVE_PROFILE].
     *
     * Every one of those answers null on purpose: they all mean "this app resolves the profile
     * itself", which is exactly the behaviour that existed before this call.
     */
    fun activeProfileKey(): String? =
        runCatching { service?.getActiveProfileKey() }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * Core's wire-ladder state for one motorcycle, as the JSON Core stores, or null when Core has
     * never walked the ladder for that bike, is not bound, or predates
     * [IpcBridgeContract.CONTRACT_VERSION_WIRE_LADDER].
     *
     * Only Core's copy is worth reporting: the ladder is walked there, and the identical
     * preferences file in this process is written by nobody.
     */
    fun wireLadderProgress(motorcycleId: String): String? =
        runCatching { service?.getWireLadderProgress(motorcycleId) }.getOrNull()
            ?.takeIf { it.isNotBlank() }

    /**
     * One motorcycle's CLIENT_INFO capabilities, as the JSON Core stores, or null when Core has
     * never seen CLIENT_INFO for that bike, is not bound, or predates
     * [IpcBridgeContract.CONTRACT_VERSION_ACTIVE_CAPABILITIES].
     *
     * Only Core's copy exists: the dash says CLIENT_INFO on the EasyConn command socket, which is
     * Core's, and the identical preferences file in this process is written by nobody whenever
     * Core owns the link. Null therefore means "keep resolving from the model id alone", which is
     * what this app did for every dash before this call.
     */
    fun capabilitiesJson(motorcycleId: String): String? =
        onCore("capabilitiesJson") { it.getCapabilitiesJson(motorcycleId) }?.takeIf { it.isNotBlank() }

    /**
     * The screen margins Core holds for [ssid] as "top,bottom,left,right", or null when Core was
     * never taught them, is not bound, or predates
     * [IpcBridgeContract.CONTRACT_VERSION_SCREEN_MARGINS].
     *
     * Every one of those nulls means the same thing - "nothing to adopt, frame it as before" -
     * which is exactly the behaviour that existed before this call.
     */
    fun screenMargins(ssid: String): String? =
        onCore("screenMargins") { it.getScreenMargins(ssid) }?.takeIf { it.isNotBlank() }

    /**
     * Why Core's last [startVideoSession] answered null, as Core would put it to a rider, or null
     * when Core is not bound, the call succeeded, or Core predates
     * [IpcBridgeContract.CONTRACT_VERSION_VIDEO_FAILURE_REASON].
     *
     * Null is "this app has nothing better to say", which leaves the caller on the summary it
     * printed before this call existed - never a blank banner.
     */
    fun lastVideoSessionFailure(): String? =
        runCatching { service?.getLastVideoSessionFailure() }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Aborts an in-flight connect() on Core's side; see ITBoxTransportService.aidl. */
    fun cancelConnect() {
        onCore("cancelConnect") { it.cancelConnect() }
    }

    fun disconnect() {
        onCore("disconnect") { it.disconnect() }
    }

    /**
     * Read-only snapshot of Core's diagnostic log, or null when the service is not bound,
     * the log is empty, or an older Core predates the method (the dead transaction surfaces
     * here as an exception, caught like openVideoStream()'s "older Binder-only Core" case).
     */
    fun openDiagnosticLogSnapshot(): ParcelFileDescriptor? =
        runCatching { service?.openDiagnosticLogSnapshot() }.getOrNull()

    /**
     * Core's port scan of the ACTIVE session's dash, as the JSON described in the AIDL, or null
     * when no session is installed, the service is not bound, or Core predates
     * [IpcBridgeContract.CONTRACT_VERSION_PORT_SCAN].
     *
     * Blocking on the caller's thread for as long as the probes take - call it off the main
     * thread, like [startVideoSession].
     */
    fun scanTBoxPorts(): String? =
        onCore("scanTBoxPorts") { it.scanTBoxPorts() }?.takeIf { it.isNotBlank() }

    /**
     * Core's verdict on a session that connected and is not reaching the dashboard, or null when
     * there is nothing to report, the service is not bound, or Core predates
     * [IpcBridgeContract.CONTRACT_VERSION_DELIVERY_REPORT].
     *
     * Parsed here rather than by every caller so the wire format stays this file's business.
     * A malformed answer is treated as no answer: this drives an offer to the rider, and an
     * offer built on a field that failed to parse is worse than the silence that came before it.
     */
    fun dashboardDeliveryReport(): DashboardDelivery? =
        runCatching { service?.getDashboardDeliveryReport() }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let(DashboardDelivery::parse)

    /**
     * Whether CORE holds BLUETOOTH_CONNECT - true, false, or null for "cannot be known".
     *
     * Three-valued on purpose. The handlebar of an Android Auto session is decoded in Core, so
     * Core's grant is the one that decides whether a press can arrive, and it is invisible from
     * this side: this process checks its own, finds it, and reports a handlebar that will never
     * work (rider 315e0af3, every Android Auto session of every report between 2026-08-24 and
     * 08-26). But an unbound service and a Core older than
     * [IpcBridgeContract.CONTRACT_VERSION_CORE_BLUETOOTH] both answer the dead transaction's
     * empty parcel, which reads as false - and a caller that took that literally would tell a
     * rider who granted the permission long ago to go and grant it. Null keeps those two cases
     * out of the advice.
     */
    fun coreHoldsHandlebarBluetoothPermission(): Boolean? {
        if (service == null) return null
        if (contractVersion() < IpcBridgeContract.CONTRACT_VERSION_CORE_BLUETOOTH) return null
        return onCore("holdsHandlebarBluetoothPermission") { it.holdsHandlebarBluetoothPermission() }
    }

    /**
     * How Core's own handlebar is configured - null for "cannot be known", never for "nothing".
     *
     * Three-valued for the same reason as [coreHoldsHandlebarBluetoothPermission], and parsed
     * here so the wire format stays this file's business. A malformed answer is no answer: this
     * ends up in a diagnostics report as a statement about the rider's phone, and a field that
     * defaulted rather than failed would send an investigation somewhere the rider never was.
     */
    fun coreHandlebarState(): HandlebarState? {
        if (service == null) return null
        if (contractVersion() < IpcBridgeContract.CONTRACT_VERSION_HANDLEBAR_STATE) return null
        return onCore("getHandlebarState") { it.getHandlebarState() }
            ?.takeIf { it.isNotBlank() }
            ?.let(HandlebarState::parse)
    }

    /** True when the clear reached Core; false when unbound or Core predates the method. */
    fun clearDiagnosticLog(): Boolean =
        runCatching { service?.also { it.clearDiagnosticLog() } != null }.getOrDefault(false)

    private companion object {
        const val TAG = "TBoxTransportClient"
    }
}
