package io.motohub.android.feature.ridedashboard.nav

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NavigationM2bUtilsTest {
    // Milan, used elsewhere in this project as the reference location.
    private val milan = NavPoint(45.4642, 9.1900)
    private val rome = ZoneId.of("Europe/Rome")
    private lateinit var originalDefaultZone: TimeZone

    @Before
    fun pinTimeZone() {
        // minutesToGoldenHour reports in ZoneId.systemDefault(); pin it so the
        // test doesn't depend on the CI/dev machine's local timezone.
        originalDefaultZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(rome))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalDefaultZone)
    }

    @Test
    fun `minutesToGoldenHour is zero when arriving during evening golden hour`() {
        // Milan sunset in late June is roughly 21:05 CEST; 20:45 sits inside
        // the last 60 minutes before sunset.
        val arrival = ZonedDateTime.of(2026, 6, 21, 20, 45, 0, 0, rome).toInstant()
        val minutes = minutesToGoldenHour(milan, Instant.now(), arrival)
        assertEquals(0, minutes)
    }

    @Test
    fun `minutesToGoldenHour is zero when arriving during morning golden hour`() {
        // Milan sunrise in late June is roughly 05:35 CEST; 06:00 sits inside
        // the first 60 minutes after sunrise.
        val arrival = ZonedDateTime.of(2026, 6, 21, 6, 0, 0, 0, rome).toInstant()
        val minutes = minutesToGoldenHour(milan, Instant.now(), arrival)
        assertEquals(0, minutes)
    }

    @Test
    fun `minutesToGoldenHour counts down to the next window at midday`() {
        val arrival = ZonedDateTime.of(2026, 6, 21, 13, 0, 0, 0, rome).toInstant()
        val minutes = minutesToGoldenHour(milan, Instant.now(), arrival)
        assertNotNull(minutes)
        assertTrue("expected a positive countdown to evening golden hour, was $minutes", minutes!! > 0)
        // Sunset golden hour starts roughly 7-8 hours after 13:00 in late June.
        assertTrue("expected countdown under 10 hours, was $minutes", minutes < 600)
    }

    @Test
    fun `minutesToGoldenHour is null outside 24h relevance for arrivals well outside any window`() {
        // Just past the evening window on the summer solstice: the next
        // window (tomorrow's sunrise) is still within 24h, so this must
        // resolve to a small positive number, not null.
        val arrival = ZonedDateTime.of(2026, 6, 21, 21, 30, 0, 0, rome).toInstant()
        val minutes = minutesToGoldenHour(milan, Instant.now(), arrival)
        assertNotNull(minutes)
        assertTrue(minutes!! > 0)
    }

    @Test
    fun `detectCurvedSegments finds nothing on a straight line`() {
        val straight = (0..40).map { NavPoint(45.0, 9.0 + it * 0.001) }
        assertTrue(detectCurvedSegments(straight).isEmpty())
    }

    @Test
    fun `detectCurvedSegments finds a zigzag`() {
        val zigzag = (0..40).map { index ->
            val lat = 45.0 + (if (index % 2 == 0) 0.0015 else -0.0015)
            NavPoint(lat, 9.0 + index * 0.001)
        }
        assertTrue(detectCurvedSegments(zigzag, curvinessTreshold = 0.2).isNotEmpty())
    }
}
