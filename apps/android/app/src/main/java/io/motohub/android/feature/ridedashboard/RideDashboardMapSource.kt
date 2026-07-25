package io.motohub.android.feature.ridedashboard

import android.content.Context

enum class RideDashboardMapSource(val label: String) {
    OPEN_STREET_MAP("OpenStreetMap"),
    MAPLIBRE("MapLibre"),
    ANDROID_AUTO("Android Auto")
}

object RideDashboardMapSourceStore {
    private const val PREFERENCES = "ride_dashboard_preferences"
    private const val KEY_MAP_SOURCE = "map_source"

    fun load(context: Context): RideDashboardMapSource {
        val stored = preferences(context).getString(
            KEY_MAP_SOURCE,
            RideDashboardMapSource.OPEN_STREET_MAP.name
        )
        return RideDashboardMapSource.entries.firstOrNull { it.name == stored }
            ?: RideDashboardMapSource.OPEN_STREET_MAP
    }

    fun save(context: Context, source: RideDashboardMapSource) {
        preferences(context).edit().putString(KEY_MAP_SOURCE, source.name).apply()
    }

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE
    )
}
