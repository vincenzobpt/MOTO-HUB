package io.motohub.android.feature.trips

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripRecordingRuntimeTest {
    @After
    fun resetRuntime() {
        TripRecordingRuntime.clearTrack()
        TripRecordingRuntime.publish(TripRecordingState.Idle)
    }

    @Test
    fun appendTrackPublishesAcceptedPointsInOrder() {
        val first = point(1)
        val second = point(2)

        TripRecordingRuntime.beginTrack(listOf(first))
        TripRecordingRuntime.appendTrack(second)

        assertEquals(listOf(first, second), TripRecordingRuntime.track.value)
    }

    @Test
    fun longTrackIsCompactedWithoutLosingItsEndpoints() {
        val points = (0..TripRecordingRuntime.MAX_RENDERED_TRACK_POINTS).map(::point)

        TripRecordingRuntime.beginTrack(points)

        val rendered = TripRecordingRuntime.track.value
        assertTrue(rendered.size <= TripRecordingRuntime.MAX_RENDERED_TRACK_POINTS)
        assertEquals(points.first(), rendered.first())
        assertEquals(points.last(), rendered.last())
    }

    private fun point(sequence: Int) = TripTrackPoint(
        sequence = sequence,
        latitude = 40.0 + sequence / 100_000.0,
        longitude = -8.0,
        timestampMillis = sequence * 1_000L,
        speedMetersPerSecond = 10f,
        accuracyMeters = 4f,
        altitudeMeters = 100.0
    )
}
