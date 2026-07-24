package io.motohub.android.feature.garage

import io.motohub.android.i18n.motoHubText

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.motohub.android.androidauto.DisplayGeometry
import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.tbox.TBoxCapabilities
import io.motohub.android.tbox.TBoxCapabilitySnapshot
import io.motohub.android.tbox.TBoxPortScanResult
import io.motohub.android.tbox.TBoxPortStatus
import io.motohub.android.ui.components.MonoLabel
import io.motohub.android.ui.components.MotoHubBackground
import io.motohub.android.ui.components.MotoHubHeader
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun TBoxCapabilityScreen(
    profile: MotorcycleProfile,
    snapshot: TBoxCapabilitySnapshot?,
    geometry: DisplayGeometry?,
    portScanInProgress: Boolean = false,
    portScanResult: TBoxPortScanResult? = null,
    onScanPorts: () -> Unit = {},
    onBack: () -> Unit
) {
    val capabilities = snapshot?.capabilities

    BackHandler(onBack = onBack)

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

            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                MonoLabel(motoHubText("T-BOX CAPABILITY INSPECTOR"))
                Text(
                    text = profile.displayName ?: profile.ssid,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "A read-only view of data observed directly from this T-Box.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            ObservationCard(snapshot, capabilities)

            InspectorSection("CONNECTION") {
                InspectorRow("Wi-Fi network", profile.ssid, monospace = true)
                InspectorRow(
                    "EasyConn endpoint",
                    snapshot?.host?.let { "${it.ipAddress}:${it.port}" },
                    monospace = true
                )
                InspectorRow("NSD package", snapshot?.host?.packageName, monospace = true)
                InspectorRow("Last discovered", formatTimestamp(snapshot?.discoveredAtEpochMillis))
            }

            InspectorSection("DIAGNOSTICS") {
                Text(
                    motoHubText("If EasyConn discovery keeps failing, briefly reconnect to this T-Box and probe ") +
                        "its well-known ports (10915-10935) directly to see which one actually answers.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onScanPorts,
                    enabled = !portScanInProgress,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (portScanInProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text(motoHubText("Scan common EasyConn ports"))
                    }
                }
                portScanResult?.let { result -> PortScanResultView(result) }
            }

            InspectorSection("DISPLAY") {
                InspectorRow("TFT capture area", geometry?.let { "${it.width} x ${it.height}" }, monospace = true)
                InspectorRow("Orientation", geometry?.orientationName())
                InspectorRow("Reported DPI", capabilities?.dpi?.toString(), monospace = true)
                CapabilityRow("DPI mode", capabilities?.dpiEnabled)
                InspectorRow("Screen type", capabilities?.screenType?.toString(), monospace = true)
            }

            InspectorSection("REPORTED IDENTITY") {
                InspectorRow("Head unit name", capabilities?.huName)
                InspectorRow("Vehicle brand", capabilities?.carBrand)
                InspectorRow("Vehicle model", capabilities?.carModel)
                Text(
                    text = "These values are shown exactly as reported by the T-Box. MOTO-HUB does not " +
                        "infer a motorcycle model from the QR code or network name.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            InspectorSection("SOFTWARE & PROTOCOL") {
                InspectorRow("PXC version", capabilities?.pxcVersion, monospace = true)
                InspectorRow("SDK version", capabilities?.sdkVersion, monospace = true)
                InspectorRow("Software version", capabilities?.versionName, monospace = true)
                InspectorRow("Version code", capabilities?.versionCode, monospace = true)
                InspectorRow("Reported package", capabilities?.packageName, monospace = true)
                InspectorRow("Product type", capabilities?.productType?.toString(), monospace = true)
                InspectorRow("Transport type", capabilities?.transportType?.toString(), monospace = true)
                InspectorRow(
                    "Function mask",
                    capabilities?.supportFunction?.let { "0x${it.toString(16).uppercase(Locale.ENGLISH)}" },
                    monospace = true
                )
                InspectorRow(
                    "Wi-Fi socket timeout",
                    capabilities?.socketTimeoutPeriodWifi?.let { "$it ms" },
                    monospace = true
                )
            }

            InspectorSection("FEATURE FLAGS") {
                CapabilityRow("Screen mirroring", capabilities?.screenMirroring)
                CapabilityRow("Screen touch", capabilities?.screenTouch)
                CapabilityRow("Overlay touch", capabilities?.mirrorOverlayTouch)
                CapabilityRow("Mirror reconnect", capabilities?.mirrorReconnect)
                CapabilityRow("Landscape adaptive", capabilities?.landscapeAdaptive)
                CapabilityRow("Socket authentication", capabilities?.socketServerAuth)
                CapabilityRow("Microphone", capabilities?.microphone)
                CapabilityRow("HID input", capabilities?.hid)
                CapabilityRow("Third-party apps", capabilities?.thirdPartyApps)
                CapabilityRow("Phone signal", capabilities?.phoneSignal)
                CapabilityRow("Time synchronization", capabilities?.syncCorrectTime)
                CapabilityRow("Bluetooth calls", capabilities?.bluetoothCall)
                CapabilityRow("Bluetooth settings", capabilities?.bluetoothSettings)
            }

            Text(
                text = "Sensitive CLIENT_INFO fields are intentionally excluded from storage and display.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
    }
}

@Composable
private fun ObservationCard(
    snapshot: TBoxCapabilitySnapshot?,
    capabilities: TBoxCapabilities?
) {
    val complete = capabilities != null
    val title = when {
        complete -> "Capability report captured"
        snapshot?.host != null -> "T-Box discovered"
        else -> "No observations yet"
    }
    val detail = when {
        complete -> "CLIENT_INFO was captured during an EasyConn handshake."
        snapshot?.host != null -> "Start mirroring or Android Auto once to capture CLIENT_INFO."
        else -> "Connect this motorcycle, then start mirroring or Android Auto once."
    }
    val accent = if (complete) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    Card(
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.10f)),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.40f)),
        shape = MaterialTheme.shapes.large
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .background(accent, RoundedCornerShape(10.dp))
                    .padding(horizontal = 11.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (complete) "LIVE" else "WAIT",
                    color = MaterialTheme.colorScheme.background,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                formatTimestamp(snapshot?.capabilitiesObservedAtEpochMillis)?.let { timestamp ->
                    Text(
                        text = "Observed $timestamp",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun InspectorSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MonoLabel(title)
            content()
        }
    }
}

@Composable
private fun InspectorRow(label: String, value: String?, monospace: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.46f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value ?: "Not reported",
            modifier = Modifier.weight(0.54f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (value == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            fontWeight = if (value == null) FontWeight.Normal else FontWeight.SemiBold
        )
    }
}

@Composable
private fun CapabilityRow(label: String, supported: Boolean?) {
    val (text, color) = when (supported) {
        true -> "SUPPORTED" to MaterialTheme.colorScheme.tertiary
        false -> "NOT SUPPORTED" to MaterialTheme.colorScheme.error
        null -> "NOT REPORTED" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun PortScanResultView(result: TBoxPortScanResult) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (result.peerIp == null) {
            Text(
                motoHubText("Could not derive a peer IP - this network may not have a usable route yet."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            return@Column
        }
        val open = result.entries.filter { it.status == TBoxPortStatus.OPEN }
        Text(
            motoHubText(
                "Peer %1\$s -> %2\$s",
                result.peerIp,
                if (open.isEmpty()) motoHubText("no open ports found")
                else motoHubText("open: %1\$s", open.joinToString { it.port.toString() })
            ),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = if (open.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
        )
        // Only ports worth a second look: an accepted connection, or an explicit refusal (the
        // peer is alive and chose to reject it) - a silent timeout on most of the range is
        // expected and would just be noise here.
        result.entries.filter { it.status != TBoxPortStatus.NO_RESPONSE }.forEach { entry ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    motoHubText("Port %1\$d", entry.port),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    entry.status.name,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (entry.status == TBoxPortStatus.OPEN)
                        MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun DisplayGeometry.orientationName(): String = when {
    width > height -> "Landscape"
    width < height -> "Portrait"
    else -> "Square"
}

private fun formatTimestamp(epochMillis: Long?): String? = epochMillis?.let {
    DATE_FORMATTER.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
}

private val DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy / HH:mm", Locale.ENGLISH)
