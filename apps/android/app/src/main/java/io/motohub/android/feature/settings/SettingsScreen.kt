package io.motohub.android.feature.settings

import io.motohub.android.i18n.motoHubText

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.motohub.android.BuildConfig
import io.motohub.android.R
import io.motohub.android.session.ProjectionEventLog
import io.motohub.android.ui.components.MonoLabel
import io.motohub.android.ui.components.MotoHubActionRow
import io.motohub.android.ui.components.MotoHubCardGroup
import io.motohub.android.ui.components.MotoHubDetailScreen
import io.motohub.android.ui.components.MotoHubRadioRow
import io.motohub.android.ui.components.ToggleRow

private enum class SettingsDetail { GENERAL, LANGUAGE, VIDEO, ANDROID_AUTO, AUTOMATION, DIAGNOSTICS }

@Composable
fun SettingsTabContent(
    onOpenNetworkDiagnostics: () -> Unit,
    onOpenApplicationLogs: () -> Unit,
    onOpenAbout: () -> Unit,
    seamlessResumeEnabled: Boolean,
    onSeamlessResumeChanged: (Boolean) -> Unit
) {
    var detail by rememberSaveable { mutableStateOf<SettingsDetail?>(null) }

    BackHandler(enabled = detail != null) { detail = null }

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
        label = "settings"
    ) { current ->
        when (current) {
            null -> SettingsMainList(
                onOpenDetail = { detail = it },
                onOpenNetworkDiagnostics = onOpenNetworkDiagnostics,
                onOpenApplicationLogs = onOpenApplicationLogs,
                onOpenAbout = onOpenAbout
            )
            SettingsDetail.GENERAL -> GeneralDetail(
                onBack = { detail = null },
                onOpenLanguage = { detail = SettingsDetail.LANGUAGE },
                seamlessResumeEnabled = seamlessResumeEnabled,
                onSeamlessResumeChanged = onSeamlessResumeChanged
            )
            SettingsDetail.LANGUAGE -> LanguageDetail(onBack = { detail = SettingsDetail.GENERAL })
            SettingsDetail.VIDEO -> VideoQualityDetail(onBack = { detail = null })
            SettingsDetail.ANDROID_AUTO -> AndroidAutoDetail(onBack = { detail = null })
            SettingsDetail.AUTOMATION -> AutomationDetail(onBack = { detail = null })
            SettingsDetail.DIAGNOSTICS -> DiagnosticsDetail(
                onBack = { detail = null },
                onOpenNetworkDiagnostics = onOpenNetworkDiagnostics,
                onOpenApplicationLogs = onOpenApplicationLogs
            )
        }
    }
}

@Composable
private fun SettingsMainList(
    onOpenDetail: (SettingsDetail) -> Unit,
    onOpenNetworkDiagnostics: () -> Unit,
    onOpenApplicationLogs: () -> Unit,
    onOpenAbout: () -> Unit
) {
    val context = LocalContext.current
    val strings = context.resources
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MonoLabel(strings.getString(R.string.settings_configuration))
            Text(strings.getString(R.string.settings_title), style = MaterialTheme.typography.displaySmall)
        }

        MotoHubCardGroup {
            MotoHubActionRow(
                title = strings.getString(R.string.settings_general),
                description = strings.getString(R.string.settings_general_description),
                onClick = { onOpenDetail(SettingsDetail.GENERAL) }
            )
            MotoHubActionRow(
                title = motoHubText("Video quality"),
                description = motoHubText("Encoder detail and power mode for all streams"),
                value = "${strings.getString(MotoHubSettings.videoQuality(context).labelRes)} · " +
                    strings.getString(MotoHubSettings.videoPowerMode(context).labelRes),
                onClick = { onOpenDetail(SettingsDetail.VIDEO) }
            )
            MotoHubActionRow(
                title = motoHubText("Android Auto"),
                description = motoHubText("Resolution and display mode"),
                value = strings.getString(MotoHubSettings.androidAutoResolution(context).labelRes),
                onClick = { onOpenDetail(SettingsDetail.ANDROID_AUTO) }
            )
            MotoHubActionRow(
                title = motoHubText("Connection & automation"),
                description = motoHubText("Auto-connect and recovery"),
                onClick = { onOpenDetail(SettingsDetail.AUTOMATION) }
            )
        }

        MotoHubCardGroup {
            MotoHubActionRow(
                title = motoHubText("Diagnostics"),
                description = motoHubText("Network tests and application logs"),
                onClick = { onOpenDetail(SettingsDetail.DIAGNOSTICS) }
            )
            MotoHubActionRow(
                title = motoHubText("About MOTO-HUB"),
                description = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                onClick = onOpenAbout
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun VideoQualityDetail(onBack: () -> Unit) {
    val context = LocalContext.current
    var quality by remember { mutableStateOf(MotoHubSettings.videoQuality(context)) }
    var powerMode by remember { mutableStateOf(MotoHubSettings.videoPowerMode(context)) }
    var disableTouchscreen by remember { mutableStateOf(MotoHubSettings.disableTouchscreen(context)) }
    MotoHubDetailScreen(title = motoHubText("Video quality"), backLabel = motoHubText("‹ Settings"), onBack = onBack) {
        Text(
            motoHubText("Choose image detail and how MOTO-HUB balances smoothness, heat, battery, and Wi-Fi load."),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        VideoQuality.entries.forEach { candidate ->
            MotoHubRadioRow(
                title = context.getString(candidate.labelRes),
                description = context.getString(candidate.descriptionRes),
                selected = quality == candidate,
                onClick = {
                    quality = candidate
                    MotoHubSettings.setVideoQuality(context, candidate)
                    ProjectionEventLog.record("SETTINGS", "Video quality changed to ${candidate.name}.")
                }
            )
        }
        HorizontalDivider()
        MonoLabel(motoHubText("POWER MODE"))
        VideoPowerMode.entries.forEach { candidate ->
            MotoHubRadioRow(
                title = context.getString(candidate.labelRes),
                description = context.getString(candidate.descriptionRes),
                selected = powerMode == candidate,
                onClick = {
                    powerMode = candidate
                    MotoHubSettings.setVideoPowerMode(context, candidate)
                    ProjectionEventLog.record("SETTINGS", "Video power mode changed to ${candidate.name}.")
                }
            )
        }
        HorizontalDivider()
        ToggleRow(
            title = motoHubText("Disable touchscreen"),
            description = motoHubText("Use focus and handlebar controls even when the T-Box reports a touch display"),
            checked = disableTouchscreen,
            onCheckedChange = {
                disableTouchscreen = it
                MotoHubSettings.setDisableTouchscreen(context, it)
                ProjectionEventLog.record("SETTINGS", "Disable touchscreen changed to enabled=$it.")
            }
        )
    }
}

@Composable
private fun GeneralDetail(
    onBack: () -> Unit,
    onOpenLanguage: () -> Unit,
    seamlessResumeEnabled: Boolean,
    onSeamlessResumeChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var autoUpdateChecks by remember { mutableStateOf(MotoHubSettings.autoUpdateChecks(context)) }
    MotoHubDetailScreen(
        title = context.getString(R.string.settings_general_title),
        backLabel = "‹ ${context.getString(R.string.settings_title)}",
        onBack = onBack
    ) {
        MotoHubActionRow(
            title = context.getString(R.string.language_title),
            description = context.getString(R.string.language_description),
            value = context.getString(AppLanguageManager.current(context).labelRes),
            onClick = onOpenLanguage
        )
        ToggleRow(
            title = context.getString(R.string.settings_check_updates_on_launch),
            description = context.getString(R.string.settings_check_updates_on_launch_description),
            checked = autoUpdateChecks,
            onCheckedChange = {
                autoUpdateChecks = it
                MotoHubSettings.setAutoUpdateChecks(context, it)
                ProjectionEventLog.record("SETTINGS", "Automatic update checks changed to enabled=$it.")
            }
        )
        ToggleRow(
            title = context.getString(R.string.settings_enable_seamless_resume),
            description = if (seamlessResumeEnabled) {
                context.getString(R.string.settings_seamless_resume_enabled_description)
            } else {
                context.getString(R.string.settings_seamless_resume_disabled_description)
            },
            checked = seamlessResumeEnabled,
            onCheckedChange = onSeamlessResumeChanged
        )
    }
}

@Composable
private fun LanguageDetail(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val current = AppLanguageManager.current(context)

    MotoHubDetailScreen(
        title = context.getString(R.string.language_title),
        backLabel = "‹ ${context.getString(R.string.settings_general_title)}",
        onBack = onBack
    ) {
        Text(
            context.getString(R.string.language_detail_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        AppLanguage.entries.forEach { language ->
            MotoHubRadioRow(
                title = context.getString(language.labelRes),
                description = if (language == AppLanguage.SYSTEM) {
                    context.getString(R.string.language_system_default)
                } else {
                    language.tag.orEmpty()
                },
                selected = current == language,
                onClick = {
                    if (current != language) {
                        AppLanguageManager.set(context, language)
                        ProjectionEventLog.record(
                            "SETTINGS",
                            "Application language changed to ${language.tag ?: "system default"}."
                        )
                        // LocaleManager updates the application configuration; recreating
                        // the activity makes every Compose screen pick up the new resources.
                        activity?.recreate()
                    }
                }
            )
        }
    }
}

@Composable
private fun AndroidAutoDetail(onBack: () -> Unit) {
    val context = LocalContext.current
    var resolution by remember { mutableStateOf(MotoHubSettings.androidAutoResolution(context)) }
    var aspectMatching by remember { mutableStateOf(MotoHubSettings.androidAutoAspectMatching(context)) }
    MotoHubDetailScreen(title = motoHubText("Android Auto"), backLabel = motoHubText("‹ Settings"), onBack = onBack) {
        MonoLabel(motoHubText("RESOLUTION"))
        AndroidAutoResolutionMode.entries.forEach { candidate ->
            MotoHubRadioRow(
                title = context.getString(candidate.labelRes),
                description = context.getString(candidate.descriptionRes),
                selected = resolution == candidate,
                onClick = {
                    resolution = candidate
                    MotoHubSettings.setAndroidAutoResolution(context, candidate)
                    ProjectionEventLog.record("SETTINGS", "Android Auto resolution changed to ${candidate.name}.")
                }
            )
        }
        HorizontalDivider()
        MonoLabel(motoHubText("ANDROID AUTO CONTENT INSETS"))
        AndroidAutoAspectMatchingMode.entries.forEach { candidate ->
            MotoHubRadioRow(
                title = context.getString(candidate.labelRes),
                description = context.getString(candidate.descriptionRes),
                selected = aspectMatching == candidate,
                onClick = {
                    aspectMatching = candidate
                    MotoHubSettings.setAndroidAutoAspectMatching(context, candidate)
                    ProjectionEventLog.record("SETTINGS", "Android Auto aspect matching changed to ${candidate.name}.")
                }
            )
        }
    }
}

@Composable
private fun AutomationDetail(onBack: () -> Unit) {
    val context = LocalContext.current
    var autoConnect by remember { mutableStateOf(MotoHubSettings.autoConnect(context)) }
    var autoRecovery by remember { mutableStateOf(MotoHubSettings.autoRecovery(context)) }
    MotoHubDetailScreen(title = motoHubText("Connection & automation"), backLabel = motoHubText("‹ Settings"), onBack = onBack) {
        ToggleRow(
            title = motoHubText("Auto-connect on launch"),
            description = motoHubText("Connect and discover T-Box when app opens"),
            checked = autoConnect,
            onCheckedChange = {
                autoConnect = it
                MotoHubSettings.setAutoConnect(context, it)
                ProjectionEventLog.record("SETTINGS", "Auto-connect changed to enabled=$it.")
            }
        )
        ToggleRow(
            title = motoHubText("Recovery watchdog"),
            description = motoHubText("Auto-recover stalled Android Auto and Ride Dashboard streams"),
            checked = autoRecovery,
            onCheckedChange = {
                autoRecovery = it
                MotoHubSettings.setAutoRecovery(context, it)
                ProjectionEventLog.record("SETTINGS", "Auto-recovery changed to enabled=$it.")
            }
        )
    }
}

@Composable
private fun DiagnosticsDetail(
    onBack: () -> Unit,
    onOpenNetworkDiagnostics: () -> Unit,
    onOpenApplicationLogs: () -> Unit
) {
    val context = LocalContext.current
    var loggingEnabled by remember { mutableStateOf(MotoHubSettings.loggingEnabled(context)) }
    var verboseLogging by remember { mutableStateOf(MotoHubSettings.verboseTBoxLogging(context)) }

    MotoHubDetailScreen(title = motoHubText("Diagnostics"), backLabel = motoHubText("‹ Settings"), onBack = onBack) {
        MotoHubCardGroup {
            MotoHubActionRow(
                title = motoHubText("Network diagnostics"),
                description = motoHubText("T-Box discovery, Wi-Fi binding, cellular routes"),
                onClick = onOpenNetworkDiagnostics
            )
            MotoHubActionRow(
                title = motoHubText("Application logs"),
                description = motoHubText("Review, copy, share, or clear events"),
                onClick = onOpenApplicationLogs
            )
        }
        ToggleRow(
            title = motoHubText("Enable logging"),
            description = motoHubText("Master switch for the diagnostic log. Off means nothing is recorded ") +
                "at all - not just less detail. On by default; turn off only if you don't want " +
                "MOTO-HUB keeping any local diagnostic history.",
            checked = loggingEnabled,
            onCheckedChange = {
                // Record the "why" before flipping off, and after flipping back on - the
                // gap in between is the point, but a change of this kind should still be
                // visible in the log itself, on either side of it.
                if (it) {
                    MotoHubSettings.setLoggingEnabled(context, true)
                    loggingEnabled = true
                    ProjectionEventLog.record("SETTINGS", "Logging enabled.")
                } else {
                    ProjectionEventLog.record("SETTINGS", "Logging disabled by the user.")
                    MotoHubSettings.setLoggingEnabled(context, false)
                    loggingEnabled = false
                }
            }
        )
        ToggleRow(
            title = motoHubText("Verbose T-Box logging"),
            description = motoHubText("Full CLIENT_INFO, every candidate profile's score, unknown command ") +
                "hex dumps, and Wi-Fi link quality. Off by default: heavier than the normal " +
                "log, only useful while diagnosing a connection problem. Has no effect while " +
                "logging above is off.",
            checked = verboseLogging,
            enabled = loggingEnabled,
            onCheckedChange = {
                verboseLogging = it
                MotoHubSettings.setVerboseTBoxLogging(context, it)
                ProjectionEventLog.record("SETTINGS", "Verbose T-Box logging changed to enabled=$it.")
            }
        )
    }
}
