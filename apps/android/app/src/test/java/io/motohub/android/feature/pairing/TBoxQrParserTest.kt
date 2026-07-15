package io.motohub.android.feature.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TBoxQrParserTest {
    @Test
    fun parsesEasyConnQrWithEncodedCredentials() {
        val result = TBoxQrParser.parse(
            "https://setup.carbit.com/connect?ssid=TBOX%20RIDE&pwd=pass%2Bword&auth=WPA2&name=My%20Bike"
        )

        assertEquals("TBOX RIDE", result.getOrThrow().ssid)
        assertEquals("pass+word", result.getOrThrow().password)
        assertEquals("My Bike", result.getOrThrow().displayName)
    }

    @Test
    fun preservesTheQrModelIdAsAnOpaqueTboxIdentifier() {
        val result = TBoxQrParser.parse(
            "http://www.carbit.com.cn/downsdk/657/658/_sdk?modelid=37416&sn=test&action=9&ssid=TBOX-test&pwd=example&auth=wpa2-psk&mac=00%3A00%3A00%3A00%3A00%3A00&name=TBOX-test"
        )

        assertEquals("TBOX-test", result.getOrThrow().ssid)
        assertEquals("example", result.getOrThrow().password)
        assertEquals("wpa2-psk", result.getOrThrow().encryption)
        assertEquals("37416", result.getOrThrow().modelId)
    }

    @Test
    fun rejectsQrFromAnotherHost() {
        assertTrue(TBoxQrParser.parse("https://example.com/?ssid=TBOX").isFailure)
    }
}
