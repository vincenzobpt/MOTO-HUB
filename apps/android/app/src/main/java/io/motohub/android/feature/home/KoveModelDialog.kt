// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.motohub.android.i18n.motoHubText
import io.motohub.android.tbox.KoveModelChoice
import io.motohub.android.tbox.ProfileOverride
import io.motohub.android.ui.components.MotoHubDialogBody

/**
 * Which KOVE is this? Asked once per motorcycle, before its first ThinkerRide connect.
 *
 * The dashboards cannot be told apart from the phone - same QR, same network name, same wire -
 * and the one thing that differs, the panel's shape, is something the phone has to declare, not
 * read. The answer lands in the same pin the Garage shows, so it can be changed there later.
 */
@Composable
fun KoveModelDialog(
    motorcycleName: String,
    onChoose: (ProfileOverride) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(motoHubText("Which KOVE is this?")) },
        text = {
            MotoHubDialogBody {
                Text(
                    motoHubText(
                        "%1\$s has a KOVE dashboard. Every KOVE dashboard looks the same to the " +
                            "phone, but their screens have different shapes, so MOTO-HUB needs " +
                            "to know the model to fill the screen correctly. You can change it " +
                            "later in the Garage.",
                        motorcycleName
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                KoveModelChoice.options.forEach { option ->
                    OutlinedButton(
                        onClick = { onChoose(option) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(motoHubText(option.label))
                            Text(
                                when (option) {
                                    ProfileOverride.KOVE_450_RALLY -> motoHubText("Wide landscape screen")
                                    else -> motoHubText("Tall portrait screen")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(motoHubText("Not now"))
            }
        }
    )
}
