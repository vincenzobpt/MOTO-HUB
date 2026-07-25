package io.motohub.android.feature.ridedashboard.widget

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.RectF
import io.motohub.android.feature.ridedashboard.RideTelemetrySnapshot
import io.motohub.android.units.UnitFormat
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.max
import kotlin.math.sin

/**
 * Speed gauge widget with an arc-based speedometer, digital speed readout,
 * compass bearing and GNSS status. Extracted from the original monolithic
 * [drawSpeedPanel] — preserves exact visual layout.
 */
class SpeedGaugeWidget : DashboardWidget {

    private var flameFrameSeed = 0
    private val outerFlamePaths = Array(FLAME_TONGUE_COUNT) { Path() }
    private val innerFlamePaths = Array(FLAME_TONGUE_COUNT) { Path() }
    private val coreFlamePaths = Array(FLAME_TONGUE_COUNT) { Path() }

    override val id: String = DashboardWidgetIDs.SPEED_GAUGE
    override val title: String = "Speed Gauge"
    override val description: String = "Arc speedometer, bearing and GNSS lock status"

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
        val bottom = bounds.bottom
        val centerX = (left + right) / 2f
        val centerY = top + 119f
        // The startup sweep is a visual gauge self-test. Keep GNSS status and
        // course semantics separate while allowing the synthetic speed to drive
        // the flame animation during the intro.
        val gaugeFreshFix = freshFix && !ctx.startupSweepActive

        // Flame effect: intensity 0→1 from 150→180 KPH
        val flameIntensity = ((snapshot.speedKph - FLAME_THRESHOLD) / (180f - FLAME_THRESHOLD)).coerceIn(0f, 1f)
        if (flameIntensity > 0f && (gaugeFreshFix || ctx.startupSweepActive)) {
            flameFrameSeed++
            drawFlames(canvas, bounds, flameIntensity, ctx)
        }

        ctx.drawPanelBorder(canvas, bounds, isLeftPanel)

        ctx.drawText(canvas, ctx.localized("GROUND SPEED / GPS"), left + 22f, top + 26f, 12f,
            ctx.colors.muted, ctx.monoTypeface)

        // Speed-dependent indicator colour: green < 90, yellow 90-100, red > 100
        val speedIndicatorColor = when {
            snapshot.speedKph > 100f -> SPEED_RED
            snapshot.speedKph >= 90f -> SPEED_YELLOW
            else -> ctx.colors.primary
        }
        val displayColor = if (gaugeFreshFix) speedIndicatorColor else ctx.colors.muted

        val gaugeRadius = (bounds.width() / 2f - 23f).coerceAtMost(85f)
        val arcBounds = RectF(
            centerX - gaugeRadius,
            centerY - gaugeRadius,
            centerX + gaugeRadius,
            centerY + gaugeRadius
        )
        ctx.strokePaint.style = Paint.Style.STROKE
        ctx.strokePaint.strokeCap = Paint.Cap.ROUND
        ctx.strokePaint.strokeWidth = 5f

        // Background arc
        ctx.strokePaint.color = ctx.colors.border
        canvas.drawArc(arcBounds, 145f, 250f, false, ctx.strokePaint)

        // Speed arc
        ctx.strokePaint.color = displayColor
        val speedFraction = (snapshot.speedKph / 180f).coerceIn(0f, 1f)
        canvas.drawArc(arcBounds, 145f, 250f * speedFraction, false, ctx.strokePaint)
        ctx.strokePaint.strokeCap = Paint.Cap.BUTT

        // Tick marks
        for (index in 0..9) {
            val angle = Math.toRadians((145f + index * (250f / 9f)).toDouble())
            val outerX = centerX + cos(angle).toFloat() * (gaugeRadius + 3f)
            val outerY = centerY + sin(angle).toFloat() * (gaugeRadius + 3f)
            val innerX = centerX + cos(angle).toFloat() * (gaugeRadius - 5f)
            val innerY = centerY + sin(angle).toFloat() * (gaugeRadius - 5f)
            ctx.strokePaint.color = if (index <= speedFraction * 9f) displayColor else ctx.colors.border
            ctx.strokePaint.strokeWidth = 2f
            canvas.drawLine(innerX, innerY, outerX, outerY, ctx.strokePaint)
        }

        // Digital speed — arc, ticks and colour thresholds stay on physical km/h;
        // only the readout converts to the rider's unit preference.
        ctx.drawText(canvas, UnitFormat.speed(snapshot.speedKph, ctx.units).roundToInt().toString(),
            centerX, top + 141f, 76f,
            displayColor,
            ctx.boldTypeface, Paint.Align.CENTER)

        ctx.drawText(canvas, ctx.localized(UnitFormat.speedLabel(ctx.units)), centerX, top + 166f, 15f,
            ctx.colors.muted, ctx.monoTypeface, Paint.Align.CENTER)

        // Bearing
        val bearing = snapshot.bearingDegrees
        val course = if (gaugeFreshFix && bearing != null) {
            "${compassPoint(bearing)} / ${bearing.roundToInt().toString().padStart(3, '0')} DEG"
        } else {
            "COURSE --"
        }
        ctx.drawText(canvas, course, centerX, top + 225f, 15f,
            ctx.colors.primary, ctx.monoBoldTypeface, Paint.Align.CENTER)

        // GNSS status
        ctx.drawText(canvas,
            if (gaugeFreshFix) "GNSS VERIFIED" else "WAITING FOR FIX",
            centerX, top + 250f, 11f,
            if (gaugeFreshFix) ctx.colors.success else ctx.colors.warning,
            ctx.monoTypeface, Paint.Align.CENTER)
    }

    private fun drawFlames(canvas: Canvas, bounds: RectF, intensity: Float, ctx: WidgetDrawingContext) {
        val left = bounds.left
        val bottom = bounds.bottom
        val panelWidth = bounds.width()

        // Keep the effect behind the readout without turning the whole panel into a
        // flat red block. The glow and tongues are clipped to the widget bounds.
        val maxFlameHeight = bounds.height() * 0.78f
        val flameHeight = maxFlameHeight * intensity
        val cellWidth = panelWidth / FLAME_TONGUE_COUNT
        val fill = ctx.fillPaint
        val saveCount = canvas.save()
        canvas.clipRect(bounds)

        fill.style = Paint.Style.FILL
        fill.shader = LinearGradient(
            0f, bottom, 0f, bottom - flameHeight,
            intArrayOf(FLAME_BASE, FLAME_GLOW, FLAME_FADE, Color.TRANSPARENT),
            floatArrayOf(0f, 0.24f, 0.58f, 1f),
            Shader.TileMode.CLAMP
        )
        // Paint the complete height so the alpha reaches zero at the top edge;
        // stopping at 68% left a visible rectangular cutoff against the black HUD.
        canvas.drawRect(left, bottom - flameHeight, bounds.right, bottom, fill)
        fill.shader = null

        // Each tongue is built from three reusable paths. Independent phase and
        // lean values make the flame read as fluid instead of a row of triangles.
        for (i in 0 until FLAME_TONGUE_COUNT) {
            val phase = flameFrameSeed * 0.34f + i * 1.47f
            val flicker = ((sin(phase) + 0.36f * sin(phase * 1.71f + i)) / 1.36f * 0.5f + 0.5f)
                .coerceIn(0f, 1f)
            val cx = left + cellWidth * (i + 0.5f) + sin(phase * 0.83f) * cellWidth * 0.28f
            val width = cellWidth * (0.38f + 0.34f * flicker)
            val height = flameHeight * (0.38f + 0.55f * flicker) * (0.82f + 0.18f * intensity)
            val lean = sin(phase * 0.61f + 0.8f) * width * 0.7f

            buildFlamePath(outerFlamePaths[i], cx, bottom, width, height, lean)
            fill.color = if (i % 3 == 0) FLAME_RED_HOT else FLAME_RED
            canvas.drawPath(outerFlamePaths[i], fill)

            val innerWidth = width * (0.58f + 0.12f * (1f - flicker))
            val innerHeight = height * (0.66f + 0.10f * flicker)
            buildFlamePath(innerFlamePaths[i], cx - lean * 0.12f, bottom + 1f, innerWidth, innerHeight, -lean * 0.45f)
            fill.color = FLAME_ORANGE
            canvas.drawPath(innerFlamePaths[i], fill)

            val coreWidth = max(1f, innerWidth * 0.46f)
            val coreHeight = innerHeight * 0.54f
            buildFlamePath(coreFlamePaths[i], cx - lean * 0.20f, bottom + 1f, coreWidth, coreHeight, -lean * 0.25f)
            fill.color = FLAME_YELLOW
            canvas.drawPath(coreFlamePaths[i], fill)
        }

        // Embers drift above the tips and vary in radius/opacity.
        val emberCount = (intensity * 12).toInt().coerceAtLeast(3)
        for (i in 0 until emberCount) {
            val phase = flameFrameSeed * 0.17f + i * 2.13f
            val drift = ((sin(phase) + 1f) * 0.5f)
            val ex = left + panelWidth * ((i + 0.37f * drift) / emberCount)
            val ey = bottom - flameHeight * (0.63f + 0.37f * ((sin(phase * 1.31f) + 1f) * 0.5f))
            fill.color = if (i % 3 == 0) FLAME_YELLOW_BRIGHT else FLAME_ORANGE
            canvas.drawCircle(ex, ey, 1.2f + 1.3f * drift, fill)
        }

        fill.shader = null
        fill.style = Paint.Style.FILL
        canvas.restoreToCount(saveCount)
    }

    private fun buildFlamePath(path: Path, cx: Float, baseY: Float, width: Float, height: Float, lean: Float) {
        val tipX = cx + lean
        path.rewind()
        path.moveTo(cx - width, baseY + 3f)
        path.cubicTo(
            cx - width * 0.88f, baseY - height * 0.24f,
            cx - width * 0.42f + lean * 0.12f, baseY - height * 0.48f,
            tipX - width * 0.10f, baseY - height * 0.82f
        )
        path.cubicTo(
            tipX - width * 0.02f, baseY - height * 0.95f,
            tipX + width * 0.04f, baseY - height,
            tipX, baseY - height - 3f
        )
        path.cubicTo(
            tipX + width * 0.10f, baseY - height * 0.70f,
            cx + width * 0.66f, baseY - height * 0.44f,
            cx + width, baseY + 3f
        )
        path.close()
    }

    private fun compassPoint(bearing: Float): String {
        val index = ((bearing + 22.5f) / 45f).toInt() % COMPASS_POINTS.size
        return COMPASS_POINTS[index]
    }

    companion object {
        private val COMPASS_POINTS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        private const val SPEED_RED = 0xFFFF321E.toInt()
        private const val SPEED_YELLOW = 0xFFFFC80A.toInt()
        private const val FLAME_THRESHOLD = 150f
        private const val FLAME_BASE = 0x33FF321E.toInt()
        private const val FLAME_GLOW = 0x66FF6A1A
        private const val FLAME_FADE = 0x22FF6A1A
        private const val FLAME_RED = 0x88FF321E.toInt()
        private const val FLAME_RED_HOT = 0xAAFF4A1A.toInt()
        private const val FLAME_ORANGE = 0xCCFF8000.toInt()
        private const val FLAME_YELLOW = 0xCCFFE040.toInt()
        private const val FLAME_YELLOW_BRIGHT = 0xFFFFF09A.toInt()
        private const val FLAME_TONGUE_COUNT = 16
    }
}
