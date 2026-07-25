package io.motohub.android.feature.ridedashboard.nav

import android.content.Context
import io.motohub.android.session.ProjectionEventLog
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

interface WeatherClient {
    suspend fun forecastAtArrival(
        location: NavPoint,
        arrivalInstant: Instant
    ): NavWeather?

    /** Fetch current weather at [location] using Open-Meteo's `current` endpoint. */
    suspend fun currentWeather(location: NavPoint): CurrentWeather?
}

/**
 * Fetches Open-Meteo forecasts (keyless, no bundled or shared key). Binds to
 * the cellular network the same way [ValhallaRoutingClient] and
 * [PhotonGeocodingClient] do, since the T-Box Wi-Fi network has no Internet -
 * the phone may still be joined to it while navigating.
 */
class OpenMeteoWeatherClient(
    context: Context,
    private val cellularOnly: Boolean = true
) : WeatherClient {
    private val applicationContext = context.applicationContext

    override suspend fun forecastAtArrival(
        location: NavPoint,
        arrivalInstant: Instant
    ): NavWeather? = withContext(Dispatchers.IO) {
        runCatching {
            withCellularNetwork(applicationContext, cellularOnly) { network ->
                val lat = location.latitude
                val lon = location.longitude
                val url = URL(
                    "https://api.open-meteo.com/v1/forecast" +
                        "?latitude=$lat&longitude=$lon" +
                        "&hourly=temperature_2m,weather_code" +
                        "&forecast_days=2" +
                        "&timezone=UTC"
                )

                val connection = (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
                val response = try {
                    connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
                    connection.readTimeout = READ_TIMEOUT_MILLIS
                    check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                        "Open-Meteo HTTP ${connection.responseCode}"
                    }
                    connection.inputStream.use { it.readBytes().decodeToString() }
                } finally {
                    connection.disconnect()
                }

                val json = JSONObject(response)
                val hourly = json.getJSONObject("hourly")
                val temperatureTimes = hourly.getJSONArray("time")
                val temperatures = hourly.getJSONArray("temperature_2m")
                val weatherCodes = hourly.getJSONArray("weather_code")

                val arrivedAt = arrivalInstant.epochSecond
                var bestIndex = -1
                var bestDelta = Long.MAX_VALUE

                for (i in 0 until temperatureTimes.length()) {
                    val hour = temperatureTimes.getString(i)
                    val hourEpoch = hour.parseISO8601ToEpochSeconds()
                    val delta = Math.abs(hourEpoch - arrivedAt)
                    if (delta < bestDelta) {
                        bestDelta = delta
                        bestIndex = i
                    }
                }

                if (bestIndex < 0) {
                    null
                } else {
                    NavWeather(
                        temperatureCelsius = temperatures.getDouble(bestIndex),
                        description = weatherCodeDescription(weatherCodes.getInt(bestIndex)),
                        weatherCode = weatherCodes.getInt(bestIndex),
                        timestamp = Instant.ofEpochSecond(temperatureTimes.getString(bestIndex).parseISO8601ToEpochSeconds())
                    )
                }
            }
        }.onFailure {
            ProjectionEventLog.warning("RIDE_NAV", "Weather forecast failed: ${it.message}", it)
        }.getOrNull()
    }

    override suspend fun currentWeather(location: NavPoint): CurrentWeather? = withContext(Dispatchers.IO) {
        runCatching {
            withCellularNetwork(applicationContext, cellularOnly) { network ->
                val lat = location.latitude
                val lon = location.longitude
                val url = URL(
                    "https://api.open-meteo.com/v1/forecast" +
                        "?latitude=$lat&longitude=$lon" +
                        "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m" +
                        "&hourly=temperature_2m,weather_code,precipitation_probability,wind_speed_10m" +
                        "&forecast_hours=5&timezone=UTC"
                )

                val connection = (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
                val response = try {
                    connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
                    connection.readTimeout = READ_TIMEOUT_MILLIS
                    check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                        "Open-Meteo HTTP ${connection.responseCode}"
                    }
                    connection.inputStream.use { it.readBytes().decodeToString() }
                } finally {
                    connection.disconnect()
                }

                val json = JSONObject(response)
                val current = json.getJSONObject("current")
                val temp = current.getDouble("temperature_2m")
                val code = current.getInt("weather_code")
                val feelsLike = if (current.has("apparent_temperature") && !current.isNull("apparent_temperature"))
                    current.getDouble("apparent_temperature") else null
                val windSpeed = if (current.has("wind_speed_10m") && !current.isNull("wind_speed_10m"))
                    current.getDouble("wind_speed_10m") else null
                val humidity = if (current.has("relative_humidity_2m") && !current.isNull("relative_humidity_2m"))
                    current.getInt("relative_humidity_2m") else null

                val nextHours = buildForecastHours(json)

                CurrentWeather(
                    temperatureCelsius = temp,
                    feelsLikeCelsius = feelsLike,
                    weatherCode = code,
                    description = weatherCodeDescription(code),
                    windSpeedKmh = windSpeed,
                    humidityPercent = humidity,
                    nextHours = nextHours
                )
            }
        }.onFailure {
            ProjectionEventLog.warning("RIDE_WEATHER", "Current weather fetch failed: ${it.message}", it)
        }.getOrNull()
    }

    private fun buildForecastHours(json: JSONObject): List<WeatherForecastHour> {
        val hourly = json.optJSONObject("hourly") ?: return emptyList()
        val times = hourly.optJSONArray("time") ?: return emptyList()
        val temperatures = hourly.optJSONArray("temperature_2m") ?: return emptyList()
        val weatherCodes = hourly.optJSONArray("weather_code") ?: return emptyList()
        val precipitation = hourly.optJSONArray("precipitation_probability")
        val winds = hourly.optJSONArray("wind_speed_10m")
        val now = Instant.now().minusSeconds(15 * 60L)
        return buildList {
            for (index in 0 until minOf(times.length(), temperatures.length(), weatherCodes.length())) {
                val timestamp = times.getString(index).parseISO8601ToEpochSeconds().let(Instant::ofEpochSecond)
                if (timestamp.isBefore(now)) continue
                add(
                    WeatherForecastHour(
                        timestamp = timestamp,
                        temperatureCelsius = temperatures.getDouble(index),
                        weatherCode = weatherCodes.getInt(index),
                        precipitationProbabilityPercent = precipitation?.let {
                            if (index < it.length() && !it.isNull(index)) it.getInt(index) else null
                        },
                        windSpeedKmh = winds?.let {
                            if (index < it.length() && !it.isNull(index)) it.getDouble(index) else null
                        }
                    )
                )
                if (size == 4) break
            }
        }
    }

    /**
     * Open-Meteo's `hourly.time` values look like "2026-07-19T00:00" - date
     * and hour/minute but no seconds and no zone suffix (UTC, since the
     * request uses timezone=UTC). [Instant.parse] requires seconds and a
     * zone offset, so both must be added before parsing.
     */
    private fun String.parseISO8601ToEpochSeconds(): Long {
        val withSeconds = if (count { it == ':' } < 2) "$this:00" else this
        return Instant.parse("${withSeconds}Z").epochSecond
    }

    private fun weatherCodeDescription(code: Int): String = when (code) {
        0 -> "Clear"
        1, 2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        61, 63, 65 -> "Rain"
        71, 73, 75, 77 -> "Snow"
        80, 81, 82 -> "Rain showers"
        85, 86 -> "Snow showers"
        95, 96, 99 -> "Thunderstorm"
        else -> "Unknown"
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 6_000
        const val READ_TIMEOUT_MILLIS = 8_000
    }
}
