// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.externaldisplay

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import io.motohub.android.session.ProjectionEventLog

/**
 * The one place that asks Android to stop Autolink before MOTO-HUB opens the USB AOA accessory.
 *
 * Autolink is the head unit's own phone app and it claims the same accessory, so `openAccessory()`
 * fails while Autolink holds it. Both AOA entry points - [AoaExternalService], which mirrors the
 * phone screen, and [AoaAccessorySession], which carries Android Auto - used to carry their own
 * copy of this, and both logged "Requested background stop of com.link.autolink." on success.
 *
 * That line was the bug. `killBackgroundProcesses` has only affected the **caller's own** packages
 * since Android 14, so on a modern phone nothing at all happened and the log said otherwise -
 * which sends whoever reads that log next looking for a fault somewhere else entirely. The same
 * limit is already written down twice in this codebase, in [io.motohub.android.tbox
 * .CompanionAppRegistry] and in `RideDaemonTransport.ensureReversePortsAvailable`.
 *
 * It is NOT dead code, though, and deleting it would quietly drop behaviour that still works:
 * this app's minSdk is 31 and it declares KILL_BACKGROUND_PROCESSES, so on Android 12 and 13 the
 * call does reach another package. What it never does, on any version, is touch a foreground
 * process or a foreground service - so even where it is honoured, an Autolink the rider is looking
 * at survives it. Hence: attempt it where it can work, skip it where it provably cannot, and in
 * both cases say plainly that the accessory may still be held.
 */
internal const val AUTOLINK_PACKAGE = "com.link.autolink"

/**
 * The first Android release on which `killBackgroundProcesses` stopped reaching other packages.
 * Named rather than inlined so the log sentence and the decision cannot drift apart.
 */
internal const val FOREIGN_PROCESS_KILL_LAST_SDK = Build.VERSION_CODES.TIRAMISU

/**
 * What the log should say, given the platform and whether the call threw.
 *
 * Pure so the wording is pinned by a test without an Android runtime - the wording is the whole
 * point of this change, and a sentence that quietly regrows the old false claim would put the bug
 * straight back. [failureMessage] is null when nothing was thrown.
 */
internal fun autolinkStopOutcome(sdkInt: Int, failureMessage: String? = null): String = when {
    failureMessage != null ->
        "Could not ask Android to stop $AUTOLINK_PACKAGE: $failureMessage. If the accessory " +
            "does not open next, Autolink is still holding it and only the rider can close it."

    sdkInt > FOREIGN_PROCESS_KILL_LAST_SDK ->
        "Did not ask Android to stop $AUTOLINK_PACKAGE: from Android 14 that request only ever " +
            "reaches this app's own processes, so it would have done nothing. If the accessory " +
            "does not open next, Autolink is still holding it and only the rider can close it."

    else ->
        "Asked Android to stop $AUTOLINK_PACKAGE so it releases the AOA accessory. This is " +
            "honoured only while Autolink sits in the background - a foreground app or " +
            "foreground service is never killed - so if the accessory does not open next, " +
            "Autolink is still holding it and only the rider can close it."
}

/**
 * Asks for the stop where the platform still allows it, and logs the honest outcome either way.
 *
 * Deliberately returns nothing: there is no answer to return. `killBackgroundProcesses` reports
 * neither whether it killed anything nor whether the accessory came free, so the only real test
 * of success is the `openAccessory()` call that follows.
 */
internal fun requestAutolinkStop(context: Context) {
    if (Build.VERSION.SDK_INT > FOREIGN_PROCESS_KILL_LAST_SDK) {
        ProjectionEventLog.record("AOA_SERVICE", autolinkStopOutcome(Build.VERSION.SDK_INT))
        return
    }
    try {
        context.applicationContext
            .getSystemService(ActivityManager::class.java)
            .killBackgroundProcesses(AUTOLINK_PACKAGE)
        ProjectionEventLog.record("AOA_SERVICE", autolinkStopOutcome(Build.VERSION.SDK_INT))
    } catch (failure: Exception) {
        ProjectionEventLog.warning(
            "AOA_SERVICE",
            autolinkStopOutcome(Build.VERSION.SDK_INT, failure.message ?: failure::class.java.simpleName)
        )
    }
}
