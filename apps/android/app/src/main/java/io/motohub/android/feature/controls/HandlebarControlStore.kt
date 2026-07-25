package io.motohub.android.feature.controls

import android.content.Context

enum class HandlebarAction(val id: String, val label: String) {
    NONE("none", "Do nothing"),
    SCROLL_FORWARD("scrollForward", "Rotary forward"),
    SCROLL_BACK("scrollBack", "Rotary back"),
    DPAD_UP("dpadUp", "D-pad up"),
    DPAD_DOWN("dpadDown", "D-pad down"),
    DPAD_LEFT("dpadLeft", "D-pad left"),
    DPAD_RIGHT("dpadRight", "D-pad right"),
    SELECT("select", "Select / OK"),
    BACK("back", "Back"),
    HOME("home", "Home"),
    ASSISTANT("assistant", "Assistant"),
    NAV_1("nav1", "Navigate → place 1"),
    NAV_2("nav2", "Navigate → place 2"),
    NAV_3("nav3", "Navigate → place 3")
}

enum class HandlebarGesture(
    val id: String,
    val label: String,
    val transportHint: String,
    val defaultAction: HandlebarAction
) {
    VOLUME_UP("volumeUp", "Up press", "Bluetooth absolute-volume change", HandlebarAction.SCROLL_BACK),
    VOLUME_UP_DOUBLE("volumeUpDouble", "Up double press", "Larger absolute-volume change", HandlebarAction.HOME),
    VOLUME_DOWN("volumeDown", "Down press", "Bluetooth absolute-volume change", HandlebarAction.SCROLL_FORWARD),
    VOLUME_DOWN_DOUBLE("volumeDownDouble", "Down double press", "Larger absolute-volume change", HandlebarAction.BACK),
    ENTER("enter", "Select tap - Enter / Star", "Bluetooth play/pause command", HandlebarAction.SELECT),
    ENTER_LONG("enterLong", "Select hold - Enter / Star", "Requires a real Bluetooth key-up", HandlebarAction.ASSISTANT),
    ENTER_DOUBLE("enterDouble", "Select double tap - Enter / Star", "Two presses inside the double-tap window", HandlebarAction.ASSISTANT),
    TRACK_BACK("trackBack", "Backward - Left", "Bluetooth previous-track command", HandlebarAction.SCROLL_BACK),
    TRACK_BACK_DOUBLE("trackBackDouble", "Backward double tap - Left", "Two previous-track presses inside the window", HandlebarAction.HOME),
    TRACK_FORWARD("trackForward", "Forward - Right", "Bluetooth next-track command", HandlebarAction.SCROLL_FORWARD),
    TRACK_FORWARD_DOUBLE("trackForwardDouble", "Forward double tap - Right", "Two next-track presses inside the window", HandlebarAction.BACK)
}

object HandlebarControlStore {
    private const val PREFERENCES = "handlebar_controls"
    private const val ENABLED = "enabled"
    /** Bumped when [defaultAction] values change — auto-migrates stored overrides. */
    private const val DEFAULTS_VER = 1
    private const val KEY_DEFAULTS_VER = "defaults_ver"

    fun isEnabled(context: Context): Boolean = preferences(context).getBoolean(ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(ENABLED, enabled).apply()
    }

    fun action(context: Context, gesture: HandlebarGesture): HandlebarAction {
        ensureDefaultsMigrated(context)
        val stored = preferences(context).getString(gesture.id, null)
        return HandlebarAction.entries.firstOrNull { it.id == stored } ?: gesture.defaultAction
    }

    fun setAction(context: Context, gesture: HandlebarGesture, action: HandlebarAction) {
        preferences(context).edit().putString(gesture.id, action.id).apply()
    }

    fun reset(context: Context) {
        preferences(context).edit().clear().apply()
    }

    private fun ensureDefaultsMigrated(context: Context) {
        val p = preferences(context)
        if (p.getInt(KEY_DEFAULTS_VER, 0) >= DEFAULTS_VER) return
        // V1: Remove stored overrides for gestures whose defaultAction changed.
        // Currently no migrations — placeholder for future default changes.
        p.edit().putInt(KEY_DEFAULTS_VER, DEFAULTS_VER).apply()
    }

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE
    )
}
