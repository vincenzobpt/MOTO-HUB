// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.pairing

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import io.motohub.android.i18n.motoHubText
import io.motohub.android.ui.components.MotoHubActionRow

/** Where the rider keeps the picture of the pairing code. */
enum class QrImageSource { GALLERY, FILES }

/**
 * The photo picker alone reaches only what the gallery indexes. A pairing code arrives just as
 * often as a screenshot in Downloads, a PNG saved out of a chat, or a file on a cloud drive -
 * none of which the gallery lists, so a rider holding one had no way in at all and no hint that
 * the picker was the reason. These are the same two doors the motorcycle photo already offers,
 * worded the same way; the camera is missing on purpose, because live scanning is its own action
 * next to this one.
 */
@Composable
fun QrImageSourceDialog(
    onDismiss: () -> Unit,
    onSelect: (QrImageSource) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(motoHubText("Import QR from an image")) },
        text = {
            Column {
                MotoHubActionRow(
                    title = motoHubText("Choose from gallery"),
                    description = motoHubText("Pick one of your photos."),
                    onClick = { onSelect(QrImageSource.GALLERY) }
                )
                HorizontalDivider()
                MotoHubActionRow(
                    title = motoHubText("Browse files"),
                    description = motoHubText("Downloads, cloud drives and any other folder."),
                    onClick = { onSelect(QrImageSource.FILES) }
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(motoHubText("Cancel")) } }
    )
}
