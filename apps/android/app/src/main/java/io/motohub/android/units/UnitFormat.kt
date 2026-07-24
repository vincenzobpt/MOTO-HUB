package io.motohub.android.units

import io.motohub.android.feature.settings.DistanceUnits
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Single source of truth for converting the app's metric-native telemetry
 * (km/h, meters) into the user's preferred display units. Models, storage and
 * telemetry stay metric everywhere; conversion happens only at presentation.
 */
object UnitFormat {
    const val METERS_PER_MILE = 1_609.344
    const val METERS_PER_FOOT = 0.3048
    const val KPH_PER_MPH = 1.609344

    /** Converts a speed expressed in km/h to the display unit's numeric value. */
    fun speed(kph: Float, units: DistanceUnits): Float = when (units) {
        DistanceUnits.KILOMETERS -> kph
        DistanceUnits.MILES -> (kph / KPH_PER_MPH).toFloat()
    }

    fun speed(kph: Double, units: DistanceUnits): Double = when (units) {
        DistanceUnits.KILOMETERS -> kph
        DistanceUnits.MILES -> kph / KPH_PER_MPH
    }

    /** Uppercase speed-unit label as drawn on the TFT dashboard. */
    fun speedLabel(units: DistanceUnits): String = when (units) {
        DistanceUnits.KILOMETERS -> "KM/H"
        DistanceUnits.MILES -> "MPH"
    }

    /** Lowercase speed-unit label used in the phone UI. */
    fun speedLabelLower(units: DistanceUnits): String = when (units) {
        DistanceUnits.KILOMETERS -> "km/h"
        DistanceUnits.MILES -> "mph"
    }

    /** "12.3 km" / "850 m" — or "7.7 mi" / "600 ft" in miles mode. */
    fun distance(meters: Double, units: DistanceUnits): String = when (units) {
        DistanceUnits.KILOMETERS ->
            if (meters >= 1_000.0) String.format(Locale.US, "%.1f km", meters / 1_000.0)
            else "${meters.roundToInt()} m"
        DistanceUnits.MILES -> {
            val miles = meters / METERS_PER_MILE
            if (miles >= 0.1) String.format(Locale.US, "%.1f mi", miles)
            else "${(meters / METERS_PER_FOOT).roundToInt()} ft"
        }
    }

    /**
     * Precision steps down as the distance grows: three significant-ish digits
     * for the dashboard navigation widget's maneuver/remaining readouts.
     */
    fun distanceCompact(meters: Double, units: DistanceUnits): String = when (units) {
        DistanceUnits.KILOMETERS -> when {
            meters >= 100_000.0 -> String.format(Locale.US, "%.0f km", meters / 1_000.0)
            meters >= 10_000.0 -> String.format(Locale.US, "%.1f km", meters / 1_000.0)
            meters >= 1_000.0 -> String.format(Locale.US, "%.2f km", meters / 1_000.0)
            else -> "${meters.roundToInt()} m"
        }
        DistanceUnits.MILES -> {
            val miles = meters / METERS_PER_MILE
            when {
                miles >= 100.0 -> String.format(Locale.US, "%.0f mi", miles)
                miles >= 10.0 -> String.format(Locale.US, "%.1f mi", miles)
                miles >= 0.1 -> String.format(Locale.US, "%.2f mi", miles)
                else -> "${(meters / METERS_PER_FOOT).roundToInt()} ft"
            }
        }
    }

    /** Value-only distance for widgets that draw the unit label on its own line. */
    fun distanceValue(meters: Double, units: DistanceUnits): String = when (units) {
        DistanceUnits.KILOMETERS -> when {
            meters >= 10_000.0 -> String.format(Locale.US, "%.0f", meters / 1_000.0)
            meters >= 1_000.0 -> String.format(Locale.US, "%.1f", meters / 1_000.0)
            else -> meters.roundToInt().toString()
        }
        DistanceUnits.MILES -> {
            val miles = meters / METERS_PER_MILE
            when {
                miles >= 10.0 -> String.format(Locale.US, "%.0f", miles)
                miles >= 0.1 -> String.format(Locale.US, "%.1f", miles)
                else -> (meters / METERS_PER_FOOT).roundToInt().toString()
            }
        }
    }

    /** Companion to [distanceValue]: the uppercase unit the value was rendered in. */
    fun distanceValueLabel(meters: Double, units: DistanceUnits): String = when (units) {
        DistanceUnits.KILOMETERS -> if (meters >= 1_000.0) "KM" else "M"
        DistanceUnits.MILES -> if (meters / METERS_PER_MILE >= 0.1) "MI" else "FT"
    }

    /** Kilometers → the display unit's distance value (km or mi). */
    fun distanceFromKm(km: Double, units: DistanceUnits): Double = when (units) {
        DistanceUnits.KILOMETERS -> km
        DistanceUnits.MILES -> km / KPH_PER_MPH
    }

    /** Meters → the display unit's large-distance value (km or mi), e.g. odometers. */
    fun distanceFromMeters(meters: Double, units: DistanceUnits): Double = when (units) {
        DistanceUnits.KILOMETERS -> meters / 1_000.0
        DistanceUnits.MILES -> meters / METERS_PER_MILE
    }

    /** Kilometers → the display unit's whole-number distance (e.g. range estimates). */
    fun wholeDistanceFromKm(km: Double, units: DistanceUnits): Int =
        distanceFromKm(km, units).roundToInt()

    /** Inverse of [distanceFromKm]: a user-entered distance back to km for storage. */
    fun kmFromDistance(value: Double, units: DistanceUnits): Double = when (units) {
        DistanceUnits.KILOMETERS -> value
        DistanceUnits.MILES -> value * KPH_PER_MPH
    }

    /** Lowercase distance-unit label for "N km"/"N mi" style sentences. */
    fun wholeDistanceLabel(units: DistanceUnits): String = when (units) {
        DistanceUnits.KILOMETERS -> "km"
        DistanceUnits.MILES -> "mi"
    }

    /** Altitude in the display unit, rounded: meters, or feet in miles mode. */
    fun altitudeValue(meters: Double, units: DistanceUnits): Int = when (units) {
        DistanceUnits.KILOMETERS -> meters.roundToInt()
        DistanceUnits.MILES -> (meters / METERS_PER_FOOT).roundToInt()
    }

    /** Uppercase altitude-unit label: "M" or "FT". */
    fun altitudeLabel(units: DistanceUnits): String = when (units) {
        DistanceUnits.KILOMETERS -> "M"
        DistanceUnits.MILES -> "FT"
    }
}
