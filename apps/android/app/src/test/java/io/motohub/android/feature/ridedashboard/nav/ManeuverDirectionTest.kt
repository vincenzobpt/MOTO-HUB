package io.motohub.android.feature.ridedashboard.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class ManeuverDirectionTest {
    @Test
    fun `maps common Valhalla maneuver types to the expected coarse direction`() {
        assertEquals(ManeuverDirection.RIGHT, maneuverDirection("10"))
        assertEquals(ManeuverDirection.LEFT, maneuverDirection("15"))
        assertEquals(ManeuverDirection.SHARP_RIGHT, maneuverDirection("11"))
        assertEquals(ManeuverDirection.SHARP_LEFT, maneuverDirection("14"))
        assertEquals(ManeuverDirection.UTURN, maneuverDirection("12"))
        assertEquals(ManeuverDirection.ROUNDABOUT, maneuverDirection("26"))
        assertEquals(ManeuverDirection.ARRIVE, maneuverDirection("4"))
    }

    @Test
    fun `unknown or malformed types default to straight`() {
        assertEquals(ManeuverDirection.STRAIGHT, maneuverDirection("999"))
        assertEquals(ManeuverDirection.STRAIGHT, maneuverDirection("not-a-number"))
    }
}
