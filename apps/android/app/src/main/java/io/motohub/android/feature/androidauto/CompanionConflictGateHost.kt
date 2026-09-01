// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.androidauto

import android.content.Context
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.motohub.android.i18n.motoHubText
import io.motohub.android.session.ProjectionEventLog
import io.motohub.android.tbox.CompanionAppRegistry
import io.motohub.android.tbox.CompanionConflictGate
import io.motohub.android.ui.components.MotoHubDialogBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * One gate, two flavors. CORE and ADVANCED each have their own `MainActivity`, and the old
 * companion-app warning lived as two near-identical copies in both; the check this replaces it
 * with has real logic in it - a socket probe, a runtime guard, a coroutine - and that is not
 * something to keep in duplicate.
 *
 * A caller wraps whatever starts a projection: `gate("Android Auto") { continueAndroidAutoStart() }`
 * runs the block immediately when the ports are free, and otherwise raises
 * [CompanionConflictGateDialog] instead of letting the rider walk into a handshake that cannot
 * succeed.
 */
@Stable
class CompanionConflictGateState internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val alsoActive: () -> Boolean
) {
    internal data class Pending(
        val conflict: CompanionConflictGate.Verdict.Conflict,
        val actionLabel: String,
        val proceed: () -> Unit
    )

    internal var pending by mutableStateOf<Pending?>(null)
        private set

    /**
     * @param actionLabel what the rider asked for, in the log and in the dialog's continue button
     *   ("Android Auto", "Mirroring").
     */
    fun gate(actionLabel: String, onProceed: () -> Unit) {
        scope.launch {
            when (val verdict = CompanionConflictGate.evaluate(context, alsoActive)) {
                CompanionConflictGate.Verdict.Clear -> onProceed()
                is CompanionConflictGate.Verdict.Conflict -> {
                    ProjectionEventLog.record(
                        "ANDROID_AUTO",
                        "$actionLabel start held back: the EasyConn reverse ports are already in " +
                            "use, so the rider is asked to free them first."
                    )
                    pending = Pending(verdict, actionLabel, onProceed)
                }
            }
        }
    }

    internal fun clear() {
        pending = null
    }
}

@Composable
fun rememberCompanionConflictGate(
    alsoActive: () -> Boolean = { false }
): CompanionConflictGateState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(context, scope) { CompanionConflictGateState(context, scope, alsoActive) }
}

/**
 * States the conflict as the fact it is, and offers the only remedy Android leaves: the rider
 * force-stopping the holder from its App info page.
 *
 * There is deliberately no "do not show this again". The warning that had one was a guess about
 * an installed app; this one only ever appears when the ports are held at that very instant, and
 * a rider who silences it silences the one screen that explains why nothing works.
 */
@Composable
fun CompanionConflictGateDialog(state: CompanionConflictGateState) {
    val pending = state.pending ?: return
    val context = LocalContext.current
    val companion = pending.conflict.companionApp
    val holderName = companion?.displayName
    AlertDialog(
        onDismissRequest = { state.clear() },
        title = {
            Text(
                if (holderName != null) {
                    motoHubText("%1\$s is holding the dashboard connection", holderName)
                } else {
                    motoHubText("Another app is holding the dashboard connection")
                }
            )
        },
        text = {
            MotoHubDialogBody {
                Text(
                    motoHubText(
                        "The three local ports MOTO-HUB needs for the dashboard (%1\$s) are in " +
                            "use right now, so the connection would fail.",
                        pending.conflict.busyPorts.joinToString()
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (holderName != null) {
                    Text(
                        motoHubText(
                            "On this phone that is almost always %1\$s. Force-stop it from its " +
                                "App info page and try again - Android does not let one app " +
                                "release another one's connection.",
                            holderName
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Text(
                        motoHubText(
                            "Force-stop your motorcycle's own companion app from its App info " +
                                "page and try again - Android does not let one app release " +
                                "another one's connection."
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    motoHubText(
                        "If your other MOTO-HUB app is running a session on this phone, stop " +
                            "that one instead."
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (companion != null) {
                    OutlinedButton(
                        onClick = {
                            if (!CompanionAppRegistry.openAppSettings(context, companion)) {
                                Toast.makeText(
                                    context,
                                    motoHubText("Unable to open the companion app settings"),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(motoHubText("Open %1\$s settings", companion.displayName))
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val proceed = pending.proceed
                state.clear()
                ProjectionEventLog.record(
                    "ANDROID_AUTO",
                    "Rider started ${pending.actionLabel} anyway, with the reverse ports held."
                )
                proceed()
            }) {
                Text(motoHubText("Try anyway"))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = { state.clear() }) {
                Text(motoHubText("Cancel"))
            }
        }
    )
}
