package io.motohub.android.feature.trips

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface TripRecordingState {
    data object Idle : TripRecordingState

    data class Recording(
        val tripId: String,
        val source: TripRecordingSource,
        val startedAtMillis: Long,
        val speedKmh: Float,
        val distanceMeters: Double,
        val movingTimeMillis: Long,
        val elapsedTimeMillis: Long,
        val maxSpeedKmh: Float,
        val pointCount: Int,
        val accuracyMeters: Float?,
        val hasFix: Boolean
    ) : TripRecordingState

    data class Failed(val message: String) : TripRecordingState

    data class Finished(
        val savedTripId: String?,
        val distanceMeters: Double,
        val pointCount: Int
    ) : TripRecordingState
}

object TripRecordingRuntime {
    private val mutableState = MutableStateFlow<TripRecordingState>(TripRecordingState.Idle)
    private val mutableTrack = MutableStateFlow<List<TripTrackPoint>>(emptyList())
    val state: StateFlow<TripRecordingState> = mutableState.asStateFlow()
    val track: StateFlow<List<TripTrackPoint>> = mutableTrack.asStateFlow()

    fun publish(value: TripRecordingState) {
        mutableState.value = value
    }

    @Synchronized
    fun beginTrack(points: List<TripTrackPoint>) {
        mutableTrack.value = compact(points)
    }

    @Synchronized
    fun appendTrack(point: TripTrackPoint) {
        mutableTrack.value = compact(mutableTrack.value + point)
    }

    @Synchronized
    fun clearTrack() {
        mutableTrack.value = emptyList()
    }

    private fun compact(points: List<TripTrackPoint>): List<TripTrackPoint> {
        var result = points
        while (result.size > MAX_RENDERED_TRACK_POINTS) {
            val lastIndex = result.lastIndex
            result = result.filterIndexed { index, _ -> index % 2 == 0 || index == lastIndex }
        }
        return result
    }

    internal const val MAX_RENDERED_TRACK_POINTS = 4_096
}
