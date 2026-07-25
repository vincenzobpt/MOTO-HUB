package io.motohub.android.feature.ridedashboard.nav

import java.time.Instant

/** A WGS84 point used by geocoding, routing and rendering. */
data class NavPoint(val latitude: Double, val longitude: Double)

/** A single turn-by-turn instruction along a route. */
data class NavManeuver(
    val type: String,
    val modifier: String?,
    val instruction: String,
    val point: NavPoint,
    val distanceMeters: Double,
    /** Index into the owning [NavRoute.points] where this maneuver begins. */
    val pointIndex: Int,
    /** Average curvature on this segment (0=straight, 1=maximum); used for highlighting. */
    val curviness: Double = 0.0
)

/** Weather snapshot at a specific time and location. */
data class NavWeather(
    val temperatureCelsius: Double,
    val description: String,
    val weatherCode: Int,
    val timestamp: Instant
)

/** A curved segment within a route; hints to the renderer for highlighting. */
data class CurvedSegment(
    val startPointIndex: Int,
    val endPointIndex: Int,
    val averageCurviness: Double
)

/** A calculated route: full shape for drawing, maneuvers for guidance, motorcycle-specific enrichments for M2b. */
data class NavRoute(
    val points: List<NavPoint>,
    val maneuvers: List<NavManeuver>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    /** Estimated weather at arrival (M2b). */
    val weatherAtArrival: NavWeather? = null,
    /** Curved segments to highlight (M2b); sorted by startPointIndex. */
    val curvedSegments: List<CurvedSegment> = emptyList(),
    /** Estimate remaining fuel in kilometers (M2b); null if no tank range configured. */
    val estimatedFuelRemainingKm: Double? = null,
    /** Golden-hour guidance: minutes to golden-hour (positive=upcoming, negative=passed, null=N/A). */
    val minutesToGoldenHour: Int? = null,
    /** Unique ID for saved rides; null if not persisted yet. */
    val savedRideId: String? = null
)

/** A geocoding search result the user can pick as a destination. */
data class NavPlace(
    val label: String,
    val point: NavPoint
)

/** A saved route that can be re-navigated; includes metadata for display and quick-nav. */
data class SavedRide(
    val id: String,
    val route: NavRoute,
    val destination: NavPlace,
    val timestamp: Instant,
    val label: String? = null
)
