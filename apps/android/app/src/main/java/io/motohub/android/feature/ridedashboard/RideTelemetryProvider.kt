package io.motohub.android.feature.ridedashboard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.SystemClock
import androidx.core.content.ContextCompat
import io.motohub.android.session.ProjectionEventLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

class RideTelemetryProvider(context: Context) : LocationListener {
    private val applicationContext = context.applicationContext
    private val locationManager = applicationContext.getSystemService(LocationManager::class.java)
    private val accumulator = RideTelemetryAccumulator()
    private val addressScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var currentAddress = ""
    private var lastGeocodeLocation: Location? = null
    private var lastGeocodeStartedElapsedMillis = Long.MIN_VALUE
    private var geocodeJob: Job? = null
    private var firstFixLogged = false
    private var started = false

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (index in 0 until status.satelliteCount) {
                if (status.usedInFix(index)) used++
            }
            accumulator.updateSatellites(status.satelliteCount, used)
        }

        override fun onStopped() {
            accumulator.updateSatellites(0, 0)
        }
    }

    fun start(): Result<Unit> = runCatching {
        if (started) return@runCatching
        check(
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) { "Precise location permission is required for the Ride Dashboard." }

        val request = LocationRequest.Builder(LOCATION_INTERVAL_MILLIS)
            .setMinUpdateIntervalMillis(MIN_LOCATION_INTERVAL_MILLIS)
            // Keep receiving fix heartbeats while stationary. Trip and track jitter
            // are filtered independently by RideTelemetryAccumulator.
            .setMinUpdateDistanceMeters(0f)
            .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
            .build()
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER)
            .distinct()
            .filter(locationManager.allProviders::contains)
        check(providers.isNotEmpty()) { "Android has no GPS or fused location provider." }
        try {
            providers.forEach { provider ->
                locationManager.requestLocationUpdates(
                    provider,
                    request,
                    applicationContext.mainExecutor,
                    this
                )
            }
            locationManager.registerGnssStatusCallback(applicationContext.mainExecutor, gnssCallback)
        } catch (failure: Throwable) {
            runCatching { locationManager.removeUpdates(this) }
            runCatching { locationManager.unregisterGnssStatusCallback(gnssCallback) }
            throw failure
        }
        started = true
        ProjectionEventLog.record(
            "RIDE_DASHBOARD",
            "Location telemetry started; providers=${providers.joinToString()}, " +
                "gpsProviderEnabled=${locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)}."
        )
    }

    fun stop() {
        if (!started) return
        runCatching { locationManager.removeUpdates(this) }
        runCatching { locationManager.unregisterGnssStatusCallback(gnssCallback) }
        geocodeJob?.cancel()
        geocodeJob = null
        started = false
        ProjectionEventLog.record("RIDE_DASHBOARD", "GNSS telemetry stopped.")
    }

    fun snapshot(): RideTelemetrySnapshot = accumulator.snapshot().copy(currentAddress = currentAddress)

    override fun onLocationChanged(location: Location) {
        accumulator.accept(
            RideLocationSample(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyMeters = location.accuracy,
                // Use local receipt time: some OEM location stacks report a different
                // elapsed-realtime base, which would make a fresh fix appear stale.
                elapsedRealtimeMillis = SystemClock.elapsedRealtime(),
                speedMetersPerSecond = location.speed.takeIf { location.hasSpeed() },
                bearingDegrees = location.bearing.takeIf { location.hasBearing() },
                altitudeMeters = location.altitude.takeIf { location.hasAltitude() }
            )
        )
        requestAddressUpdate(location)
        if (!firstFixLogged) {
            firstFixLogged = true
            ProjectionEventLog.record(
                "RIDE_DASHBOARD",
                "First GNSS fix received; accuracy=${location.accuracy.toInt()}m, " +
                    "speedPresent=${location.hasSpeed()}, bearingPresent=${location.hasBearing()}."
            )
        }
    }

    override fun onProviderEnabled(provider: String) {
        ProjectionEventLog.record("RIDE_DASHBOARD", "Location provider enabled: $provider.")
    }

    override fun onProviderDisabled(provider: String) {
        ProjectionEventLog.warning("RIDE_DASHBOARD", "Location provider disabled: $provider.")
    }

    /**
     * Reverse-geocodes at most every 30 seconds and only after moving 100 m.
     * Geocoder work is deliberately off the location/render threads: a slow or
     * unavailable provider must never make the dashboard stutter.
     */
    private fun requestAddressUpdate(location: Location) {
        if (!Geocoder.isPresent()) return
        val now = SystemClock.elapsedRealtime()
        val previous = lastGeocodeLocation
        if (lastGeocodeStartedElapsedMillis != Long.MIN_VALUE &&
            now - lastGeocodeStartedElapsedMillis < ADDRESS_REFRESH_INTERVAL_MILLIS &&
            previous?.distanceTo(location)?.let { it < ADDRESS_REFRESH_DISTANCE_METERS } != false
        ) return
        lastGeocodeStartedElapsedMillis = now
        lastGeocodeLocation = Location(location)
        geocodeJob?.cancel()
        geocodeJob = addressScope.launch {
            runCatching {
                @Suppress("DEPRECATION")
                Geocoder(applicationContext, Locale.getDefault())
                    .getFromLocation(location.latitude, location.longitude, 1)
                    .orEmpty()
                    .firstOrNull()
                    ?.let(::formatAddress)
                    ?.takeIf(String::isNotBlank)
            }.getOrNull()?.let { resolved ->
                currentAddress = resolved
            }
        }
    }

    private fun formatAddress(address: android.location.Address): String {
        val street = address.thoroughfare?.takeIf(String::isNotBlank)
        val number = address.subThoroughfare?.takeIf(String::isNotBlank)
        val city = (address.locality ?: address.subAdminArea ?: address.adminArea)
            ?.takeIf(String::isNotBlank)
        val streetLine = listOfNotNull(street, number).joinToString(" ")
        return listOfNotNull(streetLine.takeIf(String::isNotBlank), city)
            .joinToString(", ")
    }

    private companion object {
        const val LOCATION_INTERVAL_MILLIS = 1_000L
        const val MIN_LOCATION_INTERVAL_MILLIS = 500L
        const val ADDRESS_REFRESH_INTERVAL_MILLIS = 30_000L
        const val ADDRESS_REFRESH_DISTANCE_METERS = 100f
    }
}
