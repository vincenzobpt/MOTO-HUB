package io.motohub.android.feature.ridedashboard.nav

import android.content.Context
import android.content.res.Configuration
import java.util.Calendar

/**
 * Map (Android Auto) day/night theme. Switches between light and dark map styles
 * on the dash instantly via [NightModeEvent]. Mirrors OpenCfMoto's [MapTheme].
 */
enum class MapTheme(val label: String) {
    AUTO("Auto (day/night)"),
    DAY("Day (light)"),
    NIGHT("Night (dark)")
}

object NightPrefs {
    private const val PREFS = "motohub_night"
    private const val KEY = "map_theme"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun theme(ctx: Context): MapTheme {
        val name = prefs(ctx).getString(KEY, MapTheme.AUTO.name)
        return runCatching { MapTheme.valueOf(name!!) }.getOrDefault(MapTheme.AUTO)
    }

    fun setTheme(ctx: Context, theme: MapTheme) {
        prefs(ctx).edit().putString(KEY, theme.name).apply()
    }

    /** Cycle Auto → Day → Night → Auto for one-tap toggle. */
    fun cycle(ctx: Context): MapTheme {
        val next = when (theme(ctx)) {
            MapTheme.AUTO -> MapTheme.DAY
            MapTheme.DAY -> MapTheme.NIGHT
            MapTheme.NIGHT -> MapTheme.AUTO
        }
        setTheme(ctx, next)
        return next
    }

    /** Whether the head unit should report night mode to Android Auto right now. */
    fun isNightNow(ctx: Context): Boolean = when (theme(ctx)) {
        MapTheme.DAY -> false
        MapTheme.NIGHT -> true
        MapTheme.AUTO -> phoneInNightMode(ctx) || clockSaysNight()
    }

    private fun phoneInNightMode(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** Night between 19:00 and 06:59 local time. */
    private fun clockSaysNight(): Boolean {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return h < 7 || h >= 19
    }
}
