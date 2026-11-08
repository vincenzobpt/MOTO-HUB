// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import java.net.Inet4Address
import java.net.InetAddress
import java.util.Date
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Gets a dashboard onto a network over Bluetooth, for the dashboards that have no other way on.
 *
 * A Zontes S350 - and every other dash that prints the opaque `CARBIT` + 12 hex QR and then sits
 * there - never brings up an access point, never answers a Wi-Fi Direct scan, and never shows the
 * rider any credentials to type. The official app does not do anything cleverer over Wi-Fi; it
 * talks to the dash over BLE first, hosts a network of its own, and hands the credentials across.
 * That exchange is [EcBtpNetProtocol]; this is the link it runs on.
 *
 * Order matters and is the reason this is safe to try from a fallback path: nothing is created
 * until a dash has been found, has identified itself as speaking the build-net protocol, and has
 * *asked* for a phone-hosted network. A rider who merely forgot to turn their hotspot on never
 * gets a hotspot started behind their back, because no dash ever asked for one.
 *
 * Once provisioning succeeds the link stays open for the session. The dash keeps using it - the
 * clock requests [EcBtpProtocol] already answers arrive here too - and dropping it is what tells
 * the dash the phone is gone.
 */
@SuppressLint("MissingPermission")
internal class EcBtpNetLink(
    context: Context,
    private val log: (String) -> Unit,
    private val hotspot: PhoneHostedNetwork,
    private val now: () -> Date = { Date() },
    private val zone: () -> TimeZone = { TimeZone.getDefault() }
) {

    /**
     * What the exchange produced: the network the phone is now hosting, and where the dash says
     * it landed on it.
     *
     * [dashIp] is a hint, not a promise. A dash that joins but never announces its address is
     * still reachable - [TBoxHotspotScan] sweeps the subnet - so a missing address costs seconds,
     * not the session, and this succeeds without one.
     */
    data class Provisioned(
        val subnet: TBoxHotspotScan.Subnet,
        val dashIp: Inet4Address?,
        val dashModelId: String?,
        val dashName: String
    )

    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)

    private val scheduler = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "MotoHubEcBtpNetBle").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    private val closed = AtomicBoolean(false)
    private val discoveryStarted = AtomicBoolean(false)

    /** Asked at most once, from whichever of the handshake and the nudge below gets there first. */
    private val buildNetRequested = AtomicBoolean(false)
    private val ready = CompletableDeferred<Result<String>>()

    /** Frames the dash sends, in arrival order, for [provision] to consume in sequence. */
    private val inbound = Channel<EcBtpProtocol.Frame>(Channel.UNLIMITED)

    private val assembler = EcBtpNetProtocol.FrameAssembler()

    @Volatile
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    @Volatile
    private var notifyUuid: UUID? = null

    @Volatile
    private var scanStop: ScheduledFuture<*>? = null

    /** Guards the one-write-in-flight rule Android enforces per GATT connection. */
    private val queueLock = Any()
    private val writeQueue = ArrayDeque<ByteArray>()
    private var inFlight: ByteArray? = null
    private var inFlightAttempts = 0
    private var writeGeneration = 0L

    /**
     * Finds the dash, runs the build-net exchange, and returns the network it agreed to join.
     *
     * Fails - never throws - for everything a rider can act on, because on this path a failure is
     * usually "no dash was in range", which is exactly what a fallback caller needs to hear.
     */
    suspend fun provision(scanTimeoutMillis: Long = SCAN_TIMEOUT_MS): Result<Provisioned> {
        val connected = connect(scanTimeoutMillis)
        val dashName = connected.getOrElse { failure ->
            close()
            return Result.failure(failure)
        }

        val outcome = runCatching { runExchange(dashName) }
            .getOrElse { failure -> Result.failure(failure) }
        if (outcome.isFailure) close()
        return outcome
    }

    /**
     * Drives the exchange. Written as a sequence rather than a callback web on purpose: every step
     * here is "say one thing, wait for one answer", and a state machine spread across GATT
     * callbacks is how the ordering bugs in this protocol family have historically been born.
     */
    private suspend fun runExchange(dashName: String): Result<Provisioned> {
        send(EcBtpNetProtocol.clientInfo())
        log("Sent the Bluetooth client info to $dashName; waiting for its handshake.")
        // A dash that never answers the client info is not necessarily a dash that would refuse
        // the question. Nothing in the protocol says the handshake has to come first, and by this
        // point the peer has already proved what it is by exposing the setup service, so asking
        // anyway costs one frame and rescues a firmware that simply waits to be asked.
        schedule(HANDSHAKE_NUDGE_MS) {
            if (requestBuildNetOnce()) {
                log("$dashName has not sent a handshake; asking for a network anyway.")
            }
        }

        var modelId: String? = null
        var authRetries = 0
        var hosted: PhoneHostedNetwork.Credentials? = null
        var subnet: TBoxHotspotScan.Subnet? = null
        var deadline = System.currentTimeMillis() + EXCHANGE_TIMEOUT_MS

        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) {
                // Once the credentials are out, a dash that never announces its address is not a
                // failure: it is a dash that joined quietly, and the subnet sweep finds it in
                // seconds. Failing here instead would throw away a working network.
                val built = subnet
                if (built != null) {
                    log("$dashName never reported its address; letting discovery sweep ${built.interfaceName}.")
                    return Result.success(
                        Provisioned(subnet = built, dashIp = null, dashModelId = modelId, dashName = dashName)
                    )
                }
                return Result.failure(
                    IllegalStateException("The dashboard stopped answering during Bluetooth pairing.")
                )
            }
            val received = withTimeoutOrNull(remaining) { inbound.receiveCatching() } ?: continue
            val frame = received.getOrNull() ?: run {
                // The link dropped. If the network was already built and handed over, the dash may
                // simply have stopped needing Bluetooth; the session continues over Wi-Fi.
                val built = subnet
                return if (built != null) {
                    Result.success(
                        Provisioned(subnet = built, dashIp = null, dashModelId = modelId, dashName = dashName)
                    )
                } else {
                    Result.failure(
                        IllegalStateException("The dashboard closed the Bluetooth link during pairing.")
                    )
                }
            }

            when (frame.command) {
                EcBtpNetProtocol.CMD_CLIENT_INFO,
                EcBtpNetProtocol.CMD_HANDSHAKE_RESPONSE -> {
                    val handshake = EcBtpNetProtocol.parseHandshake(frame.payload)
                    if (handshake == null) {
                        // Unreadable, not hostile: this is a dash answering on the dash's own
                        // service. Ask for a network rather than stalling over a field that some
                        // firmware may simply not send.
                        log("$dashName sent a handshake this app cannot read; asking for a network anyway.")
                        requestBuildNetOnce()
                        continue
                    }
                    modelId = handshake.modelId ?: modelId
                    log(
                        "$dashName handshake: supportFunction=${handshake.supportFunction}" +
                            (handshake.modelId?.let { ", modelId=$it" } ?: "") + "."
                    )
                    if (!handshake.supportsBuildNet) {
                        return Result.failure(
                            IllegalStateException(
                                "This dashboard's Bluetooth link does not offer to set up a " +
                                    "network, so MOTO-HUB has no way to reach it."
                            )
                        )
                    }
                    if (requestBuildNetOnce()) {
                        log("Asked $dashName how it wants to be put on a network.")
                    }
                }

                EcBtpNetProtocol.CMD_REQUEST_BUILD_NET -> {
                    val status = EcBtpNetProtocol.parseBuildNet(frame.payload)
                    if (status == null) {
                        log("$dashName sent a build-network answer this app cannot read; ignoring it.")
                        continue
                    }
                    when (status.status) {
                        EcBtpNetProtocol.STATUS_USE_PHONE_AP -> {
                            if (hosted != null) continue
                            if (status.phoneApFrequencyMhz != 0) {
                                // A Wi-Fi Direct group honours it; a local-only hotspot cannot -
                                // Android picks that band - and a dash that asked for 5GHz and got
                                // 2.4GHz would otherwise fail for no visible reason.
                                log("$dashName asked for a ${status.phoneApFrequencyMhz}MHz network.")
                            }
                            val credentials = hotspot.start(HOTSPOT_TIMEOUT_MS, status.phoneApFrequencyMhz)
                                .getOrElse { failure -> return Result.failure(failure) }
                            val found = awaitHostedSubnet()
                                ?: return Result.failure(
                                    IllegalStateException(
                                        "The hotspot started but no network interface appeared for " +
                                            "it, so the dashboard could not be told where to go."
                                    )
                                )
                            hosted = credentials
                            subnet = found
                            val announcement = EcBtpNetProtocol.phoneApInfo(
                                ssid = credentials.ssid,
                                password = credentials.passphrase,
                                auth = credentials.auth,
                                ip = found.localAddress.hostAddress
                            ) ?: return Result.failure(
                                IllegalStateException("The hotspot's name and password are too long to send over Bluetooth.")
                            )
                            send(announcement)
                            deadline = System.currentTimeMillis() + ANNOUNCEMENT_TIMEOUT_MS
                            log(
                                "Told $dashName to join \"${credentials.ssid}\" on " +
                                    "${found.interfaceName} (${found.localAddress.hostAddress})."
                            )
                        }

                        EcBtpNetProtocol.STATUS_PHONE_JOINS_DASH -> {
                            // The mirror image of this path: the dash has a network and expects
                            // the PHONE to join it. MOTO-HUB can do that - it is the ordinary
                            // access-point or Wi-Fi Direct connect - but not from here, and the
                            // rider needs to be told which one to pick rather than left guessing.
                            val where = status.dashSsid
                                ?: status.dashMac?.let { "the dash at $it" }
                                ?: "its own network"
                            return Result.failure(
                                IllegalStateException(
                                    "This dashboard wants the phone to join $where instead of " +
                                        "hosting the network" +
                                        if (status.dashMode == EcBtpNetProtocol.CAR_NET_MODE_P2P) {
                                            ", over Wi-Fi Direct. Set this motorcycle's connection " +
                                                "mode to Wi-Fi Direct and connect again."
                                        } else {
                                            ". Set this motorcycle's connection mode to Access " +
                                                "point and connect again."
                                        }
                                )
                            )
                        }

                        EcBtpNetProtocol.STATUS_ALREADY_BUILT -> {
                            log("$dashName says it is already on a network; waiting for it to say where.")
                        }

                        EcBtpNetProtocol.STATUS_AUTH_PENDING -> {
                            if (authRetries++ >= MAX_AUTH_RETRIES) {
                                return Result.failure(
                                    IllegalStateException(
                                        "The dashboard kept saying it was still authorising this " +
                                            "phone and never finished."
                                    )
                                )
                            }
                            log("$dashName is still authorising this phone; asking again (${authRetries}/$MAX_AUTH_RETRIES).")
                            delay(AUTH_RETRY_DELAY_MS)
                            send(EcBtpNetProtocol.requestBuildNet())
                        }

                        EcBtpNetProtocol.STATUS_AUTH_FAILED -> return Result.failure(
                            IllegalStateException(
                                "The dashboard refused this phone over Bluetooth. It may only " +
                                    "accept the app it was paired with; unpair the phone on the " +
                                    "dash and try again."
                            )
                        )

                        else -> log("$dashName reported an unknown build-network status ${status.status}; waiting.")
                    }
                }

                EcBtpNetProtocol.CMD_NOTIFY_CAR_NET_INFO -> {
                    val interfaces = EcBtpNetProtocol.parseCarNetInterfaces(frame.payload)
                    if (interfaces.isEmpty()) {
                        log("$dashName announced its network without an address in it.")
                        continue
                    }
                    log(
                        "$dashName reports " + interfaces.joinToString {
                            "${it.name ?: "?"}=${it.ip}"
                        } + "."
                    )
                    val reported = interfaces.mapNotNull { entry -> parseIpv4(entry.ip) }
                    // The dash's own address is the best evidence available about which of this
                    // phone's interfaces it actually joined - better than the one picked before
                    // the hotspot had finished coming up. So the address chooses the interface,
                    // not the other way round.
                    val landed = hostedSubnets().firstNotNullOfOrNull { candidate ->
                        reported.firstOrNull { address -> sharesSubnet(address, candidate) }
                            ?.let { address -> candidate to address }
                    }
                    val currentSubnet = landed?.first ?: subnet ?: awaitHostedSubnet()
                    if (currentSubnet == null) {
                        log("$dashName announced an address but this phone is hosting no network; ignoring it.")
                        continue
                    }
                    if (landed == null) {
                        // Not fatal - the sweep still runs - but it means the dash is somewhere
                        // this phone cannot see, and a log that says so is the difference between
                        // diagnosing that and re-reading the Bluetooth exchange for a fault that
                        // is not there.
                        log(
                            "$dashName reported an address on none of this phone's hosted networks " +
                                "(${hostedSubnets().joinToString { it.interfaceName }.ifEmpty { "none" }}); " +
                                "discovery will have to sweep ${currentSubnet.interfaceName}."
                        )
                    }
                    send(EcBtpNetProtocol.buildNetFinished())
                    return Result.success(
                        Provisioned(
                            subnet = currentSubnet,
                            dashIp = landed?.second,
                            dashModelId = modelId,
                            dashName = dashName
                        )
                    )
                }

                else -> log(
                    "$dashName -> command 0x${(frame.command.toInt() and 0xFF).toString(16)}, " +
                        "${frame.payload.size} payload byte(s)."
                )
            }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        scanStop?.cancel(false)
        synchronized(queueLock) {
            writeQueue.clear()
            inFlight = null
        }
        val active = gatt
        gatt = null
        writeCharacteristic = null
        runCatching {
            active?.disconnect()
            active?.close()
        }
        inbound.close()
        scheduler.shutdownNow()
        if (!ready.isCompleted) ready.complete(Result.failure(IllegalStateException("Bluetooth link closed.")))
    }

    // ---- Connection ----------------------------------------------------------------------------

    private suspend fun connect(timeoutMillis: Long): Result<String> {
        val adapter = bluetoothManager?.adapter
            ?: return Result.failure(IllegalStateException("This phone has no Bluetooth adapter."))
        if (!runCatching { adapter.isEnabled }.getOrDefault(false)) {
            return Result.failure(
                IllegalStateException(
                    "Bluetooth is off. This dashboard is set up over Bluetooth; turn it on and connect again."
                )
            )
        }
        if (!ThinkerRideGate.hasBlePermissions(appContext)) {
            return Result.failure(IllegalStateException(missingPermissionMessage()))
        }
        val scanner = adapter.bluetoothLeScanner
            ?: return Result.failure(IllegalStateException("Bluetooth LE scanning is unavailable on this phone."))

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (closed.get() || gatt != null) return
                val name = runCatching { result.device.name }.getOrNull()
                    ?: "the dashboard (${result.device.address})"
                log("Found a dashboard advertising the EasyConn setup service: $name.")
                stopScan(scanner, this)
                openGatt(result.device, name)
            }

            override fun onScanFailed(errorCode: Int) {
                completeReady(Result.failure(IllegalStateException("Bluetooth scan failed (code $errorCode).")))
            }
        }

        val filters = ADVERTISED_UUIDS.map { uuid ->
            ScanFilter.Builder().setServiceUuid(ParcelUuid(uuid)).build()
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val started = runCatching { scanner.startScan(filters, settings, scanCallback) }
        started.exceptionOrNull()?.let { failure ->
            return Result.failure(
                if (failure is SecurityException) IllegalStateException(missingPermissionMessage()) else failure
            )
        }
        log("Scanning for a dashboard that sets its network up over Bluetooth.")

        scanStop = schedule(timeoutMillis) {
            if (!ready.isCompleted && gatt == null) {
                stopScan(scanner, scanCallback)
                completeReady(
                    Result.failure(
                        IllegalStateException(
                            "No dashboard answered over Bluetooth. Switch the dash to its " +
                                "phone-pairing screen, keep the phone next to it, and try again."
                        )
                    )
                )
            }
        }

        val outcome = ready.await()
        if (outcome.isFailure) stopScan(scanner, scanCallback)
        return outcome
    }

    private fun openGatt(device: BluetoothDevice, name: String) {
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(connected: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        // At the 23-byte default MTU a JSON payload arrives in 20-byte pieces and
                        // an outgoing one has to be split, which this protocol has no framing for.
                        val requested = runCatching { connected.requestMtu(PREFERRED_MTU) }.getOrDefault(false)
                        if (requested) {
                            schedule(MTU_TIMEOUT_MS) { startDiscoveryOnce(connected) }
                        } else {
                            startDiscoveryOnce(connected)
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (closed.get()) return
                        if (!ready.isCompleted) {
                            completeReady(
                                Result.failure(IllegalStateException("The dashboard closed the Bluetooth link (status $status)."))
                            )
                        } else {
                            log("The dashboard closed the Bluetooth link (status $status).")
                            inbound.close()
                        }
                    }
                }
            }

            override fun onMtuChanged(connected: BluetoothGatt, mtu: Int, status: Int) {
                log("Bluetooth MTU is now $mtu bytes (status $status).")
                startDiscoveryOnce(connected)
            }

            override fun onServicesDiscovered(connected: BluetoothGatt, status: Int) {
                val service = SERVICE_UUIDS.firstNotNullOfOrNull { uuid ->
                    runCatching { connected.getService(uuid) }.getOrNull()
                }
                if (service == null) {
                    val variant = UNSUPPORTED_SERVICE_UUIDS.any { uuid ->
                        runCatching { connected.getService(uuid) }.getOrNull() != null
                    }
                    completeReady(
                        Result.failure(
                            IllegalStateException(
                                if (variant) {
                                    "This dashboard speaks a variant of the EasyConn Bluetooth " +
                                        "setup protocol that MOTO-HUB does not implement yet. " +
                                        "Please send us a log."
                                } else {
                                    "This Bluetooth device is not an EasyConn dashboard."
                                }
                            )
                        )
                    )
                    return
                }
                val write = writableCharacteristic(service)
                val notify = notifiableCharacteristic(service)
                if (write == null) {
                    completeReady(
                        Result.failure(IllegalStateException("The dashboard's Bluetooth service accepts no commands."))
                    )
                    return
                }
                writeCharacteristic = write
                notifyUuid = notify?.uuid
                if (notify != null) {
                    subscribe(connected, notify)
                } else {
                    log("The dashboard exposes no notify channel, so it can only be spoken to.")
                }
                // Subscribing is itself a GATT write and the first command has to wait for it, or
                // the stack answers BUSY and the command is silently never transmitted.
                schedule(if (notify != null) DESCRIPTOR_TIMEOUT_MS else 0) {
                    completeReady(Result.success(name))
                }
            }

            override fun onDescriptorWrite(
                connected: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                if (descriptor.uuid != CCC_UUID) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    log("Subscribing to the dashboard's Bluetooth notifications failed (status $status).")
                }
                completeReady(Result.success(name))
            }

            override fun onCharacteristicWrite(
                connected: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    log("The dashboard rejected a Bluetooth command (status $status).")
                }
                finishInFlight()
            }

            /**
             * The pre-33 notification callback, which is the only one Android 12 has.
             *
             * The value-carrying overload below is API 33. Overriding just that one compiles
             * against compileSdk 36 and then never fires on a minSdk-31 phone, because the
             * framework class there has no such method to dispatch to - the subscription
             * succeeds and not one notification is ever delivered. On 33+ this is dead code:
             * the platform's own value-carrying default is what forwards to this shape, and
             * overriding it replaces that forwarding, so there is no double delivery.
             */
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(
                connected: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                val value = characteristic.value ?: return
                onCharacteristicChanged(connected, characteristic, value)
            }

            override fun onCharacteristicChanged(
                connected: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                if (closed.get()) return
                // Only the channel that was subscribed to carries this protocol; a dash exposing
                // several notify characteristics must not have them interleaved into one stream.
                val expected = notifyUuid
                if (expected != null && characteristic.uuid != expected) return
                val frames = assembler.accept(value)
                if (frames.isEmpty()) return
                for (frame in frames) {
                    if (answerClockRequest(frame)) continue
                    inbound.trySend(frame)
                }
            }
        }
        val opened = runCatching {
            device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        }
        gatt = opened.getOrNull()
        if (gatt == null) {
            completeReady(
                Result.failure(opened.exceptionOrNull() ?: IllegalStateException("Unable to open the Bluetooth link."))
            )
        }
    }

    /** True when this call is the one that sent the request, so only it logs. */
    private fun requestBuildNetOnce(): Boolean {
        if (closed.get() || !buildNetRequested.compareAndSet(false, true)) return false
        send(EcBtpNetProtocol.requestBuildNet())
        return true
    }

    /**
     * The dash asks for the wall clock on this same link, and answering is free: the replies are
     * the ones [EcBtpTimeLink] already sends, byte for byte. A dash that is being set up by this
     * app should not also need the rider to run the official one to get its clock right.
     */
    private fun answerClockRequest(frame: EcBtpProtocol.Frame): Boolean {
        val reply = when (frame.command) {
            EcBtpProtocol.CMD_SYNC_TIME -> EcBtpProtocol.syncTimeReply(now().time, zone().rawOffset)
            EcBtpProtocol.CMD_QUERY_TIME -> EcBtpProtocol.queryTimeReply(now(), zone())
            else -> null
        } ?: return false
        send(reply)
        log("Answered the dashboard's clock request over Bluetooth.")
        return true
    }

    private fun startDiscoveryOnce(connected: BluetoothGatt) {
        if (closed.get() || !discoveryStarted.compareAndSet(false, true)) return
        runCatching { connected.discoverServices() }
    }

    private fun subscribe(connected: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        runCatching {
            connected.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(CCC_UUID) ?: return@runCatching
            BleCompat.writeDescriptor(
                connected,
                descriptor,
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            )
        }
    }

    private fun writableCharacteristic(service: BluetoothGattService): BluetoothGattCharacteristic? =
        service.characteristics.orEmpty().firstOrNull { characteristic ->
            characteristic.properties and (
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                ) != 0
        }

    private fun notifiableCharacteristic(service: BluetoothGattService): BluetoothGattCharacteristic? =
        service.characteristics.orEmpty().firstOrNull { characteristic ->
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        }

    // ---- Hosted network ------------------------------------------------------------------------

    /**
     * Every private subnet this phone holds that is not one of its own uplinks.
     *
     * The exclusion is the whole point, and leaving it out cost the first real session on a
     * Zontes S350 (field log 2026-08-21). The rider's home Wi-Fi was up on `wlan0`; the hotspot
     * had been running for 400ms and its interface had no address yet; so `wlan0` was the only
     * candidate, the dash was told to talk to the phone's address on the home network, and
     * discovery then swept 253 addresses of the rider's living room. The dash had joined the
     * hotspot perfectly and said so.
     */
    private fun hostedSubnets(): List<TBoxHotspotScan.Subnet> =
        TBoxHotspotScan.tetheringSubnets(
            TBoxHotspotScan.snapshotInterfaces(),
            TBoxHotspotScan.addressesInUse(appContext)
        )

    /**
     * A hotspot that has just started does not have an interface yet. Polling beats guessing a
     * fixed delay: the address is what the dash is about to be told to talk to.
     */
    private suspend fun awaitHostedSubnet(): TBoxHotspotScan.Subnet? {
        val deadline = System.currentTimeMillis() + SUBNET_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val subnet = hostedSubnets().firstOrNull()
            if (subnet != null) return subnet
            delay(SUBNET_POLL_MS)
        }
        return null
    }

    private fun parseIpv4(value: String): Inet4Address? =
        runCatching { InetAddress.getByName(value) }.getOrNull() as? Inet4Address

    private fun sharesSubnet(address: Inet4Address, subnet: TBoxHotspotScan.Subnet): Boolean {
        val mask = if (subnet.prefixLength == 0) 0 else -1 shl (32 - subnet.prefixLength)
        return toInt(address.address) and mask == toInt(subnet.localAddress.address) and mask
    }

    private fun toInt(octets: ByteArray): Int =
        octets.fold(0) { accumulated, byte -> (accumulated shl 8) or (byte.toInt() and 0xFF) }

    // ---- Write queue ---------------------------------------------------------------------------

    private fun send(frame: ByteArray) {
        synchronized(queueLock) {
            if (closed.get()) return
            writeQueue.addLast(frame)
        }
        schedule(0) { pump() }
    }

    private fun pump() {
        val next = synchronized(queueLock) {
            if (closed.get() || inFlight != null) return
            val head = writeQueue.removeFirstOrNull() ?: return
            inFlight = head
            inFlightAttempts = 0
            writeGeneration++
            head
        }
        dispatch(next)
    }

    private fun dispatch(frame: ByteArray) {
        val active = gatt
        val characteristic = writeCharacteristic
        if (closed.get() || active == null || characteristic == null) {
            finishInFlight()
            return
        }
        val writeType = if (
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        ) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        val submitted = runCatching {
            BleCompat.writeCharacteristic(active, characteristic, frame, writeType)
        }
        when {
            submitted.exceptionOrNull() != null -> retryOrDrop(frame)
            submitted.getOrNull() == BluetoothStatusCodes.SUCCESS -> armWriteWatchdog()
            else -> retryOrDrop(frame)
        }
    }

    /** A refusal means the radio was busy: the bytes never left, so the only answer is to resend. */
    private fun retryOrDrop(frame: ByteArray) {
        val attempts = synchronized(queueLock) {
            if (inFlight !== frame) return
            ++inFlightAttempts
        }
        if (attempts >= MAX_WRITE_ATTEMPTS) {
            log("A Bluetooth command could not be sent after $attempts attempts; giving up on it.")
            finishInFlight()
            return
        }
        schedule(WRITE_RETRY_DELAY_MS) { dispatch(frame) }
    }

    /** A completion callback that never arrives must not wedge the queue for the whole session. */
    private fun armWriteWatchdog() {
        val generation = synchronized(queueLock) { writeGeneration }
        schedule(WRITE_CALLBACK_TIMEOUT_MS) {
            val stalled = synchronized(queueLock) { writeGeneration == generation && inFlight != null }
            if (stalled) finishInFlight()
        }
    }

    private fun finishInFlight() {
        synchronized(queueLock) {
            if (inFlight == null) return
            inFlight = null
            inFlightAttempts = 0
            writeGeneration++
        }
        schedule(WRITE_SPACING_MS) { pump() }
    }

    private fun stopScan(scanner: BluetoothLeScanner, callback: ScanCallback) {
        runCatching { scanner.stopScan(callback) }
    }

    private fun schedule(delayMillis: Long, action: () -> Unit): ScheduledFuture<*>? =
        runCatching { scheduler.schedule(action, delayMillis, TimeUnit.MILLISECONDS) }.getOrNull()

    private fun completeReady(outcome: Result<String>) {
        if (!ready.isCompleted) ready.complete(outcome)
    }

    private fun missingPermissionMessage(): String =
        ThinkerRideGate.missingPermissionMessage("MOTO-HUB")

    companion object {
        /**
         * What the dash puts in its advertisement.
         *
         * Not the same identifiers as the GATT service below: a Zontes S350 advertises the 16-bit
         * `0x6967` and carries `0xB360` as *service data*, and only exposes the 128-bit service
         * once connected. Carbit's own scan list (`ne/a.java`) contains both shapes for the same
         * reason.
         */
        val ADVERTISED_UUIDS = listOf(
            UUID.fromString("00006967-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("0000b360-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("0000b360-d6d8-c7ec-bdf0-eab1bfc6bcbc")
        )

        /** The GATT services this exchange runs on (Carbit `ne/a.java`). */
        val SERVICE_UUIDS = listOf(
            UUID.fromString("0000b360-d6d8-c7ec-bdf0-eab1bfc6bcbc"),
            UUID.fromString("0000b363-d6d8-c7ec-bdf0-eab1bfc6bcbc")
        )

        /**
         * The `b364` variant scrambles every payload through a substitution table (Carbit
         * `ug/c.java`, switched on by `qg/a.java:f71149c`). Recognised so the rider is told what
         * happened instead of watching a silent dash.
         */
        val UNSUPPORTED_SERVICE_UUIDS = listOf(
            UUID.fromString("0000b364-d6d8-c7ec-bdf0-eab1bfc6bcbc")
        )

        val CCC_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val SCAN_TIMEOUT_MS = 20_000L

        /** The whole conversation, from client info to the dash asking for a network. */
        const val EXCHANGE_TIMEOUT_MS = 45_000L

        /** How long a dash gets to report the address it took, after being told where to go. */
        const val ANNOUNCEMENT_TIMEOUT_MS = 20_000L

        const val HOTSPOT_TIMEOUT_MS = 20_000L
        const val SUBNET_TIMEOUT_MS = 15_000L
        const val SUBNET_POLL_MS = 250L
        const val AUTH_RETRY_DELAY_MS = 1_000L

        /** How long the dash gets to volunteer a handshake before it is asked outright. */
        const val HANDSHAKE_NUDGE_MS = 4_000L
        const val MAX_AUTH_RETRIES = 5
        const val PREFERRED_MTU = 185
        const val MTU_TIMEOUT_MS = 1_500L
        const val DESCRIPTOR_TIMEOUT_MS = 2_000L
        const val MAX_WRITE_ATTEMPTS = 8
        const val WRITE_RETRY_DELAY_MS = 60L
        const val WRITE_CALLBACK_TIMEOUT_MS = 1_500L
        const val WRITE_SPACING_MS = 40L
    }
}
