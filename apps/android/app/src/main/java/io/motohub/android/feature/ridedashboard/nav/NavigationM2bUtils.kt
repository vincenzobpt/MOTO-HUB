package io.motohub.android.feature.ridedashboard.nav

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Calculate bearing (azimuth) between two points in degrees. */
fun bearingBetweenPoints(from: NavPoint, to: NavPoint): Double {
    val latFrom = Math.toRadians(from.latitude)
    val latTo = Math.toRadians(to.latitude)
    val dLon = Math.toRadians(to.longitude - from.longitude)

    val x = sin(dLon) * cos(latTo)
    val y = cos(latFrom) * sin(latTo) - sin(latFrom) * cos(latTo) * cos(dLon)

    return Math.toDegrees(atan2(x, y)).let { (it % 360 + 360) % 360 }
}

/** Compute curviness of a segment: abs angle change per km. Higher = more curved. */
fun segmentCurviness(points: List<NavPoint>, startIdx: Int, endIdx: Int): Double {
    if (endIdx - startIdx < 2) return 0.0

    var totalAngleChange = 0.0
    var totalDistanceMeters = 0.0

    for (i in startIdx until endIdx - 1) {
        val bearing1 = bearingBetweenPoints(points[i], points[i + 1])
        val bearing2 = bearingBetweenPoints(points[i + 1], points[i + 2])
        val angleChange = Math.abs(bearing2 - bearing1)
        val normalizedAngle = if (angleChange > 180) 360 - angleChange else angleChange
        totalAngleChange += normalizedAngle

        totalDistanceMeters += haversineDistanceMeters(points[i], points[i + 1])
    }

    return if (totalDistanceMeters > 0) {
        val anglePerKm = (totalAngleChange / (totalDistanceMeters / 1_000.0)).coerceIn(0.0, 180.0)
        anglePerKm / 180.0
    } else {
        0.0
    }
}

/**
 * Detect curved segments within a route and return them. Chunks the route
 * into ~250m spans, requiring at least 3 points per chunk since curvature
 * cannot be measured from fewer (a 2-point chunk has no angle to compare).
 */
fun detectCurvedSegments(
    points: List<NavPoint>,
    curvinessTreshold: Double = 0.3
): List<CurvedSegment> {
    if (points.size < 3) return emptyList()

    val segments = mutableListOf<CurvedSegment>()
    var segmentStart = 0
    var cumulativeDistance = 0.0

    for (i in 0 until points.size - 1) {
        cumulativeDistance += haversineDistanceMeters(points[i], points[i + 1])
        val endIdx = i + 1
        val hasEnoughPoints = endIdx - segmentStart >= 2

        if (cumulativeDistance >= MIN_SEGMENT_LENGTH_METERS && hasEnoughPoints) {
            val curviness = segmentCurviness(points, segmentStart, endIdx)
            if (curviness > curvinessTreshold) {
                segments.add(CurvedSegment(segmentStart, endIdx, curviness))
            }
            cumulativeDistance = 0.0
            segmentStart = endIdx
        }
    }

    // Flush a trailing chunk that never reached the length threshold but has
    // enough points to measure, so a curvy tail isn't silently dropped.
    if (points.lastIndex - segmentStart >= 2) {
        val curviness = segmentCurviness(points, segmentStart, points.lastIndex)
        if (curviness > curvinessTreshold) {
            segments.add(CurvedSegment(segmentStart, points.lastIndex, curviness))
        }
    }

    return segments
}

private const val MIN_SEGMENT_LENGTH_METERS = 250.0

/**
 * Minutes to the nearer golden-hour window (morning: sunrise to sunrise+60min;
 * evening: sunset-60min to sunset) from a location and arrival time. 0 means
 * arrival falls inside a golden-hour window; null if not calculable (e.g.
 * polar day/night). Considers both today's and tomorrow's windows so an
 * arrival late in the evening still finds tomorrow's sunrise window.
 */
fun minutesToGoldenHour(
    location: NavPoint,
    nowInstant: Instant,
    arrivalInstant: Instant
): Int? {
    return runCatching {
        val zoneId = ZoneId.systemDefault()
        val arrival = ZonedDateTime.ofInstant(arrivalInstant, zoneId)
        val date = arrival.toLocalDate()

        val windows = listOf(date.minusDays(1), date, date.plusDays(1)).flatMap { day ->
            val sunTimes = calculateSunTimes(day, location.latitude, location.longitude)
            listOf(
                sunTimes.first to sunTimes.first.plusMinutes(GOLDEN_HOUR_WINDOW_MINUTES),
                sunTimes.second.minusMinutes(GOLDEN_HOUR_WINDOW_MINUTES) to sunTimes.second
            )
        }

        val insideWindow = windows.any { (start, end) -> !arrival.isBefore(start) && !arrival.isAfter(end) }
        if (insideWindow) return@runCatching 0

        val nextWindowStart = windows
            .map { (start, _) -> start }
            .filter { it.isAfter(arrival) }
            .minOrNull() ?: return@runCatching null

        val minutes = java.time.temporal.ChronoUnit.MINUTES.between(arrival, nextWindowStart)
        minutes.toInt().takeIf { it in 0..1440 }
    }.getOrNull()
}

private const val GOLDEN_HOUR_WINDOW_MINUTES = 60L

/**
 * Calculate sunrise and sunset for a date and location, using NOAA's solar
 * position approximation (equation of time + solar declination from the
 * fractional year, then hour angle). See
 * https://gml.noaa.gov/grad/solcalc/solareqns.PDF. Returns (sunrise, sunset)
 * in system timezone. Falls back to local solar noon +/- 12h if the sun
 * never rises/sets that day (polar regions), since golden-hour is not
 * meaningful there anyway.
 */
private fun calculateSunTimes(
    date: LocalDate,
    latitude: Double,
    longitude: Double
): Pair<ZonedDateTime, ZonedDateTime> {
    val zoneId = ZoneId.systemDefault()
    val dayOfYear = date.dayOfYear
    val daysInYear = if (date.isLeapYear) 366.0 else 365.0

    // Fractional year gamma, in radians, evaluated at local noon (hour=12).
    val gamma = (2.0 * Math.PI / daysInYear) * (dayOfYear - 1 + (12.0 - 12.0) / 24.0)

    // Equation of time, in minutes.
    val eqTimeMinutes = 229.18 * (
        0.000075 +
            0.001868 * cos(gamma) -
            0.032077 * sin(gamma) -
            0.014615 * cos(2 * gamma) -
            0.040849 * sin(2 * gamma)
        )

    // Solar declination, in radians.
    val declRad = 0.006918 -
        0.399912 * cos(gamma) +
        0.070257 * sin(gamma) -
        0.006758 * cos(2 * gamma) +
        0.000907 * sin(2 * gamma) -
        0.002697 * cos(3 * gamma) +
        0.00148 * sin(3 * gamma)

    val latRad = Math.toRadians(latitude)
    // 90.833 degrees accounts for atmospheric refraction and the sun's
    // apparent radius at the horizon (standard sunrise/sunset convention).
    val cosHourAngle = (cos(Math.toRadians(90.833)) / (cos(latRad) * cos(declRad))) - tan(latRad) * tan(declRad)
    val hourAngleDegrees = Math.toDegrees(acos(cosHourAngle.coerceIn(-1.0, 1.0)))

    val sunriseMinutesUtc = 720.0 - 4.0 * (longitude + hourAngleDegrees) - eqTimeMinutes
    val sunsetMinutesUtc = 720.0 - 4.0 * (longitude - hourAngleDegrees) - eqTimeMinutes

    return Pair(
        ZonedDateTime.ofInstant(utcMinutesOfDayToInstant(date, sunriseMinutesUtc), zoneId),
        ZonedDateTime.ofInstant(utcMinutesOfDayToInstant(date, sunsetMinutesUtc), zoneId)
    )
}

/** Converts "minutes from UTC midnight of [date]" (may be negative or >1440) into an absolute Instant. */
private fun utcMinutesOfDayToInstant(date: LocalDate, minutesFromUtcMidnight: Double): Instant {
    val midnightUtc = date.atStartOfDay(ZoneId.of("UTC")).toInstant()
    val nanos = (minutesFromUtcMidnight * 60.0 * 1_000_000_000.0).toLong()
    return midnightUtc.plusNanos(nanos)
}

/** Estimate remaining fuel from route distance and fuel consumption. */
fun estimateRemainingFuel(
    routeDistanceKm: Double,
    currentFuelKm: Double,
    averageConsumptionKmPerLiter: Double = 30.0
): Double? {
    if (currentFuelKm <= 0) return null
    val consumedKm = routeDistanceKm / 1_000
    return (currentFuelKm - consumedKm).coerceAtLeast(0.0)
}

fun haversineDistanceMeters(a: NavPoint, b: NavPoint): Double {
    val latitudeDelta = Math.toRadians(b.latitude - a.latitude)
    val longitudeDelta = Math.toRadians(b.longitude - a.longitude)
    val firstLatitude = Math.toRadians(a.latitude)
    val secondLatitude = Math.toRadians(b.latitude)
    val h = Math.sin(latitudeDelta / 2).let { it * it } +
        Math.cos(firstLatitude) * Math.cos(secondLatitude) *
        Math.sin(longitudeDelta / 2).let { it * it }
    return 2 * 6_371_000.0 * Math.asin(Math.sqrt(h.coerceIn(0.0, 1.0)))
}
