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
    val androidAutoNudgeShown: Boolean = false
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
        return when (record) {
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
    }

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
                androidAutoNudgeShown = json.optBoolean("nudged", false)
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
        preferences(context).edit().putString(keyFor(motorcycle), json.toString()).apply()
    }

    /**
     * Called once CLIENT_INFO has been read. A dashboard that is not the one the ladder has been
     * walking against invalidates everything learned so far - better a fresh walk than a verdict
     * inherited from other firmware.
     */
    fun onDashboardIdentified(
        context: Context,
        motorcycle: MotorcycleProfile,
        capabilities: TBoxCapabilities?
    ) {
        val fingerprint = fingerprintOf(capabilities) ?: return
        val progress = load(context, motorcycle)
        if (progress.fingerprint == null) {
            save(context, motorcycle, progress.copy(fingerprint = fingerprint))
            return
        }
        if (progress.fingerprint == fingerprint) return
        ProjectionEventLog.record(
            "WIRE",
            "A different dashboard answered on this motorcycle (was ${progress.fingerprint}, " +
                "now $fingerprint); restarting the wire search from the default."
        )
        save(context, motorcycle, TBoxLadderProgress(fingerprint = fingerprint))
    }

    /**
     * Feeds a finished session back in. Returns the progress as it now stands, so the caller can
     * see whether the rider needs to be asked anything.
     */
    fun onSessionFinished(
        context: Context,
        motorcycle: MotorcycleProfile,
        modelProfile: TBoxModelProfile,
        facts: TBoxSessionFacts
    ): TBoxLadderProgress {
        val progress = load(context, motorcycle)
        if (modelProfile != TBoxModelProfile.GENERIC) return progress
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

    /** The rider half of the same machine, likewise pure. */
    internal fun nextProgressAfterRider(
        progress: TBoxLadderProgress,
        projectionSeen: Boolean
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
        else -> advance(progress.copy(lastOutcome = "RIDER_DENIED"), null)
    }

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
        ProjectionEventLog.record(
            "WIRE",
            if (projectionSeen) {
                "Rider confirmed the dashboard is showing rung ${progress.rungIndex} " +
                    "(${rung.signature}); pinning it for this motorcycle."
            } else {
                "Rider reports rung ${progress.rungIndex} (${rung.signature}) streamed but showed " +
                    "nothing; moving on."
            }
        )
        val next = nextProgressAfterRider(progress, projectionSeen)
        save(context, motorcycle, next)
    }

    /** Diagnostics and the support report: one line describing where this motorcycle stands. */
    fun describe(context: Context, motorcycle: MotorcycleProfile): String {
        val progress = load(context, motorcycle)
        val rung = RUNGS.getOrElse(progress.rungIndex) { RUNGS.first() }
        return "rung ${progress.rungIndex}/${RUNGS.lastIndex} ${rung.signature}, " +
            "${progress.state}, tried ${progress.attemptsOnRung}x" +
            (progress.lastOutcome?.let { ", last $it" } ?: "") +
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
                lastOutcome = outcome?.name ?: progress.lastOutcome
            )
        } else {
            progress.copy(
                rungIndex = nextRung,
                state = TBoxLadderState.TRYING,
                attemptsOnRung = 0,
                lastOutcome = outcome?.name ?: progress.lastOutcome
            )
        }
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}
