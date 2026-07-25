package io.motohub.android.feature.ridedashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideTelemetryTest {
    @Test
    fun `valid movement updates trip speed and track`() {
        var now = 0L
        val telemetry = RideTelemetryAccumulator { now }
        telemetry.accept(sample(latitude = 40.0, longitude = -7.0, elapsed = 1_000L, speed = 10f))
        now = 3_000L
        telemetry.accept(sample(latitude = 40.0005, longitude = -7.0, elapsed = now, speed = 12f))

        val snapshot = telemetry.snapshot()

        assertTrue(snapshot.tripMeters in 54.0..57.0)
        assertEquals(43.2f, snapshot.speedKph, 0.01f)
        assertEquals(43.2f, snapshot.maxKph, 0.01f)
        assertEquals(2, snapshot.track.size)
    }

    @Test
    fun `small movement inside accuracy radius does not inflate trip`() {
        var now = 0L
        val telemetry = RideTelemetryAccumulator { now }
        telemetry.accept(sample(latitude = 40.0, longitude = -7.0, elapsed = 1_000L, accuracy = 20f))
        now = 2_000L
        telemetry.accept(sample(latitude = 40.00001, longitude = -7.0, elapsed = now, accuracy = 20f))

        assertEquals(0.0, telemetry.snapshot().tripMeters, 0.01)
    }

    @Test
    fun `stationary GNSS drift never creates trip distance or average speed`() {
        var now = 0L
        val telemetry = RideTelemetryAccumulator { now }
        repeat(20) { index ->
            now = (index + 1) * 1_000L
            telemetry.accept(
                sample(
                    latitude = 40.0 + index * 0.00002,
                    longitude = -7.0,
                    elapsed = now,
                    speed = 0f,
                    accuracy = 3f
                )
            )
        }

        val snapshot = telemetry.snapshot()

        assertEquals(0.0, snapshot.tripMeters, 0.01)
        assertEquals(0f, snapshot.averageKph, 0.01f)
        assertEquals(1, snapshot.track.size)
    }

    @Test
    fun `average speed cannot exceed maximum observed GNSS speed`() {
        var now = 0L
        val telemetry = RideTelemetryAccumulator { now }
        telemetry.accept(sample(latitude = 40.0, longitude = -7.0, elapsed = 1_000L, speed = 10f))
        now = 12_000L
        telemetry.accept(sample(latitude = 40.005, longitude = -7.0, elapsed = now, speed = 12f))

        val snapshot = telemetry.snapshot()

        assertTrue(snapshot.averageKph <= snapshot.maxKph)
        assertEquals(43.2f, snapshot.averageKph, 0.01f)
    }

    @Test
    fun `average speed reflects the whole trip pace, not the peak instantaneous speed`() {
        // 9 steps of 10 s at a steady 20 m/s (72 km/h) pace = 1800 m over 90 s.
        // One sample reports a much higher instantaneous speed (30 m/s, 108 km/h) so
        // maxKph and the true trip average are clearly different numbers: a bug that
        // collapses the average onto the max would show 108, not 72.
        var now = 0L
        val telemetry = RideTelemetryAccumulator { now }
        val metersPerDegreeLatitude = 111_320.0
        repeat(10) { index ->
            now = index * 10_000L
            val reportedSpeed = if (index == 3) 30f else 20f
            telemetry.accept(
                sample(
                    latitude = 40.0 + index * (200.0 / metersPerDegreeLatitude),
                    longitude = -7.0,
                    elapsed = now,
                    speed = reportedSpeed
                )
            )
        }

        val snapshot = telemetry.snapshot()

        assertEquals(108f, snapshot.maxKph, 0.1f)
        assertEquals(72f, snapshot.averageKph, 1f)
    }

    @Test
    fun `poor accuracy is reported but excluded from trip and track`() {
        var now = 0L
        val telemetry = RideTelemetryAccumulator { now }
        now = 1_000L
        telemetry.accept(sample(latitude = 40.0, longitude = -7.0, elapsed = now, accuracy = 90f))

        val snapshot = telemetry.snapshot()

        assertTrue(snapshot.hasFix)
        assertEquals(90f, snapshot.accuracyMeters ?: 0f, 0.01f)
        assertTrue(snapshot.track.isEmpty())
        assertEquals(0.0, snapshot.tripMeters, 0.01)
    }

    @Test
    fun `fix freshness expires after configured age`() {
        var now = 0L
        val telemetry = RideTelemetryAccumulator { now }
        telemetry.accept(sample(latitude = 40.0, longitude = -7.0, elapsed = 1_000L))

        val snapshot = telemetry.snapshot()

        assertTrue(snapshot.hasFreshFix(15_999L))
        assertFalse(snapshot.hasFreshFix(16_001L))
    }

    @Test
    fun `haversine distance is stable for a known latitude delta`() {
        val distance = haversineDistanceMeters(40.0, -7.0, 40.001, -7.0)

        assertTrue(distance in 110.0..112.0)
    }

    @Test
    fun `altitude session metrics track range change and filtered vertical speed`() {
        var now = 0L
        val telemetry = RideTelemetryAccumulator { now }
        telemetry.accept(sample(latitude = 40.0, longitude = -7.0, elapsed = 1_000L, altitude = 500.0))
        now = 3_000L
        telemetry.accept(sample(latitude = 40.0, longitude = -7.0, elapsed = now, altitude = 520.0))
        now = 5_000L
        telemetry.accept(sample(latitude = 40.0, longitude = -7.0, elapsed = now, altitude = 510.0))

        val snapshot = telemetry.snapshot()

        assertEquals(10.0, snapshot.altitudeChangeMeters, 0.01)
        assertEquals(500.0, snapshot.minAltitudeMeters ?: 0.0, 0.01)
        assertEquals(520.0, snapshot.maxAltitudeMeters ?: 0.0, 0.01)
        assertTrue((snapshot.verticalSpeedMps ?: 0f) > 0f)
    }

    @Test
    fun `altitude history is sampled and limited to the last hour`() {
        var now = 0L
        val telemetry = RideTelemetryAccumulator { now }
        telemetry.accept(sample(latitude = 40.0, longitude = -7.0, elapsed = 1_000L, altitude = 500.0))
        now = 6_000L
        telemetry.accept(sample(latitude = 40.0, longitude = -7.0, elapsed = now, altitude = 520.0))
        now = 3_606_000L
        telemetry.accept(sample(latitude = 40.0, longitude = -7.0, elapsed = now, altitude = 530.0))

        val history = telemetry.snapshot().altitudeHistory

        assertEquals(2, history.size)
        assertEquals(520.0, history.first().altitudeMeters, 0.01)
        assertEquals(530.0, history.last().altitudeMeters, 0.01)
    }

    @Test
    fun `OpenStreetMap projection matches documented slippy tile coordinates`() {
        val point = osmWorldPixel(35.6590699, 139.7006793, 18)

        assertEquals(232798.930, point.x / 256.0, 0.001)
        assertEquals(103246.410, point.y / 256.0, 0.001)
    }

    private fun sample(
        latitude: Double,
        longitude: Double,
        elapsed: Long,
        speed: Float? = null,
        accuracy: Float = 3f,
        altitude: Double = 742.0
    ) = RideLocationSample(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracy,
        elapsedRealtimeMillis = elapsed,
        speedMetersPerSecond = speed,
        bearingDegrees = 28f,
        altitudeMeters = altitude
    )
}
