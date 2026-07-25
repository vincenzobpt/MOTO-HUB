package io.motohub.android.feature.ridedashboard.widget

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SunsetCalculatorTest {
    private val milan = ZoneId.of("Europe/Rome")
    private lateinit var originalZone: TimeZone

    @Before
    fun pinTimeZone() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(milan))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalZone)
    }

    @Test
    fun `midnight countdown targets sunrise instead of today's sunset`() {
        val now = ZonedDateTime.of(2026, 6, 21, 0, 30, 0, 0, milan)

        val countdown = SunsetCalculator.daylightCountdown(45.4642, 9.1900, now)

        assertTrue(countdown != null)
        assertTrue(countdown!!.untilSunrise)
        assertTrue(countdown.duration.toHours() in 4L..7L)
    }

    @Test
    fun `daytime countdown targets sunset`() {
        val now = ZonedDateTime.of(2026, 6, 21, 13, 0, 0, 0, milan)

        val countdown = SunsetCalculator.daylightCountdown(45.4642, 9.1900, now)

        assertTrue(countdown != null)
        assertTrue(!countdown!!.untilSunrise)
        assertTrue(countdown.duration.toHours() in 6L..10L)
    }
}
