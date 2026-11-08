// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.session.TBoxConnectionMode

/**
 * Whether a Bluetooth connection is allowed to run — the Bluetooth sibling of [WifiDirectGate].
 * Checked before [ThinkerRideTransport] starts scanning, because a missing
 * `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` grant makes the LE scanner throw `SecurityException`
 * from deep inside a callback, which reads as a crash rather than the one screen tap that
 * actually fixes it.
 *
 * [requiresBle] is about ThinkerRide specifically, but [blePermissions] and [hasBlePermissions]
 * are the app's one answer for "may this process touch the LE radio" and [EcBtpTimeLink] uses
 * them too: its dash-clock write goes to an unbonded peripheral, which has to be scanned for.
 */
internal object ThinkerRideGate {

    /** Runtime grants a BLE GATT handshake needs on Android 12+ (minSdk 34 here). */
    val blePermissions = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT
    )

    /**
     * True when [profile] pairs over BLE, so connect flows know to ask for [blePermissions].
     *
     * Two unrelated protocols answer yes. ThinkerRide runs its pairing handshake over BLE before
     * the dash connects back over TCP; a Bluetooth-provisioned dash
     * ([TBoxConnectionMode.BLE_PROVISIONED]) has no network at all until the same radio has been
     * used to give it one. Both fail the same way without the grant - a `SecurityException` from
     * inside a scan callback - so both are gated here.
     */
    fun requiresBle(profile: MotorcycleProfile?): Boolean =
        profile?.connectionMode == TBoxConnectionMode.THINKERRIDE ||
            profile?.connectionMode == TBoxConnectionMode.BLE_PROVISIONED ||
            profile?.connectionMode == TBoxConnectionMode.MYSPIN ||
            (profile != null && TBoxModelProfile.resolve(
                profile.modelId,
                null,
                ProfileOverride.byKey(profile.profileOverrideKey)
            ).transportFamily == TBoxTransportFamily.THINKERRIDE)

    /**
     * Like [WifiDirectGate.hasNearbyDevicesPermission], [packageName] matters because the BLE
     * scan runs in the process that owns the transport: when a companion (PRO) drives the
     * connection, the pairing happens inside CORE, whose runtime grants are the ones that count
     * — and which the rider never gets asked for, because CORE stays in the background.
     */
    fun hasBlePermissions(
        context: Context,
        packageName: String = context.packageName
    ): Boolean = blePermissions.all { permission ->
        context.packageManager.checkPermission(permission, packageName) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * Whether a BLE scan could actually run right now: the radio is on and the process that would
     * do the scanning holds the grants.
     *
     * Asked before *offering* a Bluetooth path rather than before taking one. A phone-hotspot dash
     * with no hotspot running used to fail instantly with "turn your hotspot on", which is the
     * right advice for the dashes that print credentials and useless for the ones that do not -
     * those can only be reached by trying Bluetooth. Trying costs a scan, so it is worth doing
     * when a scan is possible and worth skipping, in favour of the instant message, when it is not.
     */
    fun bluetoothReady(
        context: Context,
        packageName: String = context.packageName
    ): Boolean {
        if (!hasBlePermissions(context, packageName)) return false
        return runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
        }.getOrDefault(false)
    }

    fun missingPermissionMessage(appName: String): String =
        "$appName does not have the $PERMISSION_MARKER, which this dashboard needs for " +
            "pairing. Allow it in $appName's app info, then tap Connect again."

    /**
     * Whether an error banner is showing [missingPermissionMessage], so the screen can offer
     * the one action that fixes it — matched on the message text the same way [WifiDirectGate]
     * does, and distinct from its marker (this one names Bluetooth).
     */
    fun isMissingPermissionMessage(message: String): Boolean = PERMISSION_MARKER in message

    private const val PERMISSION_MARKER = "\"Nearby devices\" (Bluetooth) permission"
}
