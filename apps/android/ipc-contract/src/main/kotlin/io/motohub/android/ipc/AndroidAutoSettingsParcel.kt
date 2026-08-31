// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.ipc

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * A snapshot of the caller's Android-Auto-affecting settings, sent to CORE right before
 * startFullSession so the session CORE runs honors the settings the user configured in the
 * companion app (whose SharedPreferences CORE cannot read directly). Enums travel as their
 * `.name`; CORE parses them defensively and falls back to its own value on any mismatch.
 */
@Parcelize
data class AndroidAutoSettingsParcel(
    val resolutionMode: String,
    val aspectMatching: String,
    val videoQuality: String,
    val disableTouchscreen: Boolean,
    val seamlessResume: Boolean,
    val nightMode: Boolean,
    /** Per-motorcycle AndroidAutoDisplayMode.name (Garage setting) — Core stores this keyed by
     *  motorcycle id/ssid, separately from the global settings above. Empty when unknown. */
    val displayMode: String = "",
    /**
     * Handlebar sync. Appended at the parcel's end so an OLD caller's parcel deserializes these
     * as false/empty/zero on a NEW Core: [handlebarSyncProvided] then gates the whole block, so
     * Core keeps its own handlebar configuration for callers that predate the sync. A NEW
     * caller against an OLD Core is harmless (extra trailing fields are never read).
     */
    val handlebarSyncProvided: Boolean = false,
    val handlebarControlsEnabled: Boolean = false,
    /** "gestureId=actionId" pairs joined by ',' (HandlebarGesture/HandlebarAction ids). */
    val handlebarMapping: String = "",
    val handlebarDoubleTapMillis: Long = 0L,
    val handlebarSelectHoldMillis: Long = 0L,
    /**
     * Appended after the first handlebar block (same trailing-field compatibility rules).
     * Gated by [handlebarSyncProvided] like the rest; an old caller's parcel deserializes
     * these as true/true/"" — the shipped defaults — so Core behaves as if unconfigured.
     */
    val handlebarEagerSingles: Boolean = true,
    val handlebarHoldsEnabled: Boolean = true,
    /** "pressId=storedValue" pairs joined by ',' (PhysicalPress ids; the value is a
     *  HandlebarGesture id, the `__missing__` marker, or "" for an unbound press). */
    val handlebarCalibration: String = "",
    /**
     * The Bluetooth dash-clock channel, which runs in CORE because that is where the T-Box
     * transport lives - so a rider who flips it in the companion app is configuring a process that
     * never reads it. Mirrored here for the same reason the handlebar block above is.
     *
     * [bluetoothClockSyncProvided] is not ceremony: CORE ships this toggle in its own settings
     * too, so a caller that predates these fields would deserialize `false` and silently switch
     * off a rider who had enabled it in CORE directly. The gate keeps an old companion from
     * overwriting a choice it does not know about.
     */
    val bluetoothClockSyncProvided: Boolean = false,
    val bluetoothClockSync: Boolean = false,
    /**
     * Which protocol the handlebar remote speaks (HandlebarInputMode.id: "avrcp" or "hid"),
     * carried for the same reason as the Bluetooth clock above rather than inside the handlebar
     * block: CORE ships this picker in its own settings too, so a companion that predates the
     * field must not silently reset a rider who selected HID there. Only the choice travels -
     * the Accessibility Service each edition needs for HID is granted per app, in system
     * settings, and cannot be handed over.
     */
    val handlebarInputModeProvided: Boolean = false,
    val handlebarInputMode: String = "",
    /**
     * The TFT pixels the dash's own furniture covers, taught with the companion's calibration
     * ruler. CORE composites Android Auto against ITS copy of these margins and the companion
     * composites the Ride Dashboard against its own, so until this travelled the same bike was
     * projected two different ways by the two halves: field log 7efdfa33 (2026-08-25) shows CORE
     * insetting Android Auto to a 680x408 viewport for a right margin of 120 while the Ride
     * Dashboard, one process away, filled all 800x480 of the same panel.
     *
     * [screenMarginsProvided] is not ceremony, and it is deliberately stricter than the other
     * gates here: it is true only when the rider has actually SAVED margins in the companion.
     * CORE ships the same ruler, so a companion whose store is empty must not push four zeros
     * over a calibration the rider did in CORE - "I never taught this" and "I taught it to be
     * zero" are different answers, and only the store can tell them apart.
     */
    val screenMarginsProvided: Boolean = false,
    val screenMarginTop: Int = 0,
    val screenMarginBottom: Int = 0,
    val screenMarginLeft: Int = 0,
    val screenMarginRight: Int = 0,
    /**
     * True while the rider is being TAUGHT the handlebar: gestures are to be observed and not
     * obeyed, so the motorcycle does not jump around under a rider performing the presses the
     * wizard asked for.
     *
     * The promise is already made in the companion app, on its own copy of HandlebarGestureFeed -
     * which is the wrong copy for an Android Auto session, whose bridge is CORE's. So the wizard
     * said "press your up button" while CORE, hearing the same press, switched the panel under
     * it. Carried on the settings parcel rather than as a call of its own because it IS a setting
     * of the handlebar and travels the same path as the rest of the block.
     *
     * [handlebarCaptureOnlyProvided] gates it for the same reason every gate here exists: an old
     * companion deserializes false, and false is also the value that means "obey them" - without
     * the flag a CORE could not tell a companion that never taught anything from one that just
     * finished. The gate makes the absent case leave CORE's own state alone.
     */
    val handlebarCaptureOnlyProvided: Boolean = false,
    val handlebarCaptureOnly: Boolean = false,
    /**
     * Whether this caller's "reconnect automatically" switch is on, and therefore whether an
     * Android Auto session Core runs on its behalf should re-establish itself when the dash drops
     * it mid-ride instead of ending.
     *
     * Core decides that from ITS copy of the switch, and a rider driving the companion app has
     * never seen that copy: they set the switch here, the session runs over there, and the two
     * disagree in silence. Field log 8d5a1631 (2026-08-26, a Voge dash over Wi-Fi Direct) is what
     * that costs - the dash ended a healthy twenty-minute session with the link still up, Core's
     * own switch was off, nothing retried, and the rider had their dashboard back thirty-six
     * minutes later by relaunching the app by hand.
     *
     * [autoRecoveryProvided] gates it for the reason every gate here exists, and here the reason
     * bites harder than most: Core ships this switch in its own settings too, and a caller that
     * predates the field deserializes `false` - which is also the value that means "do not
     * recover". Without the flag an old companion would quietly switch recovery off for a rider
     * who had turned it on in Core, and the symptom would be this very bug.
     */
    val autoRecoveryProvided: Boolean = false,
    val autoRecovery: Boolean = true,
    /**
     * AndroidAutoDensityMode.name - how big Android Auto is asked to draw itself, which CORE
     * puts in the AAP video config's `density` field when it opens the session.
     *
     * [androidAutoDensityProvided] gates it for the reason every gate here does, with the usual
     * asymmetry: an old caller deserializes "", and CORE ships this picker in its own settings
     * too, so an ungated empty string would reset a rider who set the density over there.
     */
    val androidAutoDensityProvided: Boolean = false,
    val androidAutoDensity: String = ""
) : Parcelable
