// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import kotlinx.coroutines.flow.MutableSharedFlow
import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import io.motohub.android.i18n.MotoHubStrings
import io.motohub.android.session.ProjectionEventLog
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking

/**
 * The Bluetooth half of a mySPIN connection: the phone opens an RFCOMM service and the paired dash
 * connects to it, then [MySpinControl] runs the JSON dialogue that yields the Wi-Fi credentials.
 *
 * The link has to outlive [awaitCredentials]. Once the phone has joined the dash Wi-Fi it reports
 * so through [reportPhoneWlan], and the dash keeps asking for the phone's Wi-Fi state for the rest
 * of the session; whoever owns the session closes this when it ends.
 *
 * Only the dash the rider saved is accepted ([expectedDevice], its Bluetooth name or address); any
 * other device that connects to the service is dropped and the wait goes on. It must already be
 * among the phone's paired devices, as the official app also requires: without that the wait
 * could only time out, and the first field report (2026-10-20, a Multistrada that never connected)
 * could not tell an unpaired bike from a dash nobody asked to start Ducati Connect.
 *
 * The wait is open-ended, up to [WAIT_CEILING_MS]. The official app listens for as long as the
 * bike is connected: the dash enables its "Ducati Connect" menu item only once the phone is
 * connected as a phone (HFP/A2DP) AND it finds this service on it, and it opens the channel only
 * when the rider selects that item. A fixed 180 s ran out on the second field report while the
 * rider was still waiting for the bike's own Bluetooth to settle. Both facts are logged and shown
 * while waiting, so the rider knows which step they are at.
 *
 * A BMW Motorrad TFT (Map mode) turns the roles round: the phone connects to the bike's RFCOMM
 * service and the setup is ZeroC Ice, not JSON ([BmwIceDialog]).
 * Everything after the Wi-Fi credentials is the same mySPIN session.
 *
 * [standby] marks a link opened by [MySpinStandby] because the saved bike connected over
 * Bluetooth before the rider asked to connect; the link step adopts it ([adopt]) instead of
 * opening a second service.
 */
class MySpinBluetoothLink(
    context: Context,
    private val expectedDevice: String,
    regionCode: String,
    standby: Boolean = false
) : Closeable {

    /** What the dash told the phone to join. */
    sealed interface Credentials {
        data class AccessPoint(val ssid: String, val password: String, val frequencyBand: String) : Credentials
        data class WifiDirect(val deviceName: String, val pin: String?) : Credentials
    }

    private val appContext = context.applicationContext
    private val writes = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "myspin-bt-write") }
    private val closed = AtomicBoolean()
    private val started = AtomicBoolean()
    private val control = MySpinControl(regionCode, send = ::write)
    private val credentials = CompletableDeferred<Credentials>()
    private val bmw = isBmw(expectedDevice)
    @Volatile private var ice: BmwIceDialog? = null
    private val iceFramesLogged = AtomicInteger()

    @Volatile var standby: Boolean = standby
        private set
    @Volatile private var delivered: Credentials? = null
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var wifiDirectDeviceName: (suspend () -> String?)? = null
    @Volatile private var dash: BluetoothDevice? = null
    @Volatile private var aclReceiverRegistered = false
    @Volatile private var keepAlive: AutoCloseable? = null
    @Volatile private var openedAt = 0L
    @Volatile private var lastStatus: String? = null
    @Volatile private var lastStatusAt = 0L

    /** Null until an ACL event or a profile proxy says otherwise. */
    @Volatile private var dashLinkUp: Boolean? = null
    @Volatile private var headset: BluetoothProfile? = null
    @Volatile private var a2dp: BluetoothProfile? = null

    /** The dash's Bluetooth link coming and going while we wait, so a field log shows it. */
    private val aclReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
            if (!device.address.equals(dash?.address, ignoreCase = true)) return
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    dashLinkUp = true
                    log("Bluetooth link to the dash is up.")
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    dashLinkUp = false
                    log("Bluetooth link to the dash dropped.")
                }
                else -> return
            }
            reportStatus(force = true)
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            when (profile) {
                BluetoothProfile.HEADSET -> headset = proxy
                BluetoothProfile.A2DP -> a2dp = proxy
                else -> return
            }
            reportStatus(force = true)
        }

        override fun onServiceDisconnected(profile: Int) {
            when (profile) {
                BluetoothProfile.HEADSET -> headset = null
                BluetoothProfile.A2DP -> a2dp = null
            }
        }
    }

    /** Called once when the link drops after it was up; null means it was closed on purpose. */
    @Volatile var onLost: ((Throwable?) -> Unit)? = null

    val isOpen: Boolean get() = !closed.get()

    /** The dash already handed over its Wi-Fi; [awaitCredentials] returns at once. */
    val hasCredentials: Boolean get() = delivered != null

    fun servesDevice(saved: String): Boolean = saved.trim().equals(expectedDevice.trim(), ignoreCase = true)

    /** The link step took over a standby link: from here on it belongs to the session. */
    fun adopt() {
        standby = false
    }

    /**
     * Opens the RFCOMM service and starts waiting for the dash. Safe to call more than once; only
     * the first call does anything. [wifiDirectDeviceName] is asked for only when the dash wants
     * push-button Wi-Fi Direct: that request has to carry this phone's own Wi-Fi Direct name.
     */
    fun open(wifiDirectDeviceName: suspend () -> String?): Result<Unit> {
        this.wifiDirectDeviceName = wifiDirectDeviceName
        if (closed.get()) return Result.failure(IOException("the Bluetooth control link is closed"))
        if (!started.compareAndSet(false, true)) return Result.success(Unit)
        if (!hasConnectPermission()) {
            val denied = SecurityException("Nearby devices (Bluetooth) permission is needed to talk to the dash")
            finish(denied)
            return Result.failure(denied)
        }
        // The rider puts the phone away to work the dash menu, and a backgrounded app is frozen
        // within seconds: field report 226E-8B40-60E7 waited 522 s on a 180 s timeout while the
        // dash came and went. Held for the whole life of the link, which spans the Wi-Fi join and
        // discovery too.
        // shortcut: stays up during projection beside the session's own notification; drop it once
        // the session service is running if the second notification bothers riders.
        keepAlive = MySpinKeepAliveService.hold(appContext, KEEP_ALIVE_ID, keepAliveTitle())
        openedAt = System.currentTimeMillis()
        thread(name = "myspin-bt-read", isDaemon = true) { acceptAndRead() }
        return Result.success(Unit)
    }

    /** Opens the link if needed and waits for the dash's Wi-Fi credentials. */
    suspend fun awaitCredentials(wifiDirectDeviceName: suspend () -> String?): Result<Credentials> {
        open(wifiDirectDeviceName).onFailure { return Result.failure(it) }
        return try {
            Result.success(credentials.await())
        } catch (cancellation: CancellationException) {
            close()
            throw cancellation
        } catch (failure: Throwable) {
            Result.failure(failure)
        }
    }

    fun reportPhoneWlan(state: MySpinControl.PhoneWlan) {
        val dialog = ice
        if (dialog != null) {
            val wire = when (state) {
                MySpinControl.PhoneWlan.DISABLED -> BmwIceDialog.PhoneWlan.DISABLED
                MySpinControl.PhoneWlan.READY -> BmwIceDialog.PhoneWlan.ENABLED
                MySpinControl.PhoneWlan.CONNECTED -> BmwIceDialog.PhoneWlan.CONNECTED
            }
            synchronized(dialog) { dialog.reportPhoneWlan(wire) }
            return
        }
        synchronized(control) { control.reportPhoneWlan(state) }
    }

    @SuppressLint("MissingPermission")
    private fun acceptAndRead() {
        try {
            val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
                ?: throw IOException("this phone has no Bluetooth")
            if (!adapter.isEnabled) throw IOException("Bluetooth is off")
            val bonded = adapter.bondedDevices.orEmpty()
            val paired = bonded.firstOrNull { matchesSavedDevice(expectedDevice, it.address, it.name) }
                ?: throw IOException(notPairedMessage(expectedDevice, bonded.mapNotNull { it.name }))
            dash = paired
            log("Paired dash found: \"${paired.name}\" (…${paired.address.takeLast(5)}).")
            installedOfficialApp()?.let { app ->
                ProjectionEventLog.warning(
                    TAG,
                    "The official mySPIN app ($app) is installed; it offers the same Bluetooth service and may take the dash's connection."
                )
            }
            registerAclReceiver()
            adapter.getProfileProxy(appContext, profileListener, BluetoothProfile.HEADSET)
            adapter.getProfileProxy(appContext, profileListener, BluetoothProfile.A2DP)
            if (bmw) {
                readIce(connectBmw(adapter, paired))
                finish(null)
                return
            }
            val listening = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
            server = listening
            log("RFCOMM service open; waiting for the dash \"$expectedDevice\" to connect (up to ${WAIT_CEILING_MS / 60_000} min).")
            val connected = acceptExpected(adapter, listening)
            socket = connected
            runCatching { listening.close() }
            log("Dash connected over RFCOMM; starting the control dialogue.")
            publishNotice(MotoHubStrings.get("The dash opened %1\$s; asking it for its Wi-Fi.", brand))
            synchronized(control) { control.begin() }
            val input = connected.inputStream
            while (!closed.get()) {
                val line = MySpinControl.readLine(input) ?: break
                val events = synchronized(control) { control.receive(line) }
                events.forEach(::onControlEvent)
            }
            finish(null)
        } catch (failure: Throwable) {
            finish(failure)
        }
    }

    /**
     * Waits in short slices so the ceiling and the status line keep working while nothing
     * connects. An accept timeout surfaces as a plain IOException, so one on an open link is a
     * slice ending, not a failure. With Bluetooth off every accept fails at once, so that one ends
     * the wait instead of spinning until the ceiling.
     */
    @SuppressLint("MissingPermission")
    private fun acceptExpected(adapter: BluetoothAdapter, listening: BluetoothServerSocket): BluetoothSocket {
        val deadline = openedAt + WAIT_CEILING_MS
        while (!closed.get()) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            val candidate = try {
                listening.accept(remaining.coerceAtMost(ACCEPT_SLICE_MS).toInt())
            } catch (slice: IOException) {
                if (closed.get()) throw slice
                if (!adapter.isEnabled) throw IOException("Bluetooth was turned off while waiting for the dash", slice)
                reportStatus(force = false)
                continue
            }
            val device = candidate.remoteDevice
            if (isExpected(device.address, device.name)) return candidate
            log("Ignored an RFCOMM connection from ${device.name} (${device.address}): not the saved dash.")
            runCatching { candidate.close() }
        }
        throw SocketTimeoutException(timeoutMessage(WAIT_CEILING_MS / 60_000L, installedOfficialApp() != null, brand))
    }

    /**
     * The BMW app connects as soon as the bike is up on A2DP and retries every few seconds (5
     * tries a minute); this does the same until [WAIT_CEILING_MS]. [socket] holds the attempt so
     * close() can abort a connect that is hanging.
     */
    @SuppressLint("MissingPermission")
    private fun connectBmw(adapter: BluetoothAdapter, tft: BluetoothDevice): BluetoothSocket {
        val deadline = openedAt + WAIT_CEILING_MS
        var attempt = 0
        while (!closed.get() && System.currentTimeMillis() < deadline) {
            attempt++
            runCatching { adapter.cancelDiscovery() }
            val candidate = tft.createRfcommSocketToServiceRecord(BMW_SERVICE_UUID)
            socket = candidate
            try {
                candidate.connect()
                return candidate
            } catch (failure: IOException) {
                socket = null
                runCatching { candidate.close() }
                if (closed.get()) throw failure
                if (attempt == 1 || attempt % 10 == 0) log("The TFT did not accept the Bluetooth connection (attempt $attempt): ${failure.message}")
                reportStatus(force = false)
                Thread.sleep(BMW_RETRY_MS)
            }
        }
        throw SocketTimeoutException(
            MotoHubStrings.get(
                "The TFT did not accept the Bluetooth connection in %1\$d min. Keep the bike connected " +
                    "to this phone for media, and close the BMW Motorrad Connected app.",
                WAIT_CEILING_MS / 60_000
            )
        )
    }

    /** Whole Ice frames in, the dialogue's answers out, a heartbeat so the TFT keeps the link. */
    private fun readIce(connected: BluetoothSocket) {
        log("Connected to the TFT over Bluetooth; starting the Ice dialogue.")
        publishNotice(MotoHubStrings.get("Connected to the TFT over Bluetooth; asking it for its Wi-Fi."))
        val dialog = BmwIceDialog(send = ::writeFrame)
        ice = dialog
        // The BMW app runs Ice with a 15 s idle timeout: anything quieter than that is dropped.
        thread(name = "bmw-ice-heartbeat", isDaemon = true) {
            while (!closed.get()) {
                Thread.sleep(ICE_HEARTBEAT_MS)
                writeFrame(BmwIceWire.frame(BmwIceWire.VALIDATE_CONNECTION))
            }
        }
        val input = DataInputStream(connected.inputStream)
        val header = ByteArray(BmwIceWire.HEADER_SIZE)
        while (!closed.get()) {
            try {
                input.readFully(header)
            } catch (end: EOFException) {
                break
            }
            val frame = header.copyOf(BmwIceWire.frameSize(header))
            input.readFully(frame, BmwIceWire.HEADER_SIZE, frame.size - BmwIceWire.HEADER_SIZE)
            logIceFrame("TFT", frame)
            val events = synchronized(dialog) { dialog.receive(frame) }
            events.forEach(::onIceEvent)
        }
    }

    private fun onIceEvent(event: BmwIceDialog.Event) {
        log("Ice: $event")
        when (event) {
            is BmwIceDialog.Event.Note -> Unit
            is BmwIceDialog.Event.WifiDirect -> deliver(Credentials.WifiDirect(event.deviceName, event.pin))
            is BmwIceDialog.Event.AccessPoint -> deliver(Credentials.AccessPoint(event.ssid, event.passphrase, ""))
            is BmwIceDialog.Event.Failed -> finish(IOException(event.reason))
        }
    }

    private fun writeFrame(frame: ByteArray) {
        if (closed.get()) return
        logIceFrame("phone", frame)
        writes.execute {
            try {
                val output = socket?.outputStream ?: return@execute
                output.write(frame)
                output.flush()
            } catch (failure: IOException) {
                finish(failure)
            }
        }
    }

    /** The first frames in hex, heartbeats left out: the first field log is how the codec gets checked. */
    private fun logIceFrame(from: String, frame: ByteArray) {
        if (frame.size == BmwIceWire.HEADER_SIZE && frame[8].toInt() == BmwIceWire.VALIDATE_CONNECTION && iceFramesLogged.get() > 0) return
        if (iceFramesLogged.incrementAndGet() > ICE_FRAMES_LOGGED) return
        log("Ice $from: ${BmwIceWire.hex(frame, limit = 512)}")
    }

    private fun onControlEvent(event: MySpinControl.Event) {
        log("Control: $event")
        when (event) {
            is MySpinControl.Event.Capabilities ->
                if (event.mode == MySpinControl.WirelessMode.WIFI_DIRECT_PUSH_BUTTON) askPushButton()
            is MySpinControl.Event.AccessPoint ->
                deliver(Credentials.AccessPoint(event.ssid, event.password, event.frequencyBand))
            is MySpinControl.Event.WifiDirect ->
                deliver(Credentials.WifiDirect(event.deviceName, event.pin))
            is MySpinControl.Event.Failed -> finish(IOException(event.reason))
            is MySpinControl.Event.VehicleWlan -> Unit
        }
    }

    private fun deliver(received: Credentials) {
        delivered = received
        credentials.complete(received)
        TBoxTransportNotice.clear()
        if (standby) {
            // Nobody is waiting on this side yet: the notification is what tells the rider.
            log("The dash handed over its Wi-Fi while MOTO-HUB was not connecting; open MOTO-HUB to start.")
            // A Suzuki dash keeps its Wi-Fi up for ~5 s after this: an open MOTO-HUB connects now.
            standbyCredentials.tryEmit(Unit)
            val previous = keepAlive
            keepAlive = MySpinKeepAliveService.hold(appContext, KEEP_ALIVE_ID, keepAliveTitle())
            runCatching { previous?.close() }
        }
    }

    private fun askPushButton() {
        val provider = wifiDirectDeviceName ?: return finish(IOException("no Wi-Fi Direct name provider"))
        thread(name = "myspin-p2p-name", isDaemon = true) {
            val name = runCatching { runBlocking { provider() } }.getOrNull()
            if (name.isNullOrBlank()) {
                finish(IOException("this phone's Wi-Fi Direct name is unknown"))
            } else {
                synchronized(control) { control.requestWifiDirectPushButton(name) }
            }
        }
    }

    private fun write(line: String) {
        if (closed.get()) return
        writes.execute {
            try {
                val output = socket?.outputStream ?: return@execute
                output.write((line + "\n").toByteArray(Charsets.UTF_8))
                output.flush()
            } catch (failure: IOException) {
                finish(failure)
            }
        }
    }

    private fun isExpected(address: String?, name: String?): Boolean =
        address.equals(dash?.address, ignoreCase = true) || matchesSavedDevice(expectedDevice, address, name)

    private fun registerAclReceiver() {
        val filter = IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED).apply {
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        // System broadcasts; exported as the official app does, since Bluetooth sends them.
        ContextCompat.registerReceiver(appContext, aclReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        aclReceiverRegistered = true
    }

    @SuppressLint("MissingPermission")
    private fun profileConnected(proxy: BluetoothProfile?): Boolean? {
        val device = dash ?: return null
        val connected = proxy ?: return null
        return runCatching { connected.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED }.getOrNull()
    }

    /**
     * One line for the log and one sentence for the rider, whenever the picture changes and at
     * least every [STATUS_EVERY_MS] while waiting. Which step the rider is at is exactly what
     * two field logs could not say.
     */
    private fun reportStatus(force: Boolean) {
        if (closed.get() || socket != null) return
        val phone = profileConnected(headset)
        val media = profileConnected(a2dp)
        // A connected profile proves the link is up even before any ACL broadcast reached us.
        if (phone == true || media == true) dashLinkUp = true
        val now = System.currentTimeMillis()
        val line = statusLine(dashLinkUp, phone, media, (now - openedAt) / 1000, brand)
        // Unchanged pictures are repeated only every STATUS_EVERY_MS, so a long wait stays readable.
        val picture = line.substringBefore(':').let { line.removePrefix(it) }
        if (force || picture != lastStatus || now - lastStatusAt >= STATUS_EVERY_MS) {
            lastStatus = picture
            lastStatusAt = now
            log(line)
        }
        val asPhone = phone == true || media == true
        publishNotice(
            if (asPhone && bmw) {
                MotoHubStrings.get("The bike is connected to this phone; connecting to the TFT over Bluetooth.")
            } else if (asPhone) {
                // The Suzuki and Kawasaki dash menu paths are not documented anywhere we have; Ducati's is (FAQ).
                MotoHubStrings.get(
                    if (brand == SUZUKI_SERVICE || brand == KAWASAKI_SERVICE) {
                        "The bike is connected to this phone. On the dash, open mySPIN, then confirm with the handlebar button."
                    } else {
                        "The bike is connected to this phone. On the dash: Setting menu → Ducati Connect, then confirm with the handlebar button."
                    }
                )
            } else {
                MotoHubStrings.get("Waiting for the bike: it is not connected to this phone over Bluetooth yet. Switch it on and wait for the phone icon on the dash.")
            }
        )
    }

    private fun publishNotice(text: String) {
        if (standby) return
        ProjectionEventLog.record(TAG, text)
        TBoxTransportNotice.publish(text)
    }

    private fun keepAliveTitle(): String = when {
        hasCredentials && standby -> MotoHubStrings.get("%1\$s is ready: open MOTO-HUB to start", brand)
        standby -> MotoHubStrings.get("Motorcycle connected: waiting for %1\$s on the dash", brand)
        else -> MotoHubStrings.get("Waiting for the motorcycle's dash on Bluetooth")
    }

    /** What the dash calls the phone connection, for the rider's messages. */
    private val brand: String get() = dashServiceName(expectedDevice)

    private fun installedOfficialApp(): String? = OFFICIAL_APP_PACKAGES.firstOrNull { app ->
        runCatching { appContext.packageManager.getPackageInfo(app, 0) }.isSuccess
    }

    private fun hasConnectPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun finish(error: Throwable?) {
        if (!closed.compareAndSet(false, true)) return
        if (!standby) TBoxTransportNotice.clear()
        if (aclReceiverRegistered) runCatching { appContext.unregisterReceiver(aclReceiver) }
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
        headset?.let { runCatching { adapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) } }
        a2dp?.let { runCatching { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) } }
        headset = null
        a2dp = null
        runCatching { keepAlive?.close() }
        runCatching { server?.close() }
        runCatching { socket?.close() }
        writes.shutdownNow()
        if (error != null) {
            ProjectionEventLog.warning(TAG, "Bluetooth control link ended: ${error.message}")
        }
        if (current === this) current = null
        // Before credentials arrive the failure belongs to the caller waiting for them.
        if (!credentials.isCompleted) {
            credentials.completeExceptionally(error ?: IOException("the Bluetooth control link closed"))
        } else {
            onLost?.invoke(error)
        }
    }

    override fun close() = finish(null)

    private fun log(message: String) = ProjectionEventLog.record(TAG, message)

    companion object {
        const val TAG = "MYSPIN"
        const val SERVICE_NAME = "MySpinBluetooth"
        val SERVICE_UUID: UUID = UUID.fromString("31FD954E-8138-4367-A201-0276B9716FB1")

        /**
         * The official app has no limit at all while the bike is connected; this one only stops
         * a listener nobody is using any more, and matches the 20 min the dash itself keeps a
         * session alive after key-off.
         */
        const val WAIT_CEILING_MS = 20 * 60_000L
        const val ACCEPT_SLICE_MS = 15_000L
        const val STATUS_EVERY_MS = 60_000L
        private const val KEEP_ALIVE_ID = "ducati-connect"

        /**
         * The official apps, each registering the same RFCOMM service: Ducati Connect and
         * SUZUKI mySPIN are one Bosch launcher (2.26.0) rebranded, and so is Kawasaki SPIN (2.25.0).
         */
        val OFFICIAL_APP_PACKAGES = listOf("com.bosch.myspin.ducati_launcherapp", "com.SUZUKI.SUZUKImySPIN", "jp.co.khi.mce.kawasakispin", "com.bmw.ConnectedRide")

        /** The BMW TFT's Ice service, which the phone connects to. */
        val BMW_SERVICE_UUID: UUID = UUID.fromString("c707050e-efae-1449-3913-c191e5bb32dc")
        const val BMW_RETRY_MS = 6_000L
        const val ICE_HEARTBEAT_MS = 5_000L
        private const val ICE_FRAMES_LOGGED = 40

        const val DUCATI_SERVICE = "Ducati Connect"
        const val SUZUKI_SERVICE = "SUZUKI mySPIN"
        const val KAWASAKI_SERVICE = "Kawasaki SPIN"
        const val BMW_SERVICE = "BMW Motorrad Connected"

        /** The names the BMW app accepts for a bike: "BMW Motorrad " + VIN tail, or the TFT's "ICC…". */
        fun isBmw(bikeName: String): Boolean = bikeName.trim().let {
            it.startsWith("BMW Motorrad", ignoreCase = true) || it.startsWith("ICC")
        }

        /**
         * The name the dash gives the phone connection. The Suzuki launcher only accepts bikes
         * whose Bluetooth name starts with SUZUKI_MYSPIN (Kawasaki's: Kawasaki001, Ducati's: DUCATI); anything else keeps
         * the Ducati wording, which is where this mode came from.
         */
        fun dashServiceName(bikeName: String): String = when {
            bikeName.trim().startsWith("SUZUKI", ignoreCase = true) -> SUZUKI_SERVICE
            bikeName.trim().startsWith("Kawasaki", ignoreCase = true) -> KAWASAKI_SERVICE
            isBmw(bikeName) -> BMW_SERVICE
            else -> DUCATI_SERVICE
        }

        /** The phone's country, as the official app sends; a phone with none gets IT. */
        fun regionCode(): String = Locale.getDefault().country.ifBlank { "IT" }

        /** Names the bike and the paired devices the phone does have, so a typo is visible. */
        fun notPairedMessage(saved: String, pairedNames: List<String>): String {
            val paired = pairedNames.take(10).joinToString(", ").ifEmpty { "none" }
            return "\"$saved\" is not among this phone's paired Bluetooth devices (paired: $paired). " +
                "Pair the motorcycle in Android's Bluetooth settings, then use the name it has there."
        }

        fun timeoutMessage(minutes: Long, officialAppInstalled: Boolean, brand: String = DUCATI_SERVICE): String =
            "the dash did not open $brand in $minutes min; with the bike connected as phone, choose $brand on the dash while MOTO-HUB waits" +
                if (officialAppInstalled) {
                    ". The official $brand app is installed and may be taking the connection: force-stop it and try again"
                } else {
                    ""
                }

        /** The log line while waiting: link, phone (HFP) and media (A2DP) state of the dash. */
        fun statusLine(linkUp: Boolean?, phone: Boolean?, media: Boolean?, seconds: Long, brand: String = DUCATI_SERVICE): String {
            fun word(state: Boolean?) = when (state) {
                true -> "yes"
                false -> "no"
                null -> "unknown"
            }
            val step = when {
                phone == true || media == true -> "the dash can now offer $brand; waiting for the rider to select it"
                linkUp == true -> "link up but not yet connected as phone or media"
                else -> "the bike is not connected to this phone yet"
            }
            return "Still waiting after ${seconds}s: dash link up=${word(linkUp)}, phone (HFP)=${word(phone)}, " +
                "media (A2DP)=${word(media)}; $step."
        }

        /** The saved value is the Bluetooth name the rider typed, or the dash's address. */
        fun matchesSavedDevice(saved: String, address: String?, name: String?): Boolean {
            val wanted = saved.trim()
            return wanted.isNotEmpty() &&
                (wanted.equals(address?.trim(), ignoreCase = true) || wanted.equals(name?.trim(), ignoreCase = true))
        }

        /** Fires when a standby link receives the dash's Wi-Fi; MainActivity connects at once. */
        val standbyCredentials = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        /**
         * The control link of the session being set up or running, or the standby one opened
         * when the bike connected. The link step installs or adopts it and the transport reads
         * it, because the two meet only through the session's [TBoxLink], which has no room for
         * a Bluetooth socket. At most one Ducati is connected at a time.
         */
        @Volatile var current: MySpinBluetoothLink? = null
            private set

        /** Makes [link] the current one, closing whatever was there. */
        fun install(link: MySpinBluetoothLink) {
            val previous = current
            current = link
            if (previous !== link) previous?.close()
        }

        fun closeCurrent() {
            current?.close()
            current = null
        }
    }
}
