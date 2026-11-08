// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import java.net.Inet4Address
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Puts a KTEY dash on a network over Bluetooth. This EyLink variant (Kove 450 Rally, field log
 * 30C1-136A-AF70) names no network in its QR and raises none: the phone hosts one and tells the
 * dash where it is, then the dash reports the address it took and the ordinary EyLink sockets
 * follow in [EyLinkTransport].
 *
 * The exchange is the official EYLINK app's (1.4.34), read from its `LinkFragment`. Every value is
 * text, there are no notifications, so each step is one read or write and the dash is polled:
 *
 *  1. read `aaa5` (`<mac>&<activation key>`), write one zero byte to it (the phone type);
 *  2. read `aaa4`: `-1` wants a hotspot, `-4` a 5GHz group, `-3` is Bluetooth only. A dash that
 *     stays silent gets the hotspot, as it does there;
 *  3. host that network, write its SSID to `aaa2`, its passphrase to `aaa3`, `1` to `aaa4`;
 *  4. poll `aaa1` until it holds the dash's address.
 *
 * Unverified against hardware. The official app also runs a cloud activation after this, which
 * this link does not; if a dash refuses to paint, that is the first suspect.
 *
 * Once provisioned the link stays open for the session, as there, and [close] also takes the
 * hosted network down.
 */
@SuppressLint("MissingPermission")
internal class EyLinkBleLink(
    context: Context,
    private val log: (String) -> Unit
) {

    data class Provisioned(
        val subnet: TBoxHotspotScan.Subnet,
        val dashIp: Inet4Address,
        val dashName: String
    )

    private val appContext = context.applicationContext
    private val bluetooth = appContext.getSystemService(BluetoothManager::class.java)?.adapter
    private val closed = AtomicBoolean(false)

    @Volatile
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var hosted: PhoneHostedNetwork? = null

    /** Resolves once the services are known; fails when the link drops first. */
    private val servicesReady = CompletableDeferred<Unit>()
    private val mtuReady = CompletableDeferred<Unit>()

    /** The one GATT operation in flight; [provision] runs them strictly in sequence. */
    @Volatile
    private var pending: CompletableDeferred<ByteArray?>? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val dropped = IllegalStateException(
                    "The dashboard closed the Bluetooth link during pairing (status $status)."
                )
                servicesReady.completeExceptionally(dropped)
                pending?.completeExceptionally(dropped)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            servicesReady.complete(Unit)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            mtuReady.complete(Unit)
        }

        // API 33 calls the overload with the value and no other; older releases only this one.
        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            finish(status, characteristic.value)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            finish(status, value)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            finish(status, ByteArray(0))
        }

        private fun finish(status: Int, value: ByteArray?) {
            pending?.complete(value.takeIf { status == BluetoothGatt.GATT_SUCCESS })
        }
    }

    /**
     * Runs the exchange. Fails, never throws, for everything a rider can act on. Whatever it fails
     * with has already released the radio; a success leaves that to [close].
     */
    suspend fun provision(name: String, scanTimeoutMillis: Long = SCAN_TIMEOUT_MS): Result<Provisioned> =
        try {
            Result.success(runExchange(name, scanTimeoutMillis))
        } catch (cancellation: CancellationException) {
            close()
            throw cancellation
        } catch (failure: Exception) {
            close()
            Result.failure(failure)
        }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        gatt?.let { runCatching { it.disconnect() }; runCatching { it.close() } }
        gatt = null
        hosted?.close()
    }

    private suspend fun runExchange(name: String, scanTimeoutMillis: Long): Provisioned {
        val device = findDash(name, scanTimeoutMillis)
        val dashName = device.name ?: name
        log("Found $dashName; connecting over Bluetooth.")

        val service = connect(device)
        log(
            "Bluetooth services found. TCP control ${service.has(TCP_CONTROL_FLAG)}, " +
                "TCP mirror ${service.has(TCP_MIRROR_FLAG)}."
        )

        val identity = retried(MAC_READ_ATTEMPTS, READ_RETRY_MS) { read(service, MAC)?.takeIf(ByteArray::isNotEmpty) }
        log("The dash answered ${identity?.size ?: 0} byte(s) on aaa5.")
        write(service, MAC, ByteArray(1))

        var said = ""
        val type = retried(CONNECT_TYPE_ATTEMPTS, CONNECT_TYPE_RETRY_MS) {
            read(service, CONNECT)?.let(EyLinkProtocol::bleText)?.let { text ->
                said = text
                EyLinkProtocol.bleConnectType(text)
            }
        } ?: EyLinkProtocol.BleConnectType.PHONE_HOTSPOT
        log("The dash answered \"$said\" on aaa4; going with $type.")
        check(type != EyLinkProtocol.BleConnectType.BLE_ONLY) {
            "This dashboard only offers a Bluetooth link, so it cannot show the phone's picture."
        }

        val credentials = host(type)
        val subnet = awaitHostedSubnet(appContext)
            ?: error("The network started but no interface appeared for it, so the dashboard could not join.")

        write(service, SSID, credentials.ssid.toByteArray()) ||
            error("Could not send the network name to the dashboard over Bluetooth.")
        write(service, PASSWORD, credentials.passphrase.toByteArray()) ||
            error("Could not send the network password to the dashboard over Bluetooth.")
        write(service, CONNECT, "1".toByteArray()) ||
            error("Could not tell the dashboard to join the network over Bluetooth.")
        log("Told $dashName to join \"${credentials.ssid}\" (${subnet.interfaceName}); waiting for its address.")

        val address = retried(DASH_IP_ATTEMPTS, DASH_IP_RETRY_MS) { read(service, DASH_IP)?.let(EyLinkProtocol::bleDashIp) }
            ?: error("The dashboard never joined the network this phone is hosting.")
        return Provisioned(subnet, InetAddress.getByName(address) as Inet4Address, dashName)
    }

    /** The screen drops the `_LE` the code carries, so either spelling may be on the air. */
    private suspend fun findDash(name: String, timeoutMillis: Long): BluetoothDevice {
        val scanner = bluetooth?.takeIf { it.isEnabled }?.bluetoothLeScanner
            ?: error("Bluetooth is off. Turn it on and connect again.")
        val bare = name.removeSuffix("_LE")
        val filters = listOf(bare, "${bare}_LE").map { ScanFilter.Builder().setDeviceName(it).build() }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val found = CompletableDeferred<BluetoothDevice>()
        val scan = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                found.complete(result.device)
            }

            override fun onScanFailed(errorCode: Int) {
                found.completeExceptionally(IllegalStateException("The Bluetooth scan failed ($errorCode)."))
            }
        }
        log("Scanning Bluetooth for $bare.")
        scanner.startScan(filters, settings, scan)
        try {
            return withTimeoutOrNull(timeoutMillis) { found.await() }
                ?: error(
                    "No dashboard named $bare answered on Bluetooth. Turn the bike on, open the " +
                        "connection screen on the dash, and close the EYLINK app if it is connected."
                )
        } finally {
            runCatching { scanner.stopScan(scan) }
        }
    }

    private suspend fun connect(device: BluetoothDevice): BluetoothGattService {
        val opened = device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: error("Android refused to open a Bluetooth link to the dashboard.")
        gatt = opened
        withTimeoutOrNull(CONNECT_TIMEOUT_MS) { servicesReady.await() }
            ?: error("The dashboard did not finish the Bluetooth connection.")
        opened.requestMtu(MTU)
        withTimeoutOrNull(MTU_TIMEOUT_MS) { mtuReady.await() }
        return opened.getService(SERVICE)
            ?: error("This dashboard does not offer the EyLink Bluetooth service.")
    }

    /** Hosts what the dash asked for, and the other kind if that cannot be brought up. */
    private suspend fun host(type: EyLinkProtocol.BleConnectType): PhoneHostedNetwork.Credentials {
        val networkLog: (String) -> Unit = { log(it) }
        val group = { PhoneHostedP2pGroup(appContext, networkLog) }
        val hotspot = { PhoneHostedHotspot(appContext, networkLog) }
        val wanted = type == EyLinkProtocol.BleConnectType.PHONE_P2P_GROUP
        val first: PhoneHostedNetwork = if (wanted) group() else hotspot()
        hosted = first
        val started = first.start(HOST_TIMEOUT_MS)
        if (started.isSuccess) return started.getOrThrow()

        log("Could not host that network (${started.exceptionOrNull()?.message}); trying the other kind.")
        first.close()
        val second: PhoneHostedNetwork = if (wanted) hotspot() else group()
        hosted = second
        return second.start(HOST_TIMEOUT_MS).getOrThrow()
    }

    private fun BluetoothGattService.has(uuid: UUID) = if (getCharacteristic(uuid) != null) "yes" else "no"

    private suspend fun <T : Any> retried(attempts: Int, waitMillis: Long, step: suspend () -> T?): T? {
        repeat(attempts) {
            delay(waitMillis)
            step()?.let { return it }
        }
        return null
    }

    private suspend fun read(service: BluetoothGattService, uuid: UUID): ByteArray? {
        val characteristic = service.getCharacteristic(uuid) ?: return null
        return operation { gatt?.readCharacteristic(characteristic) == true }
    }

    private suspend fun write(service: BluetoothGattService, uuid: UUID, value: ByteArray): Boolean {
        val characteristic = service.getCharacteristic(uuid) ?: return false
        return operation {
            gatt?.let {
                BleCompat.writeCharacteristic(it, characteristic, value, characteristic.writeType)
            } == BluetoothStatusCodes.SUCCESS
        } != null
    }

    /** Android refuses a new operation while one is finishing, hence the retries on [start]. */
    private suspend fun operation(start: () -> Boolean): ByteArray? {
        repeat(OPERATION_ATTEMPTS) {
            val result = CompletableDeferred<ByteArray?>()
            pending = result
            if (start()) return withTimeoutOrNull(OPERATION_TIMEOUT_MS) { result.await() }
            delay(OPERATION_RETRY_MS)
        }
        return null
    }

    private companion object {
        val SERVICE: UUID = eyUuid("aaa0")
        val DASH_IP: UUID = eyUuid("aaa1")
        val SSID: UUID = eyUuid("aaa2")
        val PASSWORD: UUID = eyUuid("aaa3")
        val CONNECT: UUID = eyUuid("aaa4")
        val MAC: UUID = eyUuid("aaa5")
        val TCP_CONTROL_FLAG: UUID = eyUuid("aaa9")
        val TCP_MIRROR_FLAG: UUID = eyUuid("aaaa")

        const val SCAN_TIMEOUT_MS = 20_000L
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val MTU = 512
        const val MTU_TIMEOUT_MS = 1_500L
        const val HOST_TIMEOUT_MS = 20_000L
        const val OPERATION_ATTEMPTS = 10
        const val OPERATION_RETRY_MS = 100L
        const val OPERATION_TIMEOUT_MS = 3_000L
        const val MAC_READ_ATTEMPTS = 6
        const val READ_RETRY_MS = 500L
        const val CONNECT_TYPE_ATTEMPTS = 4
        const val CONNECT_TYPE_RETRY_MS = 300L

        /** A minute, as the official app gives a dash to join. */
        const val DASH_IP_ATTEMPTS = 60
        const val DASH_IP_RETRY_MS = 1_000L

        fun eyUuid(short: String): UUID = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")
    }
}
