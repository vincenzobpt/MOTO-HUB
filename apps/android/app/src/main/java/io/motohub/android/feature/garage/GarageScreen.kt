package io.motohub.android.feature.garage

import io.motohub.android.i18n.motoHubText

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.ui.components.LivePill
import io.motohub.android.ui.components.MonoLabel

@Composable
fun GarageTabContent(
    profiles: List<MotorcycleProfile>,
    activeProfileId: String?,
    onAddMotorcycle: () -> Unit,
    onAddMotorcycleManually: () -> Unit,
    onSelectMotorcycle: (String) -> Unit,
    onOpenDetails: (String) -> Unit
) {
    val active = profiles.firstOrNull { it.id == activeProfileId }
    val others = profiles.filterNot { it.id == activeProfileId }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MonoLabel(motoHubText("YOUR GARAGE"))
            Text(motoHubText("Motorcycles"), style = MaterialTheme.typography.displaySmall)
        }

        if (active == null) {
            EmptyGarageCard(onAddMotorcycle, onAddMotorcycleManually)
        } else {
            ActiveMotorcycleCard(
                profile = active,
                onOpenDetails = { onOpenDetails(active.id) }
            )
            if (others.isNotEmpty()) {
                MonoLabel(motoHubText("SAVED MOTORCYCLES"))
                others.forEach { profile ->
                    SavedMotorcycleCard(
                        profile = profile,
                        onSelect = { onSelectMotorcycle(profile.id) },
                        onOpenDetails = { onOpenDetails(profile.id) }
                    )
                }
            }
        }

        Button(
            onClick = onAddMotorcycle,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text(motoHubText("Add motorcycle"), fontWeight = FontWeight.Bold)
        }
        TextButton(
            onClick = onAddMotorcycleManually,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(motoHubText("No QR? Connect manually"))
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ActiveMotorcycleCard(
    profile: MotorcycleProfile,
    onOpenDetails: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier
                .clickable(onClick = onOpenDetails)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            MotorcyclePhoto(
                path = profile.photoPath,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(178.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    LivePill(motoHubText("ACTIVE PROFILE"))
                    Text(
                        profile.displayName ?: "Unnamed motorcycle",
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        profile.ssid,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onOpenDetails) { Text(motoHubText("Options")) }
            }
        }
    }
}

@Composable
private fun SavedMotorcycleCard(
    profile: MotorcycleProfile,
    onSelect: () -> Unit,
    onOpenDetails: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onOpenDetails)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MotorcyclePhoto(
                path = profile.photoPath,
                modifier = Modifier.size(width = 100.dp, height = 78.dp),
                shape = RoundedCornerShape(14.dp)
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    profile.displayName ?: "Unnamed motorcycle",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    profile.ssid,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                OutlinedButton(
                    onClick = onSelect,
                    modifier = Modifier.height(38.dp),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(motoHubText("Use this motorcycle")) }
            }
            TextButton(onClick = onOpenDetails) { Text(motoHubText("Edit")) }
        }
    }
}

@Composable
private fun EmptyGarageCard(onAddMotorcycle: () -> Unit, onAddMotorcycleManually: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(82.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(26.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("M", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
            }
            Text(motoHubText("Your garage is empty"), style = MaterialTheme.typography.titleLarge)
            Text(
                motoHubText("Add a motorcycle by scanning its T-Box QR code."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(onClick = onAddMotorcycle) { Text(motoHubText("Scan a QR code")) }
            TextButton(onClick = onAddMotorcycleManually) { Text(motoHubText("No QR? Connect manually")) }
        }
    }
}
