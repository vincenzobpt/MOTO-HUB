package io.motohub.android.feature.ridedashboard

enum class RideDashboardLayoutPhase(
    val leftPanelVisible: Boolean,
    val rightPanelVisible: Boolean,
    val label: String
) {
    FULL_DASHBOARD(true, true, "full dashboard"),
    RIGHT_PANEL_HIDDEN(true, false, "right panel hidden"),
    BOTH_PANELS_HIDDEN(false, false, "both side panels hidden"),
    RIGHT_PANEL_RESTORED(false, true, "right panel restored")
}

data class RideDashboardLayoutSnapshot(
    val phase: RideDashboardLayoutPhase,
    val fullscreenMap: Boolean
) {
    val leftPanelVisible: Boolean
        get() = !fullscreenMap && phase.leftPanelVisible

    val rightPanelVisible: Boolean
        get() = !fullscreenMap && phase.rightPanelVisible

    val chromeVisible: Boolean
        get() = !fullscreenMap

    val label: String
        get() = if (fullscreenMap) "fullscreen map" else phase.label
}

/** Owns the deterministic handlebar-driven layout state independently from rendering. */
class RideDashboardLayoutController {
    private var phase = RideDashboardLayoutPhase.FULL_DASHBOARD
    private var fullscreenMap = false

    @Synchronized
    fun onUp(): RideDashboardLayoutSnapshot {
        if (!fullscreenMap) {
            phase = when (phase) {
                RideDashboardLayoutPhase.FULL_DASHBOARD -> RideDashboardLayoutPhase.RIGHT_PANEL_HIDDEN
                RideDashboardLayoutPhase.RIGHT_PANEL_HIDDEN -> RideDashboardLayoutPhase.BOTH_PANELS_HIDDEN
                RideDashboardLayoutPhase.BOTH_PANELS_HIDDEN -> RideDashboardLayoutPhase.RIGHT_PANEL_RESTORED
                RideDashboardLayoutPhase.RIGHT_PANEL_RESTORED -> RideDashboardLayoutPhase.FULL_DASHBOARD
            }
        }
        return snapshotUnsafe()
    }

    @Synchronized
    fun onDown(): RideDashboardLayoutSnapshot {
        fullscreenMap = !fullscreenMap
        return snapshotUnsafe()
    }

    @Synchronized
    fun snapshot(): RideDashboardLayoutSnapshot = snapshotUnsafe()

    private fun snapshotUnsafe() = RideDashboardLayoutSnapshot(
        phase = phase,
        fullscreenMap = fullscreenMap
    )
}
