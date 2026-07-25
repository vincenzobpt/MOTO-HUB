package io.motohub.android.feature.trips

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

internal class TripAccumulator(
    private val startedAtMillis: Long,
    seed: TripSummary? = null,
    lastPersistedPoint: TripTrackPoint? = null
) {
    private var lastSample: TripLocationSample? = lastPersistedPoint?.let { point ->
        TripLocationSample(
            latitude = point.latitude,
            longitude = point.longitude,
            timestampMillis = point.timestampMillis,
            elapsedRealtimeNanos = 0L,
            speedMetersPerSecond = point.speedMetersPerSecond,
            accuracyMeters = point.accuracyMeters,
            altitudeMeters = point.altitudeMeters
        )
    }
    private var lastStoredSample = lastSample
    private var distanceMeters = seed?.distanceMeters ?: 0.0
    private var movingTimeMillis = seed?.movingTimeMillis ?: 0L
    private var maxSpeedMetersPerSecond = seed?.maxSpeedMetersPerSecond ?: 0f
    private var pointCount = seed?.pointCount ?: 0
    private var currentSpeedMetersPerSecond = 0f
    private var accuracyMeters: Float? = null
    private var hasFix = false
    private var minLatitude = seed?.minLatitude
    private var maxLatitude = seed?.maxLatitude
    private var minLongitude = seed?.minLongitude
    private var maxLongitude = seed?.maxLongitude

    fun accept(sample: TripLocationSample): TripTrackPoint? {
        if (!sample.latitude.isFinite() || !sample.longitude.isFinite() ||
            sample.latitude !in -90.0..90.0 || sample.longitude !in -180.0..180.0
        ) return null
        val accuracy = sample.accuracyMeters
        if (accuracy != null && (!accuracy.isFinite() || accuracy > MAX_ACCEPTED_ACCURACY_METERS)) {
            return null
        }

        val previous = lastSample
        val elapsedMillis = previous?.let { elapsedMillis(it, sample) } ?: 0L
        val segmentDistance = previous?.let {
            haversineMeters(it.latitude, it.longitude, sample.latitude, sample.longitude)
        } ?: 0.0
        val derivedSpeed = if (elapsedMillis in 1..MAX_SEGMENT_GAP_MILLIS) {
            (segmentDistance / (elapsedMillis / 1_000.0)).toFloat()
        } else {
            0f
        }
        val reportedSpeed = sample.speedMetersPerSecond
            ?.takeIf { it.isFinite() && it in 0f..MAX_PLAUSIBLE_SPEED_METERS_PER_SECOND }
        val speed = (reportedSpeed ?: derivedSpeed).coerceAtLeast(0f)

        if (previous != null && elapsedMillis in 1..MAX_SEGMENT_GAP_MILLIS &&
            derivedSpeed > MAX_PLAUSIBLE_SPEED_METERS_PER_SECOND
        ) {
            return null
        }

        val moving = speed >= MIN_MOVING_SPEED_METERS_PER_SECOND
        if (previous != null && moving && elapsedMillis in 1..MAX_SEGMENT_GAP_MILLIS &&
            segmentDistance >= MIN_DISTANCE_INCREMENT_METERS
        ) {
            distanceMeters += segmentDistance
            movingTimeMillis += elapsedMillis
        }
        maxSpeedMetersPerSecond = maxOf(maxSpeedMetersPerSecond, speed)
        currentSpeedMetersPerSecond = speed
        accuracyMeters = accuracy
        hasFix = true
        minLatitude = minLatitude?.let { min(it, sample.latitude) } ?: sample.latitude
        maxLatitude = maxLatitude?.let { maxOf(it, sample.latitude) } ?: sample.latitude
        minLongitude = minLongitude?.let { min(it, sample.longitude) } ?: sample.longitude
        maxLongitude = maxLongitude?.let { maxOf(it, sample.longitude) } ?: sample.longitude
        lastSample = sample

        val distanceFromStored = lastStoredSample?.let {
            haversineMeters(it.latitude, it.longitude, sample.latitude, sample.longitude)
        }
        val timeFromStored = lastStoredSample?.let { sample.timestampMillis - it.timestampMillis }
        val shouldStore = lastStoredSample == null ||
            (distanceFromStored ?: 0.0) >= MIN_STORED_POINT_DISTANCE_METERS ||
            (timeFromStored ?: Long.MAX_VALUE) >= MAX_STORED_POINT_INTERVAL_MILLIS
        if (!shouldStore) return null

        val point = TripTrackPoint(
            sequence = pointCount,
            latitude = sample.latitude,
            longitude = sample.longitude,
            timestampMillis = sample.timestampMillis,
            speedMetersPerSecond = speed,
            accuracyMeters = accuracy ?: 0f,
            altitudeMeters = sample.altitudeMeters
        )
        pointCount++
        lastStoredSample = sample
        return point
    }

    fun snapshot(nowMillis: Long = System.currentTimeMillis()): TripRecordingSnapshot =
        TripRecordingSnapshot(
            distanceMeters = distanceMeters,
            movingTimeMillis = movingTimeMillis,
            elapsedTimeMillis = (nowMillis - startedAtMillis).coerceAtLeast(0L),
            maxSpeedMetersPerSecond = maxSpeedMetersPerSecond,
            currentSpeedMetersPerSecond = currentSpeedMetersPerSecond,
            pointCount = pointCount,
            accuracyMeters = accuracyMeters,
            hasFix = hasFix,
            minLatitude = minLatitude,
            maxLatitude = maxLatitude,
            minLongitude = minLongitude,
            maxLongitude = maxLongitude
        )

    private fun elapsedMillis(first: TripLocationSample, second: TripLocationSample): Long {
        if (first.elapsedRealtimeNanos > 0L && second.elapsedRealtimeNanos > first.elapsedRealtimeNanos) {
            return (second.elapsedRealtimeNanos - first.elapsedRealtimeNanos) / 1_000_000L
        }
        return second.timestampMillis - first.timestampMillis
    }

    companion object {
        private const val EARTH_RADIUS_METERS = 6_371_000.0
        private const val MAX_ACCEPTED_ACCURACY_METERS = 60f
        private const val MAX_PLAUSIBLE_SPEED_METERS_PER_SECOND = 80f
        private const val MAX_SEGMENT_GAP_MILLIS = 20_000L
        private const val MIN_MOVING_SPEED_METERS_PER_SECOND = 0.8f
        private const val MIN_DISTANCE_INCREMENT_METERS = 1.5
        private const val MIN_STORED_POINT_DISTANCE_METERS = 4.0
        private const val MAX_STORED_POINT_INTERVAL_MILLIS = 10_000L

        internal fun haversineMeters(
            firstLatitude: Double,
            firstLongitude: Double,
            secondLatitude: Double,
            secondLongitude: Double
        ): Double {
            val latitudeDelta = Math.toRadians(secondLatitude - firstLatitude)
            val longitudeDelta = Math.toRadians(secondLongitude - firstLongitude)
            val firstLatitudeRadians = Math.toRadians(firstLatitude)
            val secondLatitudeRadians = Math.toRadians(secondLatitude)
            val a = sin(latitudeDelta / 2.0) * sin(latitudeDelta / 2.0) +
                cos(firstLatitudeRadians) * cos(secondLatitudeRadians) *
                sin(longitudeDelta / 2.0) * sin(longitudeDelta / 2.0)
            return 2.0 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
        }
    }
}
