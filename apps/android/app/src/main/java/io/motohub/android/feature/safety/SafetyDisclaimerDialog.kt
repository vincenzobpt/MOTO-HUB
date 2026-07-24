package io.motohub.android.feature.safety

import io.motohub.android.i18n.motoHubText

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties

@Composable
fun SafetyDisclaimerDialog(
    doNotShowAgain: Boolean,
    onDoNotShowAgainChanged: (Boolean) -> Unit,
    onContinue: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        ),
        title = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    modifier = Modifier.size(68.dp),
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.16f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "⚠",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 40.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Text(
                    text = "SAFETY WARNING",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "Riding requires your full attention. Never interact with MOTO-HUB, " +
                        "Android Auto, navigation, mirroring, trip recording, or any on-screen " +
                        "control while the motorcycle is moving.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Configure and verify everything only while parked. Use this application " +
                        "only in a completely safe and controlled situation. If conditions are not " +
                        "completely safe, do not use it.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "MOTO-HUB is not a safety device and cannot prevent distraction, crashes, " +
                        "injury, or damage. You are solely responsible for riding safely and obeying " +
                        "all applicable laws.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDoNotShowAgainChanged(!doNotShowAgain) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = doNotShowAgain,
                        onCheckedChange = onDoNotShowAgainChanged
                    )
                    Text(
                        text = "I understand — do not show this warning again",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onContinue) {
                Text(motoHubText("I understand and continue"))
            }
        }
    )
}
