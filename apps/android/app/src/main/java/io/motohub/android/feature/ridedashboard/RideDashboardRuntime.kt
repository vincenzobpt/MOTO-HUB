package io.motohub.android.feature.ridedashboard

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface RideDashboardRuntimeState {
    data object Idle : RideDashboardRuntimeState
    data object Starting : RideDashboardRuntimeState
    data object Streaming : RideDashboardRuntimeState
    data class Stopped(val reason: String) : RideDashboardRuntimeState
    data class Failed(val message: String) : RideDashboardRuntimeState
}

/** Process-local status bridge between the dashboard service and the phone UI. */
object RideDashboardRuntime {
    private val mutableState = MutableStateFlow<RideDashboardRuntimeState>(
        RideDashboardRuntimeState.Idle
    )
    val state: StateFlow<RideDashboardRuntimeState> = mutableState.asStateFlow()

    fun publish(state: RideDashboardRuntimeState) {
        mutableState.value = state
    }
}
