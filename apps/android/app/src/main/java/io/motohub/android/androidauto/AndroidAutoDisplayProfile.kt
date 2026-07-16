package io.motohub.android.androidauto

import android.content.Context
import io.motohub.android.session.MotorcycleProfile
import kotlin.math.roundToInt

data class DisplayGeometry(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "Display geometry must be positive" }
    }
}

enum class AndroidAutoDisplayMode(
    val title: String,
    val description: String
) {
    LETTERBOX(
        title = "Preserve aspect ratio",
        description = "Show the complete image with black side bars when needed."
    ),
    STRETCH(
        title = "Fill display",
        description = "Use the whole TFT and keep all content visible with slight stretching."
    )
}

data class AndroidAutoDisplayProfile(
    val source: DisplayGeometry,
    val expectedTft: DisplayGeometry,
    val marginWidth: Int,
    val marginHeight: Int
) {
    val cropLeft: Int get() = marginWidth / 2
    val cropTop: Int get() = marginHeight / 2
    val contentWidth: Int get() = source.width - marginWidth
    val contentHeight: Int get() = source.height - marginHeight
}

internal fun calculateAndroidAutoDisplayProfile(
    target: DisplayGeometry,
    source: DisplayGeometry = DisplayGeometry(800, 480)
): AndroidAutoDisplayProfile {
    val sourceAspect = source.width.toDouble() / source.height
    val targetAspect = target.width.toDouble() / target.height
    val marginWidth: Int
    val marginHeight: Int
    if (targetAspect >= sourceAspect) {
        val contentHeight = even((source.width / targetAspect).roundToInt())
            .coerceIn(2, source.height)
        marginWidth = 0
        marginHeight = even(source.height - contentHeight)
    } else {
        val contentWidth = even((source.height * targetAspect).roundToInt())
            .coerceIn(2, source.width)
        marginWidth = even(source.width - contentWidth)
        marginHeight = 0
    }
    return AndroidAutoDisplayProfile(source, target, marginWidth, marginHeight)
}

private fun even(value: Int): Int = value and 1.inv()

object ActiveAndroidAutoDisplayProfile {
    private const val AAP_CANVAS_WIDTH = 800
    private const val AAP_CANVAS_HEIGHT = 480
    private val DEFAULT_SOURCE = DisplayGeometry(AAP_CANVAS_WIDTH, AAP_CANVAS_HEIGHT)

    @Volatile
    var current: AndroidAutoDisplayProfile = calculateAndroidAutoDisplayProfile(uncalibratedGeometry())
        private set

    fun configure(
        target: DisplayGeometry,
        source: DisplayGeometry = DEFAULT_SOURCE
    ): AndroidAutoDisplayProfile =
        calculateAndroidAutoDisplayProfile(target, source).also { current = it }

    /** Keeps the complete selected AA source visible until a T-Box geometry is available. */
    fun configureUncalibrated(source: DisplayGeometry = DEFAULT_SOURCE): AndroidAutoDisplayProfile =
        configure(source, source)

    private fun uncalibratedGeometry() = DEFAULT_SOURCE
}

class TBoxDisplayGeometryStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun load(ssid: String): DisplayGeometry? {
        val width = preferences.getInt(key(ssid, "width"), 0)
        val height = preferences.getInt(key(ssid, "height"), 0)
        return if (width > 0 && height > 0) DisplayGeometry(width, height) else null
    }

    fun save(ssid: String, geometry: DisplayGeometry) {
        preferences.edit()
            .putInt(key(ssid, "width"), geometry.width)
            .putInt(key(ssid, "height"), geometry.height)
            .apply()
    }

    private fun key(ssid: String, field: String): String = "$ssid:$field"

    private companion object {
        const val PREFERENCES_NAME = "tbox_display_geometry"
    }
}

class AndroidAutoDisplayModeStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun load(profile: MotorcycleProfile): AndroidAutoDisplayMode {
        val profileValue = preferences.getString(key(profile.id), null)
        return parse(profileValue) ?: parse(preferences.getString(key(profile.ssid), null))
        ?: AndroidAutoDisplayMode.LETTERBOX
    }

    fun save(profile: MotorcycleProfile, mode: AndroidAutoDisplayMode) {
        preferences.edit().putString(key(profile.id), mode.name).apply()
    }

    private fun parse(value: String?): AndroidAutoDisplayMode? = runCatching {
        value?.let(AndroidAutoDisplayMode::valueOf)
    }.getOrNull()

    private fun key(ssid: String): String = "mode:$ssid"

    private companion object {
        const val PREFERENCES_NAME = "android_auto_display_mode"
    }
}
