package io.motohub.android.feature.ridedashboard.widget

import io.motohub.android.i18n.motoHubText

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.motohub.android.feature.ridedashboard.NowPlayingListenerService
import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.ui.components.MonoLabel
import io.motohub.android.ui.components.MotoHubBackground
import io.motohub.android.ui.components.MotoHubHeader

/** The one panel that receives the next widget selection. */
private enum class DashboardPanel(val label: String) {
    LEFT("Left panel"),
    RIGHT("Right panel")
}

/**
 * Compact, slot-first editor for the two dashboard side panels.
 *
 * The target panel is chosen once at the top; widgets are then selected by
 * tapping their card. This avoids repeating Left/Right actions nine times and
 * keeps the layout legible as the widget catalog grows.
 */
@Composable
fun DashboardWidgetPickerScreen(
    profile: MotorcycleProfile,
    onBack: () -> Unit,
    onSave: (DashboardLayoutConfig) -> Unit
) {
    val context = LocalContext.current
    val store = remember(context) { DashboardLayoutStore(context) }
    val initialConfig = remember(profile.ssid) { store.load(profile.ssid) }
    var selectedLeftId by remember { mutableStateOf(initialConfig.leftWidgetId) }
    var selectedRightId by remember { mutableStateOf(initialConfig.rightWidgetId) }
    var targetPanel by remember { mutableStateOf(DashboardPanel.LEFT) }
    val allWidgets = remember { DashboardWidgetRegistry.all() }

    var notificationAccessGranted by remember {
        mutableStateOf(NowPlayingListenerService.isEnabled(context))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val nowGranted = NowPlayingListenerService.isEnabled(context)
                if (nowGranted) NowPlayingListenerService.requestRebind(context)
                notificationAccessGranted = nowGranted
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(onBack = onBack)

    fun saveLayout(leftId: String, rightId: String) {
        selectedLeftId = leftId
        selectedRightId = rightId
        val config = DashboardLayoutConfig(leftId, rightId)
        store.save(profile.ssid, config)
        onSave(config)
    }

    fun assignWidget(widgetId: String) {
        when (targetPanel) {
            DashboardPanel.LEFT -> when {
                selectedLeftId == widgetId -> Unit
                selectedRightId == widgetId -> saveLayout(widgetId, selectedLeftId)
                else -> saveLayout(widgetId, selectedRightId)
            }
            DashboardPanel.RIGHT -> when {
                selectedRightId == widgetId -> Unit
                selectedLeftId == widgetId -> saveLayout(selectedRightId, widgetId)
                else -> saveLayout(selectedLeftId, widgetId)
            }
        }
    }

    val selectedTargetId = if (targetPanel == DashboardPanel.LEFT) selectedLeftId else selectedRightId

    MotoHubBackground(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            MotoHubHeader(
                modifier = Modifier.fillMaxWidth(),
                trailing = { TextButton(onClick = onBack) { Text(motoHubText("Back")) } }
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MonoLabel(motoHubText("DASHBOARD CUSTOMIZATION"))
                Text(
                    text = profile.displayName ?: profile.ssid,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Tap a panel, then tap a widget. Changes are saved instantly.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.large
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            MonoLabel(motoHubText("LIVE LAYOUT"))
                            Text(
                                motoHubText("Map stays in the centre"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        TextButton(onClick = {
                            saveLayout(selectedRightId, selectedLeftId)
                        }) {
                            Text(motoHubText("Swap"))
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PanelPreviewCard(
                            label = "LEFT",
                            widgetTitle = DashboardWidgetRegistry.forId(selectedLeftId)?.title?.let(::motoHubText) ?: motoHubText("None"),
                            selected = targetPanel == DashboardPanel.LEFT,
                            modifier = Modifier.weight(1f),
                            onClick = { targetPanel = DashboardPanel.LEFT }
                        )
                        Surface(
                            modifier = Modifier.width(44.dp).height(74.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    motoHubText("MAP"),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                        PanelPreviewCard(
                            label = "RIGHT",
                            widgetTitle = DashboardWidgetRegistry.forId(selectedRightId)?.title?.let(::motoHubText) ?: motoHubText("None"),
                            selected = targetPanel == DashboardPanel.RIGHT,
                            modifier = Modifier.weight(1f),
                            onClick = { targetPanel = DashboardPanel.RIGHT }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            motoHubText("Editing %1\$s", motoHubText(targetPanel.label)),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            DashboardWidgetRegistry.forId(selectedTargetId)?.title?.let(::motoHubText) ?: motoHubText("None"),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.End
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PanelTargetButton(
                    label = "LEFT",
                    selected = targetPanel == DashboardPanel.LEFT,
                    modifier = Modifier.weight(1f),
                    onClick = { targetPanel = DashboardPanel.LEFT }
                )
                PanelTargetButton(
                    label = "RIGHT",
                    selected = targetPanel == DashboardPanel.RIGHT,
                    modifier = Modifier.weight(1f),
                    onClick = { targetPanel = DashboardPanel.RIGHT }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    MonoLabel(motoHubText("WIDGET LIBRARY"))
                    Text(
                        motoHubText("Choose a widget for %1\$s", motoHubText(targetPanel.label).lowercase()),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    motoHubText("%1\$d available", allWidgets.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            allWidgets.chunked(2).forEach { rowWidgets ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    rowWidgets.forEach { widget ->
                        WidgetLibraryCard(
                            widget = widget,
                            activePanel = when {
                                widget.id == selectedLeftId && widget.id == selectedRightId -> "BOTH"
                                widget.id == selectedLeftId -> "LEFT"
                                widget.id == selectedRightId -> "RIGHT"
                                else -> null
                            },
                            selectedForTarget = widget.id == selectedTargetId,
                            notificationAccessGranted = notificationAccessGranted,
                            context = context,
                            modifier = Modifier.weight(1f),
                            onClick = { assignWidget(widget.id) }
                        )
                    }
                    if (rowWidgets.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        saveLayout(
                            DashboardLayoutConfig.DEFAULT.leftWidgetId,
                            DashboardLayoutConfig.DEFAULT.rightWidgetId
                        )
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) { Text(motoHubText("Reset defaults")) }
                Button(
                    onClick = onBack,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) { Text(motoHubText("Done")) }
            }
        }
    }
}

@Composable
private fun PanelTargetButton(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier.height(44.dp),
            shape = RoundedCornerShape(12.dp),
            contentPadding = ButtonDefaults.ContentPadding
        ) { Text(motoHubText(label)) }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.height(44.dp),
            shape = RoundedCornerShape(12.dp)
        ) { Text(motoHubText(label)) }
    }
}

@Composable
private fun PanelPreviewCard(
    label: String,
    widgetTitle: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .heightIn(min = 74.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(10.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                motoHubText(label),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                widgetTitle,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun WidgetLibraryCard(
    widget: DashboardWidget,
    activePanel: String?,
    selectedForTarget: Boolean,
    notificationAccessGranted: Boolean,
    context: Context,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .heightIn(min = 144.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = when {
                selectedForTarget -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                activePanel != null -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.08f)
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        border = when {
            selectedForTarget -> BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
            activePanel != null -> BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
            else -> null
        },
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(30.dp),
                    shape = CircleShape,
                    color = if (selectedForTarget) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            motoHubText(widget.title).take(1),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (selectedForTarget) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    motoHubText(widget.title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                motoHubText(widget.description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3
            )
            if (activePanel != null) {
                Text(
                    if (selectedForTarget) {
                        motoHubText("SELECTED FOR %1\$s", motoHubText(activePanel ?: ""))
                    } else {
                        motoHubText("IN USE • %1\$s", motoHubText(activePanel ?: ""))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (selectedForTarget) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    motoHubText("TAP TO ASSIGN"),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (widget.id == DashboardWidgetIDs.NOW_PLAYING && !notificationAccessGranted) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        motoHubText("Needs notification access"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        },
                        contentPadding = ButtonDefaults.TextButtonContentPadding
                    ) { Text(motoHubText("Grant")) }
                }
            }
        }
    }
}
