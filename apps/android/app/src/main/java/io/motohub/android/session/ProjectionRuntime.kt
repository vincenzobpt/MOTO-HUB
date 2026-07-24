package io.motohub.android.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

sealed interface ProjectionRuntimeState {
    data object Idle : ProjectionRuntimeState
    data object Starting : ProjectionRuntimeState
    data object Streaming : ProjectionRuntimeState
    data class Stopped(val reason: String) : ProjectionRuntimeState
    data class Failed(val message: String) : ProjectionRuntimeState
}

/** Process-local status bridge between the foreground service and the UI. */
object ProjectionRuntime {
    private val mutableState = MutableStateFlow<ProjectionRuntimeState>(ProjectionRuntimeState.Idle)
    private val active = AtomicBoolean(false)
    val state: StateFlow<ProjectionRuntimeState> = mutableState.asStateFlow()

    fun publish(state: ProjectionRuntimeState) {
        active.set(state is ProjectionRuntimeState.Starting || state is ProjectionRuntimeState.Streaming)
        mutableState.value = state
    }

    fun isActive(): Boolean = active.get()
}
