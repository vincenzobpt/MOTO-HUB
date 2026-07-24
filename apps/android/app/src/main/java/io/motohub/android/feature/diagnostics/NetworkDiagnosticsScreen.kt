package io.motohub.android.feature.diagnostics

import io.motohub.android.i18n.motoHubText

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import io.motohub.android.session.ProjectionEvent
import io.motohub.android.ui.components.MonoLabel
import io.motohub.android.ui.components.MotoHubBackground
import io.motohub.android.ui.components.MotoHubHeader

@Composable
fun NetworkDiagnosticsScreen(
    state: NetworkDiagnosticsUiState,
    projectionEvents: List<ProjectionEvent>,
    onRunTests: () -> Unit,
    onBack: () -> Unit
) {
    var showEvents by rememberSaveable { mutableStateOf(false) }
    var showNetworks by rememberSaveable { mutableStateOf(false) }

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
                trailing = { TextButton(onClick = onBack) { Text(motoHubText("Close")) } }
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MonoLabel(motoHubText("SYSTEM LAB"))
                Text(motoHubText("Network diagnostics"), style = MaterialTheme.typography.headlineMedium)
                Text(
                    motoHubText("Check T-Box and cellular routes without starting a VPN or changing the default network."),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(
                onClick = onRunTests,
                enabled = !state.running,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                if (state.running) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                        modifier = Modifier
                            .size(20.dp)
                            .padding(end = 4.dp)
                    )
                }
                Text(
                    if (state.running) "Tests running" else "Run network tests",
                    fontWeight = FontWeight.Bold
                )
            }

            SummaryCard(state.conclusion)
            state.checks.forEach { check -> DiagnosticCheckCard(check) }

            DetailSection(
                title = motoHubText("Session events"),
                detail = if (projectionEvents.isEmpty()) "No events recorded" else "${projectionEvents.size} events",
                expanded = showEvents,
                onToggle = { showEvents = !showEvents }
            ) {
                if (projectionEvents.isEmpty()) {
                    Text(
                        motoHubText("Start a projection to populate the log."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    projectionEvents.asReversed().forEachIndexed { index, event ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                        }
                        val time = DateFormat.format("HH:mm:ss", event.timestampMillis)
                        Text(
                            motoHubText("%1\$s  %2\$s: %3\$s", time, event.source, event.message),
                            modifier = Modifier.padding(vertical = 7.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            DetailSection(
                title = motoHubText("Detected networks"),
                detail = if (state.networkSnapshot.isEmpty()) "Run the test first" else "${state.networkSnapshot.size} routes",
                expanded = showNetworks,
                onToggle = { showNetworks = !showNetworks }
            ) {
                if (state.networkSnapshot.isEmpty()) {
                    Text(
                        motoHubText("No network snapshot available."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    state.networkSnapshot.forEach { line ->
                        Text(
                            line,
                            modifier = Modifier.padding(vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun SummaryCard(conclusion: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            MonoLabel(motoHubText("RESULT"))
            Text(conclusion, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun DiagnosticCheckCard(check: NetworkDiagnosticCheck) {
    val color = when (check.status) {
        NetworkDiagnosticStatus.PASSED -> MaterialTheme.colorScheme.tertiary
        NetworkDiagnosticStatus.FAILED -> MaterialTheme.colorScheme.error
        NetworkDiagnosticStatus.RUNNING -> MaterialTheme.colorScheme.primary
        NetworkDiagnosticStatus.SKIPPED,
        NetworkDiagnosticStatus.NOT_RUN -> MaterialTheme.colorScheme.outline
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(13.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                Modifier
                    .padding(top = 5.dp)
                    .size(9.dp)
                    .background(color, CircleShape)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(check.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    check.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    check.status.name.replace('_', ' '),
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun DetailSection(
    title: String,
    detail: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(Modifier.fillMaxWidth()) {
            TextButton(
                onClick = onToggle,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.Start) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(if (expanded) "-" else "+", fontFamily = FontFamily.Monospace)
                }
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    content()
                }
            }
        }
    }
}
