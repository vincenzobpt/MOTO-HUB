package io.motohub.android.feature.controls

import io.motohub.android.i18n.motoHubText

import android.app.Activity
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.motohub.android.aa.AaInput
import io.motohub.android.aa.AaInputBridge
import io.motohub.android.feature.ridedashboard.RideDashboardControlBridge
import io.motohub.android.feature.ridedashboard.RideDashboardMapSource
import io.motohub.android.feature.ridedashboard.RideDashboardMapSourceStore
import io.motohub.android.androidauto.AndroidAutoPreviewRuntime
import io.motohub.android.feature.ridedashboard.nav.NightPrefs
import io.motohub.android.feature.ridedashboard.nav.MapTheme
import io.motohub.android.feature.controls.SavedPlaces
import io.motohub.android.feature.controls.NavLauncher
import io.motohub.android.session.ProjectionEventLog
import io.motohub.android.ui.components.LivePill
import io.motohub.android.ui.components.MotoHubBackground
import io.motohub.android.ui.components.MotoHubHeader
import kotlin.math.roundToInt

@Composable
fun ControlsScreen(
    androidAutoStreaming: Boolean,
    rideDashboardStreaming: Boolean,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    var padFullscreen by rememberSaveable { mutableStateOf(false) }

    // Driving mode: pad fills the screen, system bars hide, and the window stays awake so the
    // phone doesn't dim/lock mid-ride. Mirrors AndroidAutoPreviewScreen's immersive pattern.
    val window = (view.context as? Activity)?.window
    val insetsController = window?.let { WindowCompat.getInsetsController(it, view) }
    DisposableEffect(view, insetsController) {
        onDispose {
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    LaunchedEffect(padFullscreen, insetsController, window) {
        if (padFullscreen) {
            insetsController?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController?.hide(WindowInsetsCompat.Type.systemBars())
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Toast.makeText(context, motoHubText("Pad fullscreen — screen stays on. Tap Exit to leave."), Toast.LENGTH_SHORT).show()
        } else {
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    BackHandler(enabled = padFullscreen) { padFullscreen = false }
    BackHandler(enabled = !padFullscreen, onBack = onBack)
    // Map theme only ever affects Android Auto (see the card below) - showing it while Ride
    // Dashboard is actively displaying OpenStreetMap would be a control that visibly does
    // nothing, so it's hidden for exactly that combination.
    val rideDashboardMapSource = remember { RideDashboardMapSourceStore.load(context) }
    val showMapTheme = !rideDashboardStreaming || rideDashboardMapSource != RideDashboardMapSource.OPEN_STREET_MAP
    var captureEnabled by remember { mutableStateOf(HandlebarControlStore.isEnabled(context)) }
    var mappings by remember {
        mutableStateOf(HandlebarGesture.entries.associateWith { gesture ->
            HandlebarControlStore.action(context, gesture)
        })
    }
    var doubleTapDelay by remember { mutableStateOf(HandlebarTimingPrefs.doubleTap(context)) }
    var selectHoldDelay by remember { mutableStateOf(HandlebarTimingPrefs.selectHold(context)) }
    val androidAutoInputReady = androidAutoStreaming && AaInputBridge.isReady()
    val dashboardInputReady = rideDashboardStreaming && RideDashboardControlBridge.isReady()
    val handlebarTargetActive = androidAutoStreaming || rideDashboardStreaming
    val handlebarTarget = when {
        rideDashboardStreaming -> MediaButtonBridge.TARGET_RIDE_DASHBOARD
        androidAutoStreaming -> MediaButtonBridge.TARGET_ANDROID_AUTO
        else -> null
    }

    LaunchedEffect(captureEnabled, handlebarTarget) {
        handlebarTarget?.let { target ->
            val applied = MediaButtonBridge.setTargetCaptureActive(target, captureEnabled)
            if (!applied && captureEnabled) {
                ProjectionEventLog.warning("CONTROLS", "$target handlebar bridge is not registered yet.")
            }
        }
    }

    if (padFullscreen) {
        FullscreenDpad(
            enabled = androidAutoInputReady,
            onKey = { code -> AaInputBridge.sendKey(code) },
            onScroll = { delta -> AaInputBridge.sendScroll(delta) },
            onExit = { padFullscreen = false }
        )
        return
    }

    var showSavedPlaces by rememberSaveable { mutableStateOf(false) }
    var showAdvancedMapping by rememberSaveable { mutableStateOf(false) }

    MotoHubBackground(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            MotoHubHeader(
                modifier = Modifier.fillMaxWidth(),
                trailing = { TextButton(onClick = onBack) { Text(motoHubText("Close")) } }
            )

            val modeLabel = when {
                rideDashboardStreaming -> "Ride Dashboard"
                androidAutoStreaming -> "Android Auto"
                else -> "No active projection"
            }
            val mapLabel = if (rideDashboardStreaming) {
                motoHubText(rideDashboardMapSource.label)
            } else {
                motoHubText("Android Auto map")
            }
            val inputReady = dashboardInputReady || androidAutoInputReady

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    LivePill(if (inputReady) "CONTROLS READY" else "CONTROLS STANDBY")
                    Text(motoHubText("Controls"), style = MaterialTheme.typography.headlineMedium)
                    Text(
                        motoHubText("Everything on this page controls the projection currently shown on the motorcycle."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ModeBadge("MODE", modeLabel)
                        ModeBadge("MAP", mapLabel)
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    SectionHeading(
                        eyebrow = if (rideDashboardStreaming) "RIDE DASHBOARD" else "ANDROID AUTO",
                        title = if (rideDashboardStreaming) "Quick controls" else "Touch controller",
                        description = when {
                            rideDashboardStreaming && dashboardInputReady -> "Large, glove-friendly actions for the active dashboard."
                            rideDashboardStreaming -> "Start the dashboard to enable these actions."
                            androidAutoInputReady -> "Send navigation and selection commands to Android Auto."
                            else -> "Start Android Auto to enable the controller."
                        }
                    )
                    if (!rideDashboardStreaming) {
                        OutlinedButton(
                            onClick = { padFullscreen = true },
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text(motoHubText("Open glove-friendly fullscreen controller")) }
                    }
                    if (rideDashboardStreaming) {
                        RideDashboardControls(enabled = dashboardInputReady)
                    } else {
                        Dpad(
                            enabled = androidAutoInputReady,
                            onKey = { code -> AaInputBridge.sendKey(code) },
                            onScroll = { delta -> AaInputBridge.sendScroll(delta) }
                        )
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(motoHubText("Handlebar controls"), style = MaterialTheme.typography.titleMedium)
                            Text(
                                motoHubText("Use the motorcycle buttons without looking down. The active projection receives the commands first."),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = captureEnabled,
                            onCheckedChange = { enabled ->
                                captureEnabled = enabled
                                HandlebarControlStore.setEnabled(context, enabled)
                                val applied = handlebarTarget?.let { target ->
                                    MediaButtonBridge.setTargetCaptureActive(
                                        target,
                                        enabled && handlebarTargetActive
                                    )
                                } ?: false
                                if (enabled && handlebarTargetActive && !applied) {
                                    ProjectionEventLog.warning(
                                        "CONTROLS",
                                        "$handlebarTarget handlebar bridge was not ready when capture was enabled."
                                    )
                                }
                            }
                        )
                    }
                    HandlebarStatus(
                        enabled = captureEnabled,
                        active = handlebarTargetActive,
                        rideDashboard = rideDashboardStreaming
                    )
                }
            }

            // Map theme only ever changes Android Auto's own reported day/night appearance
            // (see AndroidAutoNightModeStore, which is what actually reads this preference) -
            // it has no effect on MOTO-HUB's own native OSM map, which always renders in its
            // fixed dark style regardless of this setting. Hide it while Ride Dashboard is
            // showing OpenStreetMap, where it would visibly do nothing.
            if (showMapTheme) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SectionHeading(
                        eyebrow = "ANDROID AUTO",
                        title = motoHubText("Map appearance"),
                        description = motoHubText("Choose the day/night style used by Android Auto.")
                    )
                    Text(
                        motoHubText(NightPrefs.theme(context).label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val currentTheme = NightPrefs.theme(context)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MapTheme.entries.forEach { theme ->
                            OutlinedButton(
                                onClick = {
                                    NightPrefs.setTheme(context, theme)
                                    // Persisting the preference alone only affects the OSM map,
                                    // which re-reads NightPrefs on its own next draw. Android
                                    // Auto (full-screen or embedded in Ride Dashboard) only
                                    // reads night mode once at connect time, so an already
                                    // running AA session needs this pushed live - the same call
                                    // AndroidAutoPreviewScreen's own theme toggle already makes.
                                    val applied = AndroidAutoPreviewRuntime.setNightMode(NightPrefs.isNightNow(context))
                                    if (!applied) {
                                        ProjectionEventLog.warning(
                                            "UI",
                                            "Map theme set to ${theme.name}, but no Android Auto " +
                                                "session is connected yet to push it to."
                                        )
                                    }
                                    ProjectionEventLog.record(
                                        "UI", "Map theme changed to ${theme.name}"
                                    )
                                },
                                modifier = Modifier.weight(1f).height(40.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = if (theme == currentTheme)
                                    ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.primary
                                    )
                                else ButtonDefaults.outlinedButtonColors()
                            ) { Text(motoHubText(theme.label).take(6), fontWeight = FontWeight.Bold) }
                        }
                    }
                    Text(
                        motoHubText(currentTheme.label),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SectionHeading(
                        eyebrow = "PHONE",
                        title = motoHubText("Media volume"),
                        description = motoHubText("Adjust the phone media level while handlebar capture is active.")
                    )
                    Text(
                        motoHubText("The value is restored when handlebar capture is switched off."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    var volume by remember { mutableStateOf(MediaButtonBridge.volumeLevels(context)) }
                    Slider(
                        value = volume.first.toFloat(),
                        onValueChange = { v ->
                            val level = v.roundToInt()
                            MediaButtonBridge.setVolume(context, level)
                            volume = level to volume.second
                        },
                        valueRange = 0f..volume.second.toFloat().coerceAtLeast(1f),
                        steps = (volume.second - 1).coerceAtLeast(0),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "${volume.first} / ${volume.second}",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CollapsibleHeading(
                        title = motoHubText("Saved places"),
                        description = motoHubText("Destinations available to one-press handlebar navigation."),
                        expanded = showSavedPlaces,
                        onClick = { showSavedPlaces = !showSavedPlaces }
                    )
                    if (showSavedPlaces) {
                        (0 until SavedPlaces.COUNT).forEach { slot ->
                            val savedName = remember(slot) { SavedPlaces.name(context, slot) }
                            val savedQuery = remember(slot) { SavedPlaces.query(context, slot) }
                            var name by remember(slot) { mutableStateOf(savedName) }
                            var query by remember(slot) { mutableStateOf(savedQuery) }
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                label = { Text(motoHubText("Place %1\$d name", slot + 1)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                label = { Text(motoHubText("Address or coordinates")) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedButton(
                                onClick = {
                                    SavedPlaces.set(context, slot, name, query)
                                    ProjectionEventLog.record("CONTROLS", "Saved place ${slot + 1}: $name → $query")
                                },
                                modifier = Modifier.fillMaxWidth().height(38.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) { Text(motoHubText("Save place %1\$d", slot + 1)) }
                        }
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CollapsibleHeading(
                        title = motoHubText("Advanced handlebar mapping"),
                        description = motoHubText("Customize double taps, holds, and Android Auto commands."),
                        expanded = showAdvancedMapping,
                        onClick = { showAdvancedMapping = !showAdvancedMapping }
                    )
                    if (showAdvancedMapping) {
                        Text(motoHubText("Timing"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TimingMenu(
                                title = motoHubText("Double tap"),
                                selected = motoHubText(doubleTapDelay.label),
                                options = DoubleTapDelay.entries.map { motoHubText(it.label) },
                                onSelected = { index ->
                                    doubleTapDelay = DoubleTapDelay.entries[index]
                                    HandlebarTimingPrefs.setDoubleTap(context, doubleTapDelay)
                                },
                                modifier = Modifier.weight(1f)
                            )
                            TimingMenu(
                                title = motoHubText("Select hold"),
                                selected = motoHubText(selectHoldDelay.label),
                                options = SelectHoldDelay.entries.map { motoHubText(it.label) },
                                onSelected = { index ->
                                    selectHoldDelay = SelectHoldDelay.entries[index]
                                    HandlebarTimingPrefs.setSelectHold(context, selectHoldDelay)
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        HandlebarGesture.entries.toList().chunked(2).forEach { group ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                group.forEach { gesture ->
                                    MappingRow(
                                        gesture = gesture,
                                        action = mappings.getValue(gesture),
                                        onActionChanged = { action ->
                                            HandlebarControlStore.setAction(context, gesture, action)
                                            mappings = mappings + (gesture to action)
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (group.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                HandlebarControlStore.reset(context)
                                mappings = HandlebarGesture.entries.associateWith { gesture -> gesture.defaultAction }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(motoHubText("Reset default mapping")) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ModeBadge(label: String, value: String) {
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(motoHubText(label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SectionHeading(eyebrow: String, title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            motoHubText(eyebrow),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        Text(motoHubText(title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(motoHubText(description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CollapsibleHeading(
    title: String,
    description: String,
    expanded: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onClick) { Text(if (expanded) "Hide" else "Open") }
    }
}

@Composable
private fun HandlebarStatus(enabled: Boolean, active: Boolean, rideDashboard: Boolean) {
    val title = when {
        !enabled -> "Handlebar capture is off"
        !active -> "Armed for the next supported session"
        rideDashboard -> "Ride Dashboard has priority"
        else -> "Android Auto receives handlebar input"
    }
    val detail = when {
        !enabled -> "Normal media controls remain untouched."
        !active -> "Start Ride Dashboard or Android Auto to use the buttons."
        rideDashboard -> "Up cycles the dashboard layout · Down toggles the map. Other handlebar gestures are reserved while the dashboard is active."
        else -> "Single, double, and hold gestures use the mapping below."
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .padding(top = 3.dp)
                .size(10.dp)
                .background(
                    if (enabled && active) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline,
                    RoundedCornerShape(50)
                )
        )
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RideDashboardControls(enabled: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = {
                if (!RideDashboardControlBridge.cyclePanels()) {
                    ProjectionEventLog.warning("RIDE_CONTROLS", "Phone Up command was not delivered.")
                }
            },
            enabled = enabled,
            modifier = Modifier.weight(1f).height(72.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("▲", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(motoHubText("Cycle panels"), style = MaterialTheme.typography.labelLarge)
            }
        }
        Button(
            onClick = {
                if (!RideDashboardControlBridge.toggleFullscreenMap()) {
                    ProjectionEventLog.warning("RIDE_CONTROLS", "Phone Down command was not delivered.")
                }
            },
            enabled = enabled,
            modifier = Modifier.weight(1f).height(72.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("▼", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(motoHubText("Fullscreen map"), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * Rows of at most 3 buttons each, so the pad never crowds/wraps on a narrow phone and scales
 * cleanly to the enlarged glove-friendly targets used by [FullscreenDpad]. Scroll is grouped with
 * Up like a rotary-knob step (prev/next), matching the physical dash's knob semantics.
 */
@Composable
private fun Dpad(
    enabled: Boolean,
    onKey: (Int) -> Unit,
    onScroll: (Int) -> Unit
) {
    val buttonModifier = Modifier.size(66.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { onScroll(-1) }, enabled = enabled, modifier = Modifier.height(66.dp)) { Text(motoHubText("Scroll −")) }
            ControlButton("▲", enabled, buttonModifier) { onKey(AaInput.KEY_UP) }
            OutlinedButton(onClick = { onScroll(+1) }, enabled = enabled, modifier = Modifier.height(66.dp)) { Text(motoHubText("Scroll +")) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ControlButton("◀", enabled, buttonModifier) { onKey(AaInput.KEY_LEFT) }
            ControlButton("OK", enabled, buttonModifier) { onKey(AaInput.KEY_ENTER) }
            ControlButton("▶", enabled, buttonModifier) { onKey(AaInput.KEY_RIGHT) }
        }
        ControlButton("▼", enabled, buttonModifier) { onKey(AaInput.KEY_DOWN) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onKey(AaInput.KEY_BACK) }, enabled = enabled) { Text(motoHubText("Back")) }
            OutlinedButton(onClick = { onKey(AaInput.KEY_ASSISTANT) }, enabled = enabled) { Text(motoHubText("Voice")) }
            OutlinedButton(onClick = { onKey(AaInput.KEY_HOME) }, enabled = enabled) { Text(motoHubText("Home")) }
        }
    }
}

@Composable
private fun ControlButton(
    text: String,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier) { Text(text) }
}

/**
 * Driving mode: the same button set as [Dpad], but stretched edge-to-edge with tall glove-friendly
 * hit targets, system bars hidden, and the screen held awake (see [ControlsScreen]'s window-flags
 * effect) — for using Android Auto without touching the dash while riding.
 */
@Composable
private fun FullscreenDpad(
    enabled: Boolean,
    onKey: (Int) -> Unit,
    onScroll: (Int) -> Unit,
    onExit: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onExit) { Text(motoHubText("Exit fullscreen")) }
            }
            Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FullscreenButton("Scroll −", enabled, Modifier.weight(1f).fillMaxHeight()) { onScroll(-1) }
                FullscreenButton("▲", enabled, Modifier.weight(1f).fillMaxHeight()) { onKey(AaInput.KEY_UP) }
                FullscreenButton("Scroll +", enabled, Modifier.weight(1f).fillMaxHeight()) { onScroll(+1) }
            }
            Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FullscreenButton("◀", enabled, Modifier.weight(1f).fillMaxHeight()) { onKey(AaInput.KEY_LEFT) }
                FullscreenButton("OK", enabled, Modifier.weight(1f).fillMaxHeight()) { onKey(AaInput.KEY_ENTER) }
                FullscreenButton("▶", enabled, Modifier.weight(1f).fillMaxHeight()) { onKey(AaInput.KEY_RIGHT) }
            }
            Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Spacer(Modifier.weight(1f))
                FullscreenButton("▼", enabled, Modifier.weight(1f).fillMaxHeight()) { onKey(AaInput.KEY_DOWN) }
                Spacer(Modifier.weight(1f))
            }
            Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FullscreenButton("Back", enabled, Modifier.weight(1f).fillMaxHeight()) { onKey(AaInput.KEY_BACK) }
                FullscreenButton("Voice", enabled, Modifier.weight(1f).fillMaxHeight()) { onKey(AaInput.KEY_ASSISTANT) }
                FullscreenButton("Home", enabled, Modifier.weight(1f).fillMaxHeight()) { onKey(AaInput.KEY_HOME) }
            }
        }
    }
}

@Composable
private fun FullscreenButton(
    text: String,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(18.dp)
    ) {
        Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MappingRow(
    gesture: HandlebarGesture,
    action: HandlebarAction,
    onActionChanged: (HandlebarAction) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(motoHubText(gesture.shortLabel()), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth().height(42.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(motoHubText(action.shortLabel()), maxLines = 1)
                    Text("▾")
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                HandlebarAction.entries.forEach { candidate ->
                    DropdownMenuItem(
                        text = { Text(motoHubText(candidate.label)) },
                        onClick = {
                            expanded = false
                            onActionChanged(candidate)
                        }
                    )
                }
            }
        }
        Text(
            motoHubText(gesture.transportHint.replace("Bluetooth ", "")),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun HandlebarGesture.shortLabel(): String = when (this) {
    HandlebarGesture.VOLUME_UP -> "Up · single"
    HandlebarGesture.VOLUME_UP_DOUBLE -> "Up · double"
    HandlebarGesture.VOLUME_DOWN -> "Down · single"
    HandlebarGesture.VOLUME_DOWN_DOUBLE -> "Down · double"
    HandlebarGesture.ENTER -> "Select · tap"
    HandlebarGesture.ENTER_LONG -> "Select · hold"
    HandlebarGesture.ENTER_DOUBLE -> "Select · double"
    HandlebarGesture.TRACK_BACK -> "Left · single"
    HandlebarGesture.TRACK_BACK_DOUBLE -> "Left · double"
    HandlebarGesture.TRACK_FORWARD -> "Right · single"
    HandlebarGesture.TRACK_FORWARD_DOUBLE -> "Right · double"
}

private fun HandlebarAction.shortLabel(): String = when (this) {
    HandlebarAction.NONE -> "Off"
    HandlebarAction.SCROLL_FORWARD -> "Scroll +"
    HandlebarAction.SCROLL_BACK -> "Scroll −"
    HandlebarAction.DPAD_UP -> "D-pad ▲"
    HandlebarAction.DPAD_DOWN -> "D-pad ▼"
    HandlebarAction.DPAD_LEFT -> "D-pad ◀"
    HandlebarAction.DPAD_RIGHT -> "D-pad ▶"
    HandlebarAction.SELECT -> "OK / select"
    HandlebarAction.BACK -> "Back"
    HandlebarAction.HOME -> "Home"
    HandlebarAction.ASSISTANT -> "Voice"
    HandlebarAction.NAV_1 -> "Nav · place 1"
    HandlebarAction.NAV_2 -> "Nav · place 2"
    HandlebarAction.NAV_3 -> "Nav · place 3"
}

@Composable
private fun TimingMenu(
    title: String,
    selected: String,
    options: List<String>,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth().height(42.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(selected)
                    Text("v")
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEachIndexed { index, option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            expanded = false
                            onSelected(index)
                        }
                    )
                }
            }
        }
    }
}
