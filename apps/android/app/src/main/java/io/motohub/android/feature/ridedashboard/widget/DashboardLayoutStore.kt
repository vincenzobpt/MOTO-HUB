package io.motohub.android.feature.ridedashboard.widget

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists the widget layout configuration per motorcycle (by SSID).
 *
 * Storage: private SharedPreferences, one entry per SSID as a
 * comma-separated pair "leftWidgetId,rightWidgetId".
 *
 * No encryption needed — layout preferences are not sensitive.
 */
class DashboardLayoutStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(ssid: String): DashboardLayoutConfig {
        val raw = prefs.getString(key(ssid), null) ?: return DashboardLayoutConfig.DEFAULT
        val parts = raw.split(',', limit = 2)
        if (parts.size != 2) return DashboardLayoutConfig.DEFAULT
        val left = parts[0].trim()
        val right = parts[1].trim()
        // Validate that both IDs are known — fall back to defaults if not.
        if (DashboardWidgetRegistry.forId(left) == null || DashboardWidgetRegistry.forId(right) == null) {
            return DashboardLayoutConfig.DEFAULT
        }
        return DashboardLayoutConfig(leftWidgetId = left, rightWidgetId = right)
    }

    fun save(ssid: String, config: DashboardLayoutConfig) {
        prefs.edit()
            .putString(key(ssid), "${config.leftWidgetId},${config.rightWidgetId}")
            .apply()
    }

    fun reset(ssid: String) {
        prefs.edit().remove(key(ssid)).apply()
    }

    /**
     * Notifies [listener] whenever [save] or [reset] changes the layout for any SSID -
     * callers should filter on [keyFor] themselves. Lets an active dashboard session
     * apply a layout change picked in [DashboardWidgetPickerScreen] without restarting.
     */
    fun addListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun removeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    /** The exact preference key [addListener] callbacks report for a given [ssid]. */
    fun keyFor(ssid: String): String = key(ssid)

    private fun key(ssid: String) = "dashboard_layout_$ssid"

    companion object {
        private const val PREFS_NAME = "motohub_dashboard_layout"
    }
}
