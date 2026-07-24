package io.motohub.android.units

import io.motohub.android.feature.settings.DistanceUnits
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToInt

class UnitFormatTest {

    @Test
    fun `speed passes through in kilometers mode`() {
        assertEquals(100f, UnitFormat.speed(100f, DistanceUnits.KILOMETERS), 0f)
    }

    @Test
    fun `speed converts kph to mph in miles mode`() {
        assertEquals(62, UnitFormat.speed(100f, DistanceUnits.MILES).roundToInt())
        assertEquals(50, UnitFormat.speed(80.4672, DistanceUnits.MILES).roundToInt())
    }

    @Test
    fun `speed labels match the selected unit`() {
        assertEquals("KM/H", UnitFormat.speedLabel(DistanceUnits.KILOMETERS))
        assertEquals("MPH", UnitFormat.speedLabel(DistanceUnits.MILES))
        assertEquals("km/h", UnitFormat.speedLabelLower(DistanceUnits.KILOMETERS))
        assertEquals("mph", UnitFormat.speedLabelLower(DistanceUnits.MILES))
    }

    @Test
    fun `distance formats meters and kilometers in metric mode`() {
        assertEquals("850 m", UnitFormat.distance(850.0, DistanceUnits.KILOMETERS))
        assertEquals("12.3 km", UnitFormat.distance(12_345.0, DistanceUnits.KILOMETERS))
    }

    @Test
    fun `distance formats feet and miles in miles mode`() {
        assertEquals("328 ft", UnitFormat.distance(100.0, DistanceUnits.MILES))
        assertEquals("7.7 mi", UnitFormat.distance(12_345.0, DistanceUnits.MILES))
    }

    @Test
    fun `distanceCompact steps precision with magnitude`() {
        assertEquals("1.50 km", UnitFormat.distanceCompact(1_500.0, DistanceUnits.KILOMETERS))
        assertEquals("15.0 km", UnitFormat.distanceCompact(15_000.0, DistanceUnits.KILOMETERS))
        assertEquals("150 km", UnitFormat.distanceCompact(150_000.0, DistanceUnits.KILOMETERS))
        assertEquals("0.93 mi", UnitFormat.distanceCompact(1_500.0, DistanceUnits.MILES))
        assertEquals("9.32 mi", UnitFormat.distanceCompact(15_000.0, DistanceUnits.MILES))
        assertEquals("18.6 mi", UnitFormat.distanceCompact(30_000.0, DistanceUnits.MILES))
        assertEquals("93.2 mi", UnitFormat.distanceCompact(150_000.0, DistanceUnits.MILES))
        assertEquals("124 mi", UnitFormat.distanceCompact(200_000.0, DistanceUnits.MILES))
        assertEquals("328 ft", UnitFormat.distanceCompact(100.0, DistanceUnits.MILES))
    }

    @Test
    fun `distanceValue and label stay consistent for the trip widget`() {
        assertEquals("850", UnitFormat.distanceValue(850.0, DistanceUnits.KILOMETERS))
        assertEquals("M", UnitFormat.distanceValueLabel(850.0, DistanceUnits.KILOMETERS))
        assertEquals("1.5", UnitFormat.distanceValue(1_500.0, DistanceUnits.KILOMETERS))
        assertEquals("12", UnitFormat.distanceValue(12_345.0, DistanceUnits.KILOMETERS))
        assertEquals("KM", UnitFormat.distanceValueLabel(12_345.0, DistanceUnits.KILOMETERS))
        assertEquals("7.7", UnitFormat.distanceValue(12_345.0, DistanceUnits.MILES))
        assertEquals("MI", UnitFormat.distanceValueLabel(12_345.0, DistanceUnits.MILES))
        assertEquals("328", UnitFormat.distanceValue(100.0, DistanceUnits.MILES))
        assertEquals("FT", UnitFormat.distanceValueLabel(100.0, DistanceUnits.MILES))
    }

    @Test
    fun `whole distances convert km to the display unit`() {
        assertEquals(300, UnitFormat.wholeDistanceFromKm(300.0, DistanceUnits.KILOMETERS))
        assertEquals(186, UnitFormat.wholeDistanceFromKm(300.0, DistanceUnits.MILES))
        assertEquals("km", UnitFormat.wholeDistanceLabel(DistanceUnits.KILOMETERS))
        assertEquals("mi", UnitFormat.wholeDistanceLabel(DistanceUnits.MILES))
    }

    @Test
    fun `tank range survives a display-and-save round trip in miles`() {
        val storedKm = 300.0
        val shownMiles = UnitFormat.wholeDistanceFromKm(storedKm, DistanceUnits.MILES)
        val savedKm = UnitFormat.kmFromDistance(shownMiles.toDouble(), DistanceUnits.MILES)
        assertEquals(shownMiles, UnitFormat.wholeDistanceFromKm(savedKm, DistanceUnits.MILES))
    }

    @Test
    fun `altitude converts to feet in miles mode`() {
        assertEquals(500, UnitFormat.altitudeValue(500.0, DistanceUnits.KILOMETERS))
        assertEquals(1640, UnitFormat.altitudeValue(500.0, DistanceUnits.MILES))
        assertEquals("M", UnitFormat.altitudeLabel(DistanceUnits.KILOMETERS))
        assertEquals("FT", UnitFormat.altitudeLabel(DistanceUnits.MILES))
    }

    @Test
    fun `odometer meters convert to km or miles`() {
        assertEquals(12.3, UnitFormat.distanceFromMeters(12_300.0, DistanceUnits.KILOMETERS), 0.001)
        assertEquals(7.643, UnitFormat.distanceFromMeters(12_300.0, DistanceUnits.MILES), 0.001)
    }
}
