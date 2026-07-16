package io.motohub.android.encoding

import org.junit.Assert.assertEquals
import org.junit.Test

class EncoderProfileTest {
    @Test
    fun `legacy landscape area is aligned to complete H264 macroblocks`() {
        assertEquals(EncoderProfile(width = 800, height = 384), EncoderProfile.forTBoxArea(800, 386))
    }

    @Test
    fun `portrait area is aligned without model-specific dimensions`() {
        assertEquals(EncoderProfile(width = 800, height = 944), EncoderProfile.forTBoxArea(800, 951))
    }

    @Test
    fun `already aligned runtime area remains unchanged`() {
        assertEquals(EncoderProfile(width = 1280, height = 576), EncoderProfile.forTBoxArea(1280, 576))
    }

    @Test
    fun `unknown runtime area is aligned without a device profile`() {
        assertEquals(EncoderProfile(width = 1024, height = 592), EncoderProfile.forTBoxArea(1024, 601))
    }
}
