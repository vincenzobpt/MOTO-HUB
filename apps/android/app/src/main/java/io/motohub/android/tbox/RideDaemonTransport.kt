package io.motohub.android.tbox

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import api.Api
import api.MobileCallback
import api.MobileSession
import io.motohub.android.feature.settings.MotoHubSettings
import io.motohub.android.session.ProjectionEventLog
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.ServerSocket
import java.net.BindException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val MOTO_HUB_SIMULATOR_MODEL_ID = "MOTO-HUB-SIMULATOR"
internal const val RIDE_DAEMON_STARTUP_TIMEOUT_SEC = 25L
private const val PUSH_FRAME_TIMEOUT_MS = 5_000L
private const val REJECTED_FRAME_LOG_INTERVAL = 100L
private val REVERSE_PORTS = intArrayOf(10920, 10921, 10922)

/** Kotlin boundary around the GPL gomobile binding. Network selection stays outside this class. */
class RideDaemonTransport(
    context: Context
) : TBoxTransport {
    private val appContext = context.applicationContext
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
    private val nsdManager = appContext.getSystemService(NsdManager::class.java)
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private val callbackExecutor = ContextCompat.getMainExecutor(appContext)
    // A SynchronousQueue (zero capacity) instead of the unbounded queue
    // Executors.newSingleThreadExecutor() would use: if the single worker is still busy on a
    // prior pushFrame() call, submit() rejects immediately rather than queuing. That bounds
    // memory even if the native pushFrame() call were to hang forever, instead of silently
    // accumulating queued access units behind a permanently stuck worker.
    private val pushFrameExecutor = java.util.concurrent.ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        java.util.concurrent.SynchronousQueue()
    ) { runnable -> Thread(runnable, "MotoHubPushFrame").apply { isDaemon = true } }
    private val mutableEvents = MutableSharedFlow<TBoxEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val events: Flow<TBoxEvent> = mutableEvents.asSharedFlow()
    @Volatile
    private var session: MobileSession? = null
    @Volatile
    private var sessionLink: TBoxLink? = null
    @Volatile
    private var protocolProfile: TBoxModelProfile = TBoxModelProfile.GENERIC
    private val pxcEvents = AtomicLong(0L)
    private val mediaControlEvents = AtomicLong(0L)
    private val framesOffered = AtomicLong(0L)
    private val framesTimedOut = AtomicLong(0L)
    private val framesRejected = AtomicLong(0L)
    private val lastPxcEventElapsed = AtomicLong(0L)
    private val lastMediaControlEventElapsed = AtomicLong(0L)
    private val lastFrameOfferedElapsed = AtomicLong(0L)

    override fun configureProtocolProfile(profile: TBoxModelProfile) {
        protocolProfile = profile
    }

    override suspend fun discover(link: TBoxLink, expectedModelId: String?): Result<TBoxHost> = withContext(Dispatchers.IO) {
        ProjectionEventLog.record("DISCOVERY", "Starting Android NSD discovery on T-Box link (${link.label}).")
        runCatching {
            stopSession()
            resetProtocolStats()
            val host = discoverWithRetry(link, expectedModelId)
            val profile = protocolProfile.takeIf { it != TBoxModelProfile.GENERIC }
                ?: TBoxModelProfile.resolve(expectedModelId, null)
            val mobileConfig = Api.newMobileConfig(
                ByteArray(0),
                30L,
                RIDE_DAEMON_STARTUP_TIMEOUT_SEC,
                5L,
                10L,
                3L
            ).apply {
                setSupportFunction(profile.advertisedSupportFunction.toLong())
                setProactivePxcHeartbeatEnabled(profile.requiresProactivePxcHeartbeat)
            }
            val createdSession = Api.newMobileSession(
                mobileConfig,
                SessionCallback()
            )
            session = createdSession
            sessionLink = link
            createdSession.setECHost(
                Api.newStreamHost(host.ipAddress, host.port.toString(), host.packageName)
            )
            ProjectionEventLog.record(
                "DISCOVERY",
                "RideDaemon live-only session configured for ${host.ipAddress}:${host.port}; " +
                    "package=${host.packageName}; profile=${profile.key}; " +
                    "supportFunction=${profile.advertisedSupportFunction}; " +
                    "proactivePxcHeartbeat=${profile.requiresProactivePxcHeartbeat}."
            )
            host
        }.onFailure { failure ->
            stopSession()
            // User/scope cancellation is not a discovery failure; clean up and propagate it.
            if (failure is CancellationException) throw failure
            ProjectionEventLog.error("DISCOVERY", "RideDaemon discovery/configuration failed.", failure)
        }
    }

    override suspend fun start(host: TBoxHost): Result<Unit> =
        withContext(Dispatchers.IO) {
            val activeSession = session
            val activeLink = sessionLink
            if (activeSession == null || activeLink == null) {
                return@withContext Result.failure(
                    IllegalStateException("Call discover() with an active T-Box link before starting the session")
                )
            }
            runCatching {
                ensureReversePortsAvailable()
                ProjectionEventLog.record(
                    "TBOX",
                    "Starting EasyConn handshake to ${host.ipAddress}:${host.port}; " +
                        "waiting for the TFT video area."
                )
                startWithNetworkSocket(activeSession, host, activeLink)
                ProjectionEventLog.record("TBOX", "RideDaemon startSessionWithSocketFd returned successfully.")
            }.onFailure {
                // The native call may already have opened 10920/10921/10922 before it
                // reports a timeout. Stop that session before the next user attempt.
                activeSession.runCatching { stopSession() }
                    .onFailure { stopFailure ->
                        ProjectionEventLog.warning("TBOX", "Failed to clean up the failed native session.", stopFailure)
                    }
                ProjectionEventLog.error("TBOX", "EasyConn handshake failed.", it)
            }
        }

    /** Fail early when another EasyConn client already owns the phone-side listeners. */
    private fun ensureReversePortsAvailable() {
        val probes = mutableListOf<ServerSocket>()
        try {
            REVERSE_PORTS.forEach { port -> probes += ServerSocket(port) }
        } catch (failure: BindException) {
            throw IllegalStateException(
                "Another EasyConn session is using local reverse ports 10920-10922. " +
                    "Close the official CFMOTO app and retry.",
                failure
            )
        } finally {
            probes.forEach { runCatching { it.close() } }
        }
    }

    override fun offerAccessUnit(avcc: ByteArray): Boolean {
        val activeSession = session ?: return false
        if (!activeSession.isRunning) return false
        val future = try {
            pushFrameExecutor.submit {
                activeSession.pushFrame(avcc)
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Zero-capacity queue: rejected while the worker is still inside a native pushFrame().
            // Logged throttled so a congestion burst cannot flood the diagnostic log.
            val rejections = framesRejected.incrementAndGet()
            if (rejections == 1L || rejections % REJECTED_FRAME_LOG_INTERVAL == 0L) {
                ProjectionEventLog.warning(
                    "TBOX",
                    "AVC frame dropped: the previous pushFrame() call is still running. " +
                        "Rejected frames so far: $rejections."
                )
            }
            return false
        }
        return try {
            future.get(PUSH_FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            framesOffered.incrementAndGet()
            lastFrameOfferedElapsed.set(SystemClock.elapsedRealtime())
            true
        } catch (timeout: java.util.concurrent.TimeoutException) {
            framesTimedOut.incrementAndGet()
            ProjectionEventLog.warning(
                "TBOX",
                "AVC frame dropped: pushFrame() exceeded ${PUSH_FRAME_TIMEOUT_MS}ms timeout. " +
                    "The T-Box may be unresponsive. Timeouts: ${framesTimedOut.get()}"
            )
            false
        } catch (failure: Throwable) {
            Log.w(TAG, "Unable to offer AVC access unit", failure)
            ProjectionEventLog.error("TBOX", "Unable to push an AVC access unit to RideDaemon.", failure)
            false
        }
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        stopSession()
    }

    private fun stopSession() {
        if (session != null) {
            ProjectionEventLog.record("TBOX", "Stopping RideDaemon session. ${protocolSnapshot()}")
        }
        session?.runCatching { stopSession() }
            ?.onFailure { ProjectionEventLog.warning("TBOX", "RideDaemon stopSession failed.", it) }
        session = null
        sessionLink = null
    }

    /** Opens the EasyConn command socket over the established T-Box link. */
    private suspend fun startWithNetworkSocket(
        activeSession: MobileSession,
        host: TBoxHost,
        link: TBoxLink
    ) {
        val policy = EasyConnRetryPolicy()
        val connectedSocket = retryEasyConnStart(
            policy = policy,
            shouldRetry = ::isTransientEasyConnFailure,
            onRetry = { failedAttempt, delayMillis, failure ->
                ProjectionEventLog.warning(
                    "TBOX",
                    "EasyConn attempt $failedAttempt/${policy.maxAttempts} failed: " +
                        "${failure.message.orEmpty()}. Retrying in ${delayMillis}ms."
                )
            }
        ) { attempt ->
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            ProjectionEventLog.debug(
                "TBOX",
                "EasyConn attempt $attempt/${policy.maxAttempts}: opening link-bound command " +
                    "socket to ${host.ipAddress}:${host.port} (${link.label})."
            )
            val socket = link.createSocket()
            try {
                socket.connect(InetSocketAddress(host.ipAddress, host.port), EC_CONNECT_TIMEOUT_MS)
                ProjectionEventLog.record("TBOX", "EasyConn TCP command socket connected.")
                socket to attempt
            } catch (failure: Throwable) {
                socket.close()
                throw failure
            }
        }
        if (connectedSocket.second > 1) {
            ProjectionEventLog.record(
                "TBOX",
                "EasyConn TCP connection recovered on attempt " +
                    "${connectedSocket.second}/${policy.maxAttempts}."
            )
        }
        connectedSocket.first.use { socket ->
            ParcelFileDescriptor.fromSocket(socket).use { descriptor ->
                val fd = descriptor.detachFd().toLong()
                // ParcelFileDescriptor duplicates the socket descriptor. Go owns and closes the
                // detached duplicate; the outer use{} closes the original Java socket.
                activeSession.startSessionWithSocketFd(fd)
            }
        }
    }

    // Catches only the withTimeout-specific subtype so a real user cancellation (plain
    // CancellationException) still propagates immediately instead of being retried; ensureActive
    // rethrows when the TimeoutCancellationException actually belongs to an enclosing withTimeout.
    private suspend fun discoverWithRetry(link: TBoxLink, expectedModelId: String?): TBoxHost {
        // A Wi-Fi Direct group has no bindable Network, so NSD cannot resolve the service over it.
        // Skip the (useless) discovery windows and probe the group owner directly, immediately after
        // the join while the p2p source address is still fresh - waiting 30s for NSD to fail was what
        // let the address go stale and made the probe socket bind fail with EADDRNOTAVAIL.
        if (link is TBoxLink.WifiDirect) return discoverOverWifiDirect(link)

        repeat(DISCOVERY_MAX_ATTEMPTS - 1) { attempt ->
            try {
                return withTimeout(DISCOVERY_TIMEOUT_MS) { discoverWithAndroidNsd(link, expectedModelId) }
            } catch (timeout: TimeoutCancellationException) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                ProjectionEventLog.warning(
                    "DISCOVERY",
                    "No EasyConn advertisement seen within ${DISCOVERY_TIMEOUT_MS}ms " +
                        "(attempt ${attempt + 1}/$DISCOVERY_MAX_ATTEMPTS); restarting NSD discovery."
                )
                delay(DISCOVERY_RETRY_DELAY_MS)
            }
        }
        try {
            return withTimeout(DISCOVERY_TIMEOUT_MS) { discoverWithAndroidNsd(link, expectedModelId) }
        } catch (timeout: TimeoutCancellationException) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            ProjectionEventLog.warning(
                "DISCOVERY",
                "No EasyConn advertisement seen in $DISCOVERY_MAX_ATTEMPTS windows of " +
                    "${DISCOVERY_TIMEOUT_MS / 1000}s each; the T-Box may still be starting up. " +
                    "Sending an active wake probe."
            )
        }

        // Infrastructure fallback: the probe ACK on an AP link only re-arms one more NSD window;
        // the host/port must still come from a genuine advertisement (see TBOX_STREAMING_CONTRACT.md).
        if (sendEasyConnWakeProbe(link)) {
            try {
                return withTimeout(DISCOVERY_TIMEOUT_MS) { discoverWithAndroidNsd(link, expectedModelId) }
            } catch (timeout: TimeoutCancellationException) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
            }
        }
        throw IllegalStateException(
            "The EasyConn service was not advertised in $DISCOVERY_MAX_ATTEMPTS discovery windows of " +
                "${DISCOVERY_TIMEOUT_MS / 1000}s each. This can happen when the official CFMOTO app is " +
                "already connected to the motorcycle, or when the T-Box is still starting up after " +
                "Wi-Fi association."
        )
    }

    /**
     * Discovery for a Wi-Fi Direct group owner dash. NSD is skipped (no bindable Network to run it
     * on); instead the group owner is asked directly with an EasyConn wake probe. A completed ACK is
     * a full CMD_MDNS_RESPOND handshake, so the group owner IS the confirmed EC endpoint - not an
     * invented one - matching what every reference implementation does for P2P dashes.
     */
    private suspend fun discoverOverWifiDirect(link: TBoxLink.WifiDirect): TBoxHost {
        val peerAddress = link.gatewayIp.hostAddress
        if (sendEasyConnWakeProbe(link) && peerAddress != null) {
            ProjectionEventLog.record(
                "DISCOVERY",
                "Wi-Fi Direct EasyConn endpoint confirmed at $peerAddress:$WAKE_PROBE_PORT."
            )
            return TBoxHost(peerAddress, WAKE_PROBE_PORT, SYNTHESIZED_EASYCONN_PACKAGE)
        }
        throw IllegalStateException(
            "The Wi-Fi Direct dash did not answer an EasyConn wake probe at " +
                "${link.gatewayIp.hostAddress}:$WAKE_PROBE_PORT. The dash may still be starting up, " +
                "or the official CFMOTO app may already be connected to it."
        )
    }

    /**
     * Actively asks the T-Box to respond instead of waiting for it to broadcast on its own.
     * Some Wi-Fi Direct group-owner T-Boxes never advertise `_EasyConn._tcp.` proactively; a
     * direct probe on the well-known port 10930 is what OpenCfMoto/OpenMoto observed working
     * for that case. A completed ACK is a full EasyConn CMD_MDNS_RESPOND handshake, so on a
     * Wi-Fi Direct group (where NSD has no bindable Network) the ACK-confirmed endpoint is used
     * directly as the EC host/port; on infrastructure links it only re-arms one more NSD window.
     */
    private suspend fun sendEasyConnWakeProbe(link: TBoxLink): Boolean = withContext(Dispatchers.IO) {
        val peerIp = link.peerHint ?: link.network?.let { network ->
            connectivityManager.getLinkProperties(network)?.let { properties ->
                deriveTBoxPeerIpv4(
                    gateways = properties.routes.filter { route -> route.isDefaultRoute }.mapNotNull { route -> route.gateway },
                    dnsServers = properties.dnsServers,
                    localAddresses = properties.linkAddresses.map { linkAddress -> linkAddress.address to linkAddress.prefixLength }
                )
            }
        }
        if (peerIp == null) {
            ProjectionEventLog.debug("DISCOVERY", "Wake probe skipped: no usable peer IPv4 could be derived.")
            return@withContext false
        }
        ProjectionEventLog.record(
            "DISCOVERY",
            "Sending an EasyConn wake probe to ${peerIp.hostAddress}:$WAKE_PROBE_PORT."
        )
        repeat(WAKE_PROBE_ATTEMPTS) { attempt ->
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            try {
                link.createSocket().use { socket ->
                    socket.connect(InetSocketAddress(peerIp, WAKE_PROBE_PORT), WAKE_PROBE_CONNECT_TIMEOUT_MS)
                    socket.soTimeout = WAKE_PROBE_READ_TIMEOUT_MS
                    writeWakeProbeFrame(socket.getOutputStream())
                    if (readWakeProbeAck(socket.getInputStream())) {
                        ProjectionEventLog.record(
                            "DISCOVERY",
                            "T-Box acknowledged the wake probe on attempt " +
                                "${attempt + 1}/$WAKE_PROBE_ATTEMPTS."
                        )
                        return@withContext true
                    }
                }
                ProjectionEventLog.debug(
                    "DISCOVERY",
                    "Wake probe attempt ${attempt + 1}/$WAKE_PROBE_ATTEMPTS: no acknowledgement."
                )
            } catch (failure: Throwable) {
                ProjectionEventLog.debug(
                    "DISCOVERY",
                    "Wake probe attempt ${attempt + 1}/$WAKE_PROBE_ATTEMPTS to " +
                        "${peerIp.hostAddress}:$WAKE_PROBE_PORT failed: ${failure.message}."
                )
            }
            if (attempt < WAKE_PROBE_ATTEMPTS - 1) delay(WAKE_PROBE_RETRY_DELAY_MS)
        }
        false
    }

    /** 16-byte little-endian header (cmd, totalLen, cmd xor totalLen, reserved) plus JSON payload. */
    private fun writeWakeProbeFrame(out: OutputStream) {
        val payload = WAKE_PROBE_JSON.toByteArray(Charsets.UTF_8)
        val totalLen = WAKE_PROBE_HEADER_SIZE + payload.size
        val header = ByteBuffer.allocate(WAKE_PROBE_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(0, CMD_MDNS_RESPOND)
        header.putInt(4, totalLen)
        header.putInt(8, CMD_MDNS_RESPOND xor totalLen)
        out.write(header.array())
        if (payload.isNotEmpty()) out.write(payload)
        out.flush()
    }

    private fun readWakeProbeAck(input: InputStream): Boolean {
        val header = ByteArray(WAKE_PROBE_HEADER_SIZE)
        if (!readFullyOrFalse(input, header)) return false
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = buffer.getInt(0)
        val totalLen = buffer.getInt(4)
        val magic = buffer.getInt(8)
        if ((cmd xor totalLen) != magic || cmd != CMD_MDNS_RESPOND_ACK) return false
        val payloadLen = (totalLen - WAKE_PROBE_HEADER_SIZE).coerceAtLeast(0)
        val payload = ByteArray(payloadLen)
        if (payloadLen > 0 && !readFullyOrFalse(input, payload)) return false
        return payload.toString(Charsets.UTF_8).contains("true")
    }

    private fun readFullyOrFalse(input: InputStream, buffer: ByteArray): Boolean {
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n <= 0) return false
            read += n
        }
        return true
    }

    private suspend fun discoverWithAndroidNsd(
        link: TBoxLink,
        expectedModelId: String?
    ): TBoxHost = suspendCancellableCoroutine { continuation ->
        val completed = AtomicBoolean(false)
        val multicastLock = wifiManager.createMulticastLock("$TAG.mDns").apply {
            setReferenceCounted(false)
            acquire()
        }
        ProjectionEventLog.debug("DISCOVERY", "mDNS multicast lock acquired.")
        lateinit var listener: NsdManager.DiscoveryListener
        var serviceCallback: NsdManager.ServiceInfoCallback? = null
        val discoveryStopped = AtomicBoolean(false)

        fun stopDiscovery() {
            if (!discoveryStopped.compareAndSet(false, true)) return
            serviceCallback?.let { callback ->
                runCatching { nsdManager.unregisterServiceInfoCallback(callback) }
            }
            runCatching { nsdManager.stopServiceDiscovery(listener) }
            if (multicastLock.isHeld) multicastLock.release()
            ProjectionEventLog.debug("DISCOVERY", "NSD discovery stopped and multicast lock released.")
        }

        fun finish(result: Result<TBoxHost>) {
            if (!completed.compareAndSet(false, true)) return
            stopDiscovery()
            continuation.resumeWith(result)
        }

        listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) {
                Log.d(TAG, "Android NSD discovery started: $serviceType")
                ProjectionEventLog.record("DISCOVERY", "Android NSD started for serviceType=$serviceType.")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                if (serviceInfo == null || !serviceInfo.serviceType.endsWith(SERVICE_TYPE)) return
                if (serviceCallback != null) return
                ProjectionEventLog.record(
                    "DISCOVERY",
                    "NSD candidate found: name=${serviceInfo.serviceName}, type=${serviceInfo.serviceType}."
                )
                val callback = object : NsdManager.ServiceInfoCallback {
                    override fun onServiceUpdated(resolved: NsdServiceInfo) {
                        if (!link.matchesResolvedNetwork(resolved.network)) return
                        val attributes = resolved.attributes
                        val simulatorProfileRequested =
                            TBoxModelProfile.fromModelId(expectedModelId) == TBoxModelProfile.MOTO_HUB_SIMULATOR
                        val advertisedModelId = attributes[MODEL_ID_ATTRIBUTE]
                            ?.toString(Charsets.UTF_8)
                            ?.trim()
                        if (
                            simulatorProfileRequested &&
                            !isMotoHubSimulatorAdvertisement(resolved.serviceName, advertisedModelId)
                        ) {
                            ProjectionEventLog.warning(
                                "DISCOVERY",
                                "Ignoring EasyConn candidate ${resolved.serviceName}: " +
                                    "it is not an identified MOTO-HUB simulator preset (modelId=$advertisedModelId)."
                            )
                            serviceCallback = null
                            runCatching { nsdManager.unregisterServiceInfoCallback(this) }
                            return
                        }
                        val packageName = decodeEasyConnPackage(attributes[PACKAGE_ATTRIBUTE])
                        if (packageName == null) {
                            Log.w(TAG, "EasyConn service resolved without package metadata")
                            ProjectionEventLog.warning("DISCOVERY", "Resolved EasyConn service has no package metadata.")
                            return
                        }

                        val advertisedIp = attributes[IP_ATTRIBUTE]
                            ?.toString(Charsets.UTF_8)
                            ?.let(::parseUsableEasyConnIpv4Literal)
                        val resolvedIp = resolved.hostAddresses
                            .filterIsInstance<Inet4Address>()
                            .firstOrNull(::isUsableTBoxIpv4Address)
                            ?.hostAddress
                        val unusableResolvedIp = resolved.hostAddresses
                            .filterIsInstance<Inet4Address>()
                            .firstOrNull()
                            ?.hostAddress
                        val derivedIp = if (!simulatorProfileRequested && advertisedIp == null && resolvedIp == null) {
                            link.peerHint?.hostAddress ?: link.network?.let { activeNetwork ->
                                connectivityManager.getLinkProperties(activeNetwork)?.let { linkProperties ->
                                    deriveTBoxPeerIpv4(
                                        gateways = linkProperties.routes
                                            .filter { it.isDefaultRoute }
                                            .mapNotNull { it.gateway },
                                        dnsServers = linkProperties.dnsServers,
                                        localAddresses = linkProperties.linkAddresses
                                            .map { it.address to it.prefixLength }
                                    )
                                }?.hostAddress
                            }
                        } else {
                            null
                        }
                        val ipAddress = advertisedIp ?: resolvedIp ?: derivedIp
                        val port = resolved.port
                        if (ipAddress.isNullOrBlank() || port !in 1..65535) {
                            Log.w(TAG, "EasyConn service resolved without a usable host")
                            ProjectionEventLog.warning(
                                "DISCOVERY",
                                "Resolved EasyConn service has invalid endpoint: " +
                                    "advertisedIp=${attributes[IP_ATTRIBUTE]?.toString(Charsets.UTF_8)}, " +
                                    "resolvedIp=$unusableResolvedIp, port=$port."
                            )
                            return
                        }
                        if (derivedIp != null) {
                            ProjectionEventLog.warning(
                                "DISCOVERY",
                                "EasyConn advertised no IPv4 host; using network-derived peer $derivedIp."
                            )
                        }
                        ProjectionEventLog.record(
                            "DISCOVERY",
                            "NSD resolution accepted: $ipAddress:$port, package=$packageName, network=${resolved.network}."
                        )
                        finish(Result.success(TBoxHost(ipAddress, port, packageName)))
                    }

                    override fun onServiceLost() = Unit

                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                        serviceCallback = null
                        Log.w(TAG, "EasyConn service callback failed: $errorCode")
                        ProjectionEventLog.warning("DISCOVERY", "Service info callback registration failed: $errorCode.")
                    }

                    override fun onServiceInfoCallbackUnregistered() = Unit
                }
                serviceCallback = callback
                runCatching {
                    nsdManager.registerServiceInfoCallback(serviceInfo, callbackExecutor, callback)
                }.onFailure {
                    serviceCallback = null
                    Log.w(TAG, "Unable to register EasyConn service callback", it)
                    ProjectionEventLog.warning("DISCOVERY", "Unable to register NSD service info callback.", it)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                ProjectionEventLog.warning("DISCOVERY", "NSD service lost: ${serviceInfo?.serviceName}.")
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                ProjectionEventLog.debug("DISCOVERY", "Android NSD stopped for serviceType=$serviceType.")
            }

            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                ProjectionEventLog.error("DISCOVERY", "Android NSD start failed: type=$serviceType, code=$errorCode.")
                finish(Result.failure(IllegalStateException("Android NSD start failed: $errorCode")))
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.w(TAG, "Android NSD stop failed: $errorCode")
                ProjectionEventLog.warning("DISCOVERY", "Android NSD stop failed: code=$errorCode.")
            }
        }

        continuation.invokeOnCancellation { stopDiscovery() }
        runCatching {
            link.startNsdDiscovery(nsdManager, SERVICE_TYPE, callbackExecutor, listener)
        }.onFailure { finish(Result.failure(it)) }
    }

    private inner class SessionCallback : MobileCallback {
        override fun onError(message: String?, fatal: Boolean) {
            Log.w(TAG, "T-Box error fatal=$fatal: ${message.orEmpty()}")
            val detail = message.orEmpty().ifBlank { "EasyConn error without details." }
            if (fatal) {
                ProjectionEventLog.error("TBOX", "RideDaemon fatal callback: $detail")
            } else {
                ProjectionEventLog.warning("TBOX", "RideDaemon warning callback: $detail")
            }
            if (fatal) {
                mutableEvents.tryEmit(TBoxEvent.FatalError(detail))
            } else {
                mutableEvents.tryEmit(TBoxEvent.Warning(detail))
            }
        }

        override fun onEvent(time: Long, type: Long, command: Long, payload: ByteArray?) {
            Log.d(TAG, "T-Box event type=$type command=$command bytes=${payload?.size ?: 0}")
            val verbose = MotoHubSettings.verboseTBoxLogging(appContext)
            val now = SystemClock.elapsedRealtime()
            val sequence = when (type) {
                PXC_EVENT_SOURCE -> {
                    lastPxcEventElapsed.set(now)
                    pxcEvents.incrementAndGet()
                }
                MEDIA_CONTROL_EVENT_SOURCE -> {
                    lastMediaControlEventElapsed.set(now)
                    mediaControlEvents.incrementAndGet()
                }
                else -> 0L
            }
            if (type == PXC_EVENT_SOURCE || type == MEDIA_CONTROL_EVENT_SOURCE) {
                val commandName = protocolCommandName(type, command)
                ProjectionEventLog.debug(
                    "TBOX",
                    "${protocolSourceName(type)} RX #$sequence command=" +
                        "0x${command.toString(16)} ($commandName) " +
                        "bytes=${payload?.size ?: 0}."
                )
                // Unrecognized opcode: the name table only knows a handful of commands (see
                // protocolCommandName), so most CFDL26/CFDL16 control messages show as
                // UNKNOWN. Dumping the payload here is how those get identified later - it's
                // exactly how open-cfmoto's own log let us learn what several of these opcodes
                // are, which this app currently can't name either.
                if (verbose && commandName == "UNKNOWN" && payload != null && payload.isNotEmpty()) {
                    ProjectionEventLog.debug(
                        "TBOX",
                        "Unknown command 0x${command.toString(16)} payload (verbose): ${payload.toDiagnosticHex()}."
                    )
                }
            }
            if (type == PXC_EVENT_SOURCE) {
                ProjectionEventLog.debug(
                    "TBOX",
                    "PXC event received: command=$command, bytes=${payload?.size ?: 0}."
                )
            }
            if (type == PXC_EVENT_SOURCE && command == PXC_HUD_CONFIG_COMMAND) {
                val capabilities = payload?.let(::decodeTBoxCapabilities)
                if (capabilities == null) {
                    ProjectionEventLog.warning("TBOX", "Unable to decode the T-Box CLIENT_INFO payload.")
                } else {
                    if (verbose) {
                        // Full raw CLIENT_INFO, not just the few fields TBoxCapabilities
                        // extracts - ProjectionEventLog.redact() strips password/pin-shaped
                        // fields (including btPin) before this reaches the log file.
                        val rawJson = payload.toString(Charsets.UTF_8).trim().trimEnd(' ')
                        ProjectionEventLog.debug("TBOX", "CLIENT_INFO raw (verbose): $rawJson")
                    }
                    ProjectionEventLog.record(
                        "TBOX",
                        "T-Box capabilities received: hu=${capabilities.huName ?: "not reported"}, " +
                            "pxc=${capabilities.pxcVersion ?: "not reported"}, " +
                            "touch=${capabilities.screenTouch ?: "not reported"}."
                    )
                    mutableEvents.tryEmit(TBoxEvent.Capabilities(capabilities))
                }
                return
            }
            if (type != MEDIA_CONTROL_EVENT_SOURCE) return
            if (command == MEDIA_STREAM_START_COMMAND) {
                ProjectionEventLog.record(
                    "TBOX",
                    "TFT video consumer is ready; requesting a fresh decoder sync frame."
                )
                mutableEvents.tryEmit(TBoxEvent.VideoStreamStart)
                return
            }
            val eventPayload = payload ?: return
            if (command == MEDIA_TOUCH_COMMAND) {
                decodeTBoxTouch(eventPayload)?.let(mutableEvents::tryEmit)
                return
            }
            if (command == MEDIA_CAPTURE_CONFIG_COMMAND) {
                decodeTBoxVideoArea(eventPayload)?.let { area ->
                    ProjectionEventLog.record(
                        "TBOX",
                        "TFT capture area requested: ${area.width}x${area.height}."
                    )
                    mutableEvents.tryEmit(area)
                }
                return
            }
            runCatching {
                val safeArea = org.json.JSONObject(eventPayload.toString(Charsets.UTF_8))
                    .optJSONObject("viewAreaConfig")
                    ?.optJSONArray("viewAreas")
                    ?.optJSONObject(0)
                    ?.optJSONObject("safeArea")
                    ?: return@runCatching
                val width = safeArea.optInt("width")
                val height = safeArea.optInt("height")
                if (width > 0 && height > 0) {
                    ProjectionEventLog.record("TBOX", "TFT safe area received: ${width}x$height.")
                    mutableEvents.tryEmit(TBoxEvent.VideoArea(width, height))
                }
            }.onFailure {
                Log.w(TAG, "Invalid EasyConn screen configuration", it)
                ProjectionEventLog.warning("TBOX", "Invalid EasyConn screen configuration payload.", it)
            }
        }

        override fun onStopped() {
            Log.i(TAG, "T-Box session stopped")
            ProjectionEventLog.warning(
                "TBOX",
                "RideDaemon reported that the T-Box session stopped. ${protocolSnapshot()}"
            )
            mutableEvents.tryEmit(TBoxEvent.Stopped)
        }

    }

    private fun resetProtocolStats() {
        pxcEvents.set(0L)
        mediaControlEvents.set(0L)
        framesOffered.set(0L)
        framesTimedOut.set(0L)
        framesRejected.set(0L)
        lastPxcEventElapsed.set(0L)
        lastMediaControlEventElapsed.set(0L)
        lastFrameOfferedElapsed.set(0L)
    }

    private fun protocolSnapshot(): String {
        val now = SystemClock.elapsedRealtime()
        fun age(last: AtomicLong): String = last.get().takeIf { it > 0L }?.let {
            "${(now - it).coerceAtLeast(0L)}ms ago"
        } ?: "never"
        return "protocolStats=" +
            "pxcRx=${pxcEvents.get()} (last=${age(lastPxcEventElapsed)}), " +
            "mediaCtrlRx=${mediaControlEvents.get()} (last=${age(lastMediaControlEventElapsed)}), " +
            "framesOffered=${framesOffered.get()} (last=${age(lastFrameOfferedElapsed)}), " +
            "frameTimeouts=${framesTimedOut.get()}, frameRejections=${framesRejected.get()}"
    }

    private companion object {
        const val TAG = "RideDaemonTransport"
        const val SERVICE_TYPE = "_EasyConn._tcp."
        const val PACKAGE_ATTRIBUTE = "packagename"
        const val MODEL_ID_ATTRIBUTE = "modelid"
        const val SIMULATOR_MODEL_ID = MOTO_HUB_SIMULATOR_MODEL_ID
        const val IP_ATTRIBUTE = "ip"
        const val DISCOVERY_TIMEOUT_MS = 15_000L
        const val DISCOVERY_MAX_ATTEMPTS = 2
        const val DISCOVERY_RETRY_DELAY_MS = 500L
        const val EC_CONNECT_TIMEOUT_MS = 10_000
        // Wake-probe fallback (see sendEasyConnWakeProbe): well-known port and frame layout
        // reverse-engineered by OpenCfMoto/OpenMoto, not part of the advertised EasyConn contract.
        const val WAKE_PROBE_PORT = 10930
        const val WAKE_PROBE_ATTEMPTS = 3
        const val WAKE_PROBE_CONNECT_TIMEOUT_MS = 3_000
        const val WAKE_PROBE_READ_TIMEOUT_MS = 5_000
        const val WAKE_PROBE_RETRY_DELAY_MS = 1_000L
        const val WAKE_PROBE_HEADER_SIZE = 16
        const val CMD_MDNS_RESPOND = 0x70000010
        const val CMD_MDNS_RESPOND_ACK = 0x70000011
        // Only used to build a TBoxHost when a Wi-Fi Direct group has confirmed the EC endpoint via
        // a wake-probe ACK but NSD cannot resolve the package (no bindable Network over P2P). This
        // is the package the reference implementations present to CFMoto dashes.
        const val SYNTHESIZED_EASYCONN_PACKAGE = "com.cfmoto.cfmotointernational"
        const val WAKE_PROBE_JSON =
            "{\"phoneType\":\"Android\",\"packageName\":\"com.cfmoto.cfmotointernational\"}"
        const val MEDIA_CONTROL_EVENT_SOURCE = 3L
        const val PXC_EVENT_SOURCE = 2L
        const val PXC_HEARTBEAT_COMMAND = 0x70000000L
        const val PXC_HEARTBEAT_ACK_COMMAND = 0x70000001L
        const val PXC_CLOCK_KEEPALIVE_COMMAND = 0x10600L
        const val MEDIA_CONTROL_PING_COMMAND = 64L
        const val PXC_HUD_CONFIG_COMMAND = 65_552L
        const val MEDIA_CAPTURE_CONFIG_COMMAND = 16L
        const val MEDIA_TOUCH_COMMAND = 32L
        const val MEDIA_STREAM_START_COMMAND = 112L

        fun protocolSourceName(type: Long): String = when (type) {
            PXC_EVENT_SOURCE -> "PXC"
            MEDIA_CONTROL_EVENT_SOURCE -> "MEDIA_CONTROL"
            else -> "UNKNOWN"
        }

        fun protocolCommandName(type: Long, command: Long): String = when {
            type == PXC_EVENT_SOURCE && command == PXC_HEARTBEAT_COMMAND -> "HEARTBEAT"
            type == PXC_EVENT_SOURCE && command == PXC_HEARTBEAT_ACK_COMMAND -> "HEARTBEAT_ACK"
            type == PXC_EVENT_SOURCE && command == PXC_CLOCK_KEEPALIVE_COMMAND -> "CLOCK_KEEPALIVE"
            type == MEDIA_CONTROL_EVENT_SOURCE && command == MEDIA_CONTROL_PING_COMMAND -> "PING"
            type == MEDIA_CONTROL_EVENT_SOURCE && command == MEDIA_STREAM_START_COMMAND -> "STREAM_START"
            else -> "UNKNOWN"
        }
    }
}

internal fun decodeEasyConnPackage(value: ByteArray?): String? = value
    ?.toString(Charsets.UTF_8)
    ?.trim()
    ?.takeIf(String::isNotBlank)

internal fun decodeTBoxVideoArea(payload: ByteArray): TBoxEvent.VideoArea? {
    if (payload.size < 4) return null
    val body = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
    val width = body.getShort(0).toInt() and 0xFFFF
    val height = body.getShort(2).toInt() and 0xFFFF
    return if (width > 0 && height > 0) TBoxEvent.VideoArea(width, height) else null
}

internal fun decodeTBoxTouch(payload: ByteArray): TBoxEvent.Touch? {
    if (payload.size < 8) return null
    val body = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
    val action = when (body.getShort(0).toInt() and 0xFFFF) {
        2 -> 0 // DOWN
        1 -> 1 // UP
        3 -> 2 // MOVE
        else -> return null
    }
    val x = body.getShort(2).toInt() and 0xFFFF
    val y = body.getShort(4).toInt() and 0xFFFF
    val pointerId = body.getShort(6).toInt() and 0xFFFF
    return TBoxEvent.Touch(action, pointerId, x, y)
}

internal fun deriveTBoxPeerIpv4(
    gateways: List<InetAddress>,
    dnsServers: List<InetAddress>,
    localAddresses: List<Pair<InetAddress, Int>>
): Inet4Address? {
    val localIpv4 = localAddresses.mapNotNull { (address, prefixLength) ->
        (address as? Inet4Address)?.let { it to prefixLength }
    }.filter { (address, _) -> isUsableTBoxIpv4Address(address) }
    if (localIpv4.isEmpty()) return null

    val routedCandidate = (gateways + dnsServers)
        .filterIsInstance<Inet4Address>()
        .firstOrNull { candidate ->
            isUsableTBoxIpv4Address(candidate) && localIpv4.any { (local, prefixLength) ->
                candidate != local && isSameIpv4Subnet(candidate, local, prefixLength)
            }
        }
    if (routedCandidate != null) return routedCandidate

    val (local, prefixLength) = localIpv4.firstOrNull { (_, prefix) -> prefix in 1..31 }
        ?: return null
    val octets = local.address
    val ip = ((octets[0].toInt() and 0xFF) shl 24) or
        ((octets[1].toInt() and 0xFF) shl 16) or
        ((octets[2].toInt() and 0xFF) shl 8) or
        (octets[3].toInt() and 0xFF)
    val mask = -1 shl (32 - prefixLength)
    val groupOwner = (ip and mask) or 1
    if (groupOwner == ip) return null
    return InetAddress.getByAddress(
        byteArrayOf(
            (groupOwner ushr 24).toByte(),
            (groupOwner ushr 16).toByte(),
            (groupOwner ushr 8).toByte(),
            groupOwner.toByte()
        )
    ) as Inet4Address
}

internal fun parseIpv4Literal(value: String): String? {
    val octets = value.trim().split('.')
    if (octets.size != 4) return null
    val numbers = octets.map { part ->
        if (part.isEmpty() || part.any { !it.isDigit() }) return null
        part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
    }
    return numbers.joinToString(".")
}

internal fun parseUsableEasyConnIpv4Literal(value: String): String? {
    val literal = parseIpv4Literal(value) ?: return null
    val address = InetAddress.getByName(literal)
    return literal.takeIf { isUsableTBoxIpv4Address(address) }
}

internal fun isMotoHubSimulatorAdvertisement(serviceName: String?, modelId: String?): Boolean {
    val normalizedName = serviceName?.trim().orEmpty()
    val normalizedModelId = modelId?.trim().orEmpty()
	if (normalizedModelId == MOTO_HUB_SIMULATOR_MODEL_ID) return true
    if (normalizedName.startsWith("MOTO-HUB T-Box Simulator")) return true
    return normalizedModelId in setOf(
        "37416",
        "37426",
        "66660703",
        "66660721",
        "66660732",
        "66660742"
    ) && (
        normalizedName.startsWith("CFDL") ||
            normalizedName.startsWith("CFMOTO-") ||
            normalizedName.startsWith("800NK")
        )
}

private fun isSameIpv4Subnet(first: Inet4Address, second: Inet4Address, prefixLength: Int): Boolean {
    if (prefixLength !in 1..32) return false
    val fullBytes = prefixLength / 8
    val remainingBits = prefixLength % 8
    val firstBytes = first.address
    val secondBytes = second.address
    for (index in 0 until fullBytes) {
        if (firstBytes[index] != secondBytes[index]) return false
    }
    if (remainingBits == 0) return true
    val mask = (0xFF shl (8 - remainingBits)) and 0xFF
    return (firstBytes[fullBytes].toInt() and mask) ==
        (secondBytes[fullBytes].toInt() and mask)
}

/** Space-separated lowercase hex, e.g. "7b 0a 20 20" - only ever used behind verbose logging. */
private fun ByteArray.toDiagnosticHex(): String = joinToString(" ") { byte -> "%02x".format(byte) }
