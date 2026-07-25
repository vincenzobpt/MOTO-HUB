package io.motohub.android.feature.ridedashboard.nav

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Current weather data from Open-Meteo for the ride dashboard widget.
 */
data class CurrentWeather(
    val temperatureCelsius: Double,
    val feelsLikeCelsius: Double?,
    val weatherCode: Int,
    val description: String,
    val windSpeedKmh: Double?,
    val humidityPercent: Int?,
    val nextHours: List<WeatherForecastHour> = emptyList()
)

data class WeatherForecastHour(
    val timestamp: java.time.Instant,
    val temperatureCelsius: Double,
    val weatherCode: Int,
    val precipitationProbabilityPercent: Int?,
    val windSpeedKmh: Double?
)

/** Possible states for the weather widget data source. */
sealed interface WeatherWidgetState {
    data object Loading : WeatherWidgetState
    data class Loaded(val weather: CurrentWeather) : WeatherWidgetState
    data class Failed(val message: String) : WeatherWidgetState
}

/**
 * Process-local bridge between the weather fetch coroutine and the
 * dashboard render thread (read-only).
 *
 * - `state`: current widget state (Loading / Loaded / Failed)
 * - `fetchProgress`: human-readable hint shown below the icon
 */
object WeatherWidgetRuntime {
    private val mutableState = MutableStateFlow<WeatherWidgetState>(WeatherWidgetState.Loading)
    val state: StateFlow<WeatherWidgetState> = mutableState.asStateFlow()

    private val mutableFetchProgress = MutableStateFlow("")
    val fetchProgress: StateFlow<String> = mutableFetchProgress.asStateFlow()
    @Volatile private var loadedAtElapsedMillis = 0L

    fun publish(state: WeatherWidgetState) {
        mutableState.value = state
        if (state is WeatherWidgetState.Loaded) {
            loadedAtElapsedMillis = SystemClock.elapsedRealtime()
        }
    }

    fun publishFetchProgress(text: String) {
        mutableFetchProgress.value = text
    }

    fun reset() {
        mutableState.value = WeatherWidgetState.Loading
        mutableFetchProgress.value = ""
        loadedAtElapsedMillis = 0L
    }

    /** Alternates current conditions and the next-hours view every ten seconds. */
    fun shouldShowForecast(): Boolean {
        val loadedAt = loadedAtElapsedMillis
        if (loadedAt == 0L) return false
        val elapsed = SystemClock.elapsedRealtime() - loadedAt
        return elapsed >= DISPLAY_INTERVAL_MILLIS &&
            (elapsed / DISPLAY_INTERVAL_MILLIS) % 2L == 1L
    }

    private const val DISPLAY_INTERVAL_MILLIS = 10_000L
}
