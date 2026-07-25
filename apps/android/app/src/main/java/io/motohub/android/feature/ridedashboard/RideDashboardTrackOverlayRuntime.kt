package io.motohub.android.feature.ridedashboard

import io.motohub.android.feature.trips.TripDetails
import io.motohub.android.feature.trips.TripTrackPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

object RideDashboardTrackOverlayRuntime {
    private val enabled = AtomicBoolean(true)
    private val mutableLoadedTrip = MutableStateFlow<LoadedTrip?>(null)
    private val mutableFollowLiveGps = MutableStateFlow(false)

    data class LoadedTrip(
        val id: String,
        val label: String,
        val points: List<TripTrackPoint>
    )

    /** A saved trip explicitly loaded into the OSM dashboard map. */
    val loadedTrip: StateFlow<LoadedTrip?> = mutableLoadedTrip.asStateFlow()
    /** Whether the map camera should follow live GPS while keeping the trip overlay. */
    val followLiveGps: StateFlow<Boolean> = mutableFollowLiveGps.asStateFlow()

    fun isEnabled(): Boolean = enabled.get()

    fun setEnabled(value: Boolean) {
        enabled.set(value)
    }

    @Synchronized
    fun loadTrip(details: TripDetails) {
        val points = compact(details.points)
        if (points.size < 2) {
            mutableLoadedTrip.value = null
            mutableFollowLiveGps.value = true
            return
        }
        mutableFollowLiveGps.value = false
        mutableLoadedTrip.value = LoadedTrip(
            id = details.summary.id,
            label = details.summary.name ?: details.summary.source.label,
            points = points
        )
    }

    @Synchronized
    fun clearLoadedTrip() {
        mutableLoadedTrip.value = null
        mutableFollowLiveGps.value = true
    }

    /** Keep the selected trip visible, but hand camera positioning back to live GPS. */
    @Synchronized
    fun restoreLiveGps() {
        if (mutableLoadedTrip.value != null) mutableFollowLiveGps.value = true
    }

    private fun compact(points: List<TripTrackPoint>): List<TripTrackPoint> {
        var result = points
        while (result.size > MAX_RENDERED_POINTS) {
            val lastIndex = result.lastIndex
            result = result.filterIndexed { index, _ -> index % 2 == 0 || index == lastIndex }
        }
        return result
    }

    private const val MAX_RENDERED_POINTS = 4_096
}
