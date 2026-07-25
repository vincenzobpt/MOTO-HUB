package io.motohub.android.feature.ridedashboard.nav

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Publishes the active navigation route for the dashboard render thread to
 * read, mirroring the object+StateFlow pattern already used by
 * [io.motohub.android.feature.trips.TripRecordingRuntime].
 */
object NavigationRuntime {
    private val mutableRoute = MutableStateFlow<NavRoute?>(null)
    val route: StateFlow<NavRoute?> = mutableRoute.asStateFlow()

    private val mutableDestination = MutableStateFlow<NavPlace?>(null)
    val destination: StateFlow<NavPlace?> = mutableDestination.asStateFlow()

    private val mutableProgress = MutableStateFlow<NavigationProgress?>(null)
    val progress: StateFlow<NavigationProgress?> = mutableProgress.asStateFlow()

    private val mutableVoiceMuted = MutableStateFlow(false)
    val voiceMuted: StateFlow<Boolean> = mutableVoiceMuted.asStateFlow()

    fun publish(route: NavRoute?, destination: NavPlace? = null) {
        mutableDestination.value = destination
        mutableRoute.value = route
        mutableProgress.value = null
    }

    fun publishProgress(progress: NavigationProgress?) {
        mutableProgress.value = progress
    }

    fun setVoiceMuted(muted: Boolean) {
        mutableVoiceMuted.value = muted
    }

    fun clear() {
        mutableDestination.value = null
        mutableRoute.value = null
        mutableProgress.value = null
    }
}
