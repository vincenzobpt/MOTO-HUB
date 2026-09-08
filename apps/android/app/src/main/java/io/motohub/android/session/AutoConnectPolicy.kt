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
 *
 * WHAT LIFTS A CANCEL, AND WHY IT USED TO BE UNREACHABLE. The cancel branch was written to be
 * lifted "once the dash is broadcasting", and it said so to the rider. On a phone that cannot
 * read the air that can never happen: [dashBroadcasting] is null forever, `null != true`, and one
 * Cancel disabled auto-connect for the whole life of the process while the log went on promising
 * it would resume by itself. Support 36a3fd37 fired that skip three times in ninety seconds
 * (08:57:55, 08:58:48, 08:59:22) on a phone whose every scan came back empty; fc17a4f7, 6e77dcf7
 * and f27f3825 are the same phone-blindness with a different bike. And it is not only the blind
 * phones: Android throttles `getScanResults` hard, the freshness rule at the source turns a list
 * older than 30s into null, and a definite sighting is therefore the exception on ANY phone.
 *
 * A cancel is lifted by the dash BECOMING reachable - never by it merely being reachable. Two
 * facts say "reachable", the same pair [io.motohub.android.tbox.AccessPointEvidence] weighs for
 * the access-point road: [dashBroadcasting] == true, a definite sighting in a fresh scan, or
 * [associatedToDash], this phone being on that network at this moment. The second goes through no
 * scan and no throttle, so it is the one that answers on a phone that cannot read the air.
 *
 * WHY "BECOMING", AND NOT "BEING". The first version of this lifted the cancel whenever either
 * fact was true now, and that quietly handed rider c110050c's complaint back at the one place it
 * hurts most. [associatedToDash] is true precisely when the phone is on the dash's own network -
 * which is where a rider is standing when they press Cancel during a discovery that is taking too
 * long. Cancel, five second cooldown, next resume: Go. Every fifteen seconds, for as long as they
 * stay by the motorcycle. So the question is not "can the dash be reached" but "has anything
 * changed since they said no", and [dashReachableWhenCancelled] is what makes that answerable:
 * the cancel stands until the dash goes from out of reach to in reach.
 *
 * There is deliberately NO timer that expires a cancel. A cancel that lapsed after N minutes
 * would re-open the same behaviour with a delay bolted on. The rider who changes their mind taps
 * Connect, which is what the skip says.
 */
fun autoConnectDecision(
    riderCancelled: Boolean,
    previousAttempts: Int,
    dashBroadcasting: Boolean?,
    // No defaults on either of these. A caller that cannot be bothered to answer them would
    // silently reinstate one of the two bugs this branch has had, and there are four call sites
    // across three editions.
    associatedToDash: Boolean,
    /** Whether the dash was ALREADY reachable at the moment the rider cancelled. */
    dashReachableWhenCancelled: Boolean,
): AutoConnectDecision = when {
    riderCancelled && !(dashReachable(dashBroadcasting, associatedToDash) && !dashReachableWhenCancelled) ->
        AutoConnectDecision.Skip(
            // Three cases, because the sentence a rider acts on differs in each and only one of
            // them can honestly promise anything.
            when {
                dashReachableWhenCancelled ->
                    "the rider cancelled with the motorcycle already within reach, so nothing " +
                        "about it has changed since. Tap Connect to try again."
                dashBroadcasting == null ->
                    "the rider cancelled a connection attempt, and this phone cannot say what " +
                        "is on the air - so nothing here will notice the dash coming up. Tap " +
                        "Connect when the dashboard is on (the NETWORK log line above says why " +
                        "the scan is empty)."
                else ->
                    "the rider cancelled a connection attempt and the motorcycle is not in the " +
                        "phone's latest Wi-Fi scan. Tap Connect, or it resumes by itself once " +
                        "the dash appears."
            }
        )
    previousAttempts > 0 && dashBroadcasting == false -> AutoConnectDecision.Skip(
        "the motorcycle is not in the phone's latest Wi-Fi scan and $previousAttempts automatic " +
            "attempt(s) have already been made in this session."
    )
    else -> AutoConnectDecision.Go
}

/**
 * Whether the motorcycle can be reached right now, as the two evidence rungs answer it.
 *
 * Shared by the decision and by whoever records the evidence at cancel time, so the "before" and
 * the "after" are the same question. Two call sites reading the same pair of Booleans with an
 * `||` written out twice is how they drift.
 */
fun dashReachable(dashBroadcasting: Boolean?, associatedToDash: Boolean): Boolean =
    dashBroadcasting == true || associatedToDash

/**
 * Whether the "already within reach when they cancelled" evidence still stands, given what can be
 * seen now.
 *
 * [autoConnectDecision] lifts a cancel when the dash BECOMES reachable, and it compares against a
 * fact sampled at the moment of the cancel. Left at that, the fact is about the past and nothing
 * can ever contradict it: a rider who cancels standing at the motorcycle - which is where cancels
 * happen, and the case [dashReachableWhenCancelled] was added for - then rides away and comes
 * back gets no automatic attempt for the whole life of the process, although the dash really did
 * go out of reach and return.
 *
 * Seeing it out of reach IS that contradiction, and from there a return is a change again. One
 * line, and it lives here rather than in a ViewModel because all four call sites across the three
 * editions have to retire the evidence by the same rule they read it by.
 */
fun cancelEvidenceStillStands(dashReachableWhenCancelled: Boolean, dashReachableNow: Boolean): Boolean =
    dashReachableWhenCancelled && dashReachableNow
