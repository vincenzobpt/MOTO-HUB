package io.motohub.android.ui.components

import io.motohub.android.i18n.motoHubText

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.motohub.android.ui.theme.MotoHubLive

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

@Composable
fun MotoHubBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.background),
            content = content
        )
    }
}

@Composable
fun ConnectionRail(state: ConnectionState, modifier: Modifier = Modifier) {
    val connectedColor = MotoHubLive
    val outlineColor = MaterialTheme.colorScheme.outline
    val accentColor = MaterialTheme.colorScheme.primary

    when (state) {
        ConnectionState.DISCONNECTED -> {
            Box(
                modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(outlineColor)
            )
        }
        ConnectionState.CONNECTING -> {
            val transition = rememberInfiniteTransition(label = "rail")
            val offset by transition.animateFloat(
                initialValue = -1f,
                targetValue = 2f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1400, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "shimmer"
            )
            Canvas(
                modifier
                    .fillMaxWidth()
                    .height(2.dp)
            ) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(outlineColor, accentColor, outlineColor),
                        startX = size.width * offset,
                        endX = size.width * (offset + 1f)
                    )
                )
            }
        }
        ConnectionState.CONNECTED -> {
            Box(
                modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(connectedColor)
            )
        }
    }
}

@Composable
fun HubAppBar(
    motorcycleName: String?,
    isConnected: Boolean,
    onMotorcycleTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "MOTO-HUB",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodySmall,
            letterSpacing = MaterialTheme.typography.labelMedium.letterSpacing,
            color = MaterialTheme.colorScheme.onSurface
        )
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onMotorcycleTap)
                .padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val dotColor by animateColorAsState(
                targetValue = if (isConnected) MotoHubLive else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "dot"
            )
            Box(
                Modifier
                    .size(6.dp)
                    .background(dotColor, CircleShape)
            )
            Text(
                text = motorcycleName ?: "No motorcycle",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

enum class HubTab { HOME, GARAGE, SETTINGS }

@Composable
fun HubBottomNavigation(
    selected: HubTab,
    onSelect: (HubTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        // The caller already reserves system gesture-bar space with
        // navigationBarsPadding() - an extra fixed bottom padding here on top of
        // that (there used to be one, +16.dp) just made the bar taller on every
        // device for no reason, most noticeable in landscape where screen height
        // is already tight. NavItem's own padding still keeps each tap target a
        // comfortable size for gloved riding.
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.94f))
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        NavItem("Home", selected == HubTab.HOME, Modifier.weight(1f)) { onSelect(HubTab.HOME) }
        NavItem("Garage", selected == HubTab.GARAGE, Modifier.weight(1f)) { onSelect(HubTab.GARAGE) }
        NavItem("Settings", selected == HubTab.SETTINGS, Modifier.weight(1f)) { onSelect(HubTab.SETTINGS) }
    }
}

@Composable
private fun NavItem(label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        NavIcon(label, active)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.SansSerif,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun NavIcon(label: String, active: Boolean) {
    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(22.dp)) {
        val s = size.width
        val stroke = Stroke(width = 1.7.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        when (label) {
            "Ride" -> {
                drawCircle(color = color, radius = s * 0.38f, style = stroke)
                drawLine(color, Offset(s * 0.5f, s * 0.28f), Offset(s * 0.5f, s * 0.5f), stroke.width)
                drawLine(color, Offset(s * 0.5f, s * 0.5f), Offset(s * 0.66f, s * 0.6f), stroke.width)
            }
            "Nav" -> {
                drawLine(color, Offset(s * 0.5f, s * 0.12f), Offset(s * 0.78f, s * 0.88f), stroke.width)
                drawLine(color, Offset(s * 0.5f, s * 0.12f), Offset(s * 0.22f, s * 0.88f), stroke.width)
                drawLine(color, Offset(s * 0.5f, s * 0.12f), Offset(s * 0.5f, s * 0.56f), stroke.width)
            }
            "Trips" -> {
                drawLine(color, Offset(s * 0.12f, s * 0.88f), Offset(s * 0.88f, s * 0.88f), stroke.width)
                drawLine(color, Offset(s * 0.12f, s * 0.72f), Offset(s * 0.32f, s * 0.52f), stroke.width)
                drawLine(color, Offset(s * 0.32f, s * 0.52f), Offset(s * 0.52f, s * 0.68f), stroke.width)
                drawLine(color, Offset(s * 0.52f, s * 0.68f), Offset(s * 0.88f, s * 0.28f), stroke.width)
            }
            "Garage" -> {
                drawLine(color, Offset(s * 0.1f, s * 0.42f), Offset(s * 0.5f, s * 0.12f), stroke.width)
                drawLine(color, Offset(s * 0.5f, s * 0.12f), Offset(s * 0.9f, s * 0.42f), stroke.width)
                drawLine(color, Offset(s * 0.18f, s * 0.42f), Offset(s * 0.18f, s * 0.88f), stroke.width)
                drawLine(color, Offset(s * 0.82f, s * 0.42f), Offset(s * 0.82f, s * 0.88f), stroke.width)
                drawLine(color, Offset(s * 0.18f, s * 0.88f), Offset(s * 0.82f, s * 0.88f), stroke.width)
                drawLine(color, Offset(s * 0.38f, s * 0.88f), Offset(s * 0.38f, s * 0.56f), stroke.width)
                drawLine(color, Offset(s * 0.62f, s * 0.88f), Offset(s * 0.62f, s * 0.56f), stroke.width)
                drawLine(color, Offset(s * 0.38f, s * 0.56f), Offset(s * 0.62f, s * 0.56f), stroke.width)
            }
            "CF" -> {
                drawCircle(color = color, radius = s * 0.36f, center = Offset(s * 0.5f, s * 0.5f), style = stroke)
                drawLine(color, Offset(s * 0.62f, s * 0.31f), Offset(s * 0.38f, s * 0.31f), stroke.width)
                drawLine(color, Offset(s * 0.38f, s * 0.31f), Offset(s * 0.38f, s * 0.69f), stroke.width)
                drawLine(color, Offset(s * 0.38f, s * 0.69f), Offset(s * 0.62f, s * 0.69f), stroke.width)
            }
            "Settings" -> {
                drawCircle(color = color, radius = s * 0.14f, center = Offset(s * 0.5f, s * 0.5f), style = stroke)
                drawCircle(color = color, radius = s * 0.38f, center = Offset(s * 0.5f, s * 0.5f), style = stroke)
                val notchLen = s * 0.12f
                for (i in 0 until 6) {
                    val angle = Math.toRadians((i * 60.0) - 90)
                    val inner = s * 0.38f
                    val outer = inner + notchLen
                    val cx = s * 0.5f
                    val cy = s * 0.5f
                    drawLine(
                        color,
                        Offset(cx + (inner * Math.cos(angle)).toFloat(), cy + (inner * Math.sin(angle)).toFloat()),
                        Offset(cx + (outer * Math.cos(angle)).toFloat(), cy + (outer * Math.sin(angle)).toFloat()),
                        stroke.width
                    )
                }
            }
        }
    }
}

@Composable
fun MonoLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
fun LivePill(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(
                MotoHubLive.copy(alpha = 0.08f),
                RoundedCornerShape(20.dp)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val transition = rememberInfiniteTransition(label = "pill")
        val alpha by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000),
                repeatMode = RepeatMode.Reverse
            ),
            label = "blink"
        )
        Box(
            Modifier
                .size(6.dp)
                .background(MotoHubLive.copy(alpha = alpha), CircleShape)
        )
        Text(
            text = motoHubText(text),
            style = MaterialTheme.typography.labelMedium,
            color = MotoHubLive,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(color.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(6.dp)
                .background(color, CircleShape)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun MotoHubHeader(
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "MOTO-HUB",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodySmall,
            letterSpacing = MaterialTheme.typography.labelMedium.letterSpacing,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
}

/** A scrollable detail screen with a "‹ back" link and a large title, used by any drill-down settings-style screen. */
@Composable
fun MotoHubDetailScreen(
    title: String,
    onBack: () -> Unit,
    backLabel: String = "‹ Back",
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(4.dp))
        Text(
            backLabel,
            modifier = Modifier
                .clickable(onClick = onBack)
                .padding(vertical = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Text(title, style = MaterialTheme.typography.displaySmall)
        content()
        Spacer(Modifier.height(8.dp))
    }
}

/** A card wrapping a group of [MotoHubActionRow]s, used for a list of drill-down options. */
@Composable
fun MotoHubCardGroup(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large
    ) {
        Column { content() }
    }
}

/** A tappable row with a title, description, optional current-value hint, and a chevron - opens a drill-down screen. */
@Composable
fun MotoHubActionRow(
    title: String,
    description: String,
    value: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            // Fixed to one line so every row in a group takes the same vertical
            // space regardless of how much width the trailing value claims.
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (value != null) {
            // Capped and wrapped onto up to two lines rather than truncated with
            // an ellipsis, so a longer value stays fully readable while still
            // leaving the title column above enough width to stay on one line.
            Text(
                value,
                modifier = Modifier.widthIn(max = 104.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End
            )
        }
        Text(
            "›",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** A title/description row with a trailing switch - a single on/off setting. */
@Composable
fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else contentColor
            )
            Text(description, style = MaterialTheme.typography.bodySmall, color = contentColor)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** A selectable card with a radio button, title, and description - one option among several exclusive choices. */
@Composable
fun MotoHubRadioRow(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = BorderStroke(
            1.5.dp,
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
