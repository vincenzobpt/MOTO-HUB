package io.motohub.android.feature.ridedashboard.nav

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression test for the bug where Open-Meteo's hourly.time strings
 * ("2026-07-19T00:00", no seconds, no zone suffix) failed java.time.Instant
 * parsing and were silently swallowed, making weather-at-arrival always null.
 */
class OpenMeteoTimeParsingTest {
    private fun parse(value: String): Long {
        val withSeconds = if (value.count { it == ':' } < 2) "$value:00" else value
        return Instant.parse("${withSeconds}Z").epochSecond
    }

    @Test
    fun `parses Open-Meteo hourly time format without seconds`() {
        val epoch = parse("2026-07-19T00:00")
        assertEquals(Instant.parse("2026-07-19T00:00:00Z").epochSecond, epoch)
    }

    @Test
    fun `parses a non-midnight hour`() {
        val epoch = parse("2026-07-19T14:00")
        assertEquals(Instant.parse("2026-07-19T14:00:00Z").epochSecond, epoch)
    }
}
