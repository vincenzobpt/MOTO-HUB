package io.motohub.android.tbox

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.provider.Settings

/**
 * Phone Wi-Fi must be on before Android can join the T-Box AP. When it's off,
 * [TBoxNetworkConnector.connect] silently runs out the clock on its 30s timeout instead of
 * failing fast, so callers should check this first and skip straight to an actionable message.
 */
internal object WifiGate {
    const val WIFI_OFF_MESSAGE = "Phone Wi-Fi is off. Turn it on, then tap Connect again."

    fun isWifiEnabled(context: Context): Boolean {
        val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
            ?: return true // can't tell - don't block the connection attempt
        @Suppress("DEPRECATION")
        return wifiManager.isWifiEnabled
    }

    fun openWifiSettings(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)
}
