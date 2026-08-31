// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.androidauto

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * The one prerequisite MOTO-HUB cannot check or set for the rider.
 *
 * Lives in src/main, not src/core, because the ADVANCED home screen reads [RiderStep] to draw the
 * step the rider has to carry out - and src/pro cannot see src/core. Everything it touches is
 * framework, so both flavours can carry it; core-publish keeps it in src/main for the same reason.
 *
 * Google Android Auto only projects to a head unit it is willing to accept, and a sideloaded one
 * counts as an unknown car: until "Add new cars to Android Auto" (older wording: "Unknown
 * sources") is enabled inside Android Auto's own developer settings, the projection request is
 * simply ignored — the app is asked to start and silently does nothing, which is exactly what a
 * refusal looks like from here. There is no public API to read or toggle it, so all that can be
 * done is name the step and deep-link to the screen.
 */
object AndroidAutoSelfModeHelp {
    private const val GEARHEAD_PKG = "com.google.android.projection.gearhead"
    private const val SETTINGS_ACTIVITY =
        "com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity"

    /**
     * Android Auto releases from this one on have removed loopback ("self mode") projection:
     * WirelessStartupActivity is no longer exported and WirelessStartupReceiver ships disabled,
     * so every way an app can ask for it is closed. 17.2.662634 was verified working with
     * MOTO-HUB; 17.4.663004 fails on every entry point (the headunit-revived project hit the
     * same wall in its issue #698). 17.3 is untested, hence the boundary sits at 17.3.
     *
     * The boundary is a warning, never a refusal, and this is why: a rider log of 2026-07-31
     * (OnePlus CPH2653) running that same 17.2.662634 had WirelessStartupActivity refused as not
     * exported. So the version alone does not decide it - Google gates this per device and per
     * rollout - and what actually separates the two failures is whether an entry point ACCEPTED
     * the intent. See [ACCEPTED_BUT_SILENT_MESSAGE]. The boundary is left where it is rather than
     * widened to 17.2: it would then fire for every rider on a release that does work for most
     * of them, to say something the attempt itself reports a few seconds later anyway.
     */
    private const val FIRST_BROKEN_MAJOR = 17
    private const val FIRST_BROKEN_MINOR = 3

    /**
     * Shown when the receiver never saw an inbound connection.
     *
     * Leads with the head unit server because it is the path confirmed working on the releases
     * that removed self-mode, and it needs nothing but Android Auto's own menu — no sideloaded
     * APK. MOTO-HUB keeps polling for it, so the rider can start it without restarting anything.
     */
    const val NEVER_CONNECTED_MESSAGE =
        "Google Android Auto never connected to MOTO-HUB. Newer Android Auto releases removed the " +
            "way apps ask it to project, so start it from Android Auto itself: open Android Auto ▸ " +
            "tap Version ten times ▸ Developer settings ▸ the ⋮ menu at the top right ▸ \"Start head " +
            "unit server\". Leave MOTO-HUB running: it connects on its own within a couple of " +
            "seconds, and you can leave the server running for next time."

    /**
     * Shown instead of [NEVER_CONNECTED_MESSAGE] when Android Auto *took* the request and then did
     * nothing, on a release that still has self-mode.
     *
     * The two failures look identical to the rider and have different remedies. A refusal at the
     * intent ("not exported") is the release having closed self-mode, and only the head unit server
     * is left. An accepted intent followed by silence is Android Auto declining to project to a
     * head unit it does not trust, which is the "Add new cars" switch and nothing else - and the
     * rider was being sent to the head unit server for it, which does not fix that. Field log
     * 2026-07-31 (OnePlus CPH2653) on 17.2.662634, a release this file calls verified working:
     * one refusal, three acceptances, total silence.
     *
     * That reading only holds while self-mode is open. Once [isKnownBrokenVersion] is true, use
     * [ACCEPTED_BUT_SILENT_ON_CLOSED_RELEASE_MESSAGE] instead - pick with
     * [acceptedButSilentMessage] rather than by hand.
     */
    const val ACCEPTED_BUT_SILENT_MESSAGE =
        "Google Android Auto took MOTO-HUB's request and then ignored it. That is what it does " +
            "with a head unit it has not been told to trust: open Android Auto ▸ tap Version ten " +
            "times ▸ Developer settings ▸ turn on \"Add new cars to Android Auto\" (older builds " +
            "call it \"Unknown sources\"), then start Android Auto from MOTO-HUB again. If it " +
            "still does nothing, use the ⋮ menu on that same screen ▸ \"Start head unit server\"."

    /**
     * The same acceptance-then-silence, on a release that has closed self-mode
     * ([isKnownBrokenVersion]) - where it says nothing at all about the "Add new cars" switch.
     *
     * On 17.4 the component that accepts is WirelessSetupSharedService, and it does nothing
     * without WPP pairing data in its own datastore, which only Google's QR flow writes. So the
     * acceptance is not a trust decision being made about MOTO-HUB; it is an intent falling into
     * a component that was never going to act on it. Field case FF3D-A418 (2026-08-29, Android
     * Auto 17.4.663054 on Android 16): the rider had already turned "Add new cars" on, said so,
     * and hit this ten times across an hour - and in all ten attempts his log carries "head unit
     * server is not running on :5277", because the one step that would have worked was the last
     * sentence of a message whose first sentence sent him somewhere else.
     */
    const val ACCEPTED_BUT_SILENT_ON_CLOSED_RELEASE_MESSAGE =
        "Google Android Auto took MOTO-HUB's request and then ignored it, which is all this " +
            "release does with it: Android Auto 17.3 and newer removed the way an app can ask it " +
            "to project. Start it from Android Auto instead: open Android Auto ▸ tap Version ten " +
            "times ▸ Developer settings ▸ the ⋮ menu at the top right ▸ \"Start head unit " +
            "server\". Leave MOTO-HUB running: it connects on its own within a couple of seconds. " +
            "While you are on that screen, \"Add new cars to Android Auto\" should be on too."

    /**
     * A remedy the rider has to carry out by hand, kept in the two halves it reads best in: the
     * thing to tap, and the menu path it is buried in.
     *
     * [AaSelfMode] publishes it flattened into the one-line startup detail, because that is all
     * the AIDL state channel and the preview screen's status line can carry. The home screen
     * recovers the halves with [riderStepOf] and sets them at a size that survives a glance at a
     * traffic light - which the flattened line, rendered as the session card's grey caption, did
     * not. Field case FF3D-A418 is what that cost: ten attempts across an hour, by a rider the
     * one workable step was in front of the whole time.
     */
    data class RiderStep(val action: String, val where: String) {
        /** The single line published as the startup detail. Do not reword without [riderStepOf]. */
        val flat: String get() = "$action in $where…"
    }

    /** Wanted while the release still has self-mode and Android Auto simply does not trust us. */
    val ADD_NEW_CARS_STEP = RiderStep(
        action = "Enable \"Add new cars to Android Auto\"",
        where = "Android Auto ▸ Developer settings"
    )

    /** The path that works on every release, including the ones that closed self-mode. */
    val HEAD_UNIT_SERVER_STEP = RiderStep(
        action = "Start \"head unit server\"",
        where = "Android Auto ▸ Developer settings ▸ ⋮ menu"
    )

    /**
     * The rider step a startup detail is, or null when the detail is progress narration - the
     * "Asking Android Auto to project…" line the rider only has to watch. Matching on the flat
     * text keeps the companion app's IPC-forwarded detail, which is a plain string by the time it
     * arrives, on the same footing as Core's own.
     */
    fun riderStepOf(detail: String?): RiderStep? =
        listOf(ADD_NEW_CARS_STEP, HEAD_UNIT_SERVER_STEP).firstOrNull { it.flat == detail }

    /**
     * Which acceptance-then-silence message the installed Android Auto has earned.
     *
     * Both remedies are real and neither is a superset of the other, so this picks by the only
     * thing that separates them: whether the release still has the entry points an app can use.
     */
    fun acceptedButSilentMessage(versionName: String?): String =
        if (isKnownBrokenVersion(versionName)) {
            ACCEPTED_BUT_SILENT_ON_CLOSED_RELEASE_MESSAGE
        } else {
            ACCEPTED_BUT_SILENT_MESSAGE
        }

    /**
     * Whether the installed Android Auto is new enough to have dropped self-mode. Used to warn
     * up front instead of after a full round of attempts — but never to skip them: this is a
     * behavioural regression Google could undo, and a version number is a poor thing to hard-code
     * a refusal on.
     */
    fun isKnownBrokenVersion(versionName: String?): Boolean {
        val numbers = versionName.orEmpty().substringBefore('-').split('.')
        val major = numbers.getOrNull(0)?.toIntOrNull() ?: return false
        val minor = numbers.getOrNull(1)?.toIntOrNull() ?: return false
        return major > FIRST_BROKEN_MAJOR ||
            (major == FIRST_BROKEN_MAJOR && minor >= FIRST_BROKEN_MINOR)
    }

    fun isMessageAboutSelfMode(message: String?): Boolean =
        message == NEVER_CONNECTED_MESSAGE ||
            message == ACCEPTED_BUT_SILENT_MESSAGE ||
            message == ACCEPTED_BUT_SILENT_ON_CLOSED_RELEASE_MESSAGE

    /**
     * Opens Android Auto's settings, falling back to its App info page: the settings activity is
     * an internal component and may stop being launchable, exactly as the wireless-startup one did.
     */
    fun openAndroidAutoSettings(context: Context): Boolean {
        val direct = runCatching {
            context.startActivity(
                Intent().apply {
                    setClassName(GEARHEAD_PKG, SETTINGS_ACTIVITY)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            true
        }.getOrDefault(false)
        if (direct) return true

        val launcher = runCatching {
            context.packageManager.getLaunchIntentForPackage(GEARHEAD_PKG)?.let { intent ->
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } ?: false
        }.getOrDefault(false)
        if (launcher) return true

        return runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$GEARHEAD_PKG")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)
    }
}
