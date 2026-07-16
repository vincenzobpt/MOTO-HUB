package io.motohub.android.tbox

import android.content.Context
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.ContextCompat
import api.Api
import api.MobileCallback
import api.MobileSession
import io.motohub.android.session.ProjectionEventLog
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Kotlin boundary around the GPL gomobile binding. Network selection stays outside this class. */
class RideDaemonTransport(
    context: Context
) : TBoxTransport {
    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(NsdManager::class.java)
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private val callbackExecutor = ContextCompat.getMainExecutor(appContext)
    private val mutableEvents = MutableSharedFlow<TBoxEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val events: Flow<TBoxEvent> = mutableEvents.asSharedFlow()
    private var session: MobileSession? = null
    private var sessionNetwork: Network? = null

    override suspend fun discover(network: Network): Result<TBoxHost> = withContext(Dispatchers.IO) {
        ProjectionEventLog.record("DISCOVERY", "Starting Android NSD discovery on T-Box network=$network.")
        runCatching {
            stopSession()
            val host = withTimeout(DISCOVERY_TIMEOUT_MS) {
                discoverWithAndroidNsd(network)
            }
            val createdSession = Api.newMobileSession(
                Api.newMobileConfig(
                    ByteArray(0),
                    30L,
                    10L,
                    5L,
                    10L,
                    3L
                ),
                SessionCallback()
            )
            session = createdSession
            sessionNetwork = network
            createdSession.setECHost(
                Api.newStreamHost(host.ipAddress, host.port.toString(), host.packageName)
            )
            ProjectionEventLog.record(
                "DISCOVERY",
                "RideDaemon live-only session configured for ${host.ipAddress}:${host.port}; " +
                    "package=${host.packageName}."
            )
            host
        }.onFailure {
            ProjectionEventLog.error("DISCOVERY", "RideDaemon discovery/configuration failed.", it)
            stopSession()
        }
    }

    override suspend fun start(host: TBoxHost): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                ProjectionEventLog.record(
                    "TBOX",
                    "Starting EasyConn handshake to ${host.ipAddress}:${host.port}; " +
                        "waiting for the TFT video area."
                )
                val activeSession = checkNotNull(session) {
                    "Call discover() before starting the T-Box session"
                }
                val activeNetwork = checkNotNull(sessionNetwork) {
                    "Call discover() with an active T-Box network before starting the session"
                }
                startWithNetworkSocket(activeSession, host, activeNetwork)
                ProjectionEventLog.record("TBOX", "RideDaemon startSessionWithSocketFd returned successfully.")
            }.onFailure { ProjectionEventLog.error("TBOX", "EasyConn handshake failed.", it) }
        }

    override fun offerAccessUnit(avcc: ByteArray): Boolean {
        val activeSession = session ?: return false
        return runCatching {
            if (!activeSession.isRunning) return false
            activeSession.pushFrame(avcc)
            true
        }.getOrElse {
            Log.w(TAG, "Unable to offer AVC access unit", it)
            ProjectionEventLog.error("TBOX", "Unable to push an AVC access unit to RideDaemon.", it)
            false
        }
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        stopSession()
    }

    private fun stopSession() {
        if (session != null) ProjectionEventLog.record("TBOX", "Stopping RideDaemon session.")
        session?.runCatching { stopSession() }
            ?.onFailure { ProjectionEventLog.warning("TBOX", "RideDaemon stopSession failed.", it) }
        session = null
        sessionNetwork = null
    }

    /** Opens the EasyConn command socket through Android's selected T-Box network. */
    private fun startWithNetworkSocket(
        activeSession: MobileSession,
        host: TBoxHost,
        network: Network
    ) {
        val socket: Socket = network.socketFactory.createSocket()
        var descriptorTransferred = false
        try {
            ProjectionEventLog.debug(
                "TBOX",
                "Opening network-bound command socket to ${host.ipAddress}:${host.port} on network=$network."
            )
            socket.connect(InetSocketAddress(host.ipAddress, host.port), EC_CONNECT_TIMEOUT_MS)
            ProjectionEventLog.record("TBOX", "EasyConn TCP command socket connected.")
            val descriptor = ParcelFileDescriptor.fromSocket(socket)
            val fd = descriptor.detachFd().toLong()
            descriptorTransferred = true
            activeSession.startSessionWithSocketFd(fd)
        } finally {
            // After detachFd(), Go owns and closes the descriptor during the init handshake.
            if (!descriptorTransferred) socket.close()
        }
    }

    private suspend fun discoverWithAndroidNsd(network: Network): TBoxHost = suspendCancellableCoroutine { continuation ->
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
                        if (resolved.network != network) return
                        val attributes = resolved.attributes
                        val packageName = decodeEasyConnPackage(attributes[PACKAGE_ATTRIBUTE])
                        if (packageName == null) {
                            Log.w(TAG, "EasyConn service resolved without package metadata")
                            ProjectionEventLog.warning("DISCOVERY", "Resolved EasyConn service has no package metadata.")
                            return
                        }

                        val ipAddress = attributes[IP_ATTRIBUTE]
                            ?.toString(Charsets.UTF_8)
                            ?: resolved.hostAddresses.firstOrNull()?.hostAddress
                        val port = resolved.port
                        if (ipAddress.isNullOrBlank() || port !in 1..65535) {
                            Log.w(TAG, "EasyConn service resolved without a usable host")
                            ProjectionEventLog.warning(
                                "DISCOVERY",
                                "Resolved EasyConn service has invalid endpoint: ip=$ipAddress, port=$port."
                            )
                            return
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
            nsdManager.discoverServices(
                SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                network,
                callbackExecutor,
                listener
            )
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
            ProjectionEventLog.warning("TBOX", "RideDaemon reported that the T-Box session stopped.")
            mutableEvents.tryEmit(TBoxEvent.Stopped)
        }

    }

    private companion object {
        const val TAG = "RideDaemonTransport"
        const val SERVICE_TYPE = "_EasyConn._tcp."
        const val PACKAGE_ATTRIBUTE = "packagename"
        const val IP_ATTRIBUTE = "ip"
        const val DISCOVERY_TIMEOUT_MS = 15_000L
        const val EC_CONNECT_TIMEOUT_MS = 10_000
        const val MEDIA_CONTROL_EVENT_SOURCE = 3L
        const val MEDIA_CAPTURE_CONFIG_COMMAND = 16L
        const val MEDIA_TOUCH_COMMAND = 32L
        const val MEDIA_STREAM_START_COMMAND = 112L
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
    val y = body.getInt(4)
    return TBoxEvent.Touch(action, x, y)
}
