package io.motohub.android.androidauto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewGeometryTest {
    private val source = DisplayGeometry(800, 480)

    @Test
    fun `portrait preview contains the complete Android Auto source`() {
        val viewport = calculatePreviewViewport(DisplayGeometry(900, 1800), source)

        assertEquals(0, viewport.x)
        assertEquals(630, viewport.y)
        assertEquals(900, viewport.width)
        assertEquals(540, viewport.height)
        assertEquals(0 to 0, viewport.mapToSource(0, 630))
        assertEquals(799 to 479, viewport.mapToSource(899, 1169))
        assertNull(viewport.mapToSource(450, 629))
    }

    @Test
    fun `landscape preview contains the complete Android Auto source`() {
        val viewport = calculatePreviewViewport(DisplayGeometry(2048, 607), source)

        assertEquals(518, viewport.x)
        assertEquals(0, viewport.y)
        assertEquals(1011, viewport.width)
        assertEquals(607, viewport.height)
        assertEquals(0 to 0, viewport.mapToSource(518, 0))
        assertEquals(799 to 479, viewport.mapToSource(1528, 606))
        assertNull(viewport.mapToSource(517, 300))
    }
}
