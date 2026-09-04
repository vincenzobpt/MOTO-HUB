// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.session

import io.motohub.android.encoding.VideoDeliveryProbe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What a session is doing to the dashboard, in the two numbers that decide it.
 *
 * [ssid] rather than a profile id because that is the key both halves of MOTO-HUB agree on: the
 * report is raised in whichever process owns the video pipeline and read in whichever process
 * owns the screen, and those are not the same process in the ADVANCED pairing.
 */
data class DashboardDeliveryReport(
    val ssid: String,
    val rejected: Int,
    val accepted: Int,
    val profileKey: String,
    /**
     * The stream is landing. Carried as a fact rather than left implicit in "no complaint",
     * because it is the only evidence the app has when it asks a rider to keep a profile they
     * just picked - and a confirmation prompt built on the absence of bad news would fire on a
     * session that never sent a frame.
     */
    val healthy: Boolean
) {
    /** 0..1. The single number the rider-facing sentence is built from. */
    val rejectedShare: Double
        get() = (rejected + accepted).let { if (it == 0) 0.0 else rejected.toDouble() / it }
}

/**
 * Process-wide latch for what the video pipeline concluded about the dashboard.
 *
 * Deliberately a latch and not an event stream. Each conclusion earns the right to say ONE thing
 * to the rider - "this dashboard is refusing the picture, try another profile", or "the profile
 * you just picked is working, keep it?" - and a question asked twice is a question riders learn
 * to dismiss.
 *
 * Cleared when a session is rebuilt, because a rebuild around a different profile is a genuinely
 * new question and deserves a new answer.
 */
object DashboardDeliveryMonitor {
    private val state = MutableStateFlow<DashboardDeliveryReport?>(null)

    /** Null until a session has been observed long enough to say something. */
    val current: StateFlow<DashboardDeliveryReport?> = state.asStateFlow()

    /**
     * Publishes a verdict the probe reached. Both kinds are logged, and the healthy one is not
     * noise: a rider's shared log is the only place anyone can later check whether a profile
     * change actually did what the app claimed it did.
     */
    fun publish(
        verdict: VideoDeliveryProbe.Verdict,
        ssid: String,
        rejected: Int,
        accepted: Int,
        profileKey: String
    ) {
        val total = rejected + accepted
        when (verdict) {
            VideoDeliveryProbe.Verdict.FAILING -> ProjectionEventLog.warning(
                "DELIVERY",
                "$rejected of the first $total frames could not be handed to the transport on " +
                    "profile $profileKey. The link is up, so this is the profile not matching " +
                    "the dashboard rather than a connection fault - offering the rider a " +
                    "different one."
            )
            VideoDeliveryProbe.Verdict.HEALTHY -> ProjectionEventLog.record(
                "DELIVERY",
                // Deliberately no longer says "the picture is landing". Both counters come
                // from offerAccessUnit(), which reports whether the frame entered the daemon's
                // ring buffer on THIS phone - the dashboard is never consulted, and a dash that
                // never pulls a byte produces exactly this line. Saying otherwise sent two
                // investigations down the wrong road (ticket a346ec76, a rider who has never
                // seen a picture at all). dashPulls in the session's protocol stats is the
                // counter that answers the question this one only looks like it answers.
                "$accepted of the first $total frames were handed to the transport on profile " +
                    "$profileKey without a refusal; the phone's side of the pipe is healthy. " +
                    "Whether the dashboard asked for any of them is dashPulls."
            )
        }
        state.value = DashboardDeliveryReport(
            ssid = ssid,
            rejected = rejected,
            accepted = accepted,
            profileKey = profileKey,
            healthy = verdict == VideoDeliveryProbe.Verdict.HEALTHY
        )
    }

    fun clear() {
        state.value = null
    }
}
