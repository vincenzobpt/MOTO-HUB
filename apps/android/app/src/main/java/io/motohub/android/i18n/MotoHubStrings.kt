package io.motohub.android.i18n

import android.content.Context
import java.util.Locale

/**
 * Compatibility bridge for the legacy UI catalogue. The source English text
 * is retained as the fallback while every caller is migrated to a resource.
 * The key is deterministic, so translators only edit XML catalogues.
 */
object MotoHubStrings {
    @Volatile
    private var applicationContext: Context? = null

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
    }

    fun keyFor(source: String): String {
        val stem = source.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(48)
            .ifBlank { "text" }
        return "legacy_${stem}_${source.hashCode().toUInt().toString(16)}"
    }

    fun id(context: Context, source: String): Int =
        context.resources.getIdentifier(keyFor(source), "string", context.packageName)

    fun get(context: Context, source: String): String {
        val resourceId = id(context, source)
        return if (resourceId == 0) source else context.getString(resourceId)
    }

    /** Non-Compose lookup for services, Canvas renderers, and callbacks. */
    fun get(source: String, vararg arguments: Any?): String {
        val value = applicationContext?.let { get(it, source) } ?: source
        return if (arguments.isEmpty()) value else {
            String.format(Locale.getDefault(), value, *arguments)
        }
    }
}

/** Lookup used by migrated Compose screens and regular Android callbacks. */
fun motoHubText(source: String, vararg arguments: Any?): String =
    MotoHubStrings.get(source, *arguments)
