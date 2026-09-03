// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.androidauto

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.motohub.android.androidauto.AndroidAutoRuntime
import io.motohub.android.androidauto.AndroidAutoRuntimeState
import io.motohub.android.androidauto.AndroidAutoPreviewView
import io.motohub.android.androidauto.AndroidAutoSelfModeHelp
import io.motohub.android.i18n.motoHubText
import io.motohub.android.ui.components.LivePill
import io.motohub.android.ui.components.MotoHubHeader

@Composable
fun AndroidAutoPreviewScreen(onBack: () -> Unit, startFullscreen: Boolean = false) {
    val view = LocalView.current
    val runtimeState by AndroidAutoRuntime.state.collectAsStateWithLifecycle()
    var fullscreen by rememberSaveable(startFullscreen) { mutableStateOf(startFullscreen) }
    val window = (view.context as? ComponentActivity)?.window
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

    val streaming = runtimeState is AndroidAutoRuntimeState.Streaming
    val sessionActive = runtimeState is AndroidAutoRuntimeState.Preparing ||
        runtimeState is AndroidAutoRuntimeState.ReceiverReady || streaming
    val startupDetail by AndroidAutoRuntime.startupDetail.collectAsStateWithLifecycle()
    // The startup detail is usually narration, but two of its values are an instruction the
    // rider has to carry out. Those two are stored in English - the flat line is IPC payload
    // matched by identity - so they are recognised here and drawn from the catalogue instead of
    // being passed through raw, which left them English on a phone set to any other language.
    val riderStep = AndroidAutoSelfModeHelp.riderStepOf(startupDetail)
    val riderStepLine = riderStep?.let { "${motoHubText(it.action)} · ${motoHubText(it.where)}" }
    // motoHubText on the runtime branches too: the stop reason and the failure message reach
    // this screen as plain strings, so the catalogue is the only place they can be translated,
    // and one with no entry falls back to itself.
    val status = when (val state = runtimeState) {
        AndroidAutoRuntimeState.Idle ->
            motoHubText("Android Auto is not running. Start a session from Home.")
        AndroidAutoRuntimeState.Preparing -> motoHubText("Preparing Android Auto…")
        // Not "connected": at this point MOTO-HUB is only listening, and is still asking Google
        // Android Auto to project here — which can take several seconds and several attempts.
        AndroidAutoRuntimeState.ReceiverReady ->
            riderStepLine ?: startupDetail?.let(::motoHubText)
                ?: motoHubText("Waiting for Android Auto to start projecting…")
        AndroidAutoRuntimeState.Streaming -> motoHubText("Live preview · touch enabled")
        is AndroidAutoRuntimeState.Stopped -> motoHubText(state.reason)
        is AndroidAutoRuntimeState.Failed -> motoHubText(state.message)
    }

    val preview: @Composable () -> Unit = {
        AndroidView(
            factory = ::AndroidAutoPreviewView,
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        )
    }

    // preview() binds one SurfaceView to a shared runtime registration, not one per view. A
    // slide transition would keep both the windowed and fullscreen layouts composed together
    // for the length of the animation, and two of these would each try to bind their own
    // Surface to that single registration - see the PRO edition of this screen for the same
    // reasoning. So the view is mounted exactly once here; only the header chrome slides.
    val headerHeightPx = remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val topInset by animateDpAsState(
        targetValue = if (fullscreen) 0.dp else with(density) { headerHeightPx.floatValue.toDp() },
        animationSpec = tween(TRANSITION_MILLIS, easing = FastOutSlowInEasing),
        label = "aa-preview-top-inset"
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topInset)
        ) {
            preview()
            if (!sessionActive) {
                PreviewStatusOverlay(status, Modifier.align(Alignment.Center))
            }
            if (fullscreen) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PreviewStatusPill(streaming = streaming)
                    PreviewActionButton("Exit fullscreen") { fullscreen = false }
                }
            } else {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(14.dp),
                    color = Color.Black.copy(alpha = 0.78f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = motoHubText("Android Auto"),
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge
                        )
                        PreviewActionButton(motoHubText("Fullscreen")) { fullscreen = true }
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = !fullscreen,
            enter = fadeIn(tween(TRANSITION_MILLIS)) + slideInVertically(tween(TRANSITION_MILLIS)) { -it },
            exit = fadeOut(tween(TRANSITION_MILLIS)) + slideOutVertically(tween(TRANSITION_MILLIS)) { -it }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding()
                    .onGloballyPositioned { headerHeightPx.floatValue = it.size.height.toFloat() }
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
                    PreviewStatusPill(streaming = streaming)
                    Text(
                        text = if (streaming) motoHubText("Touch the preview to control Android Auto") else status,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

/** Matches the pace the rest of the app's screen transitions run at. */
private const val TRANSITION_MILLIS = 320

@Composable
private fun PreviewStatusPill(streaming: Boolean) {
    LivePill(if (streaming) "ANDROID AUTO LIVE" else "ANDROID AUTO STANDBY")
}

@Composable
private fun PreviewActionButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.height(36.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color(0xE6141B17),
            contentColor = Color.White
        ),
        border = BorderStroke(1.dp, Color(0x55FFFFFF))
    ) { Text(label, style = MaterialTheme.typography.labelMedium) }
}

@Composable
private fun PreviewStatusOverlay(status: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.padding(24.dp),
        color = Color.Black.copy(alpha = 0.82f),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            text = status,
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
        )
    }
}
