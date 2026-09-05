// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import android.content.Context
import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.session.ProjectionEventLog
import org.json.JSONObject

/** Where a motorcycle currently stands on the ladder. */
enum class TBoxLadderState { TRYING, AWAITING_RIDER, CONFIRMED, EXHAUSTED }

/** The ladder's memory for one motorcycle. */
data class TBoxLadderProgress(
    val rungIndex: Int = 0,
    val state: TBoxLadderState = TBoxLadderState.TRYING,
    /** Dashboard identity the rungs were tried against; a different dash restarts the ladder. */
    val fingerprint: String? = null,
    val attemptsOnRung: Int = 0,
    val lastOutcome: String? = null,
    /**
     * Sessions that ended without teaching the ladder anything because they were not Android Auto.
     * Ride Dashboard sends its own video format, so what its session proves is not about the rung
     * being tried - counting them is only so a rider who never uses Android Auto can be told why
     * the search is standing still.
     */
    val sessionsWithoutAndroidAuto: Int = 0,
    /** The "connect once with Android Auto" nudge has been shown, so it is not shown again. */
    val androidAutoNudgeShown: Boolean = false,
    /**
     * Wall-clock window of the last Android Auto session the ladder was told about, and who ended
     * it. Kept in every state, terminal ones included: it is what the next CLIENT_INFO's uptime is
     * read against - see [TBoxWireLadder.dashboardRebootBehindLastSession].
     */
    val lastSessionStartedAtMillis: Long? = null,
    val lastSessionEndedAtMillis: Long? = null,
    val lastSessionEndedByDashboard: Boolean = false,
    /** The dashboard's own uptime the last time CLIENT_INFO was read, and the wall clock then. */
    val lastUptimeMillis: Long? = null,
    val lastUptimeSeenAtMillis: Long? = null,
    /** Reboots laid at the current rung's door; see [TBoxWireLadder.nextProgressAfterReboot]. */
    val rebootsOnRung: Int = 0,
    /**
     * Riders' denials collected on the current rung. Only counted where it changes anything: a
     * rung [TBoxWireCatalogue] says other riders confirmed asks for a second opinion before the
     * ladder walks away from it - see [TBoxWireLadder.nextProgressAfterRider].
     */
    val denialsOnRung: Int = 0,
    /**
     * The [TBoxWireCatalogue.REVISION] this motorcycle was last seeded at, so one catalogue update
     * re-seeds a bike exactly once and the rider stays free to walk away from it afterwards.
     */
    val catalogueRevision: Int = 0
)

/** Which stored record a ladder lookup should use, and whether the old one has to be adopted. */
internal sealed interface TBoxLadderRecord {
    data object Fresh : TBoxLadderRecord
    data class Current(val raw: String) : TBoxLadderRecord
    data class AdoptLegacy(val raw: String) : TBoxLadderRecord
}

/**
 * Picks between the record filed under a motorcycle's current key and the one its profile id
 * left behind, with no Android in it so the trap can be tested: the legacy record is ADOPTED
 * (copied), never moved. A companion app older than
 * IpcBridgeContract.CONTRACT_VERSION_WIRE_LADDER_BY_SSID can still only ask by id, and answering
 * it "no ladder" for a bike that has one is the bug CONTRACT_VERSION_WIRE_LADDER was added to fix.
 */
internal fun wireLadderRecord(
    underCurrentKey: String?,
    underLegacyKey: String?,
    keysDiffer: Boolean
): TBoxLadderRecord = when {
    underCurrentKey != null -> TBoxLadderRecord.Current(underCurrentKey)
    underLegacyKey == null -> TBoxLadderRecord.Fresh
    // A bike with no SSID is already filed under its id; there is nothing to adopt and writing
    // the same bytes back over themselves would only log a migration that did not happen.
    !keysDiffer -> TBoxLadderRecord.Current(underLegacyKey)
    else -> TBoxLadderRecord.AdoptLegacy(underLegacyKey)
}

/**
 * Tries wire formats on an unidentified dashboard until one works, then remembers it.
 *
 * A hand-written [TBoxModelProfile] is a record of somebody holding the hardware and measuring it.
 * That does not scale: every brand MOTO-HUB has never seen lands on [TBoxModelProfile.GENERIC] and
 * gets one guess, and when the guess is wrong the rider's only recourse is to know that a profile
 * override exists, know which experiment to pick, and pick it. A Zontes 368G rider went four days
 * with a working experiment published and never selected it.
 *
 * So: for a dash nothing recognises, the wire is not guessed once but walked. Rung 0 is exactly
 * what GENERIC has always sent, so a dashboard that works today is unaffected and stays on rung 0
 * forever. A session that indicts the wire moves to the next rung; a session the firmware clearly
 * liked stops the walk and asks the rider the one question no protocol event can answer.
 *
 * **One rung per session, never more.** After a failed session a Zontes 368G stops answering
 * MEDIA_CONTROL entirely until its ignition is cycled, so an in-session retry loop would burn the
 * whole ladder against a dash that had already stopped listening. The rung advances between
 * sessions and the progress is persisted, which also means the walk survives the app being closed.
 */
object TBoxWireLadder {

    /**
     * Ordered by how likely each is to be what an unknown firmware wants, most likely first.
     *
     * Rung 0 is [TBoxModelProfile.GENERIC]'s configuration verbatim - that is the contract that
     * keeps this change invisible to every dashboard already streaming. The rest are the wire
     * halves of the compatibility experiments that came out of field logs, with the identity and
     * geometry left behind: 1s GOP first because that is what the one independently-confirmed
     * Zontes implementation sends, indexed framing after it because `supportExtendProtocol=0` has
     * so far been honest wherever it was tested.
     */
    val RUNGS: List<TBoxWireConfig> = listOf(
        TBoxWireConfig(
            allowsPlainVideoFraming = true,
            requiresProactivePxcHeartbeat = true,
            encoderKeyframeIntervalSeconds = 0,
            encoderPlainGopWithoutIntraRefresh = false
        ),
        TBoxWireConfig(
            allowsPlainVideoFraming = true,
            requiresProactivePxcHeartbeat = true,
            encoderKeyframeIntervalSeconds = 1,
            encoderPlainGopWithoutIntraRefresh = true
        ),
        TBoxWireConfig(
            allowsPlainVideoFraming = false,
            requiresProactivePxcHeartbeat = true,
            encoderKeyframeIntervalSeconds = 0,
            encoderPlainGopWithoutIntraRefresh = false
        ),
        TBoxWireConfig(
            allowsPlainVideoFraming = false,
            requiresProactivePxcHeartbeat = true,
            encoderKeyframeIntervalSeconds = 1,
            encoderPlainGopWithoutIntraRefresh = true
        )
    )

    private const val PREFERENCES_NAME = "tbox_wire_ladder"

    /**
     * Identity of the dashboard, as far as CLIENT_INFO reveals it. Not the SSID: a rider who
     * re-pairs, or a dashboard that is replaced under warranty, must not inherit a verdict that was
     * reached against different firmware. Null until the first session has read CLIENT_INFO.
     */
    fun fingerprintOf(capabilities: TBoxCapabilities?): String? {
        val parts = listOfNotNull(
            capabilities?.huName?.substringBefore('-')?.takeIf { it.isNotBlank() },
            capabilities?.flavor?.takeIf { it.isNotBlank() },
            capabilities?.channel?.takeIf { it.isNotBlank() },
            capabilities?.versionName?.takeIf { it.isNotBlank() }
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString("/")
    }

    /**
     * The wire this motorcycle should be given right now.
     *
     * A recognised profile answers for itself and the ladder never sees it: somebody measured that
     * dashboard, and a guess must not overrule a measurement.
     */
    fun configFor(
        context: Context,
        motorcycle: MotorcycleProfile,
        modelProfile: TBoxModelProfile
    ): TBoxWireConfig {
        if (modelProfile != TBoxModelProfile.GENERIC) return modelProfile.wireConfig
        val progress = load(context, motorcycle)
        return RUNGS.getOrElse(progress.rungIndex) { RUNGS.first() }
    }

    /**
     * Which record belongs to this motorcycle.
     *
     * The SSID, not the profile id: the id is a UUID minted per garage entry, and MOTO-HUB has
     * two garages. Core keeps its own, the companion app keeps its own, and a companion-driven
     * session hands Core the COMPANION's id (MotorcycleConnectRequest.toProfile), so one physical
     * dashboard had two ladder records that never saw each other's verdict - and re-scanning the
     * QR code minted a third. Rider 87bc5a7c confirmed rung 0 in Core at 18:27 on 2026-08-25 and
     * was asked the same question again at 21:41 on the same dash, because by then the session
     * came in over the bridge under a freshly scanned id.
     *
     * Keying on the SSID matches [TBoxScreenMarginsStore], the store next door, and costs nothing
     * that mattered: what protects the walk from a replaced or re-flashed dashboard is
     * [fingerprintOf] and the check in [onDashboardIdentified], not the key.
     *
     * A profile with no SSID (a dash reached over Wi-Fi Direct or BLE, where the id is the only
     * handle there is) keeps the old key, so nothing about those bikes changes.
     */
    private fun keyFor(motorcycle: MotorcycleProfile): String =
        motorcycle.ssid.takeIf { it.isNotBlank() }?.let { "ssid:$it" } ?: motorcycle.id

    fun load(context: Context, motorcycle: MotorcycleProfile): TBoxLadderProgress {
        val preferences = preferences(context)
        val key = keyFor(motorcycle)
        val record = wireLadderRecord(
            underCurrentKey = preferences.getString(key, null),
            underLegacyKey = preferences.getString(motorcycle.id, null),
            keysDiffer = key != motorcycle.id
        )
        val stored = when (record) {
            is TBoxLadderRecord.Fresh -> TBoxLadderProgress()
            is TBoxLadderRecord.Current -> parseProgress(record.raw)
            is TBoxLadderRecord.AdoptLegacy -> {
                preferences.edit().putString(key, record.raw).apply()
                ProjectionEventLog.record(
                    "WIRE",
                    "Adopted this motorcycle's existing wire-ladder progress under its network " +
                        "name so both halves of MOTO-HUB now read the same record."
                )
                parseProgress(record.raw)
            }
        }
        return applyCatalogue(context, motorcycle, stored)
    }

    /**
     * Puts a motorcycle back on the wire other riders with its dashboard confirmed, once per
     * [TBoxWireCatalogue.REVISION].
     *
     * Done here rather than in [onDashboardIdentified] on purpose: the fingerprint the catalogue
     * is keyed by is already in the stored record from the previous session, so the seed reaches
     * [configFor] and the very first session after the update runs on the right wire. Waiting for
     * this session's CLIENT_INFO would cost the rider one more ride on the wrong one.
     */
    private fun applyCatalogue(
        context: Context,
        motorcycle: MotorcycleProfile,
        stored: TBoxLadderProgress
    ): TBoxLadderProgress {
        val seeded = seededProgress(stored, TBoxWireCatalogue.rungFor(stored.fingerprint))
        if (seeded == stored) return stored
        save(context, motorcycle, seeded)
        if (seeded.rungIndex != stored.rungIndex) {
            ProjectionEventLog.record(
                "WIRE",
                "Other riders with this dashboard (${stored.fingerprint}) confirmed wire rung " +
                    "${seeded.rungIndex} (${RUNGS[seeded.rungIndex].signature}); moving this " +
                    "motorcycle back onto it from rung ${stored.rungIndex} " +
                    "(${RUNGS.getOrElse(stored.rungIndex) { RUNGS.first() }.signature})."
            )
        }
        return seeded
    }

    /**
     * The catalogue half of the state machine, pure so the two cases that matter can be pinned.
     *
     * A rider's own eyes outrank the catalogue: [TBoxLadderState.CONFIRMED] keeps its rung and only
     * takes the revision stamp. [TBoxLadderState.EXHAUSTED] is left alone as well - it already sits
     * on rung 0 having tried everything, and re-opening it is the ring [onSessionFinished] refuses
     * to be. Everything else is a search still in progress, which is exactly what the catalogue has
     * better information than.
     */
    internal fun seededProgress(
        progress: TBoxLadderProgress,
        catalogueRung: Int?,
        revision: Int = TBoxWireCatalogue.REVISION
    ): TBoxLadderProgress {
        if (catalogueRung == null || catalogueRung !in RUNGS.indices) return progress
        if (progress.catalogueRevision >= revision) return progress
        val stamped = progress.copy(catalogueRevision = revision)
        if (progress.state == TBoxLadderState.CONFIRMED ||
            progress.state == TBoxLadderState.EXHAUSTED ||
            progress.rungIndex == catalogueRung
        ) {
            return stamped
        }
        return stamped.copy(
            rungIndex = catalogueRung,
            state = TBoxLadderState.TRYING,
            attemptsOnRung = 0,
            rebootsOnRung = 0,
            denialsOnRung = 0,
            lastOutcome = CATALOGUE_OUTCOME
        )
    }

    /** What [lastOutcome] reads after the catalogue moved a bike; not a session verdict. */
    internal const val CATALOGUE_OUTCOME = "CATALOGUE_SEEDED"

    /**
     * The stored JSON for one motorcycle, verbatim, for the companion app to be told over the
     * bridge - see IpcBridgeContract.CONTRACT_VERSION_WIRE_LADDER_BY_SSID. Null when this bike
     * has no ladder yet.
     *
     * [key] is an SSID as [keyFor] builds it; the raw profile id is still accepted for a
     * companion app that predates the change and can only ask that way.
     */
    fun storedProgress(context: Context, key: String): String? =
        preferences(context).getString(key, null)

    /** The lookup key a companion app should ask Core for, given the bike it has in hand. */
    fun storageKey(motorcycle: MotorcycleProfile): String = keyFor(motorcycle)

    /**
     * Reads what [save] wrote, wherever it comes from: this process's preferences, or Core's
     * answer arriving in the companion app. Anything unreadable is a fresh walk rather than an
     * error - a ladder is a guess in progress, and a corrupt one is worth exactly as much as none.
     */
    fun parseProgress(raw: String?): TBoxLadderProgress {
        if (raw == null) return TBoxLadderProgress()
        return runCatching {
            val json = JSONObject(raw)
            TBoxLadderProgress(
                rungIndex = json.optInt("rung", 0).coerceIn(0, RUNGS.lastIndex),
                state = runCatching { TBoxLadderState.valueOf(json.optString("state")) }
                    .getOrDefault(TBoxLadderState.TRYING),
                fingerprint = json.optString("fingerprint").takeIf { it.isNotBlank() },
                attemptsOnRung = json.optInt("attempts", 0),
                lastOutcome = json.optString("outcome").takeIf { it.isNotBlank() },
                sessionsWithoutAndroidAuto = json.optInt("noAaSessions", 0),
                androidAutoNudgeShown = json.optBoolean("nudged", false),
                lastSessionStartedAtMillis = json.optionalLong("sessStart"),
                lastSessionEndedAtMillis = json.optionalLong("sessEnd"),
                lastSessionEndedByDashboard = json.optBoolean("sessDashEnd", false),
                lastUptimeMillis = json.optionalLong("hu"),
                lastUptimeSeenAtMillis = json.optionalLong("huAt"),
                rebootsOnRung = json.optInt("reboots", 0),
                denialsOnRung = json.optInt("denials", 0),
                catalogueRevision = json.optInt("cat", 0)
            )
        }.getOrDefault(TBoxLadderProgress())
    }

    private fun save(context: Context, motorcycle: MotorcycleProfile, progress: TBoxLadderProgress) {
        val json = JSONObject()
            .put("rung", progress.rungIndex)
            .put("state", progress.state.name)
            .put("attempts", progress.attemptsOnRung)
        progress.fingerprint?.let { json.put("fingerprint", it) }
        progress.lastOutcome?.let { json.put("outcome", it) }
        json.put("noAaSessions", progress.sessionsWithoutAndroidAuto)
        json.put("nudged", progress.androidAutoNudgeShown)
        progress.lastSessionStartedAtMillis?.let { json.put("sessStart", it) }
        progress.lastSessionEndedAtMillis?.let { json.put("sessEnd", it) }
        json.put("sessDashEnd", progress.lastSessionEndedByDashboard)
        progress.lastUptimeMillis?.let { json.put("hu", it) }
        progress.lastUptimeSeenAtMillis?.let { json.put("huAt", it) }
        json.put("reboots", progress.rebootsOnRung)
        json.put("denials", progress.denialsOnRung)
        json.put("cat", progress.catalogueRevision)
        preferences(context).edit().putString(keyFor(motorcycle), json.toString()).apply()
    }

    /**
     * Called once CLIENT_INFO has been read.
     *
     * Two things are read out of it. A dashboard that is not the one the ladder has been walking
     * against invalidates everything learned so far - better a fresh walk than a verdict inherited
     * from other firmware. And the dashboard's uptime is compared with the last one seen: an uptime
     * that went backwards means the firmware rebooted in between, and a reboot that falls inside
     * (or just after) a session the dashboard itself ended is the one piece of evidence the ladder
     * had no way to see. A Voge 800 Rally rider (2026-08-31 and 09-03) confirmed rung 0 because the
     * picture appeared - and then lost the dash, and its clock, ~2 minutes into every session:
     * `currentHUTime` 88182ms at one CLIENT_INFO, 9738ms at the next, 130s later. STREAMED, said
     * the outcome, because 25s had passed and the frames had flowed. The rung was never moved.
     */
    fun onDashboardIdentified(
        context: Context,
        motorcycle: MotorcycleProfile,
        modelProfile: TBoxModelProfile,
        capabilities: TBoxCapabilities?,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        var progress = load(context, motorcycle)
        val fingerprint = fingerprintOf(capabilities)
        if (fingerprint != null) {
            if (progress.fingerprint == null) {
                progress = progress.copy(fingerprint = fingerprint)
            } else if (progress.fingerprint != fingerprint) {
                ProjectionEventLog.record(
                    "WIRE",
                    "A different dashboard answered on this motorcycle (was ${progress.fingerprint}, " +
                        "now $fingerprint); restarting the wire search from the default."
                )
                progress = TBoxLadderProgress(fingerprint = fingerprint)
            }
        }
        val uptime = capabilities?.huUptimeMillis
        if (uptime != null) {
            dashboardRebootBehindLastSession(progress, uptime, nowMillis)?.let { rebootedAt ->
                progress = noteDashboardReboot(progress, modelProfile, rebootedAt, nowMillis)
            }
            progress = progress.copy(lastUptimeMillis = uptime, lastUptimeSeenAtMillis = nowMillis)
        }
        save(context, motorcycle, progress)
    }

    /** Uptime may fall this far short of the wall clock before it counts as having gone backwards. */
    internal const val REBOOT_UPTIME_SLACK_MILLIS = 30_000L

    /**
     * How long after a dashboard-ended session a reboot is still laid at that session's door. The
     * dash stops answering, the keepalives go unanswered for ~18s, we declare the session over, and
     * the firmware is back up some seconds after that: on 2026-08-31 the Voge came back 18s after
     * the session it took down had been closed.
     */
    internal const val REBOOT_ATTRIBUTION_GRACE_MILLIS = 60_000L

    /**
     * Reboots on one rung before the ladder stops trusting it, whatever the rider said. One is a
     * dashboard that may have died for any reason - low battery, a loose plug - and would move a
     * bike off a wire that works; two, each ending a session the dashboard itself closed, is a
     * pattern, and the same dash ran twenty minutes clean on an earlier build, so a reboot every
     * ride is not the firmware's resting state.
     */
    internal const val REBOOTS_BEFORE_ADVANCE = 2

    /** What [lastOutcome] reads after a reboot was counted; a string because it is not a session verdict. */
    internal const val REBOOT_OUTCOME = "DASHBOARD_REBOOTED"

    /**
     * When the dashboard rebooted, if its fresh uptime says it did and the reboot belongs to the
     * last session. Null otherwise. Pure, so the arithmetic can be checked against field logs.
     *
     * The uptime is expected to have grown by the wall-clock time elapsed since it was last read;
     * one that fell short by more than [REBOOT_UPTIME_SLACK_MILLIS] was reset. The reset instant is
     * `now - uptime`, and it is blamed on the previous session only when the dashboard ended that
     * session and the instant falls between its start and [REBOOT_ATTRIBUTION_GRACE_MILLIS] past
     * its end. An ignition cycle the next morning resets the uptime too, and lands nowhere near
     * that window; a CFMOTO unit that reports an epoch-like number here cannot land in it either.
     */
    internal fun dashboardRebootBehindLastSession(
        progress: TBoxLadderProgress,
        uptimeMillis: Long,
        nowMillis: Long
    ): Long? {
        val lastUptime = progress.lastUptimeMillis ?: return null
        val lastSeenAt = progress.lastUptimeSeenAtMillis ?: return null
        if (uptimeMillis < 0L || nowMillis < lastSeenAt) return null
        val expected = lastUptime + (nowMillis - lastSeenAt)
        if (uptimeMillis + REBOOT_UPTIME_SLACK_MILLIS >= expected) return null
        if (!progress.lastSessionEndedByDashboard) return null
        val start = progress.lastSessionStartedAtMillis ?: return null
        val end = progress.lastSessionEndedAtMillis ?: return null
        val rebootedAt = nowMillis - uptimeMillis
        return rebootedAt.takeIf { it in start..(end + REBOOT_ATTRIBUTION_GRACE_MILLIS) }
    }

    /**
     * The reboot half of the state machine, pure like the rest. Counts the reboot against the
     * current rung and, at [REBOOTS_BEFORE_ADVANCE], moves on - from CONFIRMED and AWAITING_RIDER
     * as much as from TRYING, because a firmware that reboots under a stream has not confirmed it,
     * whatever the panel showed. EXHAUSTED stays exhausted: every wire has been tried, and going
     * round again is the ring [onSessionFinished] refuses to be.
     */
    internal fun nextProgressAfterReboot(progress: TBoxLadderProgress): TBoxLadderProgress {
        val counted = progress.copy(
            rebootsOnRung = progress.rebootsOnRung + 1,
            lastOutcome = REBOOT_OUTCOME
        )
        if (progress.state == TBoxLadderState.EXHAUSTED) return counted
        if (counted.rebootsOnRung < REBOOTS_BEFORE_ADVANCE) return counted
        return advance(counted, null)
    }

    private fun noteDashboardReboot(
        progress: TBoxLadderProgress,
        modelProfile: TBoxModelProfile,
        rebootedAtMillis: Long,
        nowMillis: Long
    ): TBoxLadderProgress {
        val end = progress.lastSessionEndedAtMillis ?: nowMillis
        val relative = if (rebootedAtMillis <= end) {
            "${(end - rebootedAtMillis) / 1000}s before the last session was closed"
        } else {
            "${(rebootedAtMillis - end) / 1000}s after the last session was closed"
        }
        val rung = RUNGS.getOrElse(progress.rungIndex) { RUNGS.first() }
        if (modelProfile != TBoxModelProfile.GENERIC) {
            ProjectionEventLog.warning(
                "WIRE",
                "The dashboard rebooted $relative: its uptime went backwards, and the dashboard was " +
                    "the one that ended that session. Noted only - the ${modelProfile.key} profile " +
                    "was measured by hand and the wire search does not overrule it."
            )
            return progress
        }
        val next = nextProgressAfterReboot(progress)
        val counted = progress.rebootsOnRung + 1
        ProjectionEventLog.warning(
            "WIRE",
            "The dashboard rebooted $relative: its uptime went backwards, and the dashboard was the " +
                "one that ended that session. Reboot $counted/$REBOOTS_BEFORE_ADVANCE on wire rung " +
                "${progress.rungIndex} (${rung.signature}). " +
                when {
                    next.rungIndex != progress.rungIndex ->
                        "A firmware that reboots under a stream has not confirmed it, whatever the " +
                            "screen showed: the next session tries rung ${next.rungIndex} " +
                            "(${RUNGS.getOrElse(next.rungIndex) { RUNGS.first() }.signature})."
                    next.state == TBoxLadderState.EXHAUSTED ->
                        "Every wire this app knows has already been tried; staying on the default."
                    else -> "One more and the search moves to the next wire."
                }
        )
        return next
    }

    /**
     * Feeds a finished session back in. Returns the progress as it now stands, so the caller can
     * see whether the rider needs to be asked anything.
     */
    fun onSessionFinished(
        context: Context,
        motorcycle: MotorcycleProfile,
        modelProfile: TBoxModelProfile,
        facts: TBoxSessionFacts,
        nowMillis: Long = System.currentTimeMillis()
    ): TBoxLadderProgress {
        // The window is kept whatever the state, terminal ones included: it is what the next
        // CLIENT_INFO's uptime is read against, and a reboot is exactly the thing a CONFIRMED rung
        // has to answer for - see onDashboardIdentified.
        val progress = load(context, motorcycle).copy(
            lastSessionStartedAtMillis = nowMillis - facts.durationMillis,
            lastSessionEndedAtMillis = nowMillis,
            lastSessionEndedByDashboard = facts.endedByDashboard
        )
        if (modelProfile != TBoxModelProfile.GENERIC) {
            save(context, motorcycle, progress)
            return progress
        }
        // Both terminal states stop here, and EXHAUSTED for the harder-won reason. Without it the
        // walk is a ring, not a ladder: advance() lands an exhausted bike back on rung 0, the next
        // session streams there, STREAMED asks the rider the question they have already answered,
        // the denial advances 0 -> 1 -> 2 -> 3 -> exhausted, and it begins again. Rider bffd0679
        // rode that loop - "Staying on this rung (EXHAUSTED)" twice, then a fourth verdict on a
        // format denied days earlier - and it costs more than a repeated question: rung 0 is
        // all-intra, and on that dash all-intra is what makes the firmware drop its own AP, so the
        // ring re-breaks a link that had been holding. A dash whose firmware changes is still
        // re-walked; onDashboardIdentified resets the record on a new fingerprint.
        if (progress.state == TBoxLadderState.CONFIRMED ||
            progress.state == TBoxLadderState.EXHAUSTED
        ) {
            save(context, motorcycle, progress)
            return progress
        }

        val outcome = TBoxSessionOutcome.of(facts)
        val rung = RUNGS.getOrElse(progress.rungIndex) { RUNGS.first() }
        val next = nextProgress(progress, outcome)
        save(context, motorcycle, next)
        ProjectionEventLog.record(
            "WIRE",
            "Wire rung ${progress.rungIndex} (${rung.signature}) after ${facts.durationMillis}ms, " +
                "${facts.framesOffered} frames: $outcome. " +
                (facts.trouble?.let {
                    "The stream was not healthy for part of it ($it), so what the dashboard did " +
                        "with the picture says nothing about this wire and the rider is not asked. "
                } ?: "") +
                if (next.rungIndex != progress.rungIndex) {
                    "Next session tries rung ${next.rungIndex} " +
                        "(${RUNGS.getOrElse(next.rungIndex) { RUNGS.first() }.signature})."
                } else {
                    "Staying on this rung (${next.state})."
                }
        )
        return next
    }

    /**
     * The state machine, with no Android in it so it can be tested exhaustively: what one session's
     * verdict does to where this motorcycle stands.
     */
    internal fun nextProgress(
        progress: TBoxLadderProgress,
        outcome: TBoxSessionOutcome
    ): TBoxLadderProgress = when (outcome) {
        // Says nothing about the bytes: the dash either never asked for video or never got far
        // enough to have an opinion. Staying put is the whole point - see the enum.
        TBoxSessionOutcome.NEVER_NEGOTIATED,
        TBoxSessionOutcome.INCONCLUSIVE ->
            progress.copy(
                attemptsOnRung = progress.attemptsOnRung + 1,
                lastOutcome = outcome.name
            )

        TBoxSessionOutcome.REJECTED -> advance(progress, outcome)

        // The firmware took the stream. Whether the rider could SEE it is the one thing no
        // protocol event reports, so the ladder stops here and waits to be told.
        TBoxSessionOutcome.STREAMED ->
            progress.copy(
                state = TBoxLadderState.AWAITING_RIDER,
                attemptsOnRung = progress.attemptsOnRung + 1,
                lastOutcome = outcome.name
            )
    }

    /**
     * The rider half of the same machine, likewise pure.
     *
     * [catalogueRung] is what [TBoxWireCatalogue] says this dashboard's other riders confirmed,
     * passed in rather than looked up so the second-opinion rule can be tested without a table.
     */
    internal fun nextProgressAfterRider(
        progress: TBoxLadderProgress,
        projectionSeen: Boolean,
        catalogueRung: Int? = TBoxWireCatalogue.rungFor(progress.fingerprint)
    ): TBoxLadderProgress = when {
        // A "yes" is always worth recording: it is the one answer that ends the search for good,
        // and a rider who sees the picture after an exhausted walk has told us the walk was wrong.
        projectionSeen ->
            progress.copy(state = TBoxLadderState.CONFIRMED, lastOutcome = "RIDER_CONFIRMED")
        // A "no" on an already-exhausted ladder must not restart it - see onSessionFinished. The
        // guard there means this is normally unreachable; it holds for a verdict that was already
        // on screen when the walk ended.
        progress.state == TBoxLadderState.EXHAUSTED ->
            progress.copy(lastOutcome = "RIDER_DENIED")
        // A wire other riders with this same dashboard confirmed is worth asking twice about
        // before walking away from it. One "no" is what moved rider 738a2340 onto a rung his
        // dashboard shows as a green screen, and the session behind that "no" had been failing on
        // Wi-Fi the whole time. Two says the catalogue does not fit this bike, and the ladder
        // moves on exactly as before.
        catalogueRung == progress.rungIndex && progress.denialsOnRung < DENIALS_TO_LEAVE_CATALOGUE_RUNG - 1 ->
            progress.copy(
                state = TBoxLadderState.TRYING,
                denialsOnRung = progress.denialsOnRung + 1,
                lastOutcome = "RIDER_DENIED"
            )
        else -> advance(progress.copy(lastOutcome = "RIDER_DENIED"), null)
    }

    /**
     * Denials needed to leave a rung [TBoxWireCatalogue] vouches for. Two, not more: the catalogue
     * is other people's dashboards, and a rider who says no twice about their own is telling us
     * something the table cannot see.
     */
    internal const val DENIALS_TO_LEAVE_CATALOGUE_RUNG = 2

    /**
     * A session the ladder cannot read: it ran a video format the ladder did not choose.
     *
     * Only Android Auto is governed by the search. Ride Dashboard keeps its own format, decided
     * for its own reasons long before this existed, so treating one of its sessions as a verdict
     * would promote or condemn a rung that was never actually on the wire. Counted rather than
     * ignored so [needsAndroidAutoNudge] can explain the silence to a rider who only ever mirrors.
     */
    fun onSessionIgnored(context: Context, motorcycle: MotorcycleProfile, modelProfile: TBoxModelProfile) {
        if (modelProfile != TBoxModelProfile.GENERIC) return
        val progress = load(context, motorcycle)
        if (progress.state == TBoxLadderState.CONFIRMED || progress.state == TBoxLadderState.EXHAUSTED) return
        save(context, motorcycle, progress.copy(
            sessionsWithoutAndroidAuto = progress.sessionsWithoutAndroidAuto + 1
        ))
    }

    /**
     * Whether to tell this rider that the search needs one Android Auto session to move.
     *
     * Two mirroring sessions rather than one: a rider who happens to open Ride Dashboard once
     * before Android Auto has done nothing wrong and should not be nagged for it.
     */
    fun needsAndroidAutoNudge(context: Context, motorcycle: MotorcycleProfile): Boolean {
        val progress = load(context, motorcycle)
        return !progress.androidAutoNudgeShown &&
            progress.state == TBoxLadderState.TRYING &&
            progress.sessionsWithoutAndroidAuto >= NUDGE_AFTER_MIRRORING_SESSIONS
    }

    fun markAndroidAutoNudgeShown(context: Context, motorcycle: MotorcycleProfile) {
        val progress = load(context, motorcycle)
        save(context, motorcycle, progress.copy(androidAutoNudgeShown = true))
    }

    private const val NUDGE_AFTER_MIRRORING_SESSIONS = 2

    /** The rider's answer to "did the dashboard actually show it?". */
    fun onRiderVerdict(context: Context, motorcycle: MotorcycleProfile, projectionSeen: Boolean) {
        val progress = load(context, motorcycle)
        val rung = RUNGS.getOrElse(progress.rungIndex) { RUNGS.first() }
        val next = nextProgressAfterRider(progress, projectionSeen)
        ProjectionEventLog.record(
            "WIRE",
            when {
                projectionSeen ->
                    "Rider confirmed the dashboard is showing rung ${progress.rungIndex} " +
                        "(${rung.signature}); pinning it for this motorcycle."
                next.rungIndex == progress.rungIndex && next.state != TBoxLadderState.EXHAUSTED ->
                    "Rider reports rung ${progress.rungIndex} (${rung.signature}) streamed but " +
                        "showed nothing. Other riders with this dashboard confirmed this wire, so " +
                        "it is worth one more ride before moving on: staying on it, and asking " +
                        "again after the next Android Auto session."
                else ->
                    "Rider reports rung ${progress.rungIndex} (${rung.signature}) streamed but " +
                        "showed nothing; moving on."
            }
        )
        save(context, motorcycle, next)
    }

    /** Diagnostics and the support report: one line describing where this motorcycle stands. */
    fun describe(context: Context, motorcycle: MotorcycleProfile): String {
        val progress = load(context, motorcycle)
        val rung = RUNGS.getOrElse(progress.rungIndex) { RUNGS.first() }
        return "rung ${progress.rungIndex}/${RUNGS.lastIndex} ${rung.signature}, " +
            "${progress.state}, tried ${progress.attemptsOnRung}x" +
            (progress.lastOutcome?.let { ", last $it" } ?: "") +
            (if (progress.rebootsOnRung > 0) ", reboots ${progress.rebootsOnRung}" else "") +
            (if (progress.denialsOnRung > 0) ", denials ${progress.denialsOnRung}" else "") +
            (TBoxWireCatalogue.rungFor(progress.fingerprint)?.let { ", catalogue rung $it" } ?: "") +
            (progress.fingerprint?.let { ", dash $it" } ?: "")
    }

    private fun advance(progress: TBoxLadderProgress, outcome: TBoxSessionOutcome?): TBoxLadderProgress {
        val nextRung = progress.rungIndex + 1
        return if (nextRung > RUNGS.lastIndex) {
            // Every wire this app knows has been tried and none of them was the answer. Going back
            // to the default is not giving up on the rider, it is refusing to leave them on an
            // exotic format that also did not work: the support report carries the walk, and that
            // is what turns one rider's dead end into the next release's profile.
            progress.copy(
                rungIndex = 0,
                state = TBoxLadderState.EXHAUSTED,
                attemptsOnRung = 0,
                rebootsOnRung = 0,
                denialsOnRung = 0,
                lastOutcome = outcome?.name ?: progress.lastOutcome
            )
        } else {
            progress.copy(
                rungIndex = nextRung,
                state = TBoxLadderState.TRYING,
                attemptsOnRung = 0,
                rebootsOnRung = 0,
                denialsOnRung = 0,
                lastOutcome = outcome?.name ?: progress.lastOutcome
            )
        }
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}
