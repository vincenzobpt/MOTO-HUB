package io.motohub.android.feature.navigation

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.LocationManager
import android.os.SystemClock
import io.motohub.android.i18n.motoHubText
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.motohub.android.data.MotorcycleProfileStore
import io.motohub.android.feature.ridedashboard.nav.GeocodingClient
import io.motohub.android.feature.ridedashboard.nav.NavPlace
import io.motohub.android.feature.ridedashboard.nav.NavPoint
import io.motohub.android.feature.ridedashboard.nav.NavRoute
import io.motohub.android.feature.ridedashboard.nav.NavigationM2bEnricher
import io.motohub.android.feature.ridedashboard.nav.NavigationPlacesStore
import io.motohub.android.feature.ridedashboard.nav.NavigationProgress
import io.motohub.android.feature.ridedashboard.nav.NavigationRuntime
import io.motohub.android.feature.ridedashboard.nav.NavigationSettingsStore
import io.motohub.android.feature.ridedashboard.nav.PhotonGeocodingClient
import io.motohub.android.feature.ridedashboard.nav.RoutingClient
import io.motohub.android.feature.ridedashboard.nav.SavedPlace
import io.motohub.android.feature.ridedashboard.nav.SavedRide
import io.motohub.android.feature.ridedashboard.nav.SavedRidesStore
import io.motohub.android.feature.ridedashboard.nav.parseCoordinates
import io.motohub.android.feature.ridedashboard.nav.routingClientFor
import io.motohub.android.feature.settings.DistanceUnits
import io.motohub.android.feature.settings.MotoHubSettings
import io.motohub.android.feature.settings.RoutePreference
import io.motohub.android.feature.trips.TripRecordingService
import io.motohub.android.feature.trips.TripRecordingSource
import io.motohub.android.session.ProjectionEventLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** A calculated-but-not-yet-started route the rider can review before committing. */
data class RoutePreview(
    val destination: NavPlace,
    val route: NavRoute,
    val preference: RoutePreference,
    /** Reverse-geocoded address of the exact map pin, independent of the search label. */
    val effectiveAddress: String = destination.label
)

/** Where the rider has arrived, offered for saving as a favorite. */
data class ArrivalInfo(val destination: NavPlace)

/** Typed status so the UI shows a specific message instead of one generic error. */
sealed interface NavStatus {
    data object NoApiKey : NavStatus
    data object NoNetwork : NavStatus
    data object RateLimited : NavStatus
    data class Message(val text: String) : NavStatus
}

data class NavigationUiState(
    val query: String = "",
    val suggestions: List<NavPlace> = emptyList(),
    val recents: List<SavedPlace> = emptyList(),
    val favorites: List<SavedPlace> = emptyList(),
    val savedRides: List<SavedRide> = emptyList(),
    val home: SavedPlace? = null,
    val isSearching: Boolean = false,
    val isRouting: Boolean = false,
    val preview: RoutePreview? = null,
    val navigationActive: Boolean = false,
    val progress: NavigationProgress? = null,
    val arrived: ArrivalInfo? = null,
    val units: DistanceUnits = DistanceUnits.KILOMETERS,
    val voiceMuted: Boolean = false,
    val status: NavStatus? = null
)

/**
 * M2a phone navigation: autocomplete search, recents/favorites, route preview
 * with a fastest/scenic toggle, start/stop, voice mute, and arrival handling.
 * See NAVIGATION_M2_REQUIREMENTS.md.
 *
 * Takes only [Application] so Compose's default `viewModel()` factory can
 * construct it via reflection (a Kotlin default-parameter constructor has no
 * single-`Application` JVM overload, which crashes that factory).
 */
class NavigationViewModel(application: Application) : AndroidViewModel(application) {
    private val geocodingClient: GeocodingClient = PhotonGeocodingClient(application)
    private val placesStore = NavigationPlacesStore(application)
    private val ridesStore = SavedRidesStore(application)
    private val m2bEnricher: NavigationM2bEnricher = NavigationM2bEnricher(application)
    private val mutableUiState = MutableStateFlow(NavigationUiState())
    val uiState: StateFlow<NavigationUiState> = mutableUiState.asStateFlow()

    private var searchJob: Job? = null
    private var rerouteJob: Job? = null
    private var previewAddressJob: Job? = null
    private var activeDestination: NavPlace? = null
    private var lastRerouteElapsedRealtimeMillis = Long.MIN_VALUE

    init {
        val context = getApplication<Application>()
        val muted = !MotoHubSettings.navVoiceEnabled(context)
        NavigationRuntime.setVoiceMuted(muted)
        mutableUiState.value = mutableUiState.value.copy(
            recents = placesStore.recents(),
            favorites = placesStore.favorites(),
            savedRides = ridesStore.all(),
            home = placesStore.home(),
            units = MotoHubSettings.distanceUnits(context),
            voiceMuted = muted,
            navigationActive = NavigationRuntime.route.value != null
        )
        viewModelScope.launch {
            NavigationRuntime.route.collect { route ->
                activeDestination = NavigationRuntime.destination.value
                mutableUiState.value = mutableUiState.value.copy(
                    navigationActive = route != null
                )
            }
        }
        viewModelScope.launch {
            NavigationRuntime.progress.collect { progress -> onProgress(progress) }
        }
    }

    fun refreshUserData() {
        val context = getApplication<Application>()
        mutableUiState.value = mutableUiState.value.copy(
            recents = placesStore.recents(),
            favorites = placesStore.favorites(),
            home = placesStore.home(),
            units = MotoHubSettings.distanceUnits(context)
        )
    }

    fun onQueryChange(rawQuery: String) {
        // A pasted wall of text (not a realistic address) previously reached the text
        // field and the geocoder as-is, which could stall the UI laying out a huge
        // single-line string and then have Photon reject the oversized request with
        // an opaque HTTP 403. Capping here means the field itself never holds more
        // than a real address needs.
        val query = rawQuery.take(MAX_QUERY_LENGTH)
        mutableUiState.value = mutableUiState.value.copy(query = query, status = null)
        searchJob?.cancel()
        if (query.isBlank()) {
            mutableUiState.value = mutableUiState.value.copy(suggestions = emptyList(), isSearching = false)
            return
        }
        // A pasted "lat, lon" is an immediate destination, shown ahead of geocoding.
        val coordinate = parseCoordinates(query)
        val immediate = listOfNotNull(coordinate)
        mutableUiState.value = mutableUiState.value.copy(suggestions = immediate, isSearching = true)

        searchJob = viewModelScope.launch {
            delay(AUTOCOMPLETE_DEBOUNCE_MILLIS)
            val origin = lastKnownLocationOrNull()
            geocodingClient.search(query, near = origin).fold(
                onSuccess = { places ->
                    mutableUiState.value = mutableUiState.value.copy(
                        isSearching = false,
                        suggestions = immediate + places.filterNot { coordinate != null && it.label == coordinate.label }
                    )
                },
                onFailure = { failure ->
                    mutableUiState.value = mutableUiState.value.copy(
                        isSearching = false,
                        suggestions = immediate,
                        status = failure.toNavStatus()
                    )
                }
            )
        }
    }

    fun previewDestination(destination: NavPlace) {
        computePreview(destination, mutableUiState.value.preview?.preference ?: MotoHubSettings.routePreference(getApplication()))
    }

    fun previewSavedPlace(place: SavedPlace) {
        previewDestination(NavPlace(label = place.favoriteName ?: place.label, point = place.point))
    }

    /**
     * Moves the destination pin to a point the rider tapped on the preview map
     * and recalculates the route - lets a rider fine-tune a destination when
     * OpenStreetMap has the street but not the exact house number (a common
     * OSM data gap, not a geocoding bug: see [io.motohub.android.feature.ridedashboard.nav.PhotonGeocodingClient]).
     */
    fun adjustPreviewDestination(point: NavPoint) {
        val currentPreview = mutableUiState.value.preview ?: return
        val destination = currentPreview.destination.copy(point = point)
        // Show the exact pin immediately; reverse geocoding replaces the
        // coordinate fallback with street/house number/city when available.
        mutableUiState.value = mutableUiState.value.copy(
            preview = currentPreview.copy(
                destination = destination,
                effectiveAddress = coordinateLabel(point)
            ),
            status = null
        )
        resolvePreviewAddress(point)
        computePreview(destination, currentPreview.preference)
    }

    fun navigateHome() {
        mutableUiState.value.home?.let { previewSavedPlace(it) }
    }

    fun setPreviewPreference(preference: RoutePreference) {
        val destination = mutableUiState.value.preview?.destination ?: return
        MotoHubSettings.setRoutePreference(getApplication(), preference)
        computePreview(destination, preference)
    }

    private fun computePreview(destination: NavPlace, preference: RoutePreference) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            if (!NavigationSettingsStore.hasKey(context) && !MotoHubSettings.useDemoRoutingServer(context)) {
                mutableUiState.value = mutableUiState.value.copy(status = NavStatus.NoApiKey)
                return@launch
            }
            val origin = lastKnownLocationOrNull()
            if (origin == null) {
                mutableUiState.value = mutableUiState.value.copy(
                    status = NavStatus.Message(motoHubText("No known phone location yet; move outdoors and retry."))
                )
                return@launch
            }
            mutableUiState.value = mutableUiState.value.copy(isRouting = true, status = null)
            val routingClient: RoutingClient = routingClientFor(context)
            routingClient.route(origin, destination.point, preference).fold(
                onSuccess = { route ->
                viewModelScope.launch {
                    val enrichedRoute = m2bEnricher.enrich(route, destination)
                    val existingAddress = mutableUiState.value.preview
                        ?.takeIf { it.destination.point == destination.point }
                        ?.effectiveAddress
                    mutableUiState.value = mutableUiState.value.copy(
                        isRouting = false,
                        preview = RoutePreview(
                            destination,
                            enrichedRoute,
                            preference,
                            existingAddress ?: destination.label
                        )
                    )
                    resolvePreviewAddress(destination.point)
                }
                },
                onFailure = { failure ->
                    mutableUiState.value = mutableUiState.value.copy(
                        isRouting = false,
                        status = failure.toNavStatus()
                    )
                }
            )
        }
    }

    fun startNavigation() {
        val preview = mutableUiState.value.preview ?: return
        val context = getApplication<Application>()
        activeDestination = preview.destination
        placesStore.addRecent(preview.destination)
        NavigationRuntime.publish(preview.route, preview.destination)
        mutableUiState.value = mutableUiState.value.copy(
            preview = null,
            query = "",
            suggestions = emptyList(),
            navigationActive = true,
            arrived = null,
            recents = placesStore.recents()
        )
        ProjectionEventLog.record(
            "RIDE_NAV",
            "Navigation started to ${preview.destination.label} " +
                "(${preview.preference.name}, ${(preview.route.distanceMeters / 1_000.0)} km)."
        )
        if (MotoHubSettings.autoRecordTrips(context)) {
            TripRecordingService.startAuto(
                context,
                MotorcycleProfileStore(context).load()?.id,
                TripRecordingSource.NAVIGATION
            )
            ProjectionEventLog.record(
                "TRIPS",
                "Automatic trip recording requested for active navigation."
            )
        }
    }

    fun cancelPreview() {
        mutableUiState.value = mutableUiState.value.copy(preview = null)
    }

    fun stopNavigation() {
        NavigationRuntime.clear()
        activeDestination = null
        rerouteJob?.cancel()
        mutableUiState.value = mutableUiState.value.copy(
            navigationActive = false,
            progress = null,
            arrived = null
        )
        ProjectionEventLog.record("RIDE_NAV", "Navigation stopped by the rider.")
        stopNavigationRecording("Navigation stopped by the rider.")
    }

    fun recalculate() {
        val destination = activeDestination ?: return
        lastRerouteElapsedRealtimeMillis = Long.MIN_VALUE
        computeRerouteTo(destination.point, manual = true)
    }

    fun toggleVoiceMuted() {
        val muted = !mutableUiState.value.voiceMuted
        NavigationRuntime.setVoiceMuted(muted)
        MotoHubSettings.setNavVoiceEnabled(getApplication(), !muted)
        mutableUiState.value = mutableUiState.value.copy(voiceMuted = muted)
    }

    fun saveFavorite(place: NavPlace, name: String) {
        placesStore.saveFavorite(place, name)
        mutableUiState.value = mutableUiState.value.copy(
            favorites = placesStore.favorites(),
            home = placesStore.home()
        )
    }

    fun removeFavorite(place: SavedPlace) {
        placesStore.removeFavorite(place)
        mutableUiState.value = mutableUiState.value.copy(
            favorites = placesStore.favorites(),
            home = placesStore.home()
        )
    }

    fun removeRecent(place: SavedPlace) {
        placesStore.removeRecent(place)
        mutableUiState.value = mutableUiState.value.copy(recents = placesStore.recents())
    }

    fun removeSavedRide(ride: SavedRide) {
        ridesStore.remove(ride.id)
        mutableUiState.value = mutableUiState.value.copy(savedRides = ridesStore.all())
    }

    fun saveRide(label: String?) {
        val preview = mutableUiState.value.preview ?: return
        ridesStore.saveFromRoute(preview.route, preview.destination, label)
        mutableUiState.value = mutableUiState.value.copy(savedRides = ridesStore.all())
        ProjectionEventLog.record(
            "RIDE_NAV",
            "Saved route to ${preview.destination.label} (${(preview.route.distanceMeters / 1_000.0)} km)."
        )
    }

    fun previewSavedRide(ride: SavedRide) {
        // Show the saved geometry immediately, then refresh the time-sensitive M2b fields
        // (weather-at-arrival, golden-hour) in place - the persisted snapshot reflects
        // conditions when the ride was originally saved, which can be stale by "ride again"
        // time. Distance/curviness/fuel-estimate are not time-sensitive and are kept as-is
        // until the refresh completes.
        mutableUiState.value = mutableUiState.value.copy(
            preview = RoutePreview(
                ride.destination,
                ride.route,
                RoutePreference.FASTEST,
                ride.destination.label
            ),
            isRouting = true
        )
        viewModelScope.launch {
            val refreshed = m2bEnricher.enrich(ride.route, ride.destination)
            if (mutableUiState.value.preview?.route?.savedRideId == ride.route.savedRideId) {
                mutableUiState.value = mutableUiState.value.copy(
                    isRouting = false,
                    preview = RoutePreview(
                        ride.destination,
                        refreshed,
                        RoutePreference.FASTEST,
                        mutableUiState.value.preview?.effectiveAddress ?: ride.destination.label
                    )
                )
                resolvePreviewAddress(ride.destination.point)
            }
        }
    }

    fun dismissArrival() {
        mutableUiState.value = mutableUiState.value.copy(arrived = null)
    }

    fun clearStatus() {
        mutableUiState.value = mutableUiState.value.copy(status = null)
    }

    /** Surfaces a one-off message on the NAV screen's status banner, e.g. a T-Box connection failure encountered while launching the Ride Dashboard for turn-by-turn guidance. */
    fun showStatusMessage(message: String) {
        mutableUiState.value = mutableUiState.value.copy(status = NavStatus.Message(message))
    }

    private fun onProgress(progress: NavigationProgress?) {
        if (progress == null) {
            mutableUiState.value = mutableUiState.value.copy(progress = null)
            return
        }
        mutableUiState.value = mutableUiState.value.copy(progress = progress, navigationActive = true)
        val destination = activeDestination
        if (destination != null && progress.distanceRemainingMeters <= ARRIVAL_THRESHOLD_METERS) {
            NavigationRuntime.clear()
            rerouteJob?.cancel()
            activeDestination = null
            mutableUiState.value = mutableUiState.value.copy(
                navigationActive = false,
                progress = null,
                arrived = ArrivalInfo(destination)
            )
            ProjectionEventLog.record("RIDE_NAV", "Arrived at ${destination.label}.")
            stopNavigationRecording("Navigation arrived at ${destination.label}.")
        } else if (progress.offRoute) {
            rerouteIfNeeded()
        }
    }

    /**
     * Navigation owns its automatic recording independently from projection. A Ride Dashboard
     * or Android Auto failure must not truncate a route that remains active on the phone.
     */
    private fun stopNavigationRecording(reason: String) {
        TripRecordingService.stopAuto(getApplication<Application>(), TripRecordingSource.NAVIGATION)
        ProjectionEventLog.record("TRIPS", "Automatic navigation recording stop requested: $reason")
    }

    private fun rerouteIfNeeded() {
        val destination = activeDestination ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRerouteElapsedRealtimeMillis < MIN_REROUTE_INTERVAL_MILLIS) return
        computeRerouteTo(destination.point, manual = false)
    }

    private fun computeRerouteTo(destination: NavPoint, manual: Boolean) {
        if (rerouteJob?.isActive == true) return
        rerouteJob = viewModelScope.launch {
            val context = getApplication<Application>()
            val origin = lastKnownLocationOrNull() ?: return@launch
            lastRerouteElapsedRealtimeMillis = SystemClock.elapsedRealtime()
            val preference = MotoHubSettings.routePreference(context)
            routingClientFor(context).route(origin, destination, preference).onSuccess { route ->
                NavigationRuntime.publish(route, activeDestination)
                ProjectionEventLog.record(
                    "RIDE_NAV",
                    if (manual) "Route manually recalculated." else "Rerouted after leaving the calculated route."
                )
            }
        }
    }

    private fun resolvePreviewAddress(point: NavPoint) {
        previewAddressJob?.cancel()
        previewAddressJob = viewModelScope.launch {
            val resolved = reverseGeocode(point) ?: coordinateLabel(point)
            val current = mutableUiState.value.preview ?: return@launch
            if (current.destination.point == point) {
                mutableUiState.value = mutableUiState.value.copy(
                    preview = current.copy(effectiveAddress = resolved)
                )
            }
        }
    }

    private suspend fun reverseGeocode(point: NavPoint): String? = withContext(Dispatchers.IO) {
        runCatching {
            if (!Geocoder.isPresent()) return@runCatching null
            @Suppress("DEPRECATION")
            Geocoder(getApplication<Application>(), Locale.getDefault())
                .getFromLocation(point.latitude, point.longitude, 1)
                .orEmpty()
                .firstOrNull()
                ?.let(::formatAddress)
                ?.takeIf(String::isNotBlank)
        }.getOrNull()
    }

    private fun formatAddress(address: Address): String {
        val street = address.thoroughfare?.takeIf(String::isNotBlank)
        val number = address.subThoroughfare?.takeIf(String::isNotBlank)
        val city = (address.locality ?: address.subAdminArea ?: address.adminArea)
            ?.takeIf(String::isNotBlank)
        val streetLine = listOfNotNull(street, number).joinToString(" ")
        return listOfNotNull(streetLine.takeIf(String::isNotBlank), city)
            .joinToString(", ")
    }

    private fun coordinateLabel(point: NavPoint): String =
        String.format(Locale.US, "%.6f°, %.6f°", point.latitude, point.longitude)

    private fun Throwable.toNavStatus(): NavStatus {
        val text = message.orEmpty()
        return when {
            text.contains("API key", ignoreCase = true) -> NavStatus.NoApiKey
            text.contains("429") -> NavStatus.RateLimited
            text.contains("too long", ignoreCase = true) || text.contains("403") ->
                NavStatus.Message(motoHubText("That search is too long. Try a shorter search."))
            this is java.io.IOException -> NavStatus.NoNetwork
            text.isBlank() -> NavStatus.Message(motoHubText("Something went wrong. Try again."))
            else -> NavStatus.Message(text)
        }
    }

    private fun lastKnownLocationOrNull(): NavPoint? {
        val context = getApplication<Application>()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val locationManager = context.getSystemService(LocationManager::class.java) ?: return null
        return listOf(
            LocationManager.FUSED_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        )
            .filter { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }
            .mapNotNull { provider -> runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { NavPoint(it.latitude, it.longitude) }
    }

    private companion object {
        const val AUTOCOMPLETE_DEBOUNCE_MILLIS = 320L
        const val MIN_REROUTE_INTERVAL_MILLIS = 10_000L
        const val ARRIVAL_THRESHOLD_METERS = 30.0
        // Generous headroom over any real address (longest real-world addresses run
        // well under 100 characters) while still ruling out an accidental paste.
        const val MAX_QUERY_LENGTH = 200
    }
}
