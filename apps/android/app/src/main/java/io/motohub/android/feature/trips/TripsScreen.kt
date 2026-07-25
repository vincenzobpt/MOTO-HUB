package io.motohub.android.feature.trips

import io.motohub.android.i18n.motoHubText

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.motohub.android.feature.settings.DistanceUnits
import io.motohub.android.feature.settings.MotoHubSettings
import io.motohub.android.session.ProjectionEventLog
import io.motohub.android.ui.components.LivePill
import io.motohub.android.ui.components.MonoLabel
import io.motohub.android.units.UnitFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun TripsTabContent(
    recordingState: TripRecordingState,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onDiscardRecording: () -> Unit
) {
    val context = LocalContext.current
    val store = remember(context) { TripStore(context) }
    val scope = rememberCoroutineScope()
    var trips by remember { mutableStateOf<List<TripSummary>>(emptyList()) }
    var libraryStats by remember { mutableStateOf(TripLibraryStats()) }
    var selectedTripId by remember { mutableStateOf<String?>(null) }
    var selectedTrip by remember { mutableStateOf<TripDetails?>(null) }
    var refreshToken by remember { mutableIntStateOf(0) }
    var renameTrip by remember { mutableStateOf<TripSummary?>(null) }
    var deleteTrip by remember { mutableStateOf<TripSummary?>(null) }
    var exportTrip by remember { mutableStateOf<TripDetails?>(null) }
    val recordingActive = recordingState is TripRecordingState.Recording

    BackHandler(enabled = selectedTripId != null) { selectedTripId = null }

    LaunchedEffect(recordingState) {
        val finished = recordingState as? TripRecordingState.Finished ?: return@LaunchedEffect
        refreshToken++
        finished.savedTripId?.let { selectedTripId = it }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { uri ->
        val details = exportTrip
        exportTrip = null
        if (uri == null || details == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { writer ->
                    writer.write(details.toGpx())
                } ?: error("Android did not open the selected document")
            }.onSuccess {
                ProjectionEventLog.record("TRIPS", "GPX exported for trip ${details.summary.id}.")
            }.onFailure { failure ->
                ProjectionEventLog.error("TRIPS", "GPX export failed.", failure)
            }
        }
    }

    LaunchedEffect(refreshToken, recordingActive, selectedTripId) {
        val snapshot = withContext(Dispatchers.IO) {
            Triple(store.listTrips(), store.libraryStats(), selectedTripId?.let(store::getTrip))
        }
        trips = snapshot.first
        libraryStats = snapshot.second
        selectedTrip = snapshot.third
    }

    selectedTrip?.let { details ->
        TripDetailsContent(
            details = details,
            onBack = { selectedTripId = null },
            onRename = { renameTrip = details.summary },
            onExport = {
                exportTrip = details
                exportLauncher.launch("MOTO-HUB-${details.summary.startedAtMillis}.gpx")
            },
            onDelete = { deleteTrip = details.summary }
        )
    } ?: TripsListContent(
        recordingState = recordingState,
        trips = trips,
        stats = libraryStats,
        onStartRecording = onStartRecording,
        onStopRecording = onStopRecording,
        onDiscardRecording = onDiscardRecording,
        onSelectTrip = { selectedTripId = it }
    )

    renameTrip?.let { trip ->
        var name by remember(trip.id) { mutableStateOf(trip.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renameTrip = null },
            title = { Text(motoHubText("Name this ride")) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(80) },
                    label = { Text(motoHubText("Trip name")) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        store.rename(trip.id, name)
                        withContext(Dispatchers.Main) {
                            renameTrip = null
                            refreshToken++
                        }
                    }
                }) { Text(motoHubText("Save")) }
            },
            dismissButton = { TextButton(onClick = { renameTrip = null }) { Text(motoHubText("Cancel")) } }
        )
    }

    deleteTrip?.let { trip ->
        AlertDialog(
            onDismissRequest = { deleteTrip = null },
            title = { Text(motoHubText("Delete this trip?")) },
            text = { Text(motoHubText("The GPS trace and all recorded statistics will be permanently removed.")) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        store.delete(trip.id)
                        withContext(Dispatchers.Main) {
                            deleteTrip = null
                            selectedTripId = null
                            refreshToken++
                        }
                    }
                }) { Text(motoHubText("Delete")) }
            },
            dismissButton = { TextButton(onClick = { deleteTrip = null }) { Text(motoHubText("Cancel")) } }
        )
    }
}

@Composable
private fun TripsListContent(
    recordingState: TripRecordingState,
    trips: List<TripSummary>,
    stats: TripLibraryStats,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onDiscardRecording: () -> Unit,
    onSelectTrip: (String) -> Unit
) {
    val units = currentDistanceUnits()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MonoLabel(motoHubText("RIDE ARCHIVE"))
            Text(motoHubText("Trips"), style = MaterialTheme.typography.displaySmall)
        }

        RecordingCard(recordingState, onStartRecording, onStopRecording, onDiscardRecording)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            MiniStat("RIDES", stats.tripCount.toString(), Modifier.weight(1f))
            MiniStat("TOTAL", formatTripDistance(stats.totalDistanceMeters, units), Modifier.weight(1f))
            MiniStat("TIME", formatTripDuration(stats.totalMovingTimeMillis), Modifier.weight(1f))
        }

        if (trips.isNotEmpty()) {
            MonoLabel(motoHubText("RECORDED RIDES"))
            trips.forEach { trip -> TripListCard(trip, onSelectTrip) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun RecordingCard(
    state: TripRecordingState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDiscard: () -> Unit
) {
    val units = currentDistanceUnits()
    when (state) {
        TripRecordingState.Idle -> {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.large
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(motoHubText("Record a ride"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        motoHubText("GPS logging works with the phone locked"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = onStart,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text(motoHubText("Start recording"), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        is TripRecordingState.Failed -> {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.large
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MonoLabel(motoHubText("RECORDING UNAVAILABLE"))
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text(motoHubText("Try again")) }
                }
            }
        }
        is TripRecordingState.Finished -> {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.large
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (state.savedTripId != null) {
                        MonoLabel(motoHubText("SAVED"))
                        Text(motoHubText("Trip saved to Recorded rides"), style = MaterialTheme.typography.titleMedium)
                        Text(
                            motoHubText("Opening the saved trip now."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        MonoLabel(motoHubText("NOT SAVED"))
                        Text(motoHubText("Recording was too short to keep"), style = MaterialTheme.typography.titleMedium)
                        Text(
                            motoHubText("Ride recordings need at least 100 m, 15 s of movement, and two GPS points."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = onStart,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(motoHubText("Start new recording")) }
                }
            }
        }
        is TripRecordingState.Recording -> {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                ),
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
                shape = MaterialTheme.shapes.large
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Column {
                            MonoLabel(motoHubText("RECORDING · %1\$s", motoHubText(state.source.label).uppercase()))
                            Text(
                                String.format(Locale.US, "%.0f", UnitFormat.speed(state.speedKmh, units)),
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = MaterialTheme.typography.displaySmall.fontSize
                            )
                            Text(
                                motoHubText(UnitFormat.speedLabel(units)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            if (state.hasFix) {
                                motoHubText("GPS ±%1\$d m", state.accuracyMeters?.roundToInt() ?: 0)
                            } else {
                                motoHubText("GPS SEARCH")
                            },
                            color = if (state.hasFix) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        MiniStat("DISTANCE", formatTripDistance(state.distanceMeters, units), Modifier.weight(1f))
                        MiniStat("MOVING", formatTripDuration(state.movingTimeMillis), Modifier.weight(1f))
                        MiniStat("MAX", formatSpeed(state.maxSpeedKmh, units), Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onStop,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Text(motoHubText("Finish & save"), fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = onDiscard,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Text(motoHubText("Discard"))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TripListCard(trip: TripSummary, onSelectTrip: (String) -> Unit) {
    val units = currentDistanceUnits()
    val title = trip.name ?: SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault())
        .format(Date(trip.startedAtMillis))
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(trip.startedAtMillis))
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelectTrip(trip.id) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        motoHubText("%1\$s · %2\$s", time, motoHubText(trip.source.label)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    motoHubText("VIEW"),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                MiniStat("DIST", formatTripDistance(trip.distanceMeters, units), Modifier.weight(1f))
                MiniStat("AVG", formatSpeed(trip.averageSpeedKmh, units), Modifier.weight(1f))
                MiniStat("MAX", formatSpeed(trip.maxSpeedKmh, units), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TripDetailsContent(
    details: TripDetails,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    val units = currentDistanceUnits()
    val trip = details.summary
    val title = trip.name ?: "Recorded ride"
    val date = SimpleDateFormat("EEEE, d MMMM yyyy · HH:mm", Locale.getDefault())
        .format(Date(trip.startedAtMillis))
    val elevationGain = details.points.zipWithNext().sumOf { (first, second) ->
        val delta = (second.altitudeMeters ?: return@sumOf 0.0) - (first.altitudeMeters ?: return@sumOf 0.0)
        delta.takeIf { it in 0.5..50.0 } ?: 0.0
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(4.dp))
        Text(
            motoHubText("‹ Trips"),
            modifier = Modifier
                .clickable(onClick = onBack)
                .padding(vertical = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            LivePill(motoHubText("RECORDED ROUTE"))
            Text(title, style = MaterialTheme.typography.displaySmall)
            Text(date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TripMap(details.points, Modifier.fillMaxWidth().height(300.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            MiniStat("DISTANCE", formatTripDistance(trip.distanceMeters, units), Modifier.weight(1f))
            MiniStat("MOVING", formatTripDuration(trip.movingTimeMillis), Modifier.weight(1f))
            MiniStat("ELAPSED", formatTripDuration(trip.elapsedTimeMillis), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            MiniStat("AVG SPEED", formatSpeed(trip.averageSpeedKmh, units), Modifier.weight(1f))
            MiniStat("MAX SPEED", formatSpeed(trip.maxSpeedKmh, units), Modifier.weight(1f))
            MiniStat(
                "ELEVATION +",
                "${UnitFormat.altitudeValue(elevationGain, units)} ${UnitFormat.altitudeLabel(units).lowercase()}",
                Modifier.weight(1f)
            )
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.large
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MonoLabel(motoHubText("TRACK DETAILS"))
                Text(motoHubText("%1\$d optimized GPS points", trip.pointCount), style = MaterialTheme.typography.bodyMedium)
                Text(motoHubText(trip.source.label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Button(
            onClick = onRename,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(motoHubText("Rename trip"))
        }
        OutlinedButton(
            onClick = onExport,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Text(motoHubText("Export GPX"))
        }
        TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
            Text(motoHubText("Delete trip"), color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            motoHubText(label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

/** The rider's unit preference, re-read on each composition so a settings change shows on return. */
@Composable
internal fun currentDistanceUnits(): DistanceUnits =
    MotoHubSettings.distanceUnits(LocalContext.current)

/** "87 km/h" or "54 mph" from a metric-native km/h value. */
internal fun formatSpeed(kmh: Float, units: DistanceUnits): String =
    "${UnitFormat.speed(kmh, units).roundToInt()} ${UnitFormat.speedLabelLower(units)}"
