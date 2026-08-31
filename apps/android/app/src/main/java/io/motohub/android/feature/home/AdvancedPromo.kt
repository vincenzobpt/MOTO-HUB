// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.motohub.android.BuildConfig
import io.motohub.android.i18n.motoHubText
import io.motohub.android.ui.components.MotoHubDialogBody

private const val ADVANCED_PACKAGE_NAME = "io.motohub.android.pro"
private const val ADVANCED_RELEASES_URL =
    "https://github.com/vincenzobpt/MOTO-HUB-PRO-releases/releases/latest"

/** The edition accent from MotoHubUi's title treatment, so the promo reads as ADVANCED's colour. */
private val ADVANCED_RED = Color(0xFFFF4A38)

/**
 * Whether MOTO-HUB ADVANCED is on the phone.
 *
 * Requires the `<package>` entry for [ADVANCED_PACKAGE_NAME] in the manifest's `<queries>`:
 * without it Android hides the package from `getPackageInfo` and every rider - including the
 * ones who already installed ADVANCED - would keep being offered the download.
 */
private fun isAdvancedInstalled(context: Context): Boolean =
    runCatching { context.packageManager.getPackageInfo(ADVANCED_PACKAGE_NAME, 0) }.isSuccess

/**
 * The mirror image of [CoreMissingBanner]: ADVANCED tells a rider it needs Core, and this is
 * how Core tells a rider ADVANCED exists at all.
 *
 * One row, two jobs, decided by whether ADVANCED is installed: a rider who doesn't have it gets
 * the pitch (the dialog, then the release page), and a rider who does gets a shortcut that just
 * opens it - being sold an app you already run is the fastest way to make this row feel like an
 * advert to scroll past. Deliberately the last thing in the Home column, so a rider who came here
 * to connect a motorcycle never has to step around it. Rechecks on resume, so it flips by itself
 * when the rider comes back from installing ADVANCED.
 */
@Composable
fun AdvancedPromoCard() {
    val context = LocalContext.current
    if (BuildConfig.IS_PRO) return

    var installed by remember { mutableStateOf(isAdvancedInstalled(context)) }
    var showDetails by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                installed = isAdvancedInstalled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (installed) {
                    // Between the resume recheck and this tap ADVANCED could have been
                    // uninstalled, so a missing launcher intent falls back to the pitch rather
                    // than doing nothing at all.
                    val launch = context.packageManager
                        .getLaunchIntentForPackage(ADVANCED_PACKAGE_NAME)
                    if (launch != null) {
                        runCatching { context.startActivity(launch) }.onFailure {
                            Toast.makeText(
                                context,
                                motoHubText("Couldn't open MOTO-HUB ADVANCED."),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } else {
                        installed = false
                        showDetails = true
                    }
                } else {
                    showDetails = true
                }
            },
        colors = CardDefaults.cardColors(
            containerColor = ADVANCED_RED.copy(alpha = 0.08f)
        ),
        border = BorderStroke(1.dp, ADVANCED_RED.copy(alpha = 0.35f)),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    motoHubText("MOTO-HUB ADVANCED"),
                    style = MaterialTheme.typography.labelSmall,
                    color = ADVANCED_RED,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    if (installed) {
                        motoHubText("Dashboard, navigation and trips - installed on this phone.")
                    } else {
                        motoHubText("Free companion app: dashboard, navigation, trips and more.")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                if (installed) motoHubText("Open") else motoHubText("See what it adds"),
                style = MaterialTheme.typography.labelMedium,
                color = ADVANCED_RED,
                fontWeight = FontWeight.SemiBold
            )
        }
    }

    if (showDetails) {
        AdvancedPromoDialog(onDismiss = { showDetails = false })
    }
}

@Composable
private fun AdvancedPromoDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(motoHubText("MOTO-HUB ADVANCED")) },
        text = {
            MotoHubDialogBody(spacing = 8.dp) {
                Text(
                    motoHubText(
                        "A second free app that installs next to MOTO-HUB and turns the same " +
                            "dashboard into a full riding computer. It needs no extra hardware."
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                AdvancedFeature(
                    motoHubText("Ride Dashboard - GPS speed, live map and trip stats on the TFT")
                )
                AdvancedFeature(
                    motoHubText("Navigation - search, motorcycle routing and route preview")
                )
                AdvancedFeature(
                    motoHubText("On the route - weather, fuel prices and speed cameras")
                )
                AdvancedFeature(
                    motoHubText("Trips - recording, replay, analysis and GPX export")
                )
                AdvancedFeature(
                    motoHubText("AI place discovery and a post-ride Riding Coach")
                )
                AdvancedFeature(
                    motoHubText("Group intercom - rider-to-rider voice")
                )
                Text(
                    motoHubText(
                        "MOTO-HUB stays in charge of the motorcycle: pairing, connection and " +
                            "Android Auto keep running here. Install the same version of both."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(ADVANCED_RELEASES_URL))
                    runCatching { context.startActivity(intent) }
                        .onSuccess { onDismiss() }
                        .onFailure {
                            Toast.makeText(
                                context,
                                motoHubText("Couldn't open the browser."),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = ADVANCED_RED,
                    contentColor = Color.White
                )
            ) {
                Text(motoHubText("Download ADVANCED"))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text(motoHubText("Not now")) }
        }
    )
}

@Composable
private fun AdvancedFeature(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "•",
            style = MaterialTheme.typography.bodyMedium,
            color = ADVANCED_RED,
            fontWeight = FontWeight.Bold
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
