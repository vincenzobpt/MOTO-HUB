// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.diagnostics.report

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.motohub.android.BuildConfig
import io.motohub.android.androidauto.AndroidAutoDisplayModeStore
import io.motohub.android.data.MotorcycleProfileStore
import io.motohub.android.feature.controls.BluetoothStatus
import io.motohub.android.feature.settings.MotoHubSettings
import io.motohub.android.ipc.HandlebarState
import io.motohub.android.session.InstallationId
import io.motohub.android.ipc.IpcBridgeContract
import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.session.SentryIntegration
import io.motohub.android.tbox.ProfileOverride
import io.motohub.android.tbox.TBoxCapabilities
import io.motohub.android.tbox.TBoxCapabilityStore
import io.motohub.android.tbox.TBoxModelProfile
import io.motohub.android.tbox.TBoxScanPermissions
import io.motohub.android.tbox.TBoxWireLadder
import io.motohub.android.tbox.WifiDirectGate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Why a report went out; the server groups on it. */
enum class DiagnosticReportTrigger(val wireName: String) {
    STARTUP("startup"),
    CRASH("crash"),
    MANUAL("manual")
}

/** One report ready to upload: the metadata document and the combined log it describes. */
class DiagnosticReport(
    val reportId: String,
    val supportId: String,
    val deviceId: String,
    val metadata: JSONObject,
    val logText: String
)

/**
 * Gathers everything support keeps asking riders for, in one document: which bike (as precisely
 * as the dashboard ever told us), which phone, which versions of Android, Android Auto, CORE and
 * ADVANCED, and the complete ADVANCED + CORE log. Nothing here is collected specially for the
 * report - every field is already on the phone, in the stores the app uses to run.
 *
 * What is deliberately left out: the T-Box password (encrypted at rest, never read here), the
 * dash's real MAC/BSSID (the app's standing rule is never to record those), the raw ANDROID_ID
 * (only hashed, see [InstallationId]), and anything from the AI assistant's credentials.
 */
object DiagnosticReportBuilder {
    private const val GEARHEAD_PACKAGE = "com.google.android.projection.gearhead"
    private const val CORE_PACKAGE = "io.motohub.android"
    /** Generous: BluetoothStatus.query gives up on its own well inside this. */
    private const val RADIO_QUERY_TIMEOUT_MS = 3_000L

    /** Same vocabulary the Sentry integration next door uses, so the two can be joined up. */
    private val EDITION: String get() = if (BuildConfig.IS_PRO) "advanced" else "core"

    suspend fun build(context: Context, trigger: DiagnosticReportTrigger): DiagnosticReport {
        val appContext = context.applicationContext
        val profileStore = MotorcycleProfileStore(appContext)
        val profiles = runCatching { profileStore.loadAll() }.getOrDefault(emptyList())
        val active = runCatching { profileStore.load() }.getOrNull()
        val supportId = InstallationId.supportId(appContext, active?.id)
        val deviceId = InstallationId.deviceId(appContext)
        val reportId = UUID.randomUUID().toString()
        val companion = createDiagnosticsCompanion(appContext)
        val logText = companion.exportLog(appContext)
        val ladders = companion.wireLadders(appContext, profiles)
        val bluetooth = companion.handlebarBluetoothGrants(appContext)
        val handlebarStates = companion.handlebarStates(appContext)
        val radio = bluetoothRadio(appContext)

        val metadata = JSONObject().apply {
            // 2: every motorcycle now carries a "wireLadder" object. Bumped rather than added
            // silently so the collector can tell an old report's absent field from a new one's
            // empty search.
            // 3: "permissions", with the Bluetooth grant of each half. Same reason: a missing
            // field and a denied permission must not read the same, and the difference is the
            // whole diagnosis of a handlebar that never worked.
            // 4: "handlebar" - each half's configuration, plus the radio. The grant said a press
            // could arrive; nothing said what the half that decodes it would do with one, and a
            // rider's log cannot answer that either (support 0df154af: three protocol switches
            // and a full teaching wizard, with no trace of any of it in what he sent).
            // 5: "permissions" grows the Wi-Fi/location grant of each half, and the phone-wide
            //    location toggle. The Bluetooth grant answered "can a handlebar press arrive";
            //    this answers "can either half see the Wi-Fi air at all" - and a process without
            //    it is handed an EMPTY scan and an "<unknown ssid>" with no error anywhere, so
            //    from a log it is indistinguishable from a phone that genuinely sees nothing.
            //    Four investigations (fc17a4f7, 36a3fd37, 6e77dcf7, f27f3825) had to infer it
            //    from the shape of the code and none of them could state it.
            put("schema", 5)
            put("reportId", reportId)
            put("supportId", supportId)
            put("deviceId", deviceId)
            put("trigger", trigger.wireName)
            put("createdAt", isoNow())
            put("sentry", JSONObject().apply {
                putOpt("installationId", SentryIntegration.sdkInstallationId(appContext))
                put("environment", EDITION)
                put("release", "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}")
            })
            put("phone", phone())
            put("apps", apps(appContext))
            put("androidAuto", packageVersion(appContext, GEARHEAD_PACKAGE))
            put("motorcycle", active?.let { motorcycle(appContext, it, isActive = true, ladders = ladders) } ?: JSONObject.NULL)
            put("motorcycles", JSONArray().apply {
                profiles.forEach { put(motorcycle(appContext, it, isActive = it.id == active?.id, ladders = ladders)) }
            })
            put("permissions", permissions(appContext, bluetooth))
            put("handlebar", handlebar(handlebarStates, radio))
            put("settings", settings(appContext))
            put("log", JSONObject().apply {
                put("chars", logText.length)
                put("loggingEnabled", MotoHubSettings.loggingEnabled(appContext))
                put("verboseTBoxLogging", MotoHubSettings.verboseTBoxLogging(appContext))
            })
        }
        return DiagnosticReport(reportId, supportId, deviceId, metadata, logText)
    }

    /**
     * The runtime grants that decide whether a feature can work at all, per package.
     *
     * Only Bluetooth so far, and only because a whole class of "the handlebar does nothing"
     * report turns on it: the permission belongs to whichever app decodes the presses, and that
     * is not the app the rider configured. JSONObject.NULL rather than a dropped key for the half
     * that could not be asked - see HandlebarBluetoothGrants.
     */
    private fun permissions(context: Context, bluetooth: HandlebarBluetoothGrants) = JSONObject().apply {
        put("bluetoothConnect", JSONObject().apply {
            put("advanced", bluetooth.advanced ?: JSONObject.NULL)
            put("core", bluetooth.core ?: JSONObject.NULL)
        })
        // Read straight off PackageManager for BOTH halves, with no bridge call and no timeout:
        // checkPermission reports another package's runtime grant without any privilege of its
        // own. That is why this field, unlike the Bluetooth one above, can be filled in by
        // whichever edition happens to be building the report - and why it is never "could not be
        // asked" for a package that is actually there.
        put("wifiScan", JSONObject().apply {
            put("advanced", wifiScanGrant(context, IpcBridgeContract.ADVANCED_PACKAGE_NAME))
            put("core", wifiScanGrant(context, IpcBridgeContract.CORE_PACKAGE_NAME))
        })
        // Not a permission and not per package: the phone-wide toggle Android also consults for
        // scan results. It is the second of the two ways a scan comes back empty forever, and
        // telling it from the first is the whole reason this field exists.
        //
        // Three-valued, like everything else in this block: WifiDirectGate.isLocationEnabled
        // answers TRUE when the toggle cannot be read at all, which is right where it gates a
        // hint and wrong here - a report claiming "location is on" for a phone nobody could ask
        // is the absent-vs-false conflation this whole object exists to avoid.
        put("locationServices", WifiDirectGate.locationEnabledOrNull(context) ?: JSONObject.NULL)
    }

    /**
     * Whether [packageName] holds every permission in [TBoxScanPermissions], or JSONObject.NULL
     * when that package is not installed (or not visible to this one).
     *
     * Null rather than false for an absent package, on the same rule as the Bluetooth grant
     * beside it: checkPermission answers DENIED for a package that is not there, and a report
     * that let those two read the same would have a reader telling a rider to grant a permission
     * to an app they never installed.
     */
    private fun wifiScanGrant(context: Context, packageName: String): Any =
        if (runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess) {
            TBoxScanPermissions.heldBy(context, packageName)
        } else {
            JSONObject.NULL
        }

    /**
     * How each half's handlebar is configured, and whether the radio could deliver a press at all.
     *
     * The two halves are separate objects, never merged: an Android Auto session's presses are
     * decoded in CORE, the Ride Dashboard's in ADVANCED, and one summary covering both would be
     * wrong for whichever the rider was actually using. JSONObject.NULL for a half that could not
     * be asked, on the same rule as [permissions] - absent is not "nothing configured".
     *
     * The radio is a count, not a device list: whether anything is connected is the diagnostic
     * question, and the names of a rider's other Bluetooth devices are none of this document's
     * business.
     */
    private fun handlebar(states: HandlebarStates, radio: BluetoothStatus.Status?) = JSONObject().apply {
        put("advanced", states.advanced?.let(::handlebarState) ?: JSONObject.NULL)
        put("core", states.core?.let(::handlebarState) ?: JSONObject.NULL)
        put("bluetooth", radio?.let {
            JSONObject().apply {
                put("supported", it.supported)
                put("enabled", it.enabled)
                put("permitted", it.permitted)
                put("connectedAudioDevices", it.connectedNames.size)
            }
        } ?: JSONObject.NULL)
    }

    private fun handlebarState(state: HandlebarState) = JSONObject().apply {
        put("inputMode", state.inputMode)
        put("captureEnabled", state.captureEnabled)
        put("calibrated", state.calibrated)
        put("managedByCompanion", state.managedByCompanion)
        put("hidServiceEnabled", state.hidServiceEnabled)
    }

    /**
     * The live radio, or null if it did not answer in time.
     *
     * Asked once here rather than per half: the adapter is one radio and both processes read the
     * same one. Bounded because a profile proxy that never binds must not hold up a report the
     * rider asked for - BluetoothStatus.query has its own timeout, and this is the backstop for
     * the case where its callback never runs at all.
     */
    private suspend fun bluetoothRadio(context: Context): BluetoothStatus.Status? =
        withTimeoutOrNull(RADIO_QUERY_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                runCatching {
                    BluetoothStatus.query(context) { status ->
                        if (continuation.isActive) continuation.resume(status)
                    }
                }.onFailure { if (continuation.isActive) continuation.resume(null) }
            }
        }

    private fun phone() = JSONObject().apply {
        put("manufacturer", Build.MANUFACTURER)
        put("brand", Build.BRAND)
        put("model", Build.MODEL)
        put("device", Build.DEVICE)
        put("product", Build.PRODUCT)
        put("fingerprint", Build.FINGERPRINT)
        put("androidVersion", Build.VERSION.RELEASE)
        put("sdkInt", Build.VERSION.SDK_INT)
        put("securityPatch", Build.VERSION.SECURITY_PATCH)
        put("locale", Locale.getDefault().toLanguageTag())
        put("timeZone", TimeZone.getDefault().id)
    }

    /**
     * This app under its edition's name, plus whatever it knows of the other one.
     *
     * The keys keep their meaning across editions rather than collapsing into a single "this
     * app": a CORE-only rider's report has an "advanced" that is simply absent, and the collector
     * can tell that apart from a pairing whose ADVANCED half failed to report - which it could
     * not if both editions wrote themselves into the same field.
     */
    private fun apps(context: Context) = JSONObject().apply {
        val self = JSONObject().apply {
            put("versionName", BuildConfig.VERSION_NAME)
            put("versionCode", BuildConfig.VERSION_CODE)
            put("applicationId", BuildConfig.APPLICATION_ID)
            put("buildType", BuildConfig.BUILD_TYPE)
        }
        if (BuildConfig.IS_PRO) {
            put("advanced", self)
            put("core", packageVersion(context, CORE_PACKAGE))
        } else {
            put("core", self)
        }
        put("ipcContractVersion", IpcBridgeContract.CONTRACT_VERSION)
    }

    /** `{installed:false}` when the package is absent; both queried packages are declared in the manifest. */
    private fun packageVersion(context: Context, packageName: String): JSONObject = JSONObject().apply {
        put("packageName", packageName)
        val info = runCatching { context.packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        put("installed", info != null)
        if (info != null) {
            put("versionName", info.versionName ?: "")
            put("versionCode", info.longVersionCode)
        }
    }

    private fun motorcycle(
        context: Context,
        profile: MotorcycleProfile,
        isActive: Boolean,
        ladders: Map<String, String>
    ): JSONObject {
        val snapshot = runCatching { TBoxCapabilityStore(context).load(profile) }.getOrNull()
        val capabilities = snapshot?.capabilities
        val override = ProfileOverride.byKey(profile.profileOverrideKey)
        val resolved = TBoxModelProfile.resolve(profile.modelId, capabilities, override)
        val displayMode = runCatching { AndroidAutoDisplayModeStore(context).load(profile).name }.getOrNull()
        return JSONObject().apply {
            put("profileId", profile.id)
            put("active", isActive)
            put("ssid", profile.ssid)
            putOpt("displayName", profile.displayName)
            putOpt("modelId", profile.modelId)
            put("connectionMode", profile.connectionMode.name)
            put("profileOverride", override.key)
            put("resolvedProfile", resolved.key)
            put("resolvedProfileName", resolved.displayName)
            putOpt("androidAutoDisplayMode", displayMode)
            putOpt("fuelTankRangeKm", profile.fuelTankRangeKm)
            snapshot?.host?.let { host ->
                put("dashboard", JSONObject().apply {
                    put("port", host.port)
                    put("packageName", host.packageName)
                })
            }
            putOpt("discoveredAt", snapshot?.discoveredAtEpochMillis?.let(::iso))
            putOpt("capabilitiesObservedAt", snapshot?.capabilitiesObservedAtEpochMillis?.let(::iso))
            put("capabilities", capabilities?.let(::capabilities) ?: JSONObject.NULL)
            put("wireLadder", wireLadder(ladders[profile.id], resolved))
        }
    }

    /**
     * Where the wire search stands for this motorcycle, and which format it settled on.
     *
     * This is the field that makes the whole search worth more than one rider's afternoon.
     * A dashboard MOTO-HUB has never seen walks the ladder alone and, if it is lucky, lands on a
     * format that works - knowledge that then dies on that phone. Reported here, the same
     * fingerprint arriving from several riders with the same confirmed rung is exactly the
     * evidence a shipped profile is made of, without anyone having to own the hardware.
     *
     * Carries no identifiers of its own: the fingerprint is firmware metadata (HU family, SDK
     * flavor, channel, version), the same class of thing already in `capabilities`.
     */
    private fun wireLadder(coreProgress: String?, resolved: TBoxModelProfile): JSONObject {
        val progress = TBoxWireLadder.parseProgress(coreProgress)
        val rung = TBoxWireLadder.RUNGS.getOrElse(progress.rungIndex) { TBoxWireLadder.RUNGS.first() }
        return JSONObject().apply {
            // False for a dashboard a hand-written profile already claims: its wire came from
            // somebody measuring the hardware, and the ladder never ran.
            put("searching", resolved == TBoxModelProfile.GENERIC)
            // Whether these numbers are Core's or a stand-in, said out loud. This block used to be
            // read out of THIS process's copy of the ladder preferences, which nothing here ever
            // writes: every report from every rider claimed rung 0, TRYING, no fingerprint, while
            // Core's log in the same file said otherwise (field log 90438e1e, 2026-08-25). An
            // unreachable Core is now an unreachable Core, not a fabricated fresh start.
            put("knownToCore", coreProgress != null)
            put("rung", progress.rungIndex)
            put("rungCount", TBoxWireLadder.RUNGS.size)
            put("wire", rung.signature)
            put("state", progress.state.name)
            put("attemptsOnRung", progress.attemptsOnRung)
            putOpt("lastOutcome", progress.lastOutcome)
            putOpt("dashboardFingerprint", progress.fingerprint)
        }
    }

    /** The dashboard's own account of itself: the closest thing to a bike model the link carries. */
    private fun capabilities(value: TBoxCapabilities) = JSONObject().apply {
        putOpt("huName", value.huName)
        putOpt("carBrand", value.carBrand)
        putOpt("carModel", value.carModel)
        putOpt("flavor", value.flavor)
        putOpt("channel", value.channel)
        putOpt("packageName", value.packageName)
        putOpt("versionName", value.versionName)
        putOpt("versionCode", value.versionCode)
        putOpt("sdkVersion", value.sdkVersion)
        putOpt("pxcVersion", value.pxcVersion)
        putOpt("productType", value.productType)
        putOpt("screenType", value.screenType)
        putOpt("transportType", value.transportType)
        putOpt("supportFunction", value.supportFunction)
        putOpt("dpi", value.dpi)
        putOpt("screenTouch", value.screenTouch)
        putOpt("screenMirroring", value.screenMirroring)
        putOpt("hid", value.hid)
        putOpt("microphone", value.microphone)
    }

    private fun settings(context: Context) = JSONObject().apply {
        put("autostartEnabled", MotoHubSettings.autostartEnabled(context))
        put("autostartService", MotoHubSettings.autostartService(context).name)
        put("autoUpdateChecks", MotoHubSettings.autoUpdateChecks(context))
        put("keepScreenOn", MotoHubSettings.keepScreenOn(context))
        put("autoDiagnosticsUpload", DiagnosticReportSettings.autoUploadEnabled(context))
    }

    private fun isoNow() = iso(System.currentTimeMillis())

    private fun iso(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date(epochMillis))
}
