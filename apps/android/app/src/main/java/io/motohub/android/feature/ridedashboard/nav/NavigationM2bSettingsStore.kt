package io.motohub.android.feature.ridedashboard.nav

import android.content.Context

/**
 * M2b preferences: weather highlights, golden-hour, fuel warnings, curvy-road
 * highlighting. Scenic bias is a per-route choice (see [io.motohub.android.feature.settings.RoutePreference.SCENIC]),
 * not a standing preference, so it is not stored here.
 */
class NavigationM2bSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun weatherAtArrivalEnabled(): Boolean = preferences.getBoolean(KEY_WEATHER_AT_ARRIVAL, true)

    fun setWeatherAtArrival(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_WEATHER_AT_ARRIVAL, enabled).apply()
    }

    fun goldenHourEnabled(): Boolean = preferences.getBoolean(KEY_GOLDEN_HOUR, true)

    fun setGoldenHour(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_GOLDEN_HOUR, enabled).apply()
    }

    fun fuelRangeWarningEnabled(): Boolean = preferences.getBoolean(KEY_FUEL_WARNING, true)

    fun setFuelWarning(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_FUEL_WARNING, enabled).apply()
    }

    fun curvedSegmentsHighlighted(): Boolean = preferences.getBoolean(KEY_CURVY_HIGHLIGHT, false)

    fun setCurvyHighlight(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_CURVY_HIGHLIGHT, enabled).apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "navigation_m2b_settings"
        private const val KEY_WEATHER_AT_ARRIVAL = "weather_at_arrival"
        private const val KEY_GOLDEN_HOUR = "golden_hour"
        private const val KEY_FUEL_WARNING = "fuel_warning"
        private const val KEY_CURVY_HIGHLIGHT = "curvy_highlight"
    }
}
