package io.motohub.android.tbox

import android.content.Context
import android.net.wifi.WifiManager
import io.motohub.android.session.ProjectionEventLog

/** Keeps the phone Wi-Fi path awake while a T-Box video stream is active. */
class TBoxStreamingLocks(
    context: Context,
    private val logTag: String
) {
    // A Service may instantiate this helper before attachBaseContext(). Do not touch the context
    // until acquire(), which runs after the service is attached and in the foreground.
    private val componentContext = context
    private var wifiManager: WifiManager? = null
    private var lowLatencyLock: WifiManager.WifiLock? = null
    private var highPerformanceLock: WifiManager.WifiLock? = null

    fun acquire() {
        try {
            val manager = wifiManager ?: componentContext.getSystemService(WifiManager::class.java)
                ?.also { wifiManager = it }
                ?: return
            @Suppress("DEPRECATION")
            if (lowLatencyLock == null) {
                lowLatencyLock = manager.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                    "MOTO-HUB:$logTag:low-latency"
                ).apply { setReferenceCounted(false) }
            }
            @Suppress("DEPRECATION")
            if (highPerformanceLock == null) {
                highPerformanceLock = manager.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "MOTO-HUB:$logTag:high-performance"
                ).apply { setReferenceCounted(false) }
            }
            var acquired = false
            if (lowLatencyLock?.isHeld != true) {
                lowLatencyLock?.acquire()
                acquired = true
            }
            if (highPerformanceLock?.isHeld != true) {
                highPerformanceLock?.acquire()
                acquired = true
            }
            if (acquired) {
                ProjectionEventLog.record("NETWORK", "$logTag Wi-Fi streaming locks acquired.")
            }
        } catch (failure: Throwable) {
            ProjectionEventLog.warning(
                "NETWORK",
                "$logTag Wi-Fi streaming locks unavailable; continuing with Android defaults.",
                failure
            )
        }
    }

    fun release() {
        runCatching { if (lowLatencyLock?.isHeld == true) lowLatencyLock?.release() }
        runCatching { if (highPerformanceLock?.isHeld == true) highPerformanceLock?.release() }
        lowLatencyLock = null
        highPerformanceLock = null
    }
}
