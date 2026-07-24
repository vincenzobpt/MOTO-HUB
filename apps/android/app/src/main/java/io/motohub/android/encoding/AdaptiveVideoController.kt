package io.motohub.android.encoding

import android.content.Context
import android.os.PowerManager
import io.motohub.android.feature.settings.MotoHubSettings
import io.motohub.android.feature.settings.VideoPowerMode
import kotlin.math.min

/**
 * Adjusts a live encoder only when the rider selects Power mode AUTO.
 *
 * Thermal pressure protects the phone from sustained encoder throttling. Rejected access units are
 * the downstream signal: bitrate backs off multiplicatively and recovers slowly, so a weak bike
 * link becomes softer instead of repeatedly dropping the projection.
 */
data class AdaptiveVideoDecision(
    val bitrate: Int,
    val frameRate: Int,
    val linkFactor: Float
)

object AdaptiveVideoPolicy {
    const val MIN_BITRATE = 600_000
    const val DROP_THRESHOLD = 8
    const val LINK_MIN = 0.4f
    const val LINK_MAX = 1.0f
    const val LINK_BACKOFF = 0.8f
    const val LINK_RECOVERY_STEP = 0.05f

    fun thermalBitrateFactor(status: Int): Float = when {
        status <= PowerManager.THERMAL_STATUS_LIGHT -> 1.0f
        status == PowerManager.THERMAL_STATUS_MODERATE -> 0.8f
        status == PowerManager.THERMAL_STATUS_SEVERE -> 0.6f
        else -> 0.5f
    }

    fun thermalFrameRateCap(status: Int, baseFrameRate: Int): Int = when {
        status <= PowerManager.THERMAL_STATUS_LIGHT -> baseFrameRate
        status == PowerManager.THERMAL_STATUS_MODERATE -> min(baseFrameRate, 20)
        status == PowerManager.THERMAL_STATUS_SEVERE -> min(baseFrameRate, 15)
        else -> min(baseFrameRate, 12)
    }

    fun nextLinkFactor(previous: Float, rejectedFrames: Int): Float = if (
        rejectedFrames >= DROP_THRESHOLD
    ) {
        (previous * LINK_BACKOFF).coerceAtLeast(LINK_MIN)
    } else {
        (previous + LINK_RECOVERY_STEP).coerceAtMost(LINK_MAX)
    }

    fun decide(
        baseBitrate: Int,
        baseFrameRate: Int,
        thermalStatus: Int,
        previousLinkFactor: Float,
        rejectedFrames: Int
    ): AdaptiveVideoDecision {
        val linkFactor = nextLinkFactor(previousLinkFactor, rejectedFrames)
        val factor = min(thermalBitrateFactor(thermalStatus), linkFactor)
        return AdaptiveVideoDecision(
            bitrate = (baseBitrate * factor).toInt().coerceIn(
                MIN_BITRATE.coerceAtMost(baseBitrate),
                baseBitrate
            ),
            frameRate = thermalFrameRateCap(thermalStatus, baseFrameRate),
            linkFactor = linkFactor
        )
    }
}

/** Owns the live policy state for one projection encoder. */
class AdaptiveVideoController(
    context: Context,
    private val log: (String) -> Unit
) {
    // Services can construct their fields before attachBaseContext(). Resolve the application
    // context only when the first stream tick runs, after Android has attached the component.
    private val componentContext = context
    private val appContext by lazy { componentContext.applicationContext ?: componentContext }
    private val powerManager by lazy { appContext.getSystemService(PowerManager::class.java) }
    private var linkFactor = AdaptiveVideoPolicy.LINK_MAX
    private var lastRejectedFrames = 0L
    private var appliedBitrate = -1
    private var appliedFrameRate = -1
    private var adapting = false

    fun reset() {
        linkFactor = AdaptiveVideoPolicy.LINK_MAX
        lastRejectedFrames = 0L
        appliedBitrate = -1
        appliedFrameRate = -1
        adapting = false
    }

    fun onTick(encoder: AvcEncoder?) {
        val activeEncoder = encoder ?: return
        val baseBitrate = activeEncoder.targetBitrate()
        if (baseBitrate <= 0) return

        val mode = MotoHubSettings.videoPowerMode(appContext)
        if (mode != VideoPowerMode.AUTO) {
            if (adapting || appliedFrameRate != mode.frameRate) {
                activeEncoder.setEncoderBitrate(baseBitrate)
                activeEncoder.setFrameCap(mode.frameRate.coerceAtMost(activeEncoder.baseFrameRate))
                log("[adaptive] AUTO off; restored ${mode.label} at ${mode.frameRate}fps")
                appliedBitrate = baseBitrate
                appliedFrameRate = mode.frameRate.coerceAtMost(activeEncoder.baseFrameRate)
                linkFactor = AdaptiveVideoPolicy.LINK_MAX
                lastRejectedFrames = activeEncoder.rejectedAccessUnitsTotal()
                adapting = false
            }
            return
        }

        val thermalStatus = runCatching { powerManager?.currentThermalStatus }
            .getOrNull()
            ?: PowerManager.THERMAL_STATUS_NONE
        val totalRejected = activeEncoder.rejectedAccessUnitsTotal()
        val rejectedThisTick = (totalRejected - lastRejectedFrames)
            .coerceAtLeast(0L)
            .toInt()
        lastRejectedFrames = totalRejected
        val decision = AdaptiveVideoPolicy.decide(
            baseBitrate = baseBitrate,
            baseFrameRate = activeEncoder.baseFrameRate,
            thermalStatus = thermalStatus,
            previousLinkFactor = linkFactor,
            rejectedFrames = rejectedThisTick
        )
        linkFactor = decision.linkFactor
        if (decision.bitrate != appliedBitrate) {
            activeEncoder.setEncoderBitrate(decision.bitrate)
            appliedBitrate = decision.bitrate
            adapting = true
            log(
                "[adaptive] thermal=${thermalLabel(thermalStatus)} " +
                    "rejected/tick=$rejectedThisTick link=${(linkFactor * 100).toInt()}% " +
                    "bitrate=${decision.bitrate / 1000}kbps"
            )
        }
        if (decision.frameRate != appliedFrameRate) {
            activeEncoder.setFrameCap(decision.frameRate)
            appliedFrameRate = decision.frameRate
            adapting = true
            log("[adaptive] thermal=${thermalLabel(thermalStatus)} fps=${decision.frameRate}")
        }
    }

    private fun thermalLabel(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> status.toString()
    }
}
