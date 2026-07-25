package io.motohub.android.feature.ridedashboard.nav

import io.motohub.android.session.ProjectionEventLog
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Periodically fetches current weather at the given GPS position via
 * Open-Meteo (keyless) and publishes to [WeatherWidgetRuntime]. Shared by
 * the real T-Box session ([io.motohub.android.feature.ridedashboard.RideDashboardSessionService])
 * and the phone-side dashboard preview ([io.motohub.android.feature.ridedashboard.RideDashboardPreviewScreen])
 * so the weather widget behaves the same in both - only the caller's
 * coroutine scope decides when this loop stops, by cancelling it.
 *
 * - First fetch is immediate (no delay).
 * - If GPS position is null (no fix yet), retries every 5 seconds.
 * - After a successful fetch, switches to 5-minute intervals.
 * - Errors are published to the runtime so the widget can display them.
 */
suspend fun runWeatherUpdateLoop(weatherClient: OpenMeteoWeatherClient, positionProvider: () -> NavPoint?) {
    WeatherWidgetRuntime.reset()
    // Two independent counters, not one: a GPS gap (tunnel, canyon) isn't a weather-API failure -
    // no fetch is even attempted while position is null - so it must not inflate the same "Retry N"
    // shown for real API errors, and it must clear the moment GPS returns, not only after the next
    // fetch happens to succeed.
    var consecutiveErrors = 0
    var gpsWaitCycles = 0
    while (true) {
        val position = positionProvider()
        if (position != null) {
            gpsWaitCycles = 0
            WeatherWidgetRuntime.publishFetchProgress("FETCHING")
            val weather = weatherClient.currentWeather(position)
            if (weather != null) {
                WeatherWidgetRuntime.publish(WeatherWidgetState.Loaded(weather))
                consecutiveErrors = 0
                ProjectionEventLog.record(
                    "RIDE_WEATHER",
                    "Current weather: ${weather.temperatureCelsius}°C, ${weather.description}, " +
                        "wind=${weather.windSpeedKmh?.roundToInt() ?: "?"} km/h, " +
                        "humidity=${weather.humidityPercent ?: "?"}%."
                )
                delay(WEATHER_FETCH_INTERVAL_MILLIS)
            } else {
                consecutiveErrors++
                val errorMsg = if (consecutiveErrors == 1) "Weather unavailable" else "Retry $consecutiveErrors"
                WeatherWidgetRuntime.publish(WeatherWidgetState.Failed(errorMsg))
                delay(WEATHER_RETRY_MILLIS)
            }
        } else {
            WeatherWidgetRuntime.publishFetchProgress(
                if (gpsWaitCycles > 0) "NO GPS / RETRY $gpsWaitCycles" else "WAITING FOR GPS"
            )
            delay(WEATHER_GPS_WAIT_MILLIS)
            gpsWaitCycles++
        }
    }
}

private const val WEATHER_FETCH_INTERVAL_MILLIS = 300_000L
private const val WEATHER_RETRY_MILLIS = 15_000L
private const val WEATHER_GPS_WAIT_MILLIS = 5_000L
