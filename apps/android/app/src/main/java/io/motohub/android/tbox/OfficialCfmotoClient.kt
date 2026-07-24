package io.motohub.android.tbox

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** Best-effort controls for the official CFMOTO app that can hold the EasyConn ports. */
internal object OfficialCfmotoClient {
    const val PACKAGE_NAME = "com.cfmoto.cfmotointernational"

    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(PACKAGE_NAME, 0)
        true
    }.getOrDefault(false)

    fun closeBestEffort(context: Context): Boolean = runCatching {
        context.getSystemService(ActivityManager::class.java)
            ?.killBackgroundProcesses(PACKAGE_NAME)
        true
    }.getOrDefault(false)

    fun openAppSettings(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$PACKAGE_NAME")
            )
        )
        true
    }.getOrDefault(false)
}
