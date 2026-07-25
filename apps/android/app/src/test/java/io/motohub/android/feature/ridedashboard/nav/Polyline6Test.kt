package io.motohub.android.feature.ridedashboard.nav

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.pow
import kotlin.math.roundToLong

class Polyline6Test {
    @Test
    fun `decodes the published Google precision-5 example`() {
        // https://developers.google.com/maps/documentation/utilities/polylinealgorithm
        val points = decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@", precision = 5)

        assertEquals(3, points.size)
        assertPointEquals(38.5, -120.2, points[0])
        assertPointEquals(40.7, -120.95, points[1])
        assertPointEquals(43.252, -126.453, points[2])
    }

    @Test
    fun `round-trips arbitrary points at Valhalla's precision 6`() {
        val original = listOf(
            NavPoint(45.5231, -122.6765),
            NavPoint(45.5241, -122.6745),
            NavPoint(45.5209, -122.6702)
        )

        val decoded = decodePolyline(encodePolylineForTest(original, precision = 6), precision = 6)

        assertEquals(original.size, decoded.size)
        original.zip(decoded).forEach { (expected, actual) -> assertPointEquals(expected.latitude, expected.longitude, actual) }
    }

    @Test
    fun `empty input decodes to no points`() {
        assertEquals(emptyList<NavPoint>(), decodePolyline(""))
    }

    private fun assertPointEquals(expectedLatitude: Double, expectedLongitude: Double, actual: NavPoint) {
        assertEquals(expectedLatitude, actual.latitude, 1e-4)
        assertEquals(expectedLongitude, actual.longitude, 1e-4)
    }
}

/** Minimal encoder used only to build round-trip fixtures for [Polyline6Test]. */
private fun encodePolylineForTest(points: List<NavPoint>, precision: Int): String {
    val factor = 10.0.pow(precision)
    val builder = StringBuilder()
    var previousLatitude = 0L
    var previousLongitude = 0L
    for (point in points) {
        val latitude = (point.latitude * factor).roundToLong()
        val longitude = (point.longitude * factor).roundToLong()
        encodeSignedValue(latitude - previousLatitude, builder)
        encodeSignedValue(longitude - previousLongitude, builder)
        previousLatitude = latitude
        previousLongitude = longitude
    }
    return builder.toString()
}

private fun encodeSignedValue(value: Long, builder: StringBuilder) {
    var shifted = if (value < 0) (value shl 1).inv() else value shl 1
    while (shifted >= 0x20) {
        val chunk = (shifted.toInt() and 0x1f) or 0x20
        builder.append((chunk + 63).toChar())
        shifted = shifted shr 5
    }
    builder.append((shifted.toInt() + 63).toChar())
}
