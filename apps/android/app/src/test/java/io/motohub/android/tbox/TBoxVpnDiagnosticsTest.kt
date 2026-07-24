package io.motohub.android.tbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TBoxVpnDiagnosticsTest {
    @Test
    fun detectsAndroidPermissionErrorsThroughNestedCauses() {
        val error = IllegalStateException(
            "socket setup failed",
            SecurityException("connect failed: Operation not permitted")
        )

        assertTrue(TBoxVpnDiagnostics.isVpnBindBlocked(error))
    }

    @Test
    fun doesNotClassifyOrdinaryTimeoutAsVpnFailureWithoutActiveVpn() {
        val error = java.net.SocketTimeoutException("connection timed out")

        assertFalse(TBoxVpnDiagnostics.isVpnBindBlocked(error))
        assertNull(TBoxVpnDiagnostics.userFacingMessage(error, activeVpnLabel = null))
    }

    @Test
    fun reportsActionableMessageWhenVpnIsActive() {
        assertEquals(
            TBoxVpnDiagnostics.VPN_BLOCKING_MESSAGE,
            TBoxVpnDiagnostics.userFacingMessage(
                error = IllegalStateException("network request timed out"),
                activeVpnLabel = "WireGuard"
            )
        )
    }
}
