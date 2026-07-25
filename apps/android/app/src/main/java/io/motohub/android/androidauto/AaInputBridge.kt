package io.motohub.android.androidauto

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The AGPL-derived AAP key/scroll sender (`aa.AaInput`, Core-only) implements this so
 * [AaInputBridge] — shared, flavor-agnostic — never needs to import anything from the `aa`
 * package.
 */
interface AaInputSink {
    fun sendKey(keycode: Int)
    fun sendScroll(delta: Int)
}

/** Process-wide hand-off from Android Auto's active AAP session to phone controls. */
object AaInputBridge {
    @Volatile private var activeInput: AaInputSink? = null
    private val mutableReady = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = mutableReady.asStateFlow()

    fun install(input: AaInputSink) {
        activeInput = input
        mutableReady.value = true
    }

    fun clear(input: AaInputSink? = null) {
        if (input == null || activeInput === input) {
            activeInput = null
            mutableReady.value = false
        }
    }

    fun isReady(): Boolean = activeInput != null

    /**
     * PRO flavor: Android Auto runs in CORE, so there is no local [AaInputSink] to install — but
     * the shared preview UI gates its controls on [ready]. This lets the AIDL bridge mark the
     * input channel ready/not-ready to reflect CORE's session. Keys/scroll/night are routed to
     * CORE through the installed [AndroidAutoPreviewController], not through [activeInput].
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
