package io.motohub.android.feature.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticLogShareTest {
    @Test
    fun `diagnostic file name is stable and readable`() {
        assertEquals(
            "MOTO-HUB-diagnostics-19700101-000000.txt",
            DiagnosticLogShare.fileName(0L)
        )
    }
}
