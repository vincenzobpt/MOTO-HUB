// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.aa

import android.bluetooth.BluetoothClass
import android.media.AudioDeviceInfo

/**
 * One device Android offers as a call route, reduced to what choosing among them needs.
 * [btClass] is the Bluetooth device class, null when it could not be read.
 */
internal data class VoiceCandidate(
    val name: String,
    val address: String,
    val type: Int,
    val btClass: Int?
) {
    override fun toString(): String =
        "$name/type$type/class${btClass?.let { "0x" + it.toString(16) } ?: "?"}"
}

/**
 * Which Bluetooth call route the Assistant should record from, best first.
 *
 * Taking the first call-capable device Android lists is wrong on a motorcycle: many dashboards
 * are themselves hands-free devices (the TFT answers phone calls), so the phone offers the dash
 * next to the helmet intercom — and the dash has no microphone. Rider 51be992e's VOGE TFT was
 * listed first and every Assistant request recorded digital silence from it while the LX-B4FM
 * intercom sat connected and unused.
 *
 * The order is: routes already caught carrying nothing last, then by Bluetooth class (something
 * worn on the head before a generic hands-free before a car/vehicle unit), then by route type.
 */
internal object VoiceInputRanking {

    val VOICE_TYPES = listOf(
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_HEARING_AID
    )

    fun order(candidates: List<VoiceCandidate>, silentAddresses: Set<String>): List<VoiceCandidate> =
        candidates
            .filter { it.type in VOICE_TYPES }
            .sortedWith(
                compareBy<VoiceCandidate>(
                    { it.address in silentAddresses },
                    { classRank(it.btClass) },
                    { VOICE_TYPES.indexOf(it.type) }
                )
            )

    private fun classRank(btClass: Int?): Int = when (btClass) {
        BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET -> 0
        BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE -> 1
        null -> 2
        BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> 4
        else -> 3
    }
}

/**
 * Tells a route that carries nothing from a quiet rider.
 *
 * A dash without a microphone answers the SCO link with a few samples of start-up noise and then
 * exact zeros, forever. A real microphone — even in a silent room, even behind a noise
 * suppressor — never produces 700 ms of perfect digital zero. So the verdict is taken on the
 * window after the start-up transient: every sample in it exactly zero means the route is dead.
 */
internal class SilentRouteDetector {
    companion object {
        /** 20 ms chunks: judge from 0.5 s to 1.2 s after the recorder starts. */
        const val JUDGE_FROM_CHUNK = 25
        const val JUDGE_UNTIL_CHUNK = 60
    }

    private var chunks = 0
    private var anySignal = false
    private var decided = false

    /** Feeds one chunk; returns true exactly once, when the window closed with only zeros. */
    fun offer(samples: ShortArray, count: Int): Boolean {
        if (decided) return false
        val index = chunks++
        if (index < JUDGE_FROM_CHUNK) return false
        if (!anySignal) {
            for (i in 0 until count) {
                if (samples[i].toInt() != 0) {
                    anySignal = true
                    break
                }
            }
        }
        if (index + 1 < JUDGE_UNTIL_CHUNK) return false
        decided = true
        return !anySignal
    }
}
