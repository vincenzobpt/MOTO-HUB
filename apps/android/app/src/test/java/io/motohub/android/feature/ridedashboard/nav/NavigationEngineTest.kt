package io.motohub.android.feature.ridedashboard.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationEngineTest {
    // Five points along a fixed latitude, ~79m apart, ending at an "arrive" maneuver.
    private val routePoints = listOf(
        NavPoint(45.000, 9.000),
        NavPoint(45.000, 9.001),
        NavPoint(45.000, 9.002),
        NavPoint(45.000, 9.003),
        NavPoint(45.000, 9.004)
    )
    private val arriveManeuver = NavManeuver(
        type = "4",
        modifier = null,
        instruction = "Arrive at destination",
        point = routePoints.last(),
        distanceMeters = 0.0,
        pointIndex = routePoints.lastIndex
    )
    private val route = NavRoute(
        points = routePoints,
        maneuvers = listOf(arriveManeuver),
        distanceMeters = 315.0,
        durationSeconds = 60.0
    )

    @Test
    fun `distance remaining shrinks as the rider advances along the route`() {
        val engine = NavigationEngine(route)
        val atStart = engine.update(routePoints.first())
        val atMidpoint = NavigationEngine(route).update(routePoints[2])
        val atEnd = NavigationEngine(route).update(routePoints.last())

        assertTrue(atStart.distanceRemainingMeters > atMidpoint.distanceRemainingMeters)
        assertTrue(atMidpoint.distanceRemainingMeters > atEnd.distanceRemainingMeters)
        assertEquals(0.0, atEnd.distanceRemainingMeters, 1.0)
        // Midpoint of 4 equal segments should be roughly half the total distance.
        assertEquals(atStart.distanceRemainingMeters / 2.0, atMidpoint.distanceRemainingMeters, atStart.distanceRemainingMeters * 0.05)
    }

    @Test
    fun `the single maneuver is selected while still ahead on the route`() {
        val progress = NavigationEngine(route).update(routePoints.first())
        assertEquals(arriveManeuver, progress.currentManeuver)
        assertTrue(progress.distanceToManeuverMeters > 0.0)
    }

    @Test
    fun `progress moves on to the next maneuver once the current one is passed`() {
        // A route with a turn mid-way and arrival at the end: passing the turn
        // must hand guidance to arrival, since a route can't progress past its
        // own last point once map-matched onto it.
        val turnManeuver = NavManeuver(
            type = "15",
            modifier = null,
            instruction = "Turn left",
            point = routePoints[2],
            distanceMeters = 0.0,
            pointIndex = 2
        )
        val twoManeuverRoute = route.copy(maneuvers = listOf(turnManeuver, arriveManeuver))

        val beforeTurn = NavigationEngine(twoManeuverRoute).update(routePoints.first())
        assertEquals(turnManeuver, beforeTurn.currentManeuver)

        val afterTurn = NavigationEngine(twoManeuverRoute).update(routePoints[3])
        assertEquals(arriveManeuver, afterTurn.currentManeuver)
    }

    @Test
    fun `off-route needs three consecutive far fixes before it fires, and resets on return`() {
        val engine = NavigationEngine(route)
        val onRoute = routePoints[2]
        val farAway = NavPoint(45.010, 9.002) // roughly 1.1 km off the route

        assertFalse(engine.update(farAway).offRoute)
        assertFalse(engine.update(farAway).offRoute)
        assertTrue(engine.update(farAway).offRoute)

        // Returning close to the route resets the streak.
        assertFalse(engine.update(onRoute).offRoute)
        assertFalse(engine.update(farAway).offRoute)
    }

    @Test
    fun `a route with fewer than two points reports zero distance and no crash`() {
        val singlePointRoute = NavRoute(
            points = listOf(routePoints.first()),
            maneuvers = emptyList(),
            distanceMeters = 0.0,
            durationSeconds = 0.0
        )
        val progress = NavigationEngine(singlePointRoute).update(routePoints.first())
        assertEquals(0.0, progress.distanceRemainingMeters, 0.0)
        assertFalse(progress.offRoute)
    }
}
