package io.motohub.android.feature.ridedashboard.widget

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import io.motohub.android.feature.ridedashboard.RideGeoPoint
import io.motohub.android.feature.ridedashboard.RideTelemetrySnapshot
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.roundToInt

/** Heading instrument with a solar position and rider-facing glare indicator. */
class CompassWidget : DashboardWidget {

    override val id: String = DashboardWidgetIDs.COMPASS
    override val title: String = "Compass"
    override val description: String = "Heading, sun altitude and rider-relative sun position"

    override fun draw(
        canvas: Canvas,
        bounds: RectF,
        snapshot: RideTelemetrySnapshot,
        freshFix: Boolean,
        isLeftPanel: Boolean,
        ctx: WidgetDrawingContext
    ) {
        val left = bounds.left
        val right = bounds.right
        val top = bounds.top
        val centerX = (left + right) / 2f
        // GNSS bearing disappears at low speed/standstill. Prefer the fused rotation-vector
        // heading and retain the GNSS course only as a fallback for devices without a sensor.
        val sensorHeading = snapshot.deviceHeadingDegrees?.takeIf { it.isFinite() }
        val courseHeading = snapshot.bearingDegrees?.takeIf { it.isFinite() }
        val heading = sensorHeading ?: courseHeading
        // Solar position changes slowly, so do not blank it after the 15 s GNSS freshness window.
        // A stale fix is still useful and is marked in the header; a completely missing fix is not.
        val location = snapshot.position?.takeIf { snapshot.hasFix }
        val positionFresh = freshFix && location != null
        val sun = location?.let { solarPosition(it, System.currentTimeMillis()) }
        val relative = if (sun != null && heading != null) normalizeSigned(sun.azimuthDegrees - heading) else null
        val glareRisk = sun?.let { it.elevationDegrees > 8.0 && relative != null && abs(relative) <= 58.0 } == true

        ctx.drawPanelBorder(canvas, bounds, isLeftPanel)
        // Keep the title deliberately short: the status badge on the right must never collide
        // with it, especially for the wider "GLARE RISK" state.
        ctx.drawText(canvas, ctx.localized("COMPASS // SUN"), left + 18f, top + 25f, 10f,
            ctx.colors.muted, ctx.monoTypeface)
        ctx.drawText(canvas, when {
            glareRisk -> "GLARE RISK"
            sun != null && positionFresh -> "SUN TRACKING"
            sun != null -> "GNSS STALE"
            else -> "NO GNSS"
        }, right - 18f, top + 25f, 9f,
            when {
                glareRisk -> ctx.colors.warning
                sun != null -> ctx.colors.success
                else -> ctx.colors.muted
            }, ctx.monoBoldTypeface, Paint.Align.RIGHT)

        val horizon = RectF(left + 10f, top + 39f, right - 10f, top + 145f)
        drawSolarHorizon(canvas, horizon, sun, relative, ctx)

        val headingText = if (freshFix && heading != null) "${heading.roundToInt()}°" else "---°"
        ctx.drawText(canvas, headingText, centerX, top + 188f, 50f,
            if (heading != null) ctx.colors.text else ctx.colors.muted,
            ctx.monoBoldTypeface, Paint.Align.CENTER)
        ctx.drawText(canvas, cardinalDirection(heading), centerX, top + 208f, 12f,
            ctx.colors.muted, ctx.monoTypeface, Paint.Align.CENTER)

        val metricLeft = left + 54f
        val metricRight = right - 54f
        drawMetric(ctx, canvas, "SUN ALTITUDE", sun?.let { signedDegrees(it.elevationDegrees) } ?: "--",
            metricLeft, top + 230f, if (sun != null) ctx.colors.route else ctx.colors.muted)
        drawMetric(ctx, canvas, "SUN AZIMUTH", sun?.let { degrees(it.azimuthDegrees) } ?: "--",
            metricRight, top + 230f, if (sun != null) ctx.colors.route else ctx.colors.muted)
        drawMetric(ctx, canvas, "RELATIVE", relativeLabel(relative), metricLeft, top + 260f,
            if (glareRisk) ctx.colors.warning else ctx.colors.text)
        drawMetric(ctx, canvas, "HORIZON", horizonLabel(sun), metricRight, top + 260f,
            if (glareRisk) ctx.colors.warning else ctx.colors.muted)
    }

    private fun drawSolarHorizon(
        canvas: Canvas,
        area: RectF,
        sun: SunPosition?,
        relativeAzimuth: Double?,
        ctx: WidgetDrawingContext
    ) {
        val horizonY = area.top + area.height() * 0.58f
        ctx.fillPaint.style = Paint.Style.FILL
        ctx.fillPaint.shader = LinearGradient(
            0f, area.top, 0f, horizonY,
            COLOR_SKY_TOP, COLOR_SKY_BOTTOM, Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(area, 12f, 12f, ctx.fillPaint)
        ctx.fillPaint.shader = null

        ctx.fillPaint.color = COLOR_GROUND
        canvas.drawRect(area.left, horizonY, area.right, area.bottom, ctx.fillPaint)

        ctx.strokePaint.style = Paint.Style.STROKE
        ctx.strokePaint.strokeWidth = 1.2f
        ctx.strokePaint.color = COLOR_HORIZON
        canvas.drawLine(area.left, horizonY, area.right, horizonY, ctx.strokePaint)
        ctx.strokePaint.color = COLOR_GRID
        canvas.drawLine(area.centerX(), area.top + 17f, area.centerX(), area.bottom - 17f, ctx.strokePaint)

        ctx.drawText(canvas, ctx.localized("SUN / HORIZON"), area.left + 11f, area.top + 15f, 9f,
            COLOR_SUN, ctx.monoBoldTypeface)
        ctx.drawText(canvas, ctx.localized("AHEAD"), area.centerX(), area.bottom - 6f, 8f,
            ctx.colors.muted, ctx.monoTypeface, Paint.Align.CENTER)
        ctx.drawText(canvas, ctx.localized("LEFT"), area.left + 10f, area.bottom - 6f, 8f,
            ctx.colors.muted, ctx.monoTypeface)
        ctx.drawText(canvas, ctx.localized("RIGHT"), area.right - 10f, area.bottom - 6f, 8f,
            ctx.colors.muted, ctx.monoTypeface, Paint.Align.RIGHT)

        if (sun == null || relativeAzimuth == null) {
            ctx.drawText(canvas, ctx.localized("WAITING FOR POSITION + HEADING"), area.centerX(), horizonY - 3f, 8f,
                ctx.colors.muted, ctx.monoTypeface, Paint.Align.CENTER)
            return
        }

        val relativeRadians = Math.toRadians(relativeAzimuth)
        // sin() already tapers back toward the centre as the angle continues past 90° to
        // 180° (directly behind), so a single continuous formula covers both the front and
        // rear halves. The old code branched into a separate "rear lane" formula exactly at
        // ±90°, which made the dot teleport sideways to the centre the instant the heading
        // crossed that threshold — very noticeable while riding, since normal heading changes
        // cross it often. Blending instead of branching removes that jump.
        val absRelDeg = abs(relativeAzimuth)
        val rearBlend = smoothStep(REAR_FADE_START_DEG, REAR_FADE_END_DEG, absRelDeg)
        val sunX = area.centerX() + sin(relativeRadians).toFloat() * area.width() * 0.43f
        val elevationNormalized = (sun.elevationDegrees / 75.0).toFloat().coerceIn(-1f, 1f)
        val frontY = horizonY - elevationNormalized * area.height() * 0.37f
        // Reserve a clear lane below the rear badge so the sun never obscures its label.
        val rearY = (area.top + 43f - elevationNormalized * area.height() * 0.20f)
            .coerceAtLeast(area.top + 49f)
            .coerceAtMost(horizonY + 22f)
        val sunY = frontY + (rearY - frontY) * rearBlend

        if (rearBlend > 0f) {
            val alpha = (rearBlend * 255f).roundToInt().coerceIn(0, 255)
            val badge = RectF(area.centerX() - 34f, area.top + 21f, area.centerX() + 34f, area.top + 39f)
            ctx.fillPaint.shader = null
            ctx.fillPaint.color = COLOR_REAR_BADGE
            ctx.fillPaint.alpha = alpha
            canvas.drawRoundRect(badge, 6f, 6f, ctx.fillPaint)
            ctx.strokePaint.style = Paint.Style.STROKE
            ctx.strokePaint.strokeWidth = 1.2f
            ctx.strokePaint.color = COLOR_SUN
            ctx.strokePaint.alpha = alpha
            canvas.drawRoundRect(badge, 6f, 6f, ctx.strokePaint)
            ctx.drawText(canvas, ctx.localized("SUN BEHIND"), area.centerX(), area.top + 33f, 8f,
                withAlpha(COLOR_SUN, alpha), ctx.monoBoldTypeface, Paint.Align.CENTER)
            ctx.strokePaint.strokeWidth = 1.4f
            canvas.drawLine(area.centerX(), horizonY - 3f, sunX, sunY + 10f, ctx.strokePaint)
            // These paints are shared across the whole frame, so restore full opacity
            // before the unconditional sun-dot drawing below reuses them.
            ctx.fillPaint.alpha = 255
            ctx.strokePaint.alpha = 255
        }

        ctx.fillPaint.color = COLOR_SUN_GLOW
        canvas.drawCircle(sunX, sunY, 13f, ctx.fillPaint)
        ctx.fillPaint.color = COLOR_SUN
        canvas.drawCircle(sunX, sunY, 6f, ctx.fillPaint)
        ctx.strokePaint.style = Paint.Style.STROKE
        ctx.strokePaint.strokeWidth = 1.5f
        ctx.strokePaint.color = COLOR_SUN
        canvas.drawCircle(sunX, sunY, 9f, ctx.strokePaint)

    }

    private fun drawMetric(
        ctx: WidgetDrawingContext,
        canvas: Canvas,
        label: String,
        value: String,
        x: Float,
        y: Float,
        color: Int
    ) {
        ctx.drawText(canvas, value, x, y, 14f, color, ctx.monoBoldTypeface, Paint.Align.CENTER)
        ctx.drawText(canvas, label, x, y + 12f, 8f, ctx.colors.muted, ctx.monoTypeface, Paint.Align.CENTER)
    }

    private fun cardinalDirection(bearing: Float?): String {
        if (bearing == null) return "--"
        val index = ((bearing + 22.5f) / 45f).toInt() % 8
        return arrayOf("NORTH", "NE", "EAST", "SE", "SOUTH", "SW", "WEST", "NW")[index]
    }

    private fun relativeLabel(relative: Double?): String {
        if (relative == null) return "--"
        val magnitude = abs(relative)
        val side = if (relative >= 0.0) "RIGHT" else "LEFT"
        return when {
            magnitude <= 22.5 -> "AHEAD"
            magnitude <= 67.5 -> "AHEAD $side"
            magnitude <= 112.5 -> side
            magnitude <= 157.5 -> "BEHIND $side"
            else -> "BEHIND"
        }
    }

    private fun horizonLabel(sun: SunPosition?): String = when {
        sun == null -> "--"
        sun.elevationDegrees > 8.0 -> "ABOVE"
        sun.elevationDegrees < -6.0 -> "BELOW"
        else -> "HORIZON"
    }

    private fun degrees(value: Double): String = "${value.roundToInt()}°"

    private fun signedDegrees(value: Double): String =
        if (abs(value) < 0.5) "0°" else String.format(java.util.Locale.US, "%+.0f°", value)

    private fun normalizeSigned(value: Double): Double {
        var result = value % 360.0
        if (result > 180.0) result -= 360.0
        if (result < -180.0) result += 360.0
        return result
    }

    private fun solarPosition(location: RideGeoPoint, epochMillis: Long): SunPosition {
        val julianDay = epochMillis / 86_400_000.0 + 2_440_587.5
        val days = julianDay - 2_451_545.0
        val meanLongitude = normalizeDegrees(280.46 + 0.9856474 * days)
        val meanAnomaly = Math.toRadians(normalizeDegrees(357.528 + 0.9856003 * days))
        val eclipticLongitude = Math.toRadians(
            normalizeDegrees(meanLongitude + 1.915 * sin(meanAnomaly) + 0.020 * sin(2.0 * meanAnomaly))
        )
        val obliquity = Math.toRadians(23.439 - 0.0000004 * days)
        val rightAscension = Math.toDegrees(
            atan2(cos(obliquity) * sin(eclipticLongitude), cos(eclipticLongitude))
        ).let(::normalizeDegrees)
        val declination = Math.toDegrees(asin(sin(obliquity) * sin(eclipticLongitude)))
        val gmstHours = normalizeHours(18.697374558 + 24.06570982441908 * days)
        val localSiderealDegrees = normalizeDegrees(gmstHours * 15.0 + location.longitude)
        val hourAngle = Math.toRadians(normalizeSigned(localSiderealDegrees - rightAscension))
        val latitude = Math.toRadians(location.latitude.coerceIn(-90.0, 90.0))
        val declinationRadians = Math.toRadians(declination)
        val elevation = Math.toDegrees(
            asin(sin(latitude) * sin(declinationRadians) +
                cos(latitude) * cos(declinationRadians) * cos(hourAngle))
        )
        val azimuth = normalizeDegrees(
            Math.toDegrees(atan2(
                sin(hourAngle),
                cos(hourAngle) * sin(latitude) - tan(declinationRadians) * cos(latitude)
            )) + 180.0
        )
        return SunPosition(azimuth, elevation)
    }

    private fun normalizeDegrees(value: Double): Double {
        var result = value % 360.0
        if (result < 0.0) result += 360.0
        return result
    }

    private fun normalizeHours(value: Double): Double {
        var result = value % 24.0
        if (result < 0.0) result += 24.0
        return result
    }

    /** 0 below [edge0], 1 above [edge1], eased in between. */
    private fun smoothStep(edge0: Double, edge1: Double, value: Double): Float {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return (t * t * (3.0 - 2.0 * t)).toFloat()
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha shl 24)

    private data class SunPosition(val azimuthDegrees: Double, val elevationDegrees: Double)

    companion object {
        private const val REAR_FADE_START_DEG = 75.0
        private const val REAR_FADE_END_DEG = 105.0
        private val COLOR_SKY_TOP = 0xFF102D43.toInt()
        private val COLOR_SKY_BOTTOM = 0xFF1A5060.toInt()
        private val COLOR_GROUND = 0xFF101D1B.toInt()
        private val COLOR_HORIZON = 0xFF7DE3E0.toInt()
        private val COLOR_GRID = 0x552D6A7E
        private val COLOR_SUN = 0xFFFFC857.toInt()
        private val COLOR_SUN_GLOW = 0x44FFC857
        private val COLOR_REAR_BADGE = 0xFF17251F.toInt()
    }
}
