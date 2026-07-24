package io.motohub.android.feature.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.FileProvider
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

object GithubUpdateInstaller {
    suspend fun downloadAndInstall(
        context: Context,
        release: GithubRelease,
        onProgress: (DownloadProgress) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val asset = requireNotNull(release.apkAsset) { "This release has no APK asset." }
            val updatesDirectory = File(context.cacheDir, "updates").apply { mkdirs() }
            updatesDirectory.listFiles()?.forEach { file -> runCatching { file.delete() } }
            val safeName = asset.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val temporaryFile = File(updatesDirectory, "$safeName.download")
            val apkFile = File(updatesDirectory, safeName)
            // The GitHub API already told us the asset size, so the progress bar can show a
            // real total immediately - before the HTTP response headers even arrive.
            withContext(Dispatchers.Main) { onProgress(DownloadProgress(0L, asset.sizeBytes)) }
            val connection = (URL(asset.downloadUrl).openConnection() as HttpURLConnection).apply {
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
