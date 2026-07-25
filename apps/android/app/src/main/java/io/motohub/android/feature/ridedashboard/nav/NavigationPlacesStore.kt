package io.motohub.android.feature.ridedashboard.nav

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A saved destination: a recent history entry or a named favorite. */
data class SavedPlace(
    val label: String,
    val point: NavPoint,
    val favoriteName: String? = null
)

/**
 * Recent destinations and named favorites, persisted locally. Not sensitive
 * (public place names and coordinates), so no Keystore encryption - unlike
 * [io.motohub.android.data.MotorcycleProfileStore] which guards Wi-Fi secrets.
 */
class NavigationPlacesStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun recents(): List<SavedPlace> = decode(preferences.getString(KEY_RECENTS, null))

    fun favorites(): List<SavedPlace> = decode(preferences.getString(KEY_FAVORITES, null))

    fun addRecent(place: NavPlace) {
        val entry = SavedPlace(label = place.label, point = place.point)
        val updated = buildList {
            add(entry)
            addAll(recents().filterNot { it.samePoint(entry) })
        }.take(MAX_RECENTS)
        preferences.edit().putString(KEY_RECENTS, encode(updated)).apply()
    }

    fun removeRecent(place: SavedPlace) {
        preferences.edit()
            .putString(KEY_RECENTS, encode(recents().filterNot { it.samePoint(place) }))
            .apply()
    }

    fun saveFavorite(place: NavPlace, name: String) {
        val entry = SavedPlace(label = place.label, point = place.point, favoriteName = name.trim())
        val updated = buildList {
            addAll(favorites().filterNot { it.favoriteName.equals(entry.favoriteName, ignoreCase = true) })
            add(entry)
        }
        preferences.edit().putString(KEY_FAVORITES, encode(updated)).apply()
    }

    fun removeFavorite(place: SavedPlace) {
        preferences.edit()
            .putString(KEY_FAVORITES, encode(favorites().filterNot {
                it.favoriteName.equals(place.favoriteName, ignoreCase = true)
            }))
            .apply()
    }

    fun home(): SavedPlace? = favorites().firstOrNull { it.favoriteName.equals(HOME, ignoreCase = true) }

    private fun SavedPlace.samePoint(other: SavedPlace): Boolean =
        haversineClose(point, other.point)

    private fun encode(places: List<SavedPlace>): String {
        val array = JSONArray()
        places.forEach { place ->
            array.put(
                JSONObject().apply {
                    put(KEY_LABEL, place.label)
                    put(KEY_LATITUDE, place.point.latitude)
                    put(KEY_LONGITUDE, place.point.longitude)
                    if (place.favoriteName != null) put(KEY_FAVORITE_NAME, place.favoriteName)
                }
            )
        }
        return array.toString()
    }

    private fun decode(serialized: String?): List<SavedPlace> {
        if (serialized.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(serialized)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        SavedPlace(
                            label = item.optString(KEY_LABEL),
                            point = NavPoint(item.getDouble(KEY_LATITUDE), item.getDouble(KEY_LONGITUDE)),
                            favoriteName = if (item.isNull(KEY_FAVORITE_NAME)) null else item.optString(KEY_FAVORITE_NAME).takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        const val HOME = "Home"
        const val WORK = "Work"
        private const val PREFERENCES_NAME = "navigation_places"
        private const val KEY_RECENTS = "recents"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_LABEL = "label"
        private const val KEY_LATITUDE = "lat"
        private const val KEY_LONGITUDE = "lon"
        private const val KEY_FAVORITE_NAME = "favorite_name"
        private const val MAX_RECENTS = 15
        private const val SAME_POINT_METERS = 25.0

        private fun haversineClose(a: NavPoint, b: NavPoint): Boolean {
            val latitudeDelta = Math.toRadians(b.latitude - a.latitude)
            val longitudeDelta = Math.toRadians(b.longitude - a.longitude)
            val firstLatitude = Math.toRadians(a.latitude)
            val secondLatitude = Math.toRadians(b.latitude)
            val h = Math.sin(latitudeDelta / 2).let { it * it } +
                Math.cos(firstLatitude) * Math.cos(secondLatitude) *
                Math.sin(longitudeDelta / 2).let { it * it }
            val meters = 2 * 6_371_000.0 * Math.asin(Math.sqrt(h.coerceIn(0.0, 1.0)))
            return meters <= SAME_POINT_METERS
        }
    }
}
