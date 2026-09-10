// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * The OEM companion apps that can hold the EasyConn session MOTO-HUB needs.
 *
 * Every one of them binds the same three local reverse ports (10920-10922) and keeps them while it
 * is merely in the recent-apps list, so whichever one a rider has installed is the likely holder
 * when [TBoxConflictDiagnostics.isPortConflict] fires. There is no programmatic "close": since
 * Android 14 killBackgroundProcesses only affects the caller's own packages, and even before that
 * it never touched a foreground app or foreground service - which a companion app holding the
 * link invariably is. So the only real remedy is the rider force-stopping it from its App info
 * screen ([openAppSettings]).
 *
 * "(this app's minSdk)" used to stand after Android 14 here, and stopped being true when CORE
 * dropped to minSdk 31 in 1.1.75. It read as "therefore the call is dead everywhere", which sent
 * a later reader to delete a call in [io.motohub.android.externaldisplay.AutolinkStop] that still
 * works on Android 12 and 13. The version that matters for the API is 14; the version this app
 * installs on is 12.
 *
 * This used to know one package, CFMOTO's. A Zontes rider's field log of 2026-08-23 is what made
 * the cost visible: the ports were held for four minutes across six connection attempts, and
 * because the holder was `tayo.com.ZontesIntelligence` the app both told them to force-stop an app
 * they do not have and withheld the button that would have taken them there.
 */
internal object CompanionAppRegistry {

    /** A companion app as the rider sees it: the package to open, under the label on their phone. */
    data class CompanionApp(val packageName: String, val displayName: String)

    /**
     * Only packages this project has actually observed, never a guess: a wrong package name is a
     * silent miss, and the whole point here is not to send a rider after the wrong app again.
     * `com.cfmoto.*` and the Carbit framework namespaces come from the decompiled companions under
     * `refs/`; the Zontes one is what its dash advertises over mDNS as the client it expects.
     *
     * Adding a brand is one line here plus one `<package>` entry in the manifest's `<queries>`
     * block - without that entry Android hides the package from [isInstalled] and the app is
     * invisible no matter what this list says. Declaring them one by one is deliberate: the
     * alternative, QUERY_ALL_PACKAGES, is the kind of permission that gets an APK flagged.
     */
    val known: List<CompanionApp> = listOf(
        CompanionApp("com.cfmoto.cfmotointernational", "CFMOTO"),
        CompanionApp("com.cfmoto.motoplay", "CFMOTO MotoPlay"),
        CompanionApp("tayo.com.ZontesIntelligence", "Zontes Smart"),
        CompanionApp("net.easyconn.carman", "Carbit Ride"),
        CompanionApp("com.carbit.easyconnect", "Carbit EasyConnect")
    )

    /**
     * The companion app most likely to be holding the link, or null when none is installed.
     *
     * [preferredPackage] is the client name the dash itself asked for - carried on [TBoxHost] from
     * the mDNS advertisement or the wake probe that answered. When that app is installed it is the
     * answer by definition: the dash named it. Everything else is [known] order.
     */
    fun installed(context: Context, preferredPackage: String? = null): CompanionApp? {
        val candidates = preferredPackage
            ?.let { preferred -> known.filter { it.packageName == preferred } + known }
            ?: known
        return candidates.firstOrNull { isInstalled(context, it.packageName) }
            ?.let { app -> app.copy(displayName = installedLabel(context, app) ?: app.displayName) }
    }

    /** Convenience for the UI, which only ever needs the name to put in a sentence. */
    fun installedName(context: Context, preferredPackage: String? = null): String? =
        installed(context, preferredPackage)?.displayName

    fun openAppSettings(context: Context, app: CompanionApp): Boolean = runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${app.packageName}")
            )
        )
        true
    }.getOrDefault(false)

    private fun isInstalled(context: Context, packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    /**
     * The label the launcher shows, so the rider is sent after the words on their own home screen
     * rather than our English name for the brand. Falls back to [CompanionApp.displayName] when
     * the label cannot be read or is blank.
     */
    private fun installedLabel(context: Context, app: CompanionApp): String? = runCatching {
        context.packageManager
            .getApplicationInfo(app.packageName, 0)
            .loadLabel(context.packageManager)
            .toString()
            .trim()
            .ifBlank { null }
    }.getOrNull()
}
