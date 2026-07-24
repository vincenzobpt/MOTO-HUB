package io.motohub.android.externaldisplay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether a USB AOA accessory (external head unit) is currently attached.
 *
 * Android only resolves [android.hardware.usb.UsbManager.ACTION_USB_ACCESSORY_ATTACHED] to an
 * activity launch/onNewIntent - it is never sent as a plain broadcast - so this can't be kept
 * fresh with a registered BroadcastReceiver alone. MainActivity publishes here from onCreate,
 * onNewIntent, and its ACTION_USB_ACCESSORY_DETACHED receiver.
 */
object AoaAccessoryRuntime {
    private val mutableConnected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = mutableConnected.asStateFlow()

    fun publish(connected: Boolean) {
        mutableConnected.value = connected
    }
}
