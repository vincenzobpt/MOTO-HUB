package io.motohub.android.feature.home

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.session.SessionPhase
import io.motohub.android.androidauto.AndroidAutoDisplayMode
import io.motohub.android.feature.garage.MotorcyclePhoto
import io.motohub.android.ui.components.LivePill
import io.motohub.android.ui.components.MonoLabel
import io.motohub.android.ui.components.MotoHubBackground
import io.motohub.android.ui.components.MotoHubHeader

@Composable
fun HubHomeScreen(
    state: HubUiState,
    onSsidChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onSaveMotorcycle: () -> Unit,
    onScanQr: () -> Unit,
    onImportQrPhoto: () -> Unit,
    onConnectAndDiscover: () -> Unit,
    onCancelConnection: () -> Unit,
    onOpenNetworkDiagnostics: () -> Unit,
    onOpenApplicationLogs: () -> Unit,
    onOpenGarage: () -> Unit,
    onStartProjection: () -> Unit,
    androidAutoActive: Boolean,
    androidAutoStreaming: Boolean,
    androidAutoDisplayMode: AndroidAutoDisplayMode,
    onAndroidAutoDisplayModeChanged: (AndroidAutoDisplayMode) -> Unit,
    onStartAndroidAuto: () -> Unit,
    onStopAndroidAuto: () -> Unit,
    onOpenAndroidAutoPreview: () -> Unit,
    dimDisplayEnabled: Boolean,
    onDimDisplayChanged: (Boolean) -> Unit,
    onStopProjection: () -> Unit
) {
    val session = state.session
    val destination = resolveHubDestination(session, androidAutoActive)

    MotoHubBackground(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            MotoHubHeader(
                modifier = Modifier.fillMaxWidth(),
                trailing = {
                    TextButton(onClick = onOpenGarage) {
                        Text("Garage")
                    }
                    TextButton(onClick = onOpenApplicationLogs) {
                        Text("Logs")
                    }
                }
            )

            when (destination) {
                HubDestination.PAIRING -> PairingContent(
                    state = state,
                    sessionError = session.message.takeIf { session.phase == SessionPhase.ERROR },
                    onSsidChanged = onSsidChanged,
                    onPasswordChanged = onPasswordChanged,
                    onSave = onSaveMotorcycle,
                    onScanQr = onScanQr,
                    onImportQrPhoto = onImportQrPhoto
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
                    ssid = checkNotNull(session.motorcycle).ssid,
                    photoPath = checkNotNull(session.motorcycle).photoPath,
                    dimDisplayEnabled = dimDisplayEnabled,
                    onDimDisplayChanged = onDimDisplayChanged,
                    onOpenAndroidAutoPreview = onOpenAndroidAutoPreview,
                    onStop = if (androidAutoActive) onStopAndroidAuto else onStopProjection
                )

                HubDestination.MODE_SELECTION -> ModeSelectionContent(
                    ssid = checkNotNull(session.motorcycle).ssid,
                    androidAutoDisplayMode = androidAutoDisplayMode,
                    onAndroidAutoDisplayModeChanged = onAndroidAutoDisplayModeChanged,
                    onStartProjection = onStartProjection,
                    onStartAndroidAuto = onStartAndroidAuto,
                    onOpenNetworkDiagnostics = onOpenNetworkDiagnostics,
                    onScanQr = onScanQr
                )

                HubDestination.CONNECTION -> ConnectionContent(
                    motorcycle = checkNotNull(session.motorcycle),
                    errorMessage = session.message.takeIf { session.phase == SessionPhase.ERROR },
                    onConnect = onConnectAndDiscover,
                    onScanQr = onScanQr,
                    onImportQrPhoto = onImportQrPhoto,
                    onOpenNetworkDiagnostics = onOpenNetworkDiagnostics
                )
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun PairingContent(
    state: HubUiState,
    sessionError: String?,
    onSsidChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onSave: () -> Unit,
    onScanQr: () -> Unit,
    onImportQrPhoto: () -> Unit
) {
    var showManualSetup by rememberSaveable { mutableStateOf(false) }

    HeroCopy(
        eyebrow = "FIRST-TIME SETUP",
        title = "Connect your motorcycle.",
        body = "Scan the T-Box QR code to save the network and password automatically."
    )

    sessionError?.let { ErrorNotice(it) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CodeBadge("QR")
                Column(Modifier.weight(1f)) {
                    Text("T-Box QR code", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "The fastest and most reliable method.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            PrimaryAction("Scan motorcycle QR code", onScanQr)
            SecondaryAction("Import QR code photo", onImportQrPhoto)

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            TextButton(
                onClick = { showManualSetup = !showManualSetup },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(if (showManualSetup) "Hide manual setup" else "Enter details manually")
            }

            if (showManualSetup) {
                OutlinedTextField(
                    value = state.ssid,
                    onValueChange = onSsidChanged,
                    label = { Text("Motorcycle Wi-Fi network") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.password,
                    onValueChange = onPasswordChanged,
                    label = { Text("Password Wi-Fi") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                state.formError?.let { ErrorNotice(it) }
                PrimaryAction("Save profile", onSave)
            }
        }
    }
}

@Composable
private fun ConnectionContent(
    motorcycle: MotorcycleProfile,
    errorMessage: String?,
    onConnect: () -> Unit,
    onScanQr: () -> Unit,
    onImportQrPhoto: () -> Unit,
    onOpenNetworkDiagnostics: () -> Unit
) {
    HeroCopy(
        eyebrow = if (errorMessage == null) "READY TO RIDE" else "CONNECTION INTERRUPTED",
        title = if (errorMessage == null) "Bring your phone to the TFT." else "Let's reconnect.",
        body = if (errorMessage == null) {
            "Your motorcycle is already configured. Connect it to access projection modes."
        } else {
            "The profile is saved. You can try again without scanning the QR code."
        }
    )

    errorMessage?.let { ErrorNotice(it) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MotorcyclePhoto(
                    path = motorcycle.photoPath,
                    modifier = Modifier.size(68.dp),
                    shape = RoundedCornerShape(14.dp)
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        motorcycle.displayName?.takeIf { it.isNotBlank() } ?: "My motorcycle",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        motorcycle.ssid,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            PrimaryAction(
                text = if (errorMessage == null) "Connect to motorcycle" else "Try connection again",
                onClick = onConnect
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        CompactAction("New QR code", onScanQr, Modifier.weight(1f))
        CompactAction("Import photo", onImportQrPhoto, Modifier.weight(1f))
    }
    TextButton(
        onClick = onOpenNetworkDiagnostics,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Open network diagnostics")
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
        Spacer(Modifier.height(28.dp))
        Box(
            modifier = Modifier
                .size(154.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(108.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp
            )
            CodeBadge("T")
        }
        Spacer(Modifier.height(28.dp))
        MonoLabel("CONNECTING")
        Text(
            text = "Preparing the TFT.",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp)
        )
        Text(
            text = "MOTO-HUB manages the network and T-Box discovery. No further action is needed.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp, bottom = 28.dp)
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.medium
        ) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
                ConnectionStep("Motorcycle profile loaded", done = true)
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                ConnectionStep(
                    "Connecting to $ssid",
                    done = phase == SessionPhase.DISCOVERING_TBOX,
                    current = phase == SessionPhase.CONNECTING_NETWORK
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                ConnectionStep(
                    "Finding T-Box service",
                    current = phase == SessionPhase.DISCOVERING_TBOX
                )
            }
        }
        TextButton(onClick = onCancel, modifier = Modifier.padding(top = 16.dp)) {
            Text("Cancel connection")
        }
    }
}

@Composable
private fun ModeSelectionContent(
    ssid: String,
    androidAutoDisplayMode: AndroidAutoDisplayMode,
    onAndroidAutoDisplayModeChanged: (AndroidAutoDisplayMode) -> Unit,
    onStartProjection: () -> Unit,
    onStartAndroidAuto: () -> Unit,
    onOpenNetworkDiagnostics: () -> Unit,
    onScanQr: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LivePill("T-BOX CONNECTED")
        Text("What would you like to show?", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Choose a mode. The remaining controls appear only when needed.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    DisplayModeSelector(
        mode = androidAutoDisplayMode,
        onModeChanged = onAndroidAutoDisplayModeChanged
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ModeCard(
            code = "M",
            label = "APP OR SCREEN",
            title = "Mirroring",
            body = "Share one app or your entire phone screen.",
            onClick = onStartProjection
        )
        ModeCard(
            code = "AA",
            label = "DRIVING INTERFACE",
            title = "Android Auto",
            body = "Navigation, music, and touch control from your phone.",
            onClick = onStartAndroidAuto
        )
    }

    Text(
        text = ssid,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        CompactAction("Diagnostics", onOpenNetworkDiagnostics, Modifier.weight(1f))
        CompactAction("Change motorcycle", onScanQr, Modifier.weight(1f))
    }
}

@Composable
private fun ActiveSessionContent(
    androidAutoActive: Boolean,
    androidAutoStreaming: Boolean,
    mirrorStreaming: Boolean,
    ssid: String,
    photoPath: String?,
    dimDisplayEnabled: Boolean,
    onDimDisplayChanged: (Boolean) -> Unit,
    onOpenAndroidAutoPreview: () -> Unit,
    onStop: () -> Unit
) {
    val ready = if (androidAutoActive) androidAutoStreaming else mirrorStreaming
    val modeName = if (androidAutoActive) "Android Auto" else "Mirroring"

    LivePill(if (ready) "STREAMING ACTIVE" else "PREPARING")
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(126.dp)
                .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.13f), RoundedCornerShape(38.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (androidAutoActive) "AA" else "M",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.tertiary,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MotorcyclePhoto(
                    path = photoPath,
                    modifier = Modifier.size(58.dp),
                    shape = RoundedCornerShape(14.dp)
                )
                Column(Modifier.weight(1f)) {
                    Text(modeName, style = MaterialTheme.typography.headlineMedium)
                    Text(
                        ssid,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = if (ready) {
                    "The TFT is receiving the stream. Keep MOTO-HUB running."
                } else {
                    "The session is being prepared. Wait a few seconds."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            if (androidAutoActive && androidAutoStreaming) {
                PrimaryAction("Show and control on phone", onOpenAndroidAutoPreview)
            }

            if (!androidAutoActive) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
                        .padding(15.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Dim phone display", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "The TFT stays on. Tap the phone to restore the display.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = dimDisplayEnabled,
                        onCheckedChange = onDimDisplayChanged
                    )
                }
            }

            OutlinedButton(
                onClick = onStop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.65f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Stop streaming", fontWeight = FontWeight.Bold)
            }
        }
    }

    Text(
        text = "$ssid  /  connection active",
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun DisplayModeSelector(
    mode: AndroidAutoDisplayMode,
    onModeChanged: (AndroidAutoDisplayMode) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Android Auto display format", style = MaterialTheme.typography.titleMedium)
            Text(
                "Choose how the TFT fits the complete Android Auto image.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DisplayModeButton(
                    text = "FIT",
                    selected = mode == AndroidAutoDisplayMode.LETTERBOX,
                    onClick = { onModeChanged(AndroidAutoDisplayMode.LETTERBOX) },
                    modifier = Modifier.weight(1f)
                )
                DisplayModeButton(
                    text = "FILL",
                    selected = mode == AndroidAutoDisplayMode.STRETCH,
                    onClick = { onModeChanged(AndroidAutoDisplayMode.STRETCH) },
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                mode.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DisplayModeButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
            } else {
                Color.Transparent
            },
            contentColor = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface
        )
    ) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HeroCopy(eyebrow: String, title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MonoLabel(eyebrow)
        Text(title, style = MaterialTheme.typography.displaySmall)
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ModeCard(
    code: String,
    label: String,
    title: String,
    body: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CodeBadge(code)
                Text(
                    text = "->",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CodeBadge(code: String) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.13f), RoundedCornerShape(15.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            code,
            color = MaterialTheme.colorScheme.primary,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ConnectionStep(text: String, done: Boolean = false, current: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(9.dp)
                .background(
                    if (done || current) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.outline,
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
private fun ErrorNotice(message: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.11f),
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            Modifier.padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                "CONNECTION FAILED",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PrimaryAction(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SecondaryAction(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Text(text)
    }
}

@Composable
private fun CompactAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}
