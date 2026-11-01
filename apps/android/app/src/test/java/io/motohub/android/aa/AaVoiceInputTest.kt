// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.aa

import android.bluetooth.BluetoothClass
import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AaVoiceInputTest {

    private val dash = VoiceCandidate(
        "VOGE-a53e", "AA:00", AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO
    )
    private val intercom = VoiceCandidate(
        "LX-B4FM", "BB:00", AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET
    )
    private val speaker = VoiceCandidate(
        "24122RKC7G", "", AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, null
    )

    @Test
    fun `a dash listed first loses to the intercom`() {
        val ordered = VoiceInputRanking.order(listOf(dash, intercom, speaker), emptySet())
        assertEquals(listOf(intercom, dash), ordered)
    }

    @Test
    fun `a route caught silent goes last whatever its class`() {
        val unknownDash = dash.copy(btClass = null)
        val quietIntercom = intercom
        val ordered = VoiceInputRanking.order(listOf(quietIntercom, unknownDash), setOf("BB:00"))
        assertEquals(listOf(unknownDash, quietIntercom), ordered)
    }

    @Test
    fun `digital zeros after the start-up transient condemn the route`() {
        val detector = SilentRouteDetector()
        val noise = ShortArray(320) { if (it % 7 == 0) 9 else 0 }
        val zeros = ShortArray(320)
        var verdicts = 0
        repeat(SilentRouteDetector.JUDGE_FROM_CHUNK) {
            if (detector.offer(noise, noise.size)) verdicts++
        }
        repeat(SilentRouteDetector.JUDGE_UNTIL_CHUNK) {
            if (detector.offer(zeros, zeros.size)) verdicts++
        }
        assertEquals(1, verdicts)
    }

    @Test
    fun `a quiet but live microphone is never condemned`() {
        val detector = SilentRouteDetector()
        val quiet = ShortArray(320) { if (it == 100) 1 else 0 }
        var condemned = false
        repeat(SilentRouteDetector.JUDGE_UNTIL_CHUNK + 20) {
            condemned = condemned || detector.offer(quiet, quiet.size)
        }
        assertFalse(condemned)
    }

    @Test
    fun `the verdict waits for the whole window`() {
        val detector = SilentRouteDetector()
        val zeros = ShortArray(320)
        repeat(SilentRouteDetector.JUDGE_UNTIL_CHUNK - 1) {
            assertFalse(detector.offer(zeros, zeros.size))
        }
        assertTrue(detector.offer(zeros, zeros.size))
    }
}
