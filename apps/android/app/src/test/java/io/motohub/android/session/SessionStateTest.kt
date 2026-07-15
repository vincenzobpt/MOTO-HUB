package io.motohub.android.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateTest {
    @Test
    fun motorcycleProfileRequiresNetworkSetup() {
        val state = HubSessionState().withMotorcycle(MotorcycleProfile("TBOX-01", "secret"))

        assertEquals(SessionPhase.NETWORK_SETUP_REQUIRED, state.phase)
        assertEquals("TBOX-01", state.motorcycle?.ssid)
        assertTrue(state.message.contains("discovery"))
    }
}
