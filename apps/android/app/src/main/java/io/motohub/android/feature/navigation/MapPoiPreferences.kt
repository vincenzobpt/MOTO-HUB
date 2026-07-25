package io.motohub.android.feature.navigation

import android.content.Context

/** Persists the rider's POI filter between map previews and app launches. */
internal object MapPoiPreferences {
    private const val PREFS_NAME = "map_poi_preferences"
    private const val SELECTED_CATEGORIES_KEY = "selected_categories"

    val defaultCategories: Set<MapPoiCategory> = setOf(
        MapPoiCategory.FUEL,
        MapPoiCategory.FOOD,
        MapPoiCategory.PARKING,
        MapPoiCategory.MOTORCYCLE_SERVICE,
        MapPoiCategory.CHARGING,
        MapPoiCategory.VIEWPOINT
    )

    fun load(context: Context): Set<MapPoiCategory> {
        val stored = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(SELECTED_CATEGORIES_KEY, null)
            ?: return defaultCategories
        val parsed = stored.mapNotNull { value ->
            runCatching { MapPoiCategory.valueOf(value) }.getOrNull()
        }.toSet()
        // Recover gracefully if a future version removed/renamed every stored
        // category, while preserving an intentional "CLEAR ALL" (empty set).
        return if (stored.isNotEmpty() && parsed.isEmpty()) defaultCategories else parsed
    }

    fun save(context: Context, categories: Set<MapPoiCategory>) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(SELECTED_CATEGORIES_KEY, categories.map { it.name }.toSet())
            .apply()
    }
}
