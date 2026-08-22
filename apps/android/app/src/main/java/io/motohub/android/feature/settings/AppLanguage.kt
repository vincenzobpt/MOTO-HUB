// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.settings

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
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
    KOREAN("ko-KR", R.string.language_korean),
    FRENCH("fr-FR", R.string.language_french),
    SPANISH("es-ES", R.string.language_spanish),
    GERMAN("de-DE", R.string.language_german)
}

object AppLanguageManager {
    /**
     * Per-app locales are a `LocaleManager` feature and that service exists only from
     * Android 13. On 12/12L the Settings screen hides the language row entirely and the app
     * follows the phone language - every catalogue locale still resolves through the normal
     * resource qualifiers, only the in-app override is missing.
     */
    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun current(context: Context): AppLanguage {
        if (!isSupported) return AppLanguage.SYSTEM
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
        if (!isSupported) return
        localeManager(context).applicationLocales = language.tag?.let {
            LocaleList.forLanguageTags(it)
        } ?: LocaleList.getEmptyLocaleList()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun localeManager(context: Context): LocaleManager =
        context.getSystemService(LocaleManager::class.java)
}
