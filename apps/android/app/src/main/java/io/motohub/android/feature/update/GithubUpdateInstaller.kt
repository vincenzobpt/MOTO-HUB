// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.update

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.FileProvider
import io.motohub.android.net.validatedInternetNetwork
import io.motohub.android.net.withCellularNetwork
import io.motohub.android.session.ProjectionEventLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [totalBytes] is -1 when the size is unknown (e.g. the HTTP response omitted
 * Content-Length); callers should fall back to an indeterminate indicator in that case.
 */
data class DownloadProgress(val bytesDownloaded: Long, val totalBytes: Long) {
    val fraction: Float?
        get() = if (totalBytes > 0) (bytesDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f) else null
}

/**
 * The network an update would come down, in words, and whether the rider pays for it.
 *
 * A type rather than a pair of loose values because both halves are shown to a rider: the
 * description goes in a sentence, and [metered] decides whether that sentence is a confirmation
 * or a log line.
 */
data class DownloadNetwork(val description: String, val metered: Boolean)

object GithubUpdateInstaller {
    /**
     * Downloads the release's APK and hands it to the package installer.
     *
     * THE DOWNLOAD IS PINNED TO A NETWORK THAT REACHES THE INTERNET, and that is the whole of
     * this fix. While a T-Box session is up the process is BOUND to the motorcycle's Wi-Fi
     * (ConnectivityManager.bindProcessToNetwork - see TBoxNetworkConnector), which is deliberately
     * not Internet-capable, so a plain URL.openConnection() here reaches nothing.
     *
     * Support fc17a4f7 (OnePlus CPH2449, Benelli TRK 702X, 2026-09-05) is the case, and it is a
     * closed loop: at 10:15 the app told him "CORE 1.1.111 is behind 1.1.112" and offered him the
     * button, and he pressed it three times - 20:42:48, 20:49:25, 20:50:19 - each one failing
     * "Software caused connection abort", every one of them while a session was live. A day later
     * he was still on two different versions, still being warned about it. The app asked him to
     * update in exactly the state in which updating could not work.
     *
     * Same helper the diagnostics upload has used for the same reason since the collector
     * shipped; the updater simply never adopted it.
     *
     * NOT DECIDED HERE: whether to spend a rider's mobile data on a several-megabyte APK without
     * asking. On the motorcycle the validated network is usually cellular, so that is what this
     * now uses. The transport and whether it is metered are logged so the choice is visible in
     * the next report, and a confirmation before a metered download would be a product decision
     * on top of this, not a correction to it.
     */
    suspend fun downloadAndInstall(
        context: Context,
        release: GithubRelease,
        onProgress: (DownloadProgress) -> Unit = {}
    ): Result<Unit> = runCatching {
        // runCatching OUTSIDE the helper, not inside: withCellularNetwork retries the whole block
        // on another network when a VPN in lockdown mode refuses the bind, and it can only see a
        // refusal that is THROWN. A Result built inside would swallow it and leave every rider
        // running an ad-blocker VPN unable to update - the failure NetworkBindFallback exists for.
        //
        // Dispatchers.IO around the HELPER and not only around the transfer: for a non-cellular
        // call withCellularNetwork picks the network on the caller's dispatcher, and every caller
        // here is a Compose click handler on rememberCoroutineScope(), i.e. the main thread.
        // Choosing it walks ConnectivityManager.allNetworks and reads capabilities for each -
        // binder calls, on the thread drawing the dialog.
        withContext(Dispatchers.IO) {
            withCellularNetwork(context, cellularOnly = false) { network ->
                noteDownloadNetwork(context, network, release)
                downloadAndInstallOn(context, network, release, onProgress)
            }
        }
    }

    private suspend fun downloadAndInstallOn(
        context: Context,
        network: Network?,
        release: GithubRelease,
        onProgress: (DownloadProgress) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            val asset = requireNotNull(release.apkAsset) { "This release has no APK asset." }
            val updatesDirectory = File(context.cacheDir, "updates").apply { mkdirs() }
            updatesDirectory.listFiles()?.forEach { file -> runCatching { file.delete() } }
            val safeName = asset.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val temporaryFile = File(updatesDirectory, "$safeName.download")
            val apkFile = File(updatesDirectory, safeName)
            // The GitHub API already told us the asset size, so the progress bar can show a
            // real total immediately - before the HTTP response headers even arrive.
            withContext(Dispatchers.Main) { onProgress(DownloadProgress(0L, asset.sizeBytes)) }
            val downloadUrl = URL(asset.downloadUrl)
            val connection = ((network?.openConnection(downloadUrl) ?: downloadUrl.openConnection())
                as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = DOWNLOAD_TIMEOUT_MILLIS
                readTimeout = DOWNLOAD_TIMEOUT_MILLIS
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("User-Agent", "MOTO-HUB-Android/${io.motohub.android.BuildConfig.VERSION_NAME}")
            }
            try {
                check(connection.responseCode in 200..299) {
                    "GitHub asset download failed with HTTP ${connection.responseCode}"
                }
                val totalBytes = connection.contentLengthLong.takeIf { it > 0 } ?: asset.sizeBytes
                var bytesDownloaded = 0L
                var lastReportElapsed = 0L
                connection.inputStream.use { input ->
                    temporaryFile.outputStream().use { output ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE_BYTES)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            bytesDownloaded += read
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastReportElapsed >= PROGRESS_REPORT_INTERVAL_MILLIS) {
                                lastReportElapsed = now
                                withContext(Dispatchers.Main) {
                                    onProgress(DownloadProgress(bytesDownloaded, totalBytes))
                                }
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    onProgress(DownloadProgress(bytesDownloaded, totalBytes))
                }
            } finally {
                connection.disconnect()
            }
            check(temporaryFile.renameTo(apkFile)) { "Unable to finalize the downloaded APK." }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            withContext(Dispatchers.Main) {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, APK_MIME_TYPE)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
        }
    }

    /**
     * One line saying which network the APK is about to come down, and what it may cost.
     *
     * Written because the failure this fix repairs was invisible from the outside: the log said
     * "Software caused connection abort" three times and nothing said the process was bound to a
     * network that could not reach GitHub. The line that would have named it in one read is this
     * one, and the metered flag is the other half of it - a rider asking why their data allowance
     * moved deserves to find the answer in their own log.
     *
     * Never fatal: a missing ConnectivityManager or an unreadable capability set leaves the
     * download to proceed exactly as it would have.
     */
    private fun noteDownloadNetwork(context: Context, network: Network?, release: GithubRelease) {
        val verdict = downloadNetwork(context, network)
        ProjectionEventLog.record(
            "UPDATES",
            "Downloading ${release.versionName} (${release.apkAsset.sizeText()}) over " +
                verdict.description +
                if (verdict.metered) " (metered - this counts against the rider's allowance)" else ""
        )
    }

    /**
     * What the network a download would take is, and whether it costs the rider money.
     *
     * Asked by the update dialog BEFORE the download starts, and by [noteDownloadNetwork] as it
     * starts, deliberately through the same function: a warning that named a different network
     * from the one the transfer then took would be worse than no warning.
     *
     * [metered] is only ever true of a network that is definitely metered. An unknown network, an
     * unreadable capability set and a phone with no validated network at all all answer false -
     * this gates a confirmation dialog, and a dialog raised on a guess trains riders to dismiss it.
     */
    fun downloadNetwork(context: Context, network: Network? = validatedInternetNetwork(context)):
        DownloadNetwork {
        val capabilities = runCatching {
            context.applicationContext.getSystemService(ConnectivityManager::class.java)
                ?.getNetworkCapabilities(network)
        }.getOrNull()
        if (network == null) {
            return DownloadNetwork(
                description = "the phone's default network - no validated Internet network was " +
                    "found, so this may fail if MOTO-HUB is bound to the motorcycle's Wi-Fi",
                metered = false
            )
        }
        if (capabilities == null) return DownloadNetwork("a validated Internet network", false)
        val metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                DownloadNetwork("Wi-Fi", metered)
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                DownloadNetwork("mobile data", metered)
            else -> DownloadNetwork("a validated Internet network", metered)
        }
    }

    /** The asset size as a rider reads it, or a plain admission that it is not known. */
    internal fun GithubReleaseAsset?.sizeText(): String =
        this?.sizeBytes?.takeIf { it > 0 }?.let { "%.1f MB".format(it / 1_048_576.0) }
            ?: "an unknown size"

    fun canInstallUnknownSources(context: Context): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            true
        } else {
            runCatching { context.packageManager.canRequestPackageInstalls() }
                .getOrDefault(false)
        }

    fun unknownSourcesSettingsIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}")
    )

    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    private const val DOWNLOAD_TIMEOUT_MILLIS = 60_000
    private const val DOWNLOAD_BUFFER_SIZE_BYTES = 64 * 1024
    private const val PROGRESS_REPORT_INTERVAL_MILLIS = 150L
}
