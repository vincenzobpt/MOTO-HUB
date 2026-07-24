package io.motohub.android.feature.garage

import io.motohub.android.i18n.motoHubText

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import io.motohub.android.androidauto.AndroidAutoDisplayMode
import io.motohub.android.androidauto.TBoxScreenMargins
import io.motohub.android.feature.settings.MotoHubSettings
import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.units.UnitFormat
import io.motohub.android.session.ProjectionEventLog
import io.motohub.android.tbox.ProfileOverride
import io.motohub.android.tbox.TBoxModelProfile
import io.motohub.android.ui.components.MonoLabel
import io.motohub.android.ui.components.MotoHubActionRow
import io.motohub.android.ui.components.MotoHubBackground
import io.motohub.android.ui.components.MotoHubCardGroup
import io.motohub.android.ui.components.MotoHubDetailScreen
import io.motohub.android.ui.components.MotoHubHeader
import io.motohub.android.ui.components.MotoHubRadioRow

private enum class MotorcycleDetail { ANDROID_AUTO_DISPLAY, TFT_MARGINS, PROFILE_OVERRIDE }

/**
 * Motorcycle profile screen. A compact hub (photo, name, fuel, connection info) with the
 * less-frequently-touched settings behind drill-down screens, mirroring the same
 * hub/detail pattern [io.motohub.android.feature.settings.SettingsScreen] already uses -
 * replacing what used to be nine stacked cards in a single long scroll with mixed
 * (some instant, some button-gated) save behavior. Every field here now saves immediately,
 * with no separate "Save profile" step.
 */
@Composable
fun MotorcycleDetailsScreen(
    profile: MotorcycleProfile,
    displayMode: AndroidAutoDisplayMode,
    screenMargins: TBoxScreenMargins,
    onBack: () -> Unit,
    onSave: (MotorcycleProfile) -> Boolean,
    onOpenCapabilities: () -> Unit,
    onDisplayModeChanged: (AndroidAutoDisplayMode) -> Unit,
    onScreenMarginsChanged: (TBoxScreenMargins) -> Unit,
    onChoosePhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    onDelete: () -> Unit
) {
    var detail by rememberSaveable(profile.id) { mutableStateOf<MotorcycleDetail?>(null) }
    var showDeleteConfirmation by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = detail != null) { detail = null }
    BackHandler(enabled = detail == null, onBack = onBack)

    MotoHubBackground(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = detail,
            transitionSpec = {
                if (targetState != null) {
                    (slideInHorizontally { it / 3 } + fadeIn()) togetherWith
                        (slideOutHorizontally { -it / 3 } + fadeOut())
                } else {
                    (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith
                        (slideOutHorizontally { it / 3 } + fadeOut())
                }
            },
            label = "motorcycle-details"
        ) { current ->
            when (current) {
                null -> MotorcycleDetailsMainList(
                    profile = profile,
                    displayMode = displayMode,
                    onBack = onBack,
                    onSave = onSave,
                    onOpenDetail = { detail = it },
                    onOpenCapabilities = onOpenCapabilities,
                    onChoosePhoto = onChoosePhoto,
                    onRemovePhoto = onRemovePhoto,
                    onRequestDelete = { showDeleteConfirmation = true }
                )
                MotorcycleDetail.ANDROID_AUTO_DISPLAY -> AndroidAutoDisplayDetail(
                    displayMode = displayMode,
                    onDisplayModeChanged = onDisplayModeChanged,
                    onBack = { detail = null }
                )
                MotorcycleDetail.TFT_MARGINS -> TftMarginsDetail(
                    profile = profile,
                    screenMargins = screenMargins,
                    onScreenMarginsChanged = onScreenMarginsChanged,
                    onBack = { detail = null }
                )
                MotorcycleDetail.PROFILE_OVERRIDE -> ProfileOverrideDetail(
                    profile = profile,
                    onSave = onSave,
                    onBack = { detail = null }
                )
            }
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(motoHubText("Remove motorcycle?")) },
            text = { Text(motoHubText("The saved connection profile and its photo will be removed from MOTO-HUB.")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        onDelete()
                    }
                ) { Text(motoHubText("Remove"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) { Text(motoHubText("Cancel")) }
            }
        )
    }
}

@Composable
private fun MotorcycleDetailsMainList(
    profile: MotorcycleProfile,
    displayMode: AndroidAutoDisplayMode,
    onBack: () -> Unit,
    onSave: (MotorcycleProfile) -> Boolean,
    onOpenDetail: (MotorcycleDetail) -> Unit,
    onOpenCapabilities: () -> Unit,
    onChoosePhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    onRequestDelete: () -> Unit
) {
    val units = MotoHubSettings.distanceUnits(LocalContext.current)
    var name by rememberSaveable(profile.id) { mutableStateOf(profile.displayName.orEmpty()) }
    // The field edits the profile's km-native tank range in the rider's display unit.
    var fuelTankRangeText by rememberSaveable(profile.id) {
        mutableStateOf(
            profile.fuelTankRangeKm?.let { UnitFormat.wholeDistanceFromKm(it, units) }?.toString().orEmpty()
        )
    }

    fun persist(newName: String = name, newFuelText: String = fuelTankRangeText) {
        onSave(
            profile.copy(
                displayName = newName.trim().takeIf { it.isNotEmpty() },
                fuelTankRangeKm = newFuelText.toDoubleOrNull()?.takeIf { it > 0 }
                    ?.let { UnitFormat.kmFromDistance(it, units) }
            )
        )
    }

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
            MonoLabel(motoHubText("MOTORCYCLE PROFILE"))
            Text(motoHubText("Make it yours"), style = MaterialTheme.typography.headlineMedium)
            Text(
                motoHubText("Changes are saved immediately."),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        MotorcyclePhoto(
            path = profile.photoPath,
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onChoosePhoto,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(14.dp)
            ) { Text(if (profile.photoPath == null) "Add photo" else "Change photo") }
            if (profile.photoPath != null) {
                OutlinedButton(
                    onClick = onRemovePhoto,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(motoHubText("Remove photo")) }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(motoHubText("Identity"), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = name,
                    onValueChange = { input ->
                        name = input
                        persist(newName = input)
                    },
                    label = { Text(motoHubText("Display name")) },
                    placeholder = { Text(motoHubText("For example: MT700 Adventure")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    motoHubText("The name is only used inside MOTO-HUB."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider()

                Text(motoHubText("Fuel"), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = fuelTankRangeText,
                    onValueChange = { input ->
                        val digitsOnly = input.filter { it.isDigit() }
                        fuelTankRangeText = digitsOnly
                        persist(newFuelText = digitsOnly)
                    },
                    label = { Text(motoHubText("Tank range (${UnitFormat.wholeDistanceLabel(units)})")) },
                    placeholder = { Text(motoHubText("For example: 300")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    motoHubText("Full-tank range on a fill-up. Powers the fuel-range warning in navigation."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        MotoHubCardGroup {
            MotoHubActionRow(
                title = motoHubText("Android Auto Display"),
                description = motoHubText("How the complete Android Auto image fits the TFT"),
                value = displayMode.shortLabel,
                onClick = { onOpenDetail(MotorcycleDetail.ANDROID_AUTO_DISPLAY) }
            )
            MotoHubActionRow(
                title = motoHubText("TFT Safe Margins"),
                description = motoHubText("Exclude pixels occupied by the motorcycle UI"),
                onClick = { onOpenDetail(MotorcycleDetail.TFT_MARGINS) }
            )
            MotoHubActionRow(
                title = motoHubText("T-Box Profile Override"),
                description = motoHubText("Override automatic profile detection"),
                value = motoHubText((ProfileOverride.byKey(profile.profileOverrideKey) ?: ProfileOverride.AUTO).label),
                onClick = { onOpenDetail(MotorcycleDetail.PROFILE_OVERRIDE) }
            )
            MotoHubActionRow(
                title = motoHubText("T-Box Capability Inspector"),
                description = motoHubText("Endpoint, geometry, protocol, and feature flags"),
                onClick = onOpenCapabilities
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MonoLabel(motoHubText("CONNECTION"))
            Text(profile.ssid, fontFamily = FontFamily.Monospace)
            Text(
                motoHubText("The Wi-Fi password is stored securely on this phone."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            profile.modelId?.let { modelId ->
                Text(
                    motoHubText("T-Box identifier: %1\$s", modelId),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Text(
            motoHubText("Remove this motorcycle"),
            modifier = Modifier
                .clickable(onClick = onRequestDelete)
                .padding(vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun AndroidAutoDisplayDetail(
    displayMode: AndroidAutoDisplayMode,
    onDisplayModeChanged: (AndroidAutoDisplayMode) -> Unit,
    onBack: () -> Unit
) {
    MotoHubDetailScreen(title = motoHubText("Android Auto Display"), backLabel = motoHubText("‹ Motorcycle"), onBack = onBack) {
        Text(
            motoHubText("Choose how the complete Android Auto image is fitted to the TFT."),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FormatButton(
                text = "FIT",
                selected = displayMode == AndroidAutoDisplayMode.LETTERBOX,
                onClick = { onDisplayModeChanged(AndroidAutoDisplayMode.LETTERBOX) },
                modifier = Modifier.weight(1f)
            )
            FormatButton(
                text = "STRETCH",
                selected = displayMode == AndroidAutoDisplayMode.STRETCH,
                onClick = { onDisplayModeChanged(AndroidAutoDisplayMode.STRETCH) },
                modifier = Modifier.weight(1f)
            )
            FormatButton(
                text = "CROP",
                selected = displayMode == AndroidAutoDisplayMode.FILL,
                onClick = { onDisplayModeChanged(AndroidAutoDisplayMode.FILL) },
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            displayMode.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TftMarginsDetail(
    profile: MotorcycleProfile,
    screenMargins: TBoxScreenMargins,
    onScreenMarginsChanged: (TBoxScreenMargins) -> Unit,
    onBack: () -> Unit
) {
    MotoHubDetailScreen(title = motoHubText("TFT Safe Margins"), backLabel = motoHubText("‹ Motorcycle"), onBack = onBack) {
        Text(
            motoHubText("Exclude pixels occupied by the motorcycle UI. These values are stored for this ") +
                "motorcycle and applied to both Android Auto video and touch.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MarginField("Top", screenMargins.top, { value ->
                onScreenMarginsChanged(screenMargins.copy(top = value))
            }, Modifier.weight(1f))
            MarginField("Bottom", screenMargins.bottom, { value ->
                onScreenMarginsChanged(screenMargins.copy(bottom = value))
            }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MarginField("Left", screenMargins.left, { value ->
                onScreenMarginsChanged(screenMargins.copy(left = value))
            }, Modifier.weight(1f))
            MarginField("Right", screenMargins.right, { value ->
                onScreenMarginsChanged(screenMargins.copy(right = value))
            }, Modifier.weight(1f))
        }
        OutlinedButton(
            onClick = {
                // Restore this motorcycle model's own default margins, not zero -
                // some models (e.g. the 800NK family) ship with a non-zero default
                // because their native UI occupies part of the TFT out of the box.
                onScreenMarginsChanged(TBoxModelProfile.fromModelId(profile.modelId).defaultScreenMargins)
            },
            modifier = Modifier.fillMaxWidth().height(46.dp),
            shape = RoundedCornerShape(14.dp)
        ) { Text(motoHubText("Reset margins")) }
    }
}

@Composable
private fun ProfileOverrideDetail(
    profile: MotorcycleProfile,
    onSave: (MotorcycleProfile) -> Boolean,
    onBack: () -> Unit
) {
    var profileOverrideKey by rememberSaveable(profile.id) {
        mutableStateOf(profile.profileOverrideKey)
    }

    MotoHubDetailScreen(title = motoHubText("T-Box Profile Override"), backLabel = motoHubText("‹ Motorcycle"), onBack = onBack) {
        Text(
            motoHubText("Override the automatic profile detection for this motorcycle. Use only if the ") +
                "dashboard behaves unexpectedly. Tap the active option again to return to Auto.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ProfileOverride.entries.forEach { override ->
                val isActive = ProfileOverride.byKey(profileOverrideKey) == override
                MotoHubRadioRow(
                    title = motoHubText(override.label),
                    description = motoHubText(override.description),
                    selected = isActive,
                    onClick = {
                        val newKey = if (isActive) null else override.key
                        onSave(profile.copy(profileOverrideKey = newKey))
                        profileOverrideKey = newKey
                        val label = if (isActive) motoHubText("Auto") else motoHubText(override.label)
                        ProjectionEventLog.record(
                            "GARAGE",
                            "Profile override for ${profile.ssid}: $label."
                        )
                    }
                )
            }
        }
    }
}

private val AndroidAutoDisplayMode.shortLabel: String
    get() = when (this) {
        AndroidAutoDisplayMode.LETTERBOX -> "FIT"
        AndroidAutoDisplayMode.STRETCH -> "STRETCH"
        AndroidAutoDisplayMode.FILL -> "CROP"
    }

@Composable
private fun MarginField(
    label: String,
    value: Int,
    onValueChanged: (Int) -> Unit,
    modifier: Modifier
) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { input ->
            input.toIntOrNull()?.let { onValueChanged(it.coerceIn(0, TBoxScreenMargins.MAX)) }
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}

@Composable
private fun FormatButton(
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
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
            } else {
                androidx.compose.ui.graphics.Color.Transparent
            },
            contentColor = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface
        )
    ) { Text(text, fontWeight = FontWeight.Bold) }
}
