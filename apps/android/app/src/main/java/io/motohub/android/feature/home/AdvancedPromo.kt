// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.home

import android.content.Context
import androidx.activity.compose.BackHandler
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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
import io.motohub.android.feature.about.MOTO_HUB_DISCORD_URL
import io.motohub.android.feature.about.MOTO_HUB_GITHUB_URL
import io.motohub.android.ui.components.HeroOptionRow
import io.motohub.android.ui.components.ModeIcon
import io.motohub.android.ui.components.MonoLabel
import io.motohub.android.ui.components.MotoHubCardGroup
import io.motohub.android.ui.components.MotoHubDetailScreen
import io.motohub.android.ui.theme.MotoHubManual
import io.motohub.android.ui.theme.MotoHubMirror

// The one in IpcBridgeContract, not a second copy: three files had grown their own.
private val ADVANCED_PACKAGE_NAME = io.motohub.android.ipc.IpcBridgeContract.ADVANCED_PACKAGE_NAME
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
fun AdvancedPromoCard(onOpenDetails: () -> Unit) {
    val context = LocalContext.current
    if (BuildConfig.IS_PRO) return

    var installed by remember { mutableStateOf(isAdvancedInstalled(context)) }

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
                        onOpenDetails()
                    }
                } else {
                    onOpenDetails()
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

}

/**
 * The pitch, as a screen rather than a dialog.
 *
 * It used to be an [androidx.compose.material3.AlertDialog]: six bullets in a scrolling modal,
 * one link, and no room to say how the two apps relate. A rider deciding whether to install a
 * second app is doing exactly the kind of reading a modal is worst at, so this is a drill-down
 * like every other explanation in the app - reached from the promo card at the foot of Home, and
 * from Settings.
 *
 * Nothing here is capped or fixed-height: every paragraph and every feature description wraps for
 * as long as it needs, in whichever of the nine languages the phone is set to.
 */
@Composable
fun AdvancedPromoScreen(onBack: () -> Unit) {
    // Same as every other drill-down here: the screen owns its own system-back, so the gesture
    // closes the page instead of the app.
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    var installed by remember { mutableStateOf(isAdvancedInstalled(context)) }

    // Same resume recheck as the card: a rider who leaves for the release page and comes back
    // installed should find this screen already turned into "open it", not still selling.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) installed = isAdvancedInstalled(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val open: (String) -> Unit = { url ->
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure {
                Toast.makeText(
                    context,
                    motoHubText("Couldn't open the browser."),
                    Toast.LENGTH_SHORT
                ).show()
            }
    }

    MotoHubDetailScreen(title = motoHubText("MOTO-HUB ADVANCED"), onBack = onBack) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = ADVANCED_RED.copy(alpha = 0.10f)),
            border = BorderStroke(1.dp, ADVANCED_RED.copy(alpha = 0.40f)),
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    motoHubText(if (installed) "INSTALLED ON THIS PHONE" else "FREE COMPANION APP"),
                    style = MaterialTheme.typography.labelMedium,
                    color = ADVANCED_RED,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    motoHubText("A second dashboard for the same motorcycle."),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    motoHubText(
                        "MOTO-HUB ADVANCED is a free app that installs next to this one and " +
                            "turns your TFT into a full riding computer: its own dashboard, " +
                            "navigation, recorded trips. No subscription, no account, and no " +
                            "extra hardware - it uses the T-Box you already have."
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        MonoLabel(motoHubText("WHAT IT ADDS"))
        MotoHubCardGroup {
            AdvancedFeature(
                title = "Ride Dashboard",
                description = "GPS speed, live map and trip stats drawn on the motorcycle's screen.",
                icon = "Dashboard"
            )
            AdvancedFeature(
                title = "Navigation",
                description = "Search, motorcycle routing, route preview and turn guidance.",
                icon = "Route"
            )
            AdvancedFeature(
                title = "On the route",
                description = "Weather ahead, fuel prices and speed cameras along the way.",
                icon = "Gps"
            )
            AdvancedFeature(
                title = "Trips",
                description = "Recording, replay, analysis and GPX export of everything you ride.",
                icon = "Clock"
            )
            AdvancedFeature(
                title = "Discovery and coaching",
                description = "AI place search while you ride, and a Riding Coach afterwards.",
                icon = "Search"
            )
            AdvancedFeature(
                title = "Group intercom",
                description = "Rider-to-rider voice over the internet, no extra headset box.",
                icon = "Voice"
            )
        }

        MonoLabel(motoHubText("HOW THE TWO WORK TOGETHER"))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    motoHubText(
                        "MOTO-HUB stays in charge of the motorcycle. Pairing, the Wi-Fi " +
                            "connection, the handlebar buttons and Android Auto all keep running " +
                            "here; ADVANCED asks this app for the screen when it needs it."
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    motoHubText(
                        "That is also why the two versions have to match: they talk to each " +
                            "other, so install the same version number of both and update them " +
                            "together."
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        MonoLabel(motoHubText("LINKS"))
        MotoHubCardGroup {
            if (installed) {
                HeroOptionRow(
                    title = "Open MOTO-HUB ADVANCED",
                    description = "It is already installed on this phone.",
                    icon = "Dashboard",
                    color = ADVANCED_RED,
                    onClick = {
                        val launch = context.packageManager
                            .getLaunchIntentForPackage(ADVANCED_PACKAGE_NAME)
                        if (launch == null) {
                            installed = false
                        } else {
                            runCatching { context.startActivity(launch) }.onFailure {
                                Toast.makeText(
                                    context,
                                    motoHubText("Couldn't open MOTO-HUB ADVANCED."),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                )
            } else {
                HeroOptionRow(
                    title = "Download ADVANCED",
                    description = "The latest release, straight from the project's own page.",
                    icon = "Import",
                    color = ADVANCED_RED,
                    onClick = { open(ADVANCED_RELEASES_URL) }
                )
            }
            HeroOptionRow(
                title = "Community on Discord",
                description = "Ask questions, report a dash that misbehaves, follow releases.",
                icon = "Voice",
                color = MotoHubManual,
                onClick = { open(MOTO_HUB_DISCORD_URL) }
            )
            HeroOptionRow(
                title = "MOTO-HUB source code",
                description = "This app is free software, AGPL v3. Read it, build it, fork it.",
                icon = "Search",
                color = MotoHubMirror,
                onClick = { open(MOTO_HUB_GITHUB_URL) }
            )
        }
    }
}

/** One capability, in the grouped card under "what it adds". Not tappable - there is nowhere to go. */
@Composable
private fun AdvancedFeature(title: String, description: String, icon: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(ADVANCED_RED.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            ModeIcon(icon, ADVANCED_RED, iconSize = 20.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(motoHubText(title), style = MaterialTheme.typography.titleMedium)
            Text(
                motoHubText(description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
