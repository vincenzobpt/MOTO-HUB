package io.motohub.android.androidauto

enum class AndroidAutoVideoPreset(
    val source: DisplayGeometry,
    val densityDpi: Int
) {
    LANDSCAPE_800X480(DisplayGeometry(800, 480), 160),
    PORTRAIT_720X1280(DisplayGeometry(720, 1280), 240)
}

enum class AndroidAutoCapabilitySource {
    FALLBACK,
    SAVED_TBOX_GEOMETRY
}

data class AndroidAutoCapabilityProfile(
    val videoPreset: AndroidAutoVideoPreset,
    val source: AndroidAutoCapabilitySource,
    val target: DisplayGeometry?,
    val reason: String
) {
    val video: DisplayGeometry get() = videoPreset.source
    val densityDpi: Int get() = videoPreset.densityDpi
}

object AndroidAutoCapabilityProfiles {
    fun select(target: DisplayGeometry?): AndroidAutoCapabilityProfile {
        if (target == null) return fallback("No saved T-Box geometry is available.")
        if (!target.isPlausibleTBoxGeometry()) {
            return fallback(
                "Saved T-Box geometry ${target.width}x${target.height} is outside safe limits."
            )
        }

        val preset = if (target.height > target.width) {
            AndroidAutoVideoPreset.PORTRAIT_720X1280
        } else {
            AndroidAutoVideoPreset.LANDSCAPE_800X480
        }
        return AndroidAutoCapabilityProfile(
            videoPreset = preset,
            source = AndroidAutoCapabilitySource.SAVED_TBOX_GEOMETRY,
            target = target,
            reason = "Selected from saved runtime T-Box geometry ${target.width}x${target.height}."
        )
    }

    fun fallback(reason: String = "Using the hardware-validated compatibility profile.") =
        AndroidAutoCapabilityProfile(
            videoPreset = AndroidAutoVideoPreset.LANDSCAPE_800X480,
            source = AndroidAutoCapabilitySource.FALLBACK,
            target = null,
            reason = reason
        )

    private fun DisplayGeometry.isPlausibleTBoxGeometry(): Boolean {
        val shortest = minOf(width, height)
        val longest = maxOf(width, height)
        return shortest in MIN_DIMENSION..MAX_DIMENSION &&
            longest in MIN_DIMENSION..MAX_DIMENSION &&
            longest <= shortest * MAX_ASPECT_RATIO
    }

    private const val MIN_DIMENSION = 240
    private const val MAX_DIMENSION = 4096
    private const val MAX_ASPECT_RATIO = 4
}
