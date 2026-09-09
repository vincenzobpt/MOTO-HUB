// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.motohub.android.i18n.motoHubText

/**
 * The one place a message the rider has to *read* is allowed to live.
 *
 * The rule this component exists to enforce: **no runtime string is ever drawn inside a fixed
 * box.** A status card's caption slot, a button label, a pill, a tile with `maxLines` - every one
 * of those has a size decided before the text arrives, so the moment the text is a sentence
 * instead of a word it is squeezed into a column two words wide or cut off entirely. Android
 * Auto's failure messages are seven lines of instructions; they were being rendered as the grey
 * subtitle of the session hero, where a rider could not read the one thing that would have fixed
 * their ride.
 *
 * So: the hero, the pills and the tiles carry only short, enumerated labels the layout was
 * designed around, and anything that arrives at runtime - a failure, a progress line, a step to
 * carry out - gets one of these instead. It is full width, it wraps, it has no `maxLines` and no
 * fixed height anywhere, and it grows down the scrolling column for as long as the message needs.
 *
 * [details] is the only thing behind a fold, and only ever secondary help: what went wrong and
 * what to do about it are always in front of the rider, whatever length they turn out to be.
 */
enum class NoticeTone {
    /** Narration. Something is happening and this says what. */
    INFO,

    /** The app is waiting on the rider, and the notice says exactly what to do. */
    ACTION,

    /** Something failed. */
    ERROR
}

@Composable
fun MotoHubNotice(
    label: String,
    tone: NoticeTone,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    headline: String? = null,
    body: String? = null,
    footnote: String? = null,
    actions: (@Composable ColumnScope.() -> Unit)? = null,
    details: (@Composable ColumnScope.() -> Unit)? = null
) {
    val toneColor = accent ?: when (tone) {
        NoticeTone.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
        NoticeTone.ACTION -> MaterialTheme.colorScheme.primary
        NoticeTone.ERROR -> MaterialTheme.colorScheme.error
    }
    // INFO is narration next to a card that already carries the accent; it stays a plain surface
    // so a progress line does not shout as loudly as a failure sitting in the same column.
    val container = when (tone) {
        NoticeTone.INFO -> MaterialTheme.colorScheme.surface
        else -> toneColor.copy(alpha = 0.10f)
    }
    val border = when (tone) {
        NoticeTone.INFO -> BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        else -> BorderStroke(1.dp, toneColor.copy(alpha = 0.45f))
    }
    var expanded by rememberSaveable(label, body) { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .let { if (details != null) it.clickable { expanded = !expanded } else it },
        colors = CardDefaults.cardColors(containerColor = container),
        border = border,
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            // Vertical padding only; no height. The card is exactly as tall as its contents.
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = toneColor,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (details != null) {
                    Text(
                        text = if (expanded) motoHubText("Less ▲") else motoHubText("Details ▼"),
                        style = MaterialTheme.typography.labelMedium,
                        color = toneColor,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            // Every text below is deliberately free of maxLines/overflow: this component's whole
            // job is that a long message stays readable rather than tidy.
            headline?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            body?.let {
                Text(
                    text = it,
                    // bodyLarge, not bodySmall: this is read at arm's length, sometimes with a
                    // helmet on, and it is the most important text on the screen when it appears.
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            footnote?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            actions?.invoke(this)
            if (expanded) details?.invoke(this)
        }
    }
}
