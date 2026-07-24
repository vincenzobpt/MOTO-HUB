package io.motohub.android.androidauto

enum class AndroidAutoVideoPreset(
    val source: DisplayGeometry,
    val densityDpi: Int
) {
    LANDSCAPE_800X480(DisplayGeometry(800, 480), 160),
    LANDSCAPE_1280X720(DisplayGeometry(1280, 720), 160),
    PORTRAIT_720X1280(DisplayGeometry(720, 1280), 240),
    PORTRAIT_1080X1920(DisplayGeometry(1080, 1920), 240)
}

private val AUTO_LANDSCAPE_PRESETS = listOf(
    AndroidAutoVideoPreset.LANDSCAPE_800X480,
    AndroidAutoVideoPreset.LANDSCAPE_1280X720
)

private val AUTO_PORTRAIT_PRESETS = listOf(
    AndroidAutoVideoPreset.PORTRAIT_720X1280,
    AndroidAutoVideoPreset.PORTRAIT_1080X1920
)

enum class AndroidAutoCapabilitySource {
    FALLBACK,
    SAVED_TBOX_GEOMETRY,
    USER_OVERRIDE
}

data class AndroidAutoCapabilityProfile(
    val videoPreset: AndroidAutoVideoPreset,
    val source: AndroidAutoCapabilitySource,
    val target: DisplayGeometry?,
    val reason: String,
    val screenMargins: TBoxScreenMargins = TBoxScreenMargins.NONE,
    val touchEnabled: Boolean = true
) {
    val video: DisplayGeometry get() = videoPreset.source
    val densityDpi: Int get() = videoPreset.densityDpi
    /** Android Auto's touch/UI surface after applying explicit AA content insets only. */
    val touchSurface: DisplayGeometry
        get() = screenMargins.inset(video)
    val displayProfile: AndroidAutoDisplayProfile
        get() = target?.let { calculateAndroidAutoDisplayProfile(it, video) }
            ?: calculateAndroidAutoDisplayProfile(video, video)
    val marginWidth: Int get() = (video.width - touchSurface.width).coerceAtLeast(0)
    val marginHeight: Int get() = (video.height - touchSurface.height).coerceAtLeast(0)
}

/**
 * Do not tell Android Auto that part of the negotiated source is reserved panel furniture.
 * This is deliberately independent of the learned T-Box encoder area: that area can be
 * smaller than the physical panel even when the panel itself has no Android Auto margins.
 */
internal fun AndroidAutoCapabilityProfile.withFullVideoTargetForDashboard(): AndroidAutoCapabilityProfile =
    copy(
        target = video,
        reason = reason + " Ride Dashboard composes the full AA source into its dynamic panel."
    )

object AndroidAutoCapabilityProfiles {
    /**
     * Returns learned geometry that is safe to reuse for AUTO selection. A non-exact geometry
     * with the opposite orientation from the model's validated fallback is usually a stale or
     * misreported T-Box area (for example a portrait emulator area saved for a landscape 800NK).
     * Exact-fit geometries remain valid even when they are close to square.
     */
    internal fun usableSavedGeometryForAuto(
        target: DisplayGeometry?,
        fallbackPreset: AndroidAutoVideoPreset
    ): DisplayGeometry? {
        if (target == null || !target.isPlausibleTBoxGeometry()) return null
        if (exactFitPreset(target) != null) return target
        val targetIsPortrait = target.height > target.width
        val fallbackIsPortrait = fallbackPreset.source.height > fallbackPreset.source.width
        return target.takeIf { targetIsPortrait == fallbackIsPortrait }
    }

    fun select(
        target: DisplayGeometry?,
        overridePreset: AndroidAutoVideoPreset? = null,
        screenMargins: TBoxScreenMargins = TBoxScreenMargins.NONE,
        touchEnabled: Boolean = true,
        fallbackPreset: AndroidAutoVideoPreset = AndroidAutoVideoPreset.LANDSCAPE_800X480
    ): AndroidAutoCapabilityProfile {
        if (overridePreset != null) {
            return AndroidAutoCapabilityProfile(
                videoPreset = overridePreset,
                source = AndroidAutoCapabilitySource.USER_OVERRIDE,
                target = target,
                reason = "Selected by the user's resolution and orientation override.",
                screenMargins = screenMargins,
                touchEnabled = touchEnabled
            )
        }
       if (target == null) {
           return fallback(
               reason = "No saved T-Box geometry is available.",
               screenMargins = screenMargins,
               touchEnabled = touchEnabled,
               preset = fallbackPreset
           )
       }
        if (!target.isPlausibleTBoxGeometry()) {
           return fallback(
               reason = "Saved T-Box geometry is outside safe limits.",
               screenMargins = screenMargins,
               touchEnabled = touchEnabled,
               preset = fallbackPreset
           )
       }

        val exactFit = exactFitPreset(target)
        // No exact pixel match: always fall back to the SD preset for the orientation,
        // never HD. HD is only ever selected when a saved T-Box geometry exactly matches
        // it (exactFitPreset above) - it is unverified end-to-end on unrecognized dashes
        // and picking it from aspect-ratio proximity alone is a black-screen risk on
        // decoders that haven't been validated against it.
        val preset = exactFit ?: if (target.height > target.width) {
            AUTO_PORTRAIT_PRESETS.first()
       } else {
            AUTO_LANDSCAPE_PRESETS.first()
       }
        val selectionReason = if (exactFit != null) {
            "Selected an exact-fit AA source for saved runtime T-Box geometry "
        } else {
            "No exact-fit AA source exists; selected the closest aspect ratio for saved runtime T-Box geometry "
        }
        return AndroidAutoCapabilityProfile(
            videoPreset = preset,
            source = AndroidAutoCapabilitySource.SAVED_TBOX_GEOMETRY,
            target = target,
            reason = selectionReason + "${target.width}x${target.height}: " +
                "${preset.source.width}x${preset.source.height}.",
            screenMargins = screenMargins,
            touchEnabled = touchEnabled
        )
    }

    fun fallback(
        reason: String = "Using the hardware-validated compatibility profile.",
        screenMargins: TBoxScreenMargins = TBoxScreenMargins.NONE,
        touchEnabled: Boolean = true,
        preset: AndroidAutoVideoPreset = AndroidAutoVideoPreset.LANDSCAPE_800X480
    ) =
        AndroidAutoCapabilityProfile(
            videoPreset = preset,
            source = AndroidAutoCapabilitySource.FALLBACK,
            target = null,
            reason = reason,
            screenMargins = screenMargins,
            touchEnabled = touchEnabled
        )

    private fun DisplayGeometry.isPlausibleTBoxGeometry(): Boolean {
        val shortest = minOf(width, height)
        val longest = maxOf(width, height)
        return shortest in MIN_DIMENSION..MAX_DIMENSION &&
            longest in MIN_DIMENSION..MAX_DIMENSION &&
            longest <= shortest * MAX_ASPECT_RATIO
    }

    /** Prefer a 1:1 match on one axis so the declared AA margins remain real pixels. */
    private fun exactFitPreset(target: DisplayGeometry): AndroidAutoVideoPreset? {
        val alignedWidth = target.width and 0xFFF0
        val alignedHeight = target.height and 0xFFF0
        val candidates = AndroidAutoVideoPreset.entries.filter { preset ->
            (preset.videoWidth() == alignedWidth && preset.videoHeight() >= alignedHeight) ||
                (preset.videoHeight() == alignedHeight && preset.videoWidth() >= alignedWidth)
        }
        return candidates.minByOrNull { preset ->
            val widthRemainder = (preset.videoWidth() - alignedWidth).coerceAtLeast(0)
            val heightRemainder = (preset.videoHeight() - alignedHeight).coerceAtLeast(0)
            widthRemainder.toLong() * heightRemainder + widthRemainder + heightRemainder
        }
    }

    private fun AndroidAutoVideoPreset.videoWidth(): Int = source.width
    private fun AndroidAutoVideoPreset.videoHeight(): Int = source.height

    private const val MIN_DIMENSION = 240
    private const val MAX_DIMENSION = 4096
    private const val MAX_ASPECT_RATIO = 4
}
