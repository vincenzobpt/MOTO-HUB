package io.motohub.android.feature.ridedashboard.nav

/**
 * Decodes a Google-style encoded polyline at the given coordinate precision.
 * Valhalla encodes route shapes at precision 6 (`1e-6` degrees per unit).
 */
internal fun decodePolyline(encoded: String, precision: Int = 6): List<NavPoint> {
    val factor = Math.pow(10.0, precision.toDouble())
    val points = mutableListOf<NavPoint>()
    var index = 0
    var latitude = 0L
    var longitude = 0L

    while (index < encoded.length) {
        val (latitudeDelta, latitudeConsumed) = decodeSignedValue(encoded, index)
        index += latitudeConsumed
        latitude += latitudeDelta

        val (longitudeDelta, longitudeConsumed) = decodeSignedValue(encoded, index)
        index += longitudeConsumed
        longitude += longitudeDelta

        points.add(NavPoint(latitude / factor, longitude / factor))
    }
    return points
}

/** Returns the decoded signed delta and the number of characters consumed. */
private fun decodeSignedValue(encoded: String, startIndex: Int): Pair<Long, Int> {
    var result = 0L
    var shift = 0
    var index = startIndex
    var byte: Int
    do {
        byte = encoded[index].code - 63
        index++
        result = result or ((byte and 0x1f).toLong() shl shift)
        shift += 5
    } while (byte >= 0x20)
    val delta = if (result and 1L != 0L) (result shr 1).inv() else (result shr 1)
    return delta to (index - startIndex)
}
