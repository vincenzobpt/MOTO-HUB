package io.motohub.android.androidauto

import android.content.Context
import android.content.SharedPreferences
import io.motohub.android.session.MotorcycleProfile

/** Physical TFT pixels reserved by motorcycle-owned UI around the projection area. */
data class TBoxScreenMargins(
    val top: Int = 0,
    val bottom: Int = 0,
    val left: Int = 0,
    val right: Int = 0
) {
    init {
        require(top >= 0 && bottom >= 0 && left >= 0 && right >= 0) {
            "Screen margins cannot be negative"
        }
        require(top <= MAX && bottom <= MAX && left <= MAX && right <= MAX) {
            "Screen margins exceed the supported limit"
        }
    }

    fun inset(geometry: DisplayGeometry): DisplayGeometry = DisplayGeometry(
        width = (geometry.width - left - right).coerceAtLeast(1),
        height = (geometry.height - top - bottom).coerceAtLeast(1)
    )

    companion object {
        const val MAX = 200
        val NONE = TBoxScreenMargins()
    }
}

/** Stores margins per motorcycle so one bike's panel furniture never affects another's. */
class TBoxScreenMarginsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun load(
        profile: MotorcycleProfile,
        defaults: TBoxScreenMargins = TBoxScreenMargins.NONE
    ): TBoxScreenMargins = TBoxScreenMargins(
        top = preferences.getInt(key(profile.ssid, "top"), defaults.top),
        bottom = preferences.getInt(key(profile.ssid, "bottom"), defaults.bottom),
        left = preferences.getInt(key(profile.ssid, "left"), defaults.left),
        right = preferences.getInt(key(profile.ssid, "right"), defaults.right)
    )

    fun save(profile: MotorcycleProfile, margins: TBoxScreenMargins) {
        preferences.edit()
            .putInt(key(profile.ssid, "top"), margins.top)
            .putInt(key(profile.ssid, "bottom"), margins.bottom)
            .putInt(key(profile.ssid, "left"), margins.left)
            .putInt(key(profile.ssid, "right"), margins.right)
            .apply()
    }

    fun reset(profile: MotorcycleProfile) {
        preferences.edit()
            .remove(key(profile.ssid, "top"))
            .remove(key(profile.ssid, "bottom"))
            .remove(key(profile.ssid, "left"))
            .remove(key(profile.ssid, "right"))
            .apply()
    }

    /**
     * Notifies [listener] whenever [save] or [reset] changes any margin for any SSID -
     * callers should use [belongsToMotorcycle] to filter for the SSID they care about. Lets
     * an active Android Auto/Ride Dashboard session apply a margin change picked in
     * [io.motohub.android.feature.garage.MotorcycleDetailsScreen] without restarting.
     */
    fun addListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun removeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    /** True if the preference [key] reported by [addListener] belongs to [ssid]. */
    fun belongsToMotorcycle(key: String?, ssid: String): Boolean = key?.startsWith("$ssid:") == true

    private fun key(ssid: String, edge: String): String = "$ssid:$edge"

    private companion object {
        const val PREFERENCES_NAME = "tbox_screen_margins"
    }
}
