package io.motohub.android.feature.diagnostics

import io.motohub.android.i18n.motoHubText

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.motohub.android.session.LogLevel
import io.motohub.android.session.ProjectionEvent
import io.motohub.android.ui.components.MonoLabel
import io.motohub.android.ui.components.MotoHubBackground
import io.motohub.android.ui.components.MotoHubHeader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ApplicationLogScreen(
    events: List<ProjectionEvent>,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    MotoHubBackground(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            MotoHubHeader(
                modifier = Modifier.fillMaxWidth(),
                trailing = { TextButton(onClick = onBack) { Text(motoHubText("Close")) } }
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MonoLabel(motoHubText("TROUBLESHOOTING"))
                Text(motoHubText("Application logs"), style = MaterialTheme.typography.headlineMedium)
                Text(
                    motoHubText("Persistent events from Wi-Fi, T-Box, mirroring, encoder, and Android Auto."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onCopy, modifier = Modifier.weight(1f)) {
                    Text(motoHubText("Copy log"), fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                    Text(motoHubText("Share"))
                }
                OutlinedButton(onClick = onClear) {
                    Text(motoHubText("Clear"))
                }
            }
            Text(
                motoHubText("%1\$d entries  /  newest first", events.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (events.isEmpty()) {
                    item {
                        Text(
                            motoHubText("No diagnostic events have been recorded."),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(events.asReversed(), key = { it.sequence }) {
                    LogEntryCard(it)
                }
            }
        }
    }
}

@Composable
private fun LogEntryCard(event: ProjectionEvent) {
    val levelColor = when (event.level) {
        LogLevel.DEBUG -> MaterialTheme.colorScheme.outline
        LogLevel.INFO -> MaterialTheme.colorScheme.primary
        LogLevel.WARNING -> MaterialTheme.colorScheme.tertiary
        LogLevel.ERROR -> MaterialTheme.colorScheme.error
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.small
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.foundation.layout.Box(
                        Modifier
                            .background(levelColor, CircleShape)
                            .padding(4.dp)
                    )
                    Text(
                        event.source,
                        style = MaterialTheme.typography.labelMedium,
                        color = levelColor,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    LOG_TIME_FORMAT.format(Date(event.timestampMillis)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
            }
            Text(
                event.message,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                // A handful of very long entries (raw CLIENT_INFO JSON, hex dumps under
                // verbose T-Box logging) laying out in full every time they scroll into view
                // was heavy enough to hang the screen - this list is for scanning, not
                // reading a full JSON blob; Copy/Share still export the untruncated text.
                maxLines = 20,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private val LOG_TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
