// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// MOTO-HUB glue (technique ported from headunit-revived AGPLv3 AapService.startSelfMode).
// Triggers Google Android Auto's loopback "self-mode": asks gearhead to project to 127.0.0.1:PORT
// with NO VPN. Best launched from a foreground Activity to satisfy Android's background-activity-
// launch restrictions (Android 12+/15).
package io.motohub.android.aa

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Parcel
import android.os.Parcelable
import android.os.SystemClock
import kotlinx.coroutines.delay

object AaSelfMode {
    private const val GEARHEAD_PKG = "com.google.android.projection.gearhead"

    /**
     * The entry point that has worked historically. Android Auto 17.x no longer exports it:
     * `startActivity` fails with "Permission Denial: … not exported", which no caller-side change
     * can work around. The remaining exported components are found at runtime instead of guessing
     * class names that change between releases (17.4 exposes WirelessStartupReceiver plus the
     * WirelessSetupShared* services).
     */
    private const val CLASSIC_ACTIVITY =
        "com.google.android.apps.auto.wireless.setup.service.impl.WirelessStartupActivity"
    private const val CLASSIC_RECEIVER =
        "com.google.android.apps.auto.wireless.setup.receiver.WirelessStartupReceiver"
    private const val RECEIVER_ACTION =
        "com.google.android.apps.auto.wireless.setup.receiver.wirelessstartup.START"

    /**
     * Android Auto's own head unit server, the one its Developer settings start from the overflow
     * menu. Starting it turns Android Auto into the listener on
     * [AaReceiver.HEAD_UNIT_SERVER_PORT], which [AaReceiver] then dials — the one path still open
     * on releases that removed self-mode. Historically a hidden service, so this may well be
     * refused; it costs one intent to find out, and the log says which it was.
     */
    private const val HEAD_UNIT_SERVER_SERVICE =
        "com.google.android.projection.gearhead.companion.DeveloperHeadUnitNetworkService"

    private val REQUIRED_KEYWORDS = listOf("wireless")
    private val ENTRY_KEYWORDS = listOf("startup", "start", "projection", "setup")
    /** Matches the head unit server family, whose names carry no "wireless" at all. */
    private val HEAD_UNIT_KEYWORDS = listOf("headunit", "head_unit")

    /** How long one entry point gets to produce an inbound AAP connection before the next is tried. */
    private const val ATTEMPT_WAIT_MS = 4_000L
    private const val POLL_INTERVAL_MS = 200L

    /** The pairing sheet waits on a person reading a dialog, not on a machine answering. */
    private const val PAIRING_WAIT_MS = 45_000L

    /** How long the sheet gets to come to the front before its absence means anything. */
    private const val SHEET_SETTLE_MS = 1_500L

    /**
     * Android Auto dials the head unit's own address, and here the head unit is this phone. The
     * loopback address is what self-mode always used, so it is known to be reachable from
     * Gearhead's projection client.
     */
    private const val PROJECTION_HOST = "127.0.0.1"

    /**
     * Asks Android Auto to project here, trying every entry point the installed build exposes.
     *
     * [isConnected] is what makes this reliable: `sendBroadcast` and `startService` only report
     * that an intent was dispatched, never that Gearhead acted on it, so each attempt is followed
     * by a short wait for the local AAP socket to actually be opened. Without that check the
     * sequence stopped at the first component that merely accepted the intent.
     */
    suspend fun trigger(
        context: Context,
        port: Int = AaReceiver.PORT,
        isConnected: () -> Boolean = { AaReceiver.hasAndroidAutoConnectedSinceStart() },
        onProgress: (String) -> Unit = { io.motohub.android.androidauto.AndroidAutoRuntime.publishStartupDetail(it) },
        log: (String) -> Unit
    ) {
        val version = gearheadVersion(context)
        lastGearheadVersion = version
        log("[AA] Android Auto app: ${version ?: "not installed"}")
        if (io.motohub.android.androidauto.AndroidAutoSelfModeHelp.isKnownBrokenVersion(version)) {
            // Still attempted below: Google could restore the entry points, and a version string
            // is not a good enough reason to refuse outright. The rider just learns immediately
            // why the next few seconds are likely to be wasted.
            log(
                "[AA] Android Auto $version is a release known to have removed wireless " +
                    "self-mode projection (17.2 works, 17.4 does not). Trying anyway."
            )
            onProgress("Android Auto $version may not support wireless projection…")
        }
        val extras = SelfModeExtras(context, port)

        // The QR-pairing family first: on 17.4 it is the only one still exported and enabled, and
        // for a bike already paired it needs nothing from the rider at all. See AaWirelessPairing.
        val car = resolveCar(context, port, log)
        if (car != null && AaPairingStore.isPaired(context, car.ssid)) {
            onProgress("Asking Android Auto to project…")
            if (AaWirelessPairing.requestProjection(context, car.bluetoothAddress, log = log)) {
                if (awaitConnection(isConnected)) {
                    log("[AA] Android Auto connected after the stored-car projection request.")
                    onProgress("Android Auto is starting up…")
                    return
                }
                log("[AA] stored-car request produced no connection; falling back to the older entry points.")
            }
        }

        val discovered = discoverStartupComponents(context)
        if (discovered.isEmpty()) {
            log("[AA] Android Auto exports no wireless-startup component on this device.")
        } else {
            log("[AA] Exported wireless-startup candidates: ${discovered.joinToString { it.describe() }}")
        }

        val headUnitServers = discoverHeadUnitServerComponents(context)
        if (headUnitServers.isEmpty()) {
            log(
                "[AA] Android Auto exports no head unit server component; it can only be started " +
                    "by hand from the three-dot menu at the top right of Android Auto's settings " +
                    "(visible once developer options are unlocked), not from Developer settings."
            )
        } else {
            log("[AA] Head unit server candidates: ${headUnitServers.joinToString { it.describe() }}")
        }

        // Activity first (the historical, most reliable shape), then whatever is still exported,
        // the legacy receiver in case discovery missed it, and finally the head unit server -
        // which does not project by itself but makes Android Auto listen for [AaReceiver].
        val attempts = buildList {
            add(StartupComponent(ComponentKind.ACTIVITY, CLASSIC_ACTIVITY))
            addAll(discovered)
            if (discovered.none { it.className == CLASSIC_RECEIVER }) {
                add(StartupComponent(ComponentKind.RECEIVER, CLASSIC_RECEIVER))
            }
            addAll(headUnitServers)
            if (headUnitServers.none { it.className == HEAD_UNIT_SERVER_SERVICE }) {
                add(StartupComponent(ComponentKind.SERVICE, HEAD_UNIT_SERVER_SERVICE))
            }
        }

        // Whether Android Auto ever ACCEPTED one of these intents, which is the whole diagnosis.
        // A component that answers "Permission Denial: not exported" has been closed by the
        // release; a component that takes the intent and then does nothing is Android Auto
        // deciding not to project, and the reason for that is almost always the one prerequisite
        // this app cannot read - "Add new cars to Android Auto" being off. Field log 2026-07-31
        // (OnePlus CPH2653) on Android Auto 17.2.662634, the exact build this project calls
        // verified working: the activity was refused, and a broadcast plus two services were all
        // accepted and stayed silent. The message that followed blamed 17.4.
        var accepted = false
        attempts.forEachIndexed { index, attempt ->
            if (isConnected()) return
            // The rider sees this in the session card and the preview screen: several seconds of
            // apparent inactivity is otherwise indistinguishable from a hang.
            onProgress("Asking Android Auto to start (${index + 1}/${attempts.size})…")
            val dispatched = when (attempt.kind) {
                ComponentKind.ACTIVITY -> startActivityComponent(context, attempt.className, extras, log)
                ComponentKind.RECEIVER -> sendReceiverBroadcast(context, attempt.className, extras, log)
                ComponentKind.SERVICE -> startServiceComponent(context, attempt.className, extras, log)
            }
            if (!dispatched) return@forEachIndexed
            accepted = true
            onProgress("Waiting for Android Auto to answer (${index + 1}/${attempts.size})…")
            if (awaitConnection(isConnected)) {
                log("[AA] Android Auto connected after ${attempt.describe()}.")
                onProgress("Android Auto is starting up…")
                return
            }
            log("[AA] no connection ${ATTEMPT_WAIT_MS}ms after ${attempt.describe()}; trying the next entry point.")
        }

        if (isConnected()) return

        // A pairing that was offered but never recorded is not proof it failed: the rider may have
        // tapped Continue after PAIRING_WAIT_MS ran out, in which case Android Auto has the bike
        // stored and only needs asking. Retried here rather than up front so a phone whose older
        // entry points still work never pays for it.
        if (car != null && !AaPairingStore.isPaired(context, car.ssid) &&
            AaPairingStore.wasPairingOffered(context, car.ssid)
        ) {
            onProgress("Asking Android Auto to project…")
            if (AaWirelessPairing.requestProjection(context, car.bluetoothAddress, log = log) &&
                awaitConnection(isConnected)
            ) {
                AaPairingStore.remember(context, car.ssid, car.bluetoothAddress)
                log("[AA] a previous pairing had gone through after all; this bike is stored now.")
                onProgress("Android Auto is starting up…")
                return
            }
        }

        // Last, and the only thing left on 17.4: Android Auto's own pairing sheet. It costs the
        // rider one "Continue", once per bike, and it is offered after the silent paths because a
        // phone where those still work should never see a dialog at all.
        // Once per bike per Android Auto release, never on a loop: the sheet is Google's, and when
        // its DEEP_LINK_ENABLED flag is off for this device it opens on "Can't connect with Wi-Fi"
        // instead. Measured on 17.2.662634, 2026-07-31: flag off, sheet showed the error. A rider
        // whose phone answers that way must not be shown it again on every single start.
        //
        // Foreground only, and that is not a nicety: a background Activity launch is dropped by
        // the system silently, with no exception to catch, so from a service this would spend
        // PAIRING_WAIT_MS waiting for a sheet nobody was ever shown - delaying the message that
        // actually helps. MainActivity passes itself; the service-driven paths pass a Context that
        // cannot start an Activity, and skip straight to the diagnosis.
        if (car != null &&
            context is android.app.Activity &&
            AaWirelessPairing.isPairingAvailable(context) &&
            AaPairingStore.shouldOfferPairing(context, car.ssid, version)
        ) {
            onProgress("Confirm the bike in Android Auto…")
            AaPairingStore.rememberOffered(context, car.ssid, version)
            // Deliberately NOT counted as an entry point accepting the request: `accepted` decides
            // which remedy the rider is finally shown, and Google's sheet accepts the intent even
            // when it goes on to refuse the pairing outright. Letting it set the flag would send a
            // rider whose entry points were all refused to the "Add new cars" advice, which cannot
            // help them - the head unit server is their only remaining path.
            if (AaWirelessPairing.launchPairing(context, car, log)) {
                // Let the sheet actually take focus before treating focus as "the sheet closed".
                delay(SHEET_SETTLE_MS)
                if (awaitConnection(isConnected, PAIRING_WAIT_MS, abort = { sheetClosed(context) })) {
                    AaPairingStore.remember(context, car.ssid, car.bluetoothAddress)
                    log("[AA] Android Auto connected after pairing; this bike is stored now.")
                    onProgress("Android Auto is starting up…")
                    return
                }
                log("[AA] pairing sheet produced no connection within ${PAIRING_WAIT_MS}ms.")
            }
        }

        anyEntryPointAccepted = accepted
        // An acceptance means "a trust decision was made about us" only while the release still
        // has self-mode. Past that, what accepts is a component that needs pairing data it was
        // never given, so the acceptance is noise and the head unit server is the only way in.
        // See AndroidAutoSelfModeHelp.ACCEPTED_BUT_SILENT_ON_CLOSED_RELEASE_MESSAGE.
        val selfModeClosed =
            io.motohub.android.androidauto.AndroidAutoSelfModeHelp.isKnownBrokenVersion(version)
        val diagnosis = when {
            accepted && !selfModeClosed ->
                "Android Auto ${version ?: "(version unknown)"} ACCEPTED at least one of them and " +
                    "then did nothing, which is what a refusal looks like from here: it projects " +
                    "only to a head unit it is willing to accept, and a sideloaded one counts as " +
                    "unknown until \"Add new cars to Android Auto\" is enabled in its own " +
                    "Developer settings."
            accepted ->
                "Android Auto ${version ?: "(version unknown)"} ACCEPTED at least one of them and " +
                    "then did nothing - but on a release that has closed self-mode that says " +
                    "nothing about \"Add new cars\": the components still exported here do " +
                    "nothing without pairing data of their own."
            else ->
                "Android Auto ${version ?: "(version unknown)"} refused every one of them as not " +
                    "exported - the release has closed self-mode off (same wall headunit-revived " +
                    "hit in its issue #698)."
        }
        // The only two details that are an instruction rather than narration, which is why they
        // are named next to the long-form guidance they abbreviate: the home screen tells them
        // apart by identity and gives them a card instead of a caption.
        onProgress(
            if (accepted && !selfModeClosed) {
                io.motohub.android.androidauto.AndroidAutoSelfModeHelp.ADD_NEW_CARS_STEP.flat
            } else {
                io.motohub.android.androidauto.AndroidAutoSelfModeHelp.HEAD_UNIT_SERVER_STEP.flat
            }
        )
        log(
            "[AA] Self-mode could not be triggered: none of ${attempts.size} entry points produced " +
                "a connection. $diagnosis The receiver keeps polling Android Auto's own head unit " +
                "server on :${AaReceiver.HEAD_UNIT_SERVER_PORT}, so starting it from Android Auto's " +
                "Developer settings connects either way."
        )
    }

    /**
     * Whether the last [trigger] round had an entry point accept its intent, which decides which
     * remedy the rider is shown. Process-global because the failure surfaces later and elsewhere -
     * the video-ready timeout in the session service, long after this coroutine has returned.
     */
    @Volatile
    var anyEntryPointAccepted: Boolean = false
        private set

    /**
     * The Android Auto version the last [trigger] round ran against, kept for the same reason and
     * with the same lifetime as [anyEntryPointAccepted]: the remedy is chosen where the failure
     * surfaces, and re-reading it from PackageManager there could disagree with what was actually
     * tried.
     */
    @Volatile
    var lastGearheadVersion: String? = null
        private set

    /** Polls the receiver rather than trusting the dispatch result. See [trigger]. */
    private suspend fun awaitConnection(
        isConnected: () -> Boolean,
        waitMs: Long = ATTEMPT_WAIT_MS,
        abort: () -> Boolean = { false }
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + waitMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isConnected()) return true
            if (abort()) return isConnected()
            delay(POLL_INTERVAL_MS)
        }
        return isConnected()
    }

    /**
     * Whether Android Auto's sheet has gone away again - dismissed, or closed on its own error.
     *
     * Without this a rider who taps Exit waits out the full [PAIRING_WAIT_MS] staring at "Confirm
     * the bike in Android Auto", with the sheet no longer on screen. Focus coming back to MOTO-HUB
     * is the one signal available from here; a brief settling delay keeps the check from firing
     * before the sheet has taken focus in the first place.
     */
    private fun sheetClosed(context: Context): Boolean {
        val activity = context as? android.app.Activity ?: return false
        return runCatching { activity.hasWindowFocus() }.getOrDefault(false)
    }

    private fun startActivityComponent(
        context: Context,
        className: String,
        extras: SelfModeExtras,
        log: (String) -> Unit
    ): Boolean = try {
        val intent = Intent().apply {
            setClassName(GEARHEAD_PKG, className)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            extras.applyActivityExtras(this)
        }
        log("[AA] launching Android Auto $className → 127.0.0.1:${extras.port}")
        context.startActivity(intent)
        true
    } catch (failure: Exception) {
        log("[AA] activity ${className.substringAfterLast('.')} refused: ${failure.message}")
        false
    }

    private fun sendReceiverBroadcast(
        context: Context,
        className: String,
        extras: SelfModeExtras,
        log: (String) -> Unit
    ): Boolean = try {
        val intent = Intent().apply {
            setClassName(GEARHEAD_PKG, className)
            action = RECEIVER_ACTION
            extras.applyReceiverExtras(this)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }
        context.sendBroadcast(intent)
        log("[AA] broadcast sent to ${className.substringAfterLast('.')}")
        true
    } catch (failure: Exception) {
        log("[AA] broadcast to ${className.substringAfterLast('.')} refused: ${failure.message}")
        false
    }

    /**
     * startService returns the resolved component, or null when nothing matched — the one dispatch
     * result in this file that is actually informative, so a missing service is not counted as an
     * attempt worth waiting on.
     */
    private fun startServiceComponent(
        context: Context,
        className: String,
        extras: SelfModeExtras,
        log: (String) -> Unit
    ): Boolean = try {
        val intent = Intent().apply {
            setClassName(GEARHEAD_PKG, className)
            action = RECEIVER_ACTION
            extras.applyReceiverExtras(this)
        }
        val resolved = context.startService(intent)
        if (resolved == null) {
            log("[AA] service ${className.substringAfterLast('.')} did not resolve")
            false
        } else {
            log("[AA] service ${className.substringAfterLast('.')} started")
            true
        }
    } catch (failure: Exception) {
        log("[AA] service ${className.substringAfterLast('.')} refused: ${failure.message}")
        false
    }

    /**
     * Describes this phone as the wireless head unit of the bike it is currently connected to.
     *
     * Needs a live T-Box session, because the pairing payload has to carry the dashboard's own
     * access point: that is the network Android Auto associates the stored car with. Without a
     * session there is nothing truthful to put in it, so the QR path is skipped rather than fed
     * a guess.
     */
    private fun resolveCar(context: Context, port: Int, log: (String) -> Unit): AaWirelessPairing.Car? {
        val motorcycle = io.motohub.android.tbox.TBoxSessionRegistry.current()?.motorcycle
        if (motorcycle == null || motorcycle.ssid.isBlank() || motorcycle.password.isBlank()) {
            log("[AA] no dashboard credentials available, skipping Android Auto's pairing path.")
            return null
        }
        return AaWirelessPairing.Car(
            ssid = motorcycle.ssid,
            bssid = dashboardBssid(context, motorcycle.ssid)
                ?: AaWirelessPairing.syntheticBssid(motorcycle.ssid),
            passkey = motorcycle.password,
            hostAddress = PROJECTION_HOST,
            port = port,
            bluetoothAddress = AaWirelessPairing.syntheticBluetoothAddress(motorcycle.ssid)
        )
    }

    /**
     * The dashboard access point's hardware address, and only ever that one.
     *
     * Android reports the BSSID of the *primary* Wi-Fi connection, which on this phone is usually
     * some other network entirely - MOTO-HUB holds the dashboard through a NetworkSpecifier, off
     * to the side. Writing a home router's BSSID next to the dashboard's SSID would describe a
     * network that does not exist, so the SSID has to match before the value is believed. It is
     * also redacted to 02:00:00:00:00:00 without location permission. Either way [resolveCar]
     * falls back to a synthetic address: Android Auto only checks the shape of this field.
     */
    private fun dashboardBssid(context: Context, dashboardSsid: String): String? = runCatching {
        val manager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        @Suppress("DEPRECATION")
        val info = manager.connectionInfo
        @Suppress("DEPRECATION")
        val connectedSsid = info.ssid.orEmpty().trim('"')
        if (connectedSsid != dashboardSsid) return@runCatching null
        @Suppress("DEPRECATION")
        info.bssid?.takeIf { it.matches(BSSID_SHAPE) && it != REDACTED_BSSID }
    }.getOrNull()

    private val BSSID_SHAPE = Regex("([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}")
    private const val REDACTED_BSSID = "02:00:00:00:00:00"

    /**
     * Which bikes Android Auto has already been told to trust, keyed by dashboard SSID.
     *
     * Only a record of a completed pairing - the credentials themselves stay where they already
     * live. It exists so the rider is asked once per bike and never again: on every later ride
     * the stored-car request goes out silently.
     */
    private object AaPairingStore {
        private const val PREFS = "aa_wireless_pairing"
        private const val OFFERED_PREFIX = "offered:"

        fun isPaired(context: Context, ssid: String): Boolean =
            prefs(context).contains(ssid)

        fun remember(context: Context, ssid: String, bluetoothAddress: String) {
            prefs(context).edit().putString(ssid, bluetoothAddress).apply()
        }

        /**
         * Keyed by Android Auto's version as well as the bike: an update is the one event that can
         * turn the flag on, and it is the only thing worth re-asking a rider for.
         */
        fun shouldOfferPairing(context: Context, ssid: String, version: String?): Boolean =
            prefs(context).getString(OFFERED_PREFIX + ssid, null) != version.orEmpty()

        /** Whether this bike has ever been put in front of the rider, on any release. */
        fun wasPairingOffered(context: Context, ssid: String): Boolean =
            prefs(context).contains(OFFERED_PREFIX + ssid)

        fun rememberOffered(context: Context, ssid: String, version: String?) {
            prefs(context).edit().putString(OFFERED_PREFIX + ssid, version.orEmpty()).apply()
        }

        private fun prefs(context: Context) =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private enum class ComponentKind { ACTIVITY, RECEIVER, SERVICE }

    private data class StartupComponent(val kind: ComponentKind, val className: String) {
        fun describe(): String = "${kind.name.lowercase()}:${className.substringAfterLast('.')}"
    }

    /**
     * Exported gearhead components that look like a wireless-projection entry point, activities
     * first (the historical shape). Exported-only: a non-exported component cannot be started
     * from another uid no matter how it is invoked, so listing it would only produce noise.
     */
    private fun discoverStartupComponents(context: Context): List<StartupComponent> = runCatching {
        val flags = PackageManager.GET_ACTIVITIES or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_SERVICES or
            PackageManager.MATCH_DISABLED_COMPONENTS
        val info = context.packageManager.getPackageInfo(GEARHEAD_PKG, flags)
        // Exported AND enabled: MATCH_DISABLED_COMPONENTS is needed to see the whole manifest,
        // but a disabled component silently swallows anything sent to it — Android Auto 17.4
        // ships WirelessStartupReceiver disabled, which is exactly why the broadcast that used
        // to work now vanishes without an error.
        val activities = info.activities.orEmpty()
            .filter { it.exported && it.isEnabled() && looksLikeStartup(it.name) }
            .map { StartupComponent(ComponentKind.ACTIVITY, it.name) }
        val receivers = info.receivers.orEmpty()
            .filter { it.exported && it.isEnabled() && looksLikeStartup(it.name) }
            .map { StartupComponent(ComponentKind.RECEIVER, it.name) }
        val services = info.services.orEmpty()
            .filter { it.exported && it.isEnabled() && looksLikeStartup(it.name) }
            .map { StartupComponent(ComponentKind.SERVICE, it.name) }
        (activities + receivers + services).filterNot { it.className == CLASSIC_ACTIVITY }
    }.getOrDefault(emptyList())

    /**
     * The manifest `enabled` flag combined with any runtime override the system holds for the
     * component, so a component Google turned off after install is not treated as usable.
     */
    private fun android.content.pm.ComponentInfo.isEnabled(): Boolean = enabled

    /**
     * Exported, enabled components whose name marks them as the head unit server. Kept separate
     * from [discoverStartupComponents] because these do not advertise "wireless" anywhere.
     */
    private fun discoverHeadUnitServerComponents(context: Context): List<StartupComponent> = runCatching {
        val info = context.packageManager.getPackageInfo(
            GEARHEAD_PKG,
            PackageManager.GET_SERVICES or PackageManager.MATCH_DISABLED_COMPONENTS
        )
        info.services.orEmpty()
            .filter { it.exported && it.enabled && looksLikeHeadUnitServer(it.name) }
            .map { StartupComponent(ComponentKind.SERVICE, it.name) }
    }.getOrDefault(emptyList())

    private fun looksLikeHeadUnitServer(className: String?): Boolean {
        val name = className?.lowercase()?.replace("_", "") ?: return false
        return HEAD_UNIT_KEYWORDS.any { name.contains(it.replace("_", "")) }
    }

    private fun looksLikeStartup(className: String?): Boolean {
        val name = className?.lowercase() ?: return false
        return REQUIRED_KEYWORDS.all { name.contains(it) } && ENTRY_KEYWORDS.any { name.contains(it) }
    }

    private fun gearheadVersion(context: Context): String? = runCatching {
        context.packageManager.getPackageInfo(GEARHEAD_PKG, 0).versionName
    }.getOrNull()

    /** The reflective network/WifiInfo payload every entry point expects, built once per trigger. */
    private class SelfModeExtras(context: Context, val port: Int) {
        private val network: Parcelable? = runCatching {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            (manager.activeNetwork as? Parcelable) ?: createFakeNetwork(0)
        }.getOrNull()
        private val wifiInfo: Parcelable? = createFakeWifiInfo()

        fun applyActivityExtras(intent: Intent) {
            intent.putExtra("PARAM_HOST_ADDRESS", "127.0.0.1")
            intent.putExtra("PARAM_SERVICE_PORT", port)
            network?.let { intent.putExtra("PARAM_SERVICE_WIFI_NETWORK", it) }
            wifiInfo?.let { intent.putExtra("wifi_info", it) }
        }

        fun applyReceiverExtras(intent: Intent) {
            intent.putExtra("ip_address", "127.0.0.1")
            intent.putExtra("projection_port", port)
            network?.let { intent.putExtra("PARAM_SERVICE_WIFI_NETWORK", it) }
            wifiInfo?.let { intent.putExtra("wifi_info", it) }
        }
    }

    /** Reflectively build an android.net.Network from a raw netId (HUR technique). */
    private fun createFakeNetwork(netId: Int): Parcelable? {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeInt(netId)
            parcel.setDataPosition(0)
            val creator = Class.forName("android.net.Network").getField("CREATOR").get(null) as Parcelable.Creator<*>
            creator.createFromParcel(parcel) as Parcelable
        } catch (e: Exception) {
            null
        } finally {
            parcel.recycle()
        }
    }

    /** Reflectively build a WifiInfo with a fake SSID for the self-mode intent (HUR technique). */
    private fun createFakeWifiInfo(): Parcelable? {
        return try {
            val wifiInfoClass = Class.forName("android.net.wifi.WifiInfo")
            val wifiInfo = wifiInfoClass.getDeclaredConstructor().apply { isAccessible = true }
                .newInstance() as Parcelable
            try {
                wifiInfoClass.getDeclaredField("mSSID").apply { isAccessible = true }
                    .set(wifiInfo, "\"Headunit-Fake-Wifi\"")
            } catch (_: Exception) {}
            wifiInfo
        } catch (e: Exception) {
            null
        }
    }
}
