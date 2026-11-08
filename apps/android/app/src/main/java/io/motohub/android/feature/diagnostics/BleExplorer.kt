// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.diagnostics

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.motohub.android.session.ProjectionEventLog
import io.motohub.android.tbox.BleCompat
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A general-purpose BLE workbench: scan, connect, walk the GATT tree, read, write, subscribe, and
 * watch every byte that moves.
 *
 * It exists because the alternative is guessing. A remote that advertises no service UUIDs cannot
 * be recognised by any app — the phone can only find out what it is by connecting and asking, and
 * until this screen there was nowhere in MOTO-HUB to ask. Studying a vendor's own app only
 * works when there IS a vendor app; this works on anything with a radio.
 *
 * **Every GATT operation is queued.** Android allows exactly one in flight per connection, and a
 * second call while the first is outstanding is refused rather than delayed — silently, from
 * inside a callback. So reads, writes and descriptor writes all go through [enqueue], each
 * released by the callback of the one before, with a watchdog so a stack that never answers
 * cannot wedge the queue forever.
 *
 * Every Bluetooth call is wrapped against [SecurityException]: the runtime grant can be revoked
 * mid-session and that must surface as a failed operation, never a crash.
 */
@SuppressLint("MissingPermission")
object BleExplorer {

    private const val TAG = "BLE_LAB"
    private const val OPERATION_TIMEOUT_MS = 6_000L
    private const val MAX_TRAFFIC_LINES = 400
    /** Advertisements older than this drop out of the list, so the view reflects what is there. */
    private const val STALE_AFTER_MS = 20_000L

    private val handler = Handler(Looper.getMainLooper())

    private val mutableScanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = mutableScanning.asStateFlow()

    private val mutableDevices = MutableStateFlow<List<BleScanEntry>>(emptyList())
    val devices: StateFlow<List<BleScanEntry>> = mutableDevices.asStateFlow()

    private val mutableLinkState = MutableStateFlow(BleLinkState.IDLE)
    val linkState: StateFlow<BleLinkState> = mutableLinkState.asStateFlow()

    private val mutableConnected = MutableStateFlow<BleScanEntry?>(null)
    val connected: StateFlow<BleScanEntry?> = mutableConnected.asStateFlow()

    private val mutableServices = MutableStateFlow<List<BleServiceNode>>(emptyList())
    val services: StateFlow<List<BleServiceNode>> = mutableServices.asStateFlow()

    private val mutableTraffic = MutableStateFlow<List<BleTrafficLine>>(emptyList())
    val traffic: StateFlow<List<BleTrafficLine>> = mutableTraffic.asStateFlow()

    private val mutableMtu = MutableStateFlow(23)
    val mtu: StateFlow<Int> = mutableMtu.asStateFlow()

    @Volatile private var gatt: BluetoothGatt? = null
    private var scanCallback: ScanCallback? = null
    private val seen = LinkedHashMap<String, BleScanEntry>()
    private val notifying = HashSet<String>()
    private val values = HashMap<String, ByteArray>()

    private val queue = ArrayDeque<Operation>()
    @Volatile private var inFlight: Operation? = null
    private var watchdog: Runnable? = null

    private class Operation(val description: String, val start: () -> Boolean)

    // ---------------------------------------------------------------- scanning

    fun startScan(context: Context) {
        val scanner = runCatching {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
                ?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        }.getOrNull()
        if (scanner == null) {
            note(BleTrafficLine.Kind.FAILURE, "Bluetooth is off, or this phone has no LE scanner.")
            return
        }
        if (mutableScanning.value) return
        seen.clear()
        mutableDevices.value = emptyList()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = record(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::record)
            override fun onScanFailed(errorCode: Int) {
                mutableScanning.value = false
                note(BleTrafficLine.Kind.FAILURE, "Scan failed (code $errorCode).")
            }
        }
        // Unfiltered on purpose: this screen exists for devices that declare nothing, and a
        // filter would hide exactly the ones worth looking at.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val started = runCatching { scanner.startScan(null, settings, callback) }
        if (started.isFailure) {
            note(
                BleTrafficLine.Kind.FAILURE,
                "Cannot scan: ${started.exceptionOrNull()?.message ?: "the Nearby devices permission is missing"}."
            )
            return
        }
        scanCallback = callback
        mutableScanning.value = true
        note(BleTrafficLine.Kind.INFO, "Scanning.")
    }

    fun stopScan(context: Context) {
        val callback = scanCallback ?: return
        scanCallback = null
        mutableScanning.value = false
        runCatching {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
                ?.adapter?.bluetoothLeScanner?.stopScan(callback)
        }
        note(BleTrafficLine.Kind.INFO, "Scan stopped.")
    }

    private fun record(result: ScanResult) {
        val record = result.scanRecord
        val entry = BleScanEntry(
            address = result.device.address,
            name = runCatching { result.device.name }.getOrNull() ?: record?.deviceName,
            rssi = result.rssi,
            connectable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) result.isConnectable else true,
            serviceUuids = record?.serviceUuids?.map { it.uuid.toString() }.orEmpty(),
            serviceData = record?.serviceData?.map { (uuid, bytes) ->
                "${BleNames.short(uuid.uuid)}=${BleHex.encode(bytes)}"
            }.orEmpty(),
            manufacturer = buildList {
                val data = record?.manufacturerSpecificData ?: return@buildList
                for (i in 0 until data.size()) {
                    add("0x%04X=%s".format(data.keyAt(i), BleHex.encode(data.valueAt(i))))
                }
            },
            txPower = record?.txPowerLevel?.takeIf { it != Int.MIN_VALUE },
            lastSeenElapsed = SystemClock.elapsedRealtime()
        )
        seen[entry.address] = entry
        val cutoff = SystemClock.elapsedRealtime() - STALE_AFTER_MS
        mutableDevices.value = seen.values
            .filter { it.lastSeenElapsed >= cutoff }
            .sortedByDescending { it.rssi }
    }

    // -------------------------------------------------------------- connecting

    fun connect(context: Context, entry: BleScanEntry) {
        disconnect()
        val adapter = runCatching {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        }.getOrNull() ?: return
        val device: BluetoothDevice = runCatching { adapter.getRemoteDevice(entry.address) }
            .getOrElse {
                note(BleTrafficLine.Kind.FAILURE, "${entry.address} is not a usable address.")
                return
            }
        mutableConnected.value = entry
        mutableLinkState.value = BleLinkState.CONNECTING
        note(BleTrafficLine.Kind.INFO, "Connecting to ${entry.label} (${entry.address}).")
        gatt = runCatching { device.connectGatt(context, false, callback) }.getOrNull()
        if (gatt == null) {
            mutableLinkState.value = BleLinkState.DISCONNECTED
            note(BleTrafficLine.Kind.FAILURE, "Could not open a GATT connection.")
        }
    }

    fun disconnect() {
        clearQueue()
        val open = gatt ?: return
        gatt = null
        runCatching { open.disconnect() }
        runCatching { open.close() }
        notifying.clear()
        values.clear()
        mutableServices.value = emptyList()
        mutableMtu.value = 23
        mutableLinkState.value = BleLinkState.DISCONNECTED
        note(BleTrafficLine.Kind.INFO, "Disconnected.")
    }

    fun rediscoverServices() {
        val open = gatt ?: return
        mutableLinkState.value = BleLinkState.DISCOVERING
        runCatching { open.discoverServices() }
        note(BleTrafficLine.Kind.OUT, "Re-running service discovery.")
    }

    fun requestMtu(size: Int) {
        val open = gatt ?: return
        enqueue("request MTU $size") { runCatching { open.requestMtu(size) }.getOrDefault(false) }
    }

    fun readRemoteRssi() {
        val open = gatt ?: return
        enqueue("read RSSI") { runCatching { open.readRemoteRssi() }.getOrDefault(false) }
    }

    // ------------------------------------------------------------- operations

    fun read(node: BleCharacteristicNode) {
        val open = gatt ?: return
        val characteristic = find(open, node) ?: return
        enqueue("read ${BleNames.short(node.uuid)}") {
            runCatching { open.readCharacteristic(characteristic) }.getOrDefault(false)
        }
    }

    fun write(node: BleCharacteristicNode, value: ByteArray, withResponse: Boolean) {
        val open = gatt ?: return
        val characteristic = find(open, node) ?: return
        val type = if (withResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        enqueue("write ${BleHex.encode(value)} -> ${BleNames.short(node.uuid)}") {
            runCatching {
                BleCompat.writeCharacteristic(open, characteristic, value, type) ==
                    BluetoothStatusCodes.SUCCESS
            }.getOrDefault(false)
        }
    }

    /**
     * Subscribes or unsubscribes, picking notify or indicate from what the characteristic offers.
     *
     * Both halves matter: `setCharacteristicNotification` only tells the local stack to deliver,
     * while the CCCD write is what tells the peripheral to send. Doing one without the other is
     * the classic silent failure — the app looks subscribed and nothing ever arrives.
     */
    fun setNotifying(node: BleCharacteristicNode, enabled: Boolean) {
        val open = gatt ?: return
        val characteristic = find(open, node) ?: return
        val cccd = characteristic.getDescriptor(CCCD)
        if (cccd == null) {
            note(BleTrafficLine.Kind.FAILURE, "${BleNames.short(node.uuid)} has no CCCD to subscribe with.")
            return
        }
        val value = when {
            !enabled -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            node.canNotify -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        runCatching { open.setCharacteristicNotification(characteristic, enabled) }
        enqueue("${if (enabled) "subscribe" else "unsubscribe"} ${BleNames.short(node.uuid)}") {
            runCatching {
                BleCompat.writeDescriptor(open, cccd, value) == BluetoothStatusCodes.SUCCESS
            }.getOrDefault(false)
        }
        if (enabled) notifying.add(key(node)) else notifying.remove(key(node))
    }

    fun clearTraffic() {
        mutableTraffic.value = emptyList()
    }

    // ----------------------------------------------------------------- internals

    private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private fun key(node: BleCharacteristicNode) = "${node.serviceUuid}/${node.uuid}"

    private fun find(open: BluetoothGatt, node: BleCharacteristicNode): BluetoothGattCharacteristic? {
        val characteristic = open.getService(node.serviceUuid)?.getCharacteristic(node.uuid)
        if (characteristic == null) {
            note(BleTrafficLine.Kind.FAILURE, "${BleNames.short(node.uuid)} is gone from the tree.")
        }
        return characteristic
    }

    private fun enqueue(description: String, start: () -> Boolean) {
        queue.addLast(Operation(description, start))
        pump()
    }

    private fun pump() {
        if (inFlight != null) return
        val next = queue.removeFirstOrNull() ?: return
        inFlight = next
        note(BleTrafficLine.Kind.OUT, next.description)
        if (!next.start()) {
            note(BleTrafficLine.Kind.FAILURE, "${next.description} - refused by the stack.")
            inFlight = null
            pump()
            return
        }
        val timeout = Runnable {
            if (inFlight === next) {
                note(BleTrafficLine.Kind.FAILURE, "${next.description} - no answer in ${OPERATION_TIMEOUT_MS}ms.")
                inFlight = null
                pump()
            }
        }
        watchdog = timeout
        handler.postDelayed(timeout, OPERATION_TIMEOUT_MS)
    }

    private fun finish() {
        watchdog?.let(handler::removeCallbacks)
        watchdog = null
        inFlight = null
        pump()
    }

    private fun clearQueue() {
        queue.clear()
        watchdog?.let(handler::removeCallbacks)
        watchdog = null
        inFlight = null
    }

    private fun note(kind: BleTrafficLine.Kind, text: String) {
        handler.post {
            mutableTraffic.value = (mutableTraffic.value + BleTrafficLine(System.currentTimeMillis(), kind, text))
                .takeLast(MAX_TRAFFIC_LINES)
        }
        // Mirrored into the diagnostic log so a session can be sent in a report, which is the
        // whole point when the device being probed belongs to someone else.
        when (kind) {
            BleTrafficLine.Kind.FAILURE -> ProjectionEventLog.record(TAG, text)
            else -> ProjectionEventLog.debug(TAG, text)
        }
    }

    private fun publishServices(open: BluetoothGatt) {
        mutableServices.value = open.services.map { service ->
            BleServiceNode(
                uuid = service.uuid,
                primary = service.type == android.bluetooth.BluetoothGattService.SERVICE_TYPE_PRIMARY,
                characteristics = service.characteristics.map { characteristic ->
                    BleCharacteristicNode(
                        serviceUuid = service.uuid,
                        uuid = characteristic.uuid,
                        properties = characteristic.properties,
                        descriptors = characteristic.descriptors.map { BleNames.short(it.uuid) },
                        notifying = notifying.contains("${service.uuid}/${characteristic.uuid}"),
                        lastValue = values["${service.uuid}/${characteristic.uuid}"]
                    )
                }
            )
        }
    }

    private fun remember(open: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        val service = characteristic.service?.uuid ?: return
        values["$service/${characteristic.uuid}"] = value
        publishServices(open)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(open: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                mutableLinkState.value = BleLinkState.DISCOVERING
                note(BleTrafficLine.Kind.INFO, "Connected; discovering services.")
                runCatching { open.discoverServices() }
                return
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                clearQueue()
                notifying.clear()
                mutableServices.value = emptyList()
                mutableLinkState.value = BleLinkState.DISCONNECTED
                note(BleTrafficLine.Kind.INFO, "Link dropped (status $status).")
                runCatching { open.close() }
                if (gatt === open) gatt = null
            }
        }

        override fun onServicesDiscovered(open: BluetoothGatt, status: Int) {
            publishServices(open)
            mutableLinkState.value = BleLinkState.READY
            val characteristics = open.services.sumOf { it.characteristics.size }
            note(
                BleTrafficLine.Kind.IN,
                "${open.services.size} services, $characteristics characteristics (status $status)."
            )
        }

        override fun onMtuChanged(open: BluetoothGatt, mtu: Int, status: Int) {
            mutableMtu.value = mtu
            note(BleTrafficLine.Kind.IN, "MTU is now $mtu (status $status).")
            finish()
        }

        override fun onReadRemoteRssi(open: BluetoothGatt, rssi: Int, status: Int) {
            note(BleTrafficLine.Kind.IN, "RSSI $rssi dBm.")
            finish()
        }

        override fun onCharacteristicRead(
            open: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            report("read", characteristic, value, status)
            remember(open, characteristic, value)
            finish()
        }

        @Deprecated("Pre-33 shape; forwarded to the value-carrying one.")
        override fun onCharacteristicRead(
            open: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: ByteArray(0)
            report("read", characteristic, value, status)
            remember(open, characteristic, value)
            finish()
        }

        override fun onCharacteristicWrite(
            open: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            note(
                if (status == BluetoothGatt.GATT_SUCCESS) BleTrafficLine.Kind.IN else BleTrafficLine.Kind.FAILURE,
                "write to ${BleNames.short(characteristic.uuid)} -> status $status"
            )
            finish()
        }

        override fun onDescriptorWrite(
            open: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            note(
                if (status == BluetoothGatt.GATT_SUCCESS) BleTrafficLine.Kind.IN else BleTrafficLine.Kind.FAILURE,
                "descriptor ${BleNames.short(descriptor.uuid)} -> status $status"
            )
            publishServices(open)
            finish()
        }

        override fun onCharacteristicChanged(
            open: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            report("notify", characteristic, value, BluetoothGatt.GATT_SUCCESS)
            remember(open, characteristic, value)
        }

        @Deprecated("Pre-33 shape; forwarded to the value-carrying one.")
        override fun onCharacteristicChanged(
            open: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            report("notify", characteristic, value, BluetoothGatt.GATT_SUCCESS)
            remember(open, characteristic, value)
        }
    }

    private fun report(what: String, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
        val ascii = BleHex.ascii(value).takeIf { text -> text.any { it != '.' } }
        note(
            BleTrafficLine.Kind.IN,
            "$what ${BleNames.short(characteristic.uuid)}: ${BleHex.encode(value)}" +
                (ascii?.let { "  \"$it\"" } ?: "") +
                (if (status != BluetoothGatt.GATT_SUCCESS) "  (status $status)" else "")
        )
    }
}
