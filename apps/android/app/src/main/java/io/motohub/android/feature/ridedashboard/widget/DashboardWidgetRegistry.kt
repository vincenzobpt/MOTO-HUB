package io.motohub.android.feature.ridedashboard.widget

/**
 * Canonical widget IDs used across the dashboard layout system.
 * Every ID here must have a matching entry in [DashboardWidgetRegistry].
 */
object DashboardWidgetIDs {
    const val SPEED_GAUGE = "widget_speed_gauge"
    const val TRIP_METRICS = "widget_trip_metrics"
    const val COMPASS = "widget_compass"
    const val ALTITUDE = "widget_altitude"
    const val SATELLITES = "widget_satellites"
    const val BATTERY = "widget_battery"
    const val WEATHER = "widget_weather"
    const val NAVIGATION = "widget_navigation"
    const val NOW_PLAYING = "widget_now_playing"
}

/**
 * Central registry of all available [DashboardWidget]s.
 *
 * Widgets are created once and reused across all sessions because they
 * carry no mutable per-frame state (all drawing data comes from the
 * [RideTelemetrySnapshot] parameter).
 */
object DashboardWidgetRegistry {

    private val widgets: Map<String, DashboardWidget> by lazy {
        mapOf(
            DashboardWidgetIDs.SPEED_GAUGE to SpeedGaugeWidget(),
            DashboardWidgetIDs.TRIP_METRICS to TripMetricsWidget(),
            DashboardWidgetIDs.COMPASS to CompassWidget(),
            DashboardWidgetIDs.ALTITUDE to AltitudeWidget(),
            DashboardWidgetIDs.SATELLITES to SatelliteWidget(),
            DashboardWidgetIDs.BATTERY to BatteryWidget(),
            DashboardWidgetIDs.WEATHER to WeatherWidget(),
            DashboardWidgetIDs.NAVIGATION to NavigationWidget(),
            DashboardWidgetIDs.NOW_PLAYING to NowPlayingWidget(),
        )
    }

    /** All available widgets (order matches the customisation screen display order). */
    fun all(): List<DashboardWidget> = widgets.values.toList()

    /** Look up a widget by its [id]. Returns null for unknown IDs. */
    fun forId(id: String): DashboardWidget? = widgets[id]

    /** The default layout — SpeedGauge left, TripMetrics right. */
    fun defaultLayout(): DashboardLayoutConfig = DashboardLayoutConfig.DEFAULT
}
