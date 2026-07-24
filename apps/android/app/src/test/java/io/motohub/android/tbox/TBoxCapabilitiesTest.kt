package io.motohub.android.tbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TBoxCapabilitiesTest {
    @Test
    fun `maps whitelisted CLIENT_INFO fields`() {
        val result = tBoxCapabilitiesFrom(
            mapOf(
                "HUID" to "secret-huid",
                "uuid" to "secret-uuid",
                "btPin" to "1234",
                "HUName" to "CFDL26",
                "carBrand" to "CFMOTO",
                "carModel" to "reported-model",
                "pxcVersion" to "1.2.3",
                "dpi" to 160,
                "supportScreenMirroring" to true,
                "supportScreenTouch" to false,
                "supportMirrorReconnect" to true
            )
        )

        assertEquals("CFDL26", result.huName)
        assertEquals("CFMOTO", result.carBrand)
        assertEquals("reported-model", result.carModel)
        assertEquals("1.2.3", result.pxcVersion)
        assertEquals(160, result.dpi)
        assertTrue(result.screenMirroring == true)
        assertFalse(result.screenTouch == true)
        assertTrue(result.mirrorReconnect == true)
    }

    @Test
    fun `preserves missing capability flags as not reported`() {
        val result = tBoxCapabilitiesFrom(mapOf("HUName" to "T-Box"))

        assertNull(result.screenMirroring)
        assertNull(result.screenTouch)
        assertNull(result.microphone)
    }

}
