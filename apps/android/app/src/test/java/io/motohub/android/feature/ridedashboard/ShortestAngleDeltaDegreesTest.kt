package io.motohub.android.feature.ridedashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class ShortestAngleDeltaDegreesTest {
    private fun assertDelta(expected: Float, from: Float, to: Float) {
        assertEquals(expected, shortestAngleDeltaDegrees(from, to), 0.001f)
    }

    @Test
    fun `no wraparound needed for a small forward delta`() {
        assertDelta(20f, 10f, 30f)
    }

    @Test
    fun `no wraparound needed for a small backward delta`() {
        assertDelta(-20f, 30f, 10f)
    }

    @Test
    fun `crossing 0-360 takes the short way forward`() {
        // 350 -> 10 is a 20deg turn through the 0/360 boundary, not a 340deg turn backward.
        assertDelta(20f, 350f, 10f)
    }

    @Test
    fun `crossing 0-360 takes the short way backward`() {
        assertDelta(-20f, 10f, 350f)
    }

    @Test
    fun `exact opposite is 180`() {
        assertDelta(180f, 0f, 180f)
    }

    @Test
    fun `same angle is zero delta`() {
        assertDelta(0f, 175f, 175f)
    }

    @Test
    fun `handles inputs outside 0-360`() {
        // 370 is equivalent to 10; delta to 20 should be 10, not something
        // skewed by not normalizing the input range first.
        assertDelta(10f, 370f, 20f)
        assertDelta(10f, -350f, 20f)
    }
}
