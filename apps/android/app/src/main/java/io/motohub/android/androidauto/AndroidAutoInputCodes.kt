package io.motohub.android.androidauto

/**
 * Numeric touch/key codes shared UI (previews, controls) uses to describe an Android Auto input
 * event, independent of which flavor's receiver actually sends it. These are plain protocol
 * constants (not creative expression), kept separate from `aa.AaInput` (the AGPL-derived sender)
 * so shared code never needs to import anything from the `aa` package.
 */
object AndroidAutoInputCodes {
    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
    const val ACTION_MOVE = 2

    const val KEY_UP = 19
    const val KEY_DOWN = 20
    const val KEY_LEFT = 21
    const val KEY_RIGHT = 22
    const val KEY_ENTER = 23
    const val KEY_BACK = 4
    const val KEY_HOME = 3
    const val KEY_ASSISTANT = 84
}
