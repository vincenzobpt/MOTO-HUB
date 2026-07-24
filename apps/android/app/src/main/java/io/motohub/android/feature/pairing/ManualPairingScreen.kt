package io.motohub.android.feature.pairing

import io.motohub.android.i18n.motoHubText

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.motohub.android.ui.components.MonoLabel
import io.motohub.android.ui.components.MotoHubBackground
import io.motohub.android.ui.components.MotoHubHeader
import io.motohub.android.session.TBoxConnectionMode

/**
 * Fallback pairing path for motorcycles that don't show an EasyConn QR code
 * (reported on some US-market bikes whose only official companion is
 * CFMOTO RideSync). The rider must already know the T-Box's Wi-Fi network
 * name and password from another source (the bike itself, its manual, or a
 * dealer) - this screen does not discover or guess credentials, it only
 * removes the QR scan as a hard requirement to enter them.
 */
@Composable
fun ManualPairingScreen(
    ssid: String,
    password: String,
    connectionMode: TBoxConnectionMode,
    formError: String?,
    onSsidChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onConnectionModeChanged: (TBoxConnectionMode) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)

    MotoHubBackground(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            MotoHubHeader(
                modifier = Modifier.fillMaxWidth(),
                trailing = { TextButton(onClick = onClose) { Text(motoHubText("Close")) } }
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MonoLabel(motoHubText("MANUAL SETUP"))
                Text(motoHubText("Connect without a QR code"), style = MaterialTheme.typography.displaySmall)
                Text(
                    motoHubText("Some motorcycles don't show a pairing QR code on the dash. If you already know ") +
                        "the T-Box's Wi-Fi network name and password - from the bike itself, its manual, " +
                        "or a dealer - enter them here instead of scanning.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            OutlinedTextField(
                value = ssid,
                onValueChange = onSsidChanged,
                label = { Text(motoHubText("Wi-Fi network name (SSID)")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(motoHubText("T-Box Wi-Fi transport"), style = MaterialTheme.typography.titleSmall)
                Text(
                    motoHubText("Auto detects DIRECT- networks. Choose Wi-Fi Direct for a dashboard that is a P2P Group Owner."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TBoxConnectionMode.entries.forEach { mode ->
                    FilterChip(
                        selected = connectionMode == mode,
                        onClick = { onConnectionModeChanged(mode) },
                        label = { Text(motoHubText(mode.label())) }
                    )
                }
            }
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChanged,
                label = { Text(motoHubText("Wi-Fi password")) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            formError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            Button(
                onClick = onSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(motoHubText("Save"), fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun TBoxConnectionMode.label(): String = when (this) {
    TBoxConnectionMode.AUTO -> "Auto"
    TBoxConnectionMode.ACCESS_POINT -> "Access point"
    TBoxConnectionMode.WIFI_DIRECT -> "Wi-Fi Direct (P2P)"
}
