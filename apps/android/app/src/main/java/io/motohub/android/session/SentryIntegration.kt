// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.session

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.motohub.android.BuildConfig
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid
import java.util.concurrent.atomic.AtomicInteger

/**
 * Centralized Sentry setup for CORE.
 *
 * The DSN is supplied by the build configuration, so public source builds can omit telemetry
 * while release/CI builds provide it through the ignored private properties file or environment.
 * Diagnostic errors are sent as already-redacted messages rather than raw Throwable objects: the
 * in-app log can contain connection details, and the application must never upload those values.
 */
object SentryIntegration {
    private const val LOG_TAG = "MotoHubSentry"
    private const val MAX_DIAGNOSTIC_EVENTS_PER_PROCESS = 50

    /**
     * Per-install ceiling for one day, kept in a preference so it survives the process.
     *
     * The per-process cap alone did nothing. A rider whose dash will not join restarts the app
     * five times in a car park and pays that cap five times over, and 500 riders doing it emptied
     * the organisation's error quota on 2026-08-12: 30,137 events in the window, of which about
     * 150 were real crashes. The rest were these reports, retried for the length of a ride.
     */
    private const val MAX_DIAGNOSTIC_EVENTS_PER_DAY = 5

    private const val BUDGET_PREFS = "motohub_telemetry_budget"
    private const val KEY_BUDGET_DAY = "day"
    private const val KEY_BUDGET_SENT = "sent"
    private const val KEY_BUDGET_SEEN = "seen"
    private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

    private val diagnosticEventsSent = AtomicInteger(0)
    private val budgetLock = Any()
    @Volatile private var budgetPrefs: SharedPreferences? = null
    @Volatile private var enabled = false

    fun initialize(context: Context) {
        val dsn = BuildConfig.SENTRY_DSN.trim()
        if (dsn.isEmpty()) return

        runCatching {
            SentryAndroid.init(context.applicationContext) { options ->
                options.dsn = dsn
                options.environment = "core"
                options.release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}"
                options.dist = BuildConfig.VERSION_CODE.toString()
                options.isSendDefaultPii = false
                options.isEnableAutoSessionTracking = true
                options.isDebug = BuildConfig.SENTRY_DEBUG
            }
            budgetPrefs = context.applicationContext
                .getSharedPreferences(BUDGET_PREFS, Context.MODE_PRIVATE)
            enabled = true
        }.onFailure { failure ->
            Log.e(LOG_TAG, "Sentry initialization failed; continuing without telemetry", failure)
        }
    }

    /**
     * The SDK's own per-install id, or null without telemetry. The Android SDK generates it into
     * `.sentry-installation` under filesDir and publishes it as the options' distinct id; the file
     * is the fallback in case a future SDK stops doing the latter.
     */
    fun sdkInstallationId(context: Context): String? {
        if (!enabled) return null
        return runCatching { Sentry.getCurrentScopes().options.distinctId }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: runCatching {
                java.io.File(context.applicationContext.filesDir, ".sentry-installation")
                    .takeIf { it.isFile }?.readText(Charsets.UTF_8)?.trim()?.takeIf { it.isNotEmpty() }
            }.getOrNull()
    }

    /**
     * Sends a bounded, redacted diagnostic event without changing the local log behavior.
     *
     * Only [isUnexpectedDiagnostic] reports go out. On 2026-11-11 over 99% of the events were
     * expected connection failures (dash off, hotspot down, Wi-Fi refused) from hundreds of phones,
     * and they emptied the monthly quota in four days. The diagnostics collector already gets
     * those with the whole log; Sentry keeps the reports that point at a bug.
     */
    fun captureDiagnosticError(source: String, message: String, hasThrowable: Boolean) {
        if (!enabled) return
        if (!isUnexpectedDiagnostic(source, message, hasThrowable)) return
        val fingerprint = fingerprintOf(source, message)
        if (!admits(fingerprint)) return

        runCatching {
            Sentry.withScope { scope ->
                scope.setTag("motohub.source", source)
                scope.setTag("motohub.edition", "core")
                scope.fingerprint = fingerprint
                Sentry.captureMessage(message, SentryLevel.ERROR)
            }
        }.onFailure { failure ->
            Log.w(LOG_TAG, "Unable to send diagnostic event", failure)
        }
    }

    /**
     * Attaches low-cardinality facts to every event this process sends from here on.
     *
     * Tags rather than a context, because only tags can be grouped and counted across the fleet -
     * and that is the whole reason these exist. The most useful diagnostic the app writes, what
     * the Wi-Fi scan could see in the moment before a join, is a warning, so it never left the
     * phone: 109 riders hit "no network granted" in four days and not one of those reports could
     * say whether the dash was in the air at all.
     *
     * Values must stay coarse. A tag carrying a rider's exact RSSI is a new tag value per rider,
     * which costs Sentry's indexing and answers nothing.
     */
    fun setDiagnosticTags(tags: Map<String, String>) {
        if (!enabled) return
        runCatching {
            Sentry.configureScope { scope ->
                tags.forEach { (key, value) -> scope.setTag(key, value) }
            }
        }.onFailure { failure ->
            Log.w(LOG_TAG, "Unable to attach diagnostic tags", failure)
        }
    }

    /**
     * Decides whether this report is worth one of the day's slots: each report once per day per
     * installation, and at most [MAX_DIAGNOSTIC_EVENTS_PER_DAY] a day. Sentry's user count then
     * says how many riders hit it; the collector has the repeats.
     *
     * Crashes do not pass through here. They reach Sentry from the SDK's own uncaught-exception
     * handler, and nothing in this class may ever throttle those: the whole point of cutting the
     * diagnostic volume is that the crashes get through.
     */
    private fun admits(fingerprint: List<String>): Boolean {
        if (diagnosticEventsSent.incrementAndGet() > MAX_DIAGNOSTIC_EVENTS_PER_PROCESS) return false
        return claimDailySlot(fingerprint.joinToString("\u0000").hashCode().toString())
    }

    /**
     * Takes one slot out of today's budget for [key], rolling the counters over on a new day.
     *
     * Fails open when [initialize] has not stored the preferences yet: at that point the SDK is
     * not sending anything anyway, and the per-process cap still applies.
     */
    private fun claimDailySlot(key: String): Boolean {
        val prefs = budgetPrefs ?: return true
        return synchronized(budgetLock) {
            val today = System.currentTimeMillis() / MILLIS_PER_DAY
            val sameDay = prefs.getLong(KEY_BUDGET_DAY, Long.MIN_VALUE) == today
            val sentToday = if (sameDay) prefs.getInt(KEY_BUDGET_SENT, 0) else 0
            val seenToday = if (sameDay) prefs.getStringSet(KEY_BUDGET_SEEN, null).orEmpty() else emptySet()
            if (!admitsToday(key, seenToday, sentToday, MAX_DIAGNOSTIC_EVENTS_PER_DAY)) {
                false
            } else {
                prefs.edit()
                    .putLong(KEY_BUDGET_DAY, today)
                    .putInt(KEY_BUDGET_SENT, sentToday + 1)
                    .putStringSet(KEY_BUDGET_SEEN, seenToday + key)
                    .apply()
                true
            }
        }
    }

    /**
     * Groups a report by what it SAYS rather than by the numbers and the frame names in it.
     *
     * Two things were splitting one report into dozens of issues. Elapsed times land in the
     * message - one rider's "unavailable 727144ms" and another's "48772ms" are the same event -
     * and the appended stack trace is obfuscated per build, so `zZ.c` and `qZ.c` are the same
     * frame from two releases. Split that way neither the user counts nor [setDiagnosticTags]
     * mean anything. Only the first two lines are kept: the message the call site chose, and the
     * exception line under it. The SSID in that second line still separates one bike from
     * another, which is deliberate - it is the difference between one dash that is off and a
     * model that cannot be joined.
     */
    private fun fingerprintOf(source: String, message: String): List<String> = listOf(
        source,
        message.lineSequence().take(2).joinToString("\n").replace(NUMBERS, "#")
    )

    private val NUMBERS = Regex("\\d+")
}

/**
 * Whether a logged ERROR points at a bug rather than at the world: an exception was attached, the
 * previous process died ([CrashRecovery]), or the streaming library gave up from inside. Connection
 * failures without an exception (dash off, hotspot down, Wi-Fi refused) are expected states.
 */
internal fun isUnexpectedDiagnostic(source: String, message: String, hasThrowable: Boolean): Boolean =
    hasThrowable || source == "CRASH" || message.startsWith("RideDaemon fatal callback")

/** One report per [key] per day, and no more than [cap] reports a day in all. */
internal fun admitsToday(key: String, seenToday: Set<String>, sentToday: Int, cap: Int): Boolean =
    key !in seenToday && sentToday < cap
