package io.motohub.android.feature.ridedashboard.widget

/**
 * Describes which widget occupies each panel slot in the Ride Dashboard.
 *
 * @property leftWidgetId  widget id for the left panel (default = SpeedGauge)
 * @property rightWidgetId widget id for the right panel (default = TripMetrics)
 */
data class DashboardLayoutConfig(
    val leftWidgetId: String = DashboardWidgetIDs.SPEED_GAUGE,
    val rightWidgetId: String = DashboardWidgetIDs.TRIP_METRICS
) {
    companion object {
        val DEFAULT = DashboardLayoutConfig()
    }
}
