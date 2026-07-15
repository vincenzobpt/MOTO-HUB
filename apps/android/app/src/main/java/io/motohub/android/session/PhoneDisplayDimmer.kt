package io.motohub.android.session

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager

/**
 * Keeps the physical panel at a usable minimum brightness without changing captured pixels.
 * The overlay never receives touch events, so the notification shade and the app remain usable.
 */
class PhoneDisplayDimmer(context: Context) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(WindowManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlay: View? = null

    val isDimmed: Boolean
        get() = overlay != null

    fun dim(): Boolean {
        if (overlay != null) return true
        if (!canDim(appContext)) return false

        val view = View(appContext).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Keep the overlay transparent so projection frames are unchanged. A non-zero alpha
            // keeps the brightness request active without blocking notification-shade gestures.
            alpha = OVERLAY_ALPHA
            screenBrightness = MIN_DIM_BRIGHTNESS
            buttonBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            title = "MOTO-HUB display dimmer"
        }

        return runCatching {
            windowManager.addView(view, params)
            overlay = view
            true
        }.getOrDefault(false)
    }

    fun restore() {
        val view = overlay ?: return
        overlay = null
        if (Looper.myLooper() == Looper.getMainLooper()) {
            removeOverlay(view)
        } else {
            mainHandler.post { removeOverlay(view) }
        }
    }

    private fun removeOverlay(view: View) {
        runCatching { windowManager.removeViewImmediate(view) }
    }

    companion object {
        private const val OVERLAY_ALPHA = 0.01f
        private const val MIN_DIM_BRIGHTNESS = 0.01f

        fun canDim(context: Context): Boolean = Settings.canDrawOverlays(context)
    }
}

object PhoneDisplayDimPreferences {
    private const val PREFS_NAME = "phone_display"
    private const val KEY_AUTO_DIM = "auto_dim"

    fun isEnabled(context: Context): Boolean = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    ).getBoolean(KEY_AUTO_DIM, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_DIM, enabled)
            .apply()
    }
}
