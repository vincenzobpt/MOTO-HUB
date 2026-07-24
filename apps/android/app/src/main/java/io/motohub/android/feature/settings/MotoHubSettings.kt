package io.motohub.android.feature.settings

import android.content.Context
import io.motohub.android.R
import io.motohub.android.androidauto.AndroidAutoVideoPreset
import kotlin.math.roundToInt

enum class VideoQuality(
    val label: String,
    val description: String,
    val labelRes: Int,
    val descriptionRes: Int,
    private val bitrateMultiplier: Float
) {
    SMOOTHER("Smoother", "Lower bitrate, less heat and network load.", R.string.video_quality_smoother, R.string.video_quality_smoother_description, 0.7f),
    BALANCED("Balanced", "Current MOTO-HUB quality and recommended default.", R.string.video_quality_balanced, R.string.video_quality_balanced_description, 1.0f),
    SHARPER("Sharper", "Higher bitrate for crisper maps and text.", R.string.video_quality_sharper, R.string.video_quality_sharper_description, 1.6f);

    fun bitrateFor(baseBitrate: Int): Int = (baseBitrate * bitrateMultiplier).roundToInt()
}

enum class VideoPowerMode(
    val label: String,
    val description: String,
    val labelRes: Int,
    val descriptionRes: Int,
    val frameRate: Int
) {
    AUTO("Auto", "Adapt bitrate and frame rate to phone temperature and Wi-Fi quality.", R.string.video_power_auto, R.string.video_power_auto_description, 30),
    SMOOTH("Smooth", "30 FPS with the selected video quality.", R.string.video_power_smooth, R.string.video_power_smooth_description, 30),
    BALANCED("Balanced", "Stable 24 FPS with the selected video quality.", R.string.video_power_balanced, R.string.video_power_balanced_description, 24),
    SAVER("Saver", "20 FPS for reduced heat and battery use.", R.string.video_power_saver, R.string.video_power_saver_description, 20)
}

enum class AndroidAutoResolutionMode(
    val label: String,
    val description: String,
    val labelRes: Int,
    val descriptionRes: Int,
    val preset: AndroidAutoVideoPreset?
) {
    AUTO(
        "Auto",
        "Keep MOTO-HUB automatic orientation based on the learned T-Box geometry.",
        R.string.android_auto_resolution_auto,
        R.string.android_auto_resolution_auto_description,
        null
    ),
    LANDSCAPE_SD(
        "Landscape 800 x 480",
        "Standard landscape Android Auto source.",
        R.string.android_auto_resolution_landscape_sd,
        R.string.android_auto_resolution_landscape_sd_description,
        AndroidAutoVideoPreset.LANDSCAPE_800X480
    ),
    LANDSCAPE_HD(
        "Landscape 1280 x 720",
        "Sharper HD landscape source with higher phone load.",
        R.string.android_auto_resolution_landscape_hd,
        R.string.android_auto_resolution_landscape_hd_description,
        AndroidAutoVideoPreset.LANDSCAPE_1280X720
    ),
    PORTRAIT_SD(
        "Portrait 720 x 1280",
        "Standard portrait Android Auto source.",
        R.string.android_auto_resolution_portrait_sd,
        R.string.android_auto_resolution_portrait_sd_description,
        AndroidAutoVideoPreset.PORTRAIT_720X1280
    ),
    PORTRAIT_HD(
        "Portrait 1080 x 1920",
        "Sharper HD portrait source with higher phone load.",
        R.string.android_auto_resolution_portrait_hd,
        R.string.android_auto_resolution_portrait_hd_description,
        AndroidAutoVideoPreset.PORTRAIT_1080X1920
    )
}

/**
 * Whether the motorcycle's explicit TFT safe margins are also advertised to Android Auto.
 * The negotiated T-Box VideoArea is already the projection canvas and never creates AA margins.
 */
enum class AndroidAutoAspectMatchingMode(
    val label: String,
    val description: String,
    val labelRes: Int,
    val descriptionRes: Int
) {
    AUTO(
        "Auto",
        "Use the full Android Auto source; the T-Box projection area adds no AA margins.",
        R.string.android_auto_insets_auto,
        R.string.android_auto_insets_auto_description
    ),
    MANUAL(
        "Manual",
        "Advertise the per-motorcycle TFT Safe Margins to Android Auto.",
        R.string.android_auto_insets_manual,
        R.string.android_auto_insets_manual_description
    )
}

enum class DistanceUnits(val label: String, val description: String, val labelRes: Int, val descriptionRes: Int) {
    KILOMETERS("Kilometers", "Distances and speeds in km and km/h.", R.string.distance_units_kilometers, R.string.distance_units_kilometers_description),
    MILES("Miles", "Distances and speeds in mi and mph.", R.string.distance_units_miles, R.string.distance_units_miles_description)
}

enum class RoutePreference(val label: String, val description: String, val labelRes: Int, val descriptionRes: Int) {
    FASTEST("Fastest", "Quickest route, motorways allowed.", R.string.route_preference_fastest, R.string.route_preference_fastest_description),
    SCENIC("Scenic", "Bias toward back roads and away from motorways.", R.string.route_preference_scenic, R.string.route_preference_scenic_description)
}

object MotoHubSettings {
    private const val PREFERENCES = "moto_hub_settings"
    private const val KEY_VIDEO_QUALITY = "video_quality"
    private const val KEY_VIDEO_POWER_MODE = "video_power_mode"
    private const val KEY_DISABLE_TOUCHSCREEN = "disable_touchscreen"
    private const val KEY_SEAMLESS_RESUME = "seamless_resume"
    private const val KEY_ANDROID_AUTO_RESOLUTION = "android_auto_resolution"
    private const val KEY_ANDROID_AUTO_ASPECT_MATCHING = "android_auto_aspect_matching"
    private const val KEY_AUTO_CONNECT = "auto_connect"
    private const val KEY_AUTO_RECOVERY = "auto_recovery"
    private const val KEY_AUTO_RECORD_TRIPS = "auto_record_trips"
    private const val KEY_SHOW_RECORDED_TRACK = "show_recorded_track_on_dashboard"
    private const val KEY_DISTANCE_UNITS = "distance_units"
    private const val KEY_ROUTE_PREFERENCE = "route_preference"
    private const val KEY_NAV_VOICE_ENABLED = "nav_voice_enabled"
    private const val KEY_USE_DEMO_ROUTING_SERVER = "use_demo_routing_server"
    private const val KEY_SKIPPED_UPDATE_TAG = "skipped_update_tag"
    private const val KEY_AUTO_UPDATE_CHECKS = "auto_update_checks"
    private const val KEY_LAST_AUTO_UPDATE_CHECK_AT = "last_auto_update_check_at_millis"
    private const val KEY_RIDE_DASHBOARD_STARTUP_SCREEN = "ride_dashboard_startup_screen"
    private const val KEY_SAFETY_DISCLAIMER_ACKNOWLEDGED = "safety_disclaimer_acknowledged"
    private const val KEY_VERBOSE_TBOX_LOGGING = "verbose_tbox_logging"
    private const val KEY_LOGGING_ENABLED = "logging_enabled"

    fun distanceUnits(context: Context): DistanceUnits = enumPreference(
        context = context,
        key = KEY_DISTANCE_UNITS,
        default = DistanceUnits.KILOMETERS
    )

    fun setDistanceUnits(context: Context, units: DistanceUnits) {
        preferences(context).edit().putString(KEY_DISTANCE_UNITS, units.name).apply()
    }

    fun routePreference(context: Context): RoutePreference = enumPreference(
        context = context,
        key = KEY_ROUTE_PREFERENCE,
        default = RoutePreference.FASTEST
    )

    fun setRoutePreference(context: Context, preference: RoutePreference) {
        preferences(context).edit().putString(KEY_ROUTE_PREFERENCE, preference.name).apply()
    }

    fun navVoiceEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_NAV_VOICE_ENABLED, true)

    fun setNavVoiceEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_NAV_VOICE_ENABLED, enabled).apply()
    }

    /**
     * Route with FOSSGIS's free public Valhalla demo server instead of
     * requiring a personal Stadia Maps key. Only takes effect while no key is
     * configured - see [io.motohub.android.feature.ridedashboard.nav.DemoValhallaRoutingClient].
     */
    fun useDemoRoutingServer(context: Context): Boolean =
        preferences(context).getBoolean(KEY_USE_DEMO_ROUTING_SERVER, false)

    fun setUseDemoRoutingServer(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_USE_DEMO_ROUTING_SERVER, enabled).apply()
    }

    fun videoQuality(context: Context): VideoQuality = enumPreference(
        context = context,
        key = KEY_VIDEO_QUALITY,
        default = VideoQuality.BALANCED
    )

    fun setVideoQuality(context: Context, quality: VideoQuality) {
        preferences(context).edit().putString(KEY_VIDEO_QUALITY, quality.name).apply()
    }

    fun videoPowerMode(context: Context): VideoPowerMode = enumPreference(
        context = context,
        key = KEY_VIDEO_POWER_MODE,
        default = VideoPowerMode.AUTO
    )

    fun setVideoPowerMode(context: Context, mode: VideoPowerMode) {
        preferences(context).edit().putString(KEY_VIDEO_POWER_MODE, mode.name).apply()
    }

    /** Force Android Auto to use focus/handlebar controls instead of advertising touch input. */
    fun disableTouchscreen(context: Context): Boolean =
        preferences(context).getBoolean(KEY_DISABLE_TOUCHSCREEN, false)

    fun setDisableTouchscreen(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_DISABLE_TOUCHSCREEN, enabled).apply()
    }

    fun seamlessResume(context: Context): Boolean =
        preferences(context).getBoolean(KEY_SEAMLESS_RESUME, false)

    fun setSeamlessResume(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_SEAMLESS_RESUME, enabled).apply()
    }

    fun androidAutoResolution(context: Context): AndroidAutoResolutionMode = enumPreference(
        context = context,
        key = KEY_ANDROID_AUTO_RESOLUTION,
        default = AndroidAutoResolutionMode.AUTO
    )

    fun setAndroidAutoResolution(context: Context, mode: AndroidAutoResolutionMode) {
        preferences(context).edit().putString(KEY_ANDROID_AUTO_RESOLUTION, mode.name).apply()
    }

    fun androidAutoAspectMatching(context: Context): AndroidAutoAspectMatchingMode = enumPreference(
        context = context,
        key = KEY_ANDROID_AUTO_ASPECT_MATCHING,
        default = AndroidAutoAspectMatchingMode.AUTO
    )

    fun setAndroidAutoAspectMatching(context: Context, mode: AndroidAutoAspectMatchingMode) {
        preferences(context).edit().putString(KEY_ANDROID_AUTO_ASPECT_MATCHING, mode.name).apply()
    }

    fun autoConnect(context: Context): Boolean = preferences(context).getBoolean(KEY_AUTO_CONNECT, false)

    fun setAutoConnect(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_AUTO_CONNECT, enabled).apply()
    }

    fun autoRecovery(context: Context): Boolean = preferences(context).getBoolean(KEY_AUTO_RECOVERY, false)

    fun setAutoRecovery(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_AUTO_RECOVERY, enabled).apply()
    }

    /**
     * Extra T-Box protocol diagnostics: full CLIENT_INFO JSON, every candidate
     * profile's CLIENT_INFO score (not just the winner), hex dumps of
     * unrecognized PXC/media-control commands, and Wi-Fi link quality at
     * connect time. Off by default - this is meaningfully more verbose and
     * more expensive to format than the app's normal event log, and only
     * useful when a rider is actively helping diagnose a connection problem.
     */
    fun verboseTBoxLogging(context: Context): Boolean =
        preferences(context).getBoolean(KEY_VERBOSE_TBOX_LOGGING, false)

    fun setVerboseTBoxLogging(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_VERBOSE_TBOX_LOGGING, enabled).apply()
    }

    /**
     * Master switch for [io.motohub.android.session.ProjectionEventLog]: when false, nothing
     * is recorded to Logcat, memory, or the diagnostic log file at all - not just the verbose
     * extras. On by default, since the base (non-verbose) log is what most troubleshooting in
     * this app has relied on and is not the heavy part; this exists for riders who want to
     * turn logging off entirely rather than just dial back its verbosity.
     */
    fun loggingEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_LOGGING_ENABLED, true)

    fun setLoggingEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_LOGGING_ENABLED, enabled).apply()
    }

    fun autoRecordTrips(context: Context): Boolean =
        preferences(context).getBoolean(KEY_AUTO_RECORD_TRIPS, true)

    fun setAutoRecordTrips(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_AUTO_RECORD_TRIPS, enabled).apply()
    }

    fun showRecordedTrackOnDashboard(context: Context): Boolean =
        preferences(context).getBoolean(KEY_SHOW_RECORDED_TRACK, true)

    fun setShowRecordedTrackOnDashboard(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_SHOW_RECORDED_TRACK, enabled).apply()
    }

    /** The GitHub release tag the rider chose to skip, or null if none/cleared. */
    fun skippedUpdateTag(context: Context): String? = preferences(context).getString(KEY_SKIPPED_UPDATE_TAG, null)

    fun setSkippedUpdateTag(context: Context, tagName: String?) {
        preferences(context).edit().putString(KEY_SKIPPED_UPDATE_TAG, tagName).apply()
    }

    /** Check GitHub releases shortly after launch, at most once every 24 hours. */
    fun autoUpdateChecks(context: Context): Boolean =
        preferences(context).getBoolean(KEY_AUTO_UPDATE_CHECKS, true)

    fun setAutoUpdateChecks(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_AUTO_UPDATE_CHECKS, enabled).apply()
    }

    /** Epoch millis of the last *automatic* update check; 0 if one has never run. */
    fun lastAutoUpdateCheckAtMillis(context: Context): Long =
        preferences(context).getLong(KEY_LAST_AUTO_UPDATE_CHECK_AT, 0L)

    fun setLastAutoUpdateCheckAtMillis(context: Context, epochMillis: Long) {
        preferences(context).edit().putLong(KEY_LAST_AUTO_UPDATE_CHECK_AT, epochMillis).apply()
    }

    /** Shows the motorcycle-photo puzzle and MOTO-HUB logo before the dashboard. Off by default. */
    fun rideDashboardStartupScreen(context: Context): Boolean =
        preferences(context).getBoolean(KEY_RIDE_DASHBOARD_STARTUP_SCREEN, false)

    fun setRideDashboardStartupScreen(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_RIDE_DASHBOARD_STARTUP_SCREEN, enabled).apply()
    }

    /** True after the rider chose not to see the startup safety warning again. */
    fun safetyDisclaimerAcknowledged(context: Context): Boolean =
        preferences(context).getBoolean(KEY_SAFETY_DISCLAIMER_ACKNOWLEDGED, false)

    fun setSafetyDisclaimerAcknowledged(context: Context, acknowledged: Boolean) {
        preferences(context).edit().putBoolean(KEY_SAFETY_DISCLAIMER_ACKNOWLEDGED, acknowledged).apply()
    }

    private inline fun <reified T : Enum<T>> enumPreference(
        context: Context,
        key: String,
        default: T
    ): T {
        val stored = preferences(context).getString(key, default.name)
        return enumValues<T>().firstOrNull { it.name == stored } ?: default
    }

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE
    )
}
