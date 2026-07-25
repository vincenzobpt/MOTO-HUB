package io.motohub.android.feature.ridedashboard

import io.motohub.android.i18n.motoHubText

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface as MaterialSurface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.motohub.android.data.MotorcycleProfileStore
import io.motohub.android.feature.ridedashboard.nav.NavPoint
import io.motohub.android.feature.ridedashboard.nav.OpenMeteoWeatherClient
import io.motohub.android.feature.ridedashboard.nav.runWeatherUpdateLoop
import io.motohub.android.feature.ridedashboard.widget.DashboardLayoutConfig
import io.motohub.android.feature.ridedashboard.widget.DashboardLayoutStore
import io.motohub.android.feature.ridedashboard.widget.DashboardWidgetRegistry
import io.motohub.android.session.ProjectionEventLog
import io.motohub.android.ui.components.LivePill
import io.motohub.android.ui.components.MotoHubHeader
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Renders the exact same Ride Dashboard TFT output onto a phone-side
 * `SurfaceView`, using the phone's own GPS - no T-Box connection required.
 * Lets heading-up rotation, zoom, maneuver banners etc. be checked visually
 * without a motorcycle nearby, mirroring how [io.motohub.android.feature.androidauto.AndroidAutoPreviewScreen]
 * previews an Android Auto session, but standalone rather than mirroring a
 * live stream. Fullscreen mode mirrors that same screen too, for the same
 * reason: the header/status bar chrome leaves too little height in landscape
 * to judge how the dashboard will actually look.
 *
 * Always uses the OSM map panel, regardless of the rider's real Ride
 * Dashboard map source preference: there is no live Android Auto session to
 * embed here (no T-Box, no phone-mirrored AA feed), so honoring an AA
 * preference would just render an empty panel instead of previewing anything.
 */
@Composable
fun RideDashboardPreviewScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()
    var renderer: RideDashboardRenderer? by remember { mutableStateOf(null) }
    var telemetryProvider: RideTelemetryProvider? by remember { mutableStateOf(null) }
    var weatherJob: Job? by remember { mutableStateOf(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }

    val locationGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    val window = (view.context as? Activity)?.window
    val insetsController = window?.let { WindowCompat.getInsetsController(it, view) }
    DisposableEffect(view, insetsController) {
        onDispose { insetsController?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    LaunchedEffect(fullscreen, insetsController) {
        if (fullscreen) {
            insetsController?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    BackHandler(enabled = fullscreen) { fullscreen = false }
    BackHandler(enabled = !fullscreen, onBack = onBack)

    fun stopPreview() {
        renderer?.stop()
        renderer = null
        telemetryProvider?.stop()
        telemetryProvider = null
        weatherJob?.cancel()
        weatherJob = null
    }

    fun startPreview(surface: Surface) {
        if (!locationGranted) {
            errorMessage = "Location permission is required to preview the dashboard."
            return
        }
        stopPreview()
        val activeTelemetry = RideTelemetryProvider(context)
        activeTelemetry.start().onFailure {
            errorMessage = "Unable to start GPS: ${it.message}"
            return
        }
        telemetryProvider = activeTelemetry
        // Same weather fetch loop the real T-Box session runs - without this the
        // widget stayed stuck on "Loading" forever, since nothing ever published to
        // WeatherWidgetRuntime outside that session. No T-Box Wi-Fi to avoid here, so
        // (unlike the real session) this uses whatever network the phone already has.
        val weatherClient = OpenMeteoWeatherClient(context, cellularOnly = false)
        weatherJob = coroutineScope.launch {
            runWeatherUpdateLoop(weatherClient) {
                activeTelemetry.snapshot().position?.let { NavPoint(it.latitude, it.longitude) }
            }
        }
        // Same widget layout the rider configured in Customize Dashboard for their
        // active motorcycle - without this the preview always showed the hardcoded
        // defaults (SpeedGauge/TripMetrics), regardless of what was actually saved.
        val activeMotorcycle = MotorcycleProfileStore(context).load()
        val activeSsid = activeMotorcycle?.ssid
        val layoutConfig = activeSsid?.let { DashboardLayoutStore(context).load(it) } ?: DashboardLayoutConfig.DEFAULT
        renderer = RideDashboardRenderer(
            context = context,
            surface = surface,
            fps = PREVIEW_FPS,
            bitRate = PREVIEW_DUMMY_BIT_RATE,
            tBoxLabel = "PHONE PREVIEW",
            motorcyclePhotoPath = activeMotorcycle?.photoPath,
            telemetryProvider = activeTelemetry,
            layoutController = RideDashboardLayoutController(),
            mapSource = RideDashboardMapSource.OPEN_STREET_MAP,
            embeddedAndroidAuto = null,
            cellularOnlyMaps = false,
            // The T-Box path stretches on purpose to fill its bike's fixed panel
            // resolution edge-to-edge; this SurfaceView's shape just follows the
            // phone's own orientation, so it must letterbox instead of distorting.
            preserveAspectRatio = true,
            leftWidget = DashboardWidgetRegistry.forId(layoutConfig.leftWidgetId)
                ?: DashboardWidgetRegistry.forId(DashboardLayoutConfig.DEFAULT.leftWidgetId)!!,
            rightWidget = DashboardWidgetRegistry.forId(layoutConfig.rightWidgetId)
                ?: DashboardWidgetRegistry.forId(DashboardLayoutConfig.DEFAULT.rightWidgetId)!!,
            onFailure = { failure ->
                ProjectionEventLog.error("RIDE_DASHBOARD", "Phone preview renderer stopped.", failure)
                errorMessage = "Preview stopped: ${failure.message}"
            }
        ).also { it.start() }
        errorMessage = null
    }

    val preview = @Composable {
        Box(modifier = Modifier.fillMaxSize()) {
            if (locationGranted) {
                AndroidView(
                    factory = { viewContext ->
                        SurfaceView(viewContext).apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    startPreview(holder.surface)
                                }

                                override fun surfaceChanged(
                                    holder: SurfaceHolder,
                                    format: Int,
                                    width: Int,
                                    height: Int
                                ) = Unit

                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    stopPreview()
                                }
                            })
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            errorMessage?.let { message -> StatusOverlay(message, Modifier.align(Alignment.Center)) }
            if (!locationGranted) {
                StatusOverlay(
                    "Grant location access in Settings to preview the dashboard.",
                    Modifier.align(Alignment.Center)
                )
            }
        }
    }

    if (fullscreen) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            preview()
            OutlinedButton(
                onClick = { fullscreen = false },
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color(0xE6141B17),
                    contentColor = Color.White
                ),
                border = BorderStroke(1.dp, Color(0x33FFFFFF))
            ) { Text(motoHubText("Exit fullscreen")) }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MotoHubHeader(
                    modifier = Modifier.fillMaxWidth(),
                    trailing = { TextButton(onClick = onBack) { Text(motoHubText("Close")) } }
                )
                LivePill(if (renderer != null) "PHONE PREVIEW LIVE" else "STARTING")
                Text(
                    motoHubText("Uses this phone's GPS - no T-Box needed. Same renderer and heading-up/zoom ") +
                        "logic that goes to the motorcycle TFT, always with the OSM map panel " +
                        "(there's no live Android Auto session to preview here).",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                preview()
                OutlinedButton(
                    onClick = { fullscreen = true },
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0xE6141B17),
                        contentColor = Color.White
                    ),
                    border = BorderStroke(1.dp, Color(0x33FFFFFF))
                ) { Text(motoHubText("Fullscreen")) }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { stopPreview() }
    }
}

@Composable
private fun StatusOverlay(message: String, modifier: Modifier = Modifier) {
    MaterialSurface(
        modifier = modifier.padding(24.dp),
        color = Color.Black.copy(alpha = 0.78f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
    ) {
        Text(
            text = message,
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(18.dp)
        )
    }
}

private const val PREVIEW_FPS = 20
private const val PREVIEW_DUMMY_BIT_RATE = 4_000_000
