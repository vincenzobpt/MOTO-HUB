// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import android.content.Context
import android.net.ConnectivityManager
import io.motohub.android.data.MotorcycleProfileStore
import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.session.ProjectionEventLog
import io.motohub.android.session.TBoxConnectionMode
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Single decision point for how to reach a T-Box. A profile can explicitly select Auto, AP, or
 * Wi-Fi Direct, which is essential for dashboards that act as P2P Group Owners without exposing
 * a conventional access point.
 */
/**
 * A Wi-Fi Direct group formed and owned by another process, described well enough for this one to
 * use it without looking it up. Only the forming process can read the phone's own address inside
 * the group, so it travels across the bridge instead of being resolved twice.
 */
data class FormedP2pGroup(
    val localIpv4: Inet4Address,
    val groupOwnerIpv4: Inet4Address
)

object TBoxLinkResolver {

    /**
     * @param formedGroup a Wi-Fi Direct group ANOTHER process already formed and still owns,
     *   with the addresses it resolved there. Present only on the companion-app bridge; it makes
     *   this process adopt that group instead of joining one of its own, which it cannot do -
     *   see [TBoxWifiDirectConnector.adoptFormedGroup].
     */
    suspend fun connect(
        context: Context,
        networkConnector: TBoxNetworkConnector,
        profile: MotorcycleProfile,
        formedGroup: FormedP2pGroup? = null
    ): Result<TBoxLink> =
        if (profile.connectionMode == TBoxConnectionMode.BLE_PROVISIONED) {
            bluetoothProvisionedLink(context)
        } else if (profile.connectionMode == TBoxConnectionMode.PHONE_HOTSPOT) {
            hostedLink(context).recoverCatching { hostedFailure ->
                // Nothing is hosted. Before telling the rider to turn a hotspot on - which some
                // dashes give them no credentials for - see whether this is a dash that hands its
                // network over on Bluetooth instead. It only costs a scan, and it creates nothing
                // unless a dash actually answers and asks for a network.
                bluetoothProvisionedLink(context, FALLBACK_SCAN_MILLIS)
                    .recoverCatching {
                        accessPointFallback(context, networkConnector, profile, hostedFailure).getOrThrow()
                    }
                    .getOrThrow()
            }
        } else if (usesWifiDirect(profile)) {
            ProjectionEventLog.record(
                "NETWORK",
                "Connecting to ${profile.ssid} through Wi-Fi Direct (${profile.connectionMode})" +
                    if (formedGroup != null) ", adopting the group the companion app formed." else "."
            )
            if (formedGroup != null) {
                TBoxWifiDirectConnector(context)
                    .adoptFormedGroup(profile, formedGroup.localIpv4, formedGroup.groupOwnerIpv4)
                    .map { it }
            } else {
                TBoxWifiDirectConnector(context).connect(profile).map { it }
            }
        } else {
            ProjectionEventLog.record(
                "NETWORK",
                "Connecting to ${profile.ssid} through the Wi-Fi access-point transport (${profile.connectionMode})."
            )
            networkConnector.connect(profile).map { TBoxLink.Infrastructure(it) }
        }

    /**
     * Recovery variant: reuse a still-alive infrastructure network before reconnecting.
     *
     * @param currentLink the link the session being recovered was using, when there is one. A
     *   Wi-Fi Direct group formed by the companion app must be re-adopted, never rejoined: this
     *   process cannot form or resolve one, so a rejoin here is the "connect() failed: internal
     *   error" storm the watchdog used to produce on every recovery of a handed-over session.
     */
    suspend fun reacquire(
        context: Context,
        networkConnector: TBoxNetworkConnector,
        profile: MotorcycleProfile,
        awaitNetworkMillis: Long,
        currentLink: TBoxLink? = null
    ): TBoxLink {
        // A network the phone hosts is still there after a session-level failure: the hotspot did
        // not go anywhere, and on a Bluetooth-provisioned dash tearing it down and building it
        // again would put the dash through the whole pairing exchange for nothing.
        if (currentLink is TBoxLink.PhoneHotspot) return currentLink
        if (profile.connectionMode == TBoxConnectionMode.BLE_PROVISIONED) {
            return connect(context, networkConnector, profile).getOrThrow()
        }
        if (usesWifiDirect(profile)) {
            val handedOver = (currentLink as? TBoxLink.WifiDirect)?.takeIf { it.formedElsewhere }
            if (handedOver != null) {
                return TBoxWifiDirectConnector(context)
                    .adoptFormedGroup(profile, handedOver.bindIp, handedOver.gatewayIp)
                    .getOrThrow()
            }
            // A P2P group has no ConnectivityManager-visible network to await; rejoin directly.
            return TBoxWifiDirectConnector(context).connect(profile).getOrThrow()
        }
        val network = networkConnector.currentNetwork()
            ?: networkConnector.awaitNetworkAvailable(awaitNetworkMillis)
            ?: networkConnector.connect(profile).getOrThrow()
        return TBoxLink.Infrastructure(network)
    }

    /**
     * Public so a caller can find out which transport a profile will take *before* asking for it.
     * PRO needs this: it has to form the Wi-Fi Direct group in its own process before handing the
     * connect to CORE (Android only grants a P2P join to a caller with a visible activity), while
     * the access-point join still happens inside CORE. The two paths also differ in their
     * permission gate, so PRO has to know which one it is about to trigger.
     */
    fun usesWifiDirect(profile: MotorcycleProfile): Boolean = when (profile.connectionMode) {
        TBoxConnectionMode.WIFI_DIRECT -> true
        // THINKERRIDE inverts the TCP roles, but the Wi-Fi join itself is a plain access-point
        // request — the dash's AP is ordinary WPA2, so it rides the infrastructure path here.
        TBoxConnectionMode.ACCESS_POINT,
        TBoxConnectionMode.PHONE_HOTSPOT,
        TBoxConnectionMode.BLE_PROVISIONED,
        TBoxConnectionMode.THINKERRIDE -> false
        TBoxConnectionMode.AUTO -> TBoxWifiDirectConnector.isWifiDirectSsid(profile.ssid)
    }

    /**
     * There is nothing to connect *to* in hotspot mode - the rider has already turned tethering on
     * by hand, because Android does not let an app create a hotspot with the SSID and password the
     * dash dictates. All this does is find the interface hosting it, so discovery knows which
     * subnet the dash is sitting on.
     *
     * Failing with a rider-readable message matters more here than anywhere else: "no hotspot
     * found" is something they can act on immediately, and it is by far the likeliest mistake.
     */
    /**
     * Puts a dash onto a network the app itself hosts, over Bluetooth.
     *
     * The whole exchange lives in [EcBtpNetLink]; what belongs here is what happens to it
     * afterwards. Both the hotspot and the BLE link have to outlive this call - the network exists
     * only while the reservation is held - so they are handed to the link's release, which the
     * session calls on disconnect. A failure releases them here instead, because a hotspot left
     * running after a failed connect is a radio the rider never asked to have on.
     */
    private suspend fun bluetoothProvisionedLink(
        context: Context,
        scanTimeoutMillis: Long = EcBtpNetLink.SCAN_TIMEOUT_MS
    ): Result<TBoxLink> {
        val hotspot = PhoneHostedHotspot(context) { message ->
            ProjectionEventLog.record("NETWORK", message)
        }
        val link = EcBtpNetLink(context, { message -> ProjectionEventLog.record("PAIRING", message) }, hotspot)
        val provisioned = link.provision(scanTimeoutMillis).getOrElse { failure ->
            link.close()
            hotspot.close()
            return Result.failure(failure)
        }
        ProjectionEventLog.record(
            "NETWORK",
            "${provisioned.dashName} is on the network this phone is hosting " +
                "(${provisioned.subnet.interfaceName} ${provisioned.subnet.localAddress.hostAddress}/" +
                "${provisioned.subnet.prefixLength})" +
                (provisioned.dashIp?.let { ", at ${it.hostAddress}" } ?: ", address not reported") +
                (provisioned.dashModelId?.let { "; modelId=$it" } ?: "") + "."
        )
        return Result.success(
            TBoxLink.PhoneHotspot(
                subnet = provisioned.subnet,
                peerHint = provisioned.dashIp,
                release = {
                    link.close()
                    hotspot.close()
                }
            )
        )
    }

    /**
     * How long the phone-hotspot fallback scans before giving up on Bluetooth.
     *
     * Shorter than a deliberate Bluetooth connect on purpose. Here the rider has most likely just
     * forgotten to turn their hotspot on, and used to be told so instantly; a dash that is on and
     * advertising shows up in the first seconds, so this buys the other kind of rider a chance
     * without making everyone else wait out a full scan for a message they already know.
     */
    private const val FALLBACK_SCAN_MILLIS = 6_000L

    private const val FALLBACK_PREFERENCES = "tbox_access_point_fallback"
    private const val FALLBACK_KEY_PREFIX = "streak_"

    /**
     * Consecutive access-point joins won by the fallback before the saved PHONE_HOTSPOT mode is
     * corrected. Two, not one: a single win can be a dash that happened to be in the middle of a
     * mode change, and a mode the rider chose deserves more than one sample before it is
     * overwritten. Not more than two either - every extra one is another ride spent looking for
     * a hotspot that is never going to be there.
     */
    private const val AP_FALLBACKS_BEFORE_REWRITE = 2

    private fun hostedLink(context: Context): Result<TBoxLink> {
        // Passing what the phone is *using* is not optional, though it was for a long time: the
        // parameter existed and the only production caller left it empty, so the rider's home
        // Wi-Fi (`wlan0`, a private /24 like any hotspot) was a candidate on equal footing with
        // the interface the dash was actually on. See TBoxHotspotScan.PEER_LINK_PREFIXES for the
        // log that showed it, and isHostedName() for why this can never eat a real SoftAP.
        val inUse = addressesTheNetworkStackIsUsing(context)
        val subnets = TBoxHotspotScan.tetheringSubnets(TBoxHotspotScan.snapshotInterfaces(), inUse)
        val subnet = subnets.firstOrNull()
            ?: return Result.failure(
                IllegalStateException(
                    "This motorcycle expects your phone to host the network, but no hotspot is " +
                        "running. Turn on the Android hotspot with the exact Ssid and Password " +
                        "the dash is showing, then connect again."
                )
            )
        // Naming every candidate, not counting them. A mailed-in log that says "2 candidate
        // interfaces" cannot tell us whether the right one was even on the list; one that says
        // "p2p0, wlan2" answers it in a glance.
        ProjectionEventLog.record(
            "NETWORK",
            "Phone-hosted transport: dash expected on ${subnet.localAddress.hostAddress}/" +
                "${subnet.prefixLength} via ${subnet.interfaceName}" +
                if (subnets.size > 1) {
                    " (chosen from ${subnets.joinToString { it.interfaceName }})."
                } else {
                    "."
                }
        )
        // Who is actually on that subnet is the other half of the answer, and the half no rider
        // log has ever carried: a sweep that finds nothing looks identical whether the dash never
        // joined the hotspot or joined it and stayed quiet. Best-effort, and it says so when the
        // platform refuses - see TBoxHotspotScan.neighbours.
        ProjectionEventLog.record("NETWORK", TBoxHotspotScan.describeNeighbours(subnet.interfaceName))
        return Result.success(TBoxLink.PhoneHotspot(subnet))
    }

    /**
     * Takes the access-point road after the phone-hosted one led nowhere, but only on proof that
     * the dash is broadcasting.
     *
     * PHONE_HOTSPOT is a one-way door today, and that is the defect: HubViewModel offers the
     * mode after a single failed join, saves it, and nothing ever offers the way back. A rider
     * whose dash has a perfectly good access point - one transient timeout ago - is then told to
     * turn on a hotspot forever. Field log 2026-08-06 (OnePlus CPH2653, EASYCONN_5G-F3116E): every
     * connect from the saved profile failed instantly with "no hotspot is running" while, in the
     * same log and the same minute, an AUTO-mode profile joined the same dash's AP, resolved it
     * over NSD and reached READY.
     *
     * Deliberately narrow. It runs only when the scan actually SAW the dash: an unknown answer
     * leaves the original hotspot message standing, because "turn your hotspot on" is the right
     * advice for the rider whose dash really is a Wi-Fi client.
     *
     * It used to stop there, on the rule that a mode the rider chose is theirs to keep. That rule
     * cost field log 6662-E47B-06D0 three weeks: a CFMOTO 800MT-X saved as PHONE_HOTSPOT on
     * 2026-08-21 after one timed-out join, still saved that way on 2026-09-04, with every single
     * connect in between taking this fallback and reaching the dash's access point. The advice
     * to change it back was written into a log the rider never reads, and there is no screen that
     * can act on it either - see [io.motohub.android.feature.home.HubViewModel]'s one-way
     * phone-hotspot offer. So the streak below rewrites the mode, and the rule survives where it
     * was actually protecting someone: a hotspot-only dash has no access point to join, so it can
     * never produce even one success here, let alone [AP_FALLBACKS_BEFORE_REWRITE] in a row.
     */
    private suspend fun accessPointFallback(
        context: Context,
        networkConnector: TBoxNetworkConnector,
        profile: MotorcycleProfile,
        hostedFailure: Throwable
    ): Result<TBoxLink> {
        val broadcasting = networkConnector.isDashBroadcasting(profile)
        if (broadcasting != true) {
            // The silence here was a hole. Five identical "no hotspot is running" errors in a
            // rider log (samsung SM-S948B, qj-5G-d8cf, 2026-08-23) said nothing about whether
            // this road had even been considered, let alone which of its two answers had closed
            // it - and those two answers point at opposite problems. The snapshot is the same one
            // the access-point join records before it starts, so a hotspot-mode log finally
            // carries the scan a Wi-Fi-client dash would be found in.
            networkConnector.logVisibleApSnapshot(profile)
            ProjectionEventLog.record(
                "NETWORK",
                "Staying on the phone-hosted transport: the access-point fallback only runs on a " +
                    "positive sighting, and " +
                    if (broadcasting == false) {
                        "${profile.ssid} was not in the phone's latest scan."
                    } else {
                        "this phone handed back no usable scan at all."
                    }
            )
            return Result.failure(hostedFailure)
        }
        ProjectionEventLog.record(
            "NETWORK",
            "No hosted network, but ${profile.ssid} is broadcasting - joining its access point " +
                "instead. This motorcycle is saved as \"My phone hosts the hotspot\"; if the " +
                "access point keeps working, change the mode in manual pairing to skip this step."
        )
        return networkConnector.connect(profile)
            .onFailure { forgetAccessPointFallback(context, profile) }
            .onSuccess { noteAccessPointFallback(context, profile) }
            .map { TBoxLink.Infrastructure(it) }
    }

    /**
     * Counts the access-point joins this fallback has won in a row for one motorcycle and, once
     * they amount to proof rather than a coincidence, moves the saved profile off PHONE_HOTSPOT.
     *
     * [TBoxConnectionMode.AUTO], not ACCESS_POINT: it is what scanning the dash's own QR would
     * have written, and it keeps the DIRECT- detection a bare ACCESS_POINT would throw away.
     *
     * Saved with `makeActive = false`, because being reached over the bridge does not make a
     * motorcycle the one the rider is looking at. The rewrite lands in storage, so it takes
     * effect on the next load rather than in this session's in-memory profile - the mode is only
     * read when a connect starts, and this connect has already made its choice.
     */
    private fun noteAccessPointFallback(context: Context, profile: MotorcycleProfile) {
        val preferences = context.applicationContext
            .getSharedPreferences(FALLBACK_PREFERENCES, Context.MODE_PRIVATE)
        val key = FALLBACK_KEY_PREFIX + profile.id
        val streak = preferences.getInt(key, 0) + 1
        if (streak < AP_FALLBACKS_BEFORE_REWRITE) {
            preferences.edit().putInt(key, streak).apply()
            ProjectionEventLog.record(
                "NETWORK",
                "${profile.ssid} answered on its access point while saved as \"My phone hosts " +
                    "the hotspot\" ($streak of $AP_FALLBACKS_BEFORE_REWRITE in a row). One more " +
                    "and MOTO-HUB will correct the saved mode by itself."
            )
            return
        }
        val corrected = profile.copy(connectionMode = TBoxConnectionMode.AUTO)
        val failure = MotorcycleProfileStore(context)
            .save(corrected, makeActive = false)
            .exceptionOrNull()
        if (failure != null) {
            // Left counting: a storage failure is not a reason to stop believing the evidence,
            // and the next successful join tries the same correction again.
            ProjectionEventLog.warning(
                "NETWORK",
                "Could not correct the saved connection mode for ${profile.ssid}: ${failure.message}"
            )
            return
        }
        preferences.edit().remove(key).apply()
        ProjectionEventLog.record(
            "NETWORK",
            "${profile.ssid} has now been reached on its own access point " +
                "$AP_FALLBACKS_BEFORE_REWRITE times in a row while saved as \"My phone hosts " +
                "the hotspot\". Correcting the saved mode to Auto, so future connections stop " +
                "looking for a hotspot first."
        )
    }

    /** One failed access-point join ends the streak: only consecutive wins are evidence. */
    private fun forgetAccessPointFallback(context: Context, profile: MotorcycleProfile) {
        context.applicationContext
            .getSharedPreferences(FALLBACK_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .remove(FALLBACK_KEY_PREFIX + profile.id)
            .apply()
    }

    /** Moved to [TBoxHotspotScan.addressesInUse], which the Bluetooth setup path needs as well. */
    private fun addressesTheNetworkStackIsUsing(context: Context): Set<InetAddress> =
        TBoxHotspotScan.addressesInUse(context)
}
