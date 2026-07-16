package io.motohub.android

import android.app.Activity
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.motohub.android.aa.AaSelfMode
import io.motohub.android.androidauto.AndroidAutoRuntime
import io.motohub.android.androidauto.AndroidAutoRuntimeState
import io.motohub.android.androidauto.AndroidAutoSessionService
import io.motohub.android.androidauto.AndroidAutoDisplayMode
import io.motohub.android.androidauto.AndroidAutoDisplayModeStore
import io.motohub.android.data.MotorcyclePhotoStore
import io.motohub.android.feature.about.AboutScreen
import io.motohub.android.feature.about.MOTO_HUB_GITHUB_URL
import io.motohub.android.feature.garage.GarageScreen
import io.motohub.android.feature.garage.MotorcycleDetailsScreen
import io.motohub.android.feature.home.HubHomeScreen
import io.motohub.android.feature.home.HubViewModel
import io.motohub.android.feature.androidauto.AndroidAutoPreviewScreen
import io.motohub.android.feature.diagnostics.NetworkDiagnosticsScreen
import io.motohub.android.feature.diagnostics.NetworkDiagnosticsViewModel
import io.motohub.android.feature.diagnostics.ApplicationLogScreen
import io.motohub.android.feature.pairing.TBoxQrParser
import io.motohub.android.feature.pairing.TBoxQrScannerScreen
import io.motohub.android.session.ProjectionSessionService
import io.motohub.android.session.ProjectionEventLog
import io.motohub.android.session.PhoneDisplayDimmer
import io.motohub.android.session.PhoneDisplayDimPreferences
import io.motohub.android.session.SessionPhase
import io.motohub.android.ui.theme.MotoHubTheme
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private val viewModel: HubViewModel by viewModels()
    private val diagnosticsViewModel: NetworkDiagnosticsViewModel by viewModels()
    private val androidAutoLaunchPending = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProjectionEventLog.record("UI", "Main activity created.")
        enableEdgeToEdge()

        setContent {
            MotoHubTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val diagnosticsState by diagnosticsViewModel.uiState.collectAsStateWithLifecycle()
                val projectionEvents by ProjectionEventLog.events.collectAsStateWithLifecycle()
                val androidAutoState by AndroidAutoRuntime.state.collectAsStateWithLifecycle()
                val androidAutoActive = androidAutoState is AndroidAutoRuntimeState.Preparing ||
                    androidAutoState is AndroidAutoRuntimeState.ReceiverReady ||
                    androidAutoState is AndroidAutoRuntimeState.Streaming
                val androidAutoStreaming = androidAutoState is AndroidAutoRuntimeState.Streaming
                var showQrScanner by rememberSaveable { mutableStateOf(false) }
                var showNetworkDiagnostics by rememberSaveable { mutableStateOf(false) }
                var showApplicationLogs by rememberSaveable { mutableStateOf(false) }
                var showAbout by rememberSaveable { mutableStateOf(false) }
                var showAndroidAutoPreview by rememberSaveable { mutableStateOf(false) }
                var showGarage by rememberSaveable { mutableStateOf(false) }
                var editorProfileId by rememberSaveable { mutableStateOf<String?>(null) }
                var photoTargetProfileId by rememberSaveable { mutableStateOf<String?>(null) }
                var returnToGarageAfterQr by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(androidAutoStreaming) {
                    if (!androidAutoStreaming) showAndroidAutoPreview = false
                }
                val context = LocalContext.current
                val displayModeStore = remember(context) { AndroidAutoDisplayModeStore(context) }
                val motorcyclePhotoStore = remember(context) { MotorcyclePhotoStore(context) }
                val motorcycleId = state.session.motorcycle?.id
                var androidAutoDisplayMode by rememberSaveable {
                    mutableStateOf(AndroidAutoDisplayMode.LETTERBOX)
                }
                LaunchedEffect(motorcycleId) {
                    androidAutoDisplayMode = state.session.motorcycle
                        ?.let(displayModeStore::load)
                        ?: AndroidAutoDisplayMode.LETTERBOX
                }
                var motorcycleDetailsDisplayMode by rememberSaveable {
                    mutableStateOf(AndroidAutoDisplayMode.LETTERBOX)
                }
                var dimDisplayEnabled by rememberSaveable {
                    mutableStateOf(PhoneDisplayDimPreferences.isEnabled(context))
                }
                val projectionManager = context.getSystemService(
                    MediaProjectionManager::class.java
                )
                val motorcyclePhotoLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.PickVisualMedia()
                ) { uri ->
                    val profileId = photoTargetProfileId
                    photoTargetProfileId = null
                    val profile = state.motorcycles.firstOrNull { it.id == profileId }
                    if (uri == null || profile == null) return@rememberLauncherForActivityResult
                    motorcyclePhotoStore.copyFromUri(profile.id, uri)
                        .onSuccess { photoPath ->
                            if (viewModel.updateMotorcycle(profile.copy(photoPath = photoPath))) {
                                motorcyclePhotoStore.delete(profile.photoPath)
                            } else {
                                motorcyclePhotoStore.delete(photoPath)
                            }
                            ProjectionEventLog.record("GARAGE", "Photo updated for motorcycle ${profile.ssid}.")
                        }
                        .onFailure {
                            ProjectionEventLog.error("GARAGE", "Unable to store the selected motorcycle photo.", it)
                            Toast.makeText(context, "Unable to save the motorcycle photo", Toast.LENGTH_SHORT).show()
                        }
                }
                val projectionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    ProjectionEventLog.record(
                        "PERMISSION",
                        "Screen capture consent returned resultCode=${result.resultCode}, hasData=${result.data != null}."
                    )
                    if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                        ProjectionSessionService.start(context, result.resultCode, result.data!!)
                        viewModel.onProjectionRequested()
                    } else {
                        viewModel.onProjectionCancelled()
                    }
                }
                var projectionPermissionPending by rememberSaveable { mutableStateOf(false) }
                var androidAutoPermissionPending by rememberSaveable { mutableStateOf(false) }
                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    ProjectionEventLog.record("PERMISSION", "Notification permission result: granted=$granted.")
                    if (projectionPermissionPending) {
                        projectionPermissionPending = false
                        if (granted) {
                            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                        } else {
                            viewModel.onNotificationPermissionDenied()
                        }
                    } else if (androidAutoPermissionPending) {
                        androidAutoPermissionPending = false
                        if (granted) {
                            startAndroidAuto()
                        } else {
                            viewModel.onNotificationPermissionDenied()
                        }
                    }
                }
                val wifiPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { grants ->
                    ProjectionEventLog.record(
                        "PERMISSION",
                        "Wi-Fi permission results: ${grants.entries.joinToString { "${it.key.substringAfterLast('.')}=${it.value}" }}."
                    )
                    if (grants.values.all { it }) {
                        viewModel.connectAndDiscover()
                    } else {
                        viewModel.onNearbyWifiPermissionDenied()
                    }
                }
                val overlayPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) {
                    val granted = PhoneDisplayDimmer.canDim(context)
                    ProjectionEventLog.record("PERMISSION", "Display overlay permission result: granted=$granted.")
                    PhoneDisplayDimPreferences.setEnabled(context, granted)
                    dimDisplayEnabled = granted
                    if (granted && state.session.phase == SessionPhase.CAPTURING) {
                        ProjectionSessionService.dimDisplay(context)
                    }
                }

                val cameraPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    ProjectionEventLog.record("PERMISSION", "Camera permission result: granted=$granted.")
                    if (granted) {
                        showQrScanner = true
                    } else {
                        if (returnToGarageAfterQr) {
                            returnToGarageAfterQr = false
                            showGarage = true
                        }
                        viewModel.onCameraPermissionDenied()
                    }
                }
                val qrPhotoLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.PickVisualMedia()
                ) { uri ->
                    if (uri == null) {
                        ProjectionEventLog.debug("PAIRING", "QR photo picker closed without a selection.")
                        return@rememberLauncherForActivityResult
                    }
                    ProjectionEventLog.record("PAIRING", "QR photo selected; starting ML Kit decoding.")

                    val image = runCatching { InputImage.fromFilePath(context, uri) }
                        .getOrElse {
                            viewModel.onQrImportFailed("Unable to read the selected image.")
                            return@rememberLauncherForActivityResult
                        }
                    val options = BarcodeScannerOptions.Builder()
                        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                        .build()
                    val scanner = BarcodeScanning.getClient(options)
                    scanner.process(image)
                        .addOnSuccessListener { codes ->
                            ProjectionEventLog.debug("PAIRING", "ML Kit returned ${codes.size} barcode candidate(s).")
                            val rawValue = codes.firstOrNull { it.rawValue != null }?.rawValue
                            val payload = rawValue?.let { TBoxQrParser.parse(it).getOrNull() }
                            if (payload != null) {
                                viewModel.applyQrPairing(payload)
                            } else {
                                viewModel.onQrImportFailed(
                                    "The photo does not contain a valid EasyConn T-Box QR code."
                                )
                            }
                        }
                        .addOnFailureListener {
                            ProjectionEventLog.error("PAIRING", "ML Kit failed while scanning the selected image.", it)
                            viewModel.onQrImportFailed("Unable to scan the QR code in the photo.")
                        }
                        .addOnCompleteListener { scanner.close() }
                }

                if (showApplicationLogs) {
                    ApplicationLogScreen(
                        events = projectionEvents,
                        onCopy = {
                            val text = ProjectionEventLog.exportText()
                            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                                ClipData.newPlainText("MOTO-HUB diagnostics", text)
                            )
                            ProjectionEventLog.record("LOG", "Diagnostic log copied to the clipboard.")
                            Toast.makeText(context, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        onShare = {
                            ProjectionEventLog.record("LOG", "Diagnostic log share sheet opened.")
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_SUBJECT, "MOTO-HUB diagnostics")
                                        putExtra(Intent.EXTRA_TEXT, ProjectionEventLog.exportText())
                                    },
                                    "Share MOTO-HUB log"
                                )
                            )
                        },
                        onClear = ProjectionEventLog::clear,
                        onBack = {
                            ProjectionEventLog.record("UI", "Application log screen closed.")
                            showApplicationLogs = false
                        }
                    )
                } else if (showAbout) {
                    AboutScreen(
                        onOpenGithub = {
                            ProjectionEventLog.record("UI", "GitHub repository link opened.")
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(MOTO_HUB_GITHUB_URL))
                                )
                            }.onFailure {
                                ProjectionEventLog.error("UI", "Unable to open the GitHub repository.", it)
                                Toast.makeText(
                                    context,
                                    "Unable to open GitHub",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onBack = {
                            ProjectionEventLog.record("UI", "About screen closed.")
                            showAbout = false
                        }
                    )
                } else if (showAndroidAutoPreview && androidAutoStreaming) {
                    AndroidAutoPreviewScreen(onBack = {
                        ProjectionEventLog.record("UI", "Android Auto phone preview closed.")
                        showAndroidAutoPreview = false
                    })
                } else if (showGarage) {
                    GarageScreen(
                        profiles = state.motorcycles,
                        activeProfileId = state.session.motorcycle?.id,
                        onBack = { showGarage = false },
                        onAddMotorcycle = {
                            returnToGarageAfterQr = true
                            showGarage = false
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                                PackageManager.PERMISSION_GRANTED
                            ) {
                                showQrScanner = true
                            } else {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        },
                        onSelectMotorcycle = { profileId ->
                            viewModel.selectMotorcycle(profileId)
                            showGarage = false
                        },
                        onOpenDetails = { profileId ->
                            val profile = state.motorcycles.firstOrNull { it.id == profileId }
                            if (profile != null) {
                                motorcycleDetailsDisplayMode = displayModeStore.load(profile)
                                editorProfileId = profileId
                                showGarage = false
                            }
                        }
                    )
                } else if (editorProfileId != null) {
                    val profile = state.motorcycles.firstOrNull { it.id == editorProfileId }
                    if (profile == null) {
                        editorProfileId = null
                        showGarage = true
                    } else {
                        MotorcycleDetailsScreen(
                            profile = profile,
                            displayMode = motorcycleDetailsDisplayMode,
                            onBack = {
                                editorProfileId = null
                                showGarage = true
                            },
                            onSave = { updatedProfile -> viewModel.updateMotorcycle(updatedProfile) },
                            onDisplayModeChanged = { mode ->
                                displayModeStore.save(profile, mode)
                                motorcycleDetailsDisplayMode = mode
                                ProjectionEventLog.record(
                                    "ANDROID_AUTO",
                                    "TFT display mode changed for ${profile.ssid}: $mode."
                                )
                            },
                            onChoosePhoto = {
                                photoTargetProfileId = profile.id
                                motorcyclePhotoLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            onRemovePhoto = {
                                val oldPath = profile.photoPath
                                if (viewModel.updateMotorcycle(profile.copy(photoPath = null))) {
                                    motorcyclePhotoStore.delete(oldPath)
                                    ProjectionEventLog.record("GARAGE", "Photo removed for motorcycle ${profile.ssid}.")
                                }
                            },
                            onDelete = {
                                motorcyclePhotoStore.delete(profile.photoPath)
                                viewModel.deleteMotorcycle(profile.id)
                                editorProfileId = null
                                showGarage = true
                            }
                        )
                    }
                } else if (showNetworkDiagnostics) {
                    NetworkDiagnosticsScreen(
                        state = diagnosticsState,
                        projectionEvents = projectionEvents,
                        onRunTests = diagnosticsViewModel::runTests,
                        onBack = {
                            ProjectionEventLog.record("UI", "Network diagnostics screen closed.")
                            showNetworkDiagnostics = false
                        }
                    )
                } else if (showQrScanner) {
                    TBoxQrScannerScreen(
                        onPayload = { payload ->
                            viewModel.applyQrPairing(payload)
                            ProjectionEventLog.record("UI", "QR scanner closed after a valid code.")
                            showQrScanner = false
                            if (returnToGarageAfterQr) {
                                returnToGarageAfterQr = false
                                showGarage = true
                            }
                        },
                        onClose = {
                            ProjectionEventLog.record("UI", "QR scanner cancelled by the user.")
                            showQrScanner = false
                            if (returnToGarageAfterQr) {
                                returnToGarageAfterQr = false
                                showGarage = true
                            }
                        }
                    )
                } else {
                    HubHomeScreen(
                        state = state,
                        onOpenGarage = { showGarage = true },
                        onSsidChanged = viewModel::onSsidChanged,
                        onPasswordChanged = viewModel::onPasswordChanged,
                        onSaveMotorcycle = viewModel::saveMotorcycle,
                        onScanQr = {
                            ProjectionEventLog.record("UI", "User requested live QR scanning.")
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                                PackageManager.PERMISSION_GRANTED
                            ) {
                                showQrScanner = true
                            } else {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        },
                        onImportQrPhoto = {
                            ProjectionEventLog.record("UI", "User requested QR decoding from a photo.")
                            qrPhotoLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onConnectAndDiscover = {
                            val permissions = arrayOf(
                                Manifest.permission.NEARBY_WIFI_DEVICES,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.ACCESS_FINE_LOCATION
                            )
                            if (permissions.all { permission ->
                                    ContextCompat.checkSelfPermission(context, permission) ==
                                        PackageManager.PERMISSION_GRANTED
                                }
                            ) {
                                viewModel.connectAndDiscover()
                            } else {
                                wifiPermissionLauncher.launch(permissions)
                            }
                        },
                        onCancelConnection = viewModel::cancelConnection,
                        onOpenNetworkDiagnostics = {
                            ProjectionEventLog.record("UI", "Network diagnostics screen opened.")
                            showNetworkDiagnostics = true
                        },
                        onOpenApplicationLogs = {
                            ProjectionEventLog.record("UI", "Application log screen opened.")
                            showApplicationLogs = true
                        },
                        onOpenAbout = {
                            ProjectionEventLog.record("UI", "About screen opened.")
                            showAbout = true
                        },
                        onStartProjection = {
                            ProjectionEventLog.record("MIRROR", "User selected mirroring mode.")
                            val notificationGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) == PackageManager.PERMISSION_GRANTED
                            if (notificationGranted) {
                                projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                            } else {
                                projectionPermissionPending = true
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        androidAutoActive = androidAutoActive,
                        androidAutoStreaming = androidAutoStreaming,
                        androidAutoDisplayMode = androidAutoDisplayMode,
                        onAndroidAutoDisplayModeChanged = { mode ->
                            state.session.motorcycle?.let { profile ->
                                displayModeStore.save(profile, mode)
                                androidAutoDisplayMode = mode
                                ProjectionEventLog.record(
                                    "ANDROID_AUTO",
                                    "TFT display mode changed for ${profile.ssid}: $mode."
                                )
                            }
                        },
                        onStartAndroidAuto = {
                            val notificationGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) == PackageManager.PERMISSION_GRANTED
                            if (notificationGranted) {
                                startAndroidAuto()
                            } else {
                                androidAutoPermissionPending = true
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        onStopAndroidAuto = {
                            ProjectionEventLog.record("ANDROID_AUTO", "User requested Android Auto stop.")
                            AndroidAutoSessionService.stop(context)
                        },
                        onOpenAndroidAutoPreview = {
                            ProjectionEventLog.record("UI", "Android Auto phone preview opened.")
                            showAndroidAutoPreview = true
                        },
                        dimDisplayEnabled = dimDisplayEnabled,
                        onDimDisplayChanged = { enabled ->
                            ProjectionEventLog.record("DISPLAY", "User changed display dimmer preference to enabled=$enabled.")
                            if (!enabled) {
                                PhoneDisplayDimPreferences.setEnabled(context, false)
                                dimDisplayEnabled = false
                                if (state.session.phase == SessionPhase.CAPTURING) {
                                    ProjectionSessionService.restoreDisplay(context)
                                }
                            } else if (PhoneDisplayDimmer.canDim(context)) {
                                PhoneDisplayDimPreferences.setEnabled(context, true)
                                dimDisplayEnabled = true
                                if (state.session.phase == SessionPhase.CAPTURING) {
                                    ProjectionSessionService.dimDisplay(context)
                                }
                            } else {
                                overlayPermissionLauncher.launch(
                                    Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                )
                            }
                        },
                        onStopProjection = {
                            ProjectionEventLog.record("MIRROR", "User requested mirroring stop.")
                            ProjectionSessionService.stop(context)
                        }
                    )
                }
            }
        }
    }

    private fun startAndroidAuto() {
        if (!androidAutoLaunchPending.compareAndSet(false, true)) {
            ProjectionEventLog.warning("ANDROID_AUTO", "Start request ignored because another launch is pending.")
            return
        }
        ProjectionEventLog.record("ANDROID_AUTO", "User requested Android Auto startup.")
        AndroidAutoSessionService.start(this)
        lifecycleScope.launch {
            val state = withTimeoutOrNull(10_000L) {
                AndroidAutoRuntime.state.first {
                    it is AndroidAutoRuntimeState.ReceiverReady ||
                        it is AndroidAutoRuntimeState.Failed
                }
            }
            when (state) {
                AndroidAutoRuntimeState.ReceiverReady -> {
                    ProjectionEventLog.record("ANDROID_AUTO", "AAP receiver is ready; waiting before self-mode trigger.")
                    // Match the reference app: let the receiver and NSD service settle before
                    // asking Google Android Auto to open its loopback connection.
                    delay(ANDROID_AUTO_RECEIVER_SETTLE_MS)
                    if (AndroidAutoRuntime.state.value is AndroidAutoRuntimeState.ReceiverReady) {
                        AaSelfMode.trigger(
                            context = this@MainActivity,
                            log = { ProjectionEventLog.record("AAP", it) }
                        )
                    }
                }
                is AndroidAutoRuntimeState.Failed -> Unit
                else -> {
                    ProjectionEventLog.error("ANDROID_AUTO", "Timed out while preparing Android Auto.")
                    AndroidAutoSessionService.stop(this@MainActivity)
                }
            }
            androidAutoLaunchPending.set(false)
            ProjectionEventLog.debug("ANDROID_AUTO", "Launch coordinator released.")
        }
    }

    override fun onDestroy() {
        ProjectionEventLog.record("UI", "Main activity destroyed. changingConfigurations=$isChangingConfigurations")
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        ProjectionEventLog.debug("UI", "Main activity started.")
    }

    override fun onResume() {
        super.onResume()
        ProjectionEventLog.debug("UI", "Main activity resumed.")
    }

    override fun onPause() {
        ProjectionEventLog.debug("UI", "Main activity paused.")
        super.onPause()
    }

    override fun onStop() {
        ProjectionEventLog.debug("UI", "Main activity stopped.")
        super.onStop()
    }

    private companion object {
        const val ANDROID_AUTO_RECEIVER_SETTLE_MS = 900L
    }
}
