// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.encoding

/** One AVC encoder the phone advertises, as far as the ladder cares. */
internal data class AvcEncoderCandidate(val name: String, val hardware: Boolean)

/**
 * The order in which [AvcEncoder] asks the phone's AVC encoders to take its format.
 *
 * The system default - whatever `createEncoderByType` hands out - keeps first place, because it is
 * the encoder every dash has streamed from so far. The remaining hardware encoders follow, and the
 * software ones come last: a software encoder at TFT sizes is a perfectly good fallback, but only
 * once every hardware one has refused. Names repeat neither across the groups nor within them, and
 * a default the list does not know about (an alias, or a codec the list hides) is still tried.
 */
internal fun avcEncoderOrder(
    defaultName: String?,
    available: List<AvcEncoderCandidate>
): List<String> = buildList {
    defaultName?.takeIf(String::isNotBlank)?.let(::add)
    available.filter { it.hardware }.forEach { if (it.name !in this) add(it.name) }
    available.filterNot { it.hardware }.forEach { if (it.name !in this) add(it.name) }
}

/**
 * One `configure()` call. [bare] strips every optional key - profile/level, rate-control mode,
 * intra refresh, prepended parameter sets, the repeat-frame floor - down to the size, bitrate,
 * frame rate and keyframe interval, which is the least any encoder must accept.
 */
internal data class AvcConfigureAttempt(
    val forceBaseline: Boolean,
    val intraRefresh: Boolean,
    val bare: Boolean = false
) {
    fun describe(): String = when {
        bare -> "bare format (no profile, rate-control, prepended headers or repeat-frame keys)"
        else -> (if (forceBaseline) "Baseline profile at level 3.1" else "default profile") +
            if (intraRefresh) " with intra refresh" else ""
    }
}

/**
 * The combinations one encoder is offered, most wanted first.
 *
 * Baseline is the broadly-supported profile, intra refresh the burst-free stream shape, so those
 * lead when the codec advertises the feature - a codec that advertises FEATURE_IntraRefresh can
 * still reject the key on configure(), hence the fall-through. The bare format closes the list:
 * a rider's Huawei refused both non-refresh combinations outright (CodecException 0x80001001 on
 * every session from 2026-08-31 to 2026-09-04), and with nothing left to strip the session died
 * before the first frame. Nothing here changes the keyframe interval - the wire and the pacing
 * downstream are built on the shape that was asked for.
 */
internal fun avcConfigureAttempts(intraRefreshAvailable: Boolean): List<AvcConfigureAttempt> =
    buildList {
        if (intraRefreshAvailable) {
            add(AvcConfigureAttempt(forceBaseline = true, intraRefresh = true))
            add(AvcConfigureAttempt(forceBaseline = false, intraRefresh = true))
        }
        add(AvcConfigureAttempt(forceBaseline = true, intraRefresh = false))
        add(AvcConfigureAttempt(forceBaseline = false, intraRefresh = false))
        add(AvcConfigureAttempt(forceBaseline = false, intraRefresh = false, bare = true))
    }
