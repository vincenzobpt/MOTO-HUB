package io.motohub.android.feature.ridedashboard.nav

import android.content.Context
import java.time.Instant
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Persists complete routes with metadata so riders can "ride again" saved trips. */
class SavedRidesStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun all(): List<SavedRide> = decode(preferences.getString(KEY_RIDES, null))

    fun byId(id: String): SavedRide? = all().firstOrNull { it.id == id }

    fun save(ride: SavedRide) {
        val updated = buildList {
            add(ride)
            addAll(all().filterNot { it.id == ride.id })
        }.take(MAX_SAVED_RIDES)
        preferences.edit().putString(KEY_RIDES, encode(updated)).apply()
    }

    fun saveFromRoute(route: NavRoute, destination: NavPlace, label: String? = null) {
        val ride = SavedRide(
            id = route.savedRideId ?: UUID.randomUUID().toString(),
            route = route.copy(savedRideId = route.savedRideId ?: UUID.randomUUID().toString()),
            destination = destination,
            timestamp = Instant.now(),
            label = label
        )
        save(ride)
    }

    fun remove(id: String) {
        preferences.edit().putString(KEY_RIDES, encode(all().filterNot { it.id == id })).apply()
    }

    private fun encode(rides: List<SavedRide>): String {
        val array = JSONArray()
        rides.forEach { ride ->
            array.put(
                JSONObject().apply {
                    put(KEY_ID, ride.id)
                    put(KEY_DESTINATION_LABEL, ride.destination.label)
                    put(KEY_DESTINATION_LAT, ride.destination.point.latitude)
                    put(KEY_DESTINATION_LON, ride.destination.point.longitude)
                    put(KEY_TIMESTAMP, ride.timestamp.toEpochMilli())
                    if (ride.label != null) put(KEY_LABEL, ride.label)
                    put(KEY_ROUTE_JSON, encodeRoute(ride.route))
                }
            )
        }
        return array.toString()
    }

    private fun encodeRoute(route: NavRoute): String {
        val obj = JSONObject()
        val pointsArray = JSONArray()
        route.points.forEach { p ->
            pointsArray.put(JSONObject().apply {
                put(KEY_LAT, p.latitude)
                put(KEY_LON, p.longitude)
            })
        }
        val maneuversArray = JSONArray()
        route.maneuvers.forEach { m ->
            maneuversArray.put(JSONObject().apply {
                put(KEY_MANEUVER_TYPE, m.type)
                put(KEY_MANEUVER_MODIFIER, m.modifier)
                put(KEY_MANEUVER_INSTRUCTION, m.instruction)
                put(KEY_MANEUVER_POINT_LAT, m.point.latitude)
                put(KEY_MANEUVER_POINT_LON, m.point.longitude)
                put(KEY_MANEUVER_DISTANCE_M, m.distanceMeters)
                put(KEY_MANEUVER_POINT_INDEX, m.pointIndex)
                put(KEY_MANEUVER_CURVINESS, m.curviness)
            })
        }
        obj.put(KEY_POINTS, pointsArray)
        obj.put(KEY_MANEUVERS, maneuversArray)
        obj.put(KEY_DISTANCE_M, route.distanceMeters)
        obj.put(KEY_DURATION_S, route.durationSeconds)

        val weather = route.weatherAtArrival
        if (weather != null) {
            obj.put(
                KEY_WEATHER,
                JSONObject().apply {
                    put(KEY_WEATHER_TEMP_C, weather.temperatureCelsius)
                    put(KEY_WEATHER_DESCRIPTION, weather.description)
                    put(KEY_WEATHER_CODE, weather.weatherCode)
                    put(KEY_WEATHER_TIMESTAMP, weather.timestamp.toEpochMilli())
                }
            )
        }
        val curvedSegmentsArray = JSONArray()
        route.curvedSegments.forEach { segment ->
            curvedSegmentsArray.put(
                JSONObject().apply {
                    put(KEY_CURVED_SEGMENT_START, segment.startPointIndex)
                    put(KEY_CURVED_SEGMENT_END, segment.endPointIndex)
                    put(KEY_CURVED_SEGMENT_CURVINESS, segment.averageCurviness)
                }
            )
        }
        obj.put(KEY_CURVED_SEGMENTS, curvedSegmentsArray)
        if (route.estimatedFuelRemainingKm != null) {
            obj.put(KEY_FUEL_REMAINING_KM, route.estimatedFuelRemainingKm)
        }
        if (route.minutesToGoldenHour != null) {
            obj.put(KEY_MINUTES_TO_GOLDEN_HOUR, route.minutesToGoldenHour)
        }
        return obj.toString()
    }

    private fun decode(serialized: String?): List<SavedRide> {
        if (serialized.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(serialized)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val id = item.getString(KEY_ID)
                    add(
                        SavedRide(
                            id = id,
                            route = decodeRoute(item.getString(KEY_ROUTE_JSON)).copy(savedRideId = id),
                            destination = NavPlace(
                                label = item.getString(KEY_DESTINATION_LABEL),
                                point = NavPoint(
                                    item.getDouble(KEY_DESTINATION_LAT),
                                    item.getDouble(KEY_DESTINATION_LON)
                                )
                            ),
                            timestamp = Instant.ofEpochMilli(item.getLong(KEY_TIMESTAMP)),
                            label = if (item.has(KEY_LABEL)) item.optString(KEY_LABEL).takeIf { it.isNotBlank() } else null
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun decodeRoute(routeJson: String): NavRoute {
        val obj = JSONObject(routeJson)
        val pointsArray = obj.getJSONArray(KEY_POINTS)
        val points = buildList {
            for (i in 0 until pointsArray.length()) {
                val p = pointsArray.getJSONObject(i)
                add(NavPoint(p.getDouble(KEY_LAT), p.getDouble(KEY_LON)))
            }
        }
        val maneuversArray = obj.getJSONArray(KEY_MANEUVERS)
        val maneuvers = buildList {
            for (i in 0 until maneuversArray.length()) {
                val m = maneuversArray.getJSONObject(i)
                add(
                    NavManeuver(
                        type = m.getString(KEY_MANEUVER_TYPE),
                        modifier = if (m.isNull(KEY_MANEUVER_MODIFIER)) null else m.optString(KEY_MANEUVER_MODIFIER).takeIf { it.isNotBlank() },
                        instruction = m.getString(KEY_MANEUVER_INSTRUCTION),
                        point = NavPoint(m.getDouble(KEY_MANEUVER_POINT_LAT), m.getDouble(KEY_MANEUVER_POINT_LON)),
                        distanceMeters = m.getDouble(KEY_MANEUVER_DISTANCE_M),
                        pointIndex = m.getInt(KEY_MANEUVER_POINT_INDEX),
                        curviness = m.optDouble(KEY_MANEUVER_CURVINESS, 0.0)
                    )
                )
            }
        }
        val weather = obj.optJSONObject(KEY_WEATHER)?.let { w ->
            NavWeather(
                temperatureCelsius = w.getDouble(KEY_WEATHER_TEMP_C),
                description = w.getString(KEY_WEATHER_DESCRIPTION),
                weatherCode = w.getInt(KEY_WEATHER_CODE),
                timestamp = Instant.ofEpochMilli(w.getLong(KEY_WEATHER_TIMESTAMP))
            )
        }
        val curvedSegmentsArray = obj.optJSONArray(KEY_CURVED_SEGMENTS)
        val curvedSegments = buildList {
            if (curvedSegmentsArray != null) {
                for (i in 0 until curvedSegmentsArray.length()) {
                    val s = curvedSegmentsArray.getJSONObject(i)
                    add(
                        CurvedSegment(
                            startPointIndex = s.getInt(KEY_CURVED_SEGMENT_START),
                            endPointIndex = s.getInt(KEY_CURVED_SEGMENT_END),
                            averageCurviness = s.getDouble(KEY_CURVED_SEGMENT_CURVINESS)
                        )
                    )
                }
            }
        }

        return NavRoute(
            points = points,
            maneuvers = maneuvers,
            distanceMeters = obj.getDouble(KEY_DISTANCE_M),
            durationSeconds = obj.getDouble(KEY_DURATION_S),
            weatherAtArrival = weather,
            curvedSegments = curvedSegments,
            estimatedFuelRemainingKm = if (obj.has(KEY_FUEL_REMAINING_KM)) obj.getDouble(KEY_FUEL_REMAINING_KM) else null,
            minutesToGoldenHour = if (obj.has(KEY_MINUTES_TO_GOLDEN_HOUR)) obj.getInt(KEY_MINUTES_TO_GOLDEN_HOUR) else null
        )
    }

    companion object {
        private const val PREFERENCES_NAME = "navigation_saved_rides"
        private const val KEY_RIDES = "rides"
        private const val KEY_ID = "id"
        private const val KEY_DESTINATION_LABEL = "dest_label"
        private const val KEY_DESTINATION_LAT = "dest_lat"
        private const val KEY_DESTINATION_LON = "dest_lon"
        private const val KEY_TIMESTAMP = "timestamp"
        private const val KEY_LABEL = "label"
        private const val KEY_ROUTE_JSON = "route_json"
        private const val KEY_POINTS = "points"
        private const val KEY_MANEUVERS = "maneuvers"
        private const val KEY_LAT = "lat"
        private const val KEY_LON = "lon"
        private const val KEY_MANEUVER_TYPE = "type"
        private const val KEY_MANEUVER_MODIFIER = "modifier"
        private const val KEY_MANEUVER_INSTRUCTION = "instruction"
        private const val KEY_MANEUVER_POINT_LAT = "point_lat"
        private const val KEY_MANEUVER_POINT_LON = "point_lon"
        private const val KEY_MANEUVER_DISTANCE_M = "distance_m"
        private const val KEY_MANEUVER_POINT_INDEX = "point_index"
        private const val KEY_MANEUVER_CURVINESS = "curviness"
        private const val KEY_DISTANCE_M = "distance_m"
        private const val KEY_DURATION_S = "duration_s"
        private const val KEY_WEATHER = "weather"
        private const val KEY_WEATHER_TEMP_C = "temp_c"
        private const val KEY_WEATHER_DESCRIPTION = "description"
        private const val KEY_WEATHER_CODE = "code"
        private const val KEY_WEATHER_TIMESTAMP = "timestamp"
        private const val KEY_CURVED_SEGMENTS = "curved_segments"
        private const val KEY_CURVED_SEGMENT_START = "start_index"
        private const val KEY_CURVED_SEGMENT_END = "end_index"
        private const val KEY_CURVED_SEGMENT_CURVINESS = "curviness"
        private const val KEY_FUEL_REMAINING_KM = "fuel_remaining_km"
        private const val KEY_MINUTES_TO_GOLDEN_HOUR = "minutes_to_golden_hour"
        private const val MAX_SAVED_RIDES = 50
    }
}
