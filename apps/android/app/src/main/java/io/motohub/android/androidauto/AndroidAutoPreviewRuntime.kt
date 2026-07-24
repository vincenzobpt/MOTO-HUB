package io.motohub.android.androidauto

import android.view.Surface
import java.util.concurrent.atomic.AtomicReference

interface AndroidAutoPreviewController {
    fun attachPreview(surface: Surface, width: Int, height: Int)
    fun detachPreview()
    fun sendPreviewTouch(action: Int, pointerId: Int, x: Int, y: Int)
    fun sendPreviewKey(keycode: Int): Boolean
    fun sendPreviewScroll(delta: Int): Boolean
    fun setPreviewNightMode(isNight: Boolean): Boolean
}

object AndroidAutoPreviewRuntime {
    private val controller = AtomicReference<AndroidAutoPreviewController?>()
    private val attachedSurface = AtomicReference<Surface?>()
    @Volatile private var attachedWidth = 0
    @Volatile private var attachedHeight = 0

    fun install(value: AndroidAutoPreviewController) {
        controller.set(value)
        attachedSurface.get()?.let { surface ->
            value.attachPreview(surface, attachedWidth, attachedHeight)
        }
    }

    fun clear(value: AndroidAutoPreviewController) {
        if (controller.compareAndSet(value, null)) {
            // Keep an attached phone surface across a service restart; the next controller can
            // reattach it without waiting for SurfaceView to emit another size change.
        }
    }

    fun attach(surface: Surface, width: Int, height: Int) {
        attachedSurface.set(surface)
        attachedWidth = width
        attachedHeight = height
        controller.get()?.attachPreview(surface, width, height)
    }

    fun detach(surface: Surface) {
        if (attachedSurface.compareAndSet(surface, null)) {
            attachedWidth = 0
            attachedHeight = 0
            controller.get()?.detachPreview()
        }
    }

    fun sendTouch(action: Int, pointerId: Int, x: Int, y: Int) {
        controller.get()?.sendPreviewTouch(action, pointerId, x, y)
    }

    fun sendKey(keycode: Int): Boolean = controller.get()?.sendPreviewKey(keycode) == true

    fun sendScroll(delta: Int): Boolean = controller.get()?.sendPreviewScroll(delta) == true

    fun setNightMode(isNight: Boolean): Boolean =
        controller.get()?.setPreviewNightMode(isNight) == true
}
