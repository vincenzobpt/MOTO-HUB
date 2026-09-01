// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import android.content.Context
import io.motohub.android.androidauto.AndroidAutoRuntime
import io.motohub.android.session.ProjectionEventLog
import io.motohub.android.session.ProjectionRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Asked before a projection starts: is anything holding the EasyConn reverse ports right now?
 *
 * This replaces a warning that fired on the wrong question. The old one appeared whenever an OEM
 * companion app was merely *installed*, which is true for most riders and says nothing about the
 * present moment - so it was dismissed, with "do not show again", by the riders who most needed
 * it. Support case 36A3-FD37-1DD7 ticked that box eight minutes before spending two more of them
 * discovering, one failed Android Auto session at a time, that the ports really were held.
 *
 * Binding the three ports answers the question outright, in microseconds, and turns one dialog
 * into two very different outcomes: silence when the ports are free, and a statement of fact -
 * with the force-stop shortcut - when they are not.
 */
internal object CompanionConflictGate {

    sealed interface Verdict {
        /** Nothing is holding the ports, or we are holding them ourselves. Start, say nothing. */
        data object Clear : Verdict

        /**
         * The ports are held right now. [companionApp] is the app most likely responsible, when
         * one is installed - never a claim, because Android cannot name a socket's owner. The
         * other candidate is a MOTO-HUB session in our other half, which is why the rider-facing
         * text (see [TBoxConflictDiagnostics.portConflictMessage]) names both.
         */
        data class Conflict(
            val companionApp: CompanionAppRegistry.CompanionApp?,
            val busyPorts: List<Int>
        ) : Verdict
    }

    /**
     * @param alsoActive extra "a projection of ours is running" sources the shared runtimes do not
     *   know about - PRO's Ride Dashboard, which streams to the TFT through Core just the same.
     */
    suspend fun evaluate(
        context: Context,
        alsoActive: () -> Boolean = { false }
    ): Verdict {
        // Our own live session holds these ports by design. Probing then would have the app
        // denounce itself, and in ADVANCED it would denounce Core, which is worse: the rider
        // would be sent to force-stop an app that is doing exactly what they asked.
        if (ProjectionRuntime.isActive() || AndroidAutoRuntime.isActive() || alsoActive()) {
            return Verdict.Clear
        }
        val busy = withContext(Dispatchers.IO) { ReversePortProbe.busyPorts() }
        if (busy.isEmpty()) return Verdict.Clear
        val companion = CompanionAppRegistry.installed(context)
        ProjectionEventLog.warning(
            "TBOX",
            "Local reverse ports ${busy.joinToString()} are held before the projection even " +
                "starts; the likely holder is " +
                (companion?.let { "${it.displayName} (${it.packageName})" }
                    ?: "an app this build does not know") +
                ", or another MOTO-HUB session on this phone."
        )
        return Verdict.Conflict(companion, busy)
    }
}
