// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.diagnostics

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.motohub.android.i18n.motoHubText
import io.motohub.android.tbox.ThinkerRideGate
import io.motohub.android.ui.components.MonoLabel
import io.motohub.android.ui.components.MotoHubBackground
import io.motohub.android.ui.components.MotoHubHeader
import io.motohub.android.ui.theme.MotoHubLive
import io.motohub.android.ui.theme.MotoHubManual
import io.motohub.android.ui.theme.MotoHubMirror
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A workbench for any Bluetooth LE device, not just the ones MOTO-HUB already knows.
 *
 * The need is concrete: a handlebar remote that advertises no service UUIDs cannot be identified
 * by any app, and the only way to learn what it is, is to connect and ask it. Everything a rider
 * needs to do that is here — scan, connect, walk the tree, read, write, subscribe — and every byte
 * lands in the diagnostic log, so a remote can be worked out from a report without the device ever
 * leaving its owner.
 */
@Composable
fun BleExplorerScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    // A scan left running after the screen is gone keeps the radio awake with nobody watching.
    // Leaving the screen ends the scan and the link the same way the learn wizard does.
    DisposableEffect(Unit) {
        onDispose {
            BleExplorer.stopScan(context)
            BleExplorer.disconnect()
        }
    }

    val scanning by BleExplorer.scanning.collectAsState()
    val devices by BleExplorer.devices.collectAsState()
    val linkState by BleExplorer.linkState.collectAsState()
    val connected by BleExplorer.connected.collectAsState()
    val services by BleExplorer.services.collectAsState()
    val traffic by BleExplorer.traffic.collectAsState()
    val mtu by BleExplorer.mtu.collectAsState()

    var filter by remember { mutableStateOf("") }
    var namedOnly by remember { mutableStateOf(false) }
    var writing by remember { mutableStateOf<BleCharacteristicNode?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants -> if (grants.values.all { it }) BleExplorer.startScan(context) }
    val scanWithPermissions: () -> Unit = {
        if (ThinkerRideGate.hasBlePermissions(context)) {
            BleExplorer.startScan(context)
        } else {
            permissionLauncher.launch(ThinkerRideGate.blePermissions)
        }
    }

    val visible = devices.filter { entry ->
        (!namedOnly || entry.name?.isNotBlank() == true) &&
            (filter.isBlank() ||
                entry.label.contains(filter, ignoreCase = true) ||
                entry.address.contains(filter, ignoreCase = true) ||
                entry.serviceUuids.any { it.contains(filter, ignoreCase = true) })
    }

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
                trailing = { TextButton(onClick = onBack) { Text(motoHubText("Close")) } }
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MonoLabel(motoHubText("SYSTEM LAB"))
                Text(motoHubText("Bluetooth LE explorer"), style = MaterialTheme.typography.headlineMedium)
                Text(
                    motoHubText(
                        "Find any Bluetooth LE device, open it, and see what it is really made of: " +
                            "its services, its characteristics, and every byte it sends. Made for " +
                            "remotes that announce nothing about themselves - press their buttons " +
                            "while subscribed and the protocol writes itself into the log."
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(
                onClick = { if (scanning) BleExplorer.stopScan(context) else scanWithPermissions() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    motoHubText(if (scanning) "Stop scanning" else "Scan for devices"),
                    fontWeight = FontWeight.Bold
                )
            }

            if (connected != null) {
                ConnectedDevice(
                    entry = connected,
                    linkState = linkState,
                    mtu = mtu,
                    services = services,
                    onDisconnect = { BleExplorer.disconnect() },
                    onRediscover = { BleExplorer.rediscoverServices() },
                    onRequestMtu = { BleExplorer.requestMtu(517) },
                    onReadRssi = { BleExplorer.readRemoteRssi() },
                    onRead = { BleExplorer.read(it) },
                    onWrite = { writing = it },
                    onToggleNotify = { BleExplorer.setNotifying(it, !it.notifying) }
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MonoLabel(motoHubText("DEVICES (%1\$d)", visible.size))
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    label = { Text(motoHubText("Filter by name, address or service")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(onClick = { namedOnly = !namedOnly }) {
                    Text(motoHubText(if (namedOnly) "Showing named devices only" else "Showing every device"))
                }
                if (visible.isEmpty()) {
                    Text(
                        motoHubText(
                            if (scanning) {
                                "Nothing yet. A remote that sleeps only advertises for a few seconds " +
                                    "after a button is pressed - press one and keep it close."
                            } else {
                                "Start a scan to see what is around."
                            }
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                visible.forEach { entry ->
                    DeviceRow(entry = entry, onClick = { BleExplorer.connect(context, entry) })
                }
            }

            TrafficView(traffic = traffic, onClear = { BleExplorer.clearTraffic() })
            Spacer(Modifier.height(8.dp))
        }
    }

    writing?.let { node ->
        WriteDialog(
            node = node,
            onDismiss = { writing = null },
            onWrite = { bytes, withResponse ->
                BleExplorer.write(node, bytes, withResponse)
                writing = null
            }
        )
    }
}

@Composable
private fun DeviceRow(entry: BleScanEntry, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    entry.label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "${entry.rssi} dBm",
                    style = MaterialTheme.typography.labelMedium,
                    color = signalColour(entry.rssi)
                )
            }
            Text(
                entry.address + if (entry.connectable) "" else motoHubText("  - not connectable"),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // The three fields that identify an unknown device, when it offers any of them: the
            // services it claims, the vendor id in its manufacturer data, and whatever it puts in
            // service data. A remote that shows none of these can only be identified by connecting.
            if (entry.serviceUuids.isNotEmpty()) {
                Text(
                    motoHubText("services: ") + entry.serviceUuids.joinToString { BleNames.short(java.util.UUID.fromString(it)) },
                    style = MaterialTheme.typography.labelSmall,
                    color = MotoHubMirror
                )
            }
            if (entry.manufacturer.isNotEmpty()) {
                Text(
                    motoHubText("manufacturer: ") + entry.manufacturer.joinToString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MotoHubManual
                )
            }
            if (entry.serviceData.isNotEmpty()) {
                Text(
                    motoHubText("service data: ") + entry.serviceData.joinToString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ConnectedDevice(
    entry: BleScanEntry?,
    linkState: BleLinkState,
    mtu: Int,
    services: List<BleServiceNode>,
    onDisconnect: () -> Unit,
    onRediscover: () -> Unit,
    onRequestMtu: () -> Unit,
    onReadRssi: () -> Unit,
    onRead: (BleCharacteristicNode) -> Unit,
    onWrite: (BleCharacteristicNode) -> Unit,
    onToggleNotify: (BleCharacteristicNode) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MonoLabel(motoHubText("CONNECTED"))
            Text(entry?.label.orEmpty(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "${entry?.address.orEmpty()}  -  ${linkState.name.lowercase()}  -  MTU $mtu",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDisconnect, modifier = Modifier.weight(1f)) {
                    Text(motoHubText("Disconnect"))
                }
                OutlinedButton(onClick = onRediscover, modifier = Modifier.weight(1f)) {
                    Text(motoHubText("Rediscover"))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRequestMtu, modifier = Modifier.weight(1f)) {
                    Text(motoHubText("MTU 517"))
                }
                OutlinedButton(onClick = onReadRssi, modifier = Modifier.weight(1f)) {
                    Text(motoHubText("Read RSSI"))
                }
            }
            services.forEach { service ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        BleNames.describe(service.uuid),
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MotoHubLive
                    )
                    service.characteristics.forEach { node ->
                        CharacteristicRow(
                            node = node,
                            onRead = { onRead(node) },
                            onWrite = { onWrite(node) },
                            onToggleNotify = { onToggleNotify(node) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CharacteristicRow(
    node: BleCharacteristicNode,
    onRead: () -> Unit,
    onWrite: () -> Unit,
    onToggleNotify: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                BleNames.describe(node.uuid),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace
            )
            Text(
                node.propertyLabels().joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            node.lastValue?.let { value ->
                Text(
                    BleHex.encode(value) + BleHex.ascii(value).let { text ->
                        if (text.any { it != '.' }) "   \"$text\"" else ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MotoHubLive
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (node.canRead) {
                    OutlinedButton(onClick = onRead, modifier = Modifier.weight(1f)) {
                        Text(motoHubText("Read"), style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (node.canWrite || node.canWriteNoResponse) {
                    OutlinedButton(onClick = onWrite, modifier = Modifier.weight(1f)) {
                        Text(motoHubText("Write"), style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (node.canNotify || node.canIndicate) {
                    OutlinedButton(onClick = onToggleNotify, modifier = Modifier.weight(1f)) {
                        Text(
                            motoHubText(if (node.notifying) "Unsubscribe" else "Subscribe"),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WriteDialog(
    node: BleCharacteristicNode,
    onDismiss: () -> Unit,
    onWrite: (ByteArray, Boolean) -> Unit
) {
    var text by remember { mutableStateOf("") }
    val bytes = BleHex.decode(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(motoHubText("Write to %1\$s", BleNames.short(node.uuid))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    motoHubText(
                        "Bytes in hex - \"01 FF 0A\", \"01ff0a\" and \"0x01,0xFF\" all work. " +
                            "This writes straight to the device: on some hardware the wrong value " +
                            "changes settings that are hard to change back."
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(motoHubText("Hex")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                )
                Text(
                    if (bytes == null) {
                        motoHubText("Not valid hex yet.")
                    } else {
                        motoHubText("%1\$d byte(s)", bytes.size)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (bytes == null) MaterialTheme.colorScheme.error else MotoHubLive
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = bytes != null && node.canWrite,
                onClick = { bytes?.let { onWrite(it, true) } }
            ) { Text(motoHubText("Write")) }
        },
        dismissButton = {
            Row {
                if (node.canWriteNoResponse) {
                    TextButton(
                        enabled = bytes != null,
                        onClick = { bytes?.let { onWrite(it, false) } }
                    ) { Text(motoHubText("No response")) }
                }
                TextButton(onClick = onDismiss) { Text(motoHubText("Cancel")) }
            }
        }
    )
}

@Composable
private fun TrafficView(traffic: List<BleTrafficLine>, onClear: () -> Unit) {
    val clock = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MonoLabel(motoHubText("TRAFFIC"))
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClear) { Text(motoHubText("Clear")) }
        }
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 340.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                if (traffic.isEmpty()) {
                    Text(
                        motoHubText("Nothing yet."),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                traffic.forEach { line ->
                    Text(
                        "${clock.format(Date(line.atMillis))}  ${prefix(line.kind)} ${line.text}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = when (line.kind) {
                            BleTrafficLine.Kind.IN -> MotoHubLive
                            BleTrafficLine.Kind.OUT -> MotoHubMirror
                            BleTrafficLine.Kind.FAILURE -> MaterialTheme.colorScheme.error
                            BleTrafficLine.Kind.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
        Text(
            motoHubText(
                "Every line here is also in the application log, so a session can be sent in a " +
                    "diagnostic report."
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun prefix(kind: BleTrafficLine.Kind): String = when (kind) {
    BleTrafficLine.Kind.IN -> "<-"
    BleTrafficLine.Kind.OUT -> "->"
    BleTrafficLine.Kind.FAILURE -> "!!"
    BleTrafficLine.Kind.INFO -> "  "
}

private fun signalColour(rssi: Int): Color = when {
    rssi >= -60 -> MotoHubLive
    rssi >= -80 -> MotoHubManual
    else -> Color(0xFF8C93A0)
}
