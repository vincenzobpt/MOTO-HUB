package io.motohub.android.session

import android.content.Context
import android.os.Build
import android.util.Log
import io.motohub.android.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LogLevel {
    DEBUG,
    INFO,
    WARNING,
    ERROR
}

data class ProjectionEvent(
    val timestampMillis: Long,
    val source: String,
    val message: String,
    val level: LogLevel = LogLevel.INFO
)

/** Persistent application-wide diagnostic log exposed directly in the UI. */
object ProjectionEventLog {
    private const val MAX_EVENTS = 2_500
    private const val FILE_NAME = "moto-hub-diagnostics.log"
    private val lock = Any()

    private val mutableEvents = MutableStateFlow<List<ProjectionEvent>>(emptyList())
    val events: StateFlow<List<ProjectionEvent>> = mutableEvents.asStateFlow()
    private var logFile: File? = null

    fun initialize(context: Context) {
        synchronized(lock) {
            if (logFile != null) return
            logFile = File(context.applicationContext.filesDir, FILE_NAME)
            val restored = runCatching {
                logFile?.takeIf(File::exists)
                    ?.readLines(Charsets.UTF_8)
                    ?.mapNotNull(::decodeLine)
                    ?.takeLast(MAX_EVENTS)
                    .orEmpty()
            }.getOrElse { emptyList() }
            mutableEvents.value = restored
        }
        record(
            source = "APP",
            message = "Process started: MOTO-HUB ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), " +
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
                "${Build.MANUFACTURER} ${Build.MODEL}."
        )
    }

    fun record(
        source: String,
        message: String,
        level: LogLevel = LogLevel.INFO,
        throwable: Throwable? = null
    ) {
        val detail = redact(buildString {
            append(message)
            if (throwable != null) {
                append("\n")
                append(Log.getStackTraceString(throwable))
            }
        })
        when (level) {
            LogLevel.DEBUG -> Log.d(LOG_TAG, "$source: $detail")
            LogLevel.INFO -> Log.i(LOG_TAG, "$source: $detail")
            LogLevel.WARNING -> Log.w(LOG_TAG, "$source: $detail")
            LogLevel.ERROR -> Log.e(LOG_TAG, "$source: $detail")
        }
        val event = ProjectionEvent(System.currentTimeMillis(), source, detail, level)
        synchronized(lock) {
            val updated = (mutableEvents.value + event).takeLast(MAX_EVENTS)
            mutableEvents.value = updated
            val file = logFile ?: return@synchronized
            runCatching {
                if (updated.size == MAX_EVENTS && file.length() > MAX_FILE_BYTES) {
                    rewrite(file, updated)
                } else {
                    file.appendText(encodeLine(event) + "\n", Charsets.UTF_8)
                }
            }.onFailure { Log.e(LOG_TAG, "Unable to persist diagnostic log", it) }
        }
    }

    fun clear() {
        synchronized(lock) {
            mutableEvents.value = emptyList()
            logFile?.runCatching { writeText("", Charsets.UTF_8) }
        }
        record("LOG", "Diagnostic log cleared by the user.")
    }

    fun debug(source: String, message: String) = record(source, message, LogLevel.DEBUG)

    fun warning(source: String, message: String, throwable: Throwable? = null) =
        record(source, message, LogLevel.WARNING, throwable)

    fun error(source: String, message: String, throwable: Throwable? = null) =
        record(source, message, LogLevel.ERROR, throwable)

    fun exportText(): String {
        val snapshot = mutableEvents.value
        return buildString {
            appendLine("MOTO-HUB diagnostics")
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Entries: ${snapshot.size}")
            appendLine("Generated: ${formatTime(System.currentTimeMillis())}")
            appendLine("----------------------------------------")
            snapshot.forEach { event ->
                append(formatTime(event.timestampMillis))
                append("  ")
                append(event.level.name.padEnd(7))
                append("  ")
                append(event.source)
                append(": ")
                appendLine(event.message)
            }
        }
    }

    private fun rewrite(file: File, events: List<ProjectionEvent>) {
        file.writeText(events.joinToString(separator = "\n", postfix = "\n", transform = ::encodeLine))
    }

    private fun encodeLine(event: ProjectionEvent): String = listOf(
        event.timestampMillis.toString(),
        event.level.name,
        encode(event.source),
        encode(event.message)
    ).joinToString("\t")

    private fun decodeLine(line: String): ProjectionEvent? = runCatching {
        val fields = line.split('\t', limit = 4)
        if (fields.size != 4) return@runCatching null
        ProjectionEvent(
            timestampMillis = fields[0].toLong(),
            level = LogLevel.valueOf(fields[1]),
            source = decode(fields[2]),
            message = decode(fields[3])
        )
    }.getOrNull()

    private fun encode(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decode(value: String): String =
        String(Base64.getDecoder().decode(value), Charsets.UTF_8)

    private fun redact(value: String): String = value
        .replace(SECRET_PATTERN, "$1=<redacted>")
        .take(MAX_MESSAGE_CHARS)

    private fun formatTime(timestampMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(timestampMillis))

    private const val LOG_TAG = "MotoHubProjection"
    private const val MAX_FILE_BYTES = 2L * 1024L * 1024L
    private const val MAX_MESSAGE_CHARS = 16_000
    private val SECRET_PATTERN = Regex(
        "(?i)(password|pwd|passphrase)\\s*[:=]\\s*[^\\s,;]+"
    )
}
