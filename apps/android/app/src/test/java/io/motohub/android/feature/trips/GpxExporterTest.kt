package io.motohub.android.feature.trips

import org.junit.Assert.assertTrue
import org.junit.Test

class GpxExporterTest {
    @Test
    fun exportIncludesEscapedNameCoordinatesElevationAndTime() {
        val details = TripDetails(
            summary = TripSummary(
                id = "trip",
                name = "Coast & hills",
                motorcycleId = null,
                source = TripRecordingSource.MANUAL,
                startedAtMillis = 1_000L,
                endedAtMillis = 2_000L,
                distanceMeters = 100.0,
                movingTimeMillis = 1_000L,
                elapsedTimeMillis = 1_000L,
                maxSpeedMetersPerSecond = 10f,
                pointCount = 1,
                minLatitude = 40.0,
                maxLatitude = 40.0,
                minLongitude = -8.0,
                maxLongitude = -8.0,
                active = false
            ),
            points = listOf(TripTrackPoint(0, 40.0, -8.0, 1_000L, 5f, 3f, 123.4))
        )

        val gpx = details.toGpx()

        assertTrue(gpx.contains("Coast &amp; hills"))
        assertTrue(gpx.contains("lat=\"40.0\" lon=\"-8.0\""))
        assertTrue(gpx.contains("<ele>123.4</ele>"))
        assertTrue(gpx.contains("<time>1970-01-01T00:00:01Z</time>"))
    }
}
