package io.motohub.android.feature.navigation

import io.motohub.android.i18n.motoHubText

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import io.motohub.android.feature.home.HubUiState
import io.motohub.android.feature.home.HubViewModel
import io.motohub.android.feature.ridedashboard.RideDashboardMapSource
import io.motohub.android.feature.ridedashboard.RideDashboardMapSourceStore
import io.motohub.android.feature.ridedashboard.RideDashboardRuntime
import io.motohub.android.feature.ridedashboard.RideDashboardRuntimeState
import io.motohub.android.feature.ridedashboard.RideDashboardSessionService
import io.motohub.android.feature.ridedashboard.RideDashboardTrackOverlayRuntime
import io.motohub.android.feature.ridedashboard.nav.NavFormat
import io.motohub.android.feature.ridedashboard.nav.NavPlace
import io.motohub.android.feature.ridedashboard.nav.NavRoute
import io.motohub.android.feature.ridedashboard.nav.NavigationPlacesStore
import io.motohub.android.feature.ridedashboard.nav.SavedPlace
import io.motohub.android.feature.settings.MotoHubSettings
import io.motohub.android.feature.trips.toGpx
import io.motohub.android.session.ProjectionEventLog
import io.motohub.android.session.SessionPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.motohub.android.feature.settings.RoutePreference
import io.motohub.android.ui.components.MonoLabel

@Composable
fun NavigationTabContent(
    viewModel: NavigationViewModel = viewModel(),
    hubViewModel: HubViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val hubState by hubViewModel.uiState.collectAsState()
    val context = LocalContext.current
    var manageFavorites by rememberSaveable { mutableStateOf(false) }
    var showRideDashboardConfirm by rememberSaveable { mutableStateOf(false) }
    var openRideDashboardAfterNavigation by rememberSaveable { mutableStateOf(false) }
    // Set once the T-Box connect has been kicked off so the effect below knows to
    // pick up where the confirm dialog left off and launch the dashboard once
    // (and only once) the connection either succeeds or fails.
    var awaitingTBoxConnect by rememberSaveable { mutableStateOf(false) }

    val navigationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            viewModel.startNavigation()
            if (openRideDashboardAfterNavigation) {
                proceedToRideDashboard(context, hubViewModel, hubState, viewModel) {
                    awaitingTBoxConnect = true
                }
            }
        } else {
            viewModel.showStatusMessage(
                "Allow precise location and notifications to start navigation and record this ride."
            )
        }
    }

    fun startNavigation(openRideDashboard: Boolean) {
        openRideDashboardAfterNavigation = openRideDashboard
        val requiredPermissions = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val permissionsGranted = requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
        if (!permissionsGranted) {
            navigationPermissionLauncher.launch(requiredPermissions.toTypedArray())
            return
        }
        viewModel.startNavigation()
        if (openRideDashboard) {
            proceedToRideDashboard(context, hubViewModel, hubState, viewModel) {
                awaitingTBoxConnect = true
            }
        }
    }

    LaunchedEffect(hubState.session.phase, awaitingTBoxConnect) {
        if (!awaitingTBoxConnect) return@LaunchedEffect
        when (hubState.session.phase) {
            SessionPhase.READY -> {
                awaitingTBoxConnect = false
                launchRideDashboardWithSelectedMap(context, hubViewModel, hubState)
            }
            SessionPhase.ERROR -> {
                awaitingTBoxConnect = false
                viewModel.showStatusMessage(hubState.session.message)
            }
            else -> Unit
        }
    }

    if (showRideDashboardConfirm) {
        RideDashboardLaunchDialog(
            onYes = {
                showRideDashboardConfirm = false
                startNavigation(openRideDashboard = true)
            },
            onNo = {
                showRideDashboardConfirm = false
                startNavigation(openRideDashboard = false)
            }
        )
    }

    val current = when {
        uiState.arrived != null -> NavScreen.ARRIVAL
        uiState.preview != null -> NavScreen.PREVIEW
        manageFavorites -> NavScreen.FAVORITES
        else -> NavScreen.HOME
    }

    BackHandler(enabled = current != NavScreen.HOME) {
        when (current) {
            NavScreen.PREVIEW -> viewModel.cancelPreview()
            NavScreen.ARRIVAL -> viewModel.dismissArrival()
            NavScreen.FAVORITES -> manageFavorites = false
            NavScreen.HOME -> Unit
        }
    }

    AnimatedContent(
        targetState = current,
        transitionSpec = {
            if (targetState != NavScreen.HOME) {
                (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut())
            } else {
                (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { it / 3 } + fadeOut())
            }
        },
        label = "nav"
    ) { screen ->
        when (screen) {
            NavScreen.HOME -> NavHome(
                state = uiState,
                onQueryChange = viewModel::onQueryChange,
                onPreviewPlace = viewModel::previewDestination,
                onPreviewSaved = viewModel::previewSavedPlace,
                onPreviewSavedRide = viewModel::previewSavedRide,
                onNavigateHome = viewModel::navigateHome,
                onRemoveRecent = viewModel::removeRecent,
                onRemoveSavedRide = viewModel::removeSavedRide,
                onManageFavorites = { manageFavorites = true },
                onStop = viewModel::stopNavigation,
                onRecalculate = viewModel::recalculate,
                onToggleVoice = viewModel::toggleVoiceMuted,
                onClearStatus = viewModel::clearStatus
            )
            NavScreen.PREVIEW -> uiState.preview?.let { preview ->
                RoutePreviewScreen(
                    preview = preview,
                    units = uiState.units,
                    isRouting = uiState.isRouting,
                    onPreference = viewModel::setPreviewPreference,
                    onAdjustDestination = viewModel::adjustPreviewDestination,
                    onStart = { showRideDashboardConfirm = true },
                    onSaveFavorite = viewModel::saveFavorite,
                    onSaveRide = viewModel::saveRide,
                    onBack = viewModel::cancelPreview
                )
            }
            NavScreen.ARRIVAL -> uiState.arrived?.let { arrival ->
                ArrivalScreen(
                    destination = arrival.destination,
                    onSaveFavorite = viewModel::saveFavorite,
                    onDone = viewModel::dismissArrival
                )
            }
            NavScreen.FAVORITES -> FavoritesScreen(
                favorites = uiState.favorites,
                onRemove = viewModel::removeFavorite,
                onOpen = { place ->
                    manageFavorites = false
                    viewModel.previewSavedPlace(place)
                },
                onBack = { manageFavorites = false }
            )
        }
    }
}

private enum class NavScreen { HOME, PREVIEW, ARRIVAL, FAVORITES }

/**
 * Routes a confirmed "Start navigation" toward the Ride Dashboard: launches
 * immediately if the T-Box is already connected, kicks off a connection
 * attempt (whose result [NavigationTabContent]'s effect picks up) otherwise,
 * or reports why neither is possible.
 */
private fun proceedToRideDashboard(
    context: Context,
    hubViewModel: HubViewModel,
    hubState: HubUiState,
    navViewModel: NavigationViewModel,
    onAwaitConnect: () -> Unit
) {
    val dashboardAlreadyActive = RideDashboardRuntime.state.value.let {
        it is RideDashboardRuntimeState.Starting || it is RideDashboardRuntimeState.Streaming
    }
    if (dashboardAlreadyActive) return

    when {
        hubState.session.motorcycle == null ->
            navViewModel.showStatusMessage(motoHubText("Pair a motorcycle in Garage before starting navigation."))
        hubState.session.phase == SessionPhase.READY ->
            launchRideDashboardWithSelectedMap(context, hubViewModel, hubState)
        hubState.session.phase == SessionPhase.REQUESTING_PROJECTION ||
            hubState.session.phase == SessionPhase.CAPTURING ->
            navViewModel.showStatusMessage(
                motoHubText("Stop the current mirroring or Android Auto session before opening the Ride Dashboard from here.")
            )
        else -> {
            onAwaitConnect()
            hubViewModel.connectAndDiscover()
        }
    }
}

/** Starts the native Ride Dashboard with the map engine selected in Settings → Navigation. */
private fun launchRideDashboardWithSelectedMap(
    context: Context,
    hubViewModel: HubViewModel,
    hubState: HubUiState
) {
    val selectedMapSource = RideDashboardMapSourceStore.load(context)
    RideDashboardTrackOverlayRuntime.setEnabled(MotoHubSettings.showRecordedTrackOnDashboard(context))
    hubViewModel.onRideDashboardRequested()
    RideDashboardSessionService.start(context, selectedMapSource)
}

@Composable
private fun RideDashboardLaunchDialog(onYes: () -> Unit, onNo: () -> Unit) {
    var secondsLeft by remember { mutableStateOf(RIDE_DASHBOARD_COUNTDOWN_SECONDS) }
    var triggered by remember { mutableStateOf(false) }

    fun triggerYes() {
        if (triggered) return
        triggered = true
        onYes()
    }

    LaunchedEffect(Unit) {
        while (secondsLeft > 0) {
            delay(1_000)
            secondsLeft -= 1
        }
        triggerYes()
    }

    AlertDialog(
        onDismissRequest = onNo,
        title = { Text(motoHubText("Open Ride Dashboard?")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(motoHubText("Navigation will start either way. Open the Ride Dashboard on the motorcycle TFT?"))
                LinearProgressIndicator(
                    progress = { secondsLeft / RIDE_DASHBOARD_COUNTDOWN_SECONDS.toFloat() },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    motoHubText("Opening in %1\$d s…", secondsLeft),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = ::triggerYes) { Text(motoHubText("YES")) }
        },
        dismissButton = {
            TextButton(onClick = onNo) { Text(motoHubText("NO")) }
        }
    )
}

private const val RIDE_DASHBOARD_COUNTDOWN_SECONDS = 5

@Composable
private fun NavHome(
    state: NavigationUiState,
    onQueryChange: (String) -> Unit,
    onPreviewPlace: (NavPlace) -> Unit,
    onPreviewSaved: (SavedPlace) -> Unit,
    onPreviewSavedRide: (io.motohub.android.feature.ridedashboard.nav.SavedRide) -> Unit,
    onNavigateHome: () -> Unit,
    onRemoveRecent: (SavedPlace) -> Unit,
    onRemoveSavedRide: (io.motohub.android.feature.ridedashboard.nav.SavedRide) -> Unit,
    onManageFavorites: () -> Unit,
    onStop: () -> Unit,
    onRecalculate: () -> Unit,
    onToggleVoice: () -> Unit,
    onClearStatus: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        NavPageHeader(
            eyebrow = "NAVIGATION",
            title = if (state.navigationActive) "Your route is live" else "Where are you going?",
            subtitle = if (state.navigationActive) {
                "Manage guidance, search a new destination, or resume your route."
            } else {
                "Search a destination, pick a favorite, or start from a saved route."
            }
        )

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(4.dp),
                label = { Text(motoHubText("Search address or paste coordinates")) },
                singleLine = true,
                trailingIcon = {
                    if (state.isSearching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                },
                shape = RoundedCornerShape(16.dp)
            )
        }

        state.status?.let { status -> StatusBanner(status, onClearStatus) }

        if (state.isRouting) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(motoHubText("Calculating your route…"), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (state.query.isNotBlank()) {
            NavSectionHeader("SEARCH RESULTS")
            state.suggestions.forEach { place ->
                PlaceRow(title = place.label, onClick = { onPreviewPlace(place) })
            }
            if (state.suggestions.isEmpty() && !state.isSearching) {
                Text(
                    motoHubText("No matches."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Column
        }

        if (state.navigationActive) {
            ActiveNavigationCard(
                state = state,
                onStop = onStop,
                onRecalculate = onRecalculate,
                onToggleVoice = onToggleVoice
            )
        }

        state.home?.let { home ->
            NavActionCard(
                title = motoHubText("Take me home"),
                description = home.label,
                onClick = onNavigateHome,
                prominent = true
            )
        }

        if (state.recents.isNotEmpty()) {
            NavSectionHeader("RECENT DESTINATIONS")
            state.recents.forEach { place ->
                PlaceRow(
                    title = place.label,
                    onClick = { onPreviewSaved(place) },
                    onRemove = { onRemoveRecent(place) }
                )
            }
        }

        NavSectionHeader("FAVORITES", action = "Manage", onAction = onManageFavorites)
        if (state.favorites.isEmpty()) {
            Text(
                motoHubText("Save a destination as a favorite from a route preview."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            state.favorites.forEach { place ->
                PlaceRow(
                    title = place.favoriteName ?: place.label,
                    subtitle = place.favoriteName?.let { place.label },
                    onClick = { onPreviewSaved(place) }
                )
            }
        }

        if (state.savedRides.isNotEmpty()) {
            NavSectionHeader("SAVED ROUTES")
            state.savedRides.forEach { ride ->
                PlaceRow(
                    title = ride.label ?: ride.destination.label,
                    subtitle = ride.label?.let { ride.destination.label },
                    onClick = { onPreviewSavedRide(ride) },
                    onRemove = { onRemoveSavedRide(ride) }
                )
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ActiveNavigationCard(
    state: NavigationUiState,
    onStop: () -> Unit,
    onRecalculate: () -> Unit,
    onToggleVoice: () -> Unit
) {
    var confirmStop by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            MonoLabel(motoHubText("LIVE NAVIGATION"))
            val remaining = state.progress?.distanceRemainingMeters
            Text(
                if (remaining != null) {
                    motoHubText("%1\$s remaining", NavFormat.distance(remaining, state.units))
                } else {
                    motoHubText("Guiding to destination")
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            state.progress?.currentManeuver?.instruction?.takeIf { it.isNotBlank() }?.let { instruction ->
                Text(instruction, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            if (state.progress?.offRoute == true) {
                Text(motoHubText("Off route – recalculating…"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // Compact horizontal padding - the default Material3 button padding
                // (24.dp a side) leaves too little room for text in a 3-way equal
                // split and wraps "Unmute"/"Recalc" onto a second line.
                val compactPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                OutlinedButton(
                    onClick = onToggleVoice,
                    modifier = Modifier.weight(1f),
                    contentPadding = compactPadding
                ) {
                    Text(motoHubText(if (state.voiceMuted) "Voice on" else "Mute"))
                }
                OutlinedButton(
                    onClick = onRecalculate,
                    modifier = Modifier.weight(1f),
                    contentPadding = compactPadding
                ) {
                    Text(motoHubText("Reroute"))
                }
                Button(
                    onClick = { confirmStop = true },
                    modifier = Modifier.weight(1f),
                    contentPadding = compactPadding,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(motoHubText("Stop"))
                }
            }
        }
    }
    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(motoHubText("Stop navigation?")) },
            text = { Text(motoHubText("Guidance to your destination will end.")) },
            confirmButton = {
                TextButton(onClick = { confirmStop = false; onStop() }) { Text(motoHubText("Stop")) }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(motoHubText("Keep going")) } }
        )
    }
}

@Composable
private fun NavPageHeader(eyebrow: String, title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MonoLabel(motoHubText(eyebrow))
        Text(motoHubText(title), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
        Text(motoHubText(subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NavSectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        MonoLabel(motoHubText(title))
        if (action != null && onAction != null) {
            TextButton(onClick = onAction) { Text(motoHubText(action)) }
        }
    }
}

@Composable
private fun NavActionCard(
    title: String,
    description: String,
    onClick: () -> Unit,
    prominent: Boolean = false
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (prominent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = if (prominent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RoutePreviewScreen(
    preview: RoutePreview,
    units: io.motohub.android.feature.settings.DistanceUnits,
    isRouting: Boolean,
    onPreference: (RoutePreference) -> Unit,
    onAdjustDestination: (io.motohub.android.feature.ridedashboard.nav.NavPoint) -> Unit,
    onStart: () -> Unit,
    onSaveFavorite: (NavPlace, String) -> Unit,
    onSaveRide: (String?) -> Unit,
    onBack: () -> Unit
) {
    var saving by remember { mutableStateOf(false) }
    var savingRide by remember { mutableStateOf(false) }
    var mapFullscreen by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val route = preview.route
        val label = preview.destination.label
        scope.launch(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { writer ->
                    writer.write(route.toGpx(label))
                } ?: error("Android did not open the selected document")
            }.onSuccess {
                ProjectionEventLog.record("RIDE_NAV", "GPX exported for route to $label.")
            }.onFailure { failure ->
                ProjectionEventLog.error("RIDE_NAV", "GPX export failed for route to $label.", failure)
            }
        }
    }
    BackHandler(enabled = mapFullscreen) { mapFullscreen = false }
    if (mapFullscreen) {
        RouteMapFullscreen(
            points = preview.route.points,
            onAdjustDestination = onAdjustDestination,
            onExit = { mapFullscreen = false }
        )
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        BackLink("Search", onBack)
        NavPageHeader(
            eyebrow = "ROUTE PREVIEW",
            title = preview.destination.label,
            subtitle = motoHubText("Review your route before starting guidance.")
        )

        Card(
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF20261A)),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                MonoLabel(motoHubText("INFO"))
                Text(
                    motoHubText("Not every address number is mapped in OpenStreetMap yet, so the pin ") +
                        "may land near the destination rather than exactly on it. Zoom in on " +
                        "the map below and tap the exact spot to recalculate the route there.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                MonoLabel(motoHubText("MAP LOCATION"))
                Text(
                    preview.effectiveAddress.ifBlank { preview.destination.label },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    motoHubText("Exact destination pin used for routing. Tap the map to change it."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
        }

        RouteMap(
            points = preview.route.points,
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
            onAdjustDestination = onAdjustDestination,
            onFullscreen = { mapFullscreen = true },
            visualScale = ROUTE_MAP_VISUAL_SCALE
        )

        Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                PreviewStat("DISTANCE", NavFormat.distance(preview.route.distanceMeters, units))
                PreviewStat("TIME", NavFormat.duration(preview.route.durationSeconds))
                PreviewStat("ARRIVAL", NavFormat.arrivalClock(System.currentTimeMillis(), preview.route.durationSeconds))
            }
        }

        M2bWeatherCard(preview.route)
        M2bFuelWarning(preview.route)
        M2bGoldenHourCard(preview.route)
        M2bCurvyInfo(preview.route)

        MonoLabel(motoHubText("ROUTE TYPE"))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RoutePreference.entries.forEach { candidate ->
                val selected = preview.preference == candidate
                OutlinedButton(
                    onClick = { onPreference(candidate) },
                    modifier = Modifier.weight(1f),
                    colors = if (selected) {
                        ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    } else {
                        ButtonDefaults.outlinedButtonColors()
                    }
                ) {
                    Text(candidate.label, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
            }
        }

        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth().height(58.dp),
            enabled = !isRouting,
            shape = RoundedCornerShape(18.dp)
        ) { Text(motoHubText("Start navigation"), fontWeight = FontWeight.Bold) }

        NavSectionHeader("ROUTE OPTIONS")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            NavCompactAction("Save route", Modifier.weight(1f)) { savingRide = true }
            NavCompactAction("Favorite", Modifier.weight(1f)) { saving = true }
        }
        NavCompactAction("Export GPX", Modifier.fillMaxWidth()) {
            exportLauncher.launch("${preview.destination.label}.gpx")
        }
    }
    if (saving) {
        SaveFavoriteDialog(
            onSave = { name -> saving = false; onSaveFavorite(preview.destination, name) },
            onDismiss = { saving = false }
        )
    }
    if (savingRide) {
        SaveRideDialog(
            onSave = { label -> savingRide = false; onSaveRide(label) },
            onDismiss = { savingRide = false }
        )
    }
}

@Composable
private fun RouteMapFullscreen(
    points: List<io.motohub.android.feature.ridedashboard.nav.NavPoint>,
    onAdjustDestination: (io.motohub.android.feature.ridedashboard.nav.NavPoint) -> Unit,
    onExit: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        RouteMap(
            points = points,
            modifier = Modifier.fillMaxSize(),
            onAdjustDestination = onAdjustDestination,
            showPoiControls = true,
            visualScale = ROUTE_MAP_VISUAL_SCALE
        )
        OutlinedButton(
            onClick = onExit,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = Color(0xE6141B17),
                contentColor = Color.White
            ),
            border = BorderStroke(1.dp, Color(0x33FFFFFF))
        ) {
            Text(motoHubText("Exit fullscreen"))
        }
    }
}

private const val ROUTE_MAP_VISUAL_SCALE = 2f

@Composable
private fun ArrivalScreen(
    destination: NavPlace,
    onSaveFavorite: (NavPlace, String) -> Unit,
    onDone: () -> Unit
) {
    var saving by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MonoLabel(motoHubText("ARRIVED"))
                Text(motoHubText("You have arrived"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(destination.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Spacer(Modifier.height(16.dp))
        NavActionCard("Save as favorite", "Keep this destination ready for the next ride.", { saving = true })
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)) { Text(motoHubText("Done")) }
    }
    if (saving) {
        SaveFavoriteDialog(
            onSave = { name -> saving = false; onSaveFavorite(destination, name) },
            onDismiss = { saving = false }
        )
    }
}

@Composable
private fun FavoritesScreen(
    favorites: List<SavedPlace>,
    onRemove: (SavedPlace) -> Unit,
    onOpen: (SavedPlace) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        BackLink("Navigation", onBack)
        NavPageHeader("SAVED DESTINATIONS", "Favorites", "Your quickest way back to the places you ride to most.")
        if (favorites.isEmpty()) {
            Text(
                motoHubText("No favorites yet. Save one from a route preview or an arrival screen."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        favorites.forEach { place ->
            PlaceRow(
                title = place.favoriteName ?: place.label,
                subtitle = place.favoriteName?.let { place.label },
                onClick = { onOpen(place) },
                onRemove = { onRemove(place) }
            )
        }
    }
}

@Composable
private fun SaveFavoriteDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(motoHubText("Save favorite")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { name = NavigationPlacesStore.HOME }) { Text(motoHubText("Home")) }
                    TextButton(onClick = { name = NavigationPlacesStore.WORK }) { Text(motoHubText("Work")) }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(motoHubText("Name")) },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onSave(name) }, enabled = name.isNotBlank()) { Text(motoHubText("Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(motoHubText("Cancel")) } }
    )
}

@Composable
private fun SaveRideDialog(onSave: (String?) -> Unit, onDismiss: () -> Unit) {
    var label by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(motoHubText("Save this route")) },
        text = {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text(motoHubText("Optional label")) },
                placeholder = { Text(motoHubText("e.g., Sunday loop")) },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(label.takeIf { it.isNotBlank() }) }) { Text(motoHubText("Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(motoHubText("Cancel")) } }
    )
}

@Composable
private fun StatusBanner(status: NavStatus, onDismiss: () -> Unit) {
    val message = when (status) {
        NavStatus.NoApiKey ->
            "Add a routing API key in Settings › Navigation to calculate routes, or turn on the free demo server there."
        NavStatus.NoNetwork -> "No cellular data available for routing right now."
        NavStatus.RateLimited -> "Routing service is temporarily rate-limited. Try again shortly."
        is NavStatus.Message -> status.text
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onDismiss),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                motoHubText("NAVIGATION NEEDS ATTENTION"),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(
                motoHubText("Tap to dismiss"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.75f)
            )
        }
    }
}

@Composable
private fun PreviewStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PlaceRow(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            onRemove?.let {
                TextButton(onClick = it) { Text(motoHubText("Remove")) }
            } ?: Text(
                "›",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun NavCompactAction(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
    ) {
        Text(motoHubText(label), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun BackLink(label: String, onBack: () -> Unit) {
    Text(
        motoHubText("‹ %1\$s", label),
        modifier = Modifier
            .clickable(onClick = onBack)
            .padding(vertical = 6.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold
    )
}
