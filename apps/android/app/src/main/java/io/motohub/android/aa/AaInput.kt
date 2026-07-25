// Injects touch input from a compatible T-Box into the Android Auto session. The protocol code was
// ported from headunit-revived (AGPLv3); no vehicle-specific assumptions are made here.
package io.motohub.android.aa

import android.os.SystemClock
import io.motohub.android.aa.proto.Input

/**
 * Sends touch events to Android Auto over the INPUT channel (declared as a touchscreen in
 * [ServiceDiscoveryResponse], sized to the AA video). Coordinates must already be in AA video space
 * (0..width, 0..height) — the caller letterbox-maps from the bike canvas first.
 */
class AaInput(
    private val transport: AapTransport,
    private val log: (String) -> Unit,
) {
    /** Normalised actions from the bike decoder. */
    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2

        const val KEY_UP = 19
        const val KEY_DOWN = 20
        const val KEY_LEFT = 21
        const val KEY_RIGHT = 22
        const val KEY_ENTER = 23
        const val KEY_BACK = 4
        const val KEY_HOME = 3
        const val KEY_ASSISTANT = 84
        const val KEY_SCROLL_WHEEL = 65536

        val SUPPORTED_KEYCODES = intArrayOf(
            KEY_SCROLL_WHEEL,
            KEY_UP,
            KEY_DOWN,
            KEY_LEFT,
            KEY_RIGHT,
            KEY_ENTER,
            KEY_BACK,
            KEY_HOME,
            KEY_ASSISTANT
        )
    }

    /**
     * @param action one of [ACTION_DOWN]/[ACTION_UP]/[ACTION_MOVE]
     * @param x,y    pointer position in AA video coordinates
     */
    private val pointers = LinkedHashMap<Int, Pair<Int, Int>>()

    @Synchronized
    fun sendTouch(action: Int, pointerId: Int, x: Int, y: Int) {
        val pointerAction = when (action) {
            ACTION_DOWN -> {
                val first = pointers.isEmpty()
                pointers[pointerId] = x to y
                if (first) Input.TouchEvent.PointerAction.TOUCH_ACTION_DOWN
                else Input.TouchEvent.PointerAction.TOUCH_ACTION_POINTER_DOWN
            }
            ACTION_MOVE -> {
                val promote = !pointers.containsKey(pointerId)
                pointers[pointerId] = x to y
                if (promote) {
                    if (pointers.size == 1) Input.TouchEvent.PointerAction.TOUCH_ACTION_DOWN
                    else Input.TouchEvent.PointerAction.TOUCH_ACTION_POINTER_DOWN
                } else {
                    Input.TouchEvent.PointerAction.TOUCH_ACTION_MOVE
                }
            }
            ACTION_UP -> {
                if (!pointers.containsKey(pointerId)) return
                pointers[pointerId] = x to y
                if (pointers.size == 1) Input.TouchEvent.PointerAction.TOUCH_ACTION_UP
                else Input.TouchEvent.PointerAction.TOUCH_ACTION_POINTER_UP
            }
            else -> return
        }
        val actionIndex = pointers.keys.indexOf(pointerId).coerceAtLeast(0)
        try {
            val touch = Input.TouchEvent.newBuilder()
            for ((id, position) in pointers) {
                touch.addPointerData(
                    Input.TouchEvent.Pointer.newBuilder()
                        .setX(position.first)
                        .setY(position.second)
                        .setPointerId(id)
                        .build()
                )
            }
            touch.setActionIndex(actionIndex).setAction(pointerAction)
            val report = Input.InputReport.newBuilder()
                .setTimestamp(SystemClock.elapsedRealtimeNanos() / 1000)
                .setTouchEvent(touch.build())
                .build()
            transport.send(AapMessage(Channel.ID_INP, Input.MsgType.EVENT_VALUE, report))
        } catch (e: Exception) {
            log("[AA] sendTouch failed: $e")
        } finally {
            if (action == ACTION_UP) pointers.remove(pointerId)
        }
    }

    fun sendTouch(action: Int, x: Int, y: Int) = sendTouch(action, 0, x, y)

    fun sendKey(keycode: Int) {
        try {
            sendKeyReport(keycode, down = true)
            sendKeyReport(keycode, down = false)
            log("[AA] input key sent: $keycode")
        } catch (e: Exception) {
            log("[AA] input key failed: $e")
        }
    }

    fun sendScroll(delta: Int) {
        if (delta == 0) return
        try {
            val relative = Input.RelativeEvent.newBuilder()
                .addData(
                    Input.RelativeEvent_Rel.newBuilder()
                        .setKeycode(KEY_SCROLL_WHEEL)
                        .setDelta(delta)
                        .build()
                )
                .build()
            val report = Input.InputReport.newBuilder()
                .setTimestamp(SystemClock.elapsedRealtimeNanos() / 1000)
                .setRelativeEvent(relative)
                .build()
            transport.send(AapMessage(Channel.ID_INP, Input.MsgType.EVENT_VALUE, report))
            log("[AA] input scroll sent: $delta")
        } catch (e: Exception) {
            log("[AA] input scroll failed: $e")
        }
    }

    private fun sendKeyReport(keycode: Int, down: Boolean) {
        val keyEvent = Input.KeyEvent.newBuilder()
            .addKeys(
                Input.Key.newBuilder()
                    .setKeycode(keycode)
                    .setDown(down)
                    .setMetastate(0)
                    .setLongpress(false)
                    .build()
            )
            .build()
        val report = Input.InputReport.newBuilder()
            .setTimestamp(SystemClock.elapsedRealtimeNanos() / 1000)
            .setKeyEvent(keyEvent)
            .build()
        transport.send(AapMessage(Channel.ID_INP, Input.MsgType.EVENT_VALUE, report))
    }
}
