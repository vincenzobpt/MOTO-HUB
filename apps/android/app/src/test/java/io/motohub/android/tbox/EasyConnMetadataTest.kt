package io.motohub.android.tbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EasyConnMetadataTest {
    @Test
    fun usesThePackageAdvertisedByTheTbox() {
        assertEquals(
            "net.easyconn.receiver",
            decodeEasyConnPackage("  net.easyconn.receiver  ".toByteArray())
        )
    }

    @Test
    fun rejectsMissingPackageMetadataInsteadOfInventingOne() {
        assertNull(decodeEasyConnPackage(null))
        assertNull(decodeEasyConnPackage("   ".toByteArray()))
    }
}
