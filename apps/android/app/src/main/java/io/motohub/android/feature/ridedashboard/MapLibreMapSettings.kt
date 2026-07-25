package io.motohub.android.feature.ridedashboard

import android.content.Context
import android.graphics.Color

/** MapLibre vector-basemap styles hosted by OpenFreeMap (OpenStreetMap data). */
enum class MapLibreBaseStyle(
    val label: String,
    val stylePath: String
) {
    LIBERTY("Liberty", "liberty"),
    BRIGHT("Bright", "bright"),
    DARK("Dark", "dark"),
    FIORD("Fiord", "fiord")
}

/** Bright, TFT-readable accent colors used for overlays rendered above the basemap. */
enum class MapLibreAccentColor(
    val label: String,
    val argb: Int
) {
    ELECTRIC_BLUE("Electric blue", Color.rgb(42, 164, 255)),
    RACING_RED("Racing red", Color.rgb(255, 70, 72)),
    NEON_GREEN("Neon green", Color.rgb(156, 255, 54)),
    AMBER_GOLD("Amber gold", Color.rgb(255, 190, 48)),
    ICE_CYAN("Ice cyan", Color.rgb(56, 240, 226)),
    VIOLET("Violet", Color.rgb(190, 120, 255)),
    WHITE("White", Color.WHITE)
}

/** Raster basemap styles available when the Ride Dashboard uses OpenStreetMap tiles. */
enum class OsmBaseStyle(
    val label: String,
    val cacheKey: String,
    val attribution: String
) {
    CARTO_VOYAGER("Voyager", "voyager", "© OpenStreetMap / CARTO"),
    CARTO_POSITRON("Positron light", "positron", "© OpenStreetMap / CARTO"),
    CARTO_DARK_MATTER("Dark matter", "dark-matter", "© OpenStreetMap / CARTO"),
    OSM_STANDARD("OpenStreetMap standard", "osm-standard", "© OpenStreetMap contributors")
}

/** Shared map-label sizing presets. OSM raster tiles use a visual map scale; MapLibre
 * applies the same factor directly to symbol-layer text sizes. */
enum class MapLabelScale(
    val label: String,
    val factor: Float
) {
    COMPACT("Compact", 0.85f),
    STANDARD("Standard", 1.0f),
    LARGE("Large", 1.25f),
    EXTRA_LARGE("Extra large", 1.5f)
}

data class MapLibreMapSettings(
    val baseStyle: MapLibreBaseStyle = MapLibreBaseStyle.LIBERTY,
    val labelScale: MapLabelScale = MapLabelScale.STANDARD,
    val routeColor: MapLibreAccentColor = MapLibreAccentColor.ELECTRIC_BLUE,
    val positionColor: MapLibreAccentColor = MapLibreAccentColor.NEON_GREEN,
    val destinationColor: MapLibreAccentColor = MapLibreAccentColor.RACING_RED,
    val curvyRoadColor: MapLibreAccentColor = MapLibreAccentColor.AMBER_GOLD
)

/** Overlay colors and basemap style for the classic raster OpenStreetMap renderer. */
data class OsmMapSettings(
    val baseStyle: OsmBaseStyle = OsmBaseStyle.CARTO_VOYAGER,
    val labelScale: MapLabelScale = MapLabelScale.STANDARD,
    val routeColor: MapLibreAccentColor = MapLibreAccentColor.ELECTRIC_BLUE,
    val positionColor: MapLibreAccentColor = MapLibreAccentColor.NEON_GREEN,
    val destinationColor: MapLibreAccentColor = MapLibreAccentColor.RACING_RED,
    val curvyRoadColor: MapLibreAccentColor = MapLibreAccentColor.AMBER_GOLD
)

object MapLibreMapSettingsStore {
    private const val PREFERENCES = "maplibre_map_settings"
    private const val KEY_BASE_STYLE = "base_style"
    private const val KEY_LABEL_SCALE = "label_scale"
    private const val KEY_ROUTE_COLOR = "route_color"
    private const val KEY_POSITION_COLOR = "position_color"
    private const val KEY_DESTINATION_COLOR = "destination_color"
    private const val KEY_CURVY_ROAD_COLOR = "curvy_road_color"

    fun load(context: Context): MapLibreMapSettings {
        val preferences = preferences(context)
        return MapLibreMapSettings(
            baseStyle = enumValue(preferences.getString(KEY_BASE_STYLE, null), MapLibreBaseStyle.LIBERTY),
            labelScale = enumValue(preferences.getString(KEY_LABEL_SCALE, null), MapLabelScale.STANDARD),
            routeColor = enumValue(preferences.getString(KEY_ROUTE_COLOR, null), MapLibreAccentColor.ELECTRIC_BLUE),
            positionColor = enumValue(preferences.getString(KEY_POSITION_COLOR, null), MapLibreAccentColor.NEON_GREEN),
            destinationColor = enumValue(preferences.getString(KEY_DESTINATION_COLOR, null), MapLibreAccentColor.RACING_RED),
            curvyRoadColor = enumValue(
                preferences.getString(KEY_CURVY_ROAD_COLOR, null),
                MapLibreAccentColor.AMBER_GOLD
            )
        )
    }

    fun save(context: Context, settings: MapLibreMapSettings) {
        preferences(context).edit()
            .putString(KEY_BASE_STYLE, settings.baseStyle.name)
            .putString(KEY_LABEL_SCALE, settings.labelScale.name)
            .putString(KEY_ROUTE_COLOR, settings.routeColor.name)
            .putString(KEY_POSITION_COLOR, settings.positionColor.name)
            .putString(KEY_DESTINATION_COLOR, settings.destinationColor.name)
            .putString(KEY_CURVY_ROAD_COLOR, settings.curvyRoadColor.name)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String?, fallback: T): T =
        value?.let { stored -> enumValues<T>().firstOrNull { it.name == stored } } ?: fallback

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE
    )
}

object OsmMapSettingsStore {
    private const val PREFERENCES = "osm_map_settings"
    private const val KEY_BASE_STYLE = "base_style"
    private const val KEY_LABEL_SCALE = "label_scale"
    private const val KEY_ROUTE_COLOR = "route_color"
    private const val KEY_POSITION_COLOR = "position_color"
    private const val KEY_DESTINATION_COLOR = "destination_color"
    private const val KEY_CURVY_ROAD_COLOR = "curvy_road_color"

    fun load(context: Context): OsmMapSettings {
        val preferences = preferences(context)
        return OsmMapSettings(
            baseStyle = enumValue(preferences.getString(KEY_BASE_STYLE, null), OsmBaseStyle.CARTO_VOYAGER),
            labelScale = enumValue(preferences.getString(KEY_LABEL_SCALE, null), MapLabelScale.STANDARD),
            routeColor = enumValue(preferences.getString(KEY_ROUTE_COLOR, null), MapLibreAccentColor.ELECTRIC_BLUE),
            positionColor = enumValue(preferences.getString(KEY_POSITION_COLOR, null), MapLibreAccentColor.NEON_GREEN),
            destinationColor = enumValue(preferences.getString(KEY_DESTINATION_COLOR, null), MapLibreAccentColor.RACING_RED),
            curvyRoadColor = enumValue(
                preferences.getString(KEY_CURVY_ROAD_COLOR, null),
                MapLibreAccentColor.AMBER_GOLD
            )
        )
    }

    fun save(context: Context, settings: OsmMapSettings) {
        preferences(context).edit()
            .putString(KEY_BASE_STYLE, settings.baseStyle.name)
            .putString(KEY_LABEL_SCALE, settings.labelScale.name)
            .putString(KEY_ROUTE_COLOR, settings.routeColor.name)
            .putString(KEY_POSITION_COLOR, settings.positionColor.name)
            .putString(KEY_DESTINATION_COLOR, settings.destinationColor.name)
            .putString(KEY_CURVY_ROAD_COLOR, settings.curvyRoadColor.name)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String?, fallback: T): T =
        value?.let { stored -> enumValues<T>().firstOrNull { it.name == stored } } ?: fallback

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE
    )
}
