// MOTO-HUB glue (technique ported from headunit-revived AGPLv3 AapService.startSelfMode).
// Triggers Google Android Auto's loopback "self-mode": asks gearhead to project to 127.0.0.1:PORT
// with NO VPN. Best launched from a foreground Activity to satisfy Android's background-activity-
// launch restrictions (Android 12+/15).
package io.motohub.android.aa

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Parcel
import android.os.Parcelable

object AaSelfMode {
    private const val GEARHEAD_PKG = "com.google.android.projection.gearhead"

    fun trigger(context: Context, port: Int = AaReceiver.PORT, log: (String) -> Unit) {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val networkToUse: Parcelable? = (cm.activeNetwork as? Parcelable) ?: createFakeNetwork(0)
            val fakeWifiInfo = createFakeWifiInfo()

            val intent = Intent().apply {
                setClassName(
                    GEARHEAD_PKG,
                    "com.google.android.apps.auto.wireless.setup.service.impl.WirelessStartupActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("PARAM_HOST_ADDRESS", "127.0.0.1")
                putExtra("PARAM_SERVICE_PORT", port)
                networkToUse?.let { putExtra("PARAM_SERVICE_WIFI_NETWORK", it) }
                fakeWifiInfo?.let { putExtra("wifi_info", it) }
            }
            log("[AA] launching Android Auto WirelessStartupActivity → 127.0.0.1:$port")
            context.startActivity(intent)
        } catch (e: Exception) {
            log("[AA] Activity trigger failed (${e.message}); trying broadcast fallback")
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val networkToUse: Parcelable? = (cm.activeNetwork as? Parcelable) ?: createFakeNetwork(0)
                val fakeWifiInfo = createFakeWifiInfo()
                val receiverIntent = Intent().apply {
                    setClassName(
                        GEARHEAD_PKG,
                        "com.google.android.apps.auto.wireless.setup.receiver.WirelessStartupReceiver"
                    )
                    action = "com.google.android.apps.auto.wireless.setup.receiver.wirelessstartup.START"
                    putExtra("ip_address", "127.0.0.1")
                    putExtra("projection_port", port)
                    networkToUse?.let { putExtra("PARAM_SERVICE_WIFI_NETWORK", it) }
                    fakeWifiInfo?.let { putExtra("wifi_info", it) }
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                }
                context.sendBroadcast(receiverIntent)
                log("[AA] broadcast fallback sent")
            } catch (e2: Exception) {
                log("[AA] self-mode trigger failed entirely: ${e2.message} — is Android Auto installed & set up?")
            }
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
