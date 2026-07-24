package io.motohub.android.feature.diagnostics

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object DiagnosticLogShare {
    fun createShareIntent(context: Context, text: String, nowMillis: Long = System.currentTimeMillis()): Intent {
        val file = writeLogFile(context, text, nowMillis)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "MOTO-HUB diagnostics")
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    internal fun fileName(nowMillis: Long): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(nowMillis))
        return "MOTO-HUB-diagnostics-$stamp.txt"
    }

    private fun writeLogFile(context: Context, text: String, nowMillis: Long): File {
        val directory = File(context.cacheDir, "shared-diagnostics").apply { mkdirs() }
        directory.listFiles()
            ?.filter { it.isFile && it.name.startsWith("MOTO-HUB-diagnostics-") }
            ?.forEach { runCatching { it.delete() } }
        return File(directory, fileName(nowMillis)).apply {
            writeText(text, Charsets.UTF_8)
        }
    }
}
