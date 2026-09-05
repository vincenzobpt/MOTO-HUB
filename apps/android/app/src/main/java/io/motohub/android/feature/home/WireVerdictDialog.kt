// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.home

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.motohub.android.i18n.motoHubText
import io.motohub.android.ui.components.MotoHubDialogBody

/**
 * The one question MOTO-HUB cannot answer for itself.
 *
 * Everything else the wire ladder needs, it reads off the protocol: frames accepted, sockets
 * closed, heartbeats returned. But a dashboard can take a perfectly good stream and display none
 * of it - a Zontes 368G swallowed 3900 frames over four minutes with its panel still showing the
 * pairing QR, and from the phone's side that session was indistinguishable from a flawless one.
 *
 * So this is asked once per wire format tried, and only after a session the firmware clearly
 * liked. "No" is what moves the ladder on; without it the search would stop at the first format
 * the dashboard tolerated and never find the one it actually renders.
 */
@Composable
fun WireVerdictDialog(
    motorcycleName: String,
    onAnswer: (projectionSeen: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(motoHubText("Did it show up on the dashboard?")) },
        text = {
            MotoHubDialogBody {
                Text(
                    motoHubText(
                        "Last time you connected to %1\$s, everything looked right from this phone. " +
                            "MOTO-HUB has no way to see the dashboard's screen, so this is the one " +
                            "thing it has to ask you.",
                        motorcycleName
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    motoHubText(
                        "If it stayed on the pairing screen, MOTO-HUB keeps trying video formats " +
                            "until one works. When other riders with the same dashboard have " +
                            "already confirmed this one, it asks you once more before moving on."
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            Button(onClick = { onAnswer(true) }) {
                Text(motoHubText("Yes, I saw it"))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = { onAnswer(false) }) {
                Text(motoHubText("No, nothing appeared"))
            }
        }
    )
}

/**
 * Shown to a rider whose wire search is standing still because they only ever use the Ride
 * Dashboard.
 *
 * Only Android Auto runs the format the search is testing, so a mirroring session teaches it
 * nothing and the rung never moves. Without this the rider sees a search that simply never
 * progresses and has no way to know why - and the honest fix is one sentence, not a redesign.
 */
@Composable
fun WireNeedsAndroidAutoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(motoHubText("The video format search is waiting")) },
        text = {
            MotoHubDialogBody {
                Text(
                    motoHubText(
                        "MOTO-HUB is still looking for a video format your dashboard can display, " +
                            "but it can only test one while Android Auto is running - the Ride " +
                            "Dashboard always sends its own format."
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    motoHubText(
                        "Connect once with Android Auto and the search moves on by itself."
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text(motoHubText("Got it")) }
        }
    )
}
