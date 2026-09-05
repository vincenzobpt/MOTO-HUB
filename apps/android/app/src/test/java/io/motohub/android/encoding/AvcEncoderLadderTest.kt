// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.encoding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvcEncoderLadderTest {
    private val hisi = AvcEncoderCandidate("OMX.hisi.video.encoder.avc", hardware = true)
    private val google = AvcEncoderCandidate("c2.android.avc.encoder", hardware = false)
    private val legacyGoogle = AvcEncoderCandidate("OMX.google.h264.encoder", hardware = false)

    @Test
    fun `the system default leads, hardware follows, software closes`() {
        // The rider's Huawei: the default Kirin encoder refused everything and the software
        // encoder was never asked. It is asked now - last, after every hardware one.
        assertEquals(
            listOf("OMX.hisi.video.encoder.avc", "c2.android.avc.encoder", "OMX.google.h264.encoder"),
            avcEncoderOrder(hisi.name, listOf(google, hisi, legacyGoogle))
        )
    }

    @Test
    fun `a second hardware encoder is tried before any software one`() {
        val qcom = AvcEncoderCandidate("c2.qti.avc.encoder", hardware = true)
        assertEquals(
            listOf(hisi.name, qcom.name, google.name),
            avcEncoderOrder(hisi.name, listOf(google, qcom, hisi))
        )
    }

    @Test
    fun `a default the codec list does not name is still tried first`() {
        assertEquals(
            listOf("OMX.vendor.alias", hisi.name, google.name),
            avcEncoderOrder("OMX.vendor.alias", listOf(hisi, google))
        )
    }

    @Test
    fun `no default and no list still yields nothing rather than crashing`() {
        assertEquals(emptyList<String>(), avcEncoderOrder(null, emptyList()))
        assertEquals(listOf(google.name), avcEncoderOrder("", listOf(google)))
    }

    @Test
    fun `the bare format is the last resort on every encoder`() {
        listOf(true, false).forEach { intraRefresh ->
            val attempts = avcConfigureAttempts(intraRefreshAvailable = intraRefresh)
            assertTrue(attempts.last().bare)
            assertEquals(1, attempts.count { it.bare })
            // The bare attempt asks for nothing optional - not even the profile.
            assertEquals(AvcConfigureAttempt(forceBaseline = false, intraRefresh = false, bare = true), attempts.last())
        }
    }

    @Test
    fun `intra refresh combinations lead only when the codec advertises the feature`() {
        assertEquals(
            listOf(
                AvcConfigureAttempt(forceBaseline = true, intraRefresh = true),
                AvcConfigureAttempt(forceBaseline = false, intraRefresh = true),
                AvcConfigureAttempt(forceBaseline = true, intraRefresh = false),
                AvcConfigureAttempt(forceBaseline = false, intraRefresh = false),
                AvcConfigureAttempt(forceBaseline = false, intraRefresh = false, bare = true)
            ),
            avcConfigureAttempts(intraRefreshAvailable = true)
        )
        assertEquals(
            listOf(
                AvcConfigureAttempt(forceBaseline = true, intraRefresh = false),
                AvcConfigureAttempt(forceBaseline = false, intraRefresh = false),
                AvcConfigureAttempt(forceBaseline = false, intraRefresh = false, bare = true)
            ),
            avcConfigureAttempts(intraRefreshAvailable = false)
        )
    }

    @Test
    fun `attempt descriptions name what was asked for`() {
        assertEquals(
            "Baseline profile at level 3.1 with intra refresh",
            AvcConfigureAttempt(forceBaseline = true, intraRefresh = true).describe()
        )
        assertEquals("default profile", AvcConfigureAttempt(forceBaseline = false, intraRefresh = false).describe())
        assertTrue(AvcConfigureAttempt(forceBaseline = false, intraRefresh = false, bare = true).describe().startsWith("bare format"))
    }
}
