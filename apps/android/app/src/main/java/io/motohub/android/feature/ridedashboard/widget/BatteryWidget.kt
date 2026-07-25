package io.motohub.android.feature.ridedashboard.widget

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import io.motohub.android.feature.ridedashboard.RideTelemetrySnapshot
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Clean phone/system overview with battery, connectivity and motion telemetry. */
class BatteryWidget : DashboardWidget {

    override val id: String = DashboardWidgetIDs.BATTERY
    override val title: String = "Battery & Phone"
    override val description: String = "Battery, charging, connectivity, temperature and motion sensors"

    private class SmoothingState {
        var smoothedGForce = 0f
        var smoothedGyroZ = 0f
        var sessionPeakG = 0f
    }

    private val leftSmoothing = SmoothingState()
    private val rightSmoothing = SmoothingState()

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
        val width = bounds.width()
        val state = if (isLeftPanel) leftSmoothing else rightSmoothing
        val battery = snapshot.batteryLevel.coerceIn(0, 100)

        ctx.drawPanelBorder(canvas, bounds, isLeftPanel)
        ctx.drawText(canvas, ctx.localized("PHONE // SYSTEM"), left + 18f, top + 25f, 11f,
            ctx.colors.muted, ctx.monoTypeface)
        ctx.drawText(canvas, when {
            snapshot.isCharging -> "CHARGING"
            battery <= 15 -> "LOW POWER"
            else -> "ONLINE"
        }, right - 18f, top + 25f, 9f,
            when {
                snapshot.isCharging -> ctx.colors.success
                battery <= 15 -> ctx.colors.warning
                else -> ctx.colors.muted
            }, ctx.monoBoldTypeface, Paint.Align.RIGHT)

        val hero = RectF(left + 10f, top + 39f, right - 10f, top + 102f)
        drawBatteryHero(canvas, hero, battery, snapshot, ctx)

        // Three compact rows keep each system value in its own cell. This replaces the old
        // dense grid and leaves enough vertical space for motion telemetry below it.
        val metricLeft = left + width * 0.25f
        val metricRight = left + width * 0.75f
        drawMetric(ctx, canvas, "TEMP", temperatureText(snapshot), metricLeft, top + 130f,
            temperatureColor(snapshot, ctx))
        drawMetric(ctx, canvas, "WI-FI", wifiText(snapshot), metricRight, top + 130f,
            wifiColor(snapshot, ctx))
        drawMetric(ctx, canvas, "STORAGE", "${snapshot.storageUsedPercent}%", metricLeft, top + 158f,
            ctx.colors.text)
        drawMetric(ctx, canvas, "CELLULAR", snapshot.cellularStatus, metricRight, top + 158f,
            if (snapshot.cellularStatus == "READY") ctx.colors.success else ctx.colors.warning)
        drawMetric(ctx, canvas, "VOLTAGE", voltageText(snapshot), metricLeft, top + 186f,
            ctx.colors.muted)
        drawMetric(ctx, canvas, "BAROMETER", pressureText(snapshot), metricRight, top + 186f,
            ctx.colors.muted)

        updateSmoothing(state, snapshot)
        drawMotionSection(canvas, left, right, top, state, ctx)
    }

    private fun drawBatteryHero(
        canvas: Canvas,
        hero: RectF,
        battery: Int,
        snapshot: RideTelemetrySnapshot,
        ctx: WidgetDrawingContext
    ) {
        ctx.fillPaint.style = Paint.Style.FILL
        ctx.fillPaint.color = ctx.colors.panel
        canvas.drawRoundRect(hero, 12f, 12f, ctx.fillPaint)
        ctx.strokePaint.style = Paint.Style.STROKE
        ctx.strokePaint.strokeWidth = 1f
        ctx.strokePaint.color = ctx.colors.border
        canvas.drawRoundRect(hero, 12f, 12f, ctx.strokePaint)

        val valueColor = when {
            battery <= 15 -> ctx.colors.warning
            battery <= 30 -> ctx.colors.route
            else -> ctx.colors.success
        }
        ctx.drawText(canvas, ctx.localized("$battery%"), hero.left + 14f, hero.top + 39f, 30f,
            valueColor, ctx.monoBoldTypeface)
        ctx.drawText(canvas, if (snapshot.isCharging) "EXTERNAL POWER" else "BATTERY LEVEL",
            hero.left + 16f, hero.bottom - 10f, 8f, ctx.colors.muted, ctx.monoTypeface)

        val barLeft = hero.left + 93f
        val barRight = hero.right - 14f
        val barTop = hero.top + 18f
        val barBottom = hero.top + 34f
        ctx.fillPaint.color = ctx.colors.background
        canvas.drawRoundRect(barLeft, barTop, barRight, barBottom, 4f, 4f, ctx.fillPaint)
        val fillRight = barLeft + (barRight - barLeft) * battery / 100f
        if (fillRight > barLeft) {
            ctx.fillPaint.color = valueColor
            canvas.drawRoundRect(barLeft, barTop, fillRight, barBottom, 4f, 4f, ctx.fillPaint)
        }
        ctx.strokePaint.color = ctx.colors.border
        ctx.strokePaint.strokeWidth = 1f
        canvas.drawRoundRect(barLeft, barTop, barRight, barBottom, 4f, 4f, ctx.strokePaint)
        ctx.drawText(canvas, voltageText(snapshot), barRight, hero.bottom - 10f, 10f,
            ctx.colors.text, ctx.monoBoldTypeface, Paint.Align.RIGHT)
    }

    private fun drawMotionSection(
        canvas: Canvas,
        left: Float,
        right: Float,
        top: Float,
        state: SmoothingState,
        ctx: WidgetDrawingContext
    ) {
        val separatorY = top + 201f
        ctx.strokePaint.style = Paint.Style.STROKE
        ctx.strokePaint.strokeWidth = 1f
        ctx.strokePaint.color = ctx.colors.border
        canvas.drawLine(left + 10f, separatorY, right - 10f, separatorY, ctx.strokePaint)
        ctx.drawText(canvas, ctx.localized("MOTION SENSORS"), left + 14f, separatorY + 15f, 9f,
            ctx.colors.muted, ctx.monoBoldTypeface)

        val gValue = state.smoothedGForce
        val gText = if (gValue >= 0f) String.format(Locale.US, "%.2f G", gValue) else "--"
        val turnText = if (state.smoothedGyroZ != 0f) {
            String.format(Locale.US, "%+.0f°/s", state.smoothedGyroZ * 180f / Math.PI.toFloat())
        } else "--"
        ctx.drawText(canvas, ctx.localized("G-FORCE"), left + 14f, separatorY + 33f, 8f,
            ctx.colors.muted, ctx.monoTypeface)
        ctx.drawText(canvas, gText, left + 14f, separatorY + 49f, 15f,
            ctx.colors.text, ctx.monoBoldTypeface)
        ctx.drawText(canvas, ctx.localized("TURN RATE"), right - 14f, separatorY + 33f, 8f,
            ctx.colors.muted, ctx.monoTypeface, Paint.Align.RIGHT)
        ctx.drawText(canvas, turnText, right - 14f, separatorY + 49f, 15f,
            ctx.colors.route, ctx.monoBoldTypeface, Paint.Align.RIGHT)

        val barLeft = left + 14f
        val barRight = right - 14f
        val barTop = separatorY + 56f
        val barBottom = barTop + 8f
        ctx.fillPaint.color = ctx.colors.panel
        ctx.fillPaint.style = Paint.Style.FILL
        canvas.drawRoundRect(barLeft, barTop, barRight, barBottom, 3f, 3f, ctx.fillPaint)
        if (gValue >= 0f) {
            val fillRatio = (gValue / 3f).coerceIn(0f, 1f)
            ctx.fillPaint.color = when {
                gValue < 0.5f -> ctx.colors.success
                gValue < 1.5f -> ctx.colors.route
                else -> ctx.colors.warning
            }
            canvas.drawRoundRect(barLeft, barTop, barLeft + (barRight - barLeft) * fillRatio, barBottom,
                3f, 3f, ctx.fillPaint)
        }
        if (state.sessionPeakG > 0f && gValue >= 0f) {
            val peakX = barLeft + (barRight - barLeft) * (state.sessionPeakG / 3f).coerceIn(0f, 1f)
            ctx.strokePaint.color = ctx.colors.text
            ctx.strokePaint.strokeWidth = 1.5f
            canvas.drawLine(peakX, barTop - 2f, peakX, barBottom + 2f, ctx.strokePaint)
        }
    }

    private fun updateSmoothing(state: SmoothingState, snapshot: RideTelemetrySnapshot) {
        val rawG = snapshot.linearAccelMagnitude
        if (rawG >= 0f) {
            state.smoothedGForce = 0.08f * rawG + 0.92f * state.smoothedGForce
            if (state.smoothedGForce < 0.15f) state.smoothedGForce = 0f
            state.sessionPeakG *= 0.99995f
            if (state.smoothedGForce > state.sessionPeakG) state.sessionPeakG = state.smoothedGForce
        } else {
            state.smoothedGForce = rawG
        }
        state.smoothedGyroZ = 0.06f * snapshot.gyroZ + 0.94f * state.smoothedGyroZ
        if (abs(state.smoothedGyroZ) < 0.05f) state.smoothedGyroZ = 0f
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

    private fun temperatureText(snapshot: RideTelemetrySnapshot): String =
        if (snapshot.batteryTemperatureCelsius >= 0f) "${snapshot.batteryTemperatureCelsius.roundToInt()}°C" else "--"

    private fun temperatureColor(snapshot: RideTelemetrySnapshot, ctx: WidgetDrawingContext): Int = when {
        snapshot.batteryTemperatureCelsius < 0f -> ctx.colors.muted
        snapshot.batteryTemperatureCelsius >= 60f -> ctx.colors.warning
        snapshot.batteryTemperatureCelsius >= 50f -> ctx.colors.route
        else -> ctx.colors.text
    }

    private fun wifiText(snapshot: RideTelemetrySnapshot): String =
        if (snapshot.wifiRssiDbm != 0) "${snapshot.wifiRssiDbm} dBm" else "--"

    private fun wifiColor(snapshot: RideTelemetrySnapshot, ctx: WidgetDrawingContext): Int = when {
        snapshot.wifiRssiDbm == 0 -> ctx.colors.muted
        snapshot.wifiRssiDbm > -50 -> ctx.colors.success
        snapshot.wifiRssiDbm > -70 -> ctx.colors.text
        snapshot.wifiRssiDbm > -85 -> ctx.colors.route
        else -> ctx.colors.warning
    }

    private fun voltageText(snapshot: RideTelemetrySnapshot): String =
        if (snapshot.batteryVoltageMv > 0) "${snapshot.batteryVoltageMv} mV" else "--"

    private fun pressureText(snapshot: RideTelemetrySnapshot): String =
        if (snapshot.barometricPressureHpa > 0f)
            String.format(Locale.US, "%.1f hPa", snapshot.barometricPressureHpa)
        else "--"
}
