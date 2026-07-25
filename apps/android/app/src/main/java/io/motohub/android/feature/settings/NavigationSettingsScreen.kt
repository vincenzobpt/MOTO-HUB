package io.motohub.android.feature.settings

import io.motohub.android.i18n.motoHubText

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.motohub.android.feature.ridedashboard.MapLabelScale
import io.motohub.android.feature.ridedashboard.MapLibreAccentColor
import io.motohub.android.feature.ridedashboard.MapLibreBaseStyle
import io.motohub.android.feature.ridedashboard.MapLibreMapSettings
import io.motohub.android.feature.ridedashboard.MapLibreMapSettingsStore
import io.motohub.android.feature.ridedashboard.OsmBaseStyle
import io.motohub.android.feature.ridedashboard.OsmMapSettings
import io.motohub.android.feature.ridedashboard.OsmMapSettingsStore
import io.motohub.android.feature.ridedashboard.RideDashboardMapSource
import io.motohub.android.feature.ridedashboard.RideDashboardMapSourceStore
import io.motohub.android.feature.ridedashboard.nav.NavigationM2bSettingsStore
import io.motohub.android.feature.ridedashboard.nav.NavigationRuntime
import io.motohub.android.feature.ridedashboard.nav.NavigationSettingsStore
import io.motohub.android.session.ProjectionEventLog
import io.motohub.android.ui.components.MonoLabel
import io.motohub.android.ui.components.MotoHubCardGroup
import io.motohub.android.ui.components.MotoHubDetailScreen
import io.motohub.android.ui.components.MotoHubRadioRow
import io.motohub.android.ui.components.ToggleRow
import io.motohub.android.ui.theme.MotoHubLive
import io.motohub.android.ui.theme.MotoHubMirror

private enum class NavigationDetail { ROUTING_PROVIDER, MAP_STYLE, MAP_COLORS, ROUTE_PREFERENCES, RIDE_INTELLIGENCE }

/** Entry point for the Navigation settings area - a hub of five focused screens rather than one long scroll. */
@Composable
fun NavigationSettingsRoot(onBack: () -> Unit) {
    var sub by rememberSaveable { mutableStateOf<NavigationDetail?>(null) }
    BackHandler(enabled = sub != null) { sub = null }

    // A plain slide+fade AnimatedContent here would nest inside the outer Settings
    // AnimatedContent that is already animating this screen's own entrance - two
    // AnimatedContents racing their size/enter-exit measurement on the same first
    // frame is a known source of a transient bad layout pass. Crossfade only
    // animates alpha, never a joint size interpolation, so it can't trigger that.
    Crossfade(
        targetState = sub,
        label = "navigation-settings"
    ) { current ->
        when (current) {
            null -> NavigationHub(onBack = onBack, onOpenDetail = { sub = it })
            NavigationDetail.ROUTING_PROVIDER -> NavigationRoutingProviderDetail(onBack = { sub = null })
            NavigationDetail.MAP_STYLE -> NavigationMapStyleDetail(onBack = { sub = null })
            NavigationDetail.MAP_COLORS -> NavigationMapColorsDetail(onBack = { sub = null })
            NavigationDetail.ROUTE_PREFERENCES -> NavigationRoutePreferencesDetail(onBack = { sub = null })
            NavigationDetail.RIDE_INTELLIGENCE -> NavigationRideIntelligenceDetail(onBack = { sub = null })
        }
    }
}

@Composable
private fun rememberRideDashboardMapSource(context: android.content.Context) = remember {
    val stored = RideDashboardMapSourceStore.load(context)
    val visible = stored.takeUnless { it == RideDashboardMapSource.ANDROID_AUTO }
        ?: RideDashboardMapSource.OPEN_STREET_MAP
    if (stored == RideDashboardMapSource.ANDROID_AUTO) {
        // Android Auto has no Ride Dashboard appearance controls. Migrate the
        // old hidden selection to the configurable default instead of leaving
        // the settings screen showing a value that is not in its list.
        RideDashboardMapSourceStore.save(context, visible)
    }
    mutableStateOf(visible)
}

@Composable
private fun NavigationHub(onBack: () -> Unit, onOpenDetail: (NavigationDetail) -> Unit) {
    val context = LocalContext.current
    val mapSource by rememberRideDashboardMapSource(context)

    val mapStyleLabel = if (mapSource == RideDashboardMapSource.OPEN_STREET_MAP) {
        OsmMapSettingsStore.load(context).baseStyle.label
    } else {
        MapLibreMapSettingsStore.load(context).baseStyle.label
    }
    // OSM's own "OpenStreetMap standard" style already names the engine, so
    // prefixing it again would just repeat the word and waste width.
    val mapHubValue = if (mapStyleLabel.contains(mapSource.label, ignoreCase = true)) {
        mapStyleLabel
    } else {
        "${mapSource.label} · $mapStyleLabel"
    }
    val swatches = if (mapSource == RideDashboardMapSource.OPEN_STREET_MAP) {
        val s = OsmMapSettingsStore.load(context)
        listOf(s.routeColor, s.positionColor, s.destinationColor, s.curvyRoadColor)
    } else {
        val s = MapLibreMapSettingsStore.load(context)
        listOf(s.routeColor, s.positionColor, s.destinationColor, s.curvyRoadColor)
    }.map { Color(it.argb) }

    val units = MotoHubSettings.distanceUnits(context)
    val routePreference = MotoHubSettings.routePreference(context)

    val m2bSettings = remember { NavigationM2bSettingsStore(context) }
    val ridingIntelOn = listOf(
        m2bSettings.weatherAtArrivalEnabled(),
        m2bSettings.goldenHourEnabled(),
        m2bSettings.fuelRangeWarningEnabled(),
        m2bSettings.curvedSegmentsHighlighted()
    ).count { it }

    val routingValue = when {
        NavigationSettingsStore.hasKey(context) -> "Configured"
        MotoHubSettings.useDemoRoutingServer(context) -> "Demo server"
        else -> "Not set"
    }

    MotoHubDetailScreen(title = motoHubText("Navigation"), backLabel = motoHubText("‹ Settings"), onBack = onBack) {
        NavigationHeroArt()
        Text(
            motoHubText("Routing, map look, and ride-time hints - tuned to how you ride, not a single long list."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        MotoHubCardGroup {
            NavHubRow(
                iconKind = NavFeatureIconKind.ROUTING,
                accent = MaterialTheme.colorScheme.primary,
                title = motoHubText("Routing provider"),
                description = motoHubText("Your own Stadia Maps API key for turn-by-turn"),
                value = routingValue,
                onClick = { onOpenDetail(NavigationDetail.ROUTING_PROVIDER) }
            )
            NavHubRow(
                iconKind = NavFeatureIconKind.MAP_STYLE,
                accent = MotoHubMirror,
                title = motoHubText("Map style"),
                description = motoHubText("Engine, base map, and label size"),
                value = mapHubValue,
                onClick = { onOpenDetail(NavigationDetail.MAP_STYLE) }
            )
            NavHubRow(
                iconKind = NavFeatureIconKind.MAP_COLORS,
                accent = Color(0xFFBE78FF),
                title = motoHubText("Map colors"),
                description = motoHubText("Route, position, destination, curvy roads"),
                swatches = swatches,
                onClick = { onOpenDetail(NavigationDetail.MAP_COLORS) }
            )
            NavHubRow(
                iconKind = NavFeatureIconKind.ROUTE_PREFS,
                accent = Color(0xFFFFBE30),
                title = motoHubText("Route preferences"),
                description = motoHubText("Units, route type, and voice guidance"),
                value = "${units.label} · ${routePreference.label}",
                onClick = { onOpenDetail(NavigationDetail.ROUTE_PREFERENCES) }
            )
            NavHubRow(
                iconKind = NavFeatureIconKind.RIDE_INTEL,
                accent = MotoHubLive,
                title = motoHubText("Ride intelligence"),
                description = motoHubText("Weather, golden hour, fuel range, curvy roads"),
                value = "$ridingIntelOn of 4 on",
                onClick = { onOpenDetail(NavigationDetail.RIDE_INTELLIGENCE) }
            )
        }
    }
}

@Composable
private fun NavigationRoutingProviderDetail(onBack: () -> Unit) {
    val context = LocalContext.current
    var apiKey by rememberSaveable { mutableStateOf("") }
    var hasStoredKey by remember { mutableStateOf(NavigationSettingsStore.hasKey(context)) }
    var savedMessage by remember { mutableStateOf<String?>(null) }

    MotoHubDetailScreen(title = motoHubText("Routing provider"), backLabel = motoHubText("‹ Navigation"), onBack = onBack) {
        Text(
            motoHubText("Turn-by-turn routing uses your own Stadia Maps API key, never a key bundled with the app. Create a free key at stadiamaps.com."),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        MonoLabel(motoHubText(if (hasStoredKey) "KEY CONFIGURED" else "NO KEY SET"))
        OutlinedTextField(
            value = apiKey,
            onValueChange = {
                apiKey = it
                savedMessage = null
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (hasStoredKey) "Replace routing API key" else "Routing API key") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true
        )
        savedMessage?.let { message ->
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    NavigationSettingsStore.save(context, apiKey)
                    hasStoredKey = NavigationSettingsStore.hasKey(context)
                    apiKey = ""
                    savedMessage = if (hasStoredKey) "Key saved." else "Key cleared."
                    ProjectionEventLog.record("SETTINGS", "Navigation routing key changed; configured=$hasStoredKey.")
                },
                enabled = apiKey.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) { Text(motoHubText("Save")) }
            OutlinedButton(
                onClick = {
                    NavigationSettingsStore.clear(context)
                    hasStoredKey = false
                    apiKey = ""
                    savedMessage = "Key cleared."
                    ProjectionEventLog.record("SETTINGS", "Navigation routing key cleared.")
                },
                enabled = hasStoredKey,
                modifier = Modifier.weight(1f)
            ) { Text(motoHubText("Clear")) }
        }
        var useDemoServer by remember { mutableStateOf(MotoHubSettings.useDemoRoutingServer(context)) }
        ToggleRow(
            title = motoHubText("Try without a key (demo server)"),
            description = motoHubText("Use the shared FOSSGIS Valhalla server for occasional testing. It is rate-limited and not intended for regular riding."),
            checked = useDemoServer,
            onCheckedChange = {
                useDemoServer = it
                MotoHubSettings.setUseDemoRoutingServer(context, it)
                ProjectionEventLog.record("SETTINGS", "Demo routing server changed to enabled=$it.")
            }
        )
    }
}

@Composable
private fun NavigationMapStyleDetail(onBack: () -> Unit) {
    val context = LocalContext.current
    var mapSource by rememberRideDashboardMapSource(context)
    var mapSettings by remember { mutableStateOf(MapLibreMapSettingsStore.load(context)) }
    var osmSettings by remember { mutableStateOf(OsmMapSettingsStore.load(context)) }

    MotoHubDetailScreen(title = motoHubText("Map style"), backLabel = motoHubText("‹ Navigation"), onBack = onBack) {
        Text(
            motoHubText("Select the Ride Dashboard map engine and its base style. Android Auto is managed by Android Auto itself and is not configurable here."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        MapAppearanceDropdown(
            label = motoHubText("Map engine"),
            selected = mapSource.label,
            options = listOf(
                RideDashboardMapSource.OPEN_STREET_MAP to RideDashboardMapSource.OPEN_STREET_MAP.label,
                RideDashboardMapSource.MAPLIBRE to RideDashboardMapSource.MAPLIBRE.label
            ),
            onSelected = { source ->
                mapSource = source
                RideDashboardMapSourceStore.save(context, source)
                ProjectionEventLog.record("SETTINGS", "Ride Dashboard map engine changed to ${source.name}.")
            }
        )
        if (mapSource == RideDashboardMapSource.OPEN_STREET_MAP) {
            Text(
                motoHubText("OpenStreetMap uses downloadable raster tiles."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            MapAppearanceDropdown(
                label = motoHubText("Base map style"),
                selected = osmSettings.baseStyle.label,
                options = OsmBaseStyle.entries.map { it to it.label },
                onSelected = { style ->
                    osmSettings = osmSettings.copy(baseStyle = style)
                    OsmMapSettingsStore.save(context, osmSettings)
                }
            )
            MapAppearanceDropdown(
                label = motoHubText("Map text size"),
                selected = osmSettings.labelScale.label,
                options = MapLabelScale.entries.map { it to it.label },
                onSelected = { scale ->
                    osmSettings = osmSettings.copy(labelScale = scale)
                    OsmMapSettingsStore.save(context, osmSettings)
                }
            )
        } else {
            Text(
                motoHubText("MapLibre uses OpenStreetMap vector data and supports configurable map themes."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            MapAppearanceDropdown(
                label = motoHubText("Base map style"),
                selected = mapSettings.baseStyle.label,
                options = MapLibreBaseStyle.entries.map { it to it.label },
                onSelected = { style ->
                    mapSettings = mapSettings.copy(baseStyle = style)
                    MapLibreMapSettingsStore.save(context, mapSettings)
                }
            )
            MapAppearanceDropdown(
                label = motoHubText("Map text size"),
                selected = mapSettings.labelScale.label,
                options = MapLabelScale.entries.map { it to it.label },
                onSelected = { scale ->
                    mapSettings = mapSettings.copy(labelScale = scale)
                    MapLibreMapSettingsStore.save(context, mapSettings)
                }
            )
        }
        Text(
            motoHubText("Changes apply the next time the Ride Dashboard starts."),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun NavigationMapColorsDetail(onBack: () -> Unit) {
    val context = LocalContext.current
    val mapSource by rememberRideDashboardMapSource(context)
    var mapSettings by remember { mutableStateOf(MapLibreMapSettingsStore.load(context)) }
    var osmSettings by remember { mutableStateOf(OsmMapSettingsStore.load(context)) }

    MotoHubDetailScreen(title = motoHubText("Map colors"), backLabel = motoHubText("‹ Navigation"), onBack = onBack) {
        Text(
            motoHubText("Bright, TFT-readable colors for the route line, your position, the destination pin, and curvy-road highlights. Applies to whichever map engine is active."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (mapSource == RideDashboardMapSource.OPEN_STREET_MAP) {
            MapColorDropdowns(settings = osmSettings, onChanged = {
                osmSettings = it
                OsmMapSettingsStore.save(context, it)
            })
        } else {
            MapColorDropdowns(settings = mapSettings, onChanged = {
                mapSettings = it
                MapLibreMapSettingsStore.save(context, it)
            })
        }
    }
}

@Composable
private fun NavigationRoutePreferencesDetail(onBack: () -> Unit) {
    val context = LocalContext.current
    MotoHubDetailScreen(title = motoHubText("Route preferences"), backLabel = motoHubText("‹ Navigation"), onBack = onBack) {
        var units by remember { mutableStateOf(MotoHubSettings.distanceUnits(context)) }
        MonoLabel(motoHubText("UNITS"))
        DistanceUnits.entries.forEach { candidate ->
            MotoHubRadioRow(
                title = context.getString(candidate.labelRes),
                description = context.getString(candidate.descriptionRes),
                selected = units == candidate,
                onClick = {
                    units = candidate
                    MotoHubSettings.setDistanceUnits(context, candidate)
                    ProjectionEventLog.record("SETTINGS", "Navigation distance units changed to ${candidate.name}.")
                }
            )
        }
        MonoLabel(motoHubText("DEFAULT ROUTE TYPE"))
        var routePreference by remember { mutableStateOf(MotoHubSettings.routePreference(context)) }
        RoutePreference.entries.forEach { candidate ->
            MotoHubRadioRow(
                title = context.getString(candidate.labelRes),
                description = context.getString(candidate.descriptionRes),
                selected = routePreference == candidate,
                onClick = {
                    routePreference = candidate
                    MotoHubSettings.setRoutePreference(context, candidate)
                    ProjectionEventLog.record("SETTINGS", "Default route type changed to ${candidate.name}.")
                }
            )
        }
        var voiceEnabled by remember { mutableStateOf(MotoHubSettings.navVoiceEnabled(context)) }
        ToggleRow(
            title = motoHubText("Voice guidance"),
            description = motoHubText("Announce upcoming maneuvers while navigating"),
            checked = voiceEnabled,
            onCheckedChange = {
                voiceEnabled = it
                MotoHubSettings.setNavVoiceEnabled(context, it)
                NavigationRuntime.setVoiceMuted(!it)
                ProjectionEventLog.record("SETTINGS", "Navigation voice guidance changed to enabled=$it.")
            }
        )
    }
}

@Composable
private fun NavigationRideIntelligenceDetail(onBack: () -> Unit) {
    val context = LocalContext.current
    val m2bSettings = remember { NavigationM2bSettingsStore(context) }

    MotoHubDetailScreen(title = motoHubText("Ride intelligence"), backLabel = motoHubText("‹ Navigation"), onBack = onBack) {
        Text(
            motoHubText("Optional information shown in route previews and on the Ride Dashboard map."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        var weatherEnabled by remember { mutableStateOf(m2bSettings.weatherAtArrivalEnabled()) }
        ToggleRow(
            title = motoHubText("Weather at arrival"),
            description = motoHubText("Show forecast conditions at your destination in the route preview"),
            checked = weatherEnabled,
            onCheckedChange = {
                weatherEnabled = it
                m2bSettings.setWeatherAtArrival(it)
                ProjectionEventLog.record("SETTINGS", "Weather at arrival changed to enabled=$it.")
            }
        )
        var goldenHourEnabled by remember { mutableStateOf(m2bSettings.goldenHourEnabled()) }
        ToggleRow(
            title = motoHubText("Golden-hour hint"),
            description = motoHubText("Show minutes to the nearest sunrise/sunset golden hour"),
            checked = goldenHourEnabled,
            onCheckedChange = {
                goldenHourEnabled = it
                m2bSettings.setGoldenHour(it)
                ProjectionEventLog.record("SETTINGS", "Golden-hour hint changed to enabled=$it.")
            }
        )
        var fuelWarningEnabled by remember { mutableStateOf(m2bSettings.fuelRangeWarningEnabled()) }
        ToggleRow(
            title = motoHubText("Fuel range warning"),
            description = motoHubText("Warn when a route would exceed your motorcycle's tank range (set in Garage)"),
            checked = fuelWarningEnabled,
            onCheckedChange = {
                fuelWarningEnabled = it
                m2bSettings.setFuelWarning(it)
                ProjectionEventLog.record("SETTINGS", "Fuel range warning changed to enabled=$it.")
            }
        )
        var curvyHighlightEnabled by remember { mutableStateOf(m2bSettings.curvedSegmentsHighlighted()) }
        ToggleRow(
            title = motoHubText("Highlight curvy roads"),
            description = motoHubText("Draw detected curved segments in a distinct color on the TFT map"),
            checked = curvyHighlightEnabled,
            onCheckedChange = {
                curvyHighlightEnabled = it
                m2bSettings.setCurvyHighlight(it)
                ProjectionEventLog.record("SETTINGS", "Curvy road highlighting changed to enabled=$it.")
            }
        )
    }
}

@Composable
private fun <T> MapAppearanceDropdown(
    label: String,
    selected: String,
    options: List<Pair<T, String>>,
    onSelected: (T) -> Unit
) {
    var expanded by rememberSaveable(label) { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("$label: $selected")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        expanded = false
                        onSelected(value)
                    }
                )
            }
        }
    }
}

@Composable
private fun MapColorDropdowns(
    settings: MapLibreMapSettings,
    onChanged: (MapLibreMapSettings) -> Unit
) {
    MapAppearanceDropdown(
        label = motoHubText("Route color"),
        selected = settings.routeColor.label,
        options = MapLibreAccentColor.entries.map { it to it.label },
        onSelected = { onChanged(settings.copy(routeColor = it)) }
    )
    MapAppearanceDropdown(
        label = motoHubText("GPS position color"),
        selected = settings.positionColor.label,
        options = MapLibreAccentColor.entries.map { it to it.label },
        onSelected = { onChanged(settings.copy(positionColor = it)) }
    )
    MapAppearanceDropdown(
        label = motoHubText("Destination color"),
        selected = settings.destinationColor.label,
        options = MapLibreAccentColor.entries.map { it to it.label },
        onSelected = { onChanged(settings.copy(destinationColor = it)) }
    )
    MapAppearanceDropdown(
        label = motoHubText("Curvy road color"),
        selected = settings.curvyRoadColor.label,
        options = MapLibreAccentColor.entries.map { it to it.label },
        onSelected = { onChanged(settings.copy(curvyRoadColor = it)) }
    )
}

@Composable
private fun MapColorDropdowns(
    settings: OsmMapSettings,
    onChanged: (OsmMapSettings) -> Unit
) {
    MapAppearanceDropdown(
        label = motoHubText("Route color"),
        selected = settings.routeColor.label,
        options = MapLibreAccentColor.entries.map { it to it.label },
        onSelected = { onChanged(settings.copy(routeColor = it)) }
    )
    MapAppearanceDropdown(
        label = motoHubText("GPS position color"),
        selected = settings.positionColor.label,
        options = MapLibreAccentColor.entries.map { it to it.label },
        onSelected = { onChanged(settings.copy(positionColor = it)) }
    )
    MapAppearanceDropdown(
        label = motoHubText("Destination color"),
        selected = settings.destinationColor.label,
        options = MapLibreAccentColor.entries.map { it to it.label },
        onSelected = { onChanged(settings.copy(destinationColor = it)) }
    )
    MapAppearanceDropdown(
        label = motoHubText("Curvy road color"),
        selected = settings.curvyRoadColor.label,
        options = MapLibreAccentColor.entries.map { it to it.label },
        onSelected = { onChanged(settings.copy(curvyRoadColor = it)) }
    )
}

// --- Hub row + hand-drawn icons -------------------------------------------------

private enum class NavFeatureIconKind { ROUTING, MAP_STYLE, MAP_COLORS, ROUTE_PREFS, RIDE_INTEL }

/** A hub row: colored icon chip, title/description, and either a value hint or a strip of color swatches. */
@Composable
private fun NavHubRow(
    iconKind: NavFeatureIconKind,
    accent: Color,
    title: String,
    description: String,
    onClick: () -> Unit,
    value: String? = null,
    swatches: List<Color> = emptyList()
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(accent.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            NavFeatureIcon(iconKind, accent)
        }
        Column(Modifier.weight(1f)) {
            // Fixed to one line so every row's title takes the same vertical
            // space - a wrapped title (e.g. "Route preferences" splitting onto
            // three lines) is what made rows visibly uneven in height before.
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (swatches.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
                swatches.forEach { swatchColor ->
                    Box(
                        Modifier
                            .size(14.dp)
                            .background(swatchColor, CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape)
                    )
                }
            }
        } else if (value != null) {
            // Capped and wrapped onto up to two lines rather than truncated with
            // an ellipsis, so a longer value (e.g. "Kilometers · Fastest") stays
            // fully readable while still leaving the title column enough width.
            Text(
                value,
                modifier = Modifier.widthIn(max = 104.dp),
                style = MaterialTheme.typography.bodySmall,
                color = accent,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End
            )
        }
        Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Small hand-drawn glyphs matching the stroke style already used for the bottom navigation bar's icons. */
@Composable
private fun NavFeatureIcon(kind: NavFeatureIconKind, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(22.dp)) {
        val s = size.width
        val strokeWidth = 1.6.dp.toPx()
        when (kind) {
            NavFeatureIconKind.ROUTING -> {
                drawCircle(color = tint, radius = s * 0.22f, center = Offset(s * 0.32f, s * 0.32f), style = Stroke(strokeWidth))
                drawLine(tint, Offset(s * 0.46f, s * 0.46f), Offset(s * 0.82f, s * 0.82f), strokeWidth, cap = StrokeCap.Round)
                drawLine(tint, Offset(s * 0.68f, s * 0.68f), Offset(s * 0.8f, s * 0.56f), strokeWidth, cap = StrokeCap.Round)
                drawLine(tint, Offset(s * 0.76f, s * 0.76f), Offset(s * 0.88f, s * 0.64f), strokeWidth, cap = StrokeCap.Round)
            }
            NavFeatureIconKind.MAP_STYLE -> {
                listOf(0.34f, 0.5f, 0.66f).forEach { yFrac ->
                    drawFlatDiamond(tint, centerY = s * yFrac, s = s, strokeWidth = strokeWidth)
                }
            }
            NavFeatureIconKind.MAP_COLORS -> {
                val dots = listOf(
                    Offset(s * 0.32f, s * 0.32f) to Color(0xFF2AA4FF),
                    Offset(s * 0.68f, s * 0.30f) to Color(0xFFFF4648),
                    Offset(s * 0.30f, s * 0.68f) to Color(0xFFFFBE30),
                    Offset(s * 0.68f, s * 0.68f) to Color(0xFFBE78FF)
                )
                dots.forEach { (center, color) -> drawCircle(color = color, radius = s * 0.13f, center = center) }
            }
            NavFeatureIconKind.ROUTE_PREFS -> {
                drawLine(tint, Offset(s * 0.5f, s * 0.86f), Offset(s * 0.5f, s * 0.5f), strokeWidth, cap = StrokeCap.Round)
                drawLine(tint, Offset(s * 0.5f, s * 0.5f), Offset(s * 0.22f, s * 0.16f), strokeWidth, cap = StrokeCap.Round)
                drawLine(tint, Offset(s * 0.5f, s * 0.5f), Offset(s * 0.78f, s * 0.16f), strokeWidth, cap = StrokeCap.Round)
                drawCircle(color = tint, radius = s * 0.05f, center = Offset(s * 0.5f, s * 0.86f))
            }
            NavFeatureIconKind.RIDE_INTEL -> {
                val cx = s * 0.5f
                val cy = s * 0.5f
                val outer = s * 0.4f
                val inner = s * 0.14f
                drawLine(tint, Offset(cx, cy - outer), Offset(cx, cy - inner), strokeWidth, cap = StrokeCap.Round)
                drawLine(tint, Offset(cx, cy + inner), Offset(cx, cy + outer), strokeWidth, cap = StrokeCap.Round)
                drawLine(tint, Offset(cx - outer, cy), Offset(cx - inner, cy), strokeWidth, cap = StrokeCap.Round)
                drawLine(tint, Offset(cx + inner, cy), Offset(cx + outer, cy), strokeWidth, cap = StrokeCap.Round)
                drawCircle(color = tint, radius = s * 0.07f, center = Offset(cx, cy))
            }
        }
    }
}

private fun DrawScope.drawFlatDiamond(
    tint: Color,
    centerY: Float,
    s: Float,
    strokeWidth: Float
) {
    val halfW = s * 0.34f
    val halfH = s * 0.12f
    val cx = s * 0.5f
    val top = Offset(cx, centerY - halfH)
    val right = Offset(cx + halfW, centerY)
    val bottom = Offset(cx, centerY + halfH)
    val left = Offset(cx - halfW, centerY)
    drawLine(tint, top, right, strokeWidth, cap = StrokeCap.Round)
    drawLine(tint, right, bottom, strokeWidth, cap = StrokeCap.Round)
    drawLine(tint, bottom, left, strokeWidth, cap = StrokeCap.Round)
    drawLine(tint, left, top, strokeWidth, cap = StrokeCap.Round)
}

// --- Hero art: a heading tape and an animated route, so the hub reads as "Navigation" at a glance ---------------

@Composable
private fun NavigationHeroArt(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val destinationColor = MotoHubLive
    val trackColor = MaterialTheme.colorScheme.outline
    val background = MaterialTheme.colorScheme.background
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)

    val transition = rememberInfiniteTransition(label = "navHero")
    val routeProgress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(3400, easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "routeProgress"
    )
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(1400, easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "pulse"
    )
    val tapeProgress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(42000, easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "tape"
    )

    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(20.dp)) {
            val pxPerDegree = 6.dp.toPx()
            val totalPx = 360f * pxPerDegree
            val baseOffset = tapeProgress * totalPx
            val labels = mapOf(0 to "N", 45 to "NE", 90 to "E", 135 to "SE", 180 to "S", 225 to "SW", 270 to "W", 315 to "NW")
            var deg = 0
            while (deg < 360) {
                var x = (deg * pxPerDegree - baseOffset) % totalPx
                if (x < 0) x += totalPx
                if (x in -20f..(size.width + 20f)) {
                    val isCardinal = deg % 90 == 0
                    val tickHeight = if (isCardinal) size.height * 0.65f else size.height * 0.3f
                    drawLine(
                        color = trackColor,
                        start = Offset(x, size.height),
                        end = Offset(x, size.height - tickHeight),
                        strokeWidth = 1.dp.toPx()
                    )
                    labels[deg]?.let { label ->
                        val measured = textMeasurer.measure(label, labelStyle)
                        drawText(measured, topLeft = Offset(x - measured.size.width / 2f, 0f))
                    }
                }
                deg += 15
            }
        }
        Spacer(Modifier.height(6.dp))
        Canvas(Modifier.fillMaxWidth().height(88.dp)) {
            val path = Path().apply {
                moveTo(size.width * 0.06f, size.height * 0.92f)
                cubicTo(
                    size.width * 0.28f, size.height * 0.95f,
                    size.width * 0.32f, size.height * 0.35f,
                    size.width * 0.58f, size.height * 0.42f
                )
                cubicTo(
                    size.width * 0.78f, size.height * 0.47f,
                    size.width * 0.8f, size.height * 0.12f,
                    size.width * 0.94f, size.height * 0.14f
                )
            }
            drawPath(
                path = path,
                color = primary.copy(alpha = 0.45f),
                style = Stroke(
                    width = 2.5.dp.toPx(),
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 10.dp.toPx()))
                )
            )

            // PathMeasure walks the same curve the dashed line was drawn with, so the moving
            // dot always rides exactly on top of the route rather than an approximation of it.
            val measure = android.graphics.PathMeasure(path.asAndroidPath(), false)
            val pos = floatArrayOf(0f, 0f)
            measure.getPosTan(measure.length * routeProgress, pos, null)
            val point = Offset(pos[0], pos[1])

            drawCircle(color = primary.copy(alpha = 0.22f * (1f - pulse)), radius = 14.dp.toPx() * (0.6f + pulse * 0.9f), center = point)
            drawCircle(color = primary, radius = 4.5.dp.toPx(), center = point)
            drawCircle(color = background, radius = 1.8.dp.toPx(), center = point)

            val end = Offset(size.width * 0.94f, size.height * 0.14f - 2.dp.toPx())
            drawCircle(color = destinationColor.copy(alpha = 0.18f), radius = 13.dp.toPx(), center = end)
            drawCircle(color = destinationColor, radius = 6.dp.toPx(), center = end)
            drawCircle(color = background, radius = 2.4.dp.toPx(), center = end)
        }
    }
}
