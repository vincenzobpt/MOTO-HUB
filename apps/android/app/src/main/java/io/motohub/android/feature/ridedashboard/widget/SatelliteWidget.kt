package io.motohub.android.feature.ridedashboard.widget

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import io.motohub.android.feature.ridedashboard.RideTelemetrySnapshot
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * NASA-style GNSS mission-control widget.
 *
 * The previous row of anonymous green spheres did not explain what was being
 * shown. This version separates the concepts clearly: the orbit shows the
 * visible satellites, filled nodes are the satellites currently used in the
 * navigation solution, and the footer reports the actual fix state.
 */
class SatelliteWidget : DashboardWidget {

    override val id: String = DashboardWidgetIDs.SATELLITES
    override val title: String = "Satellites"
    override val description: String = "GNSS satellite visibility and fix status"

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
        val used = snapshot.satellitesUsed.coerceAtLeast(0)
        val visible = snapshot.satellitesVisible.coerceAtLeast(0)
        val activeUsed = min(used, visible)
        val solutionColor = when {
            freshFix && activeUsed >= 4 -> ctx.colors.success
            freshFix && activeUsed > 0 -> ctx.colors.warning
            else -> ctx.colors.muted
        }

        ctx.drawPanelBorder(canvas, bounds, isLeftPanel)

        // Header: a compact mission-control identifier plus an unambiguous state.
        ctx.drawText(
            canvas,
            "GNSS // ORBITAL STATUS",
            left + 18f,
            top + 25f,
            11f,
            ctx.colors.muted,
            ctx.monoTypeface
        )
        ctx.fillPaint.style = Paint.Style.FILL
        ctx.fillPaint.color = solutionColor
        canvas.drawCircle(right - 22f, top + 20f, 4f, ctx.fillPaint)
        ctx.drawText(
            canvas,
            if (freshFix) "LINK" else "SCAN",
            right - 32f,
            top + 36f,
            8f,
            solutionColor,
            ctx.monoTypeface,
            Paint.Align.RIGHT
        )

        // Deep-space backdrop for the orbital plot.
        ctx.fillPaint.color = COLOR_SPACE
        canvas.drawRoundRect(
            left + 12f,
            top + 45f,
            right - 12f,
            top + 166f,
            16f,
            16f,
            ctx.fillPaint
        )
        drawStarfield(canvas, left + 12f, top + 45f, right - 12f, top + 166f, ctx)

        val orbitCenterX = centerX
        val orbitCenterY = top + 106f
        val orbitWidth = (right - left - 54f).coerceAtLeast(120f)
        val orbitHeight = 82f

        ctx.strokePaint.style = Paint.Style.STROKE
        ctx.strokePaint.strokeWidth = 1f
        ctx.strokePaint.color = COLOR_ORBIT
        canvas.drawOval(
            RectF(
                orbitCenterX - orbitWidth / 2f,
                orbitCenterY - orbitHeight / 2f,
                orbitCenterX + orbitWidth / 2f,
                orbitCenterY + orbitHeight / 2f
            ),
            ctx.strokePaint
        )
        ctx.strokePaint.color = COLOR_ORBIT_FAINT
        canvas.drawOval(
            RectF(
                orbitCenterX - orbitWidth * 0.36f,
                orbitCenterY - orbitHeight * 0.82f,
                orbitCenterX + orbitWidth * 0.36f,
                orbitCenterY + orbitHeight * 0.82f
            ),
            ctx.strokePaint
        )

        // Earth/receiver at the centre: a small globe with latitude lines.
        ctx.fillPaint.color = COLOR_EARTH
        canvas.drawCircle(orbitCenterX, orbitCenterY, 22f, ctx.fillPaint)
        ctx.strokePaint.strokeWidth = 1f
        ctx.strokePaint.color = COLOR_EARTH_GRID
        canvas.drawArc(RectF(orbitCenterX - 22f, orbitCenterY - 9f, orbitCenterX + 22f, orbitCenterY + 9f), 0f, 360f, false, ctx.strokePaint)
        canvas.drawArc(RectF(orbitCenterX - 10f, orbitCenterY - 22f, orbitCenterX + 10f, orbitCenterY + 22f), 0f, 360f, false, ctx.strokePaint)
        ctx.drawText(
            canvas,
            "RX",
            orbitCenterX,
            orbitCenterY + 4f,
            9f,
            ctx.colors.primary,
            ctx.monoBoldTypeface,
            Paint.Align.CENTER
        )

        // One clearly explained node per visible satellite. Filled = used by
        // the fix; hollow = visible but not currently used in the solution.
        val nodeCount = min(visible, MAX_ORBIT_NODES)
        for (index in 0 until nodeCount) {
            val angle = Math.toRadians((SATELLITE_START_ANGLE + index * SATELLITE_ANGLE_STEP).toDouble())
            val x = orbitCenterX + cos(angle).toFloat() * orbitWidth * 0.5f
            val y = orbitCenterY + sin(angle).toFloat() * orbitHeight * 0.5f
            val isUsed = index < activeUsed
            val nodeColor = if (isUsed && freshFix) ctx.colors.success else COLOR_VISIBLE_NODE
            ctx.strokePaint.color = nodeColor
            ctx.strokePaint.strokeWidth = 1.5f
            ctx.strokePaint.style = Paint.Style.STROKE
            canvas.drawCircle(x, y, 5f, ctx.strokePaint)
            if (isUsed) {
                ctx.fillPaint.color = nodeColor
                ctx.fillPaint.style = Paint.Style.FILL
                canvas.drawCircle(x, y, 3f, ctx.fillPaint)
            }
        }

        // Mission readout: two independent numbers remove the old ambiguous 0/0.
        val readoutTop = top + 190f
        drawMetric(ctx, canvas, "USED", activeUsed.toString(), left + 62f, readoutTop, solutionColor)
        drawMetric(ctx, canvas, "VISIBLE", visible.toString(), right - 62f, readoutTop, ctx.colors.text)

        val coverage = if (visible > 0) (activeUsed.toFloat() / visible).coerceIn(0f, 1f) else 0f
        ctx.drawText(canvas, ctx.localized("SIGNAL COVERAGE"), left + 18f, bottom - 31f, 9f, ctx.colors.muted, ctx.monoTypeface)
        ctx.fillPaint.color = COLOR_TRACK
        canvas.drawRoundRect(left + 18f, bottom - 24f, right - 18f, bottom - 18f, 3f, 3f, ctx.fillPaint)
        ctx.fillPaint.color = solutionColor
        canvas.drawRoundRect(left + 18f, bottom - 24f, left + 18f + (right - left - 36f) * coverage, bottom - 18f, 3f, 3f, ctx.fillPaint)

        val status = when {
            freshFix && activeUsed >= 4 -> "3D FIX  •  NAVIGATION READY"
            freshFix && activeUsed > 0 -> "2D FIX  •  WEAK GEOMETRY"
            else -> "SEARCHING FOR SATELLITES"
        }
        ctx.drawText(canvas, status, right - 18f, bottom - 31f, 9f, solutionColor, ctx.monoBoldTypeface, Paint.Align.RIGHT)
    }

    private fun drawMetric(
        ctx: WidgetDrawingContext,
        canvas: Canvas,
        label: String,
        value: String,
        x: Float,
        y: Float,
        valueColor: Int
    ) {
        ctx.drawText(canvas, value, x, y + 1f, 31f, valueColor, ctx.monoBoldTypeface, Paint.Align.CENTER)
        ctx.drawText(canvas, label, x, y + 19f, 9f, ctx.colors.muted, ctx.monoTypeface, Paint.Align.CENTER)
    }

    private fun drawStarfield(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        ctx: WidgetDrawingContext
    ) {
        ctx.fillPaint.style = Paint.Style.FILL
        for (index in STAR_X.indices) {
            val x = left + (right - left) * STAR_X[index]
            val y = top + (bottom - top) * STAR_Y[index]
            ctx.fillPaint.color = if (index % 3 == 0) COLOR_STAR_BRIGHT else COLOR_STAR
            canvas.drawCircle(x, y, if (index % 3 == 0) 1.2f else 0.7f, ctx.fillPaint)
        }
    }

    companion object {
        private const val MAX_ORBIT_NODES = 12
        private const val SATELLITE_START_ANGLE = -82f
        private const val SATELLITE_ANGLE_STEP = 360f / MAX_ORBIT_NODES
        private val COLOR_SPACE = 0xFF07121D.toInt()
        private val COLOR_ORBIT = 0xFF315A70.toInt()
        private val COLOR_ORBIT_FAINT = 0xFF1A3345.toInt()
        private val COLOR_EARTH = 0xFF123649.toInt()
        private val COLOR_EARTH_GRID = 0xFF2F7892.toInt()
        private val COLOR_VISIBLE_NODE = 0xFF5F7682.toInt()
        private val COLOR_TRACK = 0xFF172832.toInt()
        private val COLOR_STAR = 0xFF6D8998.toInt()
        private val COLOR_STAR_BRIGHT = 0xFFB7E6F3.toInt()
        private val STAR_X = floatArrayOf(
            0.08f, 0.18f, 0.31f, 0.43f, 0.57f, 0.68f, 0.82f, 0.91f,
            0.13f, 0.37f, 0.73f, 0.86f
        )
        private val STAR_Y = floatArrayOf(
            0.16f, 0.72f, 0.31f, 0.82f, 0.18f, 0.61f, 0.28f, 0.77f,
            0.48f, 0.09f, 0.88f, 0.52f
        )
    }
}
