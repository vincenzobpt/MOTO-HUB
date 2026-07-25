package io.motohub.android.feature.ridedashboard.widget

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import io.motohub.android.feature.ridedashboard.RideTelemetrySnapshot
import io.motohub.android.units.UnitFormat
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Trip overview built around four large, glanceable numbers. The previous layout spent
 * roughly a third of the panel's height on a small speed-profile chart, which left little
 * room for the actual numbers and made them too small to read on the TFT while riding. The
 * chart is gone; that space now goes into a bigger average-speed readout and bigger metric
 * grid, and every value/unit pair is split onto two lines so the number itself can be large
 * without the unit text forcing it to shrink to fit the narrow side panel.
 */
class TripMetricsWidget : DashboardWidget {

    override val id: String = DashboardWidgetIDs.TRIP_METRICS
    override val title: String = "Trip Metrics"
    override val description: String = "Trip distance, duration, average speed and max speed"

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
        val sessionLive = snapshot.elapsedMillis > 0L
        val avgColor = if (sessionLive) ctx.colors.text else ctx.colors.muted

        ctx.drawPanelBorder(canvas, bounds, isLeftPanel)
        ctx.drawText(canvas, ctx.localized("TRIP // PERFORMANCE"), left + 18f, top + 25f, 11f,
            ctx.colors.muted, ctx.monoTypeface)
        ctx.drawText(canvas, if (sessionLive) "SESSION LIVE" else "READY", right - 18f, top + 25f, 10f,
            if (sessionLive) ctx.colors.success else ctx.colors.muted,
            ctx.monoBoldTypeface, Paint.Align.RIGHT)

        val units = ctx.units
        ctx.drawText(canvas, UnitFormat.speed(snapshot.averageKph, units).roundToInt().toString(),
            centerX, top + 118f, 72f,
            avgColor, ctx.monoBoldTypeface, Paint.Align.CENTER)
        ctx.drawText(canvas, ctx.localized("AVERAGE ${UnitFormat.speedLabel(units)}"), centerX, top + 140f, 14f,
            ctx.colors.muted, ctx.monoTypeface, Paint.Align.CENTER)

        // Two rows/two columns, values-only (units live in the label line below), so each
        // number can be drawn large without the string width colliding with its neighbour
        // on the narrow 216 px side panel.
        val metricLeft = left + 54f
        val metricRight = right - 54f
        drawMetric(ctx, canvas, "DISTANCE ${UnitFormat.distanceValueLabel(snapshot.tripMeters, units)}",
            UnitFormat.distanceValue(snapshot.tripMeters, units),
            metricLeft, top + 196f, ctx.colors.text)
        drawMetric(ctx, canvas, "ELAPSED", formatDuration(snapshot.elapsedMillis), metricRight, top + 196f,
            ctx.colors.text)
        drawMetric(ctx, canvas, "ALTITUDE ${UnitFormat.altitudeLabel(units)}",
            snapshot.altitudeMeters?.let { "${UnitFormat.altitudeValue(it, units)}" } ?: "--",
            metricLeft, top + 250f, ctx.colors.text)
        drawMetric(ctx, canvas, "MAX ${UnitFormat.speedLabel(units)}",
            UnitFormat.speed(snapshot.maxKph, units).roundToInt().toString(), metricRight, top + 250f,
            ctx.colors.route)
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
        ctx.drawText(canvas, value, x, y, 30f, color, ctx.monoBoldTypeface, Paint.Align.CENTER)
        ctx.drawText(canvas, label, x, y + 20f, 12f, ctx.colors.muted, ctx.monoTypeface, Paint.Align.CENTER)
    }

    private fun formatDuration(elapsedMillis: Long): String {
        val totalMinutes = elapsedMillis / 60_000L
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return String.format(Locale.US, "%02d:%02d", hours, minutes)
    }
}
