package io.motohub.android.feature.ridedashboard.nav

import io.motohub.android.feature.ridedashboard.haversineDistanceMeters
import kotlin.math.cos

/** Live progress of a rider along a [NavRoute], recomputed on every position fix. */
data class NavigationProgress(
    val distanceRemainingMeters: Double,
    val currentManeuver: NavManeuver?,
    val distanceToManeuverMeters: Double,
    val offRoute: Boolean
)

enum class ManeuverDirection { STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, UTURN, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, ROUNDABOUT, ARRIVE }

/**
 * Maps Valhalla's numeric maneuver type (DirectionsLeg_Maneuver_Type) to a
 * coarse direction for drawing an arrow. Unmapped/unknown types default to
 * straight; a full icon set per exact type is M3 polish.
 */
fun maneuverDirection(valhallaType: String): ManeuverDirection = when (valhallaType.toIntOrNull()) {
    4, 5, 6 -> ManeuverDirection.ARRIVE
    9, 22, 23, 37 -> ManeuverDirection.SLIGHT_RIGHT
    10, 18, 20 -> ManeuverDirection.RIGHT
    11 -> ManeuverDirection.SHARP_RIGHT
    12, 13 -> ManeuverDirection.UTURN
    14 -> ManeuverDirection.SHARP_LEFT
    15, 19 -> ManeuverDirection.LEFT
    16, 24, 38 -> ManeuverDirection.SLIGHT_LEFT
    26, 27 -> ManeuverDirection.ROUNDABOUT
    else -> ManeuverDirection.STRAIGHT
}

/**
 * Tracks a rider's progress along a calculated [NavRoute]: map-matches the
 * live position onto the route polyline, derives remaining distance and the
 * active maneuver, and flags off-route with hysteresis so GNSS jitter alone
 * cannot trigger a reroute.
 */
class NavigationEngine(private val route: NavRoute) {
    private val cumulativeMeters: DoubleArray = buildCumulativeDistances(route.points)
    private val totalMeters: Double = cumulativeMeters.lastOrNull() ?: 0.0
    private var offRouteStreak = 0

    fun update(position: NavPoint): NavigationProgress {
        if (route.points.size < 2) {
            return NavigationProgress(0.0, route.maneuvers.firstOrNull(), 0.0, offRoute = false)
        }

        val nearest = nearestPointOnRoute(position)
        val progressMeters = cumulativeMeters[nearest.segmentStartIndex] +
            (cumulativeMeters[nearest.segmentStartIndex + 1] - cumulativeMeters[nearest.segmentStartIndex]) *
            nearest.fractionAlongSegment
        val distanceRemaining = (totalMeters - progressMeters).coerceAtLeast(0.0)

        val currentManeuver = route.maneuvers.firstOrNull { maneuver ->
            maneuverProgressMeters(maneuver) >= progressMeters - MANEUVER_PASSED_TOLERANCE_METERS
        }
        val distanceToManeuver = currentManeuver
            ?.let { (maneuverProgressMeters(it) - progressMeters).coerceAtLeast(0.0) }
            ?: distanceRemaining

        val isOffRouteNow = nearest.perpendicularDistanceMeters > OFF_ROUTE_THRESHOLD_METERS
        offRouteStreak = if (isOffRouteNow) offRouteStreak + 1 else 0

        return NavigationProgress(
            distanceRemainingMeters = distanceRemaining,
            currentManeuver = currentManeuver,
            distanceToManeuverMeters = distanceToManeuver,
            offRoute = offRouteStreak >= OFF_ROUTE_STREAK_THRESHOLD
        )
    }

    private fun maneuverProgressMeters(maneuver: NavManeuver): Double =
        cumulativeMeters.getOrElse(maneuver.pointIndex) { totalMeters }

    /** Projects [position] onto the nearest route segment using a local equirectangular approximation. */
    private fun nearestPointOnRoute(position: NavPoint): NearestPoint {
        var bestIndex = 0
        var bestFraction = 0.0
        var bestDistanceMeters = Double.MAX_VALUE
        val longitudeScale = cos(Math.toRadians(position.latitude))

        for (index in 0 until route.points.size - 1) {
            val start = route.points[index]
            val end = route.points[index + 1]
            val startX = start.longitude * longitudeScale
            val startY = start.latitude
            val deltaX = end.longitude * longitudeScale - startX
            val deltaY = end.latitude - startY
            val lengthSquared = deltaX * deltaX + deltaY * deltaY
            val fraction = if (lengthSquared > 0.0) {
                (((position.longitude * longitudeScale - startX) * deltaX + (position.latitude - startY) * deltaY) / lengthSquared)
                    .coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            val projectedLatitude = startY + fraction * deltaY
            val projectedLongitude = (startX + fraction * deltaX) / longitudeScale
            val distanceMeters = haversineDistanceMeters(
                position.latitude,
                position.longitude,
                projectedLatitude,
                projectedLongitude
            )
            if (distanceMeters < bestDistanceMeters) {
                bestDistanceMeters = distanceMeters
                bestIndex = index
                bestFraction = fraction
            }
        }
        return NearestPoint(bestIndex, bestFraction, bestDistanceMeters)
    }

    private data class NearestPoint(
        val segmentStartIndex: Int,
        val fractionAlongSegment: Double,
        val perpendicularDistanceMeters: Double
    )

    private companion object {
        const val OFF_ROUTE_THRESHOLD_METERS = 40.0
        const val OFF_ROUTE_STREAK_THRESHOLD = 3
        const val MANEUVER_PASSED_TOLERANCE_METERS = 15.0
    }
}

private fun buildCumulativeDistances(points: List<NavPoint>): DoubleArray {
    if (points.isEmpty()) return DoubleArray(0)
    val result = DoubleArray(points.size)
    for (index in 1 until points.size) {
        result[index] = result[index - 1] + haversineDistanceMeters(
            points[index - 1].latitude,
            points[index - 1].longitude,
            points[index].latitude,
            points[index].longitude
        )
    }
    return result
}
