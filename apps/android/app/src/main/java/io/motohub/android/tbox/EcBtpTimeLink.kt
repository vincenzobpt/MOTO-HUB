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
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import java.util.Date
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Answers the dashboard's Bluetooth clock questions, and says nothing to anything else.
 *
 * MOTO-HUB answers `ECP_C2P_QUERY_TIME` over Wi-Fi byte-for-byte as the official app does - the
 * two handlers were compared field by field against Carbit's own `ih/n0.java` - and a rider's Voge
 * still sits at 00:00 while Carbit Ride keeps it right on the same bike. The reason is that PXC is
 * not where that clock is written at all: Carbit's `sendSyncTime()` builds
 * [EcBtpProtocol.CMD_SYNC_TIME] with `System.currentTimeMillis() + rawOffset` and pushes it over
 * **BLE**, and that write is what survives an ignition cycle. This is that second channel.
 *
 * **This is a diagnostic first and a fix second.** Both requests are reactive: if this dash never
 * asks, nothing here will ever fire, and the log saying so is the answer that closes the question.
 * Every frame that arrives is logged whether or not it is a clock request.
 *
 * Four rules keep it off everyone else's hardware, because the service UUIDs this protocol rides on
 * (`ffe0/ffe1`, `fff0/fff1`, …) are generic serial-over-BLE identifiers shared with intercoms, OBD
 * dongles, TPMS sensors and countless toys:
 *
 *  1. **Opt-in.** The caller only builds this when the rider turned the setting on; off by default.
 *  2. **Only devices advertising one of these services.** The bonded list alone was the original
 *     rule and it was wrong: Carbit reaches the dash as an *unbonded* BLE peripheral - its own
 *     stack drives the Nordic scanner compat library and writes through a GATT service in the
 *     list below - and an unbonded peripheral is in nobody's `bondedDevices` and publishes no
 *     cached UUIDs. Every Voge log therefore said "no bonded Bluetooth devices" while Carbit was
 *     setting that very dash's clock. So both sources are used: bonded devices whose cached UUID
 *     list does not rule them out, plus a [SCAN_WINDOW_MILLIS] scan whose [ScanFilter]s name
 *     exactly these service UUIDs, so the Bluetooth controller drops every other advertiser
 *     before it reaches this process.
 *  3. **Listen before writing.** Not one byte is transmitted until that device has sent a
 *     *syntactically valid* EC-BTP frame - right start byte, self-consistent length, correct XOR,
 *     right terminator. An intercom or an OBD dongle cannot produce one by accident, so it never
 *     hears from us. This is the safety property that matters; [EcBtpProtocol.parse] is its gate.
 *  4. **Never alongside ThinkerRide.** KOVE dashes hold their own GATT link and two concurrent
 *     connections are a known way to destabilise the Android stack, so the caller must not start
 *     both.
 *
 * All Bluetooth calls are wrapped against [SecurityException]: the runtime grant can be revoked
 * mid-session, and that must degrade to a log line rather than a crash.
 */
@SuppressLint("MissingPermission")
internal class EcBtpTimeLink(
    context: Context,
    private val log: (String) -> Unit,
    private val now: () -> Date = { Date() },
    private val zone: () -> TimeZone = { TimeZone.getDefault() }
) {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)

    private val closed = AtomicBoolean(false)
    private val connections = mutableListOf<BluetoothGatt>()
    private val lock = Any()

    /** Addresses already being watched, so the bonded pass and the scan cannot both open one. */
    private val watched = mutableSetOf<String>()

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ec-btp-scan").apply { isDaemon = true }
    }

    @Volatile
    private var scanner: BluetoothLeScanner? = null

    @Volatile
    private var scanCallback: ScanCallback? = null

    /**
     * Watches every device that could plausibly be this dashboard, from both sources, and listens.
     *
     * Returns how many bonded devices were opened immediately. Scan results arrive later and are
     * logged as they come, so a field log distinguishes the three answers that matter: nothing was
     * ever found, something was found and never spoke, or something spoke and was answered.
     */
    fun start(): Int {
        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            log("EC-BTP: this phone has no Bluetooth adapter; the dash clock cannot be set over Bluetooth.")
            return 0
        }
        if (!adapter.isEnabled) {
            log("EC-BTP: Bluetooth is off, so the dashboard cannot be asked for its clock over it.")
            return 0
        }

        if (!ThinkerRideGate.hasBlePermissions(appContext)) {
            // Worth its own line: without this grant the scan below throws and the rider sees a
            // setting that is on and does nothing at all.
            log(
                "EC-BTP: " + ThinkerRideGate.missingPermissionMessage("MOTO-HUB") +
                    " Until then the dash clock cannot be set over Bluetooth."
            )
            return 0
        }

        val bonded = runCatching { adapter.bondedDevices }.getOrNull().orEmpty()
        val candidates = bonded.filter { candidateWorthOpening(it) }
        log(
            "EC-BTP: ${bonded.size} bonded device(s), ${candidates.size} of them could carry this " +
                "protocol. Scanning as well, because the dash need not be bonded at all. " +
                "Listening only; nothing is sent until something speaks EC-BTP."
        )
        candidates.forEach { device -> openGatt(device) }
        beginScan(adapter.bluetoothLeScanner)
        return candidates.size
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

    /**
     * Starts a bounded scan for peripherals advertising one of [SERVICE_UUIDS].
     *
     * Bounded because a dash that is going to ask for the time asks within seconds of the session
     * starting, and an unbounded LE scan is a battery cost the rider did not ask for. The filters
     * are handed to the Bluetooth controller, so an intercom or a tyre sensor is dropped below this
     * process rather than being connected to and then let go.
     */
    private fun beginScan(leScanner: BluetoothLeScanner?) {
        if (leScanner == null) {
            log("EC-BTP: Bluetooth LE scanning is unavailable on this phone; only bonded devices can be watched.")
            return
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (closed.get()) return
                val device = runCatching { result.device }.getOrNull() ?: return
                openGatt(device)
            }

            override fun onScanFailed(errorCode: Int) {
                log("EC-BTP: the Bluetooth scan could not start (code $errorCode); only bonded devices are watched.")
                endScan()
            }
        }
        val filters = SERVICE_UUIDS.map { uuid ->
            ScanFilter.Builder().setServiceUuid(ParcelUuid(uuid)).build()
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val started = runCatching { leScanner.startScan(filters, settings, callback) }
        if (started.isFailure) {
            val failure = started.exceptionOrNull()
            val reason = if (failure is SecurityException) {
                "the Bluetooth scan permission is not granted"
            } else {
                failure?.message ?: "an unknown error"
            }
            log("EC-BTP: could not scan for the dashboard ($reason); only bonded devices are watched.")
            return
        }
        scanner = leScanner
        scanCallback = callback
        log("EC-BTP: scanning ${SCAN_WINDOW_MILLIS / 1000}s for a dashboard advertising one of ${SERVICE_UUIDS.size} serial services.")
        runCatching {
            scheduler.schedule({ endScan() }, SCAN_WINDOW_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    private fun endScan() {
        val active = scanner ?: return
        val callback = scanCallback ?: return
        scanner = null
        scanCallback = null
        runCatching { active.stopScan(callback) }
    }

    /**
     * Whether a bonded device is worth opening a GATT connection to at all.
     *
     * `getUuids()` is the cached service list from bonding, so when it is populated this filter is
     * free: a headset or a tyre sensor is ruled out without ever being touched. When it is null the
     * device is allowed through, because a dash that has not been service-discovered yet would
     * otherwise be skipped forever - and [onServicesDiscovered] drops it immediately anyway if the
     * service is not really there.
     */
    private fun candidateWorthOpening(device: BluetoothDevice): Boolean {
        val cached: Array<ParcelUuid>? = runCatching { device.uuids }.getOrNull()
        if (cached.isNullOrEmpty()) return true
        return cached.any { SERVICE_UUIDS.contains(it.uuid) }
    }

    private fun openGatt(device: BluetoothDevice) {
        val address = runCatching { device.address }.getOrNull() ?: return
        synchronized(lock) {
            if (closed.get()) return
            // The bonded pass and the scan can both surface the same dash, and a second GATT
            // connection to one peripheral is exactly the thing that destabilises the stack.
            if (!watched.add(address)) return
        }
        val label = runCatching { device.name }.getOrNull() ?: address
        log("EC-BTP: watching $label ($address).")
        val callback = object : BluetoothGattCallback() {
            /** Set once this peer has proven it speaks EC-BTP; nothing is written before that. */
            private val proven = AtomicBoolean(false)

            /** Where replies are written; on a V2 dash a different characteristic from [notifyCharacteristic]. */
            @Volatile
            private var writeCharacteristic: BluetoothGattCharacteristic? = null

            @Volatile
            private var notifyCharacteristic: BluetoothGattCharacteristic? = null

            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (closed.get()) {
                        runCatching { gatt.disconnect() }
                        return
                    }
                    runCatching { gatt.discoverServices() }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    forget(gatt)
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val pair = serialPairOf(gatt)
                if (pair == null) {
                    // Not a dashboard, or not one that speaks this protocol. Let go at once rather
                    // than sitting on someone's intercom - but say what was there first. The
                    // Cyclone RX2 (support case 901bdf88) sat in this branch for nine days, and
                    // the one-line "no data characteristic" left no way to tell which of
                    // Carbit's tables its firmware actually follows.
                    log("EC-BTP: $label exposes no EC-BTP data characteristic; disconnecting.")
                    describeGattTable(gatt).forEach { line -> log("EC-BTP: $label $line") }
                    runCatching { gatt.disconnect() }
                    return
                }
                val (write, notify) = pair
                writeCharacteristic = write
                notifyCharacteristic = notify
                subscribe(gatt, notify)
                log(
                    "EC-BTP: listening to $label on ${notify.uuid}" +
                        (if (write.uuid == notify.uuid) "." else ", answering on ${write.uuid}.")
                )
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
                val frame = EcBtpProtocol.parse(value)
                if (frame == null) {
                    // Deliberately not silent: a device chattering something we cannot parse is
                    // exactly what a rider's log needs to show, and it is also the evidence that
                    // this peer is NOT a dashboard.
                    log("EC-BTP: $label sent ${value.size} byte(s) that are not an EC-BTP frame; staying silent.")
                    return
                }
                if (proven.compareAndSet(false, true)) {
                    log("EC-BTP: $label speaks EC-BTP - replies to its clock requests are now allowed.")
                }
                val command = frame.command
                log("EC-BTP: $label -> command 0x${(command.toInt() and 0xFF).toString(16)}, ${frame.payload.size} payload byte(s).")
                val reply = when (command) {
                    EcBtpProtocol.CMD_SYNC_TIME ->
                        EcBtpProtocol.syncTimeReply(now().time, zone().rawOffset)
                    EcBtpProtocol.CMD_QUERY_TIME ->
                        EcBtpProtocol.queryTimeReply(now(), zone())
                    else -> null
                }
                if (reply == null) return
                val target = writeCharacteristic ?: return
                val written = runCatching {
                    BleCompat.writeCharacteristic(gatt, target, reply, writeTypeFor(target))
                }.getOrNull()
                log("EC-BTP: answered $label's clock request with ${reply.size} byte(s) (result $written).")
            }
        }

        val opened = runCatching {
            device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        }.getOrNull()
        if (opened == null) {
            log("EC-BTP: could not open a Bluetooth link to $label.")
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

    private fun writeTypeFor(characteristic: BluetoothGattCharacteristic): Int =
        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

    private fun describeGattTable(gatt: BluetoothGatt): List<String> {
        val services = runCatching { gatt.services }.getOrNull().orEmpty()
        return buildList {
            add("GATT table, ${services.size} service(s):")
            services.forEach { service ->
                add("  service ${service.uuid}")
                service.characteristics.orEmpty().forEach { characteristic ->
                    add("    char ${characteristic.uuid} [${describeProperties(characteristic.properties)}]")
                }
            }
        }
    }

    private fun subscribe(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        runCatching { gatt.setCharacteristicNotification(characteristic, true) }
        val descriptor = runCatching { characteristic.getDescriptor(CCC_UUID) }.getOrNull() ?: return
        runCatching {
            BleCompat.writeDescriptor(gatt, descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }
    }

    private fun forget(gatt: BluetoothGatt) {
        val address = runCatching { gatt.device?.address }.getOrNull()
        synchronized(lock) {
            connections.remove(gatt)
            if (address != null) watched.remove(address)
        }
        runCatching { gatt.close() }
    }

    // Not private: EcBtpClockLab experiments on the same UUID lists, and two copies of Carbit's
    // service table would drift apart the first time a new dash adds a row.
    companion object {
        /**
         * How long to scan for an unbonded dashboard.
         *
         * A dash that asks for the time asks within seconds of the session starting - in every
         * field log the whole PXC opening burst lands inside ten - so a window this size is
         * generous, and leaving an LE scan running for a whole ride is battery the rider did not
         * agree to spend.
         */
        const val SCAN_WINDOW_MILLIS = 30_000L

        /**
         * Carbit's two BLE service tables, in their own order.
         *
         * Carbit Ride runs two Nordic managers over the same EC-BTP framing: `WrcNSManager`
         * (`pe/a.java:17`), the handlebar-remote channel, and `HudNSManager` (`ne/a.java:11`),
         * the dashboard channel that `sendSyncTime()` actually writes through. The first seven
         * entries are the remote's table; the last two are the dashboard's. `fff0` sits in both,
         * which is how the Cyclone RX2 (`BLE-ZS-049485`, support case 901bdf88) was recognised
         * as a candidate for nine days and then dropped: its characteristics are from the
         * dashboard table below, and only the remote's were being looked for.
         */
        val SERVICE_UUIDS = listOf(
            UUID.fromString("00001c00-d102-11e1-9b23-000efb0000b2"),
            UUID.fromString("0000474d-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("00001c00-d102-11e1-9b23-000efb0000c3"),
            UUID.fromString("0000474e-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("00001c00-d102-11e1-9b23-000efb0000c6"),
            UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("0000b360-d6d8-c7ec-bdf0-eab1bfc6bcbc"),
            UUID.fromString("00006967-0000-1000-8000-00805f9b34fb")
        )

        /**
         * The matching data characteristics: the remote's (`pe/a.java:20`) followed by the
         * dashboard's (`ne/a.java:14-23`). `HudNSManager` accepts three layouts of the latter -
         * V1: `b362` carries both notify and write; V2: `b364` notifies, `b363` is written;
         * V3: `b364` does both - which is why [serialPair] chooses by property rather than by
         * position in this list.
         */
        val CHARACTERISTIC_UUIDS = listOf(
            UUID.fromString("00001c0f-d102-11e1-9b23-000efb0000b2"),
            UUID.fromString("00004b59-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("00001c0f-d102-11e1-9b23-000efb0000c6"),
            UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("0000b362-d6d8-c7ec-bdf0-eab1bfc6bcbc"),
            UUID.fromString("0000b363-d6d8-c7ec-bdf0-eab1bfc6bcbc"),
            UUID.fromString("0000b364-d6d8-c7ec-bdf0-eab1bfc6bcbc")
        )

        val CCC_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /**
         * The (write, notify) characteristic UUIDs to use on a peripheral whose GATT table is
         * [services] - each entry a service UUID with its characteristics as (UUID, properties) -
         * or null when no service in [SERVICE_UUIDS] carries a characteristic in
         * [CHARACTERISTIC_UUIDS].
         *
         * Every listed service is examined, not just the first one present: a dash carrying
         * `ffe0` for something else and `fff0` for EC-BTP was previously abandoned at `ffe0`.
         * Within a service the write side is the first known characteristic that can be written
         * and the notify side the first that can notify or indicate; a known characteristic
         * whose firmware declares no properties at all still counts for both, which is what the
         * remote-table dashes were relying on before this had a notion of properties.
         */
        internal fun serialPair(services: List<Pair<UUID, List<Pair<UUID, Int>>>>): Pair<UUID, UUID>? {
            SERVICE_UUIDS.forEach { serviceUuid ->
                services.filter { (uuid, _) -> uuid == serviceUuid }.forEach { (_, characteristics) ->
                    val known = characteristics.filter { (uuid, _) -> CHARACTERISTIC_UUIDS.contains(uuid) }
                    if (known.isEmpty()) return@forEach
                    val write = known.firstOrNull { (_, properties) -> properties and WRITE_PROPERTIES != 0 }
                    val notify = known.firstOrNull { (_, properties) -> properties and NOTIFY_PROPERTIES != 0 }
                    val bare = known.firstOrNull { (_, properties) -> properties == 0 }
                    val writeUuid = (write ?: bare)?.first ?: return@forEach
                    val notifyUuid = (notify ?: bare)?.first ?: return@forEach
                    return writeUuid to notifyUuid
                }
            }
            return null
        }

        /**
         * [serialPair] applied to a live connection: the (write, notify) characteristics to use,
         * or null when the peripheral offers none of Carbit's pairs.
         */
        internal fun serialPairOf(gatt: BluetoothGatt): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
            val services = runCatching { gatt.services }.getOrNull().orEmpty()
            val shape = services.map { service ->
                service.uuid to service.characteristics.orEmpty().map { it.uuid to it.properties }
            }
            val (writeUuid, notifyUuid) = serialPair(shape) ?: return null
            val write = services.firstNotNullOfOrNull { service ->
                runCatching { service.getCharacteristic(writeUuid) }.getOrNull()
            } ?: return null
            val notify = services.firstNotNullOfOrNull { service ->
                runCatching { service.getCharacteristic(notifyUuid) }.getOrNull()
            } ?: return null
            return write to notify
        }

        internal fun describeProperties(properties: Int): String = buildList {
            if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("read")
            if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("write")
            if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("write-nr")
            if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("notify")
            if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("indicate")
        }.ifEmpty { listOf("none") }.joinToString("+")

        private const val WRITE_PROPERTIES =
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        private const val NOTIFY_PROPERTIES =
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                BluetoothGattCharacteristic.PROPERTY_INDICATE
    }
}
