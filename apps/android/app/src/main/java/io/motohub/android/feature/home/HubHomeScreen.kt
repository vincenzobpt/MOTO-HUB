package io.motohub.android.feature.home

import io.motohub.android.i18n.motoHubText

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.session.SessionPhase
import io.motohub.android.feature.garage.MotorcyclePhoto
import io.motohub.android.ui.components.ConnectionRail
import io.motohub.android.ui.components.ConnectionState
import io.motohub.android.ui.components.HubAppBar
import io.motohub.android.ui.components.HubBottomNavigation
import io.motohub.android.ui.components.HubTab
import io.motohub.android.ui.components.LivePill
import io.motohub.android.ui.components.MonoLabel
import io.motohub.android.ui.components.MotoHubBackground
import io.motohub.android.ui.theme.MotoHubAndroidAuto
import io.motohub.android.ui.theme.MotoHubMirror
import io.motohub.android.tbox.TBoxConflictDiagnostics
import io.motohub.android.tbox.WifiGate

@Composable
fun HubHomeScreen(
    state: HubUiState,
    selectedTab: HubTab,
    onTabSelected: (HubTab) -> Unit,
    onScanQr: () -> Unit,
    onImportQrPhoto: () -> Unit,
    onManualPairing: () -> Unit,
    onConnectAndDiscover: () -> Unit,
    officialCfmotoAppInstalled: Boolean,
    onCloseOfficialCfmotoAndRetry: () -> Unit,
    onOpenOfficialCfmotoSettings: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onCancelConnection: () -> Unit,
    onDisconnect: () -> Unit,
    onStartProjection: () -> Unit,
    androidAutoActive: Boolean,
    androidAutoStreaming: Boolean,
    onStartAndroidAuto: () -> Unit,
    onStopAndroidAuto: () -> Unit,
    onOpenAndroidAutoPreview: () -> Unit,
    dimDisplayEnabled: Boolean,
    onDimDisplayChanged: (Boolean) -> Unit,
    onStopProjection: () -> Unit,
    garageContent: @Composable () -> Unit,
    settingsContent: @Composable () -> Unit,
    // ── External display (USB AOA) ──
    aoaAccessoryConnected: Boolean = false,
    externalDisplayActive: Boolean = false,
    externalDisplayStreaming: Boolean = false,
    onStartExternalDisplay: () -> Unit = {},
    onStopExternalDisplay: () -> Unit = {}
) {
    val session = state.session
    val destination = resolveHubDestination(session, androidAutoActive, externalDisplayActive = externalDisplayActive)
    val connectionState = when {
        session.phase == SessionPhase.CONNECTING_NETWORK ||
            session.phase == SessionPhase.DISCOVERING_TBOX -> ConnectionState.CONNECTING
        session.phase == SessionPhase.READY ||
            session.phase == SessionPhase.REQUESTING_PROJECTION ||
            session.phase == SessionPhase.CAPTURING -> ConnectionState.CONNECTED
        else -> ConnectionState.DISCONNECTED
    }

    MotoHubBackground(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding()
        ) {
            ConnectionRail(connectionState)
            HubAppBar(
                motorcycleName = session.motorcycle?.displayName?.takeIf(String::isNotBlank)
                    ?: session.motorcycle?.ssid,
                isConnected = connectionState == ConnectionState.CONNECTED,
                onMotorcycleTap = { onTabSelected(HubTab.GARAGE) }
            )

            Box(Modifier.weight(1f)) {
                Crossfade(targetState = selectedTab, label = "tab") { tab ->
                    when (tab) {
                        HubTab.HOME -> HomeTabContent(
                            state = state,
                            destination = destination,
                            onScanQr = onScanQr,
                            onImportQrPhoto = onImportQrPhoto,
                            onManualPairing = onManualPairing,
                            onConnectAndDiscover = onConnectAndDiscover,
                            officialCfmotoAppInstalled = officialCfmotoAppInstalled,
                            onCloseOfficialCfmotoAndRetry = onCloseOfficialCfmotoAndRetry,
                            onOpenOfficialCfmotoSettings = onOpenOfficialCfmotoSettings,
                            onOpenWifiSettings = onOpenWifiSettings,
                            onCancelConnection = onCancelConnection,
                            onDisconnect = onDisconnect,
                            onStartProjection = onStartProjection,
                            androidAutoActive = androidAutoActive,
                            androidAutoStreaming = androidAutoStreaming,
                            onStartAndroidAuto = onStartAndroidAuto,
                            onStopAndroidAuto = onStopAndroidAuto,
                            onOpenAndroidAutoPreview = onOpenAndroidAutoPreview,
                            dimDisplayEnabled = dimDisplayEnabled,
                            onDimDisplayChanged = onDimDisplayChanged,
                            onStopProjection = onStopProjection,
                            aoaAccessoryConnected = aoaAccessoryConnected,
                            externalDisplayActive = externalDisplayActive,
                            externalDisplayStreaming = externalDisplayStreaming,
                            onStartExternalDisplay = onStartExternalDisplay,
                            onStopExternalDisplay = onStopExternalDisplay
                        )
                        HubTab.GARAGE -> garageContent()
                        HubTab.SETTINGS -> settingsContent()
                    }
                }
            }

            HubBottomNavigation(
                selected = selectedTab,
                onSelect = onTabSelected,
                modifier = Modifier.navigationBarsPadding()
            )
        }
    }
}

@Composable
private fun HomeTabContent(
    state: HubUiState,
    destination: HubDestination,
    onScanQr: () -> Unit,
    onImportQrPhoto: () -> Unit,
    onManualPairing: () -> Unit,
    onConnectAndDiscover: () -> Unit,
    officialCfmotoAppInstalled: Boolean,
    onCloseOfficialCfmotoAndRetry: () -> Unit,
    onOpenOfficialCfmotoSettings: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onCancelConnection: () -> Unit,
    onDisconnect: () -> Unit,
    onStartProjection: () -> Unit,
    androidAutoActive: Boolean,
    androidAutoStreaming: Boolean,
    onStartAndroidAuto: () -> Unit,
    onStopAndroidAuto: () -> Unit,
    onOpenAndroidAutoPreview: () -> Unit,
    dimDisplayEnabled: Boolean,
    onDimDisplayChanged: (Boolean) -> Unit,
    onStopProjection: () -> Unit,
    // ── External display (USB AOA) ──
    aoaAccessoryConnected: Boolean = false,
    externalDisplayActive: Boolean = false,
    externalDisplayStreaming: Boolean = false,
    onStartExternalDisplay: () -> Unit = {},
    onStopExternalDisplay: () -> Unit = {}
) {
    val session = state.session
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Spacer(Modifier.height(4.dp))

        // The motorcycle identity (photo, name, SSID) stays on screen across every
        // destination once a profile is chosen, instead of each destination re-deriving
        // its own header - this is what keeps the screen feeling like one continuous
        // place rather than a new layout every time the connection state changes.
        session.motorcycle?.let { motorcycle ->
            MotorcycleHero(
                motorcycle = motorcycle,
                compact = destination == HubDestination.CONNECTING ||
                    destination == HubDestination.ACTIVE_SESSION
            )
        }

        when (destination) {
            HubDestination.PAIRING -> PairingContent(
                onScanQr = onScanQr,
                onImportQrPhoto = onImportQrPhoto,
                onManualPairing = onManualPairing
            )
            HubDestination.CONNECTING -> ConnectingContent(
                phase = session.phase,
                ssid = checkNotNull(session.motorcycle).ssid,
                onCancel = onCancelConnection
            )
            HubDestination.ACTIVE_SESSION -> ActiveSessionContent(
                androidAutoActive = androidAutoActive,
                androidAutoStreaming = androidAutoStreaming,
                mirrorStreaming = session.phase == SessionPhase.CAPTURING,
                dimDisplayEnabled = dimDisplayEnabled,
                onDimDisplayChanged = onDimDisplayChanged,
                onOpenAndroidAutoPreview = onOpenAndroidAutoPreview,
                externalDisplayActive = externalDisplayActive,
                externalDisplayStreaming = externalDisplayStreaming,
                onStopExternalDisplay = onStopExternalDisplay,
                onStop = if (androidAutoActive) onStopAndroidAuto else onStopProjection
            )
            HubDestination.MODE_SELECTION -> ModeSelectionContent(
                onStartProjection = onStartProjection,
                onStartAndroidAuto = onStartAndroidAuto,
                onDisconnect = onDisconnect,
                aoaAccessoryConnected = aoaAccessoryConnected,
                onStartExternalDisplay = onStartExternalDisplay
            )
            HubDestination.CONNECTION -> ConnectionContent(
                errorMessage = session.message.takeIf { session.phase == SessionPhase.ERROR },
                onConnect = onConnectAndDiscover,
                officialCfmotoAppInstalled = officialCfmotoAppInstalled,
                onCloseOfficialCfmotoAndRetry = onCloseOfficialCfmotoAndRetry,
                onOpenOfficialCfmotoSettings = onOpenOfficialCfmotoSettings,
                onOpenWifiSettings = onOpenWifiSettings,
                onScanQr = onScanQr,
                onImportQrPhoto = onImportQrPhoto,
                onManualPairing = onManualPairing
            )
        }
        Spacer(Modifier.height(10.dp))
    }
}

/**
 * Persistent identity block: photo, display name, SSID, and a large monogram watermark
 * peeking out behind the name. [compact] drops the watermark and shrinks everything into
 * a single row once the screen's attention should be on connection/streaming status instead.
 */
@Composable
private fun MotorcycleHero(motorcycle: MotorcycleProfile, compact: Boolean) {
    val displayName = motorcycle.displayName?.takeIf { it.isNotBlank() } ?: "My motorcycle"
    if (compact) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MotorcyclePhoto(
                path = motorcycle.photoPath,
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(12.dp)
            )
            Text(
                displayName,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    } else {
        val monogram = displayName.trim().take(1).uppercase().ifBlank { "M" }
        val hasPhoto = !motorcycle.photoPath.isNullOrBlank()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(228.dp)
                .clip(RoundedCornerShape(22.dp))
        ) {
            if (hasPhoto) {
                MotorcyclePhoto(
                    path = motorcycle.photoPath,
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(22.dp)
                )
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                )
            }
            // Ghost monogram stamped over the art (not behind it - a user photo has no
            // transparency to peek through, unlike a studio product render), corner-anchored
            // so it reads as a stylistic accent instead of competing with the photo.
            Text(
                text = monogram,
                fontSize = 150.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = if (hasPhoto) 0.10f else 0.16f),
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 26.dp, y = (-34).dp)
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                MaterialTheme.colorScheme.background.copy(alpha = 0.9f)
                            )
                        )
                    )
                    .padding(horizontal = 18.dp, vertical = 16.dp)
            ) {
                Column {
                    Text(
                        displayName,
                        style = MaterialTheme.typography.displaySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        motorcycle.ssid,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun PairingContent(
    onScanQr: () -> Unit,
    onImportQrPhoto: () -> Unit,
    onManualPairing: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MonoLabel(motoHubText("FIRST-TIME SETUP"))
            Text(motoHubText("Connect your motorcycle."), style = MaterialTheme.typography.displaySmall)
            Text(
                motoHubText("Scan the T-Box QR code to save the network credentials automatically."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        PrimaryAction("Scan motorcycle QR code", onScanQr)
        LinkRow("Import QR from photo", onImportQrPhoto)
        LinkRow("No QR? Connect manually", onManualPairing)
    }
}

@Composable
private fun ConnectionContent(
    errorMessage: String?,
    onConnect: () -> Unit,
    officialCfmotoAppInstalled: Boolean,
    onCloseOfficialCfmotoAndRetry: () -> Unit,
    onOpenOfficialCfmotoSettings: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onScanQr: () -> Unit,
    onImportQrPhoto: () -> Unit,
    onManualPairing: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        errorMessage?.let { message ->
            ErrorBanner(
                message = message,
                showPortConflictHelp = TBoxConflictDiagnostics.isPortConflict(message),
                officialCfmotoAppInstalled = officialCfmotoAppInstalled,
                onCloseOfficialCfmotoAndRetry = onCloseOfficialCfmotoAndRetry,
                onOpenOfficialCfmotoSettings = onOpenOfficialCfmotoSettings,
                showWifiSettingsAction = message == WifiGate.WIFI_OFF_MESSAGE,
                onOpenWifiSettings = onOpenWifiSettings
            )
        }
        PrimaryAction("Connect", onConnect)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SecondaryAction("Scan new QR", onScanQr, modifier = Modifier.weight(1f))
            SecondaryAction("Import QR", onImportQrPhoto, modifier = Modifier.weight(1f))
        }
        LinkRow("No QR? Connect manually", onManualPairing)
    }
}

/**
 * Collapsed by default to just the failure headline and the actual error message - both
 * always fully readable, never truncated. Only the secondary, situational help (port-conflict
 * explanation, retry actions) is behind the expand tap, so the banner stays compact without
 * ever hiding what went wrong.
 */
@Composable
private fun ErrorBanner(
    message: String,
    showPortConflictHelp: Boolean,
    officialCfmotoAppInstalled: Boolean,
    onCloseOfficialCfmotoAndRetry: () -> Unit,
    onOpenOfficialCfmotoSettings: () -> Unit,
    showWifiSettingsAction: Boolean,
    onOpenWifiSettings: () -> Unit
) {
    var expanded by rememberSaveable(message) { mutableStateOf(false) }
    val hasExtra = showPortConflictHelp || showWifiSettingsAction
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (hasExtra) it.clickable { expanded = !expanded } else it },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    motoHubText("CONNECTION FAILED"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (hasExtra) {
                    Text(
                        if (expanded) "▲" else "▼ Details",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (expanded) {
                if (showPortConflictHelp) {
                    Text(
                        motoHubText("Another EasyConn app can keep the T-Box link occupied even after it leaves the foreground."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (officialCfmotoAppInstalled) {
                        SecondaryAction("Close official app and retry", onCloseOfficialCfmotoAndRetry)
                        SecondaryAction("Open official app settings", onOpenOfficialCfmotoSettings)
                    }
                }
                if (showWifiSettingsAction) {
                    SecondaryAction("Open Wi-Fi settings", onOpenWifiSettings)
                }
            }
        }
    }
}

@Composable
private fun ConnectingContent(
    phase: SessionPhase,
    ssid: String,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(12.dp))
        CircularProgressIndicator(
            modifier = Modifier.size(40.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 3.dp
        )
        Spacer(Modifier.height(18.dp))
        Text(
            motoHubText("Connecting"),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        Text(
            motoHubText("Setting up network and T-Box"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp)
        )
        Spacer(Modifier.height(22.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.large
        ) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
                ConnectionStep("Profile loaded", done = true)
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                ConnectionStep(
                    "Connecting to $ssid",
                    done = phase == SessionPhase.DISCOVERING_TBOX,
                    current = phase == SessionPhase.CONNECTING_NETWORK
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                ConnectionStep(
                    "Finding T-Box service",
                    current = phase == SessionPhase.DISCOVERING_TBOX
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        SecondaryAction("Cancel", onCancel, Modifier.fillMaxWidth(0.5f))
    }
}

@Composable
private fun ModeSelectionContent(
    onStartProjection: () -> Unit,
    onStartAndroidAuto: () -> Unit,
    onDisconnect: () -> Unit,
    aoaAccessoryConnected: Boolean = false,
    onStartExternalDisplay: () -> Unit = {}
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LivePill(motoHubText("T-Box connected"))
            Text(motoHubText("What to show?"), style = MaterialTheme.typography.displaySmall)
        }
        ModeGrid(
            onMirror = onStartProjection,
            onAndroidAuto = onStartAndroidAuto,
            onExternal = if (aoaAccessoryConnected) onStartExternalDisplay else null
        )
        // The only way back once connect succeeds - without it, the rider had no path from
        // "what to show?" to a different motorcycle or a plain Wi-Fi release except
        // force-stopping the app.
        Text(
            motoHubText("Disconnect"),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onDisconnect)
                .padding(vertical = 6.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ModeGrid(
    onMirror: () -> Unit,
    onAndroidAuto: () -> Unit,
    onExternal: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ModeGridItem("Mirror", MotoHubMirror, Modifier.weight(1f), onMirror)
        ModeGridItem("Auto", MotoHubAndroidAuto, Modifier.weight(1f), onAndroidAuto)
        if (onExternal != null) {
            ModeGridItem("External", MotoHubMirror, Modifier.weight(1f), onExternal)
        }
    }
}

@Composable
private fun ModeGridItem(name: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 26.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(color.copy(alpha = 0.1f), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center
            ) {
                ModeIcon(name, color, iconSize = 32.dp)
            }
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Hand-drawn line icons matching [io.motohub.android.ui.components.NavIcon]'s style - no icon-font dependency. */
@Composable
private fun ModeIcon(mode: String, color: Color, iconSize: Dp = 24.dp) {
    Canvas(Modifier.size(iconSize)) {
        val s = size.width
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (mode) {
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
        }
    }
}

@Composable
private fun ActiveSessionContent(
    androidAutoActive: Boolean,
    androidAutoStreaming: Boolean,
    mirrorStreaming: Boolean,
    dimDisplayEnabled: Boolean,
    onDimDisplayChanged: (Boolean) -> Unit,
    onOpenAndroidAutoPreview: () -> Unit,
    externalDisplayActive: Boolean = false,
    externalDisplayStreaming: Boolean = false,
    onStopExternalDisplay: () -> Unit = {},
    onStop: () -> Unit
) {
    val ready = when {
        androidAutoActive -> androidAutoStreaming
        externalDisplayActive -> externalDisplayStreaming
        else -> mirrorStreaming
    }
    val modeName = when {
        androidAutoActive -> "Android Auto"
        externalDisplayActive -> "External Display"
        else -> "Mirroring"
    }
    val modeColor = when {
        androidAutoActive -> MotoHubAndroidAuto
        externalDisplayActive -> MotoHubMirror
        else -> MotoHubMirror
    }
    val statusText = when {
        androidAutoActive && ready -> "Navigation active on TFT"
        externalDisplayActive && ready -> "Streaming to external display via USB"
        ready -> "TFT is receiving your screen"
        else -> "Session is being prepared"
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ActiveSessionHero(ready, modeName, statusText, modeColor)
        MonoLabel(motoHubText("SESSION ACTIONS"))

        if (androidAutoActive) {
            ActiveSessionAction(
                title = motoHubText("Preview & touch"),
                description = motoHubText("View Android Auto and interact from your phone."),
                accentColor = MotoHubAndroidAuto,
                icon = "Auto",
                onClick = onOpenAndroidAutoPreview
            )
        } else {
            ToggleCard(
                title = motoHubText("Dim phone display"),
                description = motoHubText("Keep the TFT active while reducing phone distraction."),
                checked = dimDisplayEnabled,
                onCheckedChange = onDimDisplayChanged
            )
        }

        StopAction(
            text = "Stop streaming",
            onClick = onStop
        )
    }
}

@Composable
private fun ActiveSessionHero(ready: Boolean, modeName: String, statusText: String, accentColor: Color) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .background(accentColor.copy(alpha = 0.12f), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center
            ) {
                ModeIcon(
                    mode = when (modeName) {
                        "Android Auto" -> "Auto"
                        else -> "Mirror"
                    },
                    color = accentColor,
                    iconSize = 32.dp
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LivePill(if (ready) "LIVE ON TFT" else "PREPARING")
                Text(modeName, style = MaterialTheme.typography.titleLarge)
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ActiveSessionAction(
    title: String,
    description: String,
    accentColor: Color,
    icon: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.height(132.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(accentColor.copy(alpha = 0.12f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                ModeIcon(icon, accentColor, iconSize = 23.dp)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    description,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ToggleCard(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun ConnectionStep(text: String, done: Boolean = false, current: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(
                    when {
                        done -> MaterialTheme.colorScheme.tertiary
                        current -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.outline
                    },
                    CircleShape
                )
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (done || current) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PrimaryAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SecondaryAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Text(text)
    }
}

@Composable
private fun StopAction(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
    ) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LinkRow(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
