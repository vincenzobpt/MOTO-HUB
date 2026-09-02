// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// Public, GPL-3.0/AGPL-3.0-licensed bridge: exposes Core's T-Box transport (ridedaemon-lib,
// GPL-3.0) and Android Auto AAP receiver (aa/, AGPL-3.0 technique ported from headunit-revived)
// to another app's process over Binder IPC, so a closed-source companion app can use both
// without linking this code into its own binary. See the "Core/Pro split" note in
// documentation/ARCHITECTURE.md for why this boundary exists.
package io.motohub.android.ipc

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteCallbackList
import android.os.SystemClock
import android.view.Surface
import androidx.core.app.NotificationCompat
import io.motohub.android.R
import io.motohub.android.androidauto.AaInputBridge
import io.motohub.android.aa.AaNavigationGuidance
import io.motohub.android.aa.AaReceiver
import io.motohub.android.aa.AaSelfMode
import io.motohub.android.aa.SingleKeyKeyManager
import io.motohub.android.androidauto.AaCompositor
import io.motohub.android.androidauto.AndroidAutoCapabilityProfiles
import io.motohub.android.androidauto.AndroidAutoDisplayMode
import io.motohub.android.androidauto.AndroidAutoDisplayModeStore
import io.motohub.android.androidauto.AndroidAutoPreviewRuntime
import io.motohub.android.androidauto.AndroidAutoReceiverOwnership
import io.motohub.android.androidauto.AndroidAutoRuntime
import io.motohub.android.androidauto.AndroidAutoRuntimeState
import io.motohub.android.androidauto.AndroidAutoNightModeStore
import io.motohub.android.androidauto.companionAutoRecovery
import io.motohub.android.androidauto.AndroidAutoSessionService
import io.motohub.android.androidauto.TBoxScreenMargins
import io.motohub.android.androidauto.TBoxScreenMarginsStore
import io.motohub.android.androidauto.encodeScreenMargins
import io.motohub.android.androidauto.withFullVideoTarget
import io.motohub.android.encoding.VideoBackpressureGuard
import io.motohub.android.feature.settings.AndroidAutoAspectMatchingMode
import io.motohub.android.feature.settings.AndroidAutoDensityMode
import io.motohub.android.feature.settings.AndroidAutoResolutionMode
import io.motohub.android.feature.settings.MotoHubSettings
import io.motohub.android.feature.settings.VideoQuality
import io.motohub.android.encoding.VideoDeliveryProbe
import io.motohub.android.session.DashboardDeliveryMonitor
import io.motohub.android.session.ProjectionEventLog
import io.motohub.android.tbox.FormedP2pGroup
import io.motohub.android.tbox.ProfileOverride
import io.motohub.android.tbox.TBoxEvent
import io.motohub.android.tbox.TBoxModelProfile
import io.motohub.android.tbox.TBoxPortScanner
import io.motohub.android.tbox.TBoxSessionHandle
import io.motohub.android.tbox.TBoxTransport
import io.motohub.android.tbox.TBoxCapabilityStore
import io.motohub.android.tbox.TBoxWireLadder
import io.motohub.android.tbox.SelectingTBoxTransport
import io.motohub.android.tbox.TBoxSessionRegistry
import io.motohub.android.tbox.TBoxVpnDiagnostics
import io.motohub.android.tbox.negotiateVideoConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException

class IpcBridgeService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val videoStreamLock = Any()
    @Volatile private var videoStreamInput: ParcelFileDescriptor? = null
    @Volatile private var videoStreamJob: Job? = null
    @Volatile private var transportWatchJob: Job? = null

    // ── T-Box transport ──────────────────────────────────────────────

    private val sessionListeners = RemoteCallbackList<ITBoxSessionListener>()
    private var sessionPollJob: Job? = null
    @Volatile private var lastKnownHandle: TBoxSessionHandle? = null
    @Volatile private var activeConnect: Pair<CoreTBoxConnector, Deferred<Boolean>>? = null

    /**
     * Why the last [ITBoxTransportService.startVideoSession] answered null, kept for the caller
     * to read afterwards. The call can only say "no parcel", and the sentence explaining it used
     * to live nowhere but this log - so a companion app printed its own EasyConn-shaped summary
     * over a ThinkerRide dash that was simply waiting for the rider to press UP on it.
     *
     * A plain field rather than a process-wide record like [CoreConnectFailureRecord]: the whole
     * negotiation happens inside this service, so there is no second class to reach it from.
     *
     * NOT named after the AIDL call it answers: Kotlin sees the Stub's `getLastVideoSessionFailure()`
     * as a read-only synthetic property, which inside the binder object would shadow a field of
     * that name and turn every assignment below into "'val' cannot be reassigned".
     */
    @Volatile private var videoSessionFailureReason: String? = null

    /**
     * A VPN's own sentence for a video-negotiation failure it caused, or null when nothing points
     * at one.
     *
     * Here rather than inside the transport because the transport is the GPL library and knows
     * only about sockets; the routing table and the tunnel's capabilities are Android's, and this
     * is the nearest place to the failure that can see both.
     */
    private fun vpnDiagnosisFor(failure: Throwable, handle: TBoxSessionHandle): String? {
        if (!TBoxVpnDiagnostics.isVpnBindBlocked(failure)) return null
        val connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return null
        val dashAddress = runCatching {
            java.net.InetAddress.getByName(handle.host.ipAddress)
        }.getOrNull()
        return TBoxVpnDiagnostics.userFacingMessage(
            failure,
            TBoxVpnDiagnostics.inspect(connectivityManager, dashAddress)
        )
    }

    private val tboxTransportBinder = object : ITBoxTransportService.Stub() {
        override fun isSessionReady(): Boolean = TBoxSessionRegistry.current() != null

        override fun getActiveMotorcycle(): MotorcycleSummary? =
            TBoxSessionRegistry.current()?.motorcycle?.let { profile ->
                MotorcycleSummary(
                    id = profile.id,
                    ssid = profile.ssid,
                    modelId = profile.modelId,
                    displayName = profile.displayName
                )
            }

        // TBoxSessionHandle doesn't carry a negotiated encoder profile today — that negotiation
        // happens per session-start inside each mode's own startCapture(), not as a queryable
        // property. Phase 2 (once a caller actually needs this) wires the real value through.
        override fun getNegotiatedEncoderProfile(): EncoderProfileParcel? = null

        // Runs the same EasyConn video start + live TFT-area negotiation Core's own
        // ProjectionSessionService does, but on behalf of a companion app that can't contain the
        // GPL transport. Blocking on the binder thread; the returned width/height are the raw TFT
        // area (the caller derives its own encoder profile/bitrate from it). offerAccessUnit()
        // starts delivering frames only after this returns non-null.
        override fun startVideoSession(): EncoderProfileParcel? =
            kotlinx.coroutines.runBlocking {
                closeVideoStreamPipe()
                videoSessionFailureReason = null
                var handle = TBoxSessionRegistry.current() ?: run {
                    videoSessionFailureReason =
                        "MOTO-HUB Core has no T-Box session open. Connect to the motorcycle again."
                    return@runBlocking null
                }
                val fallbackArea = TBoxModelProfile.fallbackVideoArea(
                    handle.motorcycle.modelId,
                    null,
                    ProfileOverride.byKey(handle.motorcycle.profileOverrideKey)
                )
                // For as long as this call blocks - up to 75s on a ThinkerRide dash - the
                // transport's notices are the only thing that can tell the rider the wait is now
                // on him. Core's own modes collect these, but none of them is running when a
                // companion app owns the session, so relay them across for the whole window and
                // stop when the answer is in. The transport object survives the re-discovery
                // below (handle.copy keeps it), so one collector covers both attempts.
                val noticeRelay = relayTransportNoticesFor(handle.transport)
                try {
                    var result = handle.transport.negotiateVideoConfiguration(
                        host = handle.host,
                        savedArea = null,
                        fallbackArea = fallbackArea,
                        videoAreaTimeoutMillis = VIDEO_AREA_TIMEOUT_MS
                    )
                    if (result.isFailure) {
                        // NOT a timing issue - RideDaemonTransport.stop() (called whenever ANY mode's
                        // session ends, e.g. Android Auto) fully tears down the underlying
                        // MobileSession (session = null), so a bare retry of start(host) fails
                        // identically every time with "Call discover() with an active T-Box link
                        // before starting the session". A rider's manual "Connect" again works only
                        // because it re-runs discover() from scratch - do that here instead of a
                        // pointless delayed retry of the exact same broken call.
                        ProjectionEventLog.warning(
                            "IPC_TBOX",
                            "startVideoSession negotiation failed (first attempt): " +
                                "${result.exceptionOrNull()?.message}. Re-discovering the T-Box before retrying."
                        )
                        val rediscovered = handle.transport.discover(handle.link, handle.motorcycle.modelId)
                        val freshHost = rediscovered.getOrNull()
                        if (freshHost == null) {
                            ProjectionEventLog.warning(
                                "IPC_TBOX",
                                "Re-discovery failed: ${rediscovered.exceptionOrNull()?.message}"
                            )
                        } else {
                            handle = handle.copy(host = freshHost)
                            TBoxSessionRegistry.install(handle)
                            result = handle.transport.negotiateVideoConfiguration(
                                host = handle.host,
                                savedArea = null,
                                fallbackArea = fallbackArea,
                                videoAreaTimeoutMillis = VIDEO_AREA_TIMEOUT_MS
                            )
                        }
                    }
                    val configuration = result.getOrElse {
                        ProjectionEventLog.warning(
                            "IPC_TBOX",
                            "startVideoSession negotiation failed: ${it.message}"
                        )
                        // A VPN in lockdown gets named before anything else, because the
                        // transport cannot name it: all it saw was a socket it was not allowed to
                        // open, and "Binding socket to network 245 failed: EPERM" is what a rider
                        // was shown four times in 53 seconds on 2026-08-26. The connect path has
                        // had this translation since 2026-08-15 and this one never did - and this
                        // is the one that fails, because connect() itself succeeds: Android grants
                        // the Wi-Fi, mDNS finds the dash, the session installs READY, and only
                        // then does the first real socket hit the refusal.
                        videoSessionFailureReason = vpnDiagnosisFor(it, handle)
                            ?: it.message?.takeIf { message -> message.isNotBlank() }
                            ?: "The motorcycle did not open its video channel."
                        return@runBlocking null
                    }
                    val area = configuration.rawArea
                    ProjectionEventLog.record(
                        "IPC_TBOX",
                        "Video session started for a companion app; TFT area ${area.width}x${area.height} " +
                            "(source=${configuration.source})."
                    )
                    EncoderProfileParcel(
                        width = area.width,
                        height = area.height,
                        frameRate = 30,
                        bitRate = 2_500_000,
                        usedFallback = configuration.source == io.motohub.android.tbox.TBoxVideoAreaSource.FALLBACK
                    )
                } finally {
                    noticeRelay.cancel()
                }
            }

        override fun offerAccessUnit(accessUnit: ByteArray): Boolean =
            TBoxSessionRegistry.current()?.transport?.offerAccessUnit(accessUnit) ?: false

        /**
         * Whether the active session's dash wants JPEG stills instead of an H.264 stream.
         * Answered from [activeSessionProfile], so a dash discovered as Yunmo is judged by the
         * profile the transport settled on rather than the one its model id resolves to.
         */
        override fun videoWantsStills(): Boolean = activeSessionProfile()?.yunmoJpegVideo ?: false

        /**
         * Names the profile only when DISCOVERY changed it - never the one the motorcycle would
         * resolve to anyway.
         *
         * That difference is the whole call. A dash that answered Yunmo on :8200 after EasyConn
         * found nothing runs a profile nothing on the caller's side can arrive at, and the caller
         * reads frame rate, bitrate, keyframe policy, touch policy and margins off whatever it
         * resolved: for rider 315e0af3 (2026-08-24) that was the generic profile's 30fps and 1s
         * GOP into a transport whose send window holds three frames, and the Ride Dashboard died
         * every ten seconds while Core's own Android Auto - which reads this same property
         * in-process - was fine.
         *
         * Answering null in every other case is deliberate, not laziness. This service has no
         * capability store, so its own resolve() is blind to the CLIENT_INFO scoring that
         * identifies a dash with no matching model id. Handing that weaker answer over would
         * overwrite a caller's better one - trading one wrong profile for another. Null means
         * "nothing to correct", exactly as it does on the property this exposes.
         */
        override fun getActiveProfileKey(): String? =
            TBoxSessionRegistry.current()?.transport?.activeProtocolProfile?.key

        /**
         * The profile of the ACTIVE session, with the same precedence the in-process session
         * services use: the transport's own profile wins when discovery changed it - a dash that
         * answered Yunmo after EasyConn found nothing is not the profile the saved motorcycle
         * resolves to. Capabilities are not consulted because this service does not own the
         * capability store; a profile reachable only by capability scoring is also reachable by
         * model id or by the rider's explicit override.
         */
        private fun activeSessionProfile(): TBoxModelProfile? {
            val handle = TBoxSessionRegistry.current() ?: return null
            return handle.transport.activeProtocolProfile ?: TBoxModelProfile.resolve(
                handle.motorcycle.modelId,
                null,
                ProfileOverride.byKey(handle.motorcycle.profileOverrideKey)
            )
        }

        // Runs Core's own GPL connect flow (hudlib) on behalf of a companion app that can't
        // contain it. Blocking on the binder thread until READY, mirroring the AIDL contract.
        // Launched as a cancellable Deferred (not a bare runBlocking body) so a concurrent
        // cancelConnect() call — arriving on a DIFFERENT binder thread — can actually interrupt
        // it instead of this call only ever returning once the connect attempt times out on
        // its own. See cancelConnect() below.
        //
        // The connector comes from CoreTBoxConnectors rather than being built here: a connector
        // owns an exclusive WifiNetworkSpecifier request, and a second live one fights the first
        // for the association. Building one per call left an orphan behind on every reconnect.
        // CoreTBoxConnectors also reuses the previous connector across retries for the same SSID
        // instead of tearing it down, so a still-pending Wi-Fi hunt survives a retry.
        override fun connect(request: MotorcycleConnectRequest): Boolean = runConnect(request, null)

        override fun getContractVersion(): Int = IpcBridgeContract.CONTRACT_VERSION

        /**
         * This app's own BLUETOOTH_CONNECT grant, which is the one that decides whether an
         * Android Auto handlebar works at all: the AVRCP bridge that decodes the presses runs
         * here, and [MediaButtonBridge] declines to take the media volume and audio focus
         * without it.
         *
         * The companion app cannot see this. It checks the grant of ITS package, finds it, and
         * shows a handlebar that is ready - while every session logs "capture skipped: Bluetooth
         * is off or unavailable to this app" over here. Rider 315e0af3 lived on both sides of
         * that sentence for three days.
         *
         * Read live rather than cached: the rider may be answering the request this very second,
         * in this app, because the companion app asked them to.
         */
        override fun holdsHandlebarBluetoothPermission(): Boolean =
            io.motohub.android.feature.controls.BluetoothStatus.hasConnectPermission(
                this@IpcBridgeService
            )

        /**
         * What this app would do with a press that arrives - the other half of the permission
         * above, and just as invisible from the companion app.
         *
         * Read live, and for the motorcycle that is active HERE: the settings parcel the
         * companion pushes is applied to these same stores, so a mismatch between what it sent
         * and what this returns is itself the finding.
         */
        override fun getHandlebarState(): String =
            io.motohub.android.feature.controls.currentHandlebarState(this@IpcBridgeService)
                .encode()

        // Read after a false connect(), so the caller can put Core's own reason in front of its
        // rider instead of "Core failed to connect to the T-Box" plus whichever help it happens
        // to have. Not cleared here: the caller may ask more than once (log it, then show it).
        override fun getLastConnectFailure(): String? = CoreConnectFailureRecord.reason()

        override fun getLastConnectFailureStage(): Int = CoreConnectFailureRecord.stage()

        // The same courtesy one call further on: read after a null startVideoSession(). Not
        // cleared here either - the caller logs it and then shows it, which is two reads.
        override fun getLastVideoSessionFailure(): String? = videoSessionFailureReason

        // Verbatim, not re-encoded: the companion app runs the same TBoxWireLadder parser on the
        // other side, so anything this end can store, that end can read.
        //
        // The argument is a store key, not necessarily an id: since
        // IpcBridgeContract.CONTRACT_VERSION_WIRE_LADDER_BY_SSID a caller asks by the
        // motorcycle's network name, because a profile id belongs to one garage and there are
        // two. An older companion still asks by id and gets the pre-migration record, which
        // TBoxWireLadder.load copies rather than moves for exactly that reason.
        override fun getWireLadderProgress(storeKey: String?): String? =
            storeKey?.takeIf { it.isNotBlank() }
                ?.let { TBoxWireLadder.storedProgress(this@IpcBridgeService, it) }

        // Verbatim for the same reason, and read from the store rather than from the live
        // session: the caller that needs this most is a diagnostics report written with nothing
        // connected, and a dash only says CLIENT_INFO once per session anyway.
        override fun getCapabilitiesJson(motorcycleId: String?): String? =
            motorcycleId?.takeIf { it.isNotBlank() }
                ?.let { TBoxCapabilityStore(this@IpcBridgeService).encodedCapabilities(it) }

        // loadStoredBySsid, not load: only what the rider actually taught here may travel. load()
        // folds "nothing saved" into the model profile's defaults, and a default handed across
        // this boundary is indistinguishable from a teaching once it lands - which is how a
        // calibration gets overwritten by a value nobody ever entered.
        override fun getScreenMargins(ssid: String?): String? =
            ssid?.takeIf { it.isNotBlank() }
                ?.let {
                    encodeScreenMargins(
                        TBoxScreenMarginsStore(this@IpcBridgeService).loadStoredBySsid(it)
                    )
                }

        /**
         * The latched verdict, flattened for the wire. Null while nothing has been concluded,
         * which is the answer for every healthy session there is.
         *
         * Read from the process-wide monitor rather than from a field here because the judgement
         * is made on three different paths - Core's own Android Auto, Core's own mirroring, and
         * the companion's frames arriving on the video pipe - and only one of them runs inside
         * this service.
         */
        override fun getDashboardDeliveryReport(): String? =
            DashboardDeliveryMonitor.current.value?.let { report ->
                // The network name goes LAST, and that is not cosmetic: an SSID may legally
                // contain the separator, the other fields cannot, so a reader splitting with a
                // fixed limit always recovers the name whole however odd it is.
                listOf(
                    if (report.healthy) "ok" else "failing",
                    report.rejected.toString(),
                    report.accepted.toString(),
                    report.profileKey,
                    report.ssid
                ).joinToString("|")
            }

        /**
         * Probes the dash's ports over the session's OWN link, which is why this belongs here at
         * all: the caller cannot do it. Only one process holds the T-Box network, and when a
         * companion drives the connect that process is this one - so the companion's scanner had
         * no socket, no peer address, and no way to recognise its own motorcycle, and refused
         * every scan a rider ran while connected (field log 7efdfa33, 2026-08-25).
         *
         * Nothing is requested, joined or torn down: the link belongs to the session and outlives
         * this call untouched, so a scan during a ride costs the ride nothing.
         */
        override fun scanTBoxPorts(): String? {
            val handle = TBoxSessionRegistry.current() ?: return null
            val peerIp = handle.host.ipAddress.takeIf { it.isNotBlank() } ?: return null
            return runCatching {
                kotlinx.coroutines.runBlocking {
                    TBoxPortScanner.encode(TBoxPortScanner.scanOverLink(handle.link, peerIp))
                }
            }.getOrElse { failure ->
                ProjectionEventLog.warning(
                    "DIAGNOSTICS",
                    "Port scan requested by the companion app failed.",
                    failure
                )
                null
            }
        }

        // The caller formed the Wi-Fi Direct group in its own process and passes the addresses it
        // resolved there, because this one cannot resolve them for a group it did not form. Bad
        // addresses are refused here rather than deep in the connect: a caller that cannot say
        // where the group is has not really handed one over.
        override fun connectOverFormedGroup(
            request: MotorcycleConnectRequest,
            localIpv4: String?,
            groupOwnerIpv4: String?
        ): Boolean {
            val local = parseIpv4(localIpv4)
            val groupOwner = parseIpv4(groupOwnerIpv4)
            if (local == null || groupOwner == null) {
                ProjectionEventLog.error(
                    "IPC_TBOX",
                    "AIDL connectOverFormedGroup refused: unusable addresses " +
                        "(local=$localIpv4, groupOwner=$groupOwnerIpv4)."
                )
                return false
            }
            ProjectionEventLog.record(
                "IPC_TBOX",
                "AIDL connect over the group the companion app formed: phone=$localIpv4, dash=$groupOwnerIpv4."
            )
            return runConnect(request, FormedP2pGroup(local, groupOwner))
        }

        private fun runConnect(
            request: MotorcycleConnectRequest,
            formedGroup: FormedP2pGroup?
        ): Boolean =
            kotlinx.coroutines.runBlocking {
                val connector = CoreTBoxConnectors.acquire(applicationContext, request.ssid)
                val deferred = serviceScope.async { connector.connect(request.toProfile(), formedGroup) }
                activeConnect = connector to deferred
                val result = try {
                    deferred.await()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    false
                }
                if (activeConnect?.second === deferred) activeConnect = null
                result
            }

        // Dotted-quad literals only. getByName() would resolve anything else through DNS, on the
        // binder thread, for a value that is always a literal when the caller is honest.
        private fun parseIpv4(value: String?): java.net.Inet4Address? {
            val text = value?.trim().orEmpty()
            if (!IPV4_LITERAL.matches(text)) return null
            return runCatching { java.net.InetAddress.getByName(text) }
                .getOrNull() as? java.net.Inet4Address
        }

        override fun cancelConnect() {
            val (connector, deferred) = activeConnect ?: return
            deferred.cancel()
            serviceScope.launch { connector.cancel() }
        }

        override fun disconnect() {
            closeVideoStreamPipe()
            kotlinx.coroutines.runBlocking {
                // Tear down the registry's session first (it may belong to Core's own UI rather
                // than to this bridge), then release our connector - which also closes the Wi-Fi
                // request that building a throwaway connector here used to leave behind.
                CoreTBoxConnector.disconnectActiveSession()
                CoreTBoxConnectors.clear()
            }
        }

        override fun registerSessionListener(listener: ITBoxSessionListener) {
            sessionListeners.register(listener)
            ensureSessionPolling()
        }

        override fun unregisterSessionListener(listener: ITBoxSessionListener) {
            sessionListeners.unregister(listener)
        }

        override fun openVideoStream(): ParcelFileDescriptor? = openVideoStreamPipe()

        override fun closeVideoStream() {
            closeVideoStreamPipe()
        }

        /**
         * The companion app mirrors this log next to its own so a rider shares one file
         * instead of exporting from two apps. A real file, not a pipe: the export is
         * bounded (log ring + message caps) and a file descriptor stays readable even
         * after this service is unbound.
         */
        override fun openDiagnosticLogSnapshot(): ParcelFileDescriptor? = runCatching {
            val text = ProjectionEventLog.exportText()
            if (text.isBlank()) return@runCatching null
            val file = java.io.File(cacheDir, "ipc-diagnostics-snapshot.txt")
            file.writeText(text, Charsets.UTF_8)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }.onFailure {
            ProjectionEventLog.warning("IPC", "Diagnostic log snapshot failed.", it)
        }.getOrNull()

        override fun clearDiagnosticLog() {
            ProjectionEventLog.clear()
            // After the clear so it survives it: without this line a rider who cleared from
            // the companion app sees a Core log that "emptied itself" with nothing to say why.
            ProjectionEventLog.record("IPC", "Diagnostic log cleared at the companion app's request.")
        }
    }

    /**
     * Opens the high-rate data plane once. The Binder bridge remains the control
     * plane; encoded frames are length-prefixed on this local pipe instead of
     * becoming one Binder transaction per frame.
     */
    private fun openVideoStreamPipe(): ParcelFileDescriptor? {
        synchronized(videoStreamLock) {
            closeVideoStreamPipeLocked()
            return runCatching {
                val pipe = ParcelFileDescriptor.createPipe()
                videoStreamInput = pipe[0]
                // The companion app streams through this pipe on the shared T-Box session, but
                // it lives in another process and cannot claim the session itself. Hold the
                // claim on its behalf so a mode stopping inside Core cannot end the session
                // while the companion is still streaming on it.
                TBoxSessionRegistry.claim(SESSION_CONSUMER)
                videoStreamJob = serviceScope.launch {
                    readVideoStream(pipe[0])
                }
                watchTransportForCompanion()
                pipe[1]
            }.onFailure {
                ProjectionEventLog.error("IPC_TBOX", "Unable to open the PRO video data pipe.", it)
            }.getOrNull()
        }
    }

    private fun closeVideoStreamPipe() {
        synchronized(videoStreamLock) {
            closeVideoStreamPipeLocked()
        }
    }

    private fun closeVideoStreamPipeLocked() {
        transportWatchJob?.cancel()
        transportWatchJob = null
        videoStreamJob?.cancel()
        videoStreamJob = null
        videoStreamInput?.runCatching { close() }
        videoStreamInput = null
        TBoxSessionRegistry.release(SESSION_CONSUMER)
    }

    /**
     * Carries the transport's own verdict that the session is over out to the companion app.
     *
     * Core's modes each collect [TBoxTransport.events] and act on `FatalError`/`Stopped`, but
     * none of them is running when a companion app owns the session - so for a companion those
     * events used to land nowhere. Field log 2026-08-19 (Samsung SM-A566B, Benelli TRK 702X):
     * after a mid-ride reconnect the dash never asked for video and went quiet, Core's PXC
     * watchdog declared the session dead at 21951ms, and nothing carried that anywhere. The
     * companion went on offering frames for another 75 seconds while its own watchdog, which
     * fires on a broken pipe, had no pipe to break - the rider had to stop and end the session
     * by hand.
     *
     * Closing the pipe is deliberately the whole mechanism. It is the exact signal a real
     * transport death already produces (the reader breaks, the write end returns EPIPE) and the
     * one the companion's watchdog has recovered from in the field, so a verdict reached by the
     * timer instead of by a socket takes a path that is already proven rather than a new one.
     * The teardown itself stays with the companion: it owns the reconnect.
     */
    /**
     * Passes one transport notice to every bound companion.
     *
     * Logged here as well as sent, under the same T-BOX tag Core's own modes use, so the two
     * halves of a rider's exported log read identically: the sentence appears in Core's half
     * because Core produced it, and in the companion's half because the companion was told.
     */
    private fun broadcastTransportNotice(message: String) {
        ProjectionEventLog.record("T-BOX", message)
        if (sessionListeners.registeredCallbackCount == 0) return
        val count = sessionListeners.beginBroadcast()
        for (i in 0 until count) {
            // A companion older than this call has no onTransportNotice() in its stub. The
            // interface is oneway, so that transaction simply fails on the far side and nothing
            // is thrown here - but a listener whose process died between beginBroadcast() and
            // this line would be, and one dead companion must not silence the others.
            runCatching { sessionListeners.getBroadcastItem(i).onTransportNotice(message) }
        }
        sessionListeners.finishBroadcast()
    }

    /** Relays [TBoxEvent.Warning] to bound companions until the returned job is cancelled. */
    private fun relayTransportNoticesFor(transport: TBoxTransport): Job = serviceScope.launch {
        transport.events.collect { event ->
            if (event is TBoxEvent.Warning) broadcastTransportNotice(event.message)
        }
    }

    private fun watchTransportForCompanion() {
        transportWatchJob?.cancel()
        val transport = TBoxSessionRegistry.current()?.transport ?: return
        transportWatchJob = serviceScope.launch {
            transport.events.collect { event ->
                // A notice is not a verdict: relay it and leave the pipe alone.
                if (event is TBoxEvent.Warning) {
                    broadcastTransportNotice(event.message)
                    return@collect
                }
                val reason = when (event) {
                    is TBoxEvent.FatalError -> event.message
                    TBoxEvent.Stopped -> "The T-Box ended the session."
                    else -> return@collect
                }
                ProjectionEventLog.warning(
                    "IPC_TBOX",
                    "Ending the companion video pipe because the T-Box session is over: $reason"
                )
                closeVideoStreamPipe()
            }
        }
    }

    /**
     * Names the session the probe just judged, for the companion app to read back over
     * [ITBoxTransportService.getDashboardDeliveryReport].
     *
     * The profile is taken the same way every session service takes it - the transport's own
     * answer first - because that is the profile the frames were actually encoded for, and it is
     * the one the rider will be offered an alternative to.
     */
    private fun publishDeliveryVerdict(verdict: VideoDeliveryProbe.Verdict, probe: VideoDeliveryProbe) {
        val handle = TBoxSessionRegistry.current() ?: return
        val profile = handle.transport.activeProtocolProfile ?: TBoxModelProfile.resolve(
            handle.motorcycle.modelId,
            null,
            ProfileOverride.byKey(handle.motorcycle.profileOverrideKey)
        )
        DashboardDeliveryMonitor.publish(
            verdict = verdict,
            ssid = handle.motorcycle.ssid,
            rejected = probe.rejectedCount(),
            accepted = probe.acceptedCount(),
            profileKey = profile.key
        )
    }

    private suspend fun readVideoStream(input: ParcelFileDescriptor) {
        try {
            ParcelFileDescriptor.AutoCloseInputStream(input).use { raw ->
                DataInputStream(BufferedInputStream(raw, VIDEO_PIPE_BUFFER_BYTES)).use { stream ->
                    // Same guard, same thresholds as the in-process Android Auto path: a
                    // rejection is a busy transport, not a dead one, and only a streak that is
                    // both long and sustained ends the session. See the block below.
                    val backpressure = VideoBackpressureGuard()
                    // Whether the companion app's stream is landing at all. This is the ONLY
                    // place that can tell for the ADVANCED pairing: the pipe is one-way, so the
                    // companion's own offerAccessUnit() reports whether the WRITE succeeded, not
                    // whether the dashboard took the frame. See DashboardDeliveryMonitor.
                    val delivery = VideoDeliveryProbe()
                    DashboardDeliveryMonitor.clear()
                    while (currentCoroutineContext().isActive) {
                        val frame = try {
                            VideoPipeFraming.read(stream)
                        } catch (_: EOFException) {
                            break
                        }
                        val transport = TBoxSessionRegistry.current()?.transport ?: break
                        // A still carries its own frame id, which is the whole reason it does not
                        // travel as a plain access unit: the dashes that want stills acknowledge
                        // by id, so an id invented on this side would throw away the link's only
                        // liveness signal.
                        val accepted = if (frame.isStill) {
                            (transport as? SelectingTBoxTransport)
                                ?.offerJpegFrame(frame.payload, frame.frameId) ?: false
                        } else {
                            transport.offerAccessUnit(frame.payload)
                        }
                        if (!accepted) {
                            // A refusal on this pipe is flow control, not death - for stills and
                            // for access units alike.
                            //
                            // A refused STILL was already exempt: the Yunmo transport refuses by
                            // design whenever the dash has not acknowledged the stills on the
                            // wire (STILL_SEND_WINDOW), and the X-Cape acks two-ish a second
                            // against ten offered, so three consecutive refusals arrive within
                            // ~300ms on a perfectly healthy link (X-Cape 1200 field report,
                            // 2026-08-24). What that fix assumed, and what was wrong, is that a
                            // refused ACCESS UNIT could only mean a dead session. The same
                            // transport refuses one whenever its single-slot frame executor is
                            // busy - which on a dash acking ~10fps against a dashboard offering
                            // 30 is every other frame. Rider 315e0af3 (2026-08-24, both editions
                            // 1.1.91) lost the dashboard every ten seconds to exactly that, while
                            // Core's own Android Auto, holding the identical stream open through
                            // 121 rejections a minute, showed what the right answer looks like.
                            //
                            // So: the same VideoBackpressureGuard that path uses. A genuinely
                            // dead session is still caught - by the guard's own long streak, and
                            // before that by watchTransportForCompanion (the transport's
                            // FatalError/Stopped events), which is what actually closes the pipe
                            // when the link is gone.
                            // Stills are deliberately left out of the delivery probe, and this is
                            // not an oversight to tidy up later. The Yunmo transport refuses a
                            // still whenever the dash has not acknowledged the ones already on
                            // the wire, and the X-Cape acks two-ish a second against ten
                            // offered - so the JPEG profile, which is the profile that WORKS on
                            // that dash, refuses most of what it is handed by design. Counting
                            // those would make the app propose a different profile to the one
                            // rider who had already found the right one.
                            if (!frame.isStill) {
                                delivery.onRejected()?.let { publishDeliveryVerdict(it, delivery) }
                                if (backpressure.onRejected()) {
                                    ProjectionEventLog.warning(
                                        "IPC_TBOX",
                                        "PRO video pipe stopped: CORE rejected " +
                                            "${backpressure.rejectionStreak()} consecutive AVC frames " +
                                            "over ${backpressure.streakMillis()}ms."
                                    )
                                    break
                                }
                            }
                        } else {
                            backpressure.onAccepted()
                            if (!frame.isStill) {
                                delivery.onAccepted()?.let { publishDeliveryVerdict(it, delivery) }
                            }
                        }
                    }
                }
            }
        } catch (failure: Throwable) {
            when {
                failure is kotlinx.coroutines.CancellationException -> Unit
                // "read interrupted by close() on another thread" is not a symptom of anything:
                // it is what THIS process's own closeVideoStreamPipe() looks like from inside the
                // read, and nothing on the far end can produce it. Stopping the Ride Dashboard by
                // hand therefore ended every session with an ERROR and a full stack trace - rider
                // a7cda9d1, 2026-08-25 11:41:45, one second after "User requested Ride Dashboard
                // stop" - which is a lie in the log and a needs-attention badge in the collector
                // for an app that did exactly what it was told.
                failure is java.io.InterruptedIOException ->
                    ProjectionEventLog.debug("IPC_TBOX", "PRO video pipe reader ended with the stream close.")
                else -> ProjectionEventLog.error("IPC_TBOX", "PRO video pipe reader stopped.", failure)
            }
        } finally {
            synchronized(videoStreamLock) {
                if (videoStreamInput === input) {
                    videoStreamInput = null
                    videoStreamJob = null
                }
            }
        }
    }

    /** TBoxSessionRegistry exposes no observable state (a deliberately small, in-process-only
     *  registry — see its own doc comment), so this is the simplest way to turn its polled
     *  current() into ready/lost callbacks for a remote caller without changing that shared,
     *  already-depended-upon class. */
    private fun ensureSessionPolling() {
        if (sessionPollJob?.isActive == true) return
        sessionPollJob = serviceScope.launch {
            while (true) {
                // Nobody is listening: this loop exists only to feed remote callbacks, and it
                // used to keep ticking for the life of the process after the last companion
                // unbound. registerSessionListener() calls back in here, so a later listener
                // restarts it.
                if (sessionListeners.registeredCallbackCount == 0) {
                    ProjectionEventLog.debug(
                        "IPC",
                        "No session listeners left; stopping the session poll until one registers."
                    )
                    return@launch
                }
                val handle = TBoxSessionRegistry.current()
                if ((handle != null) != (lastKnownHandle != null)) {
                    val ready = handle != null
                    val count = sessionListeners.beginBroadcast()
                    for (i in 0 until count) {
                        runCatching {
                            if (ready) sessionListeners.getBroadcastItem(i).onSessionReady()
                            else sessionListeners.getBroadcastItem(i).onSessionLost()
                        }
                    }
                    sessionListeners.finishBroadcast()
                }
                lastKnownHandle = handle
                delay(SESSION_POLL_INTERVAL_MS)
            }
        }
    }

    // ── Android Auto receiver ────────────────────────────────────────

    private val stateListeners = RemoteCallbackList<IAndroidAutoStateListener>()
    private val navigationListeners = RemoteCallbackList<INavigationGuidanceListener>()

    /**
     * Companion-app watchers of the handlebar gestures this process recognises.
     *
     * The teaching wizard lives over there and the presses are decoded over here, so before this
     * existed the wizard could not observe a single one during an Android Auto session: it read
     * [HandlebarGestureFeed], an in-process singleton, in the process that was not listening.
     */
    private val handlebarGestureListeners = RemoteCallbackList<IHandlebarGestureListener>()
    private var handlebarGestureJob: Job? = null

    /**
     * Starts forwarding once someone is watching, and only then. The feed is a StateFlow that
     * runs whether or not anyone listens; collecting it for the service's whole life would keep
     * a coroutine alive through every ride for the few minutes a rider spends teaching.
     *
     * Only presses made from this moment on are forwarded. The feed retains its last value and
     * nothing ever clears it, so a collector starting up replayed the previous session's final
     * press to whoever had just subscribed. The wizard survived that by comparing timestamps;
     * the companion's mode-switch listener did not, so an Android Auto session whose last press
     * had been the switch button immediately switched itself back out again.
     *
     * Synchronized because binder threads register in parallel: two of them racing this check
     * started two collectors, and every press was then broadcast — and acted on — twice.
     */
    @Synchronized
    private fun ensureHandlebarGestureForwarding() {
        if (handlebarGestureJob?.isActive == true) return
        val startedAt = SystemClock.elapsedRealtime()
        handlebarGestureJob = serviceScope.launch {
            io.motohub.android.feature.controls.HandlebarGestureFeed.lastGesture
                .filterNotNull()
                .collect { event ->
                    if (event.atElapsedRealtimeMillis < startedAt) return@collect
                    if (handlebarGestureListeners.registeredCallbackCount == 0) return@collect
                    val count = handlebarGestureListeners.beginBroadcast()
                    for (i in 0 until count) {
                        runCatching {
                            handlebarGestureListeners.getBroadcastItem(i).onHandlebarGesture(
                                event.gesture.id,
                                event.atElapsedRealtimeMillis
                            )
                        }
                    }
                    handlebarGestureListeners.finishBroadcast()
                }
        }
    }
    private var compositor: AaCompositor? = null
    private var receiver: AaReceiver? = null

    private fun AaNavigationGuidance.Snapshot.toParcel() = NavigationGuidanceParcel(
        active = active,
        rerouting = rerouting,
        maneuverType = maneuverType,
        roundaboutExitNumber = roundaboutExitNumber,
        road = road,
        distanceToManeuverMeters = distanceToManeuverMeters,
        timeToManeuverSeconds = timeToManeuverSeconds,
        distanceRemainingMeters = distanceRemainingMeters,
        timeToArrivalSeconds = timeToArrivalSeconds,
        estimatedTimeAtArrival = estimatedTimeAtArrival
    )

    private fun broadcastGuidance(snapshot: AaNavigationGuidance.Snapshot) {
        val parcel = snapshot.toParcel()
        val count = navigationListeners.beginBroadcast()
        for (i in 0 until count) {
            runCatching { navigationListeners.getBroadcastItem(i).onGuidanceChanged(parcel) }
        }
        navigationListeners.finishBroadcast()
    }

    private val androidAutoBinder = object : IAndroidAutoReceiverService.Stub() {
        override fun attachOutputSurface(surface: Surface, width: Int, height: Int): Boolean {
            if (receiver != null) {
                publishState(AndroidAutoIpcState.FAILED, "An Android Auto receiver session is already active.")
                return false
            }
            if (AndroidAutoRuntime.isActive()) {
                publishState(
                    AndroidAutoIpcState.FAILED,
                    "Core's full Android Auto session is already active; stop it before attaching a preview."
                )
                return false
            }
            if (!SingleKeyKeyManager.isAvailable(this@IpcBridgeService)) {
                publishState(AndroidAutoIpcState.FAILED, "Android Auto identity is not included in this build.")
                return false
            }
            publishState(AndroidAutoIpcState.PREPARING, "")
            val profile = AndroidAutoCapabilityProfiles.fallback().withFullVideoTarget()
            val activeCompositor = AaCompositor(
                log = { ProjectionEventLog.debug("IPC_AA", it) },
                displayMode = AndroidAutoDisplayMode.STRETCH,
                sourceGeometry = profile.video
            )
            if (!activeCompositor.start()) {
                publishState(AndroidAutoIpcState.FAILED, "Compositor failed to initialize (EGL/GL).")
                return false
            }
            val decoderSurface = activeCompositor.inputSurface
            if (decoderSurface == null) {
                activeCompositor.release()
                publishState(AndroidAutoIpcState.FAILED, "Compositor did not create a video surface.")
                return false
            }
            activeCompositor.setOutput(surface, width, height, profile.video.width, profile.video.height)
            compositor = activeCompositor

            val activeReceiver = AaReceiver(
                context = applicationContext,
                encoderSurface = decoderSurface,
                log = { ProjectionEventLog.debug("IPC_AA", it) },
                onVideoReady = { publishState(AndroidAutoIpcState.STREAMING, "") },
                onSessionEnded = { clean, userExit ->
                    publishState(
                        AndroidAutoIpcState.STOPPED,
                        when {
                            userExit -> "The user ended the Android Auto session."
                            clean -> "Android Auto ended the session."
                            else -> "Android Auto connection closed unexpectedly."
                        }
                    )
                    releaseReceiver()
                },
                mapTouchToSource = activeCompositor::mapCanvasToUi,
                capabilityProfile = profile,
                downstreamBlockedMillis = activeCompositor::downstreamBlockedMillis
            )
            // Registering here is what lets a leaked receiver from another feature be handed over
            // instead of turning into a bare EADDRINUSE: the checks above already refuse while a
            // *live* Core session is running, so this only ever takes over a stale claim.
            AndroidAutoReceiverOwnership.claim(this@IpcBridgeService, "embedded") { releaseReceiver() }
            if (!activeReceiver.start()) {
                releaseReceiver()
                publishState(AndroidAutoIpcState.FAILED, "Android Auto local port ${AaReceiver.PORT} is unavailable.")
                return false
            }
            receiver = activeReceiver
            publishState(AndroidAutoIpcState.RECEIVER_READY, "")
            triggerSelfModeForEmbeddedReceiver()
            return true
        }

        override fun detachOutputSurface() {
            // Same reasoning as stopFullSession: a pending trigger would re-launch Google
            // Android Auto after the receiver is gone.
            selfModeJob?.cancel()
            selfModeJob = null
            releaseReceiver()
        }

        override fun attachPreviewSurface(surface: Surface, width: Int, height: Int): Boolean {
            compositor?.let {
                it.setPreview(surface, width, height)
                return true
            }
            if (!AndroidAutoRuntime.isActive()) return false
            AndroidAutoPreviewRuntime.attach(surface, width, height)
            return true
        }

        override fun detachPreviewSurface() {
            compositor?.let {
                it.clearPreview()
                return
            }
            AndroidAutoPreviewRuntime.detachAttachedPreview()
        }

        // Touches arriving here come from the TFT, in output-canvas coordinates, which is the
        // space AaReceiver.sendTouch expects: it runs mapTouchToSource (the compositor's
        // canvas -> Android Auto UI mapping) on whatever it is given.
        override fun sendTouch(action: Int, x: Int, y: Int): Boolean {
            val activeReceiver = receiver ?: return false
            activeReceiver.sendTouch(action, x, y)
            return true
        }

        override fun sendPreviewTouch(action: Int, x: Int, y: Int): Boolean {
            compositor?.let { activeCompositor ->
                val mapped = activeCompositor.mapPreviewToUi(x, y) ?: return false
                // sendSourceTouch, NOT sendTouch: mapPreviewToUi has already produced Android
                // Auto UI coordinates, and sendTouch would map them a second time as if they
                // were TFT canvas pixels. That double transform is what made the embedded
                // "Preview & touch" screen land every tap short of where the rider pressed -
                // wrong by a factor of canvasWidth/sourceWidth, so exact at the origin and
                // worse the further out you touch. The full-screen preview path in
                // AndroidAutoSessionService.sendPreviewTouch already used sendSourceTouch;
                // only this bridge did not.
                receiver?.sendSourceTouch(action, mapped.first, mapped.second) ?: return false
                return true
            }
            if (!AndroidAutoRuntime.isActive()) return false
            AndroidAutoPreviewRuntime.sendTouch(action, 0, x, y)
            return true
        }

        // Route key/scroll to Core's active AAP input channel (installed by AaReceiver while a
        // session streams). Returns false when no input channel is ready.
        override fun sendKey(keycode: Int): Boolean = AaInputBridge.sendKey(keycode)
        override fun sendScroll(delta: Int): Boolean = AaInputBridge.sendScroll(delta)

        // Applies a companion app's settings snapshot to Core's own prefs (via the same setters
        // the UI uses) so the next session honors them. Enums are parsed defensively.
        override fun applyAndroidAutoSettings(settings: AndroidAutoSettingsParcel) {
            val ctx = applicationContext
            runCatching {
                MotoHubSettings.setAndroidAutoResolution(
                    ctx, AndroidAutoResolutionMode.valueOf(settings.resolutionMode)
                )
            }
            if (settings.androidAutoDensityProvided) {
                runCatching {
                    MotoHubSettings.setAndroidAutoDensity(
                        ctx, AndroidAutoDensityMode.valueOf(settings.androidAutoDensity)
                    )
                }
            }
            runCatching {
                MotoHubSettings.setAndroidAutoAspectMatching(
                    ctx, AndroidAutoAspectMatchingMode.valueOf(settings.aspectMatching)
                )
            }
            runCatching {
                MotoHubSettings.setVideoQuality(ctx, VideoQuality.valueOf(settings.videoQuality))
            }
            runCatching { MotoHubSettings.setDisableTouchscreen(ctx, settings.disableTouchscreen) }
            runCatching { MotoHubSettings.setSeamlessResume(ctx, settings.seamlessResume) }
            // The companion resolves Auto/Day/Night itself and sends the answer; AaReceiver
            // reads Core's own store when the transport connects, so without this mirror a
            // session always started with the flag left by the last LIVE toggle, not with the
            // theme the rider actually has selected.
            runCatching { AndroidAutoNightModeStore(ctx).save(settings.nightMode) }
            // Display mode (Garage's Stretch/Fit/Letterbox) is stored per-motorcycle, in the
            // caller's OWN app data — Core never sees it unless the caller forwards it here.
            // AndroidAutoSessionService reads it back keyed by handle.motorcycle (the same
            // profile this connect installed), so this must be saved before startFullSession.
            runCatching {
                if (settings.displayMode.isNotBlank()) {
                    TBoxSessionRegistry.current()?.motorcycle?.let { motorcycle ->
                        AndroidAutoDisplayModeStore(ctx).save(
                            motorcycle,
                            AndroidAutoDisplayMode.valueOf(settings.displayMode)
                        )
                    }
                }
            }
            // Screen margins, for the same reason as the display mode above and with the same
            // per-motorcycle key: AndroidAutoSessionService composites against THIS store, so
            // until this mirror existed the two halves projected the same panel differently -
            // Android Auto inset to a 680x408 viewport by a right margin of 120 while the
            // companion's Ride Dashboard filled all 800x480 of it (field log 7efdfa33,
            // 2026-08-25). Gated on screenMarginsProvided, which the companion sets only when its
            // own store really holds a taught value: this app ships the same ruler, and an empty
            // companion store must not push four zeros over a calibration made here.
            runCatching {
                if (settings.screenMarginsProvided) {
                    TBoxSessionRegistry.current()?.motorcycle?.let { motorcycle ->
                        val margins = TBoxScreenMargins(
                            top = settings.screenMarginTop,
                            bottom = settings.screenMarginBottom,
                            left = settings.screenMarginLeft,
                            right = settings.screenMarginRight
                        )
                        TBoxScreenMarginsStore(ctx).save(motorcycle, margins)
                        ProjectionEventLog.record(
                            "IPC_AA",
                            "Screen margins mirrored from companion: $margins."
                        )
                    }
                }
            }
            // Gated separately from the handlebar block: Core has this picker in its own
            // settings, so a companion that never sends the field must leave Core's choice
            // alone rather than reset it to AVRCP. HID capture itself stays per-process - each
            // edition's own Accessibility Service feeds its own bridges - so this only says
            // WHICH protocol the remote speaks, which is a property of the motorcycle.
            //
            // Missing here until 2026-08-26 while the field, the picker and the companion's
            // side of the send had all shipped: the commit that added them never reached this
            // half. A rider who chose HID in the companion app left Core on AVRCP, where
            // MediaButtonBridge then refused to capture for want of a Bluetooth grant that HID
            // does not even need (rider 315e0af3).
            if (settings.handlebarInputModeProvided) {
                runCatching {
                    io.motohub.android.feature.controls.HandlebarInputMode.entries
                        .firstOrNull { it.id == settings.handlebarInputMode }
                        ?.let {
                            val previous = io.motohub.android.feature.controls.HandlebarControlStore
                                .inputMode(ctx)
                            io.motohub.android.feature.controls.HandlebarControlStore
                                .setInputMode(ctx, it)
                            // The companion pushes this parcel mid-session too - its picker sends
                            // one on every change. Storing the choice without applying it is what
                            // made those switches invisible: the bridge reads the mode when it
                            // takes the handlebar and never again.
                            if (it != previous) {
                                ProjectionEventLog.record(
                                    "IPC_AA",
                                    "Handlebar input mode mirrored from companion: " +
                                        "${previous.id} -> ${it.id}."
                                )
                                io.motohub.android.feature.controls.MediaButtonBridge
                                    .inputModeChanged()
                            }
                        }
                }
            }
            // "Observed, not obeyed", for as long as the rider is being taught. The wizard runs
            // in the companion app and sets the flag on ITS copy of HandlebarGestureFeed, which
            // is not the copy an Android Auto session publishes into - so the wizard asked for a
            // press and this process acted on it, moving the dashboard under a rider who was
            // told nothing would happen. Gated like the blocks above: false is also the value
            // that means "obey them", so without the flag an old companion would clear a
            // teaching session it knows nothing about.
            if (settings.handlebarCaptureOnlyProvided) {
                io.motohub.android.feature.controls.HandlebarGestureFeed
                    .setCaptureOnly(settings.handlebarCaptureOnly)
            }
            // Handlebar configuration is mirrored from the companion's own stores into Core's:
            // Core's Android Auto bridge reads Core's HandlebarControlStore, so without this the
            // rider's PRO-side configuration never applied to AA sessions. Gated on
            // handlebarSyncProvided so a pre-sync caller's parcel (fields deserialize as
            // false/empty) leaves Core's own configuration untouched.
            if (settings.handlebarSyncProvided) {
                runCatching {
                    io.motohub.android.feature.controls.HandlebarControlStore.setManagedByCompanion(ctx, true)
                    io.motohub.android.feature.controls.HandlebarControlStore.setEnabled(
                        ctx, settings.handlebarControlsEnabled
                    )
                    settings.handlebarMapping.split(',').forEach { entry ->
                        val gestureId = entry.substringBefore('=', "")
                        val actionId = entry.substringAfter('=', "")
                        val gesture = io.motohub.android.feature.controls.HandlebarGesture.entries
                            .firstOrNull { it.id == gestureId }
                        val action = io.motohub.android.feature.controls.HandlebarAction.entries
                            .firstOrNull { it.id == actionId }
                        if (gesture != null && action != null) {
                            io.motohub.android.feature.controls.HandlebarControlStore.setAction(ctx, gesture, action)
                        }
                    }
                    io.motohub.android.feature.controls.DoubleTapDelay.entries
                        .firstOrNull { it.millis == settings.handlebarDoubleTapMillis }
                        ?.let { io.motohub.android.feature.controls.HandlebarTimingPrefs.setDoubleTap(ctx, it) }
                    io.motohub.android.feature.controls.SelectHoldDelay.entries
                        .firstOrNull { it.millis == settings.handlebarSelectHoldMillis }
                        ?.let { io.motohub.android.feature.controls.HandlebarTimingPrefs.setSelectHold(ctx, it) }
                    io.motohub.android.feature.controls.HandlebarTimingPrefs.setEagerSingles(
                        ctx, settings.handlebarEagerSingles
                    )
                    io.motohub.android.feature.controls.HandlebarTimingPrefs.setHoldsEnabled(
                        ctx, settings.handlebarHoldsEnabled
                    )
                    // The taught calibration travels too: Core's bridge and mapping UI read
                    // Core's own store, and the stores are scoped to the session's motorcycle,
                    // so the companion's per-bike teaching lands per-bike here as well. A live
                    // bridge then re-decides the volume pin against the fresh calibration.
                    io.motohub.android.feature.controls.HandlebarCalibration.import(
                        ctx, settings.handlebarCalibration
                    )
                    io.motohub.android.feature.controls.MediaButtonBridge.refreshVolumeGestureUse()
                    // Apply live when an AA session is already capturing (settings re-pushed by
                    // the companion at every session start, including embedded dashboard AA).
                    io.motohub.android.feature.controls.MediaButtonBridge.setTargetCaptureActive(
                        io.motohub.android.feature.controls.MediaButtonBridge.TARGET_ANDROID_AUTO,
                        settings.handlebarControlsEnabled
                    )
                    ProjectionEventLog.record(
                        "IPC_AA",
                        "Handlebar configuration mirrored from companion: " +
                            "enabled=${settings.handlebarControlsEnabled}."
                    )
                }
            }
            // The Bluetooth dash-clock channel lives here in Core, so the companion's copy of the
            // switch has to be mirrored or flipping it there configures a process that never reads
            // it. Gated like the handlebar block: Core offers this toggle in its own settings too,
            // and a caller that predates the field would otherwise push a default `false` over a
            // rider's own choice.
            if (settings.bluetoothClockSyncProvided) {
                runCatching {
                    MotoHubSettings.setBluetoothClockSync(ctx, settings.bluetoothClockSync)
                    ProjectionEventLog.record(
                        "IPC_AA",
                        "Bluetooth dash-clock sync mirrored from companion: " +
                            "enabled=${settings.bluetoothClockSync}."
                    )
                    // The transport already decided about this channel when it connected, seconds
                    // before this parcel arrived, so acting on the value now is what makes the
                    // rider's first ride after enabling behave like every later one.
                    io.motohub.android.tbox.EcBtpClockChannel.refresh(ctx)
                }
            }
            // Whether a dropped session is retried is decided in Core, by
            // AndroidAutoSessionService, from CORE's copy of the switch - so a rider who set it in
            // the companion app configured the process that does not run the session. Mirrored for
            // the same reason as the Bluetooth clock above, and gated for a sharper one: `false`
            // is both "an old caller said nothing" and "do not recover", so without the flag an
            // old companion would switch recovery off for a rider who enabled it here.
            companionAutoRecovery(settings.autoRecoveryProvided, settings.autoRecovery)?.let { on ->
                runCatching {
                    MotoHubSettings.setAutoRecovery(ctx, on)
                    ProjectionEventLog.record(
                        "IPC_AA",
                        "Automatic reconnection mirrored from companion: enabled=$on."
                    )
                }
            }
            ProjectionEventLog.record("IPC_AA", "Applied companion Android Auto settings snapshot.")
        }

        // Toggles day/night on the running session. The embedded (Ride Dashboard) receiver is
        // owned by this service and never installs itself in AndroidAutoPreviewRuntime - only
        // AndroidAutoSessionService does - so routing every call through the runtime left the
        // companion's Map appearance picker a no-op while Android Auto ran inside the dashboard:
        // the controller was null, the call returned false, and Waze/Maps stayed on whatever
        // theme the session had connected with. The stored flag is what AaReceiver hands the
        // next transport at connect, so it is kept in step here exactly as the full session does.
        override fun setNightMode(isNight: Boolean): Boolean {
            val applied = receiver?.let { embedded ->
                embedded.setNightMode(isNight)
            } ?: AndroidAutoPreviewRuntime.setNightMode(isNight)
            if (applied) AndroidAutoNightModeStore(applicationContext).save(isNight)
            return applied
        }

        // Triggers Core's own existing AndroidAutoSessionService unchanged — this deliberately
        // does not duplicate its pipeline (watchdog/recovery/T-Box negotiation) here. Both this
        // and attachOutputSurface ultimately bind the same fixed local AA port; the loser of a
        // race fails cleanly (AaReceiver.start() returns false), it does not crash.
        override fun startFullSession(): Boolean {
            if (receiver != null) {
                publishState(AndroidAutoIpcState.FAILED, "An embedded preview session is already attached.")
                return false
            }
            if (AndroidAutoRuntime.isActive()) return true
            ensureFullSessionStateForwarding()
            AndroidAutoSessionService.start(this@IpcBridgeService)
            // Core's own UI (MainActivity) normally fires the self-mode trigger once the receiver
            // is ready. When a companion app drives the session over AIDL, that Activity isn't in
            // the loop, so trigger it here instead — the broadcast fallback works from a service.
            triggerSelfModeWhenReady()
            return true
        }

        override fun stopFullSession() {
            // Cancel any pending self-mode trigger first: a stop issued while the receiver is
            // still coming up would otherwise fire AaSelfMode after teardown and immediately
            // re-launch Google Android Auto, making the stop look like it "didn't work".
            selfModeJob?.cancel()
            selfModeJob = null
            AndroidAutoSessionService.stop(this@IpcBridgeService)
        }

        override fun registerStateListener(listener: IAndroidAutoStateListener) {
            stateListeners.register(listener)
            ensureFullSessionStateForwarding()
        }

        override fun unregisterStateListener(listener: IAndroidAutoStateListener) {
            stateListeners.unregister(listener)
        }

        override fun registerNavigationGuidanceListener(listener: INavigationGuidanceListener) {
            navigationListeners.register(listener)
            // A widget attaching mid-ride should not wait for the next turn to learn the
            // current one.
            runCatching { listener.onGuidanceChanged(AaNavigationGuidance.latest.toParcel()) }
        }

        override fun unregisterNavigationGuidanceListener(listener: INavigationGuidanceListener) {
            navigationListeners.unregister(listener)
        }

        override fun registerHandlebarGestureListener(listener: IHandlebarGestureListener) {
            handlebarGestureListeners.register(listener)
            ensureHandlebarGestureForwarding()
        }

        override fun unregisterHandlebarGestureListener(listener: IHandlebarGestureListener) {
            handlebarGestureListeners.unregister(listener)
            // The wizard closing is also the moment to stop obeying-nothing: a companion that
            // died mid-teaching would otherwise leave this process observing presses and acting
            // on none of them for the rest of the ride.
            if (handlebarGestureListeners.registeredCallbackCount == 0) {
                io.motohub.android.feature.controls.HandlebarGestureFeed.setCaptureOnly(false)
                handlebarGestureJob?.cancel()
                handlebarGestureJob = null
            }
        }

    }

    private var selfModeJob: Job? = null

    /** Mirrors MainActivity.startAndroidAuto's coordinator: wait for the receiver to be ready,
     *  let it settle, then ask Google Android Auto to connect to Core's local AAP port. */
    private fun triggerSelfModeWhenReady() {
        selfModeJob?.cancel()
        selfModeJob = serviceScope.launch {
            val state = kotlinx.coroutines.withTimeoutOrNull(SELF_MODE_READY_TIMEOUT_MS) {
                AndroidAutoRuntime.state
                    .dropWhile {
                        it is AndroidAutoRuntimeState.Idle ||
                            it is AndroidAutoRuntimeState.Stopped ||
                            it is AndroidAutoRuntimeState.Failed
                    }
                    .first {
                        it is AndroidAutoRuntimeState.ReceiverReady ||
                            it is AndroidAutoRuntimeState.Failed
                    }
            }
            if (state is AndroidAutoRuntimeState.ReceiverReady) {
                delay(ANDROID_AUTO_RECEIVER_SETTLE_MS)
                if (AndroidAutoRuntime.state.value is AndroidAutoRuntimeState.ReceiverReady) {
                    AaSelfMode.trigger(
                        context = applicationContext,
                        onProgress = { detail ->
                            // Keep Core's own UI and the companion's in step during the wait.
                            AndroidAutoRuntime.publishStartupDetail(detail)
                            publishState(AndroidAutoIpcState.RECEIVER_READY, detail)
                        },
                        log = { ProjectionEventLog.record("AAP", it) }
                    )
                }
            }
        }
    }

    /**
     * The embedded (Ride Dashboard) receiver is ready as soon as AaReceiver.start() returns and
     * never drives AndroidAutoRuntime, so [triggerSelfModeWhenReady]'s state wait would block
     * forever here. Without a trigger the receiver just listens on the local AAP port and Google
     * Android Auto is never asked to connect - the dashboard sits on "STARTING ANDROID AUTO"
     * indefinitely, which is exactly what the full-session path avoids by calling AaSelfMode.
     */
    private fun triggerSelfModeForEmbeddedReceiver() {
        selfModeJob?.cancel()
        selfModeJob = serviceScope.launch {
            delay(ANDROID_AUTO_RECEIVER_SETTLE_MS)
            if (receiver != null) {
                AaSelfMode.trigger(
                    context = applicationContext,
                    // The companion has no window into Core's startup: without forwarding the
                    // progress it shows a motionless "preparing" for the whole attempt sequence.
                    onProgress = { publishState(AndroidAutoIpcState.RECEIVER_READY, it) },
                    log = { ProjectionEventLog.record("AAP", it) }
                )
            }
        }
    }

    private fun releaseReceiver() {
        receiver?.stop()
        receiver = null
        compositor?.clearOutput()
        compositor?.release()
        compositor = null
        // No-op when another owner has already taken the port over (it is the one that called us).
        AndroidAutoReceiverOwnership.release(this@IpcBridgeService)
    }

    private var fullSessionForwardingJob: Job? = null

    /** Forwards Core's own AndroidAutoRuntime.state (used by AndroidAutoSessionService, already
     *  Core's shipping full-AA feature) to remote listeners — Core's implementation itself is
     *  untouched, this only republishes its existing state on the same channel embedded-preview
     *  callers already listen on. */
    private fun ensureFullSessionStateForwarding() {
        if (fullSessionForwardingJob?.isActive == true) return
        fullSessionForwardingJob = serviceScope.launch {
            var firstEmission = true
            AndroidAutoRuntime.state.collectLatest { state ->
                // AndroidAutoRuntime.state is a StateFlow that keeps whatever it was last set to
                // by ANY previous attempt (including one that predates this listener, e.g. a
                // user tapping start before a T-Box was ready). Don't surface a stale
                // Failed/Stopped from before this listener existed as if it just happened now —
                // only report it if it happens WHILE we're actually watching.
                if (firstEmission) {
                    firstEmission = false
                    if (state is AndroidAutoRuntimeState.Failed || state is AndroidAutoRuntimeState.Stopped) {
                        publishState(AndroidAutoIpcState.IDLE, "")
                        return@collectLatest
                    }
                }
                val (ipcState, message) = when (state) {
                    AndroidAutoRuntimeState.Idle -> AndroidAutoIpcState.IDLE to ""
                    AndroidAutoRuntimeState.Preparing -> AndroidAutoIpcState.PREPARING to ""
                    AndroidAutoRuntimeState.ReceiverReady -> AndroidAutoIpcState.RECEIVER_READY to ""
                    AndroidAutoRuntimeState.Streaming -> AndroidAutoIpcState.STREAMING to ""
                    is AndroidAutoRuntimeState.Stopped -> AndroidAutoIpcState.STOPPED to state.reason
                    is AndroidAutoRuntimeState.Failed -> AndroidAutoIpcState.FAILED to state.message
                }
                publishState(ipcState, message)
            }
        }
    }

    private fun publishState(state: Int, message: String) {
        ProjectionEventLog.debug("IPC_AA", "state=$state message=$message")
        val count = stateListeners.beginBroadcast()
        for (i in 0 until count) {
            runCatching { stateListeners.getBroadcastItem(i).onStateChanged(state, message) }
        }
        stateListeners.finishBroadcast()
    }

    // ── Service lifecycle ────────────────────────────────────────────

    // This service only exists while a companion app (PRO) is bound to it — but a plain bound
    // service with no foreground presence is just a background process to the OS, and OEM
    // battery managers (ColorOS/OnePlus in particular) reap those aggressively even while the
    // binding client (PRO) is itself in the foreground. That silently drops TBoxSessionRegistry
    // (in-memory only) and any active AA session, surfacing as "No T-Box is ready" or a session
    // that stops working until the rider disconnects and reconnects. Run in the foreground for
    // this service's whole lifetime (bind-to-unbind) so it survives like Core's own AA/Mirroring/
    // Advanced streaming sessions already do.
    override fun onCreate() {
        super.onCreate()
        // Single consumer by design: this bridge is the only cross-process door into CORE, so
        // it owns the guidance fan-out for however many companion listeners register.
        AaNavigationGuidance.setListener(::broadcastGuidance)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.core_bridge_channel_name),
                NotificationManager.IMPORTANCE_MIN
            ).apply { description = getString(R.string.core_bridge_channel_description) }
        )
        // Going foreground can be refused outright, and this service is bound from another
        // process - so the refusal arrives on someone else's schedule, not ours. A companion
        // binding from the background is exactly when Android throws
        // ForegroundServiceStartNotAllowedException here, and uncaught it takes CORE down: the
        // companion then reports "Core is taking too long to respond" and the rider is told
        // nothing at all. AndroidAutoSessionService already names this hazard and this same
        // caller in its own comment.
        //
        // Unlike a session service, this one does NOT give up when refused. Being foreground is
        // what keeps the process out of the cached bucket and lets it submit a Wi-Fi request;
        // the AIDL door itself works either way, and staying bound but demoted is far better
        // than not answering at all. The line goes in the log because it explains, later, why a
        // join was refused or the process was killed while a companion was waiting on it.
        val foreground = runCatching {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        foreground.exceptionOrNull()?.let { failure ->
            ProjectionEventLog.warning(
                "IPC",
                "The Core bridge could not go foreground " +
                    "(${failure.javaClass.simpleName}: ${failure.message}); it stays bound and " +
                    "answers as usual, but the process can be reclaimed and a Wi-Fi request from " +
                    "it may be refused."
            )
        }
    }

    private fun createNotification(): android.app.Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.core_bridge_notification_title))
            .setContentText(getString(R.string.core_bridge_notification_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    override fun onBind(intent: Intent): IBinder? = when (intent.action) {
        IpcBridgeContract.BIND_ACTION_TBOX_TRANSPORT -> tboxTransportBinder
        IpcBridgeContract.BIND_ACTION_ANDROID_AUTO_RECEIVER -> androidAutoBinder
        else -> null
    }

    override fun onDestroy() {
        closeVideoStreamPipe()
        sessionPollJob?.cancel()
        fullSessionForwardingJob?.cancel()
        selfModeJob?.cancel()
        releaseReceiver()
        AaNavigationGuidance.setListener(null)
        sessionListeners.kill()
        stateListeners.kill()
        navigationListeners.kill()
        handlebarGestureJob?.cancel()
        handlebarGestureListeners.kill()
        io.motohub.android.feature.controls.HandlebarGestureFeed.setCaptureOnly(false)
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private companion object {
        const val SESSION_CONSUMER = CoreTBoxConnector.BRIDGE_SESSION_CONSUMER
        const val VIDEO_PIPE_BUFFER_BYTES = 64 * 1024
        const val MAX_VIDEO_ACCESS_UNIT_BYTES = 2 * 1024 * 1024
        const val SESSION_POLL_INTERVAL_MS = 1_000L
        const val VIDEO_AREA_TIMEOUT_MS = 10_000L
        const val SELF_MODE_READY_TIMEOUT_MS = 10_000L
        const val ANDROID_AUTO_RECEIVER_SETTLE_MS = 900L
        const val CHANNEL_ID = "core_bridge_v1"
        const val NOTIFICATION_ID = 9101
        val IPV4_LITERAL = Regex("""^(\d{1,3}\.){3}\d{1,3}$""")
    }
}
