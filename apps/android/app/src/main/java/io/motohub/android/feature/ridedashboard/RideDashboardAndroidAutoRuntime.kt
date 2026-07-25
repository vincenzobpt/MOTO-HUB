package io.motohub.android.feature.ridedashboard

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface RideDashboardAndroidAutoState {
    data object Idle : RideDashboardAndroidAutoState
    data object Preparing : RideDashboardAndroidAutoState
    data object ReceiverReady : RideDashboardAndroidAutoState
    data object Streaming : RideDashboardAndroidAutoState
    data class Failed(val message: String) : RideDashboardAndroidAutoState
}

object RideDashboardAndroidAutoRuntime {
    private val mutableState = MutableStateFlow<RideDashboardAndroidAutoState>(
        RideDashboardAndroidAutoState.Idle
    )
    val state: StateFlow<RideDashboardAndroidAutoState> = mutableState.asStateFlow()

    fun publish(state: RideDashboardAndroidAutoState) {
        mutableState.value = state
    }
}
