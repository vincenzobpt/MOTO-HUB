// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.ipc

/** Int constants used by IAndroidAutoStateListener.onStateChanged — shared vocabulary
 *  for both sides of the Binder call, since AIDL has no sealed-class equivalent. */
object AndroidAutoIpcState {
    const val IDLE = 0
    const val PREPARING = 1
    const val RECEIVER_READY = 2
    const val STREAMING = 3
    const val STOPPED = 4
    const val FAILED = 5
}

/** Also used for io.motohub.android.ipc.IpcBridgeService's own internal AA session bookkeeping. */
object IpcBridgeContract {
    /**
     * Two distinct actions, not one action + an extra: Android caches the IBinder returned by
     * onBind() per Intent.filterEquals(), which ignores extras. A single shared action would
     * make the second bind (regardless of its extra) silently receive the first caller's binder.
     */
    const val BIND_ACTION_TBOX_TRANSPORT = "io.motohub.android.ipc.BIND_TBOX_TRANSPORT"
    const val BIND_ACTION_ANDROID_AUTO_RECEIVER = "io.motohub.android.ipc.BIND_ANDROID_AUTO_RECEIVER"

    /** Signature-level permission a caller must hold to bind IpcBridgeService. */
    const val BIND_PERMISSION = "io.motohub.android.permission.BIND_CORE_SERVICE"

    /**
     * Revision of ITBoxTransportService this build implements, answered by getContractVersion().
     * Bump it whenever a call is appended, and gate the caller on the constant naming the call
     * it needs - a Core that predates getContractVersion() itself answers 0.
     *
     * 1: everything up to clearDiagnosticLog(), which had no version call.
     * 2: connectOverFormedGroup().
     * 3: getLastConnectFailure() + getLastConnectFailureStage().
     * 4: videoWantsStills(), and the extended video-pipe frame in [VideoPipeFraming].
     * 5: getActiveProfileKey().
     * 6: getLastVideoSessionFailure().
     * 7: getWireLadderProgress().
     * 8: getCapabilitiesJson().
     * 9: scanTBoxPorts().
     * 10: getWireLadderProgress() is keyed by the motorcycle's network name, not its profile id.
     *     No call appended - the argument's meaning changed, which needs the same gate.
     * 11: getScreenMargins().
     * 12: getDashboardDeliveryReport().
     * 13: holdsHandlebarBluetoothPermission(), the handlebar gesture listener on
     *     IAndroidAutoReceiverService, and the capture-only field in [AndroidAutoSettingsParcel].
     *     One bump for three calls: they are the same fault seen from three sides - the handlebar
     *     of an Android Auto session is decoded in Core, and everything that configures, permits
     *     or teaches it lives in the companion app.
     * 14: the auto-recovery field in [AndroidAutoSettingsParcel]. No call appended - the parcel
     *     grew, and a caller has to be able to ask whether Core reads the new field before it can
     *     tell "recovery is off" from "this Core never heard the question".
     * 15: getHandlebarState(). The other half of 13: that one says whether a press can reach Core,
     *     this one says what Core would do with it.
     */
    const val CONTRACT_VERSION = 16

    /** First [CONTRACT_VERSION] whose Core implements connectOverFormedGroup(). */
    const val CONTRACT_VERSION_FORMED_GROUP = 2

    /** First [CONTRACT_VERSION] whose Core can say why a connect failed, and at which stage. */
    const val CONTRACT_VERSION_CONNECT_FAILURE_REASON = 3

    /**
     * First [CONTRACT_VERSION] whose Core answers videoWantsStills() and understands a still on
     * the video pipe. A companion must check this before writing one: an older Core reads the
     * extended frame's negative length as a corrupt access unit and closes the pipe.
     */
    const val CONTRACT_VERSION_VIDEO_STILLS = 4

    /**
     * First [CONTRACT_VERSION] whose Core names the profile its transport actually settled on,
     * through getActiveProfileKey().
     *
     * Until this, a companion app driving a Core-owned session could only resolve the profile
     * from its own saved motorcycle, and a dash that answered Yunmo after EasyConn discovery
     * found nothing resolves there to the generic one. So the companion's Ride Dashboard encoded
     * a Moto Morini X-Cape 1200 at the generic 30fps while Core's own Android Auto path, holding
     * the real profile, encoded the same dash at the 10fps that profile asks for - three times
     * the frames into a three-frame send window (rider 315e0af3, 2026-08-24, both editions
     * 1.1.91: the dashboard reappeared and died every ten seconds while Android Auto worked).
     */
    const val CONTRACT_VERSION_ACTIVE_PROFILE = 5

    /**
     * First [CONTRACT_VERSION] whose Core says why startVideoSession() returned null, through
     * getLastVideoSessionFailure().
     *
     * Until this, the companion app answered every video-negotiation failure with one hard-coded
     * sentence about the phone-connection screen and other apps holding the T-Box - EasyConn
     * help, on a boundary that also carries ThinkerRide. A KOVE dash whose firmware waits for the
     * rider to long-press UP was therefore reported as a dash refusing the stream, and two riders
     * chased that instead of the gesture (32e132d0, 1013eadf).
     */
    const val CONTRACT_VERSION_VIDEO_FAILURE_REASON = 6

    /**
     * First [CONTRACT_VERSION] whose Core answers getWireLadderProgress(), so a diagnostics report
     * can state which wire an unidentified dashboard is actually being given.
     *
     * The ladder is walked in Core and persisted in Core's own preferences. The companion app read
     * the preferences of ITS process, which nothing there ever writes, and every report from every
     * rider therefore said "rung 0, TRYING, no dashboard fingerprint" - a sentence about the
     * companion app's empty store, printed where the reader expects a fact about the motorcycle.
     */
    const val CONTRACT_VERSION_WIRE_LADDER = 7

    /**
     * First [CONTRACT_VERSION] whose Core hands over a dashboard's CLIENT_INFO capabilities,
     * through getCapabilitiesJson().
     *
     * The same shape of bug as [CONTRACT_VERSION_WIRE_LADDER], in the store next door. CLIENT_INFO
     * is read on the EasyConn command socket, which lives in Core; the companion app's own
     * capability store is written by nobody when Core owns the link, which is every installation
     * that has Core. So the companion resolved the GENERIC profile for every dash ever connected,
     * and its diagnostics report said `capabilities: null` for every rider - 68 motorcycle rows
     * out of 68 in the collector on 2026-08-25, with no exception in the whole history.
     */
    const val CONTRACT_VERSION_ACTIVE_CAPABILITIES = 8

    /**
     * First [CONTRACT_VERSION] whose Core runs the T-Box port scan on the companion's behalf,
     * through scanTBoxPorts().
     *
     * The scanner needs a socket on the dash's network, and when Core owns the link the companion
     * has none: its own TBoxNetworkConnector never joined anything, so the diagnostic's
     * "is this the same motorcycle?" test could not answer yes and the scan was refused with
     * "MOTO-HUB is connected to a different motorcycle right now" - to a rider with one
     * motorcycle, connected to it (field log 7efdfa33, 2026-08-25). Asking Core, which does hold
     * the link, is the only way the inspector can run at the moment a rider would want it: while
     * connected to the dash that is misbehaving.
     *
     * A companion still scans locally when nothing is connected; that path never needed Core.
     */
    const val CONTRACT_VERSION_PORT_SCAN = 9

    /**
     * First [CONTRACT_VERSION] whose Core files the wire ladder under the motorcycle's network
     * name instead of its profile id, and therefore expects getWireLadderProgress() to be asked
     * that way.
     *
     * A profile id is a UUID minted per garage entry, and MOTO-HUB has two garages: Core's and
     * the companion app's. A companion-driven session hands Core the COMPANION's id, a Core-only
     * session uses Core's own, and re-scanning the dash QR code mints a third - so one physical
     * dashboard accumulated ladder records that never saw each other's verdict. Rider 87bc5a7c
     * answered "yes, I can see it" for rung 0 in Core at 18:27 on 2026-08-25 and was asked the
     * same question again at 21:41 on the same dash, because the second session arrived over the
     * bridge under a freshly scanned id. The network name is what both halves agree on, and it is
     * already how the screen-margins store next door is keyed.
     *
     * Cross-version pairs degrade to "no ladder in the report", never to another bike's record:
     * an older Core asked by SSID has nothing under that key, and an older companion asked by id
     * gets the record Core copied rather than moved when it migrated.
     */
    const val CONTRACT_VERSION_WIRE_LADDER_BY_SSID = 10

    /**
     * First [CONTRACT_VERSION] whose Core hands back the screen margins it holds for a motorcycle,
     * through getScreenMargins(), so the companion app can adopt a calibration made in Core.
     *
     * The teaching already travelled the other way in [AndroidAutoSettingsParcel], gated on this
     * app actually having one - four zeros for a bike nobody calibrated here would erase a
     * calibration made over there. What that gate left open is the commoner case: Core ships the
     * same ruler and owns Android Auto, so Core is usually where a rider measures, and nothing
     * carried it back. One dash then got two framings from one pair - Android Auto composited
     * into a 680x408 viewport of an 800x480 panel while the Ride Dashboard used all 800x480,
     * minutes apart in the same session (riders 7efdfa33 and 87bc5a7c, 2026-08-25).
     *
     * Adoption only ever FILLS A GAP: a value taught in this app still wins, so the two do not
     * ping-pong and the rider's most recent teaching is the one that survives.
     */
    const val CONTRACT_VERSION_SCREEN_MARGINS = 11

    /**
     * First [CONTRACT_VERSION] whose Core reports a session that connected and is not reaching
     * the dashboard, through getDashboardDeliveryReport().
     *
     * The companion app cannot see this on its own: the video pipe is one-way, so its
     * offerAccessUnit() answers whether the write into the pipe succeeded, never whether the
     * dashboard took the frame. Both facts only meet in Core.
     *
     * Rider 315e0af3 is why it is worth a call of its own. Two days on a Moto Morini X-Cape 1200
     * with the generic profile: link healthy, session READY, dashboard taking roughly one frame
     * in eight, app reporting nothing wrong. He found the profile override in the Garage by
     * accident, pinned the X-Cape entry, and the same ride went from 132 refusals in five seconds
     * to 9 in fourteen. The app had every number needed to suggest that, in Core, and no way to
     * say it to the half with the screen.
     *
     * Absent on an older Core, which is what its dead transaction reads as - the companion then
     * behaves exactly as before, offering nothing.
     */
    const val CONTRACT_VERSION_DELIVERY_REPORT = 12

    /**
     * First [CONTRACT_VERSION] whose Core answers holdsHandlebarBluetoothPermission(), accepts a
     * handlebar gesture listener, and honours the capture-only field of
     * [AndroidAutoSettingsParcel].
     *
     * Below this the companion app must treat Core's Bluetooth grant as UNKNOWN rather than
     * missing - the dead transaction's empty reply parcel reads as false, and telling a rider to
     * grant a permission they already granted is worse than saying nothing.
     *
     * All three exist because an Android Auto session's handlebar is decoded in Core while every
     * screen that configures it is in the companion app, and nothing carried the three things
     * that have to cross for it to work: the PERMISSION is per-package and only Core's counts;
     * the taught GESTURES are published into a process-local feed that only Core's process sees;
     * and the wizard's promise that a press will be observed and not obeyed was set on the
     * companion's copy of that feed, leaving Core free to act on every press the rider was asked
     * to make. Rider 315e0af3, 2026-08-24 to 08-26.
     */
    const val CONTRACT_VERSION_CORE_BLUETOOTH = 13

    /**
     * First [CONTRACT_VERSION] whose Core honours the auto-recovery field of
     * [AndroidAutoSettingsParcel] instead of deciding from its own copy of the switch.
     *
     * The same shape as the Bluetooth clock and the handlebar block above: the setting is offered
     * in the companion app, the thing it governs runs in Core. What makes this one worse is that
     * both halves ship the switch ON, so nothing looks wrong until a rider turns it off in one
     * app and expects the other to agree - or, as in field log 8d5a1631, until a session ends
     * mid-ride and only the half without a screen knows why nothing came back.
     *
     * Below this the companion cannot make Core recover, and should not claim otherwise in its
     * own settings screen.
     */
    const val CONTRACT_VERSION_AUTO_RECOVERY = 14

    /**
     * First [CONTRACT_VERSION] whose Core answers getHandlebarState(), so a diagnostics report can
     * state how the half that decodes the presses is actually configured.
     *
     * [CONTRACT_VERSION_CORE_BLUETOOTH] closed the question of whether a press can arrive; this
     * closes what happens to one that does, and until it existed a report answered that question
     * with the companion app's own settings. Support 0df154af (2026-08-27) shows the gap from the
     * outside: the rider switched input protocol three times mid-session and ran the wizard, and
     * neither his log nor his report could say what the process reading his presses believed.
     *
     * Below this the companion must report Core's handlebar as unknown - the dead transaction's
     * empty reply parcel is indistinguishable from a Core that answered "nothing configured".
     */
    const val CONTRACT_VERSION_HANDLEBAR_STATE = 15
    const val CONTRACT_VERSION_PROJECTION_AUDIO = 16

    /**
     * Which half of Core's connect produced the last failure, answered by
     * getLastConnectFailureStage(). The distinction is what keeps help for a busy EasyConn session
     * off a failure that never reached one: below [CONNECT_STAGE_DISCOVERY] no session existed for
     * another app to be holding, so nothing about another app can be the explanation.
     */
    const val CONNECT_STAGE_UNKNOWN = 0

    /** The phone never got a usable link to the dash: Wi-Fi join, P2P group, or routing. */
    const val CONNECT_STAGE_NETWORK = 1

    /** The link was up and the dash did not answer as an EasyConn (or other family) host. */
    const val CONNECT_STAGE_DISCOVERY = 2

    /** Core refused before trying: it is already driving this dash for someone else. */
    const val CONNECT_STAGE_REFUSED = 3

    const val CORE_PACKAGE_NAME = "io.motohub.android"
    const val CORE_MAIN_ACTIVITY_CLASS_NAME = "io.motohub.android.MainActivity"

    /** Core-side deep-link used by the full Android Auto preview controls. */
    const val EXTRA_OPEN_ANDROID_AUTO_PREVIEW = "io.motohub.android.extra.OPEN_ANDROID_AUTO_PREVIEW"
    const val EXTRA_OPEN_ANDROID_AUTO_PREVIEW_FULLSCREEN =
        "io.motohub.android.extra.OPEN_ANDROID_AUTO_PREVIEW_FULLSCREEN"

    /** Legacy Core deep-links retained only for settings migration compatibility. */
    const val EXTRA_OPEN_RIDE_DASHBOARD_CONTROLS = "io.motohub.android.extra.OPEN_RIDE_DASHBOARD_CONTROLS"
    const val EXTRA_OPEN_RIDE_DASHBOARD_CUSTOMIZE = "io.motohub.android.extra.OPEN_RIDE_DASHBOARD_CUSTOMIZE"

    /**
     * Same idea as [EXTRA_OPEN_ANDROID_AUTO_PREVIEW], but for a session with no T-Box at all:
     * Advanced asks Core to both START a phone-only Android Auto session (Core builds/runs the AA
     * receiver itself, bound only to the phone's own preview Surface) AND open the screen that
     * shows it, in one launch.
     */
    const val EXTRA_START_PHONE_ONLY_ANDROID_AUTO = "io.motohub.android.extra.START_PHONE_ONLY_ANDROID_AUTO"

    /**
     * Advanced's own Android Auto Display default (see the Garage's default-settings panel),
     * as an [io.motohub.android.androidauto.AndroidAutoDisplayMode] name string. Core is the
     * only process that ever runs the phone-only receiver, so Advanced's own copy of this
     * setting has no effect unless it rides along on this extra — Core applies it to its own
     * store before starting the session. Optional: absent means "use whatever Core already has."
     */
    const val EXTRA_ANDROID_AUTO_DISPLAY_MODE = "io.motohub.android.extra.ANDROID_AUTO_DISPLAY_MODE"

    /**
     * Asks Core to put ITS OWN BLUETOOTH_CONNECT request in front of the rider, then close.
     *
     * A runtime permission can only be requested by the package that wants it, and the package
     * that wants this one is Core - it decodes the handlebar of every Android Auto session. The
     * companion app can see the gap (holdsHandlebarBluetoothPermission) and explain it, but it
     * cannot answer it, and sending a rider to hunt through system settings for the second of two
     * apps with almost the same name is how [handlebarNeedsBluetoothPermission]'s own field
     * report started.
     *
     * Deliberately Core's existing launcher activity rather than a new exported one: the pair
     * already deep-links this way (see the preview extras above), and an activity whose whole
     * purpose is to raise a permission dialog is a component worth not adding. Core finishes as
     * soon as the rider answers, so they land back where they tapped.
     */
    const val EXTRA_REQUEST_HANDLEBAR_BLUETOOTH = "io.motohub.android.extra.REQUEST_HANDLEBAR_BLUETOOTH"
}
