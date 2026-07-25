package io.motohub.android.feature.ridedashboard.nav

import io.motohub.android.feature.settings.DistanceUnits
import io.motohub.android.units.UnitFormat
import java.util.Locale
import kotlin.math.roundToInt

/** Human-facing formatting for the phone navigation UI, honoring the unit preference. */
object NavFormat {

    fun distance(meters: Double, units: DistanceUnits): String = UnitFormat.distance(meters, units)

    fun duration(seconds: Double): String {
        val totalMinutes = (seconds / 60.0).roundToInt()
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes} min"
    }

    /** Local clock time of arrival, given "now" in epoch millis and remaining seconds. */
    fun arrivalClock(nowEpochMillis: Long, remainingSeconds: Double): String {
        val arrivalMillis = nowEpochMillis + (remainingSeconds * 1_000L).toLong()
        val totalMinutesOfDay = ((arrivalMillis / 60_000L) % (24L * 60L)).toInt()
        val hours = totalMinutesOfDay / 60
        val minutes = totalMinutesOfDay % 60
        return String.format(Locale.US, "%02d:%02d", hours, minutes)
    }
}
