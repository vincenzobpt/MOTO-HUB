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
    val displayMode: String = ""
) : Parcelable
