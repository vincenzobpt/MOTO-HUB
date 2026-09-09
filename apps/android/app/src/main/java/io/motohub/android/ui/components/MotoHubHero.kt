// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.ui.components

import io.motohub.android.i18n.motoHubText

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared "hero" design language - colorful icon+subtitle cards and their hand-drawn icon set -
 * used by Home's pairing/connection screens and the Garage tab. Kept in one file so every screen
 * reads from the same icon vocabulary instead of drifting into inconsistent hand-drawn styles.
 */
@Composable
fun ModeIcon(mode: String, color: Color, iconSize: Dp = 24.dp) {
    Canvas(Modifier.size(iconSize)) {
        val s = size.width
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (mode) {
            "OSM" -> {
                // Folded map with a location marker: recognizable even at compact sizes.
                drawRoundRect(
                    color = color,
                    topLeft = Offset(s * 0.12f, s * 0.20f),
                    size = Size(s * 0.76f, s * 0.58f),
                    cornerRadius = CornerRadius(s * 0.05f),
                    style = stroke
                )
                drawLine(color, Offset(s * 0.37f, s * 0.20f), Offset(s * 0.37f, s * 0.78f), stroke.width)
                drawLine(color, Offset(s * 0.64f, s * 0.20f), Offset(s * 0.64f, s * 0.78f), stroke.width)
                drawCircle(color, radius = s * 0.09f, center = Offset(s * 0.50f, s * 0.45f), style = stroke)
                drawLine(color, Offset(s * 0.50f, s * 0.54f), Offset(s * 0.50f, s * 0.67f), stroke.width, cap = StrokeCap.Round)
            }
            "MapLibre" -> {
                // Three connected vector nodes, reflecting MapLibre's vector-map engine.
                drawLine(color, Offset(s * 0.20f, s * 0.72f), Offset(s * 0.46f, s * 0.28f), stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(s * 0.46f, s * 0.28f), Offset(s * 0.78f, s * 0.60f), stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(s * 0.20f, s * 0.72f), Offset(s * 0.78f, s * 0.60f), stroke.width, cap = StrokeCap.Round)
                drawCircle(color, radius = s * 0.10f, center = Offset(s * 0.20f, s * 0.72f))
                drawCircle(color, radius = s * 0.10f, center = Offset(s * 0.46f, s * 0.28f))
                drawCircle(color, radius = s * 0.10f, center = Offset(s * 0.78f, s * 0.60f))
            }
            "Mirror" -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(s * 0.28f, s * 0.06f),
                    size = Size(s * 0.44f, s * 0.88f),
                    cornerRadius = CornerRadius(s * 0.08f),
                    style = stroke
                )
                drawLine(color, Offset(s * 0.42f, s * 0.82f), Offset(s * 0.58f, s * 0.82f), stroke.width, cap = StrokeCap.Round)
            }
            "Dashboard" -> {
                drawArc(
                    color = color,
                    startAngle = 150f,
                    sweepAngle = 240f,
                    useCenter = false,
                    style = stroke,
                    topLeft = Offset(s * 0.08f, s * 0.12f),
                    size = Size(s * 0.84f, s * 0.84f)
                )
                drawLine(color, Offset(s * 0.5f, s * 0.54f), Offset(s * 0.7f, s * 0.32f), stroke.width, cap = StrokeCap.Round)
                drawCircle(color, radius = s * 0.055f, center = Offset(s * 0.5f, s * 0.54f))
            }
            "Auto" -> {
                drawLine(color, Offset(s * 0.12f, s * 0.6f), Offset(s * 0.22f, s * 0.38f), stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(s * 0.22f, s * 0.38f), Offset(s * 0.38f, s * 0.28f), stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(s * 0.38f, s * 0.28f), Offset(s * 0.62f, s * 0.28f), stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(s * 0.62f, s * 0.28f), Offset(s * 0.78f, s * 0.38f), stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(s * 0.78f, s * 0.38f), Offset(s * 0.88f, s * 0.6f), stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(s * 0.12f, s * 0.6f), Offset(s * 0.88f, s * 0.6f), stroke.width, cap = StrokeCap.Round)
                drawCircle(color, radius = s * 0.09f, center = Offset(s * 0.28f, s * 0.62f), style = stroke)
                drawCircle(color, radius = s * 0.09f, center = Offset(s * 0.72f, s * 0.62f), style = stroke)
            }
            "External" -> {
                // USB connector icon: a rectangle with a trident fork.
                drawRoundRect(
                    color = color,
                    topLeft = Offset(s * 0.22f, s * 0.18f),
                    size = Size(s * 0.56f, s * 0.44f),
                    cornerRadius = CornerRadius(s * 0.06f),
                    style = stroke
                )
                drawLine(color, Offset(s * 0.50f, s * 0.62f), Offset(s * 0.50f, s * 0.84f), stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(s * 0.36f, s * 0.72f), Offset(s * 0.50f, s * 0.84f), stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(s * 0.64f, s * 0.72f), Offset(s * 0.50f, s * 0.84f), stroke.width, cap = StrokeCap.Round)
            }
            "Preview" -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(s * 0.12f, s * 0.14f),
                    size = Size(s * 0.76f, s * 0.58f),
                    cornerRadius = CornerRadius(s * 0.08f),
                    style = stroke
                )
                drawLine(color, Offset(s * 0.38f, s * 0.86f), Offset(s * 0.62f, s * 0.86f), stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(s * 0.5f, s * 0.72f), Offset(s * 0.5f, s * 0.86f), stroke.width, cap = StrokeCap.Round)
                // Play marker inside the screen makes this unmistakably a preview.
                drawLine(color, Offset(s * 0.44f, s * 0.31f), Offset(s * 0.44f, s * 0.55f), stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(s * 0.44f, s * 0.31f), Offset(s * 0.64f, s * 0.43f), stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(s * 0.64f, s * 0.43f), Offset(s * 0.44f, s * 0.55f), stroke.width, cap = StrokeCap.Round)
            }
            "Controls" -> {
                // Handlebar silhouette with two grips and a central control stem.
                drawLine(color, Offset(s * 0.12f, s * 0.34f), Offset(s * 0.30f, s * 0.34f), stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(s * 0.70f, s * 0.34f), Offset(s * 0.88f, s * 0.34f), stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(s * 0.30f, s * 0.34f), Offset(s * 0.40f, s * 0.48f), stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(s * 0.70f, s * 0.34f), Offset(s * 0.60f, s * 0.48f), stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(s * 0.40f, s * 0.48f), Offset(s * 0.60f, s * 0.48f), stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(s * 0.50f, s * 0.48f), Offset(s * 0.50f, s * 0.82f), stroke.width, cap = StrokeCap.Round)
                drawCircle(color, radius = s * 0.045f, center = Offset(s * 0.28f, s * 0.34f))
                drawCircle(color, radius = s * 0.045f, center = Offset(s * 0.72f, s * 0.34f))
            }
            "Customize" -> {
                // Three adjustable layout sliders, representing dashboard setup.
                val rows = floatArrayOf(0.28f, 0.50f, 0.72f)
                val knobs = floatArrayOf(0.66f, 0.38f, 0.56f)
                rows.forEachIndexed { index, row ->
                    drawLine(color, Offset(s * 0.14f, s * row), Offset(s * 0.86f, s * row), stroke.width, cap = StrokeCap.Round)
                    drawCircle(color, radius = s * 0.085f, center = Offset(s * knobs[index], s * row), style = stroke)
                }
            }
            "Route" -> {
                // Route polyline ending in a destination pin.
                drawLine(color, Offset(s * 0.16f, s * 0.76f), Offset(s * 0.36f, s * 0.58f), stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(s * 0.36f, s * 0.58f), Offset(s * 0.54f, s * 0.68f), stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(s * 0.54f, s * 0.68f), Offset(s * 0.76f, s * 0.34f), stroke.width, cap = StrokeCap.Round)
                drawCircle(color, radius = s * 0.075f, center = Offset(s * 0.16f, s * 0.76f), style = stroke)
                drawCircle(color, radius = s * 0.10f, center = Offset(s * 0.76f, s * 0.28f), style = stroke)
                drawLine(color, Offset(s * 0.76f, s * 0.38f), Offset(s * 0.76f, s * 0.52f), stroke.width, cap = StrokeCap.Round)
            }
            "Gps" -> {
                // Generic GPS/navigation marker with a heading arrow.
                drawCircle(color, radius = s * 0.30f, center = Offset(s * 0.5f, s * 0.52f), style = stroke)
                drawLine(color, Offset(s * 0.5f, s * 0.10f), Offset(s * 0.5f, s * 0.25f), stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(s * 0.5f, s * 0.10f), Offset(s * 0.40f, s * 0.20f), stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(s * 0.5f, s * 0.10f), Offset(s * 0.60f, s * 0.20f), stroke.width, cap = StrokeCap.Round)
                drawCircle(color, radius = s * 0.065f, center = Offset(s * 0.5f, s * 0.52f))
            }
            "Clear" -> {
                // Map tile with a clear/remove cross.
                drawRoundRect(
                    color = color,
                    topLeft = Offset(s * 0.16f, s * 0.16f),
                    size = Size(s * 0.68f, s * 0.68f),
                    cornerRadius = CornerRadius(s * 0.08f),
                    style = stroke
                )
                drawLine(color, Offset(s * 0.30f, s * 0.30f), Offset(s * 0.70f, s * 0.70f), stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(s * 0.70f, s * 0.30f), Offset(s * 0.30f, s * 0.70f), stroke.width, cap = StrokeCap.Round)
            }
            "QrScan" -> {
                // QR finder-pattern corners - unmistakably "scan a code".
                val corner = s * 0.24f
                listOf(
                    Offset(s * 0.12f, s * 0.12f),
                    Offset(s * 0.88f - corner, s * 0.12f),
                    Offset(s * 0.12f, s * 0.88f - corner)
                ).forEach { topLeft ->
                    drawRoundRect(
                        color = color,
                        topLeft = topLeft,
                        size = Size(corner, corner),
                        cornerRadius = CornerRadius(s * 0.05f),
                        style = stroke
                    )
                }
                drawRoundRect(
                    color = color,
                    topLeft = Offset(s * 0.60f, s * 0.60f),
                    size = Size(s * 0.28f, s * 0.28f),
                    cornerRadius = CornerRadius(s * 0.05f),
                    style = stroke
                )
            }
            "Import" -> {
                // Inbox tray with a downward arrow - reads cleanly as "import" at small sizes.
                drawLine(color, Offset(s * 0.5f, s * 0.12f), Offset(s * 0.5f, s * 0.58f), stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(s * 0.32f, s * 0.42f), Offset(s * 0.5f, s * 0.60f), stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(s * 0.68f, s * 0.42f), Offset(s * 0.5f, s * 0.60f), stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(s * 0.14f, s * 0.66f), Offset(s * 0.14f, s * 0.86f), stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(s * 0.14f, s * 0.86f), Offset(s * 0.86f, s * 0.86f), stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(s * 0.86f, s * 0.86f), Offset(s * 0.86f, s * 0.66f), stroke.width, cap = StrokeCap.Round)
            }
            "Manual" -> {
                // Keyboard silhouette - manual pairing means typing the network details in by hand.
                drawRoundRect(
                    color = color,
                    topLeft = Offset(s * 0.10f, s * 0.26f),
                    size = Size(s * 0.80f, s * 0.48f),
                    cornerRadius = CornerRadius(s * 0.08f),
                    style = stroke
                )
                drawLine(color, Offset(s * 0.22f, s * 0.42f), Offset(s * 0.30f, s * 0.42f), stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(s * 0.38f, s * 0.42f), Offset(s * 0.46f, s * 0.42f), stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(s * 0.54f, s * 0.42f), Offset(s * 0.62f, s * 0.42f), stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(s * 0.70f, s * 0.42f), Offset(s * 0.78f, s * 0.42f), stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(s * 0.30f, s * 0.58f), Offset(s * 0.70f, s * 0.58f), stroke.width, cap = StrokeCap.Round)
            }
            "Search" -> {
                // Classic magnifying glass: a ring with a diagonal handle.
                drawCircle(color, radius = s * 0.26f, center = Offset(s * 0.42f, s * 0.42f), style = stroke)
                drawLine(color, Offset(s * 0.62f, s * 0.62f), Offset(s * 0.84f, s * 0.84f), stroke.width, cap = StrokeCap.Round)
            }
            "Star" -> {
                // Five-point star outline - favorites/saved places.
                val cx = s * 0.5f
                val cy = s * 0.5f
                val outerR = s * 0.40f
                val innerR = s * 0.16f
                val path = Path()
                for (i in 0 until 10) {
                    val angle = (Math.PI / 5.0 * i - Math.PI / 2.0).toFloat()
                    val r = if (i % 2 == 0) outerR else innerR
                    val x = cx + r * kotlin.math.cos(angle)
                    val y = cy + r * kotlin.math.sin(angle)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                drawPath(path, color, style = stroke)
            }
            "Clock" -> {
                // Recent destinations - a simple clock face with hour/minute hands.
                drawCircle(color, radius = s * 0.34f, center = Offset(s * 0.5f, s * 0.5f), style = stroke)
                drawLine(color, Offset(s * 0.5f, s * 0.5f), Offset(s * 0.5f, s * 0.30f), stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(s * 0.5f, s * 0.5f), Offset(s * 0.66f, s * 0.58f), stroke.width, cap = StrokeCap.Round)
            }
            "Voice" -> {
                // Microphone on a stand - rider-to-rider voice, the one thing this set had no
                // shape for. Capsule, arc cradle, stem, foot.
                drawRoundRect(
                    color = color,
                    topLeft = Offset(s * 0.38f, s * 0.10f),
                    size = Size(s * 0.24f, s * 0.44f),
                    cornerRadius = CornerRadius(s * 0.12f),
                    style = stroke
                )
                drawArc(
                    color = color,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    style = stroke,
                    topLeft = Offset(s * 0.24f, s * 0.34f),
                    size = Size(s * 0.52f, s * 0.42f)
                )
                drawLine(color, Offset(s * 0.50f, s * 0.76f), Offset(s * 0.50f, s * 0.88f), stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(s * 0.34f, s * 0.88f), Offset(s * 0.66f, s * 0.88f), stroke.width, cap = StrokeCap.Round)
            }
            "Bike" -> {
                // Side-view motorcycle silhouette: two wheels, a seat/frame line, and a headlight
                // dot - reads cleanly as "motorcycle" at small icon sizes.
                drawCircle(color, radius = s * 0.16f, center = Offset(s * 0.26f, s * 0.72f), style = stroke)
                drawCircle(color, radius = s * 0.16f, center = Offset(s * 0.74f, s * 0.72f), style = stroke)
                drawLine(color, Offset(s * 0.26f, s * 0.72f), Offset(s * 0.42f, s * 0.46f), stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(s * 0.42f, s * 0.46f), Offset(s * 0.68f, s * 0.46f), stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(s * 0.68f, s * 0.46f), Offset(s * 0.74f, s * 0.72f), stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(s * 0.42f, s * 0.46f), Offset(s * 0.34f, s * 0.30f), stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(s * 0.60f, s * 0.46f), Offset(s * 0.60f, s * 0.30f), stroke.width, cap = StrokeCap.Round)
                drawCircle(color, radius = s * 0.05f, center = Offset(s * 0.34f, s * 0.28f))
            }
        }
    }
}

/** Full-width, brightly-filled hero card for a screen's single most important action. */
@Composable
fun HeroPrimaryAction(
    title: String,
    subtitle: String,
    icon: String,
    color: Color,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color),
        shape = MaterialTheme.shapes.large
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(Color.Black.copy(alpha = 0.16f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                ModeIcon(icon, Color.Black, iconSize = 30.dp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    motoHubText(title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Text(
                    motoHubText(subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Black.copy(alpha = 0.7f)
                )
            }
        }
    }
}

/** Smaller colorful tile for secondary actions - same hand-drawn icon language as
 *  [HeroPrimaryAction], with room for a one-line subtitle since these need more explaining. */
@Composable
fun HeroTile(
    title: String,
    subtitle: String,
    icon: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(color.copy(alpha = 0.16f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                ModeIcon(icon, color, iconSize = 24.dp)
            }
            Text(
                motoHubText(title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                motoHubText(subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * One option in a grouped card: icon, what it is, what it costs you, chevron.
 *
 * The row equivalent of [HeroTile] - same icon vocabulary, same accent treatment, but laid out
 * for a list of alternatives rather than a pair of targets. Deliberately not
 * [MotoHubActionRow]: that one pins its title to a single line so every row in a settings group
 * is the same height, and a clipped title is exactly what the message-legibility rule forbids.
 * Nothing here is capped; a longer translation makes the row taller.
 */
@Composable
fun HeroOptionRow(
    title: String,
    description: String,
    icon: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: String = "\u203a"
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(color.copy(alpha = 0.12f), RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center
        ) {
            ModeIcon(icon, color, iconSize = 21.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(motoHubText(title), style = MaterialTheme.typography.titleMedium)
            Text(
                motoHubText(description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            trailing,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
