// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.about

import io.motohub.android.i18n.motoHubText

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.motohub.android.BuildConfig
import io.motohub.android.ui.components.MonoLabel
import io.motohub.android.ui.components.MotoHubBackground
import io.motohub.android.ui.components.MotoHubHeader

const val MOTO_HUB_GITHUB_URL = "https://github.com/vincenzobpt/MOTO-HUB"
const val MOTO_HUB_DISCORD_URL = "https://discord.gg/FzhXZtPhC8"

/** Taps on the version card that reveal an edition's hidden prototype page. */
private const val PROTOTYPE_UNLOCK_TAP_COUNT = 10

@Composable
fun AboutScreen(
    onOpenGithub: () -> Unit,
    onOpenDiscord: () -> Unit,
    onCheckUpdates: () -> Unit,
    onBack: () -> Unit,
    /** Editions with a hidden prototype pass this; where it is null the version
     *  card is inert and no unlock exists. This screen is shared by both
     *  flavors, so it never names what it unlocks. */
    onUnlockPrototype: (() -> Unit)? = null,
    /** Only the edition that actually draws maps passes true. CORE ships no map, no geocoder and
     *  no routing, so crediting OpenStreetMap there would claim a dependency it does not have. */
    showsMaps: Boolean = false
) {
    BackHandler(onBack = onBack)

    MotoHubBackground(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            MotoHubHeader(
                modifier = Modifier.fillMaxWidth(),
                trailing = {
                    TextButton(onClick = onBack) {
                        Text(motoHubText("Close"))
                    }
                }
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MonoLabel(motoHubText("ABOUT THE PROJECT"))
                Text(
                    text = motoHubText("Your phone.\nYour motorcycle display."),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = motoHubText(
                        "MOTO-HUB connects an Android 12+ phone to a motorcycle dashboard that " +
                            "pairs over EasyConn — the Carbit software several manufacturers ship, " +
                            "CFMOTO among them. It supports screen and app mirroring, Android Auto " +
                            "projection, saved motorcycle profiles, and on-device diagnostics."
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.large
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    MonoLabel(motoHubText("COMMUNITY & SOURCE"))
                    Text(
                        text = motoHubText("Follow development, join our community, report issues, and download releases."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = onOpenGithub,
                            modifier = Modifier
                                .weight(1f)
                                .height(54.dp)
                        ) {
                            Text(motoHubText("GitHub"))
                        }
                        Button(
                            onClick = onOpenDiscord,
                            modifier = Modifier
                                .weight(1f)
                                .height(54.dp)
                        ) {
                            Text(motoHubText("Discord"))
                        }
                    }
                }
            }

            if (showsMaps) MapCreditsCard()
            VersionCard(onUnlockPrototype = onUnlockPrototype)
            Button(
                onClick = onCheckUpdates,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                Text(motoHubText("Check for updates"))
            }
            DisclaimerCard()

            Text(
                text = motoHubText(
                    "MOTO-HUB is an independent project. It is not affiliated with, endorsed by, " +
                        "or sponsored by Carbit, CFMOTO, any other manufacturer whose dashboard uses " +
                        "EasyConn, Google, or Android Auto. All product names and marks belong to " +
                        "their respective owners."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
    }
}

@Composable
private fun VersionCard(onUnlockPrototype: (() -> Unit)? = null) {
    val context = LocalContext.current
    // Android developer-options style easter egg. The count resets every time
    // the About screen is reopened, and the card is not clickable at all in an
    // edition that passes no unlock.
    var tapCount by remember { mutableIntStateOf(0) }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.09f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onUnlockPrototype == null) {
                        Modifier
                    } else {
                        Modifier.clickable {
                            tapCount++
                            val remaining = PROTOTYPE_UNLOCK_TAP_COUNT - tapCount
                            when {
                                remaining <= 0 -> {
                                    tapCount = 0
                                    Toast.makeText(
                                        context,
                                        motoHubText("Prototype unlocked"),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    onUnlockPrototype()
                                }
                                remaining <= 3 -> Toast.makeText(
                                    context,
                                    motoHubText("%d taps away from the prototype", remaining),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                )
                .padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                MonoLabel(motoHubText("VERSION"))
                Text(
                    text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                MonoLabel(motoHubText("PLATFORM"))
                Text(
                    text = "Android 12+",
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * Where the map data comes from, and who it belongs to.
 *
 * OpenStreetMap is under the ODbL and its attribution guidance asks for the credit to be visible
 * to the person looking at the map - on the map, or one step away from it - which a line in the
 * repository README does not satisfy. MapLibre Native is BSD-2-Clause and asks for its notice to
 * travel with the binary. The phone maps also show MapLibre's own attribution control; this card
 * is what covers the dashboard, where a TFT glanced at mid-ride has no room for one.
 */
@Composable
private fun MapCreditsCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MonoLabel(motoHubText("MAPS & DATA"))
            Text(
                text = motoHubText(
                    "Maps, addresses and routes are built on data by © OpenStreetMap " +
                        "contributors, licensed under the ODbL."
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = motoHubText(
                    "Map rendering by MapLibre Native (BSD-2-Clause). Vector tiles by OpenFreeMap, " +
                        "to the OpenMapTiles schema; raster tiles by the OpenStreetMap " +
                        "Foundation. Address search by Photon. Routing by Valhalla, hosted by " +
                        "Stadia Maps or the FOSSGIS demo server. Places by Overpass. Weather by " +
                        "Open-Meteo. Petrol prices published as open data by Spain's Ministerio " +
                        "para la Transición Ecológica, Portugal's DGEG, the French Ministère de " +
                        "l'Économie and Italy's MIMIT. DGEG's data may not be used commercially."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DisclaimerCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MonoLabel(motoHubText("EXPERIMENTAL SOFTWARE"))
            Text(
                text = motoHubText("MOTO-HUB is an experimental proof-of-concept, not a production-grade product."),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = motoHubText(
                    "Development and testing happen on a CFMOTO 700MT-ADV dashboard with " +
                        "OnePlus 13 / Galaxy Z Fold4 phones. Other motorcycles, brands, T-Box " +
                        "firmware versions and phones are untested here: expect different behaviour, " +
                        "retries, or no connection at all. If the dashboard shows a pairing QR code, " +
                        "it is worth trying."
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = motoHubText(
                    "Do not rely on it as your only source of critical navigation. Configure " +
                        "navigation while parked and use the software at your own risk."
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
