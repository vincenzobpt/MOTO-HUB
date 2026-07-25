package io.motohub.android.feature.trips

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripAccumulatorTest {
    @Test
    fun accurateMovementBuildsDistanceAndMovingTime() {
        val accumulator = TripAccumulator(startedAtMillis = 1_000L)

        assertNotNull(accumulator.accept(sample(latitude = 40.0, time = 1_000L, elapsed = 1_000_000_000L)))
        accumulator.accept(sample(latitude = 40.00009, time = 2_000L, elapsed = 2_000_000_000L))

        val snapshot = accumulator.snapshot(nowMillis = 2_000L)
        assertTrue(snapshot.distanceMeters in 9.0..11.5)
        assertEquals(1_000L, snapshot.movingTimeMillis)
        assertTrue(snapshot.hasFix)
    }

    @Test
    fun inaccurateFixIsRejectedWithoutMutatingTheTrack() {
        val accumulator = TripAccumulator(startedAtMillis = 1_000L)

        val point = accumulator.accept(
            sample(latitude = 40.0, time = 1_000L, elapsed = 1_000_000_000L, accuracy = 120f)
        )

        assertNull(point)
        assertFalse(accumulator.snapshot().hasFix)
        assertEquals(0, accumulator.snapshot().pointCount)
    }

    @Test
    fun impossibleGpsJumpDoesNotInflateTripDistance() {
        val accumulator = TripAccumulator(startedAtMillis = 1_000L)
        accumulator.accept(sample(latitude = 40.0, time = 1_000L, elapsed = 1_000_000_000L))

        val jump = accumulator.accept(sample(latitude = 41.0, time = 2_000L, elapsed = 2_000_000_000L))

        assertNull(jump)
        assertEquals(0.0, accumulator.snapshot().distanceMeters, 0.01)
        assertEquals(1, accumulator.snapshot().pointCount)
    }

    private fun sample(
        latitude: Double,
        time: Long,
        elapsed: Long,
        accuracy: Float = 4f
    ) = TripLocationSample(
        latitude = latitude,
        longitude = -8.0,
        timestampMillis = time,
        elapsedRealtimeNanos = elapsed,
        speedMetersPerSecond = null,
        accuracyMeters = accuracy,
        altitudeMeters = 100.0
    )
}
