// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Whether the process that will actually run the Wi-Fi Direct join is allowed to run it.
 *
 * `WifiP2pManager` answers a caller without `NEARBY_WIFI_DEVICES` exactly the way it answers a
 * wedged radio: `discoverPeers()` and `connect()` both fail within milliseconds with a bare
 * `ERROR` - "internal error" - and the P2P state queries keep working, so a rider's log shows a
 * healthy `p2p=enabled` stack that refuses every request. The two causes are indistinguishable
 * after the fact; they have to be separated before the join is attempted.
 *
 * The [WifiNetworkSpecifier][TBoxNetworkConnector] path has no such gate, which is why an app can
 * join every ordinary T-Box AP for months without ever holding this permission and only fall over
 * on the first `DIRECT-` dash.
 *
 * Hence [packageName]: runtime permissions are per package, and when ADVANCED drives the
 * connection the join runs in CORE's process - a package the rider never brings to the foreground
 * and which therefore never gets to ask for its own permission. ADVANCED checks CORE's grant,
 * CORE checks its own. `PackageManager.checkPermission` reports another package's runtime grant
 * without any privilege of its own.
 *
 * Field log 2026-07-30 (Xiaomi 25062RN2DL, `DIRECT-VOGE-034672`, ADVANCED driving CORE): fresh
 * process, `p2p=enabled`, discovery refused after 23ms and `connect()` refused after 10ms.
 */
internal object WifiDirectGate {

    /**
     * The runtime permission `WifiP2pManager` gates every call on for this OS release:
     * `NEARBY_WIFI_DEVICES` exists only from Android 13; on Android 12/12L the framework runs
     * the very same gate on `ACCESS_FINE_LOCATION` instead. Checking the 13+ permission on a
     * 12 phone would always report DENIED (an unknown permission is never granted), bricking
     * every Wi-Fi Direct join there.
     */
    val requiredPermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }

    /** True when [packageName] holds the runtime permission `WifiP2pManager` gates every call on. */
    fun hasNearbyDevicesPermission(
        context: Context,
        packageName: String = context.packageName
    ): Boolean = context.packageManager.checkPermission(
        requiredPermission,
        packageName
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * The phone-wide location toggle, which is not a permission and cannot be granted per app.
     * Android still consults it for nearby-device calls unless the app declares
     * `NEARBY_WIFI_DEVICES` with `neverForLocation` - which this app does not - so a phone with
     * location switched off is a plausible second cause of the same bare `ERROR`. Reported rather
     * than enforced: unlike the permission, whether it blocks P2P varies by OEM build, and
     * blocking a join that would have worked is worse than a slightly longer failure.
     */
    fun isLocationEnabled(context: Context): Boolean = locationEnabledOrNull(context) ?: true

    /**
     * The same toggle, but **null when it cannot be read** - no LocationManager, or one that
     * threw.
     *
     * [isLocationEnabled] deliberately answers true there, because it gates a hint and blocking a
     * join that would have worked is the worse mistake. A diagnostics report is the opposite
     * case: "the phone says location is on" and "nobody could ask" are different facts, and a
     * report that prints the first for the second sends its reader somewhere else entirely. Same
     * rule as the per-half permission fields beside it.
     */
    fun locationEnabledOrNull(context: Context): Boolean? {
        val locationManager = context.getSystemService(LocationManager::class.java) ?: return null
        return runCatching { locationManager.isLocationEnabled }.getOrNull()
    }

    /** Shown to the rider when the joining app has no grant for [requiredPermission]. */
    fun missingPermissionMessage(appName: String): String =
        "$appName does not have the $PERMISSION_MARKER, which Android requires to join " +
            "a Wi-Fi Direct dashboard. Allow it in $appName's app info, then tap Connect again."

    /**
     * Whether an error banner is showing [missingPermissionMessage], so the screen can offer the
     * one action that fixes it. Matched on the message rather than carried as a separate flag
     * because that is how the session state already travels to the UI (see `WifiGate`).
     */
    fun isMissingPermissionMessage(message: String): Boolean = PERMISSION_MARKER in message

    // The marker names the permission the rider must actually flip: "Nearby devices" is the
    // 13+ toggle, "Location" the 12/12L one. Generated and matched on the same phone, so the
    // two sides of isMissingPermissionMessage always agree.
    private val PERMISSION_MARKER: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            "\"Nearby devices\" permission"
        } else {
            "\"Location\" permission"
        }

    /** Appended to a P2P failure when location services are off - a cause, not a certainty. */
    const val LOCATION_OFF_HINT: String =
        "Location services are also switched off on this phone; Android can refuse Wi-Fi Direct " +
            "in that state, so turn them on before retrying."

    fun openAppInfo(context: Context, packageName: String): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)

    fun openLocationSettings(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)
}
