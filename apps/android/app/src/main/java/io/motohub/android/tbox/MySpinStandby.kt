// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import io.motohub.android.data.MotorcycleProfileStore
import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.session.ProjectionEventLog
import io.motohub.android.session.TBoxConnectionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Opens the mySPIN Bluetooth service as soon as the saved Ducati connects to the phone, before
 * the rider asks MOTO-HUB for anything, the way the official app does from its own Bluetooth
 * receiver. The dash offers "Ducati Connect" in its menu only while it can see this service on
 * the connected phone, so a listener that opens only after the rider taps Connect is late for a
 * dash that already looked.
 *
 * The standby link waits with the app's foreground keep-alive; when the dash hands over its Wi-Fi
 * the notification changes to "open MOTO-HUB", and the link step adopts the link, credentials and
 * all, instead of opening a second service ([TBoxLinkResolver]). It closes when the bike's
 * Bluetooth drops or after [MySpinBluetoothLink.WAIT_CEILING_MS].
 */
object MySpinStandby {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    enum class Decision { IGNORE, LISTEN, CLOSE }

    /** Pure: what one ACL event for [address]/[name] means for the saved [profile]. */
    fun decide(
        profile: MotorcycleProfile?,
        address: String?,
        name: String?,
        connected: Boolean,
        current: MySpinBluetoothLink?
    ): Decision {
        if (profile == null || profile.connectionMode != TBoxConnectionMode.MYSPIN) return Decision.IGNORE
        if (!MySpinBluetoothLink.matchesSavedDevice(profile.ssid, address, name)) return Decision.IGNORE
        // A BMW TFT has no service to find on the phone: the link starts when the rider connects.
        if (MySpinBluetoothLink.isBmw(profile.ssid)) return Decision.IGNORE
        val serving = current?.takeIf { it.isOpen && it.servesDevice(profile.ssid) }
        return when {
            connected -> if (serving == null) Decision.LISTEN else Decision.IGNORE
            serving != null && serving.standby && !serving.hasCredentials -> Decision.CLOSE
            else -> Decision.IGNORE
        }
    }

    fun onDashLink(context: Context, device: BluetoothDevice, connected: Boolean) {
        // The bike name needs BLUETOOTH_CONNECT; without it this receiver would throw.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        val store = MotorcycleProfileStore(context)
        val profile = store.load()
        when (decide(profile, device.address, device.name, connected, MySpinBluetoothLink.current)) {
            Decision.IGNORE -> {
                // A mySPIN bike nobody saved yet: offer it, rather than leave the rider to find the mode.
                val name = device.name
                if (connected && name != null && MySpinBikes.unsaved(listOf(name), store.loadAll().map { it.ssid }).isNotEmpty()) {
                    MySpinBikes.notifyFound(context, name)
                }
            }
            Decision.CLOSE -> {
                ProjectionEventLog.record(MySpinBluetoothLink.TAG, "The motorcycle's Bluetooth dropped; closing the standby listener.")
                MySpinBluetoothLink.closeCurrent()
            }
            Decision.LISTEN -> {
                val saved = profile ?: return
                val link = MySpinBluetoothLink(context, saved.ssid, MySpinBluetoothLink.regionCode(), standby = true)
                MySpinBluetoothLink.install(link)
                ProjectionEventLog.record(
                    MySpinBluetoothLink.TAG,
                    "The motorcycle \"${saved.ssid}\" connected over Bluetooth; listening for ${MySpinBluetoothLink.dashServiceName(saved.ssid)} so the dash finds the service."
                )
                val provider: suspend () -> String? = { TBoxLinkResolver.ownWifiDirectName(context) }
                // Opened here, inside the broadcast, so the foreground start is still allowed.
                link.open(provider).onFailure { return }
                scope.launch { link.awaitCredentials(provider) }
            }
        }
    }
}

/** Manifest receiver for the bike's Bluetooth link coming and going; see [MySpinStandby]. */
class MySpinAutoStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val connected = when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> true
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> false
            else -> return
        }
        val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
        MySpinStandby.onDashLink(context, device, connected)
    }
}
