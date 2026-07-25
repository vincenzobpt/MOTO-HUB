package io.motohub.android.feature.ridedashboard

import io.motohub.android.feature.controls.HandlebarGesture

/** Exposes the active dashboard layout controls to the phone UI. */
object RideDashboardControlBridge {
    @Volatile
    private var gestureHandler: ((HandlebarGesture) -> Boolean)? = null

    @Synchronized
    fun install(handler: (HandlebarGesture) -> Boolean) {
        gestureHandler = handler
    }

    @Synchronized
    fun clear(handler: (HandlebarGesture) -> Boolean) {
        if (gestureHandler === handler) gestureHandler = null
    }

    fun isReady(): Boolean = gestureHandler != null

    fun cyclePanels(): Boolean = gestureHandler?.invoke(HandlebarGesture.VOLUME_UP) == true

    fun toggleFullscreenMap(): Boolean = gestureHandler?.invoke(HandlebarGesture.VOLUME_DOWN) == true
}
