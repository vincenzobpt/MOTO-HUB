// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.session

import android.content.Context
import android.os.Build
import android.util.Log
import io.motohub.android.BuildConfig
import io.motohub.android.feature.settings.MotoHubSettings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LogLevel {
    DEBUG,
    INFO,
    WARNING,
    ERROR
}

/**
 * Guesses a level for a message written by code that has none (the ported AAP stack).
 *
 * Deliberately conservative about ERROR: a word matcher cannot tell a fault from a line that
 * merely counts faults, so anything reporting a zero count, or an ordinary socket teardown,
 * stays below ERROR. Callers must not report the result to telemetry either way - see
 * [ProjectionEventLog.external].
 *
 * Guessing is the fallback, not the first move. [io.motohub.android.aa.AaLog] routes every level
 * through `Log.i` and carries the author's own level as a `W: `/`E: ` text marker instead
 * ([io.motohub.android.aa.AaLog.w]) - so the whole ported AA receiver, the largest subsystem in
 * the app, could not produce a single WARNING or ERROR record while this function classified by
 * keyword alone. `W: AapVideo: Dropped Flag 11` came out WARNING by luck (it says "dropped");
 * `W: Decoder stall detected` came out INFO. Rider 4d8a4c5b's log (2026-08-26) has the video path
 * collapsing to 2fps with seven forced decoder restarts, and the console read it as zero errors,
 * zero warnings - invisible to the badges, the needs-attention filter and the cross-rider
 * grouping that exist to surface exactly that.
 *
 * The marker wins over the keywords, but the two rules below can still demote it: they encode
 * faults that were observed to be false, and an author's `E:` on a socket the rider just closed
 * is as wrong as a word matcher's.
 */
internal fun classifyExternalMessage(message: String): LogLevel {
    val text = message.lowercase()
    // "dropped: 0", "0 errors", "failures=0" - a counter at rest is the opposite of a fault,
    // and reading it as one is how a healthy session filled the log with red lines.
    val countsNothing = ZERO_COUNT_PATTERN.containsMatchIn(text)
    val teardown = text.contains("socket closed") || text.contains("stream closed") ||
        text.contains("ended: socket") || text.contains("interrupted")
    val declared = declaredLevelOf(text)
    return when {
        countsNothing -> LogLevel.INFO
        // A declared level answers every keyword rule below; only the demotions outrank it.
        declared != null -> if (teardown) minOf(declared, LogLevel.WARNING) else declared
        text.contains("timed out") || text.contains("timeout") -> LogLevel.ERROR
        // A teardown mentioning "failed"/"closed" during a normal stop is not a fault.
        teardown -> LogLevel.WARNING
        text.contains("failed") || text.contains("error") || text.contains("unable to") ->
            LogLevel.ERROR
        text.contains("warning") || text.contains("dropped") || text.contains("retry") ||
            text.contains("retrying") -> LogLevel.WARNING
        else -> LogLevel.INFO
    }
}

/**
 * The level the writer declared, if any: `W: `/`E: ` at the head of the message, after the
 * optional `[STAGE]` tag the log convention puts in front of it (`[AA] W: ...`).
 *
 * Anchored on purpose - a bare "e: " deep inside a stack trace or a hex dump declares nothing.
 */
private fun declaredLevelOf(lowercaseText: String): LogLevel? {
    // Only a leading tag is stripped: the first "] " of a message that does not open with one
    // belongs to its payload (a buffer dump, a protobuf), and nothing after it declares anything.
    val body = if (lowercaseText.startsWith("[")) {
        lowercaseText.substringAfter("] ", lowercaseText)
    } else {
        lowercaseText
    }
    return when {
        body.startsWith("w: ") -> LogLevel.WARNING
        body.startsWith("e: ") -> LogLevel.ERROR
        else -> null
    }
}

/** Matches "<word>: 0", "<word>=0" and "0 <word>" so a zero counter reads as the non-event it is. */
private val ZERO_COUNT_PATTERN =
    Regex("(?:dropped|failed|failures|errors|timeouts|rejections|rejected)\\s*[:=]\\s*0\\b|\\b0\\s+(?:dropped|failures|errors|timeouts)\\b")

/** The one entry that stands in for a finished run of identical lines. */
internal data class RepeatSummary(val source: String, val message: String, val level: LogLevel)

internal sealed interface RepeatDecision {
    /** The line repeats the open run; nothing is written and nothing is reported. */
    data object Folded : RepeatDecision

    /** Write the line, preceded by [closed] when a previous run ended on it. */
    data class Append(val closed: RepeatSummary?) : RepeatDecision
}

/**
 * Folds consecutive identical log lines into one entry plus a count.
 *
 * The log is a fixed ring, so a single talkative call site can erase everything else in it. A
 * rider chasing a Ride Dashboard that kept disconnecting sent two logs on 2026-07-31 in which
 * 1307 of 1600 entries were the one line "Prepended cached SPS/PPS to AVC keyframe" - written
 * once per frame at 30fps, which overwrites all 800 entries every 42 seconds. Neither log could
 * contain the failure he was reporting. That particular line is fixed at its source, but the
 * shape of the accident is not specific to it: the next per-frame line anyone adds does the same.
 *
 * Only CONSECUTIVE repeats fold, which is the honest limit of this. Two lines alternating
 * (A B A B) defeat it entirely and would need a per-message budget instead. This handles the
 * flood that actually happens - one hot call site inside a tight loop - for a string comparison.
 *
 * Free of Android types and of the ring itself, so the rule can be unit tested: this decides
 * what a rider's only diagnostic tool records, and a mistake here is invisible until it is the
 * thing preventing a diagnosis.
 */
internal class RepeatCollapser {
    private var source: String? = null
    private var message: String? = null
    private var level: LogLevel? = null
    private var folded = 0

    fun onLine(source: String, message: String, level: LogLevel): RepeatDecision {
        if (source == this.source && message == this.message && level == this.level) {
            folded++
            return RepeatDecision.Folded
        }
        val closed = close()
        this.source = source
        this.message = message
        this.level = level
        return RepeatDecision.Append(closed)
    }

    /**
     * Ends the open run, returning the entry that reports how many lines it swallowed.
     *
     * Emitted when the run ends rather than kept as a live counter on the last entry: the log
     * file is append-only, so a count that grew in place would have to rewrite something already
     * persisted. The cost is that a run still in progress shows only its first line until
     * something else is logged, which is why an export closes the run before reading.
     */
    fun close(): RepeatSummary? {
        val count = folded
        val runSource = source
        val runLevel = level
        reset()
        if (count <= 0 || runSource == null || runLevel == null) return null
        return RepeatSummary(
            runSource,
            "The line above repeated $count more time${if (count == 1) "" else "s"}.",
            runLevel
        )
    }

    fun reset() {
        source = null
        message = null
        level = null
        folded = 0
    }
}

data class ProjectionEvent(
    val timestampMillis: Long,
    val source: String,
    val message: String,
    val level: LogLevel = LogLevel.INFO,
    /**
     * In-memory only, never persisted: assigned fresh on every app run so it's
     * guaranteed unique within [ProjectionEventLog.events], unlike timestamp+message,
     * which collide easily (e.g. two identical "PXC event received" heartbeats logged
     * in the same millisecond) and crashed the log screen's LazyColumn on scroll when
     * used as its key.
     */
    val sequence: Long = 0
)

/**
 * Persistent application-wide diagnostic log exposed directly in the UI.
 *
 * **What each level means** - the contract every call site is expected to honour, because
 * telemetry and the log screen both read it as if it were true:
 *
 * - [debug] - per-event detail: protocol frames, touch samples, link measurements. High volume
 *   by nature; use the `() -> String` overload so nothing is formatted when logging is off.
 * - [record] (INFO) - state transitions worth reading a year from now: a session started, a
 *   profile was selected, a geometry was learned.
 * - [warning] - degraded but alive: something was retried, dropped, or fell back. The session
 *   continues.
 * - [error] - **fatal to the session, and reported to telemetry.** Not "an exception was
 *   caught": a caught exception the app recovered from is a [warning]. If a rider would not
 *   have noticed it, it is not an error.
 *
 * [external] exists for message streams this app does not author (the ported AAP stack), which
 * arrive as plain strings with no level at all. Those are classified by their wording, which is
 * a guess - so they are never reported to telemetry, however they end up being displayed.
 */
object ProjectionEventLog {
    // Oldest events drop automatically once this is exceeded (a ring buffer, not a manual
    // clear) - lowered from 2_500 after a very long/verbose session made the log screen heavy
    // enough to hang while scrolling.
    //
    // Raised from 800 to 1_500 once [RepeatCollapser] landed: the reason 800 felt short was a
    // per-frame line that spent the whole ring in 42 seconds, and that is now two entries
    // rather than seven hundred. Deliberately still well under the 2_500 that broke the log
    // screen - headroom is worth having, a hung screen is not.
    private const val MAX_EVENTS = 1500
    private const val FILE_NAME = "moto-hub-diagnostics.log"
    private val lock = Any()

    /**
     * The authoritative event store, guarded by [lock]. An ArrayDeque ring instead of the
     * immutable list it used to be: `mutableEvents.value + event` copied all 800 elements on
     * EVERY log call, and the callers include the hottest paths in the app - the T-Box event
     * callback logs one debug line per MEDIA_CONTROL event, touch moves included, so a drag on
     * the TFT paid a full list copy plus a synchronous file write per sample, on the transport's
     * own callback thread. [mutableEvents] is now a throttled SNAPSHOT of this ring, published
     * by the writer thread at [FLUSH_DELAY_MS] cadence; [exportText] reads the ring directly, so
     * exports and the IPC snapshot stay complete and current regardless of the throttle.
     */
    private val ring = ArrayDeque<ProjectionEvent>(MAX_EVENTS)

    /** Encoded lines waiting for the writer thread; guarded by [lock], swapped at flush. */
    private var pendingLines = StringBuilder()

    /** Folds a run of identical lines into one entry plus a count; guarded by [lock]. */
    private val collapser = RepeatCollapser()
    private val flushScheduled = java.util.concurrent.atomic.AtomicBoolean(false)
    private val writer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "MotoHubLogWriter").apply { isDaemon = true }
    }

    private val mutableEvents = MutableStateFlow<List<ProjectionEvent>>(emptyList())
    val events: StateFlow<List<ProjectionEvent>> = mutableEvents.asStateFlow()
    private var logFile: File? = null
    private var appContext: Context? = null
    private val sequenceCounter = java.util.concurrent.atomic.AtomicLong(0)

    fun initialize(context: Context) {
        appContext = context.applicationContext
        synchronized(lock) {
            if (logFile != null) return
            logFile = File(context.applicationContext.filesDir, FILE_NAME)
            val restored = runCatching {
                logFile?.takeIf(File::exists)
                    ?.readLines(Charsets.UTF_8)
                    ?.mapNotNull(::decodeLine)
                    ?.takeLast(MAX_EVENTS)
                    .orEmpty()
            }.getOrElse { emptyList() }
                .map { it.copy(sequence = sequenceCounter.incrementAndGet()) }
            ring.clear()
            ring.addAll(restored)
            mutableEvents.value = restored
        }
        record(
            source = "APP",
            message = "Process started: MOTO-HUB ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), " +
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
                "${Build.MANUFACTURER} ${Build.MODEL}."
        )
    }

    /**
     * True when anything at all is being recorded. Public so hot call sites can skip building
     * a message they would only throw away; cached in [MotoHubSettings], so it is cheap enough
     * to call per protocol event.
     */
    fun isLoggingEnabled(): Boolean =
        appContext?.let(MotoHubSettings::loggingEnabled) ?: true

    /**
     * When the newest entry in the ring was recorded, or null when the log is empty.
     *
     * Only interesting while logging is OFF, where it is the moment the log stopped being an
     * account of what the app is doing. Turning the switch off stops new entries and deliberately
     * keeps the old ones - a rider who wanted the history gone would clear it, and losing a
     * week of evidence to a switch flipped for a minute would be the worse mistake - so what is
     * left still reads like a complete log while being a fossil. Rider 94c456e9 sent a report
     * whose ADVANCED half ended 21 days and 61 builds before the report itself, with nothing in
     * the file saying so; this is what lets the app say it out loud before the report goes.
     */
    fun lastEntryAtMillis(): Long? = synchronized(lock) { ring.lastOrNull()?.timestampMillis }

    fun record(
        source: String,
        message: String,
        level: LogLevel = LogLevel.INFO,
        throwable: Throwable? = null,
        /**
         * Whether an ERROR here is worth waking someone up for. False for levels that were
         * GUESSED from wording rather than chosen by a call site - see [external].
         */
        reportToTelemetry: Boolean = true
    ) {
        // Master switch (Settings > Diagnostics > Enable logging): when off, nothing is
        // written anywhere - not Logcat, not memory, not the log file - not just the
        // verbose extras. appContext is only null for the instant before initialize()
        // runs, which never calls record(); defaulting to enabled there is unreachable
        // in practice but keeps this fail-open rather than silently swallowing events.
        if (!isLoggingEnabled()) return
        val detail = redact(buildString {
            append(message)
            if (throwable != null) {
                append("\n")
                append(Log.getStackTraceString(throwable))
            }
        })
        // Cheap by design: ring append + trim and an encoded pending line. The list copy for
        // the UI and the file write both happen on the writer thread at flush time - never on
        // the caller's thread, which can be the T-Box callback delivering a touch.
        if (!synchronized(lock) { appendOrCollapseLocked(source, detail, level) }) return
        if (level == LogLevel.ERROR && reportToTelemetry) {
            SentryIntegration.captureDiagnosticError(source, detail, hasThrowable = throwable != null)
        }
        when (level) {
            LogLevel.DEBUG -> Log.d(LOG_TAG, "$source: $detail")
            LogLevel.INFO -> Log.i(LOG_TAG, "$source: $detail")
            LogLevel.WARNING -> Log.w(LOG_TAG, "$source: $detail")
            LogLevel.ERROR -> Log.e(LOG_TAG, "$source: $detail")
        }
        scheduleFlush()
    }

    /**
     * Appends [detail], or swallows it when it merely repeats the line already at the tail.
     *
     * @return true when a new entry was appended, so the caller logs and reports it. False
     *   means it was collapsed: deliberately, a run of identical ERRORs also reports to
     *   telemetry once rather than fifty times.
     */
    private fun appendOrCollapseLocked(source: String, detail: String, level: LogLevel): Boolean {
        val decision = collapser.onLine(source, detail, level)
        if (decision !is RepeatDecision.Append) return false
        decision.closed?.let { appendLocked(it.source, it.message, it.level) }
        appendLocked(source, detail, level)
        return true
    }

    private fun closeRepeatRunLocked() {
        collapser.close()?.let { appendLocked(it.source, it.message, it.level) }
    }

    private fun appendLocked(source: String, detail: String, level: LogLevel) {
        val event = ProjectionEvent(
            System.currentTimeMillis(),
            source,
            detail,
            level,
            sequenceCounter.incrementAndGet()
        )
        ring.addLast(event)
        if (ring.size > MAX_EVENTS) ring.removeFirst()
        pendingLines.append(encodeLine(event)).append('\n')
    }

    /** Batches file writes and snapshot publication; one task per [FLUSH_DELAY_MS] window. */
    private fun scheduleFlush() {
        if (!flushScheduled.compareAndSet(false, true)) return
        runCatching {
            writer.schedule(::flush, FLUSH_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        }.onFailure { flushScheduled.set(false) }
    }

    private fun flush() {
        // Reopen the gate BEFORE swapping: an event recorded after the swap below must be able
        // to schedule the next window even while this one is still writing.
        flushScheduled.set(false)
        val lines: String
        val snapshot: List<ProjectionEvent>
        val file: File?
        synchronized(lock) {
            lines = pendingLines.toString()
            if (pendingLines.isNotEmpty()) pendingLines = StringBuilder()
            snapshot = ring.toList()
            file = logFile
        }
        mutableEvents.value = snapshot
        if (file == null || lines.isEmpty()) return
        runCatching {
            if (snapshot.size == MAX_EVENTS && file.length() > MAX_FILE_BYTES) {
                rewrite(file, snapshot)
            } else {
                file.appendText(lines, Charsets.UTF_8)
            }
        }.onFailure { Log.e(LOG_TAG, "Unable to persist diagnostic log", it) }
    }

    /**
     * Empties the ring, the pending buffer and the log file.
     *
     * [reason] is the first line of the fresh log: a log screen that goes blank with no
     * explanation reads as a fault, and the caller is not always the rider - the diagnostics
     * collector clears the log once the server has confirmed it holds these lines.
     */
    fun clear(reason: String = "Diagnostic log cleared by the user.") {
        synchronized(lock) {
            ring.clear()
            pendingLines = StringBuilder()
            // Forget the open run too: its summary would otherwise land in an empty log and
            // count lines the rider just asked to be rid of.
            collapser.reset()
            mutableEvents.value = emptyList()
        }
        // On the writer thread so it cannot interleave with a flush already in progress.
        runCatching { writer.execute { logFile?.runCatching { writeText("", Charsets.UTF_8) } } }
        record("LOG", reason)
    }

    fun debug(source: String, message: String) = record(source, message, LogLevel.DEBUG)

    /**
     * Per-event debug whose message is only built when logging is on. The hot callers - the
     * T-Box protocol callback, touch normalisation - used to format their string first and
     * discard it inside [record], paying for a log nobody asked for.
     */
    inline fun debug(source: String, message: () -> String) {
        if (!isLoggingEnabled()) return
        debug(source, message())
    }

    fun warning(source: String, message: String, throwable: Throwable? = null) =
        record(source, message, LogLevel.WARNING, throwable)

    fun error(source: String, message: String, throwable: Throwable? = null) =
        record(source, message, LogLevel.ERROR, throwable)

    /**
     * Records facts about the state of the world rather than an event: what the radio could see,
     * what band it was on. They belong on every later report instead of on a line of their own,
     * so they ride along as tags rather than as log entries.
     *
     * Behind this funnel because [SentryIntegration] has exactly one caller in the app, and gated
     * by the same master switch as [record]: a rider who turned logging off has turned telemetry
     * off too, and that has to stay true for facts as much as for events.
     */
    fun setTelemetryFacts(facts: Map<String, String>) {
        if (!isLoggingEnabled()) return
        SentryIntegration.setDiagnosticTags(facts)
    }

    /**
     * Records a message from a stream this app does not author - the ported AAP stack hands
     * out plain strings with no level - by GUESSING a level from its wording.
     *
     * Never reported to telemetry, precisely because it is a guess: "Frames dropped: 0" and
     * "head unit server poll ended: Socket closed" both read as failures to a word matcher,
     * and every one of them used to raise a Sentry event indistinguishable from a real fault.
     * The guess is still good enough to colour the log screen, which is all it is for.
     */
    fun external(source: String, message: String) =
        record(source, message, classifyExternalMessage(message), reportToTelemetry = false)

    fun exportText(): String {
        // From the ring, not the throttled StateFlow snapshot: an export (share sheet, the
        // companion's IPC log mirror) must contain everything recorded up to this instant -
        // including the tail of a run of repeats that has not ended yet, which is why the run
        // is closed here first.
        val snapshot = synchronized(lock) {
            closeRepeatRunLocked()
            ring.toList()
        }
        return buildString {
            appendLine("MOTO-HUB diagnostics")
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Entries: ${snapshot.size}")
            appendLine("Generated: ${formatTime(System.currentTimeMillis())}")
            appendLine("----------------------------------------")
            snapshot.forEach { event ->
                append(formatTime(event.timestampMillis))
                append("  ")
                append(event.level.name.padEnd(7))
                append("  ")
                append(event.source)
                append(": ")
                appendLine(event.message)
            }
        }
    }

    private fun rewrite(file: File, events: List<ProjectionEvent>) {
        file.writeText(events.joinToString(separator = "\n", postfix = "\n", transform = ::encodeLine))
    }

    private fun encodeLine(event: ProjectionEvent): String = listOf(
        event.timestampMillis.toString(),
        event.level.name,
        encode(event.source),
        encode(event.message)
    ).joinToString("\t")

    private fun decodeLine(line: String): ProjectionEvent? = runCatching {
        val fields = line.split('\t', limit = 4)
        if (fields.size != 4) return@runCatching null
        ProjectionEvent(
            timestampMillis = fields[0].toLong(),
            level = LogLevel.valueOf(fields[1]),
            source = decode(fields[2]),
            message = decode(fields[3])
        )
    }.getOrNull()

    private fun encode(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decode(value: String): String =
        String(Base64.getDecoder().decode(value), Charsets.UTF_8)

    internal fun redact(value: String): String = value
        .replace(SECRET_PATTERN, "$1=<redacted>")
        .replace(BEARER_TOKEN_PATTERN, "Bearer <redacted>")
        .replace(API_KEY_LITERAL_PATTERN, "<redacted-key>")
        .replace(MAC_ADDRESS_PATTERN, "<redacted-mac>")
        .redactAddressesSparingVersions()
        .take(MAX_MESSAGE_CHARS)

    /**
     * Redacts IPv4 addresses without eating four-part version numbers.
     *
     * A version like `1.0.13.1` is a valid IPv4 address as far as any pattern can tell, and the
     * dashboard identity line is made of exactly those: field log 90438e1e (2026-08-25) reads
     * `Unrecognised dashboard: package=?, version=V0.0.1(?), sdk=<redacted-ip>` - the one line
     * whose whole purpose is to say which firmware this is, with the firmware redacted out of it.
     * The version had to be recovered from the raw CLIENT_INFO hex dump further up the same log.
     *
     * So a quad introduced by a version-ish key is held back from the address pass and put back
     * afterwards. Nothing else changes: an address anywhere else in the message, keyed or bare, is
     * still replaced, and a key nobody thought of is redacted rather than published - the rule
     * only spares what it can name.
     */
    private fun String.redactAddressesSparingVersions(): String {
        val spared = mutableListOf<String>()
        val held = VERSION_QUAD_PATTERN.replace(this) { match ->
            spared += match.groupValues[3]
            "${match.groupValues[1]}${match.groupValues[2]}$VERSION_PLACEHOLDER"
        }
        val redacted = held.replace(IPV4_ADDRESS_PATTERN, "<redacted-ip>")
        if (spared.isEmpty()) return redacted
        var index = 0
        return redacted.replace(VERSION_PLACEHOLDER) { spared.getOrElse(index++) { "" } }
    }

    private fun String.replace(literal: String, next: () -> String): String {
        val builder = StringBuilder(length)
        var from = 0
        while (true) {
            val at = indexOf(literal, from)
            if (at < 0) {
                builder.append(this, from, length)
                return builder.toString()
            }
            builder.append(this, from, at).append(next())
            from = at + literal.length
        }
    }

    private fun formatTime(timestampMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(timestampMillis))

    private const val LOG_TAG = "MotoHubProjection"
    /**
     * Batching window for the writer thread: one file write and one UI snapshot per window
     * instead of one per event. Short enough that the log screen still reads as live and a
     * crash loses at most a quarter-second of tail; the logcat mirror in [record] is
     * synchronous anyway, so nothing is lost for adb-attached debugging.
     */
    private const val FLUSH_DELAY_MS = 250L
    private const val MAX_FILE_BYTES = 2L * 1024L * 1024L
    // A single event could reach this size (e.g. a raw CLIENT_INFO JSON dump under verbose
    // T-Box logging) - 16_000 let one entry's Text composable choke the log screen's layout
    // pass badly enough to hang while scrolling. 2_000 is still ample to read a JSON blob or
    // stack trace; anything genuinely longer is truncated rather than rendered whole.
    private const val MAX_MESSAGE_CHARS = 2_000
    // Quote-tolerant so this also catches quoted-JSON shapes like "btPin": "1234" or
    // "pwd":"1234", not just bare key=value/key: value - needed now that verbose T-Box
    // logging (Settings > Diagnostics) can log a raw CLIENT_INFO JSON blob, which carries
    // btPin among other fields.
    // huid/uuid joined when verbose T-Box logging became the default: the raw CLIENT_INFO dump
    // carries both, they identify the dashboard hardware forever, and logs get pasted into
    // public Discord threads. Redacting them here is what made the default flip safe.
    // api_key/authorization/token joined the list when the AI assistant landed in the companion
    // edition: the rider enters a provider key that, unlike the T-Box credentials, is worth real
    // money if it leaks through a shared diagnostic log. Kept identical in both editions on
    // purpose - this file is shared, and a redaction rule that exists in only one of them is a
    // trap the next person to move code between them will fall into.
    private val SECRET_PATTERN = Regex(
        "(?i)\"?(password|pwd|passphrase|psk|btpin|bt_pin|api_?key|api-key|authorization|" +
            "access_?token|refresh_?token|token|secret|huid|uuid)\"?\\s*[:=]\\s*\"?[^\\s,;\"]+\"?"
    )
    // SECRET_PATTERN stops the value at the first space, so "Authorization: Bearer sk-..." would
    // otherwise redact only the word "Bearer" and leave the token itself in the clear.
    private val BEARER_TOKEN_PATTERN = Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]{8,}")
    // Last resort for a key pasted somewhere with no surrounding label at all. Covers the
    // sk-/sk-proj-/sk-or- prefixes shared by OpenAI and the compatible providers.
    private val API_KEY_LITERAL_PATTERN = Regex("\\bsk-[A-Za-z0-9_-]{12,}\\b")
    // Catches MAC addresses and literal IPv4 addresses wherever they surface in a message
    // or exception text (e.g. "failed to connect to /192.168.49.1"), not just at known
    // call sites - ARCHITECTURE.md commits to replacing IP/MAC values with placeholders
    // in the exported log.
    private val MAC_ADDRESS_PATTERN = Regex(
        "\\b[0-9A-Fa-f]{2}(?:[:-][0-9A-Fa-f]{2}){5}\\b"
    )
    private val IPV4_ADDRESS_PATTERN = Regex(
        "\\b(?:(?:25[0-5]|2[0-4]\\d|[01]?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d?\\d)\\b"
    )
    /**
     * A four-part number introduced by a key that can only be announcing a version. The keys are
     * the ones this app actually logs (its own lines and the dashboard's CLIENT_INFO fields);
     * anything else keeps being treated as an address.
     */
    private val VERSION_QUAD_PATTERN = Regex(
        "(?i)\\b(sdk|sdkversion|version|versionname|versioncode|firmware|pxc|pxcversion|" +
            "release|build)(\"?\\s*[:=]\\s*\"?v?)((?:\\d{1,3}\\.){3}\\d{1,3})\\b"
    )
    /** Cannot occur in a log line: the redaction runs before anything else could write it. */
    private const val VERSION_PLACEHOLDER = "\u0000version\u0000"
}
