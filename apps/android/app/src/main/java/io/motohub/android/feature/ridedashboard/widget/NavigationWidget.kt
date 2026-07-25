package io.motohub.android.feature.ridedashboard.widget

import io.motohub.android.i18n.motoHubText

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import io.motohub.android.feature.ridedashboard.RideTelemetrySnapshot
import io.motohub.android.feature.ridedashboard.nav.ManeuverDirection
import io.motohub.android.feature.ridedashboard.nav.maneuverDirection
import io.motohub.android.units.UnitFormat
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Navigation + Sun-position widget.
 *
 * When a route is active it shows a maneuver icon, distance, instruction,
 * remaining distance and ETA. Below that — or filling the whole panel when
 * there's no route — a horizon-arc plots where the sun actually is right
 * now for today's latitude and season, alongside sunrise/sunset times,
 * remaining daylight and an estimated rideable distance before dark.
 */
class NavigationWidget : DashboardWidget {

    override val id: String = DashboardWidgetIDs.NAVIGATION
    override val title: String = "Navigation"
    override val description: String = "Next turn, ETA, sun position, daylight remaining"

    // Reusable arrow path (points up, centred on 0,0), sized for the icon puck.
    private val arrowPath = Path().apply {
        moveTo(0f, -10f)     // tip
        lineTo(-8f, 3f)      // bottom-left wing
        lineTo(-3f, 3f)      // inner left
        lineTo(-3f, 9f)      // shaft bottom-left
        lineTo(3f, 9f)       // shaft bottom-right
        lineTo(3f, 3f)       // inner right
        lineTo(8f, 3f)       // bottom-right wing
        close()
    }

    // Reusable path for the sun's horizon-to-horizon arc, rebuilt each frame via rewind().
    private val sunTrackPath = Path()

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
        val panelWidth = right - left
        val panelHeight = bounds.height()

        ctx.drawPanelBorder(canvas, bounds, isLeftPanel)

        val hasRoute = snapshot.navHasRoute
        var currentY = top + panelHeight * 0.02f

        if (hasRoute) {
            currentY = drawNavigationSection(canvas, ctx, snapshot, left, right, currentY, panelWidth, panelHeight)
        } else {
            currentY = drawNavigationPlaceholder(
                canvas, ctx, snapshot, left, right, currentY, panelWidth, panelHeight
            )
        }

        drawSunSection(canvas, ctx, snapshot, left, right, currentY, top, panelWidth, panelHeight, hasRoute)
    }

    // ═══════════════════════════════════════════════════════════════════
    // NAVIGATION SECTION (only when a route is active)
    // ═══════════════════════════════════════════════════════════════════
    private fun drawNavigationSection(
        canvas: Canvas,
        ctx: WidgetDrawingContext,
        snapshot: RideTelemetrySnapshot,
        left: Float,
        right: Float,
        sectionTop: Float,
        panelWidth: Float,
        panelHeight: Float
    ): Float {
        var y = sectionTop

        ctx.textPaint.textSize = 11f
        ctx.textPaint.color = ctx.colors.muted
        ctx.textPaint.textAlign = Paint.Align.LEFT
        ctx.textPaint.typeface = ctx.monoTypeface
        canvas.drawText(motoHubText("NAV / ROUTE"), left + 14f, y + panelHeight * 0.045f, ctx.textPaint)
        y += panelHeight * 0.075f

        val maneuver = snapshot.navManeuverType
        val instruction = snapshot.navManeuverInstruction
        val distToManeuver = snapshot.navDistanceToManeuverMeters
        val distRemaining = snapshot.navDistanceRemainingMeters

        // ── Maneuver icon puck ────────────────────────────────────────────
        val puckRadius = panelHeight * 0.095f
        val puckCx = left + panelWidth * 0.20f
        val puckCy = y + puckRadius
        val dir = if (maneuver.isNotEmpty()) maneuverDirection(maneuver) else ManeuverDirection.STRAIGHT
        val angleDeg = arrowAngle(dir)

        ctx.fillPaint.color = ctx.colors.primary
        ctx.fillPaint.style = Paint.Style.FILL
        canvas.drawCircle(puckCx, puckCy, puckRadius, ctx.fillPaint)

        canvas.save()
        canvas.translate(puckCx, puckCy)
        val puckScale = puckRadius / 14f
        canvas.scale(puckScale, puckScale)
        canvas.rotate(angleDeg)
        ctx.fillPaint.color = ctx.colors.primaryText
        canvas.drawPath(arrowPath, ctx.fillPaint)
        canvas.restore()

        // ── Distance to maneuver + instruction, right of the puck ─────────
        val textLeft = puckCx + puckRadius + 12f
        val textMaxWidth = (right - 14f - textLeft).coerceAtLeast(0f)

        ctx.textPaint.textSize = 22f
        ctx.textPaint.color = ctx.colors.text
        ctx.textPaint.textAlign = Paint.Align.LEFT
        ctx.textPaint.typeface = ctx.monoBoldTypeface
        canvas.drawText(formatDistanceCompact(distToManeuver, ctx), textLeft, puckCy - 2f, ctx.textPaint)

        if (instruction.isNotEmpty()) {
            ctx.textPaint.textSize = 12f
            ctx.textPaint.color = ctx.colors.muted
            ctx.textPaint.typeface = ctx.monoTypeface
            canvas.drawText(
                truncateToWidth(instruction, textMaxWidth, ctx.textPaint),
                textLeft, puckCy + 17f, ctx.textPaint
            )
        }

        y = puckCy + puckRadius + panelHeight * 0.03f

        // ── Off-route banner ────────────────────────────────────────────
        if (snapshot.navOffRoute) {
            val bannerTop = y
            val bannerBottom = bannerTop + panelHeight * 0.055f
            ctx.fillPaint.color = ctx.colors.warning
            ctx.fillPaint.style = Paint.Style.FILL
            canvas.drawRoundRect(left + 10f, bannerTop, right - 10f, bannerBottom, 6f, 6f, ctx.fillPaint)
            ctx.textPaint.textSize = 12f
            ctx.textPaint.color = ctx.colors.primaryText
            ctx.textPaint.textAlign = Paint.Align.CENTER
            ctx.textPaint.typeface = ctx.monoBoldTypeface
            canvas.drawText(
                motoHubText("OFF ROUTE — RECALCULATING"),
                (left + right) / 2f, (bannerTop + bannerBottom) / 2f + 4f, ctx.textPaint
            )
            y = bannerBottom + panelHeight * 0.025f
        }

        // ── Separator ────────────────────────────────────────────────────
        ctx.strokePaint.style = Paint.Style.STROKE
        ctx.strokePaint.strokeWidth = 1f
        ctx.strokePaint.color = ctx.colors.border
        canvas.drawLine(left + 10f, y, right - 10f, y, ctx.strokePaint)
        y += panelHeight * 0.035f

        // ── Remaining distance + ETA ────────────────────────────────────
        ctx.textPaint.textSize = 15f
        ctx.textPaint.color = ctx.colors.text
        ctx.textPaint.textAlign = Paint.Align.LEFT
        ctx.textPaint.typeface = ctx.monoBoldTypeface
        canvas.drawText(formatDistanceCompact(distRemaining, ctx), left + 14f, y + 6f, ctx.textPaint)

        ctx.textPaint.textSize = 9f
        ctx.textPaint.color = ctx.colors.muted
        ctx.textPaint.typeface = ctx.monoTypeface
        canvas.drawText(motoHubText("REMAINING"), left + 14f, y + 19f, ctx.textPaint)

        val speedKph = snapshot.speedKph
        val etaText = if (speedKph > 1f) {
            val etaSeconds = (distRemaining / (speedKph / 3.6)).roundToInt()
            val etaMinutes = etaSeconds / 60
            val etaHours = etaMinutes / 60
            val etaMins = etaMinutes % 60
            String.format(Locale.US, "%d:%02d", etaHours, etaMins)
        } else {
            "--:--"
        }
        ctx.textPaint.textSize = 15f
        ctx.textPaint.color = ctx.colors.route
        ctx.textPaint.textAlign = Paint.Align.RIGHT
        ctx.textPaint.typeface = ctx.monoBoldTypeface
        canvas.drawText(etaText, right - 14f, y + 6f, ctx.textPaint)

        ctx.textPaint.textSize = 9f
        ctx.textPaint.color = ctx.colors.muted
        ctx.textPaint.typeface = ctx.monoTypeface
        canvas.drawText(motoHubText("ETA"), right - 14f, y + 19f, ctx.textPaint)

        // Fixed pixel gap, not a fraction of panelHeight: the "REMAINING"/"ETA"
        // captions above sit at a fixed +19px regardless of panel size, so the
        // separator needs a fixed clearance past that to avoid cutting through
        // their glyphs (a fraction-of-panelHeight gap was landing above them).
        y += 30f

        ctx.strokePaint.style = Paint.Style.STROKE
        ctx.strokePaint.strokeWidth = 1f
        ctx.strokePaint.color = ctx.colors.border
        canvas.drawLine(left + 10f, y, right - 10f, y, ctx.strokePaint)

        return y
    }

    private fun drawNavigationPlaceholder(
        canvas: Canvas,
        ctx: WidgetDrawingContext,
        snapshot: RideTelemetrySnapshot,
        left: Float,
        right: Float,
        sectionTop: Float,
        panelWidth: Float,
        panelHeight: Float
    ): Float {
        val marginX = (panelWidth * 0.07f + 10f).coerceAtMost(panelWidth * 0.22f)
        val sectionBottom = sectionTop + NAV_SECTION_HEIGHT
        ctx.fillPaint.style = Paint.Style.FILL
        ctx.fillPaint.color = Color.argb(150, Color.red(ctx.colors.panel), Color.green(ctx.colors.panel), Color.blue(ctx.colors.panel))
        canvas.drawRoundRect(left + 8f, sectionTop, right - 8f, sectionBottom, 10f, 10f, ctx.fillPaint)
        ctx.strokePaint.style = Paint.Style.STROKE
        ctx.strokePaint.strokeWidth = 1f
        ctx.strokePaint.color = ctx.colors.border
        canvas.drawRoundRect(left + 8f, sectionTop, right - 8f, sectionBottom, 10f, 10f, ctx.strokePaint)

        ctx.textPaint.textAlign = Paint.Align.LEFT
        ctx.textPaint.typeface = ctx.monoTypeface
        ctx.textPaint.textSize = 10f
        ctx.textPaint.color = ctx.colors.muted
        canvas.drawText(motoHubText("CURRENT LOCATION"), left + marginX, sectionTop + 22f, ctx.textPaint)

        val address = snapshot.currentAddress.ifBlank {
            snapshot.position?.let {
                String.format(Locale.US, "%.5f°, %.5f°", it.latitude, it.longitude)
            } ?: "Location unavailable"
        }
        ctx.textPaint.textSize = 14f
        ctx.textPaint.typeface = ctx.monoBoldTypeface
        ctx.textPaint.color = ctx.colors.text
        val addressLines = addressLines(address, right - left - marginX * 2f, ctx.textPaint)
        canvas.drawText(addressLines.first(), left + marginX, sectionTop + 52f, ctx.textPaint)
        addressLines.getOrNull(1)?.let { secondLine ->
            ctx.textPaint.textSize = 11f
            ctx.textPaint.typeface = ctx.monoTypeface
            ctx.textPaint.color = ctx.colors.muted
            canvas.drawText(secondLine, left + marginX, sectionTop + 70f, ctx.textPaint)
        }

        ctx.textPaint.textSize = 10f
        ctx.textPaint.typeface = ctx.monoTypeface
        ctx.textPaint.color = ctx.colors.route
        ctx.textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(
            truncateToWidth("START NAV FOR DIRECTIONS", right - left - 20f, ctx.textPaint),
            (left + right) / 2f,
            sectionBottom - 20f,
            ctx.textPaint
        )
        return sectionBottom
    }

    // ═══════════════════════════════════════════════════════════════════
    // SUN SECTION (always shown — fills the panel when there's no route)
    // ═══════════════════════════════════════════════════════════════════
    private fun drawSunSection(
        canvas: Canvas,
        ctx: WidgetDrawingContext,
        snapshot: RideTelemetrySnapshot,
        left: Float,
        right: Float,
        sectionTop: Float,
        panelTop: Float,
        panelWidth: Float,
        panelHeight: Float,
        hasRoute: Boolean
    ) {
        val sectionBottom = panelTop + panelHeight
        val marginX = (panelWidth * 0.07f + 10f).coerceAtMost(panelWidth * 0.22f)

        var y = sectionTop + 10f
        if (!hasRoute) {
            ctx.textPaint.textSize = 11f
            ctx.textPaint.color = ctx.colors.muted
            ctx.textPaint.textAlign = Paint.Align.LEFT
            ctx.textPaint.typeface = ctx.monoTypeface
            canvas.drawText(motoHubText("SUN"), left + marginX, y + 8f, ctx.textPaint)
            y += 24f
        }

        // ── Data ────────────────────────────────────────────────────────
        val position = snapshot.position
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        val sunArc = position?.let { SunsetCalculator.sunArcPosition(it.latitude, it.longitude, now) }
        val sunriseTime = position?.let { SunsetCalculator.sunriseTime(it.latitude, it.longitude, now) }
        val sunsetTime = position?.let { SunsetCalculator.sunsetTime(it.latitude, it.longitude, now) }
        val daylight = position?.let {
            SunsetCalculator.daylightCountdown(it.latitude, it.longitude, now)
        }
        val hasDaylight = daylight != null && !daylight.duration.isZero
        val showRideable = hasDaylight && !daylight!!.untilSunrise && snapshot.speedKph > 1f

        // ── Reserve a fixed-height text block at the bottom of the section
        // first, then give the arc whatever room is left above it. Doing it
        // in this order is what stops the arc from ever being tall enough
        // to crash into the text underneath it. This must include EVERY gap
        // actually used below the horizon line, including the leading one
        // (horizon → RISE/SET label) - omitting it previously under-reserved
        // by exactly that much, letting the daylight/rideable lines spill
        // past the panel's bottom edge whenever the nav section above left
        // less room than usual.
        // With no route, the navigation placeholder already reserves the upper
        // envelope. Keep the sun information dense in the remaining area so the
        // solar arc retains a useful vertical shape instead of becoming a flat
        // line at the bottom of the widget.
        val compactSunLayout = !hasRoute
        val horizonToLabelGap = if (compactSunLayout) 10f else 18f
        val riseSetLabelToValueGap = if (compactSunLayout) 16f else 22f
        val valueToDaylightGap = if (compactSunLayout) 17f else 26f
        val daylightToRideableGap = if (compactSunLayout) 12f else 16f
        val bottomPad = if (compactSunLayout) 6f else 10f
        val textBlockHeight = horizonToLabelGap + riseSetLabelToValueGap + valueToDaylightGap +
            (if (showRideable) daylightToRideableGap else 0f) + bottomPad

        // ── Horizon arc — sized to fit the leftover space, not stretched to fill it ──
        val arcAreaTop = y
        val arcAreaBottom = (sectionBottom - textBlockHeight).coerceAtLeast(arcAreaTop + 44f)
        val arcAreaHeight = arcAreaBottom - arcAreaTop
        val cx = (left + right) / 2f
        val rx = (panelWidth / 2f - marginX - 6f).coerceAtLeast(10f)

        val glowClearance = if (compactSunLayout) 12f else 22f // room above the dome apex for the sun's halo
        // The no-route sun panel has enough vertical room now; use a taller
        // dome than the old wide-and-flat 0.6 ratio so the seasonal path reads
        // clearly even on the narrow side panel.
        val idealRy = rx * if (compactSunLayout) 0.78f else 0.6f
        val ry = min(idealRy, (arcAreaHeight - glowClearance).coerceAtLeast(10f))
        val leftoverSpace = (arcAreaHeight - ry - glowClearance).coerceAtLeast(0f)
        val horizonY = arcAreaBottom - leftoverSpace / 2f

        // Use one strong seasonal guide. Drawing a second low-altitude curve
        // made the visible arc look flattened again; the marker follows this
        // same geometry so it can never appear detached.
        val guideRy = ry
        drawSunTrack(canvas, ctx, cx, horizonY, rx, guideRy)

        val horizonColor = ctx.colors.muted
        ctx.strokePaint.style = Paint.Style.STROKE
        ctx.strokePaint.strokeWidth = 1.25f
        ctx.strokePaint.color = Color.argb(120, Color.red(horizonColor), Color.green(horizonColor), Color.blue(horizonColor))
        canvas.drawLine(left + marginX - 6f, horizonY, right - marginX + 6f, horizonY, ctx.strokePaint)

        if (sunArc != null) {
            val theta = Math.toRadians(180.0 * (1.0 - sunArc.xFraction))
            val dotX = cx + (rx - 4f).coerceAtLeast(6f) * cos(theta).toFloat()
            val dotY = horizonY - guideRy * sin(theta).toFloat()
            val dotRadius = 6f

            val sunColor = if (sunArc.isDaytime) ctx.colors.warning else ctx.colors.muted
            if (sunArc.isDaytime) {
                // Soft halo built from two translucent passes, brightest near the core.
                ctx.fillPaint.style = Paint.Style.FILL
                ctx.fillPaint.color = Color.argb(26, Color.red(sunColor), Color.green(sunColor), Color.blue(sunColor))
                canvas.drawCircle(dotX, dotY, dotRadius * 3.2f, ctx.fillPaint)
                ctx.fillPaint.color = Color.argb(50, Color.red(sunColor), Color.green(sunColor), Color.blue(sunColor))
                canvas.drawCircle(dotX, dotY, dotRadius * 2f, ctx.fillPaint)

                ctx.strokePaint.style = Paint.Style.STROKE
                ctx.strokePaint.strokeWidth = 1.5f
                ctx.strokePaint.color = sunColor
                canvas.drawCircle(dotX, dotY, dotRadius + 4f, ctx.strokePaint)
            }
            ctx.fillPaint.color = sunColor
            ctx.fillPaint.style = Paint.Style.FILL
            canvas.drawCircle(dotX, dotY, dotRadius, ctx.fillPaint)
        }

        // ── Sunrise / sunset labels either side of the arc ────────────────
        val riseSetLabelY = horizonY + horizonToLabelGap
        ctx.textPaint.textSize = if (compactSunLayout) 8.5f else 9f
        ctx.textPaint.color = ctx.colors.muted
        ctx.textPaint.typeface = ctx.monoTypeface
        ctx.textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(motoHubText("RISE"), left + marginX, riseSetLabelY, ctx.textPaint)
        ctx.textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(motoHubText("SET"), right - marginX, riseSetLabelY, ctx.textPaint)

        val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val valueY = riseSetLabelY + riseSetLabelToValueGap
        ctx.textPaint.textSize = if (compactSunLayout) 13f else 16f
        ctx.textPaint.color = ctx.colors.text
        ctx.textPaint.typeface = ctx.monoBoldTypeface
        ctx.textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(sunriseTime?.format(timeFormatter) ?: "--:--", left + marginX, valueY, ctx.textPaint)
        ctx.textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(sunsetTime?.format(timeFormatter) ?: "--:--", right - marginX, valueY, ctx.textPaint)

        // ── Remaining daylight ─────────────────────────────────────────────
        val daylightY = (valueY + valueToDaylightGap).coerceAtMost(
            sectionBottom - if (showRideable) daylightToRideableGap + 6f else 5f
        )
        val daylightText = if (daylight != null && !daylight.duration.isZero) {
            val minutes = daylight.duration.toMinutes()
            val hours = minutes / 60
            val mins = minutes % 60
            val suffix = if (daylight.untilSunrise) "until sunrise" else "of daylight left"
            if (hours > 0) String.format(Locale.US, "%dh %dm %s", hours, mins, suffix)
            else String.format(Locale.US, "%dm %s", mins, suffix)
        } else {
            "Dark ☄"
        }
        ctx.textPaint.textSize = if (compactSunLayout) 10f else 12f
        ctx.textPaint.color = if (hasDaylight) ctx.colors.text else ctx.colors.warning
        ctx.textPaint.textAlign = Paint.Align.CENTER
        ctx.textPaint.typeface = ctx.monoTypeface
        canvas.drawText(daylightText, cx, daylightY, ctx.textPaint)

        // ── Rideable distance before dark ───────────────────────────────
        if (showRideable) {
            // showRideable already guarantees a non-zero daylight duration; the
            // elvis fallback only exists to satisfy the compiler's null-checker.
            val hoursUntilDark = (daylight?.duration ?: Duration.ZERO).toMillis() / 3_600_000.0
            val speed = snapshot.speedKph
            val rideable = UnitFormat.wholeDistanceFromKm(speed * hoursUntilDark, ctx.units)
            val rideableText = String.format(
                Locale.US, "~%d %s before dark @ %d %s",
                rideable, UnitFormat.wholeDistanceLabel(ctx.units),
                UnitFormat.speed(speed, ctx.units).roundToInt(), UnitFormat.speedLabelLower(ctx.units)
            )
            ctx.textPaint.textSize = if (compactSunLayout) 9.5f else 10.5f
            ctx.textPaint.color = ctx.colors.route
            ctx.textPaint.textAlign = Paint.Align.CENTER
            ctx.textPaint.typeface = ctx.monoTypeface
            canvas.drawText(
                rideableText,
                cx,
                (daylightY + daylightToRideableGap).coerceAtMost(sectionBottom - 2f),
                ctx.textPaint
            )
        }
    }

    /**
     * The sun's full daily arc, horizon-left (rise) to horizon-right (set) —
     * this *is* the sun's path, so it needs to actually read as one, not
     * hide as a barely-there guideline. Drawn as a true smooth ellipse arc
     * (not a faceted polyline) with a warm gradient that brightens toward
     * the zenith; a wide low-alpha pass underneath a crisp core fakes a
     * soft glow along the curve.
     */
    private fun drawSunTrack(
        canvas: Canvas,
        ctx: WidgetDrawingContext,
        cx: Float,
        horizonY: Float,
        rx: Float,
        ry: Float,
        alphaScale: Float = 1f
    ) {
        val oval = RectF(cx - rx, horizonY - ry, cx + rx, horizonY + ry)
        sunTrackPath.rewind()
        sunTrackPath.addArc(oval, 180f, 180f)

        val trackColor = ctx.colors.warning
        val r = Color.red(trackColor)
        val g = Color.green(trackColor)
        val b = Color.blue(trackColor)
        val gradient = LinearGradient(
            cx - rx, 0f, cx + rx, 0f,
            intArrayOf(
                Color.argb((80 * alphaScale).roundToInt().coerceIn(0, 255), r, g, b),
                Color.argb((235 * alphaScale).roundToInt().coerceIn(0, 255), r, g, b),
                Color.argb((80 * alphaScale).roundToInt().coerceIn(0, 255), r, g, b)
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )

        ctx.strokePaint.style = Paint.Style.STROKE
        ctx.strokePaint.strokeCap = Paint.Cap.ROUND
        ctx.strokePaint.shader = gradient

        ctx.strokePaint.strokeWidth = 6f
        ctx.strokePaint.alpha = (70 * alphaScale).roundToInt().coerceIn(0, 255)
        canvas.drawPath(sunTrackPath, ctx.strokePaint)

        ctx.strokePaint.strokeWidth = 2f
        ctx.strokePaint.alpha = (255 * alphaScale).roundToInt().coerceIn(0, 255)
        canvas.drawPath(sunTrackPath, ctx.strokePaint)

        // ctx.strokePaint is shared across every widget drawn this frame —
        // never leave a shader/cap on it for the next consumer to inherit.
        ctx.strokePaint.shader = null
        ctx.strokePaint.strokeCap = Paint.Cap.BUTT
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Convolve a [ManeuverDirection] into a clockwise Canvas rotation. */
    private fun arrowAngle(dir: ManeuverDirection): Float = when (dir) {
        ManeuverDirection.STRAIGHT -> 0f
        ManeuverDirection.SLIGHT_LEFT -> -30f
        ManeuverDirection.LEFT -> -90f
        ManeuverDirection.SHARP_LEFT -> -135f
        ManeuverDirection.UTURN -> 180f
        ManeuverDirection.SLIGHT_RIGHT -> 30f
        ManeuverDirection.RIGHT -> 90f
        ManeuverDirection.SHARP_RIGHT -> 135f
        ManeuverDirection.ROUNDABOUT -> 0f
        ManeuverDirection.ARRIVE -> 135f // checkered flag direction
    }

    private fun formatDistanceCompact(meters: Double, ctx: WidgetDrawingContext): String =
        UnitFormat.distanceCompact(meters, ctx.units)

    /** Truncates [text] with a trailing "…" so it fits within [maxWidth] at [paint]'s current size/typeface. */
    private fun truncateToWidth(text: String, maxWidth: Float, paint: Paint): String {
        if (maxWidth <= 0f || paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 0 && paint.measureText(text.substring(0, end) + "…") > maxWidth) {
            end--
        }
        return if (end <= 0) "…" else text.substring(0, end) + "…"
    }

    private fun addressLines(address: String, maxWidth: Float, paint: Paint): List<String> {
        if (address.isBlank()) return listOf("Location unavailable")
        val parts = address.split(',').map(String::trim).filter(String::isNotBlank)
        if (parts.size >= 2) {
            val first = truncateToWidth(parts.first(), maxWidth, paint)
            val second = truncateToWidth(parts.drop(1).joinToString(", "), maxWidth, paint)
            return listOf(first, second)
        }
        return listOf(truncateToWidth(address, maxWidth, paint))
    }

    private companion object {
        /** Matches the normal navigation content envelope before the sun section starts. */
        // The no-route card only needs room for the location label, two address
        // lines and the CTA. Keeping it compact gives the solar panel enough
        // height for a readable seasonal arc and its daylight status line.
        const val NAV_SECTION_HEIGHT = 112f
    }
}
