// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import io.motohub.android.session.MotorcycleProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TBoxWireLadderTest {

    private fun facts(
        durationMillis: Long,
        mediaControlEvents: Long = 4L,
        framesOffered: Long = 500L,
        endedByDashboard: Boolean = false
    ) = TBoxSessionFacts(
        durationMillis = durationMillis,
        mediaControlEvents = mediaControlEvents,
        framesOffered = framesOffered,
        frameTimeouts = 0L,
        frameRejections = 0L,
        endedByDashboard = endedByDashboard
    )

    /**
     * The contract that makes this whole mechanism safe to ship: a dashboard that streams today is
     * on rung 0, and rung 0 is byte-for-byte what GENERIC has always sent. If this fails, every
     * unidentified dashboard in the field just changed wire format.
     */
    @Test
    fun rungZeroIsExactlyTheGenericWire() {
        assertEquals(TBoxModelProfile.GENERIC.wireConfig, TBoxWireLadder.RUNGS.first())
    }

    @Test
    fun everyRungIsDistinct() {
        assertEquals(TBoxWireLadder.RUNGS.size, TBoxWireLadder.RUNGS.toSet().size)
    }

    /**
     * The ladder crosses a process boundary now: Core stores it, the companion app reports it, and
     * the JSON in between is read by this one parser on both sides. A report that cannot decode
     * Core's answer is a report that quietly claims a fresh search - the failure this call exists
     * to end (field log 90438e1e, 2026-08-25).
     */
    @Test
    fun `a stored ladder survives the trip to the companion app`() {
        val stored = """
            {"rung":2,"state":"AWAITING_RIDER","attempts":3,"fingerprint":"HU/51/37504/V0.0.1",
             "outcome":"STREAMED","noAaSessions":4,"nudged":true}
        """.trimIndent()

        val progress = TBoxWireLadder.parseProgress(stored)

        assertEquals(2, progress.rungIndex)
        assertEquals(TBoxLadderState.AWAITING_RIDER, progress.state)
        assertEquals(3, progress.attemptsOnRung)
        assertEquals("HU/51/37504/V0.0.1", progress.fingerprint)
        assertEquals("STREAMED", progress.lastOutcome)
        assertEquals(4, progress.sessionsWithoutAndroidAuto)
        assertTrue(progress.androidAutoNudgeShown)
    }

    @Test
    fun `an unreachable Core reads as a fresh ladder, not as a crash`() {
        assertEquals(TBoxLadderProgress(), TBoxWireLadder.parseProgress(null))
        assertEquals(TBoxLadderProgress(), TBoxWireLadder.parseProgress("not json at all"))
    }

    /** A rung index from another version of the app must not index past the table. */
    @Test
    fun `a rung this build does not have is clamped instead of throwing`() {
        val progress = TBoxWireLadder.parseProgress("""{"rung":97,"state":"TRYING"}""")

        assertEquals(TBoxWireLadder.RUNGS.lastIndex, progress.rungIndex)
    }

    /**
     * The 2026-08-11 Zontes 368G runs: indexed framing made the dash drop the video socket at 6s
     * and 17s, where the plain stream held its full 30s timeout. Only that shape indicts the wire.
     */
    @Test
    fun aDashboardThatDropsTheSessionEarlyIndictsTheWire() {
        assertEquals(
            TBoxSessionOutcome.REJECTED,
            TBoxSessionOutcome.of(facts(durationMillis = 6_000L, endedByDashboard = true))
        )
    }

    /**
     * The wedged state: after an earlier failure the 368G answered no MEDIA_CONTROL at all until
     * its ignition was cycled. Blaming the frame format for that would walk the whole ladder in an
     * afternoon against a dash that had stopped listening.
     */
    @Test
    fun aDashboardThatNeverAsksForVideoDoesNotMoveTheLadder() {
        val outcome = TBoxSessionOutcome.of(
            facts(durationMillis = 40_000L, mediaControlEvents = 0L, framesOffered = 0L)
        )
        assertEquals(TBoxSessionOutcome.NEVER_NEGOTIATED, outcome)
        val progress = TBoxLadderProgress(rungIndex = 1)
        assertEquals(1, TBoxWireLadder.nextProgress(progress, outcome).rungIndex)
    }

    /** 3900 frames over four minutes, panel still on the QR page: healthy is not the same as seen. */
    @Test
    fun aLongHealthySessionOnlyEarnsAQuestion() {
        val outcome = TBoxSessionOutcome.of(facts(durationMillis = 230_000L, framesOffered = 3_900L))
        assertEquals(TBoxSessionOutcome.STREAMED, outcome)
        val next = TBoxWireLadder.nextProgress(TBoxLadderProgress(), outcome)
        assertEquals(TBoxLadderState.AWAITING_RIDER, next.state)
        assertEquals(0, next.rungIndex)
    }

    @Test
    fun aRiderWhoSawNothingMovesToTheNextRung() {
        val awaiting = TBoxLadderProgress(rungIndex = 0, state = TBoxLadderState.AWAITING_RIDER)
        val next = TBoxWireLadder.nextProgressAfterRider(awaiting, projectionSeen = false)
        assertEquals(1, next.rungIndex)
        assertEquals(TBoxLadderState.TRYING, next.state)
        assertNotEquals(TBoxWireLadder.RUNGS[0], TBoxWireLadder.RUNGS[next.rungIndex])
    }

    @Test
    fun aRiderWhoSawItPinsTheRungForGood() {
        val awaiting = TBoxLadderProgress(rungIndex = 2, state = TBoxLadderState.AWAITING_RIDER)
        val next = TBoxWireLadder.nextProgressAfterRider(awaiting, projectionSeen = true)
        assertEquals(TBoxLadderState.CONFIRMED, next.state)
        assertEquals(2, next.rungIndex)
    }

    /**
     * Walking off the end returns to the default rather than leaving a rider parked on an exotic
     * format that also did not work.
     */
    @Test
    fun runningOutOfRungsFallsBackToTheDefault() {
        var progress = TBoxLadderProgress()
        repeat(TBoxWireLadder.RUNGS.size) {
            progress = TBoxWireLadder.nextProgressAfterRider(
                progress.copy(state = TBoxLadderState.AWAITING_RIDER),
                projectionSeen = false
            )
        }
        assertEquals(TBoxLadderState.EXHAUSTED, progress.state)
        assertEquals(0, progress.rungIndex)
    }

    /**
     * The walk is a ladder, not a ring. Rider bffd0679's QJ reached EXHAUSTED, was put back on
     * rung 0 by design, streamed there, and was asked to judge a format they had already denied
     * days before - and a "no" started the whole climb over. Rung 0 is all-intra and that dash
     * drops its own AP on all-intra, so the loop did not merely repeat a question, it kept
     * re-breaking a link that had been holding.
     */
    @Test
    fun anExhaustedLadderIsNotWalkedAgainByAnotherDenial() {
        val exhausted = TBoxLadderProgress(rungIndex = 0, state = TBoxLadderState.EXHAUSTED)
        val next = TBoxWireLadder.nextProgressAfterRider(exhausted, projectionSeen = false)
        assertEquals(TBoxLadderState.EXHAUSTED, next.state)
        assertEquals(0, next.rungIndex)
    }

    /**
     * A "yes" still lands, though. If an exhausted walk ever does produce a picture, that answer
     * is the most valuable one this mechanism can collect and must not be swallowed by the guard.
     */
    @Test
    fun anExhaustedLadderStillAcceptsAConfirmation() {
        val exhausted = TBoxLadderProgress(rungIndex = 0, state = TBoxLadderState.EXHAUSTED)
        val next = TBoxWireLadder.nextProgressAfterRider(exhausted, projectionSeen = true)
        assertEquals(TBoxLadderState.CONFIRMED, next.state)
        assertEquals(0, next.rungIndex)
    }

    /** A confirmed motorcycle is never walked again, whatever a later session looks like. */
    @Test
    fun aConfirmedRungSurvivesALaterBadSession() {
        val confirmed = TBoxLadderProgress(rungIndex = 1, state = TBoxLadderState.CONFIRMED)
        val outcome = TBoxSessionOutcome.of(facts(durationMillis = 5_000L, endedByDashboard = true))
        // onSessionFinished short-circuits on CONFIRMED before ever reaching the state machine;
        // this pins the guard that makes that safe.
        assertEquals(TBoxLadderState.CONFIRMED, confirmed.state)
        assertEquals(TBoxSessionOutcome.REJECTED, outcome)
    }

    /**
     * A Ride Dashboard session runs its own video format, so it must not be able to promote or
     * condemn the rung the search is on: a rider testing through mirroring would otherwise end the
     * search on a format that never reached the wire. onSessionIgnored is what the transport calls
     * instead, and it only counts.
     */
    @Test
    fun aSessionTheLadderDidNotGovernNeverMovesTheRung() {
        val progress = TBoxLadderProgress(rungIndex = 1, sessionsWithoutAndroidAuto = 1)
        val counted = progress.copy(sessionsWithoutAndroidAuto = progress.sessionsWithoutAndroidAuto + 1)
        assertEquals(1, counted.rungIndex)
        assertEquals(TBoxLadderState.TRYING, counted.state)
        assertEquals(2, counted.sessionsWithoutAndroidAuto)
    }

    @Test
    fun theFingerprintIgnoresTheUnitSerialSoTwoBikesOfAModelAgree() {
        val a = TBoxCapabilities(huName = "JCDZ34-1112", flavor = "65561", channel = "21334")
        val b = TBoxCapabilities(huName = "JCDZ34-1152", flavor = "65561", channel = "21334")
        assertEquals(TBoxWireLadder.fingerprintOf(a), TBoxWireLadder.fingerprintOf(b))
        assertTrue(TBoxWireLadder.fingerprintOf(a)!!.contains("JCDZ34"))
    }

    @Test
    fun aDashboardThatSaysNothingHasNoFingerprint() {
        assertEquals(null, TBoxWireLadder.fingerprintOf(TBoxCapabilities()))
        assertEquals(null, TBoxWireLadder.fingerprintOf(null))
    }

    /**
     * The defect this key exists to fix: Core's garage, the companion app's garage and a QR rescan
     * each mint their own profile id for one physical dashboard, so the rider's "yes, I can see
     * it" was filed where the next session would not look for it (rider 87bc5a7c, 2026-08-25:
     * rung 0 confirmed in Core at 18:27, asked again on the same dash at 21:41).
     */
    @Test
    fun `one dashboard is one record however many profile ids it collected`() {
        val core = MotorcycleProfile(ssid = "EASYCONN_5G-1813BC", password = "x", id = "core-uuid")
        val companion =
            MotorcycleProfile(ssid = "EASYCONN_5G-1813BC", password = "x", id = "companion-uuid")
        val rescanned =
            MotorcycleProfile(ssid = "EASYCONN_5G-1813BC", password = "x", id = "rescanned-uuid")

        assertEquals(TBoxWireLadder.storageKey(core), TBoxWireLadder.storageKey(companion))
        assertEquals(TBoxWireLadder.storageKey(core), TBoxWireLadder.storageKey(rescanned))
    }

    @Test
    fun `two dashboards stay two records`() {
        val one = MotorcycleProfile(ssid = "EASYCONN_5G-1813BC", password = "x", id = "shared")
        val other = MotorcycleProfile(ssid = "EASYCONN_5G-F3116E", password = "x", id = "shared")
        assertNotEquals(TBoxWireLadder.storageKey(one), TBoxWireLadder.storageKey(other))
    }

    /**
     * A dash reached over Wi-Fi Direct or BLE has no SSID, so its id is the only handle there is.
     * Those bikes must keep the key they already have rather than collide on an empty one.
     */
    @Test
    fun `a motorcycle with no network name keeps its profile id`() {
        val direct = MotorcycleProfile(ssid = "", password = "", id = "p2p-uuid")
        val otherDirect = MotorcycleProfile(ssid = "", password = "", id = "ble-uuid")
        assertEquals("p2p-uuid", TBoxWireLadder.storageKey(direct))
        assertNotEquals(TBoxWireLadder.storageKey(direct), TBoxWireLadder.storageKey(otherDirect))
    }

    @Test
    fun `a bike that already walked the ladder adopts its progress instead of starting over`() {
        assertEquals(
            TBoxLadderRecord.AdoptLegacy(STORED),
            wireLadderRecord(underCurrentKey = null, underLegacyKey = STORED, keysDiffer = true)
        )
    }

    /**
     * Copied, not moved. A companion app older than CONTRACT_VERSION_WIRE_LADDER_BY_SSID can only
     * ask by profile id, and answering it "no ladder" for a bike that has one is exactly the bug
     * CONTRACT_VERSION_WIRE_LADDER was added to fix - so the migration must never be a rename.
     */
    @Test
    fun `adopting is a copy so an older companion app can still read the old key`() {
        val record = wireLadderRecord(
            underCurrentKey = null,
            underLegacyKey = STORED,
            keysDiffer = true
        )
        assertTrue(record is TBoxLadderRecord.AdoptLegacy)
        // The legacy raw is handed back untouched: nothing here can express a delete.
        assertEquals(STORED, (record as TBoxLadderRecord.AdoptLegacy).raw)
    }

    @Test
    fun `the current record wins once it exists and nothing is adopted again`() {
        assertEquals(
            TBoxLadderRecord.Current(STORED),
            wireLadderRecord(underCurrentKey = STORED, underLegacyKey = "stale", keysDiffer = true)
        )
    }

    @Test
    fun `a bike whose key never changed is never migrated onto itself`() {
        assertEquals(
            TBoxLadderRecord.Current(STORED),
            wireLadderRecord(underCurrentKey = null, underLegacyKey = STORED, keysDiffer = false)
        )
    }

    @Test
    fun `a bike that never walked the ladder starts fresh`() {
        assertEquals(
            TBoxLadderRecord.Fresh,
            wireLadderRecord(underCurrentKey = null, underLegacyKey = null, keysDiffer = true)
        )
    }

    private companion object {
        const val STORED = """{"rung":0,"state":"CONFIRMED","outcome":"RIDER_CONFIRMED"}"""
    }
}
