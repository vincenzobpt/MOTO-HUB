package io.motohub.android.aa

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-wide hand-off from Android Auto's active AAP session to phone controls. */
object AaInputBridge {
    @Volatile private var activeInput: AaInput? = null
    private val mutableReady = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = mutableReady.asStateFlow()

    fun install(input: AaInput) {
        activeInput = input
        mutableReady.value = true
    }

    fun clear(input: AaInput? = null) {
        if (input == null || activeInput === input) {
            activeInput = null
            mutableReady.value = false
        }
    }

    fun isReady(): Boolean = activeInput != null

    /**
     * PRO flavor: Android Auto runs in CORE, so there is no local [AaInput] to install — but the
     * shared preview UI gates its controls on [ready]. This lets the AIDL bridge mark the input
     * channel ready/not-ready to reflect CORE's session. Keys/scroll/night are routed to CORE
     * through the installed [AndroidAutoPreviewController], not through [activeInput].
     */
    fun setRemoteReady(ready: Boolean) {
        mutableReady.value = ready
    }

    fun sendKey(keycode: Int): Boolean {
        val input = activeInput ?: return false
        input.sendKey(keycode)
        return true
    }

    fun sendScroll(delta: Int): Boolean {
        val input = activeInput ?: return false
        input.sendScroll(delta)
        return true
    }
}
