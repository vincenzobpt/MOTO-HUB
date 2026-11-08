// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.session

import io.motohub.android.i18n.motoHubText
import java.util.UUID

/** Explicit transport selected for the motorcycle T-Box Wi-Fi network. */
enum class TBoxConnectionMode {
    /** Infer P2P from a DIRECT- SSID; otherwise request the dashboard access point. */
    AUTO,
    /** Always use Android's regular Wi-Fi access-point request. */
    ACCESS_POINT,
    /** Always join through Wi-Fi Direct (P2P), including dashes acting as Group Owners. */
    WIFI_DIRECT,

    /**
     * The dash is the Wi-Fi *client*: it joins a hotspot the phone hosts, under an SSID and
     * password the dash prints on its own pairing screen ("Please open Android hotspot and set the
     * following parameters" - confirmed on a tester's dash 2026-08-02, which advertises no access
     * point of its own at all).
     *
     * There is nothing for MOTO-HUB to join here, and Android does not let a third-party app
     * create a hotspot with dictated credentials, so the rider turns tethering on by hand. This
     * mode only tells the transport to stop looking for a dash AP and start looking for a dash
     * *on the phone's own tethering subnet*.
     */
    PHONE_HOTSPOT,

    /**
     * The dash owns no network and will not tell the rider one either: it is put onto a network
     * the phone hosts, over a Bluetooth exchange, and only then can be reached over Wi-Fi.
     *
     * Confirmed on a Zontes S350 (Brazil/JTZ, 2026): no access point, no Wi-Fi Direct peer, no
     * credentials printed anywhere, and an opaque `CARBIT` + 12 hex QR. Its dash advertises the
     * EasyConn setup service over BLE and waits to be told which network to join - which is
     * exactly what the official app does, and what [io.motohub.android.tbox.EcBtpNetLink] does
     * here. Unlike [PHONE_HOTSPOT] the rider configures nothing: the hotspot is created by the
     * app, with credentials the app reads back and hands to the dash.
     */
    BLE_PROVISIONED,

    /**
     * ThinkerRide dashboards (KOVE family): the phone joins the dash's ordinary access point,
     * but everything after that is inverted — pairing runs over a BLE GATT handshake and the
     * dash then connects to TCP servers the phone opens. Selected automatically when the
     * ThinkerRide pairing QR (`g.thinkerride.com`) is scanned; also selectable by hand for a
     * rebadged unit whose QR points at some OEM host.
     */
    THINKERRIDE,

    /**
     * Bosch mySPIN dashes (Ducati Multistrada V4 "Ducati Connect", Suzuki, Kawasaki, BMW). The dash,
     * already paired with the phone in Android's Bluetooth settings, connects to the phone over
     * Bluetooth and hands over its Wi-Fi; nothing is typed but the Bluetooth name.
     * [MotorcycleProfile.ssid] holds that name (or the dash's Bluetooth address), the way
     * [BLE_PROVISIONED] keeps its Bluetooth name there.
     */
    MYSPIN
}

enum class SessionPhase {
    SETUP_REQUIRED,
    CONNECTING_NETWORK,
    DISCOVERING_TBOX,
    READY,
    NETWORK_SETUP_REQUIRED,
    REQUESTING_PROJECTION,
    CAPTURING,
    ERROR
}

data class MotorcycleProfile(
    val ssid: String,
    val password: String,
    val id: String = UUID.randomUUID().toString(),
    val modelId: String? = null,
    val displayName: String? = null,
    val photoPath: String? = null,
    val fuelTankRangeKm: Double? = null,
    val profileOverrideKey: String? = null,
    val connectionMode: TBoxConnectionMode = TBoxConnectionMode.AUTO
)

data class HubSessionState(
    val phase: SessionPhase = SessionPhase.SETUP_REQUIRED,
    val motorcycle: MotorcycleProfile? = null,
    val message: String = motoHubText("Set up the motorcycle network to get started."),
    /**
     * The last failure was Android never joining the dash's access point, on a profile that was
     * not already [TBoxConnectionMode.PHONE_HOTSPOT]. That is the exact shape a Wi-Fi-client dash
     * produces - there is no access point to join, so the join can only time out - and whether a
     * given dash is one of those cannot be inferred from its brand, its SSID, or its modelId
     * (the modelId only arrives after a link is up, which never happens here). So the app cannot
     * decide for the rider; it can only make trying the other mode cost one tap instead of a
     * hunt through the menus.
     *
     * Carried as state rather than matched out of [message] because that text is assembled from
     * the underlying exception and is translated.
     */
    val offerPhoneHotspotRetry: Boolean = false,

    /**
     * The last failure is one another EasyConn app could plausibly have caused - i.e. the link to
     * the dash was up and the session handshake is what failed. Only then is it fair to point the
     * rider at their motorcycle's own companion app, which cannot be force-stopped by this
     * app and so costs them a trip through Settings to find out.
     *
     * The banner used to offer that help for *every* failure on a phone with a companion app
     * installed, including failures that never got as far as a network. One rider spent a morning
     * on it - force-stopping it, then uninstalling it - for a connection Android was refusing to
     * route at all (2026-08-15). Advice that cannot possibly apply is not neutral; it is the
     * thing being investigated instead of the real cause.
     *
     * Same reasoning as [offerPhoneHotspotRetry] for carrying it as state: the stage a failure
     * came from is known where it is raised and unrecoverable from the translated text.
     */
    val offerOfficialAppHelp: Boolean = false,

    /**
     * The session is up and the dashboard is refusing most of what it is sent - see
     * [DashboardDeliveryMonitor]. Not a failure and never phrased as one: the phase stays
     * whatever it was, because everything the app can check really is working.
     *
     * This exists because the failure it describes had no representation at all. Rider 315e0af3
     * spent two days on a Moto Morini X-Cape 1200 that connected, reported READY, and painted a
     * frozen picture; the app's own log had the numbers the whole time. He found the profile
     * override in the Garage by accident, pinned the right entry, and was riding in ninety
     * seconds - so the fix was always one tap away behind a screen nothing pointed him at.
     */
    val deliveryWarning: DashboardDeliveryReport? = null
)

fun HubSessionState.withMotorcycle(profile: MotorcycleProfile): HubSessionState = copy(
    phase = SessionPhase.NETWORK_SETUP_REQUIRED,
    motorcycle = profile,
    message = motoHubText("Profile saved. Connect to the T-Box network and start discovery.")
)
