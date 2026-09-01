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
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import io.motohub.android.data.MotorcycleProfileStore
import io.motohub.android.session.MotorcycleProfile
import java.util.Date
import java.util.UUID
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The clock EXPERIMENT bench behind Settings > Diagnostics > Dash clock lab.
 *
 * [EcBtpTimeLink] is deliberately reactive: it answers a dash that asks for the time and says
 * nothing to one that stays silent. Zontes riders keep reporting the dash clock resetting to
 * 00:00 after an ignition cycle - and their logs show exactly that silence: the link opens,
 * nothing ever speaks, nothing is ever written. Carbit's own `sendSyncTime()` is not an answer,
 * it is an unsolicited BLE push. So the open question is which unsolicited shape this dash
 * actually accepts, and this lab exists to ask it on a real bike.
 *
 * It connects to everything that plausibly is the dash, dumps the full GATT table, listens for
 * a window, and then pushes the time in every shape worth ruling in or out - epoch with raw
 * offset (the exact Carbit shape), the same frame acked, pure UTC, DST-aware offset, and the
 * formatted-string form - a few seconds apart, logging every byte in both directions. The rider
 * then checks the dash after each ignition cycle and the log says which attempt was on the wire.
 *
 * Unlike [EcBtpTimeLink], the scan here runs UNFILTERED and the bonded pass opens everything.
 * The first field run (Voge Valico 900, report 402D-D3F2, 2026-08-28) proved the production
 * filters blind for a lab: the dash was on and in range - the rider's handlebar was delivering
 * presses minutes later - yet a scan filtered on the seven known serial-service UUIDs saw
 * *nothing*, and all six bonded devices were skipped on their cached SDP UUIDs without so much
 * as their names in the log. So the lab now logs every advertiser it hears (name, address,
 * RSSI, advertised services) and every bonded device it opens; what the dash actually
 * advertises is precisely the answer the lab is out to collect.
 *
 * The listen-before-write rule that protects [EcBtpTimeLink] is deliberately relaxed here:
 * a dash that never speaks is the very case under investigation. The blast radius is still
 * bounded - only peripherals exposing one of Carbit's serial service+characteristic pairs are
 * written to, the frames are valid EC-BTP that generic serial devices ignore, and the whole lab
 * is a button a rider pressed on a diagnostics screen, not something that runs by itself.
 */
@SuppressLint("MissingPermission")
internal class EcBtpClockLab(
    context: Context,
    private val log: (String) -> Unit,
    private val onFinished: () -> Unit
) {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)

    private val closed = AtomicBoolean(false)
    private val lock = Any()
    private val connections = mutableListOf<BluetoothGatt>()
    private val watched = mutableSetOf<String>()
    private val advertisersSeen = mutableSetOf<String>()
    private val scanConnects = AtomicInteger(0)
    private val framesReceived = AtomicInteger(0)
    private val devicesWritten = AtomicInteger(0)

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ec-btp-clock-lab").apply { isDaemon = true }
    }

    @Volatile
    private var scanner: BluetoothLeScanner? = null

    @Volatile
    private var scanCallback: ScanCallback? = null

    /** Starts the bench; it closes itself after [LAB_DURATION_MILLIS] and then calls [onFinished]. */
    fun start() {
        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            log("This phone has no Bluetooth adapter; the lab cannot run.")
            finish()
            return
        }
        if (!adapter.isEnabled) {
            log("Bluetooth is off. Turn it on and run the lab again.")
            finish()
            return
        }
        if (!ThinkerRideGate.hasBlePermissions(appContext)) {
            log(ThinkerRideGate.missingPermissionMessage("MOTO-HUB") + " The lab cannot run until then.")
            finish()
            return
        }

        val live = TBoxSessionRegistry.current()
        if (live != null) {
            // Support case f014ce61 (VOGE 800 Rally, 2026-08-31) is what this refusal is made of:
            // the lab was run twice during a live projection, and 35s into the first run the
            // dash stopped answering on its PXC control channel - 28 keepalives unanswered, the
            // video still landing - and Android Auto was torn down. Ten RFCOMM sockets and a 20s
            // unfiltered LE scan are exactly the kind of neighbour a dash streaming over 2.4GHz
            // Wi-Fi Direct cannot absorb. The experiment can wait; the rider's ride cannot.
            log(
                "The lab will not run while MOTO-HUB is connected to " +
                    "${live.motorcycle.displayName ?: live.motorcycle.ssid}: opening every " +
                    "Bluetooth device and scanning for 20s has ended a live session before. " +
                    "Disconnect the motorcycle first, leave the dash powered on, and run the " +
                    "lab again."
            )
            finish()
            return
        }

        log(
            "Clock lab started. Keep the dash powered on: the lab runs for " +
                "${LAB_DURATION_MILLIS / 1000}s, pushes the time in ${ATTEMPT_COUNT} different " +
                "shapes, and logs everything. Check the dash clock after the next ignition cycle."
        )

        val bonded = runCatching { adapter.bondedDevices }.getOrNull().orEmpty()
        log("${bonded.size} bonded Bluetooth device(s); opening every one of them and scanning for unbonded dashes.")
        // No pre-filter: the cached UUIDs are classic SDP records and say nothing reliable about
        // an LE dash. A headset's GATT dump is a few log lines; a silently skipped dash is a
        // wasted field run.
        //
        // Ordered, though, because MAX_BONDED_CONNECTS is a cap on an unordered Set:
        // getBondedDevices() promises no order at all. In support case f014ce61 the rider had 23
        // bonded devices and the dash came up sixth by luck; with two more headsets paired the
        // lab would have written "13 skipped" and looked like a complete run without ever having
        // opened the motorcycle. Filtering on the SDP records would not save it either - that
        // dash advertises A2DP, AVRCP and HFP and nothing else, exactly like the headsets.
        val hints = dashNameHints(savedMotorcycles())
        val ordered = bonded.sortedByDescending { device ->
            looksLikeDash(runCatching { device.name }.getOrNull(), hints)
        }
        ordered.take(MAX_BONDED_CONNECTS).forEach { device ->
            val address = runCatching { device.address }.getOrNull() ?: return@forEach
            val name = runCatching { device.name }.getOrNull() ?: "?"
            val cached = runCatching { device.uuids }.getOrNull()
                ?.joinToString { it.uuid.toString() } ?: "none cached"
            log("Bonded: $name ($address), SDP UUIDs: $cached.")
            openGatt(device, "bonded")
        }
        if (bonded.size > MAX_BONDED_CONNECTS) {
            log("Only the first $MAX_BONDED_CONNECTS bonded devices are opened; ${bonded.size - MAX_BONDED_CONNECTS} skipped.")
        }
        beginScan(adapter.bluetoothLeScanner)

        runCatching {
            scheduler.schedule({ finish() }, LAB_DURATION_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        endScan()
        scheduler.shutdownNow()
        synchronized(lock) {
            connections.forEach { gatt ->
                runCatching { gatt.disconnect() }
                runCatching { gatt.close() }
            }
            connections.clear()
            watched.clear()
        }
    }

    private fun finish() {
        if (closed.get()) return
        log(
            "Clock lab finished: wrote to ${devicesWritten.get()} device(s), received " +
                "${framesReceived.get()} notification(s). Share the application log if the dash " +
                "clock is still wrong after the next ignition cycle."
        )
        close()
        onFinished()
    }

    private fun beginScan(leScanner: BluetoothLeScanner?) {
        if (leScanner == null) {
            log("Bluetooth LE scanning is unavailable on this phone; only bonded devices are tried.")
            return
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (closed.get()) return
                val device = runCatching { result.device }.getOrNull() ?: return
                val address = runCatching { device.address }.getOrNull() ?: return
                val record = result.scanRecord
                val advertised = record?.serviceUuids?.map { it.uuid }.orEmpty()
                val name = runCatching { device.name }.getOrNull()
                    ?: record?.deviceName
                val firstSighting = synchronized(lock) { advertisersSeen.add(address) }
                val knownService = advertised.any { EcBtpTimeLink.SERVICE_UUIDS.contains(it) }
                if (firstSighting) {
                    log(
                        "Advertiser: ${name ?: "(no name)"} ($address), rssi ${result.rssi}, " +
                            "services: ${if (advertised.isEmpty()) "none advertised" else advertised.joinToString()}" +
                            if (knownService) " - KNOWN serial service." else "."
                    )
                }
                when {
                    // watched-set in openGatt dedupes, so a repeat sighting is harmless.
                    knownService -> openGatt(device, "scan, advertises a known serial service")
                    // A nameless advertiser is overwhelmingly a phone/beacon; a named one in
                    // Bluetooth range of a motorcycle is worth one GATT look, capped so a busy
                    // car park cannot eat the lab.
                    firstSighting && !name.isNullOrBlank() -> {
                        val slot = scanConnects.incrementAndGet()
                        if (slot <= MAX_SCAN_CONNECTS) {
                            openGatt(device, "scan, named advertiser")
                        } else if (slot == MAX_SCAN_CONNECTS + 1) {
                            log("Named-advertiser cap ($MAX_SCAN_CONNECTS) reached; further ones are logged only.")
                        }
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                log("The Bluetooth scan could not start (code $errorCode); only bonded devices are tried.")
                endScan()
            }
        }
        // Unfiltered on purpose: the first Voge field run proved a scan filtered on the known
        // service UUIDs sees nothing, so what the dash advertises instead IS the experiment.
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val started = runCatching { leScanner.startScan(null, settings, callback) }
        if (started.isFailure) {
            log("Could not scan for the dash (${started.exceptionOrNull()?.message ?: "unknown error"}); only bonded devices are tried.")
            return
        }
        scanner = leScanner
        scanCallback = callback
        log("Scanning ${SCAN_WINDOW_MILLIS / 1000}s, unfiltered - every advertiser in range is logged once.")
        runCatching {
            scheduler.schedule({
                endScan()
                if (closed.get()) return@schedule
                val seen = synchronized(lock) { advertisersSeen.size }
                log(
                    "Scan over: $seen advertiser(s) heard, " +
                        "${scanConnects.get().coerceAtMost(MAX_SCAN_CONNECTS)} opened from the scan." +
                        if (seen == 0) " Nothing at all was advertising - is the dash powered on and its Bluetooth awake?" else ""
                )
            }, SCAN_WINDOW_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    private fun endScan() {
        val active = scanner ?: return
        val callback = scanCallback ?: return
        scanner = null
        scanCallback = null
        runCatching { active.stopScan(callback) }
    }

    private fun openGatt(device: BluetoothDevice, origin: String) {
        val address = runCatching { device.address }.getOrNull() ?: return
        synchronized(lock) {
            if (closed.get()) return
            if (!watched.add(address)) return
        }
        val label = runCatching { device.name }.getOrNull() ?: address
        log("Opening $label ($address, $origin).")

        val callback = object : BluetoothGattCallback() {
            @Volatile
            private var dataCharacteristic: BluetoothGattCharacteristic? = null

            /**
             * Where the replies arrive, which on a Carbit serial pair is the same characteristic
             * that is written and on a dash matched by shape is a different one.
             */
            @Volatile
            private var notifyCharacteristic: BluetoothGattCharacteristic? = null

            /** The attempt whose reply a following notification most plausibly is. */
            @Volatile
            private var lastAttempt: String = "before any write"

            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (closed.get()) {
                        runCatching { gatt.disconnect() }
                        return
                    }
                    log("$label: connected, discovering services.")
                    runCatching { gatt.discoverServices() }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    log("$label: link closed (status $status).")
                    forget(gatt)
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                dumpGattTable(label, gatt)
                val service = EcBtpTimeLink.SERVICE_UUIDS.firstNotNullOfOrNull { uuid ->
                    runCatching { gatt.getService(uuid) }.getOrNull()
                }
                val known = service?.let { dataCharacteristicOf(it) }?.let { it to it }
                // The seven known UUIDs come from Carbit's own app, so they name the dashes we
                // have already met and nothing else. Support case f014ce61: the VOGE's BLE side
                // carries 5fe695f1-fd7b-4f9b-98cc-ee6cf57a776e with one write and one notify
                // characteristic - the shape of a serial channel, and the only writable thing on
                // the whole motorcycle - and the lab dumped it into the log and walked away. The
                // comment on dumpGattTable already said the fix may hide in a UUID we have never
                // met; this is the lab acting on it.
                val pair = known ?: shapedPair(gatt, label)
                if (pair == null) {
                    log("$label: no serial-shaped service+characteristic pair; leaving it alone.")
                    runCatching { gatt.disconnect() }
                    return
                }
                val (writeTarget, notifyTarget) = pair
                dataCharacteristic = writeTarget
                notifyCharacteristic = notifyTarget
                subscribe(gatt, notifyTarget)
                log(
                    "$label: experimenting on ${writeTarget.uuid} " +
                        "(${describeProperties(writeTarget.properties)})" +
                        if (notifyTarget.uuid == writeTarget.uuid) {
                            ""
                        } else {
                            ", listening on ${notifyTarget.uuid}"
                        } +
                        ". Listening ${LISTEN_WINDOW_MILLIS / 1000}s first - a dash that asks by " +
                        "itself is the answer EcBtpTimeLink already handles."
                )
                scheduleAttempts(gatt, label)
            }

            /**
             * The first service on this peripheral shaped like a serial channel, when none of the
             * known ones is there. Reported as the guess it is, because it is one.
             */
            private fun shapedPair(
                gatt: BluetoothGatt,
                label: String
            ): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
                val services = runCatching { gatt.services }.getOrNull().orEmpty()
                services.forEach { candidate ->
                    val characteristics = candidate.characteristics.orEmpty()
                    val shaped = serialShapedPair(
                        candidate.uuid,
                        characteristics.map { it.uuid to it.properties }
                    ) ?: return@forEach
                    val write = characteristics.firstOrNull { it.uuid == shaped.first }
                    val notify = characteristics.firstOrNull { it.uuid == shaped.second }
                    if (write == null || notify == null) return@forEach
                    log(
                        "$label: none of the ${EcBtpTimeLink.SERVICE_UUIDS.size} known serial " +
                            "services is here, but ${candidate.uuid} has the shape of one - " +
                            "${shaped.first} to write, ${shaped.second} to listen on. Trying it; " +
                            "this is a guess, and the log below says what it answered."
                    )
                    return write to notify
                }
                return null
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                log(
                    "$label: acked write completed with GATT status $status " +
                        if (status == BluetoothGatt.GATT_SUCCESS) "(accepted)." else "(REFUSED)."
                )
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                val value = characteristic.value ?: return
                onCharacteristicChanged(gatt, characteristic, value)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                if (closed.get()) return
                framesReceived.incrementAndGet()
                val parsed = EcBtpProtocol.parse(value)
                val kind = when {
                    parsed == null -> "not an EC-BTP frame"
                    parsed.command == EcBtpProtocol.CMD_SYNC_TIME -> "EC-BTP SYNC_TIME request"
                    parsed.command == EcBtpProtocol.CMD_QUERY_TIME -> "EC-BTP QUERY_TIME request"
                    else -> "EC-BTP command 0x${(parsed.command.toInt() and 0xFF).toString(16)}"
                }
                log("$label -> ${value.size} byte(s) ($kind, $lastAttempt): ${hex(value)}")
                // A dash that asks gets the stock answer too, so the lab never leaves it worse
                // than EcBtpTimeLink would.
                val reply = when (parsed?.command) {
                    EcBtpProtocol.CMD_SYNC_TIME ->
                        EcBtpProtocol.syncTimeReply(System.currentTimeMillis(), TimeZone.getDefault().rawOffset)
                    EcBtpProtocol.CMD_QUERY_TIME ->
                        EcBtpProtocol.queryTimeReply(Date(), TimeZone.getDefault())
                    else -> null
                } ?: return
                val target = dataCharacteristic ?: return
                val written = runCatching {
                    BleCompat.writeCharacteristic(
                        gatt, target, reply, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    )
                }.getOrNull()
                log("$label: answered its request with ${reply.size} byte(s) (result $written).")
            }

            private fun scheduleAttempts(gatt: BluetoothGatt, label: String) {
                var delay = LISTEN_WINDOW_MILLIS
                attempts().forEachIndexed { index, attempt ->
                    runCatching {
                        scheduler.schedule({
                            if (closed.get()) return@schedule
                            val target = dataCharacteristic ?: return@schedule
                            // Built at fire time, not schedule time: a frame carrying the
                            // timestamp of when the button was pressed would set a clock that is
                            // already tens of seconds behind.
                            val frame = attempt.build()
                            lastAttempt = "after attempt ${index + 1}"
                            val writeType = if (attempt.acked) {
                                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            } else {
                                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                            }
                            val result = runCatching {
                                BleCompat.writeCharacteristic(gatt, target, frame, writeType)
                            }.getOrNull()
                            if (index == 0) devicesWritten.incrementAndGet()
                            log(
                                "$label: attempt ${index + 1}/${ATTEMPT_COUNT} - ${attempt.title}: " +
                                    "${frame.size} byte(s), submit result $result. ${hex(frame)}"
                            )
                        }, delay, TimeUnit.MILLISECONDS)
                    }
                    delay += ATTEMPT_GAP_MILLIS
                }
            }
        }

        val opened = runCatching {
            device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        }.getOrNull()
        if (opened == null) {
            log("$label: could not open a Bluetooth link.")
            return
        }
        synchronized(lock) {
            if (closed.get()) {
                runCatching { opened.disconnect() }
                runCatching { opened.close() }
            } else {
                connections += opened
            }
        }
    }

    /** One unsolicited shape worth ruling in or out, in the order they go on the wire. */
    private class Attempt(val title: String, val acked: Boolean, val build: () -> ByteArray)

    private fun attempts(): List<Attempt> = listOf(
        Attempt("SYNC_TIME epoch+rawOffset, unacked (the exact Carbit sendSyncTime shape)", acked = false) {
            EcBtpProtocol.syncTimeReply(System.currentTimeMillis(), TimeZone.getDefault().rawOffset)
        },
        Attempt("SYNC_TIME epoch+rawOffset, acked write (same frame, dash must answer the write)", acked = true) {
            EcBtpProtocol.syncTimeReply(System.currentTimeMillis(), TimeZone.getDefault().rawOffset)
        },
        Attempt("SYNC_TIME pure UTC epoch, no zone offset", acked = false) {
            EcBtpProtocol.syncTimeReply(System.currentTimeMillis(), 0)
        },
        Attempt("SYNC_TIME epoch+DST-aware offset", acked = false) {
            EcBtpProtocol.syncTimeReply(
                System.currentTimeMillis(),
                TimeZone.getDefault().getOffset(System.currentTimeMillis())
            )
        },
        Attempt("QUERY_TIME formatted local timestamp, pushed unasked", acked = false) {
            EcBtpProtocol.queryTimeReply(Date(), TimeZone.getDefault())
        }
    )

    private fun dataCharacteristicOf(service: BluetoothGattService): BluetoothGattCharacteristic? =
        EcBtpTimeLink.CHARACTERISTIC_UUIDS.firstNotNullOfOrNull { uuid ->
            runCatching { service.getCharacteristic(uuid) }.getOrNull()
        }

    private fun savedMotorcycles(): List<MotorcycleProfile> =
        runCatching { MotorcycleProfileStore(appContext).loadAll() }.getOrElse { emptyList() }

    /** The whole table, because the fix for a dash we have never met may hide in a UUID we have never met. */
    private fun dumpGattTable(label: String, gatt: BluetoothGatt) {
        val services = runCatching { gatt.services }.getOrNull().orEmpty()
        log("$label: GATT table, ${services.size} service(s):")
        services.forEach { service ->
            log("$label:   service ${service.uuid}")
            service.characteristics.forEach { characteristic ->
                log(
                    "$label:     char ${characteristic.uuid} " +
                        "[${describeProperties(characteristic.properties)}]"
                )
            }
        }
    }

    private fun describeProperties(properties: Int): String = buildList {
        if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("read")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("write")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("write-nr")
        if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("notify")
        if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("indicate")
    }.ifEmpty { listOf("none") }.joinToString("+")

    private fun subscribe(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        runCatching { gatt.setCharacteristicNotification(characteristic, true) }
        val descriptor = runCatching { characteristic.getDescriptor(EcBtpTimeLink.CCC_UUID) }.getOrNull() ?: return
        runCatching {
            BleCompat.writeDescriptor(gatt, descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }
    }

    private fun forget(gatt: BluetoothGatt) {
        synchronized(lock) { connections.remove(gatt) }
        runCatching { gatt.close() }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02X".format(it) }

    internal companion object {

        /**
         * The names a bonded device could carry if it were this rider's dash.
         *
         * A Wi-Fi Direct group is named `DIRECT-<two chars>-<device name>` by Android's own
         * convention, so `DIRECT-VOGE-057543` names a dash that calls itself `VOGE-057543` on
         * Bluetooth and advertises as `BLE-VOGE-057543`. Both the group name and the stripped
         * form go in, along with whatever the rider typed as the display name.
         */
        internal fun dashNameHints(profiles: List<MotorcycleProfile>): List<String> =
            profiles.flatMap { profile ->
                val ssid = profile.ssid.trim().removeSurrounding("\"")
                listOfNotNull(
                    ssid.takeIf { it.isNotBlank() },
                    TBoxWifiDirectConnector.peerNameFromGroupSsid(ssid),
                    profile.displayName?.trim()?.takeIf { it.isNotBlank() }
                )
            }.map { it.uppercase() }.distinct()

        /**
         * Whether a bonded device's name looks like one of [hints], in either direction: the
         * dash's Bluetooth name is often a prefix or a suffix of the network it hosts.
         */
        internal fun looksLikeDash(deviceName: String?, hints: List<String>): Boolean {
            val name = deviceName?.trim()?.uppercase()?.takeIf { it.isNotBlank() } ?: return false
            return hints.any { hint -> name.contains(hint) || hint.contains(name) }
        }

        /**
         * The write/notify characteristics of [serviceUuid] when it is shaped like a serial
         * channel, or null.
         *
         * Exactly one of each, deliberately. A vendor serial channel is a pipe: one way in, one
         * way out. A service offering three writable characteristics is something else, and
         * writing EC-BTP frames into a guess that rich is how a lab stops being safe.
         *
         * Services in the Bluetooth SIG base range are skipped whatever their shape: every
         * peripheral carries GAP, GATT and Device Information, and none of them is a dash's
         * private channel. The two known serial services that DO live in that range
         * (`0000ffe0-` and `0000fff0-`) never reach here - they are matched by UUID first.
         */
        internal fun serialShapedPair(
            serviceUuid: UUID,
            characteristics: List<Pair<UUID, Int>>
        ): Pair<UUID, UUID>? {
            if (isSigAssigned(serviceUuid)) return null
            val writes = characteristics.filter { (_, properties) ->
                properties and WRITE_PROPERTIES != 0
            }
            val notifies = characteristics.filter { (_, properties) ->
                properties and NOTIFY_PROPERTIES != 0
            }
            if (writes.size != 1 || notifies.size != 1) return null
            return writes.first().first to notifies.first().first
        }

        /** A 16-bit UUID adopted by the Bluetooth SIG, expanded onto the standard base. */
        private fun isSigAssigned(uuid: UUID): Boolean =
            uuid.leastSignificantBits == SIG_BASE_LEAST_SIGNIFICANT_BITS &&
                (uuid.mostSignificantBits and SIG_BASE_HIGH_MASK) == SIG_BASE_HIGH_BITS

        private val SIG_BASE_LEAST_SIGNIFICANT_BITS =
            UUID.fromString("00000000-0000-1000-8000-00805f9b34fb").leastSignificantBits
        private const val SIG_BASE_HIGH_MASK = 0x0000_0000_FFFF_FFFFL
        private const val SIG_BASE_HIGH_BITS = 0x0000_1000L
        private const val WRITE_PROPERTIES =
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        private const val NOTIFY_PROPERTIES =
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                BluetoothGattCharacteristic.PROPERTY_INDICATE

        /** Long enough for a slow dash to boot its Bluetooth after ignition; the rider is watching. */
        const val LAB_DURATION_MILLIS = 60_000L
        const val SCAN_WINDOW_MILLIS = 20_000L
        const val LISTEN_WINDOW_MILLIS = 5_000L
        const val ATTEMPT_GAP_MILLIS = 4_000L
        const val ATTEMPT_COUNT = 5
        const val MAX_BONDED_CONNECTS = 10
        const val MAX_SCAN_CONNECTS = 8
    }
}
