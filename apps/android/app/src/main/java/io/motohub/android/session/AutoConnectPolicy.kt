// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// Whether an UNATTENDED connection attempt may start. The rider's cancel outranks the retry.
package io.motohub.android.session

/** The verdict on one unattended attempt: start it, or say in the log why it was not started. */
sealed interface AutoConnectDecision {
    data object Go : AutoConnectDecision

    /** [reason] completes the sentence "Auto-connect skipped; ". */
    data class Skip(val reason: String) : AutoConnectDecision
}

/**
 * Should auto-connect fire on this resume?
 *
 * Auto-connect deliberately retries on every resume rather than once per launch - the bike's AP
 * may not be up the first time MOTO-HUB opens - and its only brake used to be a 5s cooldown. Two
 * field patterns show what that costs, both from rider c110050c (2026-08-25/26, a phone at home
 * with no motorcycle anywhere near it):
 *
 * - The rider tapped Cancel and the app started again 5s later, once *while he was starting
 *   phone-only Android Auto*, and again on the next launch's first resume. Cancel is the
 *   clearest "no" the UI has; it must not be answered by a fresh attempt one cooldown later.
 *   [HubViewModel.cancelConnection] leaves the phase at NETWORK_SETUP_REQUIRED - exactly the
 *   phase auto-connect requires - so without a memory of the cancel the retry is not merely
 *   allowed, it is invited.
 * - Four attempts fired from the ON_RESUME of returning from the photo picker while he was only
 *   changing the bike's picture in Garage, each burning a 30s+6s Wi-Fi request that CORE had
 *   already predicted would fail: it logs "CFMOTO7095 is NOT in the phone's latest Wi-Fi scan"
 *   and submits the request anyway.
 *
 * [dashBroadcasting] is [TBoxNetworkConnector.isDashBroadcasting]'s tri-state, and the tri-state
 * is why this is safe: null means the phone handed back no usable scan (absent, empty, or too old
 * to have seen the dash come up) and convicts nobody, so it never blocks an attempt. Only a
 * definite sighting lifts a cancel, and only a definite absence stops a retry.
 *
 * Age is checked at the source rather than here - a list older than
 * [SCAN_EVIDENCE_MAX_AGE_MS] arrives as null - because this function must not be able to tell a
 * stale absence from a fresh one. Rider 36a3fd37 (2026-09-01) lost a ride to exactly that: he
 * powered the dash up AFTER pressing start, came back to the app twice, and both retries were
 * refused by a scan taken before the dash existed.
 *
 * The FIRST attempt of a process is never blocked by absence ([previousAttempts] == 0). A scan
 * can be minutes old at launch, and the one attempt a rider actually waits for is the one that
 * runs when they open the app - if it is going to be wrong, it should be wrong in the direction
 * of trying.
 */
fun autoConnectDecision(
    riderCancelled: Boolean,
    previousAttempts: Int,
    dashBroadcasting: Boolean?,
): AutoConnectDecision = when {
    riderCancelled && dashBroadcasting != true -> AutoConnectDecision.Skip(
        "the rider cancelled a connection attempt and the motorcycle has not been seen on the " +
            "air since. Tap Connect, or it resumes by itself once the dash is broadcasting."
    )
    previousAttempts > 0 && dashBroadcasting == false -> AutoConnectDecision.Skip(
        "the motorcycle is not in the phone's latest Wi-Fi scan and $previousAttempts automatic " +
            "attempt(s) have already been made in this session."
    )
    else -> AutoConnectDecision.Go
}
