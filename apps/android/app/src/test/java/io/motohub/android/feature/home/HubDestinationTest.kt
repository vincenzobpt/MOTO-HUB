package io.motohub.android.feature.home

import io.motohub.android.session.HubSessionState
import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.session.SessionPhase
import org.junit.Assert.assertEquals
import org.junit.Test

class HubDestinationTest {
    private val motorcycle = MotorcycleProfile("TBOX-test", "password")

    @Test
    fun `profile missing always opens pairing including errors`() {
        val session = HubSessionState(phase = SessionPhase.ERROR, message = "Invalid QR code")

        assertEquals(HubDestination.PAIRING, resolveHubDestination(session, false))
    }

    @Test
    fun `network and discovery phases open connection progress`() {
        assertEquals(
            HubDestination.CONNECTING,
            resolveHubDestination(session(SessionPhase.CONNECTING_NETWORK), false)
        )
        assertEquals(
            HubDestination.CONNECTING,
            resolveHubDestination(session(SessionPhase.DISCOVERING_TBOX), false)
        )
    }

    @Test
    fun `ready session opens mode selection`() {
        assertEquals(
            HubDestination.MODE_SELECTION,
            resolveHubDestination(session(SessionPhase.READY), false)
        )
    }

    @Test
    fun `projection and android auto open active session`() {
        assertEquals(
            HubDestination.ACTIVE_SESSION,
            resolveHubDestination(session(SessionPhase.REQUESTING_PROJECTION), false)
        )
        assertEquals(
            HubDestination.ACTIVE_SESSION,
            resolveHubDestination(session(SessionPhase.CAPTURING), false)
        )
        assertEquals(
            HubDestination.ACTIVE_SESSION,
            resolveHubDestination(session(SessionPhase.READY), true)
        )
    }

    @Test
    fun `saved profile requiring network opens connection screen`() {
        assertEquals(
            HubDestination.CONNECTION,
            resolveHubDestination(session(SessionPhase.NETWORK_SETUP_REQUIRED), false)
        )
        assertEquals(
            HubDestination.CONNECTION,
            resolveHubDestination(session(SessionPhase.ERROR), false)
        )
    }

    private fun session(phase: SessionPhase) = HubSessionState(
        phase = phase,
        motorcycle = motorcycle
    )
}
