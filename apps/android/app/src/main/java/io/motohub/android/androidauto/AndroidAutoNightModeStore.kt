package io.motohub.android.androidauto

import android.content.Context

class AndroidAutoNightModeStore(private val context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun load(): Boolean {
        // Check manual map theme first; fall back to stored night flag only if NightPrefs is
        // unreadable. AUTO must resolve through NightPrefs.isNightNow() (phone dark mode / clock) -
        // routing it to the legacy KEY_NIGHT flag instead left AUTO stuck on whatever that flag
        // last happened to be (day, on a fresh install), never actually time/theme-aware.
        val manualTheme = runCatching {
            io.motohub.android.feature.ridedashboard.nav.NightPrefs.theme(context)
        }.getOrNull() ?: return preferences.getBoolean(KEY_NIGHT, false)
        return when (manualTheme) {
            io.motohub.android.feature.ridedashboard.nav.MapTheme.DAY -> false
            io.motohub.android.feature.ridedashboard.nav.MapTheme.NIGHT -> true
            io.motohub.android.feature.ridedashboard.nav.MapTheme.AUTO ->
                io.motohub.android.feature.ridedashboard.nav.NightPrefs.isNightNow(context)
        }
    }

    fun save(isNight: Boolean) {
        preferences.edit().putBoolean(KEY_NIGHT, isNight).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "android_auto_night_mode"
        const val KEY_NIGHT = "night"
    }
}
