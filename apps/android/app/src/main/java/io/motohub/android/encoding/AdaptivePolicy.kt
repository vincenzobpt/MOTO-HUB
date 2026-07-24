package io.motohub.android.encoding

import android.os.PowerManager

/**
 * Pure adaptation math for power mode AUTO (no Android dependencies, unit-testable).
 *
 * Two pressures pull video quality down; the more aggressive wins:
 * - **Thermal**: maps PowerManager thermal status to bitrate factor + fps cap
 * - **Link (AIMD)**: dropped-frame congestion → multiplicative back-off, additive recovery
 *
 * Adapted from OpenCfMoto's AdaptivePolicy.
 */
data class AdaptiveDecision(val bitrate: Int, val fps: Int, val linkFactor: Float)

object AdaptivePolicy {
    const val LINK_MIN = 0.4f
    const val LINK_MAX = 1.0f
    const val LINK_DECREASE = 0.8f
    const val LINK_INCREASE_STEP = 0.05f
    const val DROP_CONGESTION_THRESHOLD = 8
    const val MIN_BITRATE = 600_000

    fun thermalBitrateFactor(status: Int): Float = when {
        status <= PowerManager.THERMAL_STATUS_LIGHT -> 1.0f
        status == PowerManager.THERMAL_STATUS_MODERATE -> 0.8f
        status == PowerManager.THERMAL_STATUS_SEVERE -> 0.6f
        else -> 0.5f
    }

    fun thermalFpsCap(status: Int, baseFps: Int): Int = when {
        status <= PowerManager.THERMAL_STATUS_LIGHT -> baseFps
        status == PowerManager.THERMAL_STATUS_MODERATE -> minOf(baseFps, 20)
        status == PowerManager.THERMAL_STATUS_SEVERE -> minOf(baseFps, 15)
        else -> minOf(baseFps, 12)
    }

    fun nextLinkFactor(prev: Float, dropsThisTick: Int): Float =
        if (dropsThisTick >= DROP_CONGESTION_THRESHOLD)
            (prev * LINK_DECREASE).coerceAtLeast(LINK_MIN)
        else
            (prev + LINK_INCREASE_STEP).coerceAtMost(LINK_MAX)

    fun decide(
        baseBitrate: Int,
        baseFps: Int,
        thermalStatus: Int,
        prevLinkFactor: Float,
        dropsThisTick: Int,
    ): AdaptiveDecision {
        val link = nextLinkFactor(prevLinkFactor, dropsThisTick)
        val factor = minOf(thermalBitrateFactor(thermalStatus), link)
        val bitrate = (baseBitrate * factor).toInt()
            .coerceIn(MIN_BITRATE.coerceAtMost(baseBitrate), baseBitrate)
        val fps = thermalFpsCap(thermalStatus, baseFps)
        return AdaptiveDecision(bitrate, fps, link)
    }
}
