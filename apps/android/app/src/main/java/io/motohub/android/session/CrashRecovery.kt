package io.motohub.android.session

import android.content.Context
import android.os.Build
import android.util.Log
import io.motohub.android.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists the fatal exception that terminated the previous process and brings it into the
 * rider-visible diagnostics on the following launch. The original uncaught-exception handler is
 * always invoked, so this is reporting only and never changes Android's crash semantics.
 */
object CrashRecovery {
    private const val FILE_NAME = "moto-hub-last-crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeReport(appContext, thread, throwable) }
                .onFailure { Log.e("MotoHubCrash", "Unable to persist fatal crash report", it) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Returns true when a previous crash was recovered into the normal diagnostics timeline. */
    fun restorePreviousCrash(context: Context): Boolean {
        val file = reportFile(context)
        val report = runCatching { file.takeIf(File::exists)?.readText(Charsets.UTF_8) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return false
        ProjectionEventLog.error("CRASH", "Previous process ended unexpectedly.\n$report")
        file.runCatching { delete() }
        return true
    }

    private fun writeReport(context: Context, thread: Thread, throwable: Throwable) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())
        reportFile(context).writeText(
            buildString {
                appendLine("Captured: $timestamp")
                appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("Thread: ${thread.name}")
                appendLine()
                append(Log.getStackTraceString(throwable))
            },
            Charsets.UTF_8
        )
    }

    private fun reportFile(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)
}
