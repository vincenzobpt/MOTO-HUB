// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
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

/**
 * Every coded source the Android Auto protocol defines, plus AUTO.
 *
 * The list is deliberately complete rather than curated: MOTO-HUB is the head unit, so whatever
 * the protocol can ask the phone for is a resolution some dashboard out there could want, and a
 * rider who knows their panel is a better judge of it than a table of the dashes we have seen.
 * What the app does NOT do is choose the unvalidated ones by itself - see
 * [AndroidAutoVideoPreset.autoSelectable].
 */
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
    LANDSCAPE_FHD(
        "Landscape 1920 x 1080",
        "Full HD landscape source. Not validated on any known dashboard.",
        R.string.android_auto_resolution_landscape_fhd,
        R.string.android_auto_resolution_landscape_fhd_description,
        AndroidAutoVideoPreset.LANDSCAPE_1920X1080
    ),
    LANDSCAPE_QHD(
        "Landscape 2560 x 1440",
        "Quad HD landscape source. Heavy on the phone and not validated on any known dashboard.",
        R.string.android_auto_resolution_landscape_qhd,
        R.string.android_auto_resolution_landscape_qhd_description,
        AndroidAutoVideoPreset.LANDSCAPE_2560X1440
    ),
    LANDSCAPE_UHD(
        "Landscape 3840 x 2160",
        "4K landscape source. The phone encodes and decodes 4K at once; expect heat and drops.",
        R.string.android_auto_resolution_landscape_uhd,
        R.string.android_auto_resolution_landscape_uhd_description,
        AndroidAutoVideoPreset.LANDSCAPE_3840X2160
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
    ),
    PORTRAIT_QHD(
        "Portrait 1440 x 2560",
        "Quad HD portrait source. Heavy on the phone and not validated on any known dashboard.",
        R.string.android_auto_resolution_portrait_qhd,
        R.string.android_auto_resolution_portrait_qhd_description,
        AndroidAutoVideoPreset.PORTRAIT_1440X2560
    ),
    PORTRAIT_UHD(
        "Portrait 2160 x 3840",
        "4K portrait source. The phone encodes and decodes 4K at once; expect heat and drops.",
        R.string.android_auto_resolution_portrait_uhd,
        R.string.android_auto_resolution_portrait_uhd_description,
        AndroidAutoVideoPreset.PORTRAIT_2160X3840
    );

    /** Whether this is a source no dashboard has been seen to accept. */
    val experimental: Boolean get() = preset?.autoSelectable == false

    /** True for the landscape sources; false for AUTO and for the portrait ones. */
    val landscape: Boolean
        get() = preset != null && preset.source.width >= preset.source.height
}

/**
 * How big Android Auto draws itself on the dashboard.
 *
 * Resolution is pixels; density is size. Android Auto lays its UI out in dp, and
 * dp = px * 160 / dpi - so raising the dpi makes every button, label and map icon bigger and
 * fits less on screen, and lowering it does the opposite. The same 800x480 panel wants a
 * different answer on a 5" TFT than on a 10" one, and until now every rider got whichever single
 * value the resolution preset happened to carry.
 *
 * [AUTO] keeps exactly that value, so the default behaviour is unchanged.
 */
enum class AndroidAutoDensityMode(
    val label: String,
    val description: String,
    val labelRes: Int,
    val descriptionRes: Int,
    /** The dpi to advertise, or null to keep the selected resolution's own density. */
    val dpi: Int?
) {
    AUTO(
        "Auto",
        "Use the density that comes with the selected resolution.",
        R.string.android_auto_density_auto,
        R.string.android_auto_density_auto_description,
        null
    ),
    DPI_120(
        "120 dpi",
        "Smallest interface, most map on screen. Hard to read on a small TFT.",
        R.string.android_auto_density_120,
        R.string.android_auto_density_120_description,
        120
    ),
    DPI_160(
        "160 dpi",
        "Small interface. The density the landscape sources use by default.",
        R.string.android_auto_density_160,
        R.string.android_auto_density_160_description,
        160
    ),
    DPI_213(
        "213 dpi",
        "Between small and standard. Useful when 160 is too tight and 240 too coarse.",
        R.string.android_auto_density_213,
        R.string.android_auto_density_213_description,
        213
    ),
    DPI_240(
        "240 dpi",
        "Standard interface. The density the portrait sources use by default.",
        R.string.android_auto_density_240,
        R.string.android_auto_density_240_description,
        240
    ),
    DPI_320(
        "320 dpi",
        "Large interface, easier to read and to hit with gloves. Less map on screen.",
        R.string.android_auto_density_320,
        R.string.android_auto_density_320_description,
        320
    ),
    DPI_480(
        "480 dpi",
        "Largest interface. Only sensible on the high-resolution sources.",
        R.string.android_auto_density_480,
        R.string.android_auto_density_480_description,
        480
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
        "Match Android Auto's layout to the dashboard's real shape, so the map fills the panel " +
            "instead of sitting between black bars.",
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

/**
 * The screen MOTO-HUB puts on the TFT by itself once the motorcycle link comes up, when
 * autostart is enabled.
 *
 * [RIDE_DASHBOARD] exists only in Advanced - Core ships Mirror and Android Auto - so Core's
 * settings never offers it and Core's autostart refuses it if the stored value somehow names it.
 */
enum class AutostartService(
    val label: String,
    val description: String,
    val advancedOnly: Boolean = false
) {
    MIRRORING(
        "Mirroring",
        "Share the phone screen. Android still asks for screen-capture permission each time.",
    ),
    ANDROID_AUTO(
        "Android Auto",
        "Launch Android Auto on the dashboard."
    ),
    RIDE_DASHBOARD(
        "Ride Dashboard",
        "Render the native dashboard, in the mode selected below.",
        advancedOnly = true
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
    private const val KEY_ANDROID_AUTO_DENSITY = "android_auto_density"
    private const val KEY_ANDROID_AUTO_ASPECT_MATCHING = "android_auto_aspect_matching"
    private const val KEY_AUTO_CONNECT = "auto_connect"
    private const val KEY_AUTOSTART_ENABLED = "autostart_enabled"
    private const val KEY_AUTOSTART_SERVICE = "autostart_service"
    private const val KEY_AUTO_RECOVERY = "auto_recovery"
    private const val KEY_KEEP_WIFI_DIRECT_ON_DISCONNECT = "keep_wifi_direct_on_disconnect"
    private const val KEY_BLUETOOTH_CLOCK_SYNC = "bluetooth_clock_sync"
    private const val KEY_AUTO_RECORD_TRIPS = "auto_record_trips"
    private const val KEY_SHOW_RECORDED_TRACK = "show_recorded_track_on_dashboard"
    private const val KEY_DISTANCE_UNITS = "distance_units"
    private const val KEY_ROUTE_PREFERENCE = "route_preference"
    private const val KEY_NAV_VOICE_ENABLED = "nav_voice_enabled"
    private const val KEY_USE_DEMO_ROUTING_SERVER = "use_demo_routing_server"
    private const val KEY_SKIPPED_UPDATE_TAG = "skipped_update_tag"
    private const val KEY_AUTO_UPDATE_CHECKS = "auto_update_checks"
    private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    private const val KEY_LAST_AUTO_UPDATE_CHECK_AT = "last_auto_update_check_at_millis"
    private const val KEY_LAST_AUTO_UPDATE_CHECK_VERSION = "last_auto_update_check_version"
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
     * configured - see the advanced routing implementation.
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

    fun androidAutoDensity(context: Context): AndroidAutoDensityMode = enumPreference(
        context = context,
        key = KEY_ANDROID_AUTO_DENSITY,
        default = AndroidAutoDensityMode.AUTO
    )

    fun setAndroidAutoDensity(context: Context, mode: AndroidAutoDensityMode) {
        preferences(context).edit().putString(KEY_ANDROID_AUTO_DENSITY, mode.name).apply()
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

    /**
     * Put the configured service on the TFT as soon as the motorcycle link is up, instead of
     * leaving the rider on the mode picker. Off by default: which screen a rider wants is a
     * personal choice, and starting one uninvited would take over their dashboard.
     */
    fun autostartEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_AUTOSTART_ENABLED, false)

    fun setAutostartEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_AUTOSTART_ENABLED, enabled).apply()
    }

    fun autostartService(context: Context): AutostartService = enumPreference(
        context = context,
        key = KEY_AUTOSTART_SERVICE,
        default = AutostartService.MIRRORING
    )

    fun setAutostartService(context: Context, service: AutostartService) {
        preferences(context).edit().putString(KEY_AUTOSTART_SERVICE, service.name).apply()
    }

    /** Recovery is enabled by default so a transient T-Box transport failure does not end a ride. */
    fun autoRecovery(context: Context): Boolean = preferences(context).getBoolean(KEY_AUTO_RECOVERY, true)

    fun setAutoRecovery(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_AUTO_RECOVERY, enabled).apply()
    }

    /**
     * Skip releasing the Wi-Fi Direct group when disconnecting from a bike that uses it, instead
     * of the default eager teardown. Off by default: staying associated to the dash's network has
     * a real cost (no other network usable over that link until the group is actually torn down,
     * i.e. Wi-Fi off or the app fully exits), so only a rider whose dash needs this should pay it.
     * See [io.motohub.android.tbox.TBoxLink.WifiDirect].
     */
    fun keepWifiDirectAfterDisconnect(context: Context): Boolean =
        preferences(context).getBoolean(KEY_KEEP_WIFI_DIRECT_ON_DISCONNECT, false)

    fun setKeepWifiDirectAfterDisconnect(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_KEEP_WIFI_DIRECT_ON_DISCONNECT, enabled).apply()
    }

    /**
     * Answer the dashboard's clock questions over Bluetooth as well as over Wi-Fi.
     *
     * Off by default and deliberately opt-in: the protocol runs on generic serial-over-BLE service
     * UUIDs that intercoms, OBD dongles and tyre sensors also use, so this is not something to
     * switch on for riders who are not chasing a wrong dash clock. Even when enabled,
     * [io.motohub.android.tbox.EcBtpTimeLink] never transmits to a device until that device has
     * sent a valid frame of the protocol.
     */
    fun bluetoothClockSync(context: Context): Boolean =
        preferences(context).getBoolean(KEY_BLUETOOTH_CLOCK_SYNC, false)

    fun setBluetoothClockSync(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_BLUETOOTH_CLOCK_SYNC, enabled).apply()
    }

    /**
     * Extra T-Box protocol diagnostics: full CLIENT_INFO JSON, every candidate
     * profile's CLIENT_INFO score (not just the winner), hex dumps of
     * unrecognized PXC/media-control commands, and Wi-Fi link quality at
     * connect time. Off by default - this is meaningfully more verbose and
     * more expensive to format than the app's normal event log, and only
     * useful when a rider is actively helping diagnose a connection problem.
     */
    // On by default (2026-07-30): a connection problem's first occurrence is the one that
    // matters, and with verbose off it produced a log with the diagnosis missing - riders had
    // to reproduce the failure after finding this switch. The stable identifiers that once
    // justified defaulting off (HUID/uuid in the raw CLIENT_INFO) are redacted since the same
    // change. A rider who turns it off stays off - this is a default, not an override.
    fun verboseTBoxLogging(context: Context): Boolean =
        cachedBoolean(context, KEY_VERBOSE_TBOX_LOGGING, true)

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
        cachedBoolean(context, KEY_LOGGING_ENABLED, true)

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

    /**
     * Hold the phone's screen awake while a MOTO-HUB screen is in the foreground.
     *
     * No screen in this edition writes it, so it reads false and the diagnostics report says so -
     * which is the truth about a CORE install rather than a gap in it. It lives here, and not
     * behind an edition check at the one place that reads it, so the two copies of this shared
     * file keep the same surface.
     */
    fun keepScreenOn(context: Context): Boolean =
        preferences(context).getBoolean(KEY_KEEP_SCREEN_ON, false)

    fun setKeepScreenOn(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_KEEP_SCREEN_ON, enabled).apply()
    }

    /** Epoch millis of the last *automatic* update check; 0 if one has never run. */
    fun lastAutoUpdateCheckAtMillis(context: Context): Long =
        preferences(context).getLong(KEY_LAST_AUTO_UPDATE_CHECK_AT, 0L)

    fun setLastAutoUpdateCheckAtMillis(context: Context, epochMillis: Long) {
        preferences(context).edit().putLong(KEY_LAST_AUTO_UPDATE_CHECK_AT, epochMillis).apply()
    }

    fun lastAutoUpdateCheckVersion(context: Context): String? =
        preferences(context).getString(KEY_LAST_AUTO_UPDATE_CHECK_VERSION, null)

    fun setLastAutoUpdateCheckVersion(context: Context, versionName: String) {
        preferences(context).edit().putString(KEY_LAST_AUTO_UPDATE_CHECK_VERSION, versionName).apply()
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

    // ── cached flags ─────────────────────────────────────────────────────────────────────────
    //
    // SharedPreferences serves reads from memory, but every call still takes its internal lock
    // and hashes the key - and these two flags are read on the hottest paths in the app: the
    // logging master switch on EVERY ProjectionEventLog.record(), and the verbose flag on every
    // T-Box protocol event, touch moves included. The values change only when the rider flips a
    // switch, so they are cached and invalidated by the preference change itself. Anything
    // read once per session does NOT belong here - the plain accessors stay the default.

    private val flagCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * Held in a field on purpose: SharedPreferences keeps only a WEAK reference to its listeners,
     * so a listener that is not referenced anywhere is collected and the cache silently goes
     * stale - a flag the rider turned off would keep reading as on until the process restarts.
     */
    private val cacheInvalidation =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null) flagCache.clear() else flagCache.remove(key)
        }

    @Volatile
    private var cacheInvalidationRegistered = false

    private fun cachedBoolean(context: Context, key: String, default: Boolean): Boolean {
        flagCache[key]?.let { return it }
        val preferences = preferences(context)
        if (!cacheInvalidationRegistered) {
            synchronized(flagCache) {
                if (!cacheInvalidationRegistered) {
                    preferences.registerOnSharedPreferenceChangeListener(cacheInvalidation)
                    cacheInvalidationRegistered = true
                }
            }
        }
        val value = preferences.getBoolean(key, default)
        flagCache[key] = value
        return value
    }
}
