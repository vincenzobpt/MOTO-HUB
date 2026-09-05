// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import io.motohub.android.session.MotorcycleProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

    // ----- dashboard reboots ---------------------------------------------------------------------

    private fun confirmedRungZero(
        sessionStart: Long,
        sessionEnd: Long,
        endedByDashboard: Boolean = true,
        uptimeAtStart: Long = 88_182L,
        rungIndex: Int = 0,
        state: TBoxLadderState = TBoxLadderState.CONFIRMED,
        rebootsOnRung: Int = 0
    ) = TBoxLadderProgress(
        rungIndex = rungIndex,
        state = state,
        lastOutcome = "RIDER_CONFIRMED",
        lastSessionStartedAtMillis = sessionStart,
        lastSessionEndedAtMillis = sessionEnd,
        lastSessionEndedByDashboard = endedByDashboard,
        lastUptimeMillis = uptimeAtStart,
        lastUptimeSeenAtMillis = sessionStart,
        rebootsOnRung = rebootsOnRung
    )

    /**
     * VOGE 800 Rally, 2026-09-03: CLIENT_INFO said 88182ms at 11:54:36.105, the dash went silent
     * and the session was closed at 11:56:37.062, and the next CLIENT_INFO at 11:56:46.018 said
     * 9738ms. The firmware came back up 0.8s before we closed the session it had taken down.
     */
    @Test
    fun `an uptime that went backwards inside a dash-ended session is that session's reboot`() {
        val start = BASE
        val end = BASE + 120_957L
        val next = BASE + 129_913L
        assertEquals(
            next - 9_738L,
            TBoxWireLadder.dashboardRebootBehindLastSession(
                confirmedRungZero(start, end), uptimeMillis = 9_738L, nowMillis = next
            )
        )
    }

    /** Same bike on 2026-08-31: 52154ms at 12:10:59, closed 12:12:56, 53468ms at 12:14:07 - back up 18s after the close. */
    @Test
    fun `a reboot shortly after the dash closed the session still belongs to it`() {
        val start = BASE
        val end = BASE + 117_256L
        val next = BASE + 188_535L
        val rebootedAt = TBoxWireLadder.dashboardRebootBehindLastSession(
            confirmedRungZero(start, end, uptimeAtStart = 52_154L), uptimeMillis = 53_468L, nowMillis = next
        )
        assertEquals(next - 53_468L, rebootedAt)
        assertTrue(rebootedAt!! > end)
    }

    @Test
    fun `an uptime that kept counting is not a reboot`() {
        val start = BASE
        val next = BASE + 129_913L
        assertNull(
            TBoxWireLadder.dashboardRebootBehindLastSession(
                confirmedRungZero(start, BASE + 120_957L), uptimeMillis = 88_182L + 129_913L, nowMillis = next
            )
        )
    }

    @Test
    fun `a reboot after a session the rider ended is nobody's fault`() {
        val next = BASE + 129_913L
        assertNull(
            TBoxWireLadder.dashboardRebootBehindLastSession(
                confirmedRungZero(BASE, BASE + 120_957L, endedByDashboard = false), uptimeMillis = 9_738L, nowMillis = next
            )
        )
    }

    @Test
    fun `the next morning's ignition cycle is not laid at last night's session`() {
        // Dash-ended session, but the uptime says the firmware came up eight hours later.
        val next = BASE + 8 * 3_600_000L
        assertNull(
            TBoxWireLadder.dashboardRebootBehindLastSession(
                confirmedRungZero(BASE, BASE + 120_957L), uptimeMillis = 12_000L, nowMillis = next
            )
        )
    }

    @Test
    fun `an epoch-like uptime can neither count up into a reboot nor jump back into one`() {
        // Some CFMOTO units put an epoch-ish millisecond value here (1613228316255 seen in the field).
        val epochLike = 1_613_228_316_255L
        val progress = confirmedRungZero(BASE, BASE + 120_957L, uptimeAtStart = epochLike)
        assertNull(TBoxWireLadder.dashboardRebootBehindLastSession(progress, epochLike + 15_129L, BASE + 15_129L))
        assertNull(TBoxWireLadder.dashboardRebootBehindLastSession(progress, epochLike - 600_000L, BASE + 15_129L))
    }

    @Test
    fun `one reboot is counted, two move a confirmed rung on`() {
        val once = TBoxWireLadder.nextProgressAfterReboot(confirmedRungZero(BASE, BASE + 1L))
        assertEquals(0, once.rungIndex)
        assertEquals(TBoxLadderState.CONFIRMED, once.state)
        assertEquals(1, once.rebootsOnRung)
        assertEquals(TBoxWireLadder.REBOOT_OUTCOME, once.lastOutcome)

        val twice = TBoxWireLadder.nextProgressAfterReboot(once)
        assertEquals(1, twice.rungIndex)
        assertEquals(TBoxLadderState.TRYING, twice.state)
        assertEquals(0, twice.rebootsOnRung)
        assertEquals(0, twice.attemptsOnRung)
        assertEquals(TBoxWireLadder.REBOOT_OUTCOME, twice.lastOutcome)
    }

    @Test
    fun `reboots on the last rung exhaust the ladder rather than wrapping it`() {
        val last = confirmedRungZero(BASE, BASE + 1L, rungIndex = TBoxWireLadder.RUNGS.lastIndex, state = TBoxLadderState.AWAITING_RIDER, rebootsOnRung = 1)
        val next = TBoxWireLadder.nextProgressAfterReboot(last)
        assertEquals(TBoxLadderState.EXHAUSTED, next.state)
        assertEquals(0, next.rungIndex)
    }

    @Test
    fun `an exhausted ladder counts reboots and never walks again`() {
        val exhausted = confirmedRungZero(BASE, BASE + 1L, state = TBoxLadderState.EXHAUSTED, rebootsOnRung = 5)
        val next = TBoxWireLadder.nextProgressAfterReboot(exhausted)
        assertEquals(TBoxLadderState.EXHAUSTED, next.state)
        assertEquals(0, next.rungIndex)
        assertEquals(6, next.rebootsOnRung)
    }

    @Test
    fun `the session window and the uptime survive the round trip`() {
        val stored = """
            {"rung":0,"state":"CONFIRMED","outcome":"DASHBOARD_REBOOTED","sessStart":1000,"sessEnd":121957,
             "sessDashEnd":true,"hu":88182,"huAt":1000,"reboots":1}
        """.trimIndent()
        val progress = TBoxWireLadder.parseProgress(stored)
        assertEquals(1000L, progress.lastSessionStartedAtMillis)
        assertEquals(121_957L, progress.lastSessionEndedAtMillis)
        assertTrue(progress.lastSessionEndedByDashboard)
        assertEquals(88_182L, progress.lastUptimeMillis)
        assertEquals(1000L, progress.lastUptimeSeenAtMillis)
        assertEquals(1, progress.rebootsOnRung)
    }

    @Test
    fun `an old record without the reboot fields still parses`() {
        val progress = TBoxWireLadder.parseProgress(STORED)
        assertNull(progress.lastSessionStartedAtMillis)
        assertNull(progress.lastUptimeMillis)
        assertEquals(0, progress.rebootsOnRung)
        assertEquals(false, progress.lastSessionEndedByDashboard)
    }

    // --- the stream had to be healthy for the rider's answer to mean anything ---

    /**
     * Rider 738a2340, 2026-09-05: the dashboard's Wi-Fi kept going away, two auto-recoveries burned
     * their full 120s, the Android Auto decoder stalled for 20-45s at a time - and frames kept
     * being handed to the dashboard the whole time, so the session looked like a clean STREAMED.
     * The question that follows it reads a "no" as a verdict on the wire, and that "no" moved him
     * onto a rung his dashboard renders green.
     */
    @Test
    fun aSessionWhoseStreamWasNotHealthyDoesNotEarnTheQuestion() {
        val outcome = TBoxSessionOutcome.of(
            facts(durationMillis = 230_000L, framesOffered = 3_900L)
                .copy(trouble = "Android Auto auto-recovery timed out after 2 attempt(s)")
        )
        assertEquals(TBoxSessionOutcome.INCONCLUSIVE, outcome)
        val next = TBoxWireLadder.nextProgress(TBoxLadderProgress(), outcome)
        assertEquals(TBoxLadderState.TRYING, next.state)
        assertEquals(0, next.rungIndex)
    }

    /**
     * Trouble silences the rider's question, not the protocol's own evidence: a dashboard that
     * dropped the socket did so over bytes it had already received, and a stalled source still
     * produces valid H.264.
     */
    @Test
    fun troubleDoesNotExcuseADashboardThatRejectedTheStream() {
        val outcome = TBoxSessionOutcome.of(
            facts(durationMillis = 6_000L, endedByDashboard = true)
                .copy(trouble = "Android Auto recovery attempt 1 failed")
        )
        assertEquals(TBoxSessionOutcome.REJECTED, outcome)
    }

    // --- what other riders with the same dashboard already answered ---

    /** A table that points at a rung this build does not have would silently do nothing. */
    @Test
    fun everyCataloguedRungExists() {
        TBoxWireCatalogue.CONFIRMED_RUNGS.forEach { (fingerprint, rung) ->
            assertTrue(fingerprint, rung in TBoxWireLadder.RUNGS.indices)
        }
    }

    /**
     * The rescue this table exists for: a bike walked off the wire its dashboard's other riders
     * confirmed is put back on it, once, by the update that adds the row.
     */
    @Test
    fun aBikeWalkedOffTheConfirmedWireIsPutBackOnIt() {
        val strayed = TBoxLadderProgress(
            rungIndex = 1,
            state = TBoxLadderState.TRYING,
            fingerprint = "EASYCONN_5G/49/66660005/5.0",
            lastOutcome = "INCONCLUSIVE"
        )
        val seeded = TBoxWireLadder.seededProgress(strayed, catalogueRung = 0, revision = 1)
        assertEquals(0, seeded.rungIndex)
        assertEquals(TBoxLadderState.TRYING, seeded.state)
        assertEquals(1, seeded.catalogueRevision)
        // and only once: the rider is free to walk away from the catalogue afterwards.
        val walkedAgain = seeded.copy(rungIndex = 1)
        assertEquals(walkedAgain, TBoxWireLadder.seededProgress(walkedAgain, 0, revision = 1))
    }

    /** A rider's own eyes outrank the table; so does an exhausted walk, which must not re-open. */
    @Test
    fun theCatalogueNeverOverrulesTheRiderOrReopensAnExhaustedWalk() {
        val confirmed = TBoxLadderProgress(rungIndex = 2, state = TBoxLadderState.CONFIRMED)
        val seededConfirmed = TBoxWireLadder.seededProgress(confirmed, catalogueRung = 0, revision = 1)
        assertEquals(2, seededConfirmed.rungIndex)
        assertEquals(TBoxLadderState.CONFIRMED, seededConfirmed.state)

        val exhausted = TBoxLadderProgress(rungIndex = 0, state = TBoxLadderState.EXHAUSTED)
        val seededExhausted = TBoxWireLadder.seededProgress(exhausted, catalogueRung = 0, revision = 1)
        assertEquals(TBoxLadderState.EXHAUSTED, seededExhausted.state)
        // Both are stamped, or the seed would run again at every load.
        assertEquals(1, seededConfirmed.catalogueRevision)
        assertEquals(1, seededExhausted.catalogueRevision)
    }

    /** A dashboard nobody has agreed on is left exactly as it was. */
    @Test
    fun aDashboardWithNoCatalogueEntryIsUntouched() {
        val progress = TBoxLadderProgress(rungIndex = 1, fingerprint = "nobody/has/this/one")
        assertEquals(progress, TBoxWireLadder.seededProgress(progress, catalogueRung = null))
    }

    /**
     * One "no" against a wire other riders confirmed buys a second ride, not a move. Two "no"s
     * mean this bike disagrees with the table, and the ladder walks exactly as it always did.
     */
    @Test
    fun leavingAConfirmedWireTakesASecondOpinion() {
        val awaiting = TBoxLadderProgress(
            rungIndex = 0,
            state = TBoxLadderState.AWAITING_RIDER,
            fingerprint = "EASYCONN_5G/49/66660005/5.0"
        )
        val once = TBoxWireLadder.nextProgressAfterRider(awaiting, projectionSeen = false, catalogueRung = 0)
        assertEquals(0, once.rungIndex)
        assertEquals(TBoxLadderState.TRYING, once.state)
        assertEquals(1, once.denialsOnRung)

        val twice = TBoxWireLadder.nextProgressAfterRider(
            once.copy(state = TBoxLadderState.AWAITING_RIDER),
            projectionSeen = false,
            catalogueRung = 0
        )
        assertEquals(1, twice.rungIndex)
        assertEquals(0, twice.denialsOnRung)
    }

    /** The second opinion is owed to the catalogued rung only, not to whatever the bike is on. */
    @Test
    fun aRungTheCatalogueDoesNotVouchForStillMovesOnTheFirstNo() {
        val awaiting = TBoxLadderProgress(rungIndex = 1, state = TBoxLadderState.AWAITING_RIDER)
        val next = TBoxWireLadder.nextProgressAfterRider(awaiting, projectionSeen = false, catalogueRung = 0)
        assertEquals(2, next.rungIndex)
    }

    @Test
    fun `an old record without the catalogue fields still parses`() {
        val progress = TBoxWireLadder.parseProgress(STORED)
        assertEquals(0, progress.denialsOnRung)
        assertEquals(0, progress.catalogueRevision)
    }

    private companion object {
        const val BASE = 1_756_900_000_000L
        const val STORED = """{"rung":0,"state":"CONFIRMED","outcome":"RIDER_CONFIRMED"}"""
    }
}
