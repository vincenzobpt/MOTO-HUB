// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.androidauto

/**
 * Carries "who asked for this stop" from the caller of
 * [AndroidAutoSessionService.stop] to the service's own onDestroy().
 *
 * `stopService()` carries no reason: Android simply calls onDestroy(), which fell back to
 * [STOPPED_BY_ANDROID] and so recorded *every* deliberate stop - the app's own UI, the launch
 * timeout, a companion app over the AIDL bridge - as if the system had killed the session. A
 * rider's diagnostic report then read as OEM process management on a phone that had done nothing:
 * support case 6A55-7ACB showed four sessions torn down in 10-45s, all blamed on Android, all
 * asked for over AIDL.
 *
 * Both sides live in the same process (a service of this same app), so a single slot carries it.
 * The two rules that keep it honest are what this object exists to make testable:
 *  - a reason is consumed exactly once, so a later destroy nobody asked for still reads as
 *    Android's doing;
 *  - a stop that never reached a running service is dropped when the next session starts, so it
 *    cannot mislabel a session it was never about.
 */
internal object AndroidAutoStopReason {
    /** What onDestroy() records when nobody inside the app asked for the stop. */
    const val STOPPED_BY_ANDROID = "Android Auto service stopped by Android."

    @Volatile
    private var pending: String? = null

    /** Called just before the service is torn down, by whoever is asking for the stop. */
    fun publish(reason: String) {
        pending = reason
    }

    /** The pending reason, or null when the stop came from outside the app. Consumes it. */
    fun take(): String? = pending.also { pending = null }

    /** Drops a reason left behind by a stop that never reached a running service. */
    fun clear() {
        pending = null
    }
}
