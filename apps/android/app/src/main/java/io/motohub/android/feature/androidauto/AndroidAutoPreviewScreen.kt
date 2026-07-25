package io.motohub.android.feature.androidauto

import io.motohub.android.i18n.motoHubText

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.motohub.android.aa.AaInput
import io.motohub.android.aa.AaInputBridge
import io.motohub.android.androidauto.AndroidAutoPreviewRuntime
import io.motohub.android.androidauto.AndroidAutoPreviewView
import io.motohub.android.androidauto.AndroidAutoRuntime
import io.motohub.android.androidauto.AndroidAutoRuntimeState
import io.motohub.android.feature.ridedashboard.RideDashboardAndroidAutoRuntime
import io.motohub.android.feature.ridedashboard.RideDashboardAndroidAutoState
import io.motohub.android.feature.ridedashboard.nav.NightPrefs
import io.motohub.android.feature.ridedashboard.nav.MapTheme
import io.motohub.android.ui.components.LivePill
import io.motohub.android.ui.components.MotoHubHeader

@Composable
fun AndroidAutoPreviewScreen(onBack: () -> Unit, startFullscreen: Boolean = false) {
    val context = LocalContext.current
    val view = LocalView.current
    val fullRuntimeState by AndroidAutoRuntime.state.collectAsStateWithLifecycle()
    val embeddedRuntimeState by RideDashboardAndroidAutoRuntime.state.collectAsStateWithLifecycle()
    val inputReady by AaInputBridge.ready.collectAsStateWithLifecycle()
    var fullscreen by rememberSaveable { mutableStateOf(startFullscreen) }
    // Not reset to false on every fullscreen entry: a direct "open fullscreen controls"
    // shortcut (startFullscreen=true) needs the D-pad visible immediately, and the
    // windowed "Fullscreen" button below sets this explicitly itself when the rider is
    // just maximizing the video. Either way, InlineAaControls stays reachable via the
    // toggle button in the fullscreen branch - previously nothing could bring it back
    // once fullscreen hid it.
    var controlsVisible by rememberSaveable { mutableStateOf(true) }

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

    val fullSessionActive = fullRuntimeState is AndroidAutoRuntimeState.Preparing ||
        fullRuntimeState is AndroidAutoRuntimeState.ReceiverReady ||
        fullRuntimeState is AndroidAutoRuntimeState.Streaming
    val embeddedSessionActive = embeddedRuntimeState is RideDashboardAndroidAutoState.Preparing ||
        embeddedRuntimeState is RideDashboardAndroidAutoState.ReceiverReady ||
        embeddedRuntimeState is RideDashboardAndroidAutoState.Streaming
    val sessionActive = fullSessionActive || embeddedSessionActive
    val streaming = fullRuntimeState is AndroidAutoRuntimeState.Streaming ||
        embeddedRuntimeState is RideDashboardAndroidAutoState.Streaming
    val status = when {
        fullSessionActive -> when (fullRuntimeState) {
            AndroidAutoRuntimeState.Preparing ->
                "Session preparing. Waiting for Android Auto to connect."
            AndroidAutoRuntimeState.ReceiverReady ->
                "Receiver ready. Waiting for the first Android Auto frame."
            AndroidAutoRuntimeState.Streaming -> if (inputReady) {
                "Live preview and touch control available."
            } else {
                "Live video. Input channel is still pending."
            }
            else -> "Android Auto is not running."
        }
        embeddedSessionActive -> when (embeddedRuntimeState) {
            RideDashboardAndroidAutoState.Preparing ->
                "Ride Dashboard Android Auto is preparing."
            RideDashboardAndroidAutoState.ReceiverReady ->
                "Receiver ready. Waiting for the first Android Auto frame."
            RideDashboardAndroidAutoState.Streaming -> if (inputReady) {
                "Ride Dashboard: live preview and touch control available."
            } else {
                "Ride Dashboard: live video. Input channel is still pending."
            }
            else -> "Ride Dashboard Android Auto is not available."
        }
        embeddedRuntimeState is RideDashboardAndroidAutoState.Failed ->
            (embeddedRuntimeState as RideDashboardAndroidAutoState.Failed).message
        else -> when (val state = fullRuntimeState) {
            AndroidAutoRuntimeState.Idle -> "Android Auto is not running. Start the session from Home."
            is AndroidAutoRuntimeState.Stopped -> state.reason
            is AndroidAutoRuntimeState.Failed -> state.message
            else -> "Android Auto is not running."
        }
    }
    val controlsEnabled = sessionActive && inputReady

    val preview = @Composable {
        AndroidView(
            factory = ::AndroidAutoPreviewView,
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        )
    }

    if (fullscreen) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            preview()
            if (controlsVisible) {
                InlineAaControls(
                    enabled = controlsEnabled,
                    nightMode = NightPrefs.isNightNow(context),
                    onKey = AndroidAutoPreviewRuntime::sendKey,
                    onScroll = AndroidAutoPreviewRuntime::sendScroll,
                    onToggleNight = {
                        // next == MapTheme.NIGHT alone would push isNight=false when cycling
                        // into AUTO instead of resolving it (phone dark mode / clock); use the
                        // same resolution NightPrefs.isNightNow() already does for the label.
                        NightPrefs.cycle(context)
                        AndroidAutoPreviewRuntime.setNightMode(NightPrefs.isNightNow(context))
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp)
                )
            }
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { controlsVisible = !controlsVisible },
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0xE6141B17),
                        contentColor = Color.White
                    ),
                    border = BorderStroke(1.dp, Color(0x33FFFFFF))
                ) { Text(motoHubText(if (controlsVisible) "Hide controls" else "Controls")) }
                OutlinedButton(
                    onClick = { fullscreen = false },
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0xE6141B17),
                        contentColor = Color.White
                    ),
                    border = BorderStroke(1.dp, Color(0x33FFFFFF))
                ) { Text(motoHubText("Exit fullscreen")) }
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LivePill(if (streaming) "ANDROID AUTO LIVE" else "ANDROID AUTO STANDBY")
                    Text(
                        text = status,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                preview()
                if (!controlsEnabled) {
                    StatusOverlay(status, Modifier.align(Alignment.Center))
                }
                if (controlsVisible) {
                    InlineAaControls(
                        enabled = controlsEnabled,
                        nightMode = NightPrefs.isNightNow(context),
                        onKey = AndroidAutoPreviewRuntime::sendKey,
                        onScroll = AndroidAutoPreviewRuntime::sendScroll,
                        onToggleNight = {
                            val next = NightPrefs.cycle(context)
                            AndroidAutoPreviewRuntime.setNightMode(next == MapTheme.NIGHT)
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(12.dp)
                    )
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = { controlsVisible = !controlsVisible }) {
                        Text(if (controlsVisible) "Hide controls" else "Controls")
                    }
                    OutlinedButton(onClick = {
                        controlsVisible = false
                        fullscreen = true
                    }) { Text(motoHubText("Fullscreen")) }
                }
            }
        }
    }
}

@Composable
private fun StatusOverlay(status: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .padding(24.dp),
        color = Color.Black.copy(alpha = 0.78f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text = status,
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(18.dp)
        )
    }
}

@Composable
private fun InlineAaControls(
    enabled: Boolean,
    nightMode: Boolean,
    onKey: (Int) -> Boolean,
    onScroll: (Int) -> Boolean,
    onToggleNight: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.Black.copy(alpha = 0.78f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AaButton("Up", enabled, Modifier.weight(1f)) { onKey(AaInput.KEY_UP) }
                AaButton("Left", enabled, Modifier.weight(1f)) { onKey(AaInput.KEY_LEFT) }
                AaButton("OK", enabled, Modifier.weight(1f)) { onKey(AaInput.KEY_ENTER) }
                AaButton("Right", enabled, Modifier.weight(1f)) { onKey(AaInput.KEY_RIGHT) }
                AaButton("Down", enabled, Modifier.weight(1f)) { onKey(AaInput.KEY_DOWN) }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AaButton("Back", enabled, Modifier.weight(1f)) { onKey(AaInput.KEY_BACK) }
                AaButton("Home", enabled, Modifier.weight(1f)) { onKey(AaInput.KEY_HOME) }
                AaButton("Assistant", enabled, Modifier.weight(1f)) { onKey(AaInput.KEY_ASSISTANT) }
                AaButton("Scroll -", enabled, Modifier.weight(1f)) { onScroll(-1) }
                AaButton("Scroll +", enabled, Modifier.weight(1f)) { onScroll(+1) }
                AaButton(if (nightMode) "Day" else "Night", enabled, Modifier.weight(1f), onToggleNight)
            }
        }
    }
}

@Composable
private fun AaButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(38.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
    ) { Text(label, style = MaterialTheme.typography.labelSmall) }
}
