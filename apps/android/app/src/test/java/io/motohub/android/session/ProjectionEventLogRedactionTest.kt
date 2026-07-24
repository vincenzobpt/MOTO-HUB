package io.motohub.android.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProjectionEventLogRedactionTest {
    @Test
    fun redactsPasswordKeyValuePairs() {
        assertEquals(
            "Connecting with password=<redacted> to SSID CFMOTO-1234",
            ProjectionEventLog.redact("Connecting with password=Sup3rSecret to SSID CFMOTO-1234")
        )
    }

    @Test
    fun redactsPskAndPassphraseSynonyms() {
        assertFalse(ProjectionEventLog.redact("psk: abc123def").contains("abc123def"))
        assertFalse(ProjectionEventLog.redact("passphrase=hunter2!!").contains("hunter2"))
    }

    @Test
    fun redactsIpv4AddressesInsideFreeText() {
        val redacted = ProjectionEventLog.redact(
            "java.net.ConnectException: failed to connect to /192.168.49.1 (port 7788)"
        )
        assertFalse(redacted.contains("192.168.49.1"))
        assertEquals(
            "java.net.ConnectException: failed to connect to /<redacted-ip> (port 7788)",
            redacted
        )
    }

    @Test
    fun redactsColonAndHyphenSeparatedMacAddresses() {
        assertFalse(ProjectionEventLog.redact("Peer MAC AA:BB:CC:DD:EE:FF found").contains("AA:BB:CC:DD:EE:FF"))
        assertFalse(ProjectionEventLog.redact("Peer MAC aa-bb-cc-dd-ee-ff found").contains("aa-bb-cc-dd-ee-ff"))
    }

    @Test
    fun doesNotMangleVersionOrResolutionStrings() {
        val message = "MOTO-HUB 0.9.0-beta.10 negotiated 1280x720@30fps"
        assertEquals(message, ProjectionEventLog.redact(message))
    }
}
