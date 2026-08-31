// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.motohub.android.BuildConfig
import io.motohub.android.R
import io.motohub.android.feature.controls.HandlebarControlStore
import io.motohub.android.feature.controls.HandlebarHidCaptureService
import io.motohub.android.feature.controls.HandlebarInputMode
import io.motohub.android.feature.controls.HandlebarMappingScreen
import io.motohub.android.feature.controls.MediaButtonBridge
import io.motohub.android.feature.diagnostics.report.SupportIdSection
import io.motohub.android.session.ProjectionEventLog
import io.motohub.android.ui.components.MonoLabel
import io.motohub.android.ui.components.MotoHubActionRow
import io.motohub.android.ui.components.MotoHubCardGroup
import io.motohub.android.ui.components.MotoHubDetailScreen
import io.motohub.android.ui.components.MotoHubRadioRow
import io.motohub.android.feature.controls.HandlebarPressHud
import io.motohub.android.ui.components.ToggleRow

private enum class SettingsDetail {
    GENERAL, LANGUAGE, AUTOSTART, VIDEO, ANDROID_AUTO, ANDROID_AUTO_RESOLUTION, ANDROID_AUTO_DENSITY, HANDLEBAR, HANDLEBAR_MAPPING, AUTOMATION,
    DIAGNOSTICS
}

@Composable
fun SettingsTabContent(
    onOpenNetworkDiagnostics: () -> Unit,
    onOpenClockLab: () -> Unit,
    onOpenBleExplorer: () -> Unit,
    onOpenApplicationLogs: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenAndroidAutoHelp: () -> Unit,
    seamlessResumeEnabled: Boolean,
    onSeamlessResumeChanged: (Boolean) -> Unit
) {
    var detail by rememberSaveable { mutableStateOf<SettingsDetail?>(null) }

    // The enum is flat but the screens are not: Language and Autostart are reachable only from
    // General, and their own "‹ General" link says so. Sending back to the root from there would
    // skip a level and contradict the link right above it.
    BackHandler(enabled = detail != null) {
        detail = when (detail) {
            SettingsDetail.LANGUAGE, SettingsDetail.AUTOSTART -> SettingsDetail.GENERAL
            SettingsDetail.ANDROID_AUTO_RESOLUTION, SettingsDetail.ANDROID_AUTO_DENSITY ->
                SettingsDetail.ANDROID_AUTO
            else -> null
        }
    }

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
                onOpenAbout = onOpenAbout,
                onOpenAndroidAutoHelp = onOpenAndroidAutoHelp
            )
            SettingsDetail.GENERAL -> GeneralDetail(
                onBack = { detail = null },
                onOpenLanguage = { detail = SettingsDetail.LANGUAGE },
                onOpenAutostart = { detail = SettingsDetail.AUTOSTART },
                seamlessResumeEnabled = seamlessResumeEnabled,
                onSeamlessResumeChanged = onSeamlessResumeChanged
            )
            SettingsDetail.LANGUAGE -> LanguageDetail(onBack = { detail = SettingsDetail.GENERAL })
            SettingsDetail.AUTOSTART -> AutostartDetail(onBack = { detail = SettingsDetail.GENERAL })
            SettingsDetail.VIDEO -> VideoQualityDetail(onBack = { detail = null })
            SettingsDetail.ANDROID_AUTO -> AndroidAutoDetail(
                onBack = { detail = null },
                onOpenResolution = { detail = SettingsDetail.ANDROID_AUTO_RESOLUTION },
                onOpenDensity = { detail = SettingsDetail.ANDROID_AUTO_DENSITY }
            )
            SettingsDetail.ANDROID_AUTO_RESOLUTION -> AndroidAutoResolutionDetail(
                onBack = { detail = SettingsDetail.ANDROID_AUTO }
            )
            SettingsDetail.ANDROID_AUTO_DENSITY -> AndroidAutoDensityDetail(
                onBack = { detail = SettingsDetail.ANDROID_AUTO }
            )
            SettingsDetail.HANDLEBAR -> HandlebarControlsDetail(
                onBack = { detail = null },
                onOpenMapping = { detail = SettingsDetail.HANDLEBAR_MAPPING }
            )
            // The calibration-first mapping screen (shared with the companion app): one card
            // per PHYSICAL button, taught by pressing, instead of raw Bluetooth gesture names
            // that lie on half the dashes (a 700MT's held rocker arrives as "next track").
            SettingsDetail.HANDLEBAR_MAPPING -> HandlebarMappingScreen(
                onBack = { detail = SettingsDetail.HANDLEBAR },
                backLabel = "‹ ${motoHubText("Handlebar buttons")}"
            )
            SettingsDetail.AUTOMATION -> AutomationDetail(onBack = { detail = null })
            SettingsDetail.DIAGNOSTICS -> DiagnosticsDetail(
                onBack = { detail = null },
                onOpenNetworkDiagnostics = onOpenNetworkDiagnostics,
                onOpenClockLab = onOpenClockLab,
                onOpenBleExplorer = onOpenBleExplorer,
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
    onOpenAbout: () -> Unit,
    onOpenAndroidAutoHelp: () -> Unit
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
                value = "${strings.getString(MotoHubSettings.androidAutoResolution(context).labelRes)} · " +
                    strings.getString(MotoHubSettings.androidAutoDensity(context).labelRes),
                onClick = { onOpenDetail(SettingsDetail.ANDROID_AUTO) }
            )
            MotoHubActionRow(
                title = motoHubText("Handlebar buttons"),
                description = motoHubText("Drive Android Auto with the motorcycle's buttons"),
                value = if (HandlebarControlStore.isEnabled(context)) motoHubText("On") else motoHubText("Off"),
                onClick = { onOpenDetail(SettingsDetail.HANDLEBAR) }
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
                title = motoHubText("Android Auto does not start"),
                description = motoHubText("What to do when Android Auto refuses to project"),
                onClick = onOpenAndroidAutoHelp
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
    onOpenAutostart: () -> Unit,
    seamlessResumeEnabled: Boolean,
    onSeamlessResumeChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var autoUpdateChecks by remember { mutableStateOf(MotoHubSettings.autoUpdateChecks(context)) }
    val autostartEnabled = MotoHubSettings.autostartEnabled(context)
    val autostartService = MotoHubSettings.autostartService(context)
    MotoHubDetailScreen(
        title = context.getString(R.string.settings_general_title),
        backLabel = "‹ ${context.getString(R.string.settings_title)}",
        onBack = onBack
    ) {
        if (AppLanguageManager.isSupported) {
            MotoHubActionRow(
                title = context.getString(R.string.language_title),
                description = context.getString(R.string.language_description),
                value = context.getString(AppLanguageManager.current(context).labelRes),
                onClick = onOpenLanguage
            )
        }
        MotoHubActionRow(
            title = motoHubText("Start automatically"),
            description = motoHubText("Put a screen on the TFT as soon as the motorcycle connects"),
            value = if (autostartEnabled) motoHubText(autostartService.label) else motoHubText("Off"),
            onClick = onOpenAutostart
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
private fun AutostartDetail(onBack: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(MotoHubSettings.autostartEnabled(context)) }
    var service by remember { mutableStateOf(MotoHubSettings.autostartService(context)) }

    MotoHubDetailScreen(
        title = motoHubText("Start automatically"),
        backLabel = "‹ ${context.getString(R.string.settings_general_title)}",
        onBack = onBack
    ) {
        Text(
            motoHubText(
                "With this on, MOTO-HUB skips the \"what should I show?\" screen and puts the " +
                    "chosen screen on the TFT as soon as the motorcycle link comes up. It runs " +
                    "once per app launch - stop a screen and you are back in control."
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ToggleRow(
            title = motoHubText("Start on connect"),
            description = motoHubText("Off means the mode picker stays, exactly as before."),
            checked = enabled,
            onCheckedChange = {
                enabled = it
                MotoHubSettings.setAutostartEnabled(context, it)
                ProjectionEventLog.record("SETTINGS", "Autostart on connect changed to enabled=$it.")
            }
        )
        HorizontalDivider()
        MonoLabel(motoHubText("WHAT TO START"))
        AutostartService.entries
            .filter { BuildConfig.IS_PRO || !it.advancedOnly }
            .forEach { candidate ->
                MotoHubRadioRow(
                    title = motoHubText(candidate.label),
                    description = motoHubText(candidate.description),
                    selected = service == candidate,
                    onClick = {
                        service = candidate
                        MotoHubSettings.setAutostartService(context, candidate)
                        ProjectionEventLog.record("SETTINGS", "Autostart service changed to ${candidate.name}.")
                    }
                )
            }
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
private fun AndroidAutoDetail(
    onBack: () -> Unit,
    onOpenResolution: () -> Unit,
    onOpenDensity: () -> Unit
) {
    val context = LocalContext.current
    var aspectMatching by remember { mutableStateOf(MotoHubSettings.androidAutoAspectMatching(context)) }
    MotoHubDetailScreen(title = motoHubText("Android Auto"), backLabel = motoHubText("‹ Settings"), onBack = onBack) {
        // Nine coded sources and seven densities do not belong on one scrolling page next to the
        // insets picker: each is its own question, so each gets its own screen and this one shows
        // the answers. Read straight from the store rather than remembered - returning from a
        // child recomposes this screen, and a remembered copy would show the old choice.
        MotoHubActionRow(
            title = motoHubText("Resolution"),
            description = motoHubText("The video source Android Auto sends to the dashboard"),
            value = context.getString(MotoHubSettings.androidAutoResolution(context).labelRes),
            onClick = onOpenResolution
        )
        MotoHubActionRow(
            title = motoHubText("Interface size"),
            description = motoHubText("How large Android Auto draws itself on that source"),
            value = context.getString(MotoHubSettings.androidAutoDensity(context).labelRes),
            onClick = onOpenDensity
        )
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
    var keepWifiDirect by remember { mutableStateOf(MotoHubSettings.keepWifiDirectAfterDisconnect(context)) }
    var bluetoothClock by remember { mutableStateOf(MotoHubSettings.bluetoothClockSync(context)) }
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
            description = motoHubText("Auto-recover stalled Android Auto streams"),
            checked = autoRecovery,
            onCheckedChange = {
                autoRecovery = it
                MotoHubSettings.setAutoRecovery(context, it)
                ProjectionEventLog.record("SETTINGS", "Auto-recovery changed to enabled=$it.")
            }
        )
        ToggleRow(
            title = motoHubText("Set the dash clock over Bluetooth (experimental)"),
            description = motoHubText(
                "Some dashboards ask for the time over Bluetooth instead of Wi-Fi, and sit at " +
                    "00:00 without it. Needs the bike already paired to this phone in Android's " +
                    "Bluetooth settings. Off by default; MOTO-HUB only ever replies to a device " +
                    "that asks in the dashboard's own protocol."
            ),
            checked = bluetoothClock,
            onCheckedChange = {
                bluetoothClock = it
                MotoHubSettings.setBluetoothClockSync(context, it)
                ProjectionEventLog.record("SETTINGS", "Bluetooth clock sync changed to enabled=$it.")
            }
        )
        ToggleRow(
            title = motoHubText("Stay linked to the bike's Wi-Fi after disconnecting"),
            description = motoHubText(
                "Some dashboards forget settings like the clock when the Wi-Fi Direct link " +
                    "fully drops. Keeps the phone associated to the bike's network until you " +
                    "leave the app or turn Wi-Fi off. Off by default; only turn on if your " +
                    "dash loses the time after disconnecting."
            ),
            checked = keepWifiDirect,
            onCheckedChange = {
                keepWifiDirect = it
                MotoHubSettings.setKeepWifiDirectAfterDisconnect(context, it)
                ProjectionEventLog.record("SETTINGS", "Keep Wi-Fi Direct after disconnect changed to enabled=$it.")
            }
        )
    }
}

@Composable
private fun DiagnosticsDetail(
    onBack: () -> Unit,
    onOpenNetworkDiagnostics: () -> Unit,
    onOpenClockLab: () -> Unit,
    onOpenBleExplorer: () -> Unit,
    onOpenApplicationLogs: () -> Unit
) {
    val context = LocalContext.current
    var loggingEnabled by remember { mutableStateOf(MotoHubSettings.loggingEnabled(context)) }
    var verboseLogging by remember { mutableStateOf(MotoHubSettings.verboseTBoxLogging(context)) }
    var pressBanner by remember { mutableStateOf(HandlebarPressHud.isEnabled(context)) }

    MotoHubDetailScreen(title = motoHubText("Diagnostics"), backLabel = motoHubText("‹ Settings"), onBack = onBack) {
        SupportIdSection(loggingEnabled = loggingEnabled)
        MotoHubCardGroup {
            MotoHubActionRow(
                title = motoHubText("Network diagnostics"),
                description = motoHubText("T-Box discovery, Wi-Fi binding, cellular routes"),
                onClick = onOpenNetworkDiagnostics
            )
            MotoHubActionRow(
                title = motoHubText("Dash clock lab"),
                description = motoHubText("Experiments for dashes that reset the clock (Zontes, Voge)"),
                onClick = onOpenClockLab
            )
            MotoHubActionRow(
                title = motoHubText("Bluetooth LE explorer"),
                description = motoHubText("Scan, connect and read any BLE device byte by byte"),
                onClick = onOpenBleExplorer
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
                "hex dumps, and Wi-Fi link quality. On by default so a problem's first " +
                "occurrence is already captured; turn off for a lighter log. Has no effect " +
                "while logging above is off.",
            checked = verboseLogging,
            enabled = loggingEnabled,
            onCheckedChange = {
                verboseLogging = it
                MotoHubSettings.setVerboseTBoxLogging(context, it)
                ProjectionEventLog.record("SETTINGS", "Verbose T-Box logging changed to enabled=$it.")
            }
        )
        ToggleRow(
            title = motoHubText("Show button presses on the dashboard"),
            description = motoHubText("Every handlebar press ") +
                "- puts a black banner on the TFT for one second naming the button and the " +
                "action it ran. It is how you find out whether a press arrived at all, and " +
                "what it did, without reading a log. Works in Android Auto and on the Ride " +
                "Dashboard alike.",
            checked = pressBanner,
            onCheckedChange = {
                pressBanner = it
                HandlebarPressHud.setEnabled(context, it)
                ProjectionEventLog.record("SETTINGS", "Press banner changed to enabled=$it.")
            }
        )
    }
}

@Composable
private fun HandlebarControlsDetail(onBack: () -> Unit, onOpenMapping: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(HandlebarControlStore.isEnabled(context)) }
    var inputMode by remember { mutableStateOf(HandlebarControlStore.inputMode(context)) }
    // Granted outside this app, in system settings, so the only moment it can have changed is a
    // return to this screen - hence the resume watch rather than a plain read in composition.
    var hidServiceEnabled by remember { mutableStateOf(HandlebarHidCaptureService.isEnabled(context)) }
    var openedAccessibilitySettings by rememberSaveable { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hidServiceEnabled = HandlebarHidCaptureService.isEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val volumeLevels = remember { MediaButtonBridge.volumeLevels(context) }
    var listeningVolume by remember { mutableStateOf(volumeLevels.first.toFloat()) }
    MotoHubDetailScreen(
        title = motoHubText("Handlebar buttons"),
        backLabel = "‹ ${motoHubText("Settings")}",
        onBack = onBack
    ) {
        Text(
            motoHubText(
                "The motorcycle's buttons reach the phone over Bluetooth as media keys. " +
                    "While a session is streaming, MOTO-HUB can capture them and drive " +
                    "Android Auto navigation instead of the music player."
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (HandlebarControlStore.isManagedByCompanion(context)) {
            // The companion re-pushes ITS handlebar configuration at every session start,
            // silently overwriting anything set here — without this note the Core switch
            // looks broken ("I enabled it and it turned itself off").
            Text(
                motoHubText(
                    "Managed by the companion app: its Controls screen re-applies its own " +
                        "handlebar configuration every time a session starts, overwriting " +
                        "what is set here. Configure the handlebar there."
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
        MonoLabel(motoHubText("INPUT PROTOCOL"))
        Text(
            motoHubText(
                "Most dashboards send buttons as AVRCP media keys — leave this on AVRCP. Pick " +
                    "HID only if the remote pairs as a Bluetooth keyboard and its presses never " +
                    "register below."
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HandlebarInputMode.entries.forEach { candidate ->
            MotoHubRadioRow(
                title = motoHubText(candidate.label),
                description = motoHubText(candidate.description),
                selected = inputMode == candidate,
                onClick = {
                    inputMode = candidate
                    HandlebarControlStore.setInputMode(context, candidate)
                    // A session may well be running while the rider is in here - that is when
                    // they discover the protocol is wrong. Without this the switch takes effect
                    // only at the next session start.
                    MediaButtonBridge.inputModeChanged()
                    ProjectionEventLog.record(
                        "SETTINGS",
                        "Handlebar input mode changed to ${candidate.name}."
                    )
                }
            )
        }
        if (inputMode == HandlebarInputMode.HID) {
            if (!hidServiceEnabled) {
                Text(
                    motoHubText(
                        "HID mode also needs MOTO-HUB's Accessibility Service turned on, or " +
                            "presses will not be seen."
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            MotoHubActionRow(
                title = motoHubText("Open Accessibility settings"),
                description = motoHubText(
                    "Turn on MOTO-HUB so handlebar presses reach the app from any screen"
                ),
                onClick = {
                    openedAccessibilitySettings = true
                    HandlebarHidCaptureService.openAccessibilitySettings(context)
                }
            )
            // Second half of the grant, and only shown once the rider has come back from the
            // first half without the service on - which is exactly what the Android 13+
            // restricted-settings gate looks like from here. Never shown pre-emptively: on a
            // phone where the toggle worked normally this step would be noise.
            // See HandlebarHidCaptureService.openAppInfo.
            if (openedAccessibilitySettings && !hidServiceEnabled) {
                Text(
                    motoHubText(
                        "Was MOTO-HUB's switch greyed out? Android blocks it for apps that " +
                            "were not installed from a store, and MOTO-HUB is downloaded from " +
                            "GitHub. Open App info, tap ⋮ at the top right, choose \"Allow " +
                            "restricted settings\", then come back and turn the switch on."
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                MotoHubActionRow(
                    title = motoHubText("Open App info"),
                    description = motoHubText("Where \"Allow restricted settings\" lives"),
                    onClick = { HandlebarHidCaptureService.openAppInfo(context) }
                )
            }
        }
        HorizontalDivider()
        ToggleRow(
            title = motoHubText("Buttons control Android Auto"),
            description = motoHubText(
                "Requires the phone paired to the motorcycle's Bluetooth. Music keeps playing " +
                    "but its buttons are captured while a session runs."
            ),
            checked = enabled,
            onCheckedChange = { value ->
                enabled = value
                HandlebarControlStore.setEnabled(context, value)
                val applied = MediaButtonBridge.setTargetCaptureActive(
                    MediaButtonBridge.TARGET_ANDROID_AUTO,
                    value
                )
                ProjectionEventLog.record(
                    "SETTINGS",
                    "Handlebar capture changed to enabled=$value; liveSession=$applied."
                )
            }
        )
        MotoHubActionRow(
            title = motoHubText("Button mapping"),
            description = motoHubText("What each press, double press and hold does"),
            onClick = onOpenMapping
        )
        HorizontalDivider()
        MonoLabel(motoHubText("MUSIC VOLUME"))
        Text(
            motoHubText(
                "While capture is on, the volume buttons navigate instead of changing " +
                    "volume — set your listening level here."
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = listeningVolume,
            onValueChange = { listeningVolume = it },
            onValueChangeFinished = {
                MediaButtonBridge.setVolume(context, listeningVolume.toInt())
            },
            valueRange = 0f..volumeLevels.second.toFloat(),
            steps = (volumeLevels.second - 1).coerceAtLeast(0)
        )
    }
}

@Composable
private fun AndroidAutoResolutionDetail(onBack: () -> Unit) {
    val context = LocalContext.current
    var resolution by remember { mutableStateOf(MotoHubSettings.androidAutoResolution(context)) }
    val select: (AndroidAutoResolutionMode) -> Unit = { candidate ->
        resolution = candidate
        MotoHubSettings.setAndroidAutoResolution(context, candidate)
        ProjectionEventLog.record("SETTINGS", "Android Auto resolution changed to ${candidate.name}.")
    }
    MotoHubDetailScreen(
        title = motoHubText("Resolution"),
        backLabel = "‹ ${motoHubText("Android Auto")}",
        onBack = onBack
    ) {
        // Every source the Android Auto protocol defines, split the way a rider thinks about
        // them - the shape of their dashboard first, the number of pixels second.
        AndroidAutoResolutionMode.entries.filter { it.preset == null }.forEach { candidate ->
            AndroidAutoResolutionRow(candidate, resolution == candidate) { select(candidate) }
        }
        HorizontalDivider()
        MonoLabel(motoHubText("LANDSCAPE"))
        AndroidAutoResolutionMode.entries.filter { it.preset != null && it.landscape }
            .forEach { candidate ->
                AndroidAutoResolutionRow(candidate, resolution == candidate) { select(candidate) }
            }
        HorizontalDivider()
        MonoLabel(motoHubText("PORTRAIT"))
        AndroidAutoResolutionMode.entries.filter { it.preset != null && !it.landscape }
            .forEach { candidate ->
                AndroidAutoResolutionRow(candidate, resolution == candidate) { select(candidate) }
            }
    }
}

@Composable
private fun AndroidAutoResolutionRow(
    candidate: AndroidAutoResolutionMode,
    selected: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val description = context.getString(candidate.descriptionRes)
    MotoHubRadioRow(
        title = context.getString(candidate.labelRes),
        // The warning is part of the sentence rather than a badge: these sources are not worse,
        // they are unproven, and a rider choosing one should read why before they ride on it.
        description = if (candidate.experimental) {
            "${motoHubText("Experimental")} · $description"
        } else {
            description
        },
        selected = selected,
        onClick = onClick
    )
}

@Composable
private fun AndroidAutoDensityDetail(onBack: () -> Unit) {
    val context = LocalContext.current
    var density by remember { mutableStateOf(MotoHubSettings.androidAutoDensity(context)) }
    MotoHubDetailScreen(
        title = motoHubText("Interface size"),
        backLabel = "‹ ${motoHubText("Android Auto")}",
        onBack = onBack
    ) {
        AndroidAutoDensityMode.entries.forEach { candidate ->
            MotoHubRadioRow(
                title = context.getString(candidate.labelRes),
                description = context.getString(candidate.descriptionRes),
                selected = density == candidate,
                onClick = {
                    density = candidate
                    MotoHubSettings.setAndroidAutoDensity(context, candidate)
                    ProjectionEventLog.record(
                        "SETTINGS",
                        "Android Auto density changed to ${candidate.name}."
                    )
                }
            )
        }
    }
}
