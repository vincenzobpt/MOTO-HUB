// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.androidauto

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.motohub.android.androidauto.AndroidAutoSelfModeHelp
import io.motohub.android.i18n.motoHubText
import io.motohub.android.ui.components.MonoLabel
import io.motohub.android.ui.components.MotoHubActionRow
import io.motohub.android.ui.components.MotoHubBackground
import io.motohub.android.ui.components.MotoHubDetailScreen

/**
 * How to get Android Auto to project when it will not start on its own.
 *
 * Android Auto 17.4 removed the entry points an app could use to ask for projection, so on those
 * releases the rider has to start it from Android Auto's own developer menu. That is a sequence
 * of taps in another app, buried behind a hidden menu — exactly the kind of thing that belongs in
 * front of the rider rather than in a support thread.
 *
 * The head unit server leads, and "Add new cars to Android Auto" follows it. That order is the
 * other way round from how this page first read, and field case FF3D-A418 is why: a rider on
 * 17.4.663054 turned the switch on, took it for the whole fix because it was named first, and
 * spent an hour retrying a path his release had already closed.
 *
 * The two live in different places, which this page used to get wrong: tapping "Version" ten
 * times unlocks Android Auto's developer options, and "Add new cars" is then a switch inside
 * the Developer settings list - but "Start head unit server" is not in that list at all. It is
 * in the three-dot menu at the top right of Android Auto's ordinary settings screen. Step 2
 * says so in as many words, because the rider who scrolls Developer settings looking for it
 * finds nothing and concludes the whole page is wrong.
 *
 * Shown as a full-screen overlay straight from MainActivity, not inside the hub, so it has to
 * bring its own background and its own back handling the way the About and diagnostics screens
 * do. Without [MotoHubBackground] the theme never provides a content colour and every
 * Text that does not name one falls back to Compose's default black on the near-black
 * background; without the [BackHandler] the swipe-back gesture reaches the activity and
 * minimises the app instead of closing the page.
 */
@Composable
fun AndroidAutoHelpScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)
    MotoHubBackground(Modifier.fillMaxSize()) {
        MotoHubDetailScreen(
            title = motoHubText("Android Auto does not start"),
            backLabel = "‹ ${motoHubText("Back")}",
            onBack = onBack
        ) {
            // Body copy on this page is what the rider is here to read, not a secondary hint, so
            // it stays on the full-contrast content colour rather than the muted variant.
            Text(
                motoHubText(
                    "Android Auto 17.4 removed the way an app can ask it to project. MOTO-HUB still " +
                        "tries, and on older versions it works — but when it does not, the four " +
                        "steps below start Android Auto from its own developer menu instead. This " +
                        "is the part that works when nothing else does, and you do not need to " +
                        "install anything."
                ),
                style = MaterialTheme.typography.bodyMedium
            )

            HorizontalDivider()
            MonoLabel(motoHubText("START ANDROID AUTO YOURSELF"))
            HelpStep(
                number = "1",
                text = motoHubText(
                    "Open the Android Auto app's settings, scroll to the bottom and tap " +
                        "\"Version\" ten times. That unlocks its developer options — you only have " +
                        "to do it once."
                )
            )
            HelpStep(
                number = "2",
                text = motoHubText(
                    "Stay on that same Android Auto settings screen, open the three-dot menu at " +
                        "the top right and choose \"Start head unit server\". It is in that menu, " +
                        "not inside Developer settings. This is the step that does it."
                )
            )
            HelpStep(
                number = "3",
                text = motoHubText(
                    "A notification confirms the server is running. Leave it running: it stays up " +
                        "until you stop it or restart the phone."
                )
            )
            HelpStep(
                number = "4",
                text = motoHubText(
                    "Go back to MOTO-HUB and start Android Auto. It connects on its own within a " +
                        "couple of seconds — there is nothing else to press."
                )
            )

            HorizontalDivider()
            MonoLabel(motoHubText("WHILE YOU ARE IN THERE"))
            HelpStep(
                number = "5",
                text = motoHubText(
                    "Open Developer settings from that same screen and turn on \"Add new cars to " +
                        "Android Auto\" (older versions call it \"Unknown sources\"). On Android " +
                        "Auto 17.2 and older that switch on its own is often enough. From 17.3 on " +
                        "it is not — step 2 is — so do not stop here if Android Auto still will " +
                        "not start."
                )
            )

            Spacer(Modifier.height(4.dp))
            MotoHubActionRow(
                title = motoHubText("Open Android Auto settings"),
                description = motoHubText("Jumps straight to the app where the menu above lives"),
                onClick = { AndroidAutoSelfModeHelp.openAndroidAutoSettings(context) }
            )

            HorizontalDivider()
            MonoLabel(motoHubText("WHY"))
            Text(
                motoHubText(
                    "Normally MOTO-HUB waits and asks Android Auto to connect to it. Version 17.4 " +
                        "closed that door for every app of this kind, not just this one. The head " +
                        "unit server reverses the direction — Android Auto waits and MOTO-HUB " +
                        "connects to it — which is a door Google left open for its own testing tools."
                ),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun HelpStep(number: String, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            number,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(20.dp)
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
    }
}
