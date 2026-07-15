package io.motohub.android.androidauto

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface AndroidAutoRuntimeState {
    data object Idle : AndroidAutoRuntimeState
    data object Preparing : AndroidAutoRuntimeState
    data object ReceiverReady : AndroidAutoRuntimeState
    data object Streaming : AndroidAutoRuntimeState
    data class Stopped(val reason: String) : AndroidAutoRuntimeState
    data class Failed(val message: String) : AndroidAutoRuntimeState
}

object AndroidAutoRuntime {
    private val mutableState = MutableStateFlow<AndroidAutoRuntimeState>(AndroidAutoRuntimeState.Idle)
    val state: StateFlow<AndroidAutoRuntimeState> = mutableState.asStateFlow()

    fun publish(state: AndroidAutoRuntimeState) {
        mutableState.value = state
    }

    fun isActive(): Boolean = when (mutableState.value) {
        AndroidAutoRuntimeState.Preparing,
        AndroidAutoRuntimeState.ReceiverReady,
        AndroidAutoRuntimeState.Streaming -> true
        else -> false
    }
}
