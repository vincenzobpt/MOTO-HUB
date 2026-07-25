package io.motohub.android.feature.ridedashboard.nav

import android.content.Context
import io.motohub.android.data.MotorcycleProfileStore
import java.time.Instant

/** Enriches a base routing result with M2b features: weather-at-arrival, fuel, golden-hour, curvy. */
class NavigationM2bEnricher(private val context: Context) {
    private val weatherClient = OpenMeteoWeatherClient(context)
    private val m2bSettings = NavigationM2bSettingsStore(context)
    private val motorcycleStore = MotorcycleProfileStore(context)

    suspend fun enrich(route: NavRoute, destination: NavPlace): NavRoute {
        val arrivalInstant = Instant.now().plusSeconds(route.durationSeconds.toLong())

        return route.copy(
            weatherAtArrival = if (m2bSettings.weatherAtArrivalEnabled()) {
                weatherClient.forecastAtArrival(destination.point, arrivalInstant)
            } else {
                null
            },
            estimatedFuelRemainingKm = if (m2bSettings.fuelRangeWarningEnabled()) enrichFuel(route) else null,
            minutesToGoldenHour = if (m2bSettings.goldenHourEnabled()) {
                minutesToGoldenHour(destination.point, Instant.now(), arrivalInstant)
            } else {
                null
            }
        )
    }

    private fun enrichFuel(route: NavRoute): Double? {
        val profile = motorcycleStore.load() ?: return null
        val tankRangeKm = profile.fuelTankRangeKm ?: return null
        if (tankRangeKm <= 0) return null

        val routeKm = route.distanceMeters / 1_000.0
        return (tankRangeKm - routeKm).coerceAtLeast(0.0)
    }
}
