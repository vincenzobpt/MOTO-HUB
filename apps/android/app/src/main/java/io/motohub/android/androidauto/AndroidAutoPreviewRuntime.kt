package io.motohub.android.androidauto

import android.view.Surface
import java.util.concurrent.atomic.AtomicReference

interface AndroidAutoPreviewController {
    fun attachPreview(surface: Surface, width: Int, height: Int)
    fun detachPreview()
    fun sendPreviewTouch(action: Int, x: Int, y: Int)
}

object AndroidAutoPreviewRuntime {
    private val controller = AtomicReference<AndroidAutoPreviewController?>()
    private val attachedSurface = AtomicReference<Surface?>()

    fun install(value: AndroidAutoPreviewController) {
        controller.set(value)
    }

    fun clear(value: AndroidAutoPreviewController) {
        if (controller.compareAndSet(value, null)) {
            attachedSurface.set(null)
        }
    }

    fun attach(surface: Surface, width: Int, height: Int) {
        attachedSurface.set(surface)
        controller.get()?.attachPreview(surface, width, height)
    }

    fun detach(surface: Surface) {
        if (attachedSurface.compareAndSet(surface, null)) {
            controller.get()?.detachPreview()
        }
    }

    fun sendTouch(action: Int, x: Int, y: Int) {
        controller.get()?.sendPreviewTouch(action, x, y)
    }
}
