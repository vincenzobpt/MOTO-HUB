package io.motohub.android.feature.trips

import io.motohub.android.feature.settings.DistanceUnits
import io.motohub.android.units.UnitFormat
import java.util.Locale

enum class TripRecordingSource(val label: String) {
    MANUAL("Manual recording"),
    MIRRORING("Mirroring"),
    ANDROID_AUTO("Android Auto"),
    RIDE_DASHBOARD("Ride Dashboard"),
    NAVIGATION("Navigation");

    companion object {
        fun fromStorage(value: String): TripRecordingSource =
            entries.firstOrNull { it.name == value } ?: MANUAL
    }
}

data class TripTrackPoint(
    val sequence: Int,
    val latitude: Double,
    val longitude: Double,
    val timestampMillis: Long,
    val speedMetersPerSecond: Float,
    val accuracyMeters: Float,
    val altitudeMeters: Double?
)

data class TripSummary(
    val id: String,
    val name: String?,
    val motorcycleId: String?,
    val source: TripRecordingSource,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
    val distanceMeters: Double,
    val movingTimeMillis: Long,
    val elapsedTimeMillis: Long,
    val maxSpeedMetersPerSecond: Float,
    val pointCount: Int,
    val minLatitude: Double?,
    val maxLatitude: Double?,
    val minLongitude: Double?,
    val maxLongitude: Double?,
    val active: Boolean
) {
    val averageSpeedKmh: Float
        get() = if (movingTimeMillis > 0L) {
            (distanceMeters / (movingTimeMillis / 1_000.0) * 3.6).toFloat()
        } else {
            0f
        }

    val maxSpeedKmh: Float get() = maxSpeedMetersPerSecond * 3.6f
}

data class TripDetails(
    val summary: TripSummary,
    val points: List<TripTrackPoint>
)

data class TripLibraryStats(
    val tripCount: Int = 0,
    val totalDistanceMeters: Double = 0.0,
    val totalMovingTimeMillis: Long = 0L
)

internal data class TripLocationSample(
    val latitude: Double,
    val longitude: Double,
    val timestampMillis: Long,
    val elapsedRealtimeNanos: Long,
    val speedMetersPerSecond: Float?,
    val accuracyMeters: Float?,
    val altitudeMeters: Double?
)

internal data class TripRecordingSnapshot(
    val distanceMeters: Double,
    val movingTimeMillis: Long,
    val elapsedTimeMillis: Long,
    val maxSpeedMetersPerSecond: Float,
    val currentSpeedMetersPerSecond: Float,
    val pointCount: Int,
    val accuracyMeters: Float?,
    val hasFix: Boolean,
    val minLatitude: Double?,
    val maxLatitude: Double?,
    val minLongitude: Double?,
    val maxLongitude: Double?
)

fun formatTripDistance(distanceMeters: Double, units: DistanceUnits): String =
    UnitFormat.distance(distanceMeters, units)

fun formatTripDuration(durationMillis: Long): String {
    val seconds = (durationMillis / 1_000L).coerceAtLeast(0L)
    val hours = seconds / 3_600L
    val minutes = (seconds % 3_600L) / 60L
    val remainingSeconds = seconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, remainingSeconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, remainingSeconds)
    }
}
