// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.session

import android.content.Context
import android.content.Intent

/**
 * Whether MOTO-HUB should keep looking for the motorcycle with nobody watching the screen.
 *
 * Pure so it can be tested, and so the reasons live in one place instead of being spread over
 * three lifecycle callbacks. Every clause is a "no" the rider or the session has already given:
 *
 * - [autoConnectEnabled] is the rider's own setting. Off means off, including in the pocket.
 * - [hasSavedMotorcycle]: there is nothing to look for otherwise.
 * - [riderCancelled] is the clearest "no" the UI has - see [autoConnectDecision], which reads the
 *   same flag. A watch that outlived a cancel would be the cancel bug again, only invisible.
 *
 * The phases that qualify are every phase in which a link is wanted and none exists:
 * NETWORK_SETUP_REQUIRED (where a session that has not connected sits), ERROR (where a 30s
 * timeout leaves it), and the two in-flight phases. The in-flight ones are deliberate and are
 * the case that matters most: rider 36a3fd37 left the app FIVE SECONDS into an attempt, while
 * the phase was DISCOVERING_TBOX, and the attempt only failed half a minute later with the app
 * already in the background. [BikeWatch.arm] can only be called while MOTO-HUB is still on
 * screen - Android refuses a foreground service started from the background - so a watch not
 * armed on the way out cannot be armed afterwards, and refusing to arm during an attempt would
 * miss exactly the rider this exists for.
 *
 * SETUP_REQUIRED is excluded because pairing is not finished, and READY / REQUESTING_PROJECTION /
 * CAPTURING because the motorcycle is already there: a notification saying "waiting for the
 * motorcycle" beside a running session is a lie.
 */
fun shouldWatchForBike(
    autoConnectEnabled: Boolean,
    hasSavedMotorcycle: Boolean,
    phase: SessionPhase,
    riderCancelled: Boolean,
): Boolean =
    autoConnectEnabled &&
        hasSavedMotorcycle &&
        !riderCancelled &&
        when (phase) {
            SessionPhase.NETWORK_SETUP_REQUIRED,
            SessionPhase.ERROR,
            SessionPhase.CONNECTING_NETWORK,
            SessionPhase.DISCOVERING_TBOX -> true
            SessionPhase.SETUP_REQUIRED,
            SessionPhase.READY,
            SessionPhase.REQUESTING_PROJECTION,
            SessionPhase.CAPTURING -> false
        }

/**
 * Keeps MOTO-HUB alive and foreground-eligible while the rider waits for the bike to come up.
 *
 * WHY THIS EXISTS. Rider 36a3fd37 (CFMOTO 800NK, POCO / HyperOS, 2026-09-02) described the gap
 * exactly: "I open the app and it automatically starts up. Then I open the CFMoto app to start
 * the motorcycle and put my phone in my pocket. I start the motorcycle and open MotoPlay hoping
 * that Moto Hub will open automatically. But it never does." His log agrees to the second - the
 * app asked once at 08:44:00, he left for the other app at 08:44:05, the attempt timed out at
 * 08:44:34, the dash only came up around 08:45, and when he brought MOTO-HUB back himself at
 * 08:45:51 it joined in 3239ms. The bike had been there.
 *
 * Two things defeated the in-app retry loop, and this object answers both:
 *
 * 1. Android refuses a `WifiNetworkSpecifier` request from a process that is neither a foreground
 *    app nor a foreground service (see [MainActivity.connectWhenAndroidAccepts]), which is why the
 *    watch loop only ran while the activity was on screen. A foreground service clears that bar -
 *    IMPORTANCE_FOREGROUND_SERVICE is the exact threshold the platform reads.
 * 2. The process did not survive the pocket at all. That log records eight kills in three days,
 *    always from `importance=cached` (SwipeUpClean, clean_up_mem). Nothing running means nothing
 *    retrying, whatever the interval. A foreground service is not cached.
 *
 * The service holds no connection logic of its own on purpose. The retry stays where every brake
 * already lives - the activity's `attemptAutoConnect`, with its phase guard, cooldown, scan
 * evidence and the rider's cancel - and this only buys that loop the right to run.
 */
object BikeWatch {

    /**
     * How long the watch may hold a notification and keep re-asking.
     *
     * The gap it exists to cover is about two minutes: unlock the phone, start the bike, wait for
     * the dash to boot. Fifteen minutes is generous for that and short enough that a rider who
     * opened MOTO-HUB and then went to work does not carry a retrying app and a notification all
     * day. When it expires the app is exactly where it was before - nothing is lost but the
     * waiting.
     */
    const val WATCH_WINDOW_MS = 15 * 60 * 1000L

    @Volatile
    var armed: Boolean = false
        private set

    /** The motorcycle being waited for, shown in the notification. Null when not armed. */
    @Volatile
    var watchingFor: String? = null
        private set

    fun arm(context: Context, motorcycleName: String) {
        if (armed) return
        armed = true
        watchingFor = motorcycleName
        ProjectionEventLog.record(
            "AUTO_CONNECT",
            "Watching for $motorcycleName in the background for up to " +
                "${WATCH_WINDOW_MS / 60_000} minutes; MOTO-HUB stays awake so Android will " +
                "accept the Wi-Fi request when the dash comes up."
        )
        val intent = Intent(context, BikeWatchService::class.java)
        runCatching { context.startForegroundService(intent) }.onFailure { failure ->
            armed = false
            watchingFor = null
            ProjectionEventLog.warning(
                "AUTO_CONNECT",
                "Could not start the background watch: ${failure.javaClass.simpleName} " +
                    "${failure.message}. The bike will only be picked up while MOTO-HUB is on screen."
            )
        }
    }

    fun disarm(context: Context, reason: String) {
        if (!armed) return
        armed = false
        val bike = watchingFor
        watchingFor = null
        ProjectionEventLog.record("AUTO_CONNECT", "Stopped watching for ${bike ?: "the motorcycle"}: $reason.")
        runCatching { context.stopService(Intent(context, BikeWatchService::class.java)) }
    }

    /** The service telling the object it died on its own - a deadline, or the rider's Stop. */
    internal fun onServiceGone() {
        armed = false
        watchingFor = null
    }
}
