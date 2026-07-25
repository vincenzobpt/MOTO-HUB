package io.motohub.android.feature.controls

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Start Google Maps turn-by-turn to a destination from a handlebar button.
 * With Android Auto projecting, the route appears on the dash.
 */
object NavLauncher {

    fun canLaunchFromBackground(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun navigate(context: Context, destination: String, log: (String) -> Unit): Boolean {
        val dest = destination.trim()
        if (dest.isEmpty()) {
            log("[NAV] no destination set for that button")
            return false
        }
        if (!canLaunchFromBackground(context)) {
            log("[NAV] 'Display over other apps' is off — Android may block this launch")
        }
        val uri = Uri.parse("google.navigation:q=" + Uri.encode(dest))
        val intents = listOf(
            Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps"),
            Intent(Intent.ACTION_VIEW, uri),
        )
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                log("[NAV] → navigating to \"$dest\"")
                return true
            } catch (_: Exception) { }
        }
        log("[NAV] navigation failed — is Google Maps installed?")
        return false
    }

    fun overlayPermissionIntent(context: Context) =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
}
