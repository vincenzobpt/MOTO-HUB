// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A hotspot the app itself brings up, with credentials the app can read.
 *
 * This is the piece that makes [EcBtpNetProtocol]'s build-net exchange possible. Android has never
 * let a third-party app create a hotspot with *dictated* credentials - which is why
 * [io.motohub.android.session.TBoxConnectionMode.PHONE_HOTSPOT] asks the rider to type what their
 * dash prints - but `startLocalOnlyHotspot` is the other half of that rule: the framework picks
 * the SSID and passphrase and hands them to the app that asked. Useless when the dash dictates,
 * exactly right when the dash will accept whatever it is told over Bluetooth.
 *
 * Two behaviours are worth knowing before reading a field log:
 *
 *  * The hotspot lives as long as the reservation is held. Dropping it - including by the process
 *    dying - takes the network down and the dash with it, so [close] is the session's job, not a
 *    detail of the connect.
 *  * Starting it takes the Wi-Fi station radio down on most phones. That is not a fault to work
 *    around; it is what a dash that joins the phone needs, and it is why the connect gate must
 *    not ask "is Wi-Fi on" on this path.
 */
@SuppressLint("MissingPermission")
internal class PhoneHostedHotspot(context: Context, private val log: (String) -> Unit) : PhoneHostedNetwork {

    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private val closed = AtomicBoolean(false)

    @Volatile
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    /**
     * Brings the hotspot up and reads back what it was given.
     *
     * Fails rather than throws for every outcome a rider can act on, because each one has its own
     * fix: tethering can be blocked by policy, another app can already own the SoftAP, and some
     * phones refuse while a hotspot they own is already running.
     */
    override suspend fun start(timeoutMillis: Long, frequencyMhz: Int): Result<PhoneHostedNetwork.Credentials> {
        val manager = wifiManager
            ?: return Result.failure(IllegalStateException("This phone exposes no Wi-Fi service."))
        if (closed.get()) return Result.failure(IllegalStateException("The hotspot was already released."))

        val started = CompletableDeferred<Result<PhoneHostedNetwork.Credentials>>()
        val callback = object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(granted: WifiManager.LocalOnlyHotspotReservation) {
                reservation = granted
                if (closed.get()) {
                    runCatching { granted.close() }
                    started.complete(Result.failure(IllegalStateException("The hotspot was released while starting.")))
                    return
                }
                val credentials = readCredentials(granted)
                started.complete(
                    credentials?.let { Result.success(it) } ?: Result.failure(
                        IllegalStateException(
                            "Android started the hotspot but would not say what its name and " +
                                "password are, so the dashboard cannot be told how to join it."
                        )
                    )
                )
            }

            override fun onStopped() {
                // Arrives both for an orderly close and for a hotspot the platform took away
                // (a rider turning tethering on by hand does exactly that). The session finds out
                // through the link dropping; this line is what explains why.
                log("The phone-hosted hotspot stopped.")
            }

            override fun onFailed(reason: Int) {
                started.complete(Result.failure(IllegalStateException(describeFailure(reason))))
            }
        }

        // Recorded before the call, not after a failure: Android has refused radio work to
        // backgrounded callers before on this codebase (see TBoxNetworkConnector's specifier
        // race), and a hotspot request that fails from a process nobody can see is a different
        // problem from one that fails with the app on screen. The failure itself cannot tell
        // them apart, so the log has to.
        log("Requesting the hotspot at process importance ${processImportance()} (smaller is closer to the rider).")
        val requested = runCatching {
            manager.startLocalOnlyHotspot(callback, Handler(Looper.getMainLooper()))
        }
        requested.exceptionOrNull()?.let { failure ->
            return Result.failure(
                when (failure) {
                    is SecurityException -> IllegalStateException(
                        "MOTO-HUB is not allowed to create a hotspot on this phone. Allow its " +
                            "location permission, then connect again."
                    )
                    // The documented signal for "this app already has one" and for a phone whose
                    // tethering is in a state the framework will not add to.
                    is IllegalStateException -> IllegalStateException(
                        "Android refused to start a hotspot: ${failure.message ?: "no reason given"}. " +
                            "Turn the phone's own hotspot off, then connect again."
                    )
                    else -> failure
                }
            )
        }
        log("Asked Android for a phone-hosted hotspot; waiting for it to come up.")

        return withTimeoutOrNull(timeoutMillis) { started.await() }
            ?: Result.failure(
                IllegalStateException(
                    "The phone-hosted hotspot did not come up within ${timeoutMillis / 1000}s."
                )
            )
    }

    /** Android's own scale for how close this process is to the rider; smaller is closer. */
    private fun processImportance(): Int {
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        return state.importance
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val active = reservation
        reservation = null
        if (active != null) log("Releasing the phone-hosted hotspot.")
        runCatching { active?.close() }
    }

    private fun readCredentials(
        reservation: WifiManager.LocalOnlyHotspotReservation
    ): PhoneHostedNetwork.Credentials? {
        val configuration = runCatching { reservation.softApConfiguration }.getOrNull() ?: return null
        val ssid = readSsid(configuration)?.takeIf { it.isNotBlank() } ?: return null
        val passphrase = configuration.passphrase.orEmpty()
        val auth = authName(runCatching { configuration.securityType }.getOrNull())
        log("Phone-hosted hotspot is up: \"$ssid\" ($auth).")
        return PhoneHostedNetwork.Credentials(ssid = ssid, passphrase = passphrase, auth = auth)
    }

    /**
     * The hotspot's name, from whichever accessor this Android version does not deprecate.
     *
     * Surrounding quotes are stripped even though this API is not documented to add them: the
     * older `WifiConfiguration.SSID` on the same subject does add them, and an SSID handed to the
     * dash with quotes around it is a network the dash will look for and never find.
     */
    private fun readSsid(configuration: SoftApConfiguration): String? {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { configuration.wifiSsid?.bytes?.toString(StandardCharsets.UTF_8) }.getOrNull()
        } else {
            @Suppress("DEPRECATION")
            runCatching { configuration.ssid }.getOrNull()
        }
        return raw?.trim()?.removeSurrounding("\"")
    }

    /**
     * The word the dash expects for the security type, in Carbit's vocabulary
     * (`WifiHotspotUtils.auth`, which is the literal string "WPA2" on every build seen).
     */
    private fun authName(securityType: Int?): String = when (securityType) {
        SoftApConfiguration.SECURITY_TYPE_OPEN -> "NONE"
        SoftApConfiguration.SECURITY_TYPE_WPA3_SAE -> "WPA3"
        SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION -> "WPA2"
        else -> "WPA2"
    }

    private fun describeFailure(reason: Int): String = when (reason) {
        ERROR_NO_CHANNEL ->
            "Android could not find a free Wi-Fi channel for the hotspot. Move away from " +
                "crowded Wi-Fi, or turn the phone's Wi-Fi off, and connect again."
        ERROR_TETHERING_DISALLOWED ->
            "Hotspot sharing is switched off for this phone or blocked by its carrier, so the " +
                "dashboard has no network to join."
        ERROR_INCOMPATIBLE_MODE ->
            "This phone cannot host a hotspot while doing what it is currently doing. Turn any " +
                "other hotspot or Wi-Fi sharing off, then connect again."
        else -> "Android could not start a hotspot for the dashboard (reason $reason)."
    }

    private companion object {
        // WifiManager.LocalOnlyHotspotCallback's reason codes are public constants on the
        // callback class; naming them here keeps the `when` readable next to the messages.
        const val ERROR_NO_CHANNEL = WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL
        const val ERROR_INCOMPATIBLE_MODE = WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE
        const val ERROR_TETHERING_DISALLOWED = WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED
    }
}
