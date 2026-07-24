package io.motohub.android.feature.settings

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import io.motohub.android.R

/**
 * Languages exposed by MOTO-HUB. The SYSTEM option clears the per-app locale
 * override and lets Android follow the phone language.
 */
enum class AppLanguage(
    val tag: String?,
    val labelRes: Int
) {
    SYSTEM(null, R.string.language_system_default),
    ENGLISH("en-US", R.string.language_english),
    ITALIAN("it-IT", R.string.language_italian),
    PORTUGUESE("pt-PT", R.string.language_portuguese),
    KOREAN("ko-KR", R.string.language_korean)
}

object AppLanguageManager {
    fun current(context: Context): AppLanguage {
        val tag = localeManager(context).applicationLocales
            .takeIf { !it.isEmpty }
            ?.get(0)
            ?.toLanguageTag()
            ?.lowercase()
            ?: return AppLanguage.SYSTEM

        return AppLanguage.entries.firstOrNull { it.tag?.lowercase() == tag }
            ?: AppLanguage.SYSTEM
    }

    fun set(context: Context, language: AppLanguage) {
        localeManager(context).applicationLocales = language.tag?.let {
            LocaleList.forLanguageTags(it)
        } ?: LocaleList.getEmptyLocaleList()
    }

    private fun localeManager(context: Context): LocaleManager =
        context.getSystemService(LocaleManager::class.java)
}
