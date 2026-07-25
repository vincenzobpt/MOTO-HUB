package io.motohub.android.feature.ridedashboard.widget

import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.math.*

/**
 * Computes sunrise/sunset time, remaining daylight and the sun's current
 * position using the NOAA-standard solar position approximation. Accurate
 * to within ±5 minutes for dashboard purposes — no network calls, no
 * permissions.
 */
object SunsetCalculator {

    /** The next meaningful daylight boundary for the current local date. */
    data class DaylightCountdown(
        val duration: Duration,
        /** True when the countdown ends at sunrise; false when it ends at sunset. */
        val untilSunrise: Boolean
    )

    /** Point on a horizon-arc sun-path visualization. */
    data class SunArcPoint(
        /** 0 = sunrise (left), 0.5 = solar noon (top), 1 = sunset (right). */
        val xFraction: Float,
        /** Elevation angle normalized against today's solar-noon peak; 0 = horizon, 1 = peak. */
        val heightFraction: Float,
        /** Raw elevation angle in degrees (negative when below the horizon). */
        val elevationDeg: Float,
        val isDaytime: Boolean
    )

    private class SolarParams(
        val latitude: Double,
        val declinationRad: Double,
        /** Half-day hour angle in degrees: the sun's hour angle at sunrise/sunset. */
        val halfDayHourAngleDeg: Double,
        val eotMinutes: Double
    )

    /** Shared declination / hour-angle / equation-of-time terms for a given day and latitude. */
    private fun solarParams(latitude: Double, dayOfYear: Int): SolarParams? {
        val latRad = Math.toRadians(latitude)
        val declination = 0.4095 * sin(2.0 * PI / 365.0 * (dayOfYear - 80.25))

        val cosHA = -sin(latRad) * sin(declination) / (cos(latRad) * cos(declination))
        if (cosHA < -1.0 || cosHA > 1.0) return null // polar day/night

        val haDeg = Math.toDegrees(acos(cosHA))

        val gamma = 2.0 * PI / 365.0 * (dayOfYear - 1)
        val eot = 229.18 * (
            0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) -
                0.014615 * cos(2.0 * gamma) - 0.04089 * sin(2.0 * gamma)
            )

        return SolarParams(latitude, declination, haDeg, eot)
    }

    private fun localTimeFromHourAngle(params: SolarParams, longitude: Double, now: ZonedDateTime, hourAngleDeg: Double): LocalTime {
        val zoneOff = now.offset.totalSeconds / 3600.0
        val localHours = 12.0 - longitude / 15.0 + hourAngleDeg / 15.0 - params.eotMinutes / 60.0 + zoneOff
        val normalized = ((localHours % 24.0) + 24.0) % 24.0
        val h = normalized.toInt().coerceIn(0, 23)
        val m = ((normalized - h) * 60.0).toInt().coerceIn(0, 59)
        val s = (((normalized - h) * 60.0 - m) * 60.0).toInt().coerceIn(0, 59)
        return LocalTime.of(h, m, s)
    }

    /**
     * Calculates local sunset time for the given position and date.
     * @return sunset as [LocalTime], or `null` if the sun never sets
     *         (midnight sun above the Arctic/Antarctic circle).
     */
    fun sunsetTime(latitude: Double, longitude: Double, now: ZonedDateTime): LocalTime? {
        val params = solarParams(latitude, now.dayOfYear) ?: return null
        return localTimeFromHourAngle(params, longitude, now, params.halfDayHourAngleDeg)
    }

    /**
     * Calculates local sunrise time for the given position and date.
     * @return sunrise as [LocalTime], or `null` if the sun never rises/sets
     *         (midnight sun above the Arctic/Antarctic circle).
     */
    fun sunriseTime(latitude: Double, longitude: Double, now: ZonedDateTime): LocalTime? {
        val params = solarParams(latitude, now.dayOfYear) ?: return null
        return localTimeFromHourAngle(params, longitude, now, -params.halfDayHourAngleDeg)
    }

    /**
     * Returns the remaining daylight duration from [now] until [sunset].
     * @return duration, [Duration.ZERO] if it's already past sunset, or
     *         `null` if [sunset] is null.
     */
    fun remainingDaylight(now: ZonedDateTime, sunset: LocalTime?): Duration? {
        val s = sunset ?: return null
        val sunsetDt = now.with(s)
        return if (sunsetDt.isBefore(now)) Duration.ZERO
        else Duration.between(now, sunsetDt)
    }

    /**
     * Returns the correct next daylight transition across midnight. Before today's sunrise
     * (including 00:00–05:00) the countdown is to sunrise, during daylight it is to sunset,
     * and after sunset it is to tomorrow's sunrise. The previous implementation always used
     * today's sunset, which made the widget claim that many hours of daylight remained while
     * the rider was actually in the middle of the night.
     */
    fun daylightCountdown(
        latitude: Double,
        longitude: Double,
        now: ZonedDateTime
    ): DaylightCountdown? {
        val sunriseToday = sunriseTime(latitude, longitude, now)?.let(now::with) ?: return null
        val sunsetToday = sunsetTime(latitude, longitude, now)?.let(now::with) ?: return null
        return when {
            now.isBefore(sunriseToday) -> DaylightCountdown(
                Duration.between(now, sunriseToday),
                untilSunrise = true
            )
            now.isBefore(sunsetToday) -> DaylightCountdown(
                Duration.between(now, sunsetToday),
                untilSunrise = false
            )
            else -> {
                val tomorrow = now.plusDays(1)
                val sunriseTomorrow = sunriseTime(latitude, longitude, tomorrow)
                    ?.let(tomorrow::with)
                    ?: return null
                DaylightCountdown(
                    Duration.between(now, sunriseTomorrow),
                    untilSunrise = true
                )
            }
        }
    }

    /**
     * Computes the sun's current position for a horizon-arc visualization.
     * The arc runs from sunrise (x=0) through solar noon (x=0.5, the peak)
     * to sunset (x=1); [SunArcPoint.heightFraction] reflects how high the
     * sun actually gets today at this latitude/season (flatter arc in
     * winter, taller in summer).
     * @return `null` for polar day/night (the sun never crosses the horizon).
     */
    fun sunArcPosition(latitude: Double, longitude: Double, now: ZonedDateTime): SunArcPoint? {
        val params = solarParams(latitude, now.dayOfYear) ?: return null
        val zoneOff = now.offset.totalSeconds / 3600.0
        val localNoonHours = 12.0 - longitude / 15.0 - params.eotMinutes / 60.0 + zoneOff
        val nowHours = now.hour + now.minute / 60.0 + now.second / 3600.0

        // 15° of hour angle per hour away from local solar noon.
        val haNowDeg = (nowHours - localNoonHours) * 15.0
        val haNowRad = Math.toRadians(haNowDeg)
        val latRad = Math.toRadians(params.latitude)
        val elevationRad = asin(
            sin(latRad) * sin(params.declinationRad) +
                cos(latRad) * cos(params.declinationRad) * cos(haNowRad)
        )
        val elevationDeg = Math.toDegrees(elevationRad)

        // Elevation at solar noon: 90° minus the angular distance between
        // latitude and declination.
        val noonElevationDeg = 90.0 - abs(params.latitude - Math.toDegrees(params.declinationRad))

        val halfDay = params.halfDayHourAngleDeg
        val xFraction = ((haNowDeg + halfDay) / (2.0 * halfDay)).coerceIn(0.0, 1.0)
        val heightFraction = if (noonElevationDeg > 0.0) {
            (elevationDeg / noonElevationDeg).coerceIn(0.0, 1.0)
        } else {
            0.0
        }

        return SunArcPoint(
            xFraction = xFraction.toFloat(),
            heightFraction = heightFraction.toFloat(),
            elevationDeg = elevationDeg.toFloat(),
            isDaytime = elevationDeg > 0.0
        )
    }
}
