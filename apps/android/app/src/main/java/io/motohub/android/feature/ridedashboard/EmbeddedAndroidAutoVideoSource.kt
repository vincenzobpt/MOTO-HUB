package io.motohub.android.feature.ridedashboard

import android.graphics.Canvas
import android.graphics.RectF
import io.motohub.android.androidauto.AndroidAutoDisplayMode
import io.motohub.android.androidauto.TBoxScreenMargins

/**
 * Shared-code contract for Ride Dashboard's embedded Android Auto map panel. The real
 * implementation (`aa.EmbeddedAndroidAutoSource`, wired to the AGPL AAP receiver) lives in Core
 * only — this interface is what `RideDashboardSessionService`/`RideDashboardRenderer` (compiled
 * for both flavors) depend on instead, via `createEmbeddedAndroidAutoSource(...)`.
 */
interface EmbeddedAndroidAutoVideoSource {
    val width: Int
    val height: Int
    val displayMode: AndroidAutoDisplayMode

    fun start(): Boolean
    fun stop()
    fun draw(canvas: Canvas, destination: RectF): Boolean
    fun sendSourceTouch(action: Int, pointerId: Int, x: Int, y: Int)
    fun refreshMargins(margins: TBoxScreenMargins)
}
