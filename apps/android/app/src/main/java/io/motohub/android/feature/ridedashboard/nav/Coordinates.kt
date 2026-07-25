package io.motohub.android.feature.ridedashboard.nav

/**
 * Parses a raw "lat, lon" text entry into a destination, for trailheads or
 * meeting points that have no street address. Accepts comma or whitespace
 * separators and rejects out-of-range values.
 */
fun parseCoordinates(input: String): NavPlace? {
    val parts = input.trim().split(Regex("[,\\s]+")).filter { it.isNotBlank() }
    if (parts.size != 2) return null
    val latitude = parts[0].toDoubleOrNull() ?: return null
    val longitude = parts[1].toDoubleOrNull() ?: return null
    if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
    return NavPlace(
        label = "${trimTrailingZeros(latitude)}, ${trimTrailingZeros(longitude)}",
        point = NavPoint(latitude, longitude)
    )
}

private fun trimTrailingZeros(value: Double): String =
    value.toString().let { if (it.endsWith(".0")) it.dropLast(2) else it }
