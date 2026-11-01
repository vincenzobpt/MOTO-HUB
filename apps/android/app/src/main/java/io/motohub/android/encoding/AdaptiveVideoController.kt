// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.encoding

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import io.motohub.android.feature.settings.MotoHubSettings
import io.motohub.android.feature.settings.VideoPowerMode
import java.util.Locale
import kotlin.math.min

/**
 * Adjusts a live encoder to what the phone and the bike link can actually carry.
 *
 * Thermal pressure protects the phone from sustained encoder throttling, and stays a Power mode
 * AUTO courtesy: a rider who picked a fixed mode asked for that frame rate and gets it.
 *
 * Lost frames are the downstream signal, and they are not a preference - a link that is discarding
 * frames wastes the bitrate spent on them and, on a GOP stream, smears the TFT until the next
 * keyframe. So the link backoff applies in every mode, multiplicative down and slow back up, with
 * the rider's mode as the ceiling it recovers to. Loss counts both access units the transport
 * refused and frames it accepted and then dropped: the CORE video pipe does the latter, and
 * counting only the former left the controller blind exactly when it was needed. A rider's CFMOTO
 * MTX800 on 2026-08-17 lost ~280 dashboard frames in that pipe against a single rejection, at a
 * fixed Smooth 30fps that nothing was allowed to lower.
 */
data class AdaptiveVideoDecision(
    val bitrate: Int,
    val frameRate: Int,
    val linkFactor: Float,
    /** Consecutive ticks without a single lost frame, this one included; see [AdaptiveVideoPolicy.regainStep]. */
    val cleanStreak: Int = 0,
    /** The frame rate the dash's own poll rate allows, or null when it is not holding the stream
     *  back; see [AdaptiveVideoPolicy.nextPullRateCap]. */
    val pullRateCap: Int? = null
)

object AdaptiveVideoPolicy {
    const val MIN_BITRATE = 600_000

    /** Lost frames in one tick that count as the link being over its head rather than unlucky. */
    const val LOSS_TRIGGER = 8

    const val LINK_MIN = 0.4f
    const val LINK_MAX = 1.0f

    /** Shed a fifth of the budget per congested tick; win back a twentieth per clean one. Down
     *  fast and up slow, so a link that is genuinely marginal settles instead of oscillating. */
    const val LINK_SHED = 0.8f
    const val LINK_REGAIN_STEP = 0.05f

    /**
     * Clean ticks after which the climb back speeds up, and the steps it speeds up to.
     *
     * A flat twentieth per 5s tick took a full minute to climb back from the floor, and 20s from a
     * single shed - so one burst of Wi-Fi interference left the TFT soft long after the link was
     * fine again. The first ticks after a loss still climb slowly, which is what keeps a genuinely
     * marginal link from oscillating: it loses frames again before the streak can build. Only a
     * link that stays perfectly clean earns the bigger steps. From the floor that is ~30s back to
     * full quality instead of 60, and ~15s from one shed instead of 20.
     */
    const val FAST_REGAIN_AFTER_CLEAN_TICKS = 3
    const val FASTER_REGAIN_AFTER_CLEAN_TICKS = 5
    const val FAST_REGAIN_STEP = 0.10f
    const val FASTER_REGAIN_STEP = 0.20f

    /** The slowest the link backoff may pace a stream. Below this the TFT reads as a slideshow,
     *  and a link that cannot carry 12fps has a problem no encoder setting is going to solve. */
    const val MIN_FRAME_RATE = 12

    /**
     * One rung of the thermal ladder: what the encoder is allowed once the phone has reached
     * [atLeastStatus]. Ordered hottest first, so the first rung a status reaches is its rung.
     */
    private data class ThermalRung(
        val atLeastStatus: Int,
        val bitrateScale: Float,
        val frameRateCeiling: Int,
    )

    private val THERMAL_LADDER = listOf(
        ThermalRung(PowerManager.THERMAL_STATUS_CRITICAL, 0.5f, MIN_FRAME_RATE),
        ThermalRung(PowerManager.THERMAL_STATUS_SEVERE, 0.6f, 15),
        ThermalRung(PowerManager.THERMAL_STATUS_MODERATE, 0.8f, 20),
    )

    /** Anything up to and including LIGHT: the phone is fine, take nothing away. */
    private val COOL = ThermalRung(Int.MIN_VALUE, 1.0f, Int.MAX_VALUE)

    private fun thermalRung(status: Int): ThermalRung =
        THERMAL_LADDER.firstOrNull { status >= it.atLeastStatus } ?: COOL

    fun thermalBitrateFactor(status: Int): Float = thermalRung(status).bitrateScale

    fun thermalFrameRateCap(status: Int, baseFrameRate: Int): Int =
        min(baseFrameRate, thermalRung(status).frameRateCeiling)

    /** A tick counts toward the streak only with no loss at all; any loss starts it over. */
    fun nextCleanStreak(previous: Int, lostFrames: Int): Int =
        if (lostFrames == 0) previous + 1 else 0

    /** How much of the budget a tick that is not struggling wins back, given its clean streak. */
    fun regainStep(cleanStreak: Int): Float = when {
        cleanStreak >= FASTER_REGAIN_AFTER_CLEAN_TICKS -> FASTER_REGAIN_STEP
        cleanStreak >= FAST_REGAIN_AFTER_CLEAN_TICKS -> FAST_REGAIN_STEP
        else -> LINK_REGAIN_STEP
    }

    /** @param cleanStreak consecutive loss-free ticks including this one ([nextCleanStreak]). */
    fun nextLinkFactor(previous: Float, lostFrames: Int, cleanStreak: Int = 0): Float {
        val struggling = lostFrames >= LOSS_TRIGGER
        val proposed =
            if (struggling) previous * LINK_SHED else previous + regainStep(cleanStreak)
        return proposed.coerceIn(LINK_MIN, LINK_MAX)
    }

    /** Fewer, larger-budget frames on a link that is losing them. Cutting only the bitrate leaves
     *  the same frame count queued on a transport whose queue is what overflowed. */
    fun linkFrameRateCap(baseFrameRate: Int, linkFactor: Float): Int =
        (baseFrameRate * linkFactor).toInt()
            .coerceAtLeast(MIN_FRAME_RATE.coerceAtMost(baseFrameRate))
            .coerceAtMost(baseFrameRate)

    /** Share of the dash's poll rate the stream may fill; the rest absorbs poll jitter, so the
     *  transport queue drains instead of sitting full. */
    const val PULL_RATE_HEADROOM = 0.9f

    /** The lowest a measured poll rate may pace the stream. A Wi-Fi stall that leaves the dash
     *  polling twice a second measures the stall, not the dash, and a cap read from it would take
     *  minutes to climb back one frame per tick. */
    const val MIN_PULL_RATE_CAP = 8

    /**
     * The frame rate the dash can actually take, learned from how often it polls.
     *
     * Set only on a tick that lost at least [LOSS_TRIGGER] frames: then the queue was full, every
     * poll found a frame, and the poll rate IS the dash's capacity. A frame or two lost in a tick
     * is a hiccup, not a full queue. On a clean tick the measurement also counts empty polls,
     * which says nothing about capacity, so the cap is not re-read - it climbs one frame per tick
     * once the link has been clean for [FASTER_REGAIN_AFTER_CLEAN_TICKS], and lets go at the base.
     *
     * Floored at [MIN_PULL_RATE_CAP], not at [MIN_FRAME_RATE] like the link cap: the MH Tray 125
     * (8F15-E4D6-0243) polled ~11.6 times a second, and the 12 fps floor kept its queue overflowing
     * for the whole ride. Nine whole pictures beat twelve with holes in them.
     */
    fun nextPullRateCap(
        previous: Int?,
        baseFrameRate: Int,
        dashPullRate: Float?,
        lostFrames: Int,
        cleanStreak: Int
    ): Int? = when {
        lostFrames >= LOSS_TRIGGER && dashPullRate != null ->
            (dashPullRate * PULL_RATE_HEADROOM).toInt()
                .coerceIn(MIN_PULL_RATE_CAP.coerceAtMost(baseFrameRate), baseFrameRate)
        previous == null -> null
        cleanStreak < FASTER_REGAIN_AFTER_CLEAN_TICKS -> previous
        previous + 1 >= baseFrameRate -> null
        else -> previous + 1
    }

    /**
     * @param baseFrameRate the ceiling this session may run at: the encoder's own rate under Power
     * mode AUTO, the mode's rate otherwise.
     * @param thermalStatus always `THERMAL_STATUS_NONE` outside AUTO, so a fixed mode never gets
     * throttled for heat behind the rider's back.
     * @param dashPullRate the dash's video polls per second, null when the transport cannot see them.
     */
    fun decide(
        baseBitrate: Int,
        baseFrameRate: Int,
        thermalStatus: Int,
        previousLinkFactor: Float,
        lostFrames: Int,
        previousCleanStreak: Int = 0,
        previousPullRateCap: Int? = null,
        dashPullRate: Float? = null
    ): AdaptiveVideoDecision {
        val cleanStreak = nextCleanStreak(previousCleanStreak, lostFrames)
        val linkFactor = nextLinkFactor(previousLinkFactor, lostFrames, cleanStreak)
        val factor = min(thermalBitrateFactor(thermalStatus), linkFactor)
        val pullRateCap = nextPullRateCap(previousPullRateCap, baseFrameRate, dashPullRate, lostFrames, cleanStreak)
        return AdaptiveVideoDecision(
            bitrate = (baseBitrate * factor).toInt().coerceIn(
                MIN_BITRATE.coerceAtMost(baseBitrate),
                baseBitrate
            ),
            frameRate = minOf(
                thermalFrameRateCap(thermalStatus, baseFrameRate),
                linkFrameRateCap(baseFrameRate, linkFactor),
                pullRateCap ?: baseFrameRate
            ),
            linkFactor = linkFactor,
            cleanStreak = cleanStreak,
            pullRateCap = pullRateCap
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
    private var cleanStreak = 0
    private var pullRateCap: Int? = null
    private var lastLostFrames = 0L
    private var appliedBitrate = -1
    private var appliedFrameRate = -1
    private var lastThermalLogAt = 0L

    fun reset() {
        linkFactor = AdaptiveVideoPolicy.LINK_MAX
        cleanStreak = 0
        pullRateCap = null
        lastLostFrames = 0L
        appliedBitrate = -1
        appliedFrameRate = -1
        lastThermalLogAt = 0L
    }

    /**
     * @param linkDown true while the session has no working transport at all - a broken video
     * pipe, a recovery in flight. Every access unit is rejected then, which is not the "weak bike
     * link" this policy exists to soften: reading it as one collapsed the bitrate to the floor
     * within twenty seconds and handed the recovered session a picture degraded for no reason.
     * The outage's rejections are absorbed, not judged.
     * @param dashPullRate the dash's video polls per second ([io.motohub.android.tbox.TBoxTransport.dashPullRate]).
     */
    fun onTick(encoder: AvcEncoder?, linkDown: Boolean = false, dashPullRate: Float? = null) {
        val activeEncoder = encoder ?: return
        logPhoneHeat()
        val baseBitrate = activeEncoder.targetBitrate()
        if (baseBitrate <= 0) return
        if (linkDown) {
            lastLostFrames = lostFramesTotal(activeEncoder)
            return
        }

        val mode = MotoHubSettings.videoPowerMode(appContext)
        val autoMode = mode == VideoPowerMode.AUTO
        // Outside AUTO the rider named a frame rate, so it becomes the ceiling the link backoff
        // works under, and heat is left out of it entirely.
        val ceilingFrameRate = if (autoMode) {
            activeEncoder.baseFrameRate
        } else {
            mode.frameRate.coerceAtMost(activeEncoder.baseFrameRate)
        }
        val thermalStatus = if (autoMode) {
            runCatching { powerManager?.currentThermalStatus }
                .getOrNull()
                ?: PowerManager.THERMAL_STATUS_NONE
        } else {
            PowerManager.THERMAL_STATUS_NONE
        }
        val totalLost = lostFramesTotal(activeEncoder)
        val lostThisTick = (totalLost - lastLostFrames)
            .coerceAtLeast(0L)
            .toInt()
        lastLostFrames = totalLost
        val decision = AdaptiveVideoPolicy.decide(
            baseBitrate = baseBitrate,
            baseFrameRate = ceilingFrameRate,
            thermalStatus = thermalStatus,
            previousLinkFactor = linkFactor,
            lostFrames = lostThisTick,
            previousCleanStreak = cleanStreak,
            previousPullRateCap = pullRateCap,
            dashPullRate = dashPullRate
        )
        linkFactor = decision.linkFactor
        cleanStreak = decision.cleanStreak
        pullRateCap = decision.pullRateCap
        if (decision.bitrate != appliedBitrate) {
            activeEncoder.setEncoderBitrate(decision.bitrate)
            appliedBitrate = decision.bitrate
            log(
                "[adaptive] ${mode.label} thermal=${thermalLabel(thermalStatus)} " +
                    "lost/tick=$lostThisTick link=${(linkFactor * 100).toInt()}% " +
                    "bitrate=${decision.bitrate / 1000}kbps"
            )
        }
        if (decision.frameRate != appliedFrameRate) {
            activeEncoder.setFrameCap(decision.frameRate)
            appliedFrameRate = decision.frameRate
            log(
                "[adaptive] ${mode.label} thermal=${thermalLabel(thermalStatus)} " +
                    "link=${(linkFactor * 100).toInt()}% fps=${decision.frameRate}" +
                    (dashPullRate?.let { " dashPulls=${"%.1f".format(Locale.US, it)}/s" } ?: "")
            )
        }
    }

    /**
     * Every [HEAT_LOG_MS], what the phone says about its own heat, read the same way in every
     * Power mode. The adaptive lines only show the steps AUTO took; a 2026-10-21 ticket ("the
     * phone gets very hot") had no battery temperature, no charger state and no forecast to
     * tell the curve - or a fix that flattened it - from a hot day.
     */
    private fun logPhoneHeat() {
        val now = SystemClock.elapsedRealtime()
        if (lastThermalLogAt != 0L && now - lastThermalLogAt < HEAT_LOG_MS) return
        lastThermalLogAt = now
        val status = runCatching { powerManager?.currentThermalStatus }.getOrNull()
        val headroom = runCatching { powerManager?.getThermalHeadroom(HEADROOM_FORECAST_SECONDS) }
            .getOrNull()
            ?.takeUnless { it.isNaN() }
        val battery = runCatching {
            appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val tenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?: Int.MIN_VALUE
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        log(
            "[heat] thermal=${status?.let(::thermalLabel) ?: "?"} " +
                "headroom${HEADROOM_FORECAST_SECONDS}s=${headroom?.let { "%.2f".format(Locale.US, it) } ?: "?"} " +
                "battery=${if (tenths == Int.MIN_VALUE) "?" else "%.1fC".format(Locale.US, tenths / 10f)} " +
                "plugged=${pluggedLabel(plugged)}"
        )
    }

    private fun pluggedLabel(plugged: Int): String = when (plugged) {
        -1 -> "?"
        0 -> "no"
        BatteryManager.BATTERY_PLUGGED_AC -> "ac"
        BatteryManager.BATTERY_PLUGGED_USB -> "usb"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
        BatteryManager.BATTERY_PLUGGED_DOCK -> "dock"
        else -> plugged.toString()
    }

    /** Access units the transport refused, plus frames it accepted and then dropped. Both are one
     *  picture that never reached the TFT. */
    private fun lostFramesTotal(encoder: AvcEncoder): Long =
        encoder.rejectedAccessUnitsTotal() + encoder.transportDroppedFramesTotal()

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

    private companion object {
        const val HEAT_LOG_MS = 30_000L
        /** Same horizon as the film export's thermal gate. */
        const val HEADROOM_FORECAST_SECONDS = 10
    }
}
