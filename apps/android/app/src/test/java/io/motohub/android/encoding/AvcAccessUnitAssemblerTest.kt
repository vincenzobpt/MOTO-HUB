package io.motohub.android.encoding

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AvcAccessUnitAssemblerTest {
    @Test
    fun `prepends Annex B codec config to AVCC keyframe`() {
        val assembler = AvcAccessUnitAssembler()
        assembler.consume(
            annexB(sps, pps),
            isCodecConfig = true,
            isKeyFrame = false
        )

        val output = assembler.consume(
            buildAvccAccessUnit(listOf(idr)),
            isCodecConfig = false,
            isKeyFrame = true
        )

        assertEquals(listOf(7, 8, 5), parseAvcNals(output!!)!!.map { it[0].toInt() and 0x1F })
        assertTrue(assembler.prependedCodecConfig)
    }

    @Test
    fun `does not duplicate parameter sets already in keyframe`() {
        val assembler = AvcAccessUnitAssembler()
        assembler.updateCodecConfig(annexB(sps, pps))
        val keyframe = buildAvccAccessUnit(listOf(sps, pps, idr))

        val output = assembler.consume(keyframe, isCodecConfig = false, isKeyFrame = true)

        assertArrayEquals(keyframe, output)
        assertFalse(assembler.prependedCodecConfig)
    }

    @Test
    fun `passes predictive frame through unchanged`() {
        val assembler = AvcAccessUnitAssembler()
        val predictive = buildAvccAccessUnit(listOf(byteArrayOf(0x41, 0x01)))

        assertArrayEquals(
            predictive,
            assembler.consume(predictive, isCodecConfig = false, isKeyFrame = false)
        )
    }

    @Test
    fun `holds codec configuration instead of emitting it`() {
        val assembler = AvcAccessUnitAssembler()

        assertNull(assembler.consume(annexB(sps, pps), isCodecConfig = true, isKeyFrame = false))
    }

    private fun annexB(vararg nals: ByteArray): ByteArray = nals.fold(ByteArray(0)) { output, nal ->
        output + byteArrayOf(0, 0, 0, 1) + nal
    }

    private companion object {
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1F)
        val pps = byteArrayOf(0x68, 0x01, 0x02)
        val idr = byteArrayOf(0x65, 0x11, 0x22)
    }
}
