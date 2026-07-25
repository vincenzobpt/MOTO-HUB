package io.motohub.android.feature.ridedashboard.widget

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import io.motohub.android.feature.ridedashboard.RideAltitudeSample
import io.motohub.android.feature.ridedashboard.RideTelemetrySnapshot
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Elevation instrument with a real one-hour session profile and climb metrics. */
class AltitudeWidget : DashboardWidget {

    private val profilePath = Path()
    private val profileFillPath = Path()

    override val id: String = DashboardWidgetIDs.ALTITUDE
    override val title: String = "Altitude"
    override val description: String = "Elevation profile, climb rate and GPS accuracy"

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
        val hasAltitude = freshFix && snapshot.altitudeMeters != null
        val altitude = snapshot.altitudeMeters ?: 0.0
        val valueColor = if (hasAltitude) ctx.colors.text else ctx.colors.muted

        ctx.drawPanelBorder(canvas, bounds, isLeftPanel)
        ctx.drawText(
            canvas,
            "ALTIMETER // ELEVATION",
            left + 18f,
            top + 25f,
            11f,
            ctx.colors.muted,
            ctx.monoTypeface
        )
        ctx.drawText(
            canvas,
            if (hasAltitude) "GNSS LIVE" else "NO FIX",
            right - 18f,
            top + 25f,
            10f,
            if (hasAltitude) ctx.colors.success else ctx.colors.warning,
            ctx.monoBoldTypeface,
            Paint.Align.RIGHT
        )

        val chart = RectF(left + 10f, top + 39f, right - 10f, top + 145f)
        drawElevationProfile(canvas, chart, snapshot, ctx)

        ctx.drawText(
            canvas,
            if (hasAltitude) formatMeters(altitude) else "--",
            centerX,
            top + 188f,
            52f,
            valueColor,
            ctx.monoBoldTypeface,
            Paint.Align.CENTER
        )
        ctx.drawText(
            canvas,
            "METERS ASL",
            centerX,
            top + 207f,
            12f,
            ctx.colors.muted,
            ctx.monoTypeface,
            Paint.Align.CENTER
        )

        val change = if (hasAltitude) snapshot.altitudeChangeMeters else 0.0
        val verticalSpeed = if (hasAltitude) snapshot.verticalSpeedMps else null
        val trendColor = when {
            verticalSpeed == null -> ctx.colors.warning
            verticalSpeed > 0.15f -> ctx.colors.success
            verticalSpeed < -0.15f -> ctx.colors.warning
            else -> ctx.colors.muted
        }
        // Two rows/two columns keep every value inside its own generous cell. The previous
        // three-column row was too narrow for MIN/MAX and the GNSS footer crossed its labels.
        val metricLeft = left + 54f
        val metricRight = right - 54f
        drawMetric(ctx, canvas, "SESSION Δ", signedMeters(change), metricLeft, top + 230f, trendColor)
        drawMetric(ctx, canvas, "MIN / MAX", rangeText(snapshot), metricRight, top + 230f, ctx.colors.text)
        drawMetric(ctx, canvas, verticalLabel(verticalSpeed), verticalSpeedText(verticalSpeed), metricLeft, top + 260f, trendColor)
        drawMetric(ctx, canvas, "GNSS ACCURACY", accuracyShortText(snapshot, hasAltitude), metricRight, top + 260f,
            if (hasAltitude) ctx.colors.success else ctx.colors.warning)
    }

    private fun drawElevationProfile(
        canvas: Canvas,
        chart: RectF,
        snapshot: RideTelemetrySnapshot,
        ctx: WidgetDrawingContext
    ) {
        ctx.fillPaint.style = Paint.Style.FILL
        ctx.fillPaint.shader = LinearGradient(
            0f,
            chart.top,
            0f,
            chart.bottom,
            COLOR_CHART_TOP,
            COLOR_CHART_BOTTOM,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(chart, 12f, 12f, ctx.fillPaint)
        ctx.fillPaint.shader = null

        val visible = visibleSamples(snapshot)
        val plot = RectF(chart.left + 27f, chart.top + 22f, chart.right - 8f, chart.bottom - 17f)
        drawGrid(canvas, chart, plot, ctx)

        if (visible.isEmpty()) {
            ctx.drawText(canvas, ctx.localized("WAITING FOR GNSS ELEVATION"), chart.centerX(), chart.centerY() + 4f, 8f,
                ctx.colors.muted, ctx.monoTypeface, Paint.Align.CENTER)
            return
        }

        val minAltitude = visible.minOf { it.altitudeMeters }
        val maxAltitude = visible.maxOf { it.altitudeMeters }
        val range = (maxAltitude - minAltitude).coerceAtLeast(1.0)
        val padding = max(2.0, range * 0.12)
        val low = minAltitude - padding
        val high = maxAltitude + padding
        val windowStart = visible.first().elapsedMillis
        val windowEnd = max(snapshot.elapsedMillis, visible.last().elapsedMillis)
        val duration = (windowEnd - windowStart).coerceAtLeast(1L).toFloat()

        profilePath.rewind()
        profileFillPath.rewind()
        visible.forEachIndexed { index, sample ->
            val x = plot.left + ((sample.elapsedMillis - windowStart).coerceAtLeast(0L) / duration) * plot.width()
            val normalized = ((sample.altitudeMeters - low) / (high - low)).toFloat().coerceIn(0f, 1f)
            val y = plot.bottom - normalized * plot.height()
            if (index == 0) {
                profilePath.moveTo(x, y)
                profileFillPath.moveTo(x, plot.bottom)
                profileFillPath.lineTo(x, y)
            } else {
                profilePath.lineTo(x, y)
                profileFillPath.lineTo(x, y)
            }
        }
        val last = visible.last()
        val lastX = plot.left + ((last.elapsedMillis - windowStart).coerceAtLeast(0L) / duration) * plot.width()
        profileFillPath.lineTo(lastX, plot.bottom)
        profileFillPath.close()

        ctx.fillPaint.shader = LinearGradient(
            0f, plot.top, 0f, plot.bottom,
            COLOR_PROFILE_FILL_TOP, COLOR_PROFILE_FILL_BOTTOM, Shader.TileMode.CLAMP
        )
        canvas.drawPath(profileFillPath, ctx.fillPaint)
        ctx.fillPaint.shader = null

        ctx.strokePaint.style = Paint.Style.STROKE
        ctx.strokePaint.strokeWidth = 2.2f
        ctx.strokePaint.strokeCap = Paint.Cap.ROUND
        ctx.strokePaint.strokeJoin = Paint.Join.ROUND
        ctx.strokePaint.color = COLOR_PROFILE_LINE
        canvas.drawPath(profilePath, ctx.strokePaint)
        ctx.strokePaint.strokeCap = Paint.Cap.BUTT

        ctx.drawText(canvas, formatCompactAltitude(maxAltitude), chart.left + 8f, plot.top + 3f, 7f,
            ctx.colors.muted, ctx.monoTypeface, Paint.Align.LEFT)
        ctx.drawText(canvas, formatCompactAltitude(minAltitude), chart.left + 8f, plot.bottom + 2f, 7f,
            ctx.colors.muted, ctx.monoTypeface, Paint.Align.LEFT)
        ctx.drawText(canvas, if (windowEnd - windowStart >= HOUR_MILLIS) "-1H" else "START",
            plot.left, chart.bottom - 5f, 7f, ctx.colors.muted, ctx.monoTypeface)
        ctx.drawText(canvas, ctx.localized("NOW"), plot.right, chart.bottom - 5f, 7f,
            ctx.colors.muted, ctx.monoTypeface, Paint.Align.RIGHT)
        ctx.drawText(canvas, ctx.localized("ELEVATION / LAST HOUR"), chart.left + 11f, chart.top + 15f, 8f,
            COLOR_PROFILE_LINE, ctx.monoBoldTypeface)
    }

    private fun drawGrid(canvas: Canvas, chart: RectF, plot: RectF, ctx: WidgetDrawingContext) {
        ctx.strokePaint.shader = null
        ctx.strokePaint.style = Paint.Style.STROKE
        ctx.strokePaint.strokeWidth = 1f
        ctx.strokePaint.color = COLOR_GRID
        repeat(3) { index ->
            val y = plot.top + plot.height() * index / 2f
            canvas.drawLine(plot.left, y, plot.right, y, ctx.strokePaint)
        }
        repeat(5) { index ->
            val x = plot.left + plot.width() * index / 4f
            canvas.drawLine(x, plot.top, x, plot.bottom, ctx.strokePaint)
        }
        ctx.strokePaint.color = COLOR_CHART_BORDER
        canvas.drawRoundRect(chart, 12f, 12f, ctx.strokePaint)
    }

    private fun visibleSamples(snapshot: RideTelemetrySnapshot): List<RideAltitudeSample> {
        val samples = snapshot.altitudeHistory.filter { it.altitudeMeters.isFinite() }
        if (samples.isEmpty()) return emptyList()
        val cutoff = (snapshot.elapsedMillis - HOUR_MILLIS).coerceAtLeast(0L)
        return samples.filter { it.elapsedMillis >= cutoff }
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
        ctx.drawText(canvas, value, x, y, 15f, color, ctx.monoBoldTypeface, Paint.Align.CENTER)
        ctx.drawText(canvas, label, x, y + 12f, 8f, ctx.colors.muted, ctx.monoTypeface, Paint.Align.CENTER)
    }

    private fun accuracyShortText(snapshot: RideTelemetrySnapshot, hasAltitude: Boolean): String =
        if (hasAltitude && snapshot.accuracyMeters != null) {
            "±${snapshot.accuracyMeters.roundToInt()} M"
        } else {
            "--"
        }

    private fun verticalLabel(verticalSpeed: Float?): String = when {
        verticalSpeed == null -> "VERTICAL SPEED"
        verticalSpeed > 0.15f -> "VERTICAL · CLIMBING"
        verticalSpeed < -0.15f -> "VERTICAL · DESCENDING"
        else -> "VERTICAL · LEVEL"
    }

    private fun verticalSpeedText(verticalSpeed: Float?): String = when {
        verticalSpeed == null -> "--"
        else -> String.format(Locale.US, "%+.1f M/S", verticalSpeed)
    }

    private fun rangeText(snapshot: RideTelemetrySnapshot): String {
        val min = snapshot.minAltitudeMeters ?: return "--"
        val max = snapshot.maxAltitudeMeters ?: return "--"
        return "${min.roundToInt()} / ${max.roundToInt()}"
    }

    private fun signedMeters(value: Double): String = when {
        abs(value) < 0.5 -> "0 M"
        else -> String.format(Locale.US, "%+.0f M", value)
    }

    private fun formatMeters(value: Double): String = value.roundToInt().toString()

    private fun formatCompactAltitude(value: Double): String = "${value.roundToInt()}M"

    companion object {
        private const val HOUR_MILLIS = 60 * 60 * 1_000L
        private val COLOR_CHART_TOP = 0xFF102D43.toInt()
        private val COLOR_CHART_BOTTOM = 0xFF07131D.toInt()
        private val COLOR_CHART_BORDER = 0xFF2B5367.toInt()
        private val COLOR_GRID = 0x332D6A7E
        private val COLOR_PROFILE_LINE = 0xFF7DE3E0.toInt()
        private val COLOR_PROFILE_FILL_TOP = 0x557DE3E0
        private val COLOR_PROFILE_FILL_BOTTOM = 0x087DE3E0
    }
}
