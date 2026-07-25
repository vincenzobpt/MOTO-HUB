package io.motohub.android.feature.ridedashboard.widget

import io.motohub.android.i18n.motoHubText

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import io.motohub.android.feature.ridedashboard.RideTelemetrySnapshot
import io.motohub.android.feature.ridedashboard.nav.CurrentWeather
import io.motohub.android.feature.ridedashboard.nav.WeatherForecastHour
import io.motohub.android.feature.ridedashboard.nav.WeatherWidgetRuntime
import io.motohub.android.feature.ridedashboard.nav.WeatherWidgetState
import io.motohub.android.units.UnitFormat
import kotlin.math.roundToInt
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Weather widget showing current conditions, temperature, wind, humidity
 * and feels-like temperature. Reads from [WeatherWidgetRuntime] which is
 * updated asynchronously by the session service.
 *
 * Handles three states: Loading / Loaded / Failed — all displayed
 * directly inside the dashboard Canvas.
 */
class WeatherWidget : DashboardWidget {

    override val id: String = DashboardWidgetIDs.WEATHER
    override val title: String = "Weather"
    override val description: String = "Current conditions, wind, humidity via Open-Meteo"

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
        val cx = (left + right) / 2f
        val panelWidth = right - left

        ctx.drawPanelBorder(canvas, bounds, isLeftPanel)

        // Label
        ctx.textPaint.textSize = 12f
        ctx.textPaint.color = ctx.colors.muted
        ctx.textPaint.textAlign = Paint.Align.CENTER
        ctx.textPaint.typeface = ctx.monoTypeface
        val state = WeatherWidgetRuntime.state.value
        val showForecast = WeatherWidgetRuntime.shouldShowForecast() &&
            state is WeatherWidgetState.Loaded && state.weather.nextHours.isNotEmpty()
        canvas.drawText(
            if (showForecast) "WEATHER / NEXT 4 HOURS" else "WEATHER / CURRENT",
            cx,
            top + 26f,
            ctx.textPaint
        )

        val fetchProgress = WeatherWidgetRuntime.fetchProgress.value

        when (state) {
            is WeatherWidgetState.Loading -> drawLoadingState(canvas, cx, top, bottom, fetchProgress, ctx)
            is WeatherWidgetState.Loaded -> if (showForecast && state.weather.nextHours.isNotEmpty()) {
                drawForecastData(canvas, left, right, top, bottom, state.weather.nextHours, ctx)
            } else {
                drawWeatherData(canvas, left, right, cx, top, bottom, panelWidth, state.weather, ctx)
            }
            is WeatherWidgetState.Failed -> drawErrorState(canvas, cx, top, bottom, state.message, ctx)
        }
    }

    private fun drawLoadingState(
        canvas: Canvas, cx: Float, top: Float, bottom: Float,
        progress: String, ctx: WidgetDrawingContext
    ) {
        ctx.textPaint.textSize = 36f
        ctx.textPaint.color = ctx.colors.muted
        ctx.textPaint.textAlign = Paint.Align.CENTER
        ctx.textPaint.typeface = ctx.monoTypeface
        canvas.drawText("\u23F3", cx, top + 82f, ctx.textPaint) // ⏳ hourglass

        ctx.textPaint.textSize = 14f
        ctx.textPaint.color = ctx.colors.muted
        ctx.textPaint.typeface = ctx.monoTypeface
        canvas.drawText(motoHubText("LOADING"), cx, top + 110f, ctx.textPaint)

        if (progress.isNotEmpty()) {
            ctx.textPaint.textSize = 10f
            ctx.textPaint.color = ctx.colors.warning
            ctx.textPaint.typeface = ctx.monoTypeface
            canvas.drawText(progress, cx, top + 130f, ctx.textPaint)
        }
    }

    private fun drawErrorState(
        canvas: Canvas, cx: Float, top: Float, bottom: Float,
        message: String, ctx: WidgetDrawingContext
    ) {
        ctx.textPaint.textSize = 32f
        ctx.textPaint.color = ctx.colors.warning
        ctx.textPaint.textAlign = Paint.Align.CENTER
        ctx.textPaint.typeface = ctx.monoTypeface
        canvas.drawText("\u26A0\uFE0F", cx, top + 78f, ctx.textPaint) // ⚠️

        ctx.textPaint.textSize = 12f
        ctx.textPaint.color = ctx.colors.warning
        ctx.textPaint.typeface = ctx.monoTypeface
        canvas.drawText(message, cx, top + 106f, ctx.textPaint)

        // Retry hint
        ctx.textPaint.textSize = 10f
        ctx.textPaint.color = ctx.colors.muted
        ctx.textPaint.typeface = ctx.monoTypeface
        canvas.drawText(motoHubText("RETRYING EVERY 15S"), cx, top + 124f, ctx.textPaint)

        ctx.textPaint.textSize = 9f
        ctx.textPaint.color = ctx.colors.muted
        canvas.drawText(motoHubText("CHECK CELLULAR DATA"), cx, top + 140f, ctx.textPaint)
    }

    private fun drawWeatherData(
        canvas: Canvas, left: Float, right: Float, cx: Float,
        top: Float, bottom: Float, panelWidth: Float,
        weather: CurrentWeather, ctx: WidgetDrawingContext
    ) {
        // ===== ROW 1: Emoji + Temperature (big) =====
        ctx.textPaint.textSize = 42f
        ctx.textPaint.color = ctx.colors.text
        ctx.textPaint.textAlign = Paint.Align.CENTER
        ctx.textPaint.typeface = ctx.boldTypeface
        val emoji = weatherEmoji(weather.weatherCode)
        canvas.drawText(emoji, cx, top + 64f, ctx.textPaint)

        ctx.textPaint.textSize = 30f
        ctx.textPaint.color = ctx.colors.text
        ctx.textPaint.typeface = ctx.monoBoldTypeface
        canvas.drawText(ctx.localized("%1\$d°C", weather.temperatureCelsius.roundToInt()), cx, top + 102f, ctx.textPaint)

        // Description
        ctx.textPaint.textSize = 12f
        ctx.textPaint.color = ctx.colors.muted
        ctx.textPaint.typeface = ctx.monoTypeface
        canvas.drawText(weather.description.uppercase(), cx, top + 122f, ctx.textPaint)

        // ===== ROW 2: Divider + Metrics grid =====
        val row2Top = top + 132f
        val row2Bottom = top + 192f
        ctx.strokePaint.style = Paint.Style.STROKE
        ctx.strokePaint.strokeWidth = 1f
        ctx.strokePaint.color = ctx.colors.border
        canvas.drawLine(left + 10f, row2Top, right - 10f, row2Top, ctx.strokePaint)

        val colW = panelWidth / 2f

        // Helper
        fun drawMetric(label: String, value: String, x: Float, y: Float, valueColor: Int = ctx.colors.text) {
            ctx.textPaint.textSize = 10f
            ctx.textPaint.color = ctx.colors.muted
            ctx.textPaint.textAlign = Paint.Align.CENTER
            ctx.textPaint.typeface = ctx.monoTypeface
            canvas.drawText(label, x, y, ctx.textPaint)
            ctx.textPaint.textSize = 16f
            ctx.textPaint.color = valueColor
            ctx.textPaint.typeface = ctx.monoBoldTypeface
            canvas.drawText(value, x, y + 20f, ctx.textPaint)
        }

        // Row 2a
        val windStr = weather.windSpeedKmh?.let {
            "${UnitFormat.speed(it, ctx.units).roundToInt()} ${ctx.localized(UnitFormat.speedLabel(ctx.units))}"
        } ?: "--"
        val feelsStr = weather.feelsLikeCelsius?.let { "${it.roundToInt()}\u00B0C" } ?: "--"
        drawMetric("WIND", windStr, left + colW * 0.5f, row2Top + 18f)
        drawMetric("FEELS LIKE", feelsStr, left + colW * 1.5f, row2Top + 18f,
            valueColor = if (weather.feelsLikeCelsius != null && weather.feelsLikeCelsius < weather.temperatureCelsius - 3f)
                ctx.colors.warning else ctx.colors.text)

        // Row 2b
        canvas.drawLine(left + 10f, row2Bottom, right - 10f, row2Bottom, ctx.strokePaint)
        val humStr = weather.humidityPercent?.let { "$it%" } ?: "--"
        drawMetric("HUMIDITY", humStr, left + colW * 0.5f, row2Bottom + 18f,
            valueColor = if (weather.humidityPercent != null && weather.humidityPercent > 80) ctx.colors.warning else ctx.colors.text)
        drawMetric("WMO CODE", "${weather.weatherCode}", left + colW * 1.5f, row2Bottom + 18f, ctx.colors.muted)

        // Live indicator
        ctx.textPaint.textSize = 10f
        ctx.textPaint.color = ctx.colors.success
        ctx.textPaint.textAlign = Paint.Align.RIGHT
        ctx.textPaint.typeface = ctx.monoTypeface
        canvas.drawText(motoHubText("LIVE"), right - 10f, bottom - 10f, ctx.textPaint)
    }

    private fun drawForecastData(
        canvas: Canvas,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        hours: List<WeatherForecastHour>,
        ctx: WidgetDrawingContext
    ) {
        val formatter = DateTimeFormatter.ofPattern("HH:mm")
        val rowHeight = ((bottom - top - 48f) / 4f).coerceAtLeast(42f)
        hours.take(4).forEachIndexed { index, hour ->
            val rowTop = top + 42f + index * rowHeight
            val rowBottom = (rowTop + rowHeight - 4f).coerceAtMost(bottom - 8f)
            if (index > 0) {
                ctx.strokePaint.color = ctx.colors.border
                ctx.strokePaint.strokeWidth = 1f
                canvas.drawLine(left + 10f, rowTop - 4f, right - 10f, rowTop - 4f, ctx.strokePaint)
            }
            ctx.textPaint.textAlign = Paint.Align.LEFT
            ctx.textPaint.typeface = ctx.monoBoldTypeface
            ctx.textPaint.textSize = 12f
            ctx.textPaint.color = ctx.colors.text
            canvas.drawText(hour.timestamp.atZone(ZoneId.systemDefault()).format(formatter), left + 12f, rowTop + 16f, ctx.textPaint)

            ctx.textPaint.textSize = 24f
            canvas.drawText(weatherEmoji(hour.weatherCode), left + 60f, rowTop + 22f, ctx.textPaint)

            ctx.textPaint.textSize = 9f
            ctx.textPaint.typeface = ctx.monoTypeface
            ctx.textPaint.color = ctx.colors.muted
            val description = weatherDescription(hour.weatherCode).uppercase().take(12)
            canvas.drawText(description, left + 92f, rowTop + 13f, ctx.textPaint)
            hour.precipitationProbabilityPercent?.let { probability ->
        canvas.drawText(ctx.localized("RAIN %1\$d%%", probability), left + 92f, rowTop + 27f, ctx.textPaint)
            }

            ctx.textPaint.textAlign = Paint.Align.RIGHT
            ctx.textPaint.typeface = ctx.monoBoldTypeface
            ctx.textPaint.textSize = 18f
            ctx.textPaint.color = ctx.colors.text
        canvas.drawText(ctx.localized("%1\$d°C", hour.temperatureCelsius.roundToInt()), right - 12f, rowTop + 20f, ctx.textPaint)
            hour.windSpeedKmh?.let { wind ->
                ctx.textPaint.textSize = 8f
                ctx.textPaint.typeface = ctx.monoTypeface
                ctx.textPaint.color = ctx.colors.muted
                canvas.drawText(
                    ctx.localized("%1\$d ${UnitFormat.speedLabel(ctx.units)}", UnitFormat.speed(wind, ctx.units).roundToInt()),
                    right - 12f, rowTop + 32f, ctx.textPaint
                )
            }
            if (rowBottom <= rowTop) return@forEachIndexed
        }
        ctx.textPaint.textAlign = Paint.Align.RIGHT
        ctx.textPaint.textSize = 9f
        ctx.textPaint.typeface = ctx.monoTypeface
        ctx.textPaint.color = ctx.colors.success
        canvas.drawText(motoHubText("OPEN-METEO • GPS"), right - 10f, bottom - 8f, ctx.textPaint)
    }

    private fun weatherDescription(code: Int): String = when (code) {
        0 -> "CLEAR"
        1, 2 -> "PARTLY CLOUDY"
        3 -> "OVERCAST"
        45, 48 -> "FOG"
        51, 53, 55 -> "DRIZZLE"
        61, 63, 65, 80, 81, 82 -> "RAIN"
        71, 73, 75, 77, 85, 86 -> "SNOW"
        95, 96, 99 -> "STORM"
        else -> "UNKNOWN"
    }

    private fun weatherEmoji(code: Int): String = when (code) {
        0 -> "\u2600\uFE0F"          // ☀️
        1 -> "\uD83C\uDF24\uFE0F"    // 🌤️
        2 -> "\u26C5"                // ⛅
        3 -> "\u2601\uFE0F"          // ☁️
        45, 48 -> "\uD83C\uDF2B\uFE0F"  // 🌫️
        51 -> "\uD83C\uDF26\uFE0F"    // 🌦️
        53 -> "\uD83C\uDF26\uFE0F"    // 🌦️
        55 -> "\uD83C\uDF27\uFE0F"    // 🌧️
        61, 63, 65 -> "\uD83C\uDF27\uFE0F" // 🌧️
        71, 73, 75 -> "\uD83C\uDF28\uFE0F" // 🌨️
        77 -> "\u2744\uFE0F"         // ❄️
        80, 81, 82 -> "\uD83C\uDF27\uFE0F" // 🌧️
        85, 86 -> "\uD83C\uDF28\uFE0F"     // 🌨️
        95 -> "\u26C8\uFE0F"         // ⛈️
        96, 99 -> "\uD83C\uDF2A\uFE0F"     // 🌪️
        else -> "\u2753"              // ❓
    }
}
