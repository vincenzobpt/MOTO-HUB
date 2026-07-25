package io.motohub.android.feature.ridedashboard

import android.graphics.Bitmap
import android.os.SystemClock
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class RideGeoPoint(
    val latitude: Double,
    val longitude: Double
)

data class RideLocationSample(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val elapsedRealtimeMillis: Long,
    val speedMetersPerSecond: Float? = null,
    val bearingDegrees: Float? = null,
    val altitudeMeters: Double? = null
)

/** A GNSS elevation sample expressed on the dashboard session timeline. */
data class RideAltitudeSample(
    val elapsedMillis: Long,
    val altitudeMeters: Double
)

data class RideTelemetrySnapshot(
    val hasFix: Boolean = false,
    val lastFixElapsedRealtimeMillis: Long? = null,
    val speedKph: Float = 0f,
    val bearingDegrees: Float? = null,
    /** Absolute phone/device heading from the rotation-vector sensor, when available. */
    val deviceHeadingDegrees: Float? = null,
    val altitudeMeters: Double? = null,
    /** Change from the first valid altitude received in this dashboard session. */
    val altitudeChangeMeters: Double = 0.0,
    /** Lowest valid altitude received in this dashboard session. */
    val minAltitudeMeters: Double? = null,
    /** Highest valid altitude received in this dashboard session. */
    val maxAltitudeMeters: Double? = null,
    /** Smoothed vertical speed in metres per second; positive means climbing. */
    val verticalSpeedMps: Float? = null,
    val accuracyMeters: Float? = null,
    val position: RideGeoPoint? = null,
    /** Best-effort reverse-geocoded current street/house number and city. */
    val currentAddress: String = "",
    val satellitesVisible: Int = 0,
    val satellitesUsed: Int = 0,
    val tripMeters: Double = 0.0,
    val elapsedMillis: Long = 0,
    val averageKph: Float = 0f,
    val maxKph: Float = 0f,
    val track: List<RideGeoPoint> = emptyList(),
    val speedHistoryKph: List<Float> = emptyList(),
    /** Elevation samples from the last hour of this dashboard session. */
    val altitudeHistory: List<RideAltitudeSample> = emptyList(),
    /** Phone battery level 0-100, refreshed periodically by the dashboard renderer. */
    val batteryLevel: Int = 50,
    /** Cellular network status string (e.g. "READY", "OFF"), refreshed periodically. */
    val cellularStatus: String = "OFF",
    /** Battery temperature in degrees Celsius, from BatteryManager. */
    val batteryTemperatureCelsius: Float = -1f,
    /** Whether the phone is currently charging (AC/wireless/USB). */
    val isCharging: Boolean = false,
    /** Internal storage used as percentage (0-100). */
    val storageUsedPercent: Int = 0,
    /** Instantaneous battery voltage in millivolts from BatteryManager. */
    val batteryVoltageMv: Int = 0,
    /** Wi-Fi RSSI of the connected T-Box access point in dBm, or 0 if unknown. */
    val wifiRssiDbm: Int = 0,
    /** Linear acceleration magnitude (g-force, gravity removed), or -1f if unavailable. */
    val linearAccelMagnitude: Float = -1f,
    /** Peak linear acceleration magnitude since last refresh (g-force). */
    val linearAccelPeak: Float = 0f,
    /** Barometric pressure in hPa, or 0 if unavailable. */
    val barometricPressureHpa: Float = 0f,
    /** Gyroscope Z-axis angular velocity in rad/s (yaw rate), or 0 if unavailable. */
    val gyroZ: Float = 0f,
    // ── Navigation (from NavigationRuntime) ─────────────────────────────────
    /** Whether a route is currently active. */
    val navHasRoute: Boolean = false,
    /** Distance to destination in meters. */
    val navDistanceRemainingMeters: Double = 0.0,
    /** Distance to the next maneuver in meters. */
    val navDistanceToManeuverMeters: Double = 0.0,
    /** Valhalla maneuver type string (e.g. "1" for straight, "10" for right). Empty if inactive. */
    val navManeuverType: String = "",
    /** Valhalla maneuver modifier (e.g. "left", "right"). Empty if inactive. */
    val navManeuverModifier: String = "",
    /** Human-readable turn instruction (e.g. "Via Roma"). Empty if inactive. */
    val navManeuverInstruction: String = "",
    /** Whether the rider has deviated far enough from the route to trigger a reroute. */
    val navOffRoute: Boolean = false,
    // ── Media / Now Playing ─────────────────────────────────────────────────
    /** Current track title, or empty if no media playing. */
    val mediaTitle: String = "",
    /** Current track artist, or empty if no media playing. */
    val mediaArtist: String = "",
    /** Current track album, or empty if no media playing. */
    val mediaAlbum: String = "",
    /** Current album artwork supplied by the active MediaSession, if available. */
    val mediaArtwork: Bitmap? = null,
    /** Playback position in milliseconds. */
    val mediaPositionMs: Long = 0L,
    /** Track duration in milliseconds. */
    val mediaDurationMs: Long = 0L,
    /** Whether media is currently playing (vs. paused). */
    val mediaIsPlaying: Boolean = false
) {
    fun hasFreshFix(nowElapsedRealtimeMillis: Long, maxAgeMillis: Long = 15_000L): Boolean =
        hasFix && lastFixElapsedRealtimeMillis?.let { nowElapsedRealtimeMillis - it <= maxAgeMillis } == true
}

/** Filters GNSS jitter and produces an immutable snapshot safe for the render thread. */
class RideTelemetryAccumulator(
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime
) {
    private val startedAtMillis = elapsedRealtime()
    private var lastAcceptedSample: RideLocationSample? = null
    private var lastTrackPoint: RideLocationSample? = null
    private var latestSample: RideLocationSample? = null
    private var satellitesVisible = 0
    private var satellitesUsed = 0
    private var tripMeters = 0.0
    private var maxKph = 0f
    private var firstAltitudeMeters: Double? = null
    private var minAltitudeMeters: Double? = null
    private var maxAltitudeMeters: Double? = null
    private var previousAltitudeMeters: Double? = null
    private var previousAltitudeElapsedMillis: Long? = null
    private var verticalSpeedMps: Float? = null
    private var firstMovementAtMillis: Long? = null
    private val track = ArrayDeque<RideGeoPoint>()
    private val speedHistory = ArrayDeque<Float>()
    private val altitudeHistory = ArrayDeque<RideAltitudeSample>()
    private var lastAltitudeHistoryElapsedMillis: Long? = null

    @Synchronized
    fun accept(sample: RideLocationSample) {
        if (!sample.latitude.isFinite() || !sample.longitude.isFinite() ||
            !sample.accuracyMeters.isFinite() || sample.accuracyMeters < 0f
        ) {
            return
        }
        latestSample = sample

        sample.altitudeMeters?.takeIf(Double::isFinite)?.let { altitude ->
            val sessionElapsed = (sample.elapsedRealtimeMillis - startedAtMillis).coerceAtLeast(0L)
            val lastHistoryAt = lastAltitudeHistoryElapsedMillis
            if (lastHistoryAt == null || sessionElapsed - lastHistoryAt >= ALTITUDE_HISTORY_INTERVAL_MILLIS) {
                altitudeHistory.addLast(RideAltitudeSample(sessionElapsed, altitude))
                lastAltitudeHistoryElapsedMillis = sessionElapsed
            }
            while (altitudeHistory.isNotEmpty() &&
                sessionElapsed - altitudeHistory.first().elapsedMillis > ALTITUDE_HISTORY_WINDOW_MILLIS
            ) {
                altitudeHistory.removeFirst()
            }
            if (firstAltitudeMeters == null) firstAltitudeMeters = altitude
            minAltitudeMeters = minAltitudeMeters?.coerceAtMost(altitude) ?: altitude
            maxAltitudeMeters = maxAltitudeMeters?.coerceAtLeast(altitude) ?: altitude
            val previousAltitude = previousAltitudeMeters
            val previousElapsed = previousAltitudeElapsedMillis
            if (previousAltitude != null && previousElapsed != null) {
                val deltaSeconds = (sample.elapsedRealtimeMillis - previousElapsed)
                    .coerceAtLeast(0L) / 1_000.0
                if (deltaSeconds in MIN_VERTICAL_SAMPLE_SECONDS..MAX_VERTICAL_SAMPLE_SECONDS) {
                    val rawVerticalSpeed = ((altitude - previousAltitude) / deltaSeconds)
                        .toFloat()
                        .coerceIn(-MAX_VERTICAL_SPEED_MPS, MAX_VERTICAL_SPEED_MPS)
                    val filtered = verticalSpeedMps?.let {
                        it * VERTICAL_SPEED_SMOOTHING +
                            rawVerticalSpeed * (1f - VERTICAL_SPEED_SMOOTHING)
                    } ?: rawVerticalSpeed
                    verticalSpeedMps = if (kotlin.math.abs(filtered) < VERTICAL_SPEED_DEADBAND_MPS) {
                        0f
                    } else {
                        filtered
                    }
                }
            }
            previousAltitudeMeters = altitude
            previousAltitudeElapsedMillis = sample.elapsedRealtimeMillis
        }

        val previous = lastAcceptedSample
        val distanceMeters = previous?.let { distanceMeters(it, sample) } ?: 0.0
        val elapsedSeconds = previous?.let {
            (sample.elapsedRealtimeMillis - it.elapsedRealtimeMillis).coerceAtLeast(0L) / 1_000.0
        } ?: 0.0
        val derivedSpeed = if (elapsedSeconds > 0.0) {
            (distanceMeters / elapsedSeconds).toFloat()
        } else {
            0f
        }
        val speedMetersPerSecond = (sample.speedMetersPerSecond ?: derivedSpeed)
            .takeIf(Float::isFinite)
            ?.coerceIn(0f, MAX_REASONABLE_SPEED_METERS_PER_SECOND)
            ?: 0f
        val speedKph = speedMetersPerSecond * 3.6f
        val movementConfirmed = speedMetersPerSecond >= MIN_MOVEMENT_SPEED_METERS_PER_SECOND
        maxKph = max(maxKph, speedKph)
        speedHistory.addLast(speedKph)
        while (speedHistory.size > MAX_SPEED_HISTORY_POINTS) speedHistory.removeFirst()

        if (sample.accuracyMeters <= MAX_TRIP_ACCURACY_METERS) {
            if (previous != null && elapsedSeconds in MIN_SAMPLE_INTERVAL_SECONDS..MAX_SAMPLE_INTERVAL_SECONDS) {
                val jitterThreshold = max(
                    MIN_TRIP_DELTA_METERS,
                    min(previous.accuracyMeters, sample.accuracyMeters) * ACCURACY_JITTER_FACTOR
                )
                if (movementConfirmed && distanceMeters >= jitterThreshold &&
                    derivedSpeed <= MAX_REASONABLE_SPEED_METERS_PER_SECOND
                ) {
                    if (firstMovementAtMillis == null) {
                        firstMovementAtMillis = previous.elapsedRealtimeMillis
                    }
                    tripMeters += distanceMeters
                }
            }
            lastAcceptedSample = sample

            val trackDistance = lastTrackPoint?.let { distanceMeters(it, sample) }
            if (trackDistance == null ||
                movementConfirmed && trackDistance >= MIN_TRACK_DELTA_METERS
            ) {
                track.addLast(RideGeoPoint(sample.latitude, sample.longitude))
                lastTrackPoint = sample
                while (track.size > MAX_TRACK_POINTS) track.removeFirst()
            }
        }
    }

    @Synchronized
    fun updateSatellites(visible: Int, used: Int) {
        satellitesVisible = visible.coerceAtLeast(0)
        satellitesUsed = used.coerceIn(0, satellitesVisible)
    }

    @Synchronized
    fun snapshot(): RideTelemetrySnapshot {
        val now = elapsedRealtime()
        val elapsed = (now - startedAtMillis).coerceAtLeast(0L)
        val latest = latestSample
        val movingTripElapsed = firstMovementAtMillis?.let { started ->
            (now - started).coerceAtLeast(0L)
        } ?: 0L
        val rawAverageKph = if (movingTripElapsed >= MIN_AVERAGE_INTERVAL_MILLIS) {
            // tripMeters is in metres, so it must be converted to kilometres before dividing
            // by hours — without it this yields a value ~1000x too large, which the coerceIn
            // below then silently clamps down to maxKph, making the trip computer display the
            // maximum speed under the "average" label.
            ((tripMeters / 1_000.0) / (movingTripElapsed / 3_600_000.0)).toFloat()
        } else {
            0f
        }
        val averageKph = rawAverageKph.coerceIn(0f, maxKph)
        return RideTelemetrySnapshot(
            hasFix = latest != null,
            lastFixElapsedRealtimeMillis = latest?.elapsedRealtimeMillis,
            speedKph = latest?.speedMetersPerSecond
                ?.takeIf(Float::isFinite)
                ?.coerceIn(0f, MAX_REASONABLE_SPEED_METERS_PER_SECOND)
                ?.times(3.6f)
                ?: speedHistory.lastOrNull()
                ?: 0f,
            bearingDegrees = latest?.bearingDegrees?.takeIf(Float::isFinite)?.normalizeBearing(),
            altitudeMeters = latest?.altitudeMeters?.takeIf(Double::isFinite),
            altitudeChangeMeters = latest?.altitudeMeters?.takeIf(Double::isFinite)?.let {
                it - (firstAltitudeMeters ?: it)
            } ?: 0.0,
            minAltitudeMeters = minAltitudeMeters,
            maxAltitudeMeters = maxAltitudeMeters,
            verticalSpeedMps = verticalSpeedMps,
            accuracyMeters = latest?.accuracyMeters,
            position = latest?.let { RideGeoPoint(it.latitude, it.longitude) },
            satellitesVisible = satellitesVisible,
            satellitesUsed = satellitesUsed,
            tripMeters = tripMeters,
            elapsedMillis = elapsed,
            averageKph = averageKph,
            maxKph = maxKph,
            track = track.toList(),
            speedHistoryKph = speedHistory.toList(),
            altitudeHistory = altitudeHistory.toList()
        )
    }

    private fun Float.normalizeBearing(): Float = ((this % 360f) + 360f) % 360f

    private fun distanceMeters(first: RideLocationSample, second: RideLocationSample): Double =
        haversineDistanceMeters(
            first.latitude,
            first.longitude,
            second.latitude,
            second.longitude
        )

    private companion object {
        const val MAX_TRIP_ACCURACY_METERS = 60f
        const val MIN_TRIP_DELTA_METERS = 2.5
        const val MIN_TRACK_DELTA_METERS = 3.0
        const val ACCURACY_JITTER_FACTOR = 0.35
        const val MIN_SAMPLE_INTERVAL_SECONDS = 0.2
        const val MAX_SAMPLE_INTERVAL_SECONDS = 15.0
        const val MAX_REASONABLE_SPEED_METERS_PER_SECOND = 95f
        const val MIN_VERTICAL_SAMPLE_SECONDS = 0.2
        const val MAX_VERTICAL_SAMPLE_SECONDS = 15.0
        const val MAX_VERTICAL_SPEED_MPS = 25f
        const val VERTICAL_SPEED_SMOOTHING = 0.7f
        const val VERTICAL_SPEED_DEADBAND_MPS = 0.15f
        const val MIN_MOVEMENT_SPEED_METERS_PER_SECOND = 0.8f
        const val MIN_AVERAGE_INTERVAL_MILLIS = 10_000L
        const val MAX_TRACK_POINTS = 240
        const val MAX_SPEED_HISTORY_POINTS = 180
        const val ALTITUDE_HISTORY_WINDOW_MILLIS = 60 * 60 * 1_000L
        const val ALTITUDE_HISTORY_INTERVAL_MILLIS = 5_000L
    }
}

internal fun haversineDistanceMeters(
    firstLatitude: Double,
    firstLongitude: Double,
    secondLatitude: Double,
    secondLongitude: Double
): Double {
    val latitudeDelta = Math.toRadians(secondLatitude - firstLatitude)
    val longitudeDelta = Math.toRadians(secondLongitude - firstLongitude)
    val firstLatitudeRadians = Math.toRadians(firstLatitude)
    val secondLatitudeRadians = Math.toRadians(secondLatitude)
    val a = sin(latitudeDelta / 2.0).pow(2) +
        cos(firstLatitudeRadians) * cos(secondLatitudeRadians) *
        sin(longitudeDelta / 2.0).pow(2)
    return 2.0 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

private const val EARTH_RADIUS_METERS = 6_371_000.0
