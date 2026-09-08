// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * The runtime grants a process needs to SEE the Wi-Fi air rather than merely ask Android to join
 * a network - and the reason a whole class of connect failure has been undiagnosable.
 *
 * `WifiManager.getScanResults()` and `WifiInfo.getSSID()` are both gated on these. Without them
 * neither throws: the scan hands back an EMPTY list and the SSID reads `<unknown ssid>`, which is
 * indistinguishable from a phone that genuinely sees nothing and a rider who is genuinely on no
 * network. Everything MOTO-HUB knows about the air is built on those two calls:
 * [TBoxNetworkConnector.isDashBroadcasting], [TBoxNetworkConnector.isAssociatedTo],
 * [TBoxNetworkConnector.visibleSsids] and the snapshot in
 * [TBoxNetworkConnector.logVisibleApSnapshot]. All four answer "cannot be said" for a process
 * that was never granted these, forever, silently.
 *
 * WHY THIS IS ITS OWN FILE, AND WHY [heldBy] TAKES A PACKAGE NAME. A runtime permission belongs
 * to a package, and MOTO-HUB is two of them. A rider who drives every connection from the
 * companion app never brings this one to the foreground, so this one never gets to ask - and
 * until now the only place that ever asked was this app's own Connect button
 * (`MainActivity.tboxConnectPermissions`). The bridge path -
 * [io.motohub.android.ipc.IpcBridgeService] -> [io.motohub.android.ipc.CoreTBoxConnector] - has
 * never checked a permission at all.
 *
 * Four installations show what that costs, and they are all the same log: support fc17a4f7
 * (OnePlus CPH2449, ZHKJ13-1122, bj5G0d60, 2026-09-05) prints "The phone's Wi-Fi scan came back
 * empty (0 networks)" on every one of ~60 attempts across two days, with no non-empty scan
 * anywhere in the file; 36a3fd37, 6e77dcf7 and f27f3825 the same. On f27f3825's phone the dash
 * was on its own access point with the phone already associated to it, and the third rung of
 * [AccessPointEvidence] - written for exactly that rider - reads the SSID through the same gate,
 * so it too answered false.
 *
 * The set is deliberately the WHOLE set this app's own Connect button asks for, and any missing
 * one counts as blind. Android 13+ can serve `getScanResults()` on `NEARBY_WIFI_DEVICES` alone,
 * but only for an app that declares it `neverForLocation`, which this one does not (see
 * [WifiDirectGate.isLocationEnabled]) - so the location pair still decides here, and a half-held
 * set is not a state worth having a second opinion about.
 */
internal object TBoxScanPermissions {

    /**
     * `NEARBY_WIFI_DEVICES` exists only from Android 13; requesting an unknown permission on 12
     * gets an instant auto-denial and checking it would report DENIED forever. There the location
     * pair IS the whole gate - the same rule [WifiDirectGate.requiredPermission] already states.
     */
    val required: List<String>
        get() = buildList {
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }

    /**
     * Whether [packageName] holds all of [required].
     *
     * `PackageManager.checkPermission` reports another package's runtime grant without any
     * privilege of its own, which is what lets the companion app ask this about Core - the same
     * mechanism [WifiDirectGate.hasNearbyDevicesPermission] and
     * [ThinkerRideGate.hasBlePermissions] already use for the two grants that make a connect
     * impossible. This one does not make a connect impossible; it makes it blind.
     */
    fun heldBy(context: Context, packageName: String = context.packageName): Boolean =
        required.all {
            context.packageManager.checkPermission(it, packageName) == PackageManager.PERMISSION_GRANTED
        }

    /** Those of [required] that [packageName] does not hold, for naming them in a log line. */
    fun missingFor(context: Context, packageName: String = context.packageName): List<String> =
        required.filter {
            context.packageManager.checkPermission(it, packageName) != PackageManager.PERMISSION_GRANTED
        }

    /**
     * Why an empty `getScanResults()` was empty, in the log line that reports it.
     *
     * The neutral wording it replaces - "came back empty (0 networks), so it says nothing about
     * whether X is in range" - was correct and useless: it is the same sentence for a throttled
     * scan, a phone with location switched off, and a process that will never be told anything
     * for the life of the installation. Separating the third from the first two took a code read
     * and a guess in every investigation it appeared in ("confidence: high, but not proven",
     * support 36a3fd37, 2026-09-01), because nothing in the report or the log states the grant.
     *
     * Ordered by what a reader should act on: a missing grant is permanent and fixable, location
     * services are a phone-wide toggle, and only when neither is the answer is throttling worth
     * mentioning - it is the one cause that goes away on its own.
     */
    fun emptyScanCause(context: Context): String {
        val missing = missingFor(context).map { it.substringAfterLast('.') }
        return when {
            missing.isNotEmpty() ->
                "this app has not been granted ${missing.joinToString()}, so Android hands it " +
                    "an empty list whatever is on the air"
            !WifiDirectGate.isLocationEnabled(context) ->
                "location services are switched off on this phone, and Android empties the Wi-Fi " +
                    "scan for every app while they are"
            else ->
                "the platform has not refreshed the list, or scan throttling is in force"
        }
    }
}
