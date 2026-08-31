// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.androidauto

/**
 * Every coded source the Android Auto protocol defines (control.proto's
 * `VideoCodecResolutionType`), each with the density that keeps its layout the same size in dp
 * as the SD source for the same orientation - the extra pixels buy sharpness, not more UI.
 *
 * [autoSelectable] is what stops that completeness from becoming a black screen. The four
 * sources below it are the ones MOTO-HUB has actually run end to end on a dashboard; the rest
 * are offered as a manual choice only, because AUTO picks a source from a geometry the T-Box
 * reported about itself, and a dash that misreports 1920x1080 would have us encode 1080p into a
 * decoder nobody has ever fed 1080p. A rider who knows their panel can still select one by hand.
 */
enum class AndroidAutoVideoPreset(
    val source: DisplayGeometry,
    val densityDpi: Int,
    /** Whether AUTO is allowed to land on this source from a learned T-Box geometry. */
    val autoSelectable: Boolean = true
) {
    LANDSCAPE_800X480(DisplayGeometry(800, 480), 160),
    LANDSCAPE_1280X720(DisplayGeometry(1280, 720), 160),
    LANDSCAPE_1920X1080(DisplayGeometry(1920, 1080), 240, autoSelectable = false),
    LANDSCAPE_2560X1440(DisplayGeometry(2560, 1440), 320, autoSelectable = false),
    LANDSCAPE_3840X2160(DisplayGeometry(3840, 2160), 480, autoSelectable = false),
    PORTRAIT_720X1280(DisplayGeometry(720, 1280), 240),
    PORTRAIT_1080X1920(DisplayGeometry(1080, 1920), 240),
    PORTRAIT_1440X2560(DisplayGeometry(1440, 2560), 320, autoSelectable = false),
    PORTRAIT_2160X3840(DisplayGeometry(2160, 3840), 480, autoSelectable = false)
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

/**
 * Coded-frame pixels given up so Android Auto lays its UI out at the PANEL's aspect ratio.
 *
 * Distinct from [TBoxScreenMargins], which describes physical furniture the motorcycle owns and
 * is capped at a couple of hundred pixels for that reason. These are a projection decision and
 * are routinely enormous: matching a 800x951 panel from a 720x1280 coded source gives up 424
 * rows. Both end up in the same AAP `marginWidth`/`marginHeight` fields, so they add.
 *
 * Without them, no coded size fits a portrait-ish dash and Android Auto is letterboxed into a
 * band: a rider's 800x951 panel (modelId 37426, 2026-07-31) got a 418x744 viewport inside a
 * 800x944 canvas - 291 black pixels down each side, more than half the screen wasted.
 */
data class AaAspectMargins(val width: Int, val height: Int) {
    init {
        require(width >= 0 && height >= 0) { "Aspect margins cannot be negative" }
    }

    /** Whether anything is actually given up, and therefore has to be cropped back out. */
    val any: Boolean get() = width > 0 || height > 0

    companion object {
        val NONE = AaAspectMargins(0, 0)

        /**
         * Margins that shrink [coded] until what is left has [panel]'s aspect ratio.
         *
         * Only the axis that is too long is trimmed, so the other keeps every coded pixel and as
         * much detail as possible survives the scale to the panel. A [minUsable] floor stops a
         * wildly misreported panel from collapsing the picture to nothing - a margin that leaves
         * eight usable rows is not a better answer than no margin at all.
         */
        fun forPanel(
            coded: DisplayGeometry,
            panel: DisplayGeometry,
            minUsable: Int = MIN_USABLE
        ): AaAspectMargins {
            // No zero guard: DisplayGeometry refuses to hold one, so both are already positive.
            val codedAspect = coded.width.toDouble() / coded.height
            val panelAspect = panel.width.toDouble() / panel.height
            return if (codedAspect < panelAspect) {
                // The coded frame is too tall for this panel: give up rows.
                val usable = Math.round(coded.width * panel.height.toDouble() / panel.width)
                    .toInt().coerceIn(minUsable, coded.height)
                AaAspectMargins(0, coded.height - usable)
            } else {
                // Too wide: give up columns.
                val usable = Math.round(coded.height * panel.width.toDouble() / panel.height)
                    .toInt().coerceIn(minUsable, coded.width)
                AaAspectMargins(coded.width - usable, 0)
            }
        }

        /** Below this many usable pixels on an axis the match is refused rather than applied. */
        const val MIN_USABLE = 160
    }
}

data class AndroidAutoCapabilityProfile(
    val videoPreset: AndroidAutoVideoPreset,
    val source: AndroidAutoCapabilitySource,
    val target: DisplayGeometry?,
    val reason: String,
    val screenMargins: TBoxScreenMargins = TBoxScreenMargins.NONE,
    val touchEnabled: Boolean = true,
    /** See [AaAspectMargins]; added on top of [screenMargins] in the AAP margin fields. */
    val aspectMargins: AaAspectMargins = AaAspectMargins.NONE,
    /**
     * The rider's explicit density, in dpi, or null to use the one the preset carries.
     *
     * Density is the only thing that decides how big Android Auto draws itself: the source size
     * is pixels, and dp = px * 160 / dpi. Two dashes with the same 800x480 panel can want
     * different answers here - a 5" TFT at arm's length and a 10" one on a tourer - and the
     * preset's own value is a single compromise for both.
     */
    val densityOverride: Int? = null
) {
    val video: DisplayGeometry get() = videoPreset.source
    val densityDpi: Int get() = densityOverride ?: videoPreset.densityDpi
    /** Android Auto's touch/UI surface after applying explicit AA content insets only. */
    val touchSurface: DisplayGeometry
        get() = screenMargins.inset(video).let { framed ->
            DisplayGeometry(
                width = (framed.width - aspectMargins.width).coerceAtLeast(1),
                height = (framed.height - aspectMargins.height).coerceAtLeast(1)
            )
        }
    val displayProfile: AndroidAutoDisplayProfile
        get() = target?.let { calculateAndroidAutoDisplayProfile(it, video) }
            ?: calculateAndroidAutoDisplayProfile(video, video)
    val marginWidth: Int get() = (video.width - touchSurface.width).coerceAtLeast(0)
    val marginHeight: Int get() = (video.height - touchSurface.height).coerceAtLeast(0)
}

/**
 * Do not tell Android Auto that part of the negotiated source is reserved output furniture.
 * This is deliberately independent of the learned T-Box encoder area: that area can be
 * smaller than the physical panel even when the panel itself has no Android Auto margins.
 */
internal fun AndroidAutoCapabilityProfile.withFullVideoTarget(): AndroidAutoCapabilityProfile =
    copy(
        target = video,
        reason = reason + " The full negotiated AA source is used by the output compositor."
    )

object AndroidAutoCapabilityProfiles {
    /**
     * Returns learned geometry that is safe to reuse for AUTO selection. A non-exact geometry
     * with the opposite orientation from the model's validated fallback is usually a stale or
     * misreported T-Box area (for example a portrait emulator area saved for a landscape 800NK).
     * Exact-fit geometries remain valid even when they are close to square.
     *
     * [fallbackIsValidated] must be false when the fallback comes from the generic profile
     * rather than a recognized model. GENERIC's landscape default is a guess, not a
     * measurement, and vetoing against it is self-defeating: a rider log (modelId 37426 whose
     * CLIENT_INFO failed to decode, so it resolved to GENERIC) showed a real portrait 800x951
     * dash rejected on every session, which also blocked saving the very geometry that would
     * have corrected the guess - Android Auto stayed letterboxed into a 800x480 band forever.
     */
    internal fun usableSavedGeometryForAuto(
        target: DisplayGeometry?,
        fallbackPreset: AndroidAutoVideoPreset,
        fallbackIsValidated: Boolean = true
    ): DisplayGeometry? {
        if (target == null || !target.isPlausibleTBoxGeometry()) return null
        if (exactFitPreset(target) != null) return target
        if (!fallbackIsValidated) return target
        val targetIsPortrait = target.height > target.width
        val fallbackIsPortrait = fallbackPreset.source.height > fallbackPreset.source.width
        return target.takeIf { targetIsPortrait == fallbackIsPortrait }
    }

    fun select(
        target: DisplayGeometry?,
        overridePreset: AndroidAutoVideoPreset? = null,
        screenMargins: TBoxScreenMargins = TBoxScreenMargins.NONE,
        touchEnabled: Boolean = true,
        fallbackPreset: AndroidAutoVideoPreset = AndroidAutoVideoPreset.LANDSCAPE_800X480,
        densityOverride: Int? = null
    ): AndroidAutoCapabilityProfile {
        if (overridePreset != null) {
            return AndroidAutoCapabilityProfile(
                videoPreset = overridePreset,
                source = AndroidAutoCapabilitySource.USER_OVERRIDE,
                target = target,
                reason = "Selected by the user's resolution and orientation override.",
                screenMargins = screenMargins,
                touchEnabled = touchEnabled,
                densityOverride = densityOverride
            )
        }
       if (target == null) {
           return fallback(
               reason = "No saved T-Box geometry is available.",
               screenMargins = screenMargins,
               touchEnabled = touchEnabled,
               preset = fallbackPreset,
               densityOverride = densityOverride
           )
       }
        if (!target.isPlausibleTBoxGeometry()) {
           return fallback(
               reason = "Saved T-Box geometry is outside safe limits.",
               screenMargins = screenMargins,
               touchEnabled = touchEnabled,
               preset = fallbackPreset,
               densityOverride = densityOverride
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
            touchEnabled = touchEnabled,
            densityOverride = densityOverride
        )
    }

    fun fallback(
        reason: String = "Using the hardware-validated compatibility profile.",
        screenMargins: TBoxScreenMargins = TBoxScreenMargins.NONE,
        touchEnabled: Boolean = true,
        preset: AndroidAutoVideoPreset = AndroidAutoVideoPreset.LANDSCAPE_800X480,
        densityOverride: Int? = null
    ) =
        AndroidAutoCapabilityProfile(
            videoPreset = preset,
            source = AndroidAutoCapabilitySource.FALLBACK,
            target = null,
            reason = reason,
            screenMargins = screenMargins,
            touchEnabled = touchEnabled,
            densityOverride = densityOverride
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
            // autoSelectable, not entries: the sources beyond 720p exist for a rider to choose,
            // never for a T-Box's own report to choose for them. See the enum.
            preset.autoSelectable &&
                ((preset.videoWidth() == alignedWidth && preset.videoHeight() >= alignedHeight) ||
                    (preset.videoHeight() == alignedHeight && preset.videoWidth() >= alignedWidth))
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
