package io.motohub.android.tbox

import io.motohub.android.androidauto.AndroidAutoDisplayMode
import io.motohub.android.androidauto.AndroidAutoVideoPreset
import io.motohub.android.androidauto.TBoxScreenMargins

/** Tunings that can be applied after the transport has decoded a touch frame. */
data class TBoxTouchPolicy(
    val ghostMergePx: Int = 48,
    val stitchMillis: Long = 80,
    val stitchDistancePx: Int = 150,
    val staleContactMillis: Long = 300,
    val maxPointers: Int = 2
)

/**
 * Known T-Box behavior hints, matching the profiles from the OpenCfMoto reference.
 * Wire-level quirks remain inside the native transport.
 */
enum class TBoxModelProfile(
    val key: String,
    val displayName: String,
    private val modelIds: Set<String>,
    val mapTilesRequireCellular: Boolean,
    val touchPolicy: TBoxTouchPolicy = TBoxTouchPolicy(),
    val defaultScreenMargins: TBoxScreenMargins = TBoxScreenMargins.NONE,
    /** Default only; riders may still select FIT, STRETCH, or CROP per motorcycle. */
    val defaultAndroidAutoDisplayMode: AndroidAutoDisplayMode = AndroidAutoDisplayMode.LETTERBOX,
    val supportsScreenTouch: Boolean = true,
    val defaultAndroidAutoPreset: AndroidAutoVideoPreset =
        AndroidAutoVideoPreset.LANDSCAPE_800X480,
    /** Matches OpenCfMoto's requiresSockServerAuth / enableSockServerAuth flag. */
    val requiresSockAuth: Boolean = false,
    /** Capability bitmask returned by the MediaCtrlScreenConf response. */
    val advertisedSupportFunction: Int = 0,
    /** Firmware closes EasyConn unless both reverse PXC channel sockets receive traffic. */
    val requiresProactivePxcHeartbeat: Boolean = false
) {
    MOTO_HUB_SIMULATOR(
        key = "moto_hub_simulator",
        displayName = "MOTO-HUB T-Box Simulator",
        modelIds = setOf("MOTO-HUB-SIMULATOR"),
        mapTilesRequireCellular = false,
        advertisedSupportFunction = 128
    ),
    /** CFDL16 / 450SR-style non-touch legacy dash. */
    LEGACY_CFDL16(
        key = "legacy_cfdl16",
        displayName = "CFDL16 / Legacy (BIKE A)",
        modelIds = setOf("37416"),
        mapTilesRequireCellular = true,
        supportsScreenTouch = false,
        defaultAndroidAutoPreset = AndroidAutoVideoPreset.LANDSCAPE_800X480,
        requiresSockAuth = false,
        advertisedSupportFunction = 0
    ),
    /** 800NK CRCP / sdk 0.9.23.x non-touch. */
    CFMOTO_800NK(
        key = "cfmoto_800nk",
        displayName = "CFMOTO 800NK",
        modelIds = setOf("66660703", "66660721"),
        mapTilesRequireCellular = true,
        defaultScreenMargins = TBoxScreenMargins(top = 22),
        supportsScreenTouch = false,
        defaultAndroidAutoPreset = AndroidAutoVideoPreset.LANDSCAPE_800X480,
        requiresSockAuth = false,
        advertisedSupportFunction = 128,
        requiresProactivePxcHeartbeat = true
    ),
    /**
     * MTX800 portrait Wi-Fi Direct dashboard. Model id 66660732 was previously
     * grouped with 800NK, which gave the motorcycle the wrong product identity.
     * Its measured projection area is 460x750 and the tester hardware exhibits
     * the same dual-channel PXC timeout unless proactive heartbeats are enabled.
     * It has no confirmed motorcycle-owned top strip inside that projection area.
     */
    CFMOTO_MTX800(
        key = "cfmoto_mtx800",
        displayName = "CFMOTO MTX800",
        modelIds = setOf("66660732"),
        mapTilesRequireCellular = true,
        defaultAndroidAutoDisplayMode = AndroidAutoDisplayMode.FILL,
        supportsScreenTouch = false,
        defaultAndroidAutoPreset = AndroidAutoVideoPreset.PORTRAIT_720X1280,
        requiresSockAuth = false,
        advertisedSupportFunction = 128,
        requiresProactivePxcHeartbeat = true
    ),
    /** CFDL26 MotoPlay Landscape (800MT) — touchscreen dash. */
    CFDL26_LANDSCAPE(
        key = "cfdl26_landscape",
        displayName = "CFDL26 / MotoPlay Landscape (800MT)",
        modelIds = setOf("37426"),
        mapTilesRequireCellular = true,
        supportsScreenTouch = true,
        defaultAndroidAutoPreset = AndroidAutoVideoPreset.LANDSCAPE_800X480,
        requiresSockAuth = true,
        advertisedSupportFunction = 128
    ),
    /** CFDL26 MotoPlay Portrait (1000 MT‑X) — handlebar-primary, non-touch. */
    CFDL26_PORTRAIT(
        key = "cfdl26_portrait",
        displayName = "CFDL26 / MotoPlay Portrait (1000 MT‑X)",
        modelIds = setOf("37426"),
        mapTilesRequireCellular = true,
        supportsScreenTouch = false,
        defaultAndroidAutoPreset = AndroidAutoVideoPreset.PORTRAIT_720X1280,
        requiresSockAuth = true,
        advertisedSupportFunction = 128
    ),
    /**
     * CFDL26 800NK Advanced — near-square touch panel measured 720x712 by the OpenCfMoto
     * zanderp reference, which requests it at 160dpi. MOTO-HUB's [AndroidAutoVideoPreset]
     * bundles one fixed density per resolution and [AndroidAutoVideoPreset.PORTRAIT_720X1280]
     * is already 240dpi for [CFDL26_PORTRAIT] above; adding a second 720x1280 preset just for
     * this profile's dpi would ripple through the whole AA capability-profile/settings system
     * for a UI-scaling difference, not a resolution difference, so this deliberately reuses the
     * existing 240dpi preset. Shares modelId "37426" with [CFDL26_LANDSCAPE]/[CFDL26_PORTRAIT];
     * disambiguated from both via [resolve]'s CLIENT_INFO scoring, not by modelId alone.
     */
    CFDL26_NK_TOUCH(
        key = "cfdl26_nk_touch",
        displayName = "CFDL26 / 800NK Advanced (touch, 720x712)",
        modelIds = setOf("37426"),
        mapTilesRequireCellular = true,
        supportsScreenTouch = true,
        defaultScreenMargins = TBoxScreenMargins(top = 22),
        defaultAndroidAutoPreset = AndroidAutoVideoPreset.PORTRAIT_720X1280,
        requiresSockAuth = true,
        advertisedSupportFunction = 128
    ),
    /** CFDL16-class MotoPlay Landscape, modelId 66660742 (Wi-Fi Direct, non-touch). */
    CFDL16_MOTOPLAY_LANDSCAPE(
        key = "cfdl16_motoplay_landscape",
        displayName = "CFDL16 / MotoPlay Landscape (66660742)",
        modelIds = setOf("66660742"),
        mapTilesRequireCellular = true,
        supportsScreenTouch = false,
        defaultAndroidAutoPreset = AndroidAutoVideoPreset.LANDSCAPE_800X480,
        requiresSockAuth = false,
        advertisedSupportFunction = 128
    ),
    /** Near-square CL-C450 panel measured 544x512; requested with an HD 1280x720 AA source. */
    CL_C450(
        key = "cl_c450",
        displayName = "CL-C450 (544x512)",
        modelIds = setOf("66660736", "CLC450"),
        mapTilesRequireCellular = true,
        supportsScreenTouch = false,
        defaultAndroidAutoPreset = AndroidAutoVideoPreset.LANDSCAPE_1280X720,
        requiresSockAuth = false,
        advertisedSupportFunction = 0
    ),
    GENERIC(
        key = "generic",
        displayName = "Generic CFMOTO T-Box",
        modelIds = emptySet(),
        mapTilesRequireCellular = true
    );

    companion object {
        private fun candidatesForModelId(modelId: String?): List<TBoxModelProfile> {
            val normalized = modelId?.trim().orEmpty()
            if (normalized.isEmpty()) return emptyList()
            return entries.filter { normalized in it.modelIds }
        }

        fun fromModelId(modelId: String?): TBoxModelProfile =
            // If multiple profiles match the same modelId (e.g. "37426" for CFDL26_LANDSCAPE,
            // CFDL26_PORTRAIT and CFDL26_NK_TOUCH), resolve via CLIENT_INFO scoring in resolve().
            candidatesForModelId(modelId).singleOrNull() ?: GENERIC

        /** Prefer authoritative CLIENT_INFO strings when the QR/model id is ambiguous. */
        fun resolve(modelId: String?, capabilities: TBoxCapabilities?): TBoxModelProfile {
            return resolve(modelId, capabilities, null)
        }

        /** Resolve with optional manual override. When [profileOverride] is not AUTO, it pins the profile. */
        fun resolve(
            modelId: String?,
            capabilities: TBoxCapabilities?,
            profileOverride: ProfileOverride?
        ): TBoxModelProfile {
            profileOverride?.resolve()?.let { return it }
            val byId = fromModelId(modelId)
            if (byId != GENERIC) return byId
            if (capabilities == null) return GENERIC
            // Restrict scoring to profiles that share the (ambiguous) modelId when one was
            // provided at all - e.g. only the three CFDL26 variants compete for "37426", never
            // a profile the modelId itself doesn't claim. Only opens up to every profile when
            // there was no modelId lead to begin with.
            val candidates = candidatesForModelId(modelId).ifEmpty { entries.filterNot { it == GENERIC } }
            return candidates
                .map { it to score(it, capabilities) }
                .filter { (_, points) -> points > 0 }
                .maxByOrNull { (_, points) -> points }
                ?.first
                ?: GENERIC
        }

        /**
         * Every profile's CLIENT_INFO score, not just the one [resolve] picks - diagnostic
         * only (gated behind Settings > Diagnostics > Verbose T-Box logging), so a rider's
         * shared log can show *why* a given profile won instead of only the final answer.
         */
        internal fun scoreBreakdown(capabilities: TBoxCapabilities): String =
            entries.filterNot { it == GENERIC }
                .joinToString(", ") { "${it.displayName}=${score(it, capabilities)}" }

        /**
         * Weighted match against CLIENT_INFO signals, used to disambiguate profiles sharing a
         * modelId or to identify a device when no modelId is known at all. The signal set
         * mirrors what the OpenCfMoto zanderp fork's `BikeProfile.score()` checks per profile
         * (version_name/package_name/sdkVersion/supportFunction/HUName/capability-flag
         * matches), reimplemented against [TBoxCapabilities]. Highest positive score wins; a
         * score of 0 means "no claim" and is never selected over [GENERIC].
         */
        private fun score(profile: TBoxModelProfile, capabilities: TBoxCapabilities): Int {
            // Combined lowercase fallback for the same free-text keyword matching resolve()
            // used before this scoring existed (carModel included) - kept alongside the more
            // precise per-field signals below so devices only identifiable by a loose keyword
            // match (e.g. only `carModel` populated) still resolve the same way they did.
            val identity = listOf(
                capabilities.carModel,
                capabilities.huName,
                capabilities.packageName,
                capabilities.versionName,
                capabilities.sdkVersion
            ).filterNotNull().joinToString(" ").lowercase()
            val versionName = capabilities.versionName.orEmpty()
            val packageName = capabilities.packageName.orEmpty()
            val sdkVersion = capabilities.sdkVersion.orEmpty()
            val supportFunction = capabilities.supportFunction ?: 0
            val sockAuth = capabilities.socketServerAuth ?: false
            val mirrorOverlayTouch = capabilities.mirrorOverlayTouch ?: false
            val screenTouch = capabilities.screenTouch ?: false
            val landscapeAdaptive = capabilities.landscapeAdaptive ?: false

            fun cfdl26BaseScore(): Int {
                var points = 0
                if (versionName.startsWith("CFDL26")) points += 4
                if (packageName == "com.cfmoto.easyconnect") points += 3
                if (sockAuth) points += 2
                if (sdkVersion.isNotEmpty() && !sdkVersion.startsWith("0.")) points += 2
                if (points > 0 && supportFunction == 128) points += 1
                if (identity.contains("cfdl26") || identity.contains("motoplay")) points += 2
                return points
            }

            return when (profile) {
                CFDL26_LANDSCAPE -> cfdl26BaseScore()
                CFDL26_PORTRAIT -> {
                    val base = cfdl26BaseScore()
                    if (base == 0) {
                        0
                    } else {
                        // Deliberately does NOT award points for screenTouch/mirrorOverlayTouch
                        // being merely absent (unset defaults to false the same as "measured
                        // false") - unlike the reference, which does. Landscape is the safer
                        // tie-break default when CLIENT_INFO is too sparse to say anything
                        // positive about orientation; a wrong landscape/portrait guess is a
                        // worse outcome than a wrong dpi guess.
                        val portraitHint = identity.contains("portrait") ||
                            identity.contains("mt_x") || identity.contains("mt-x")
                        base + (if (portraitHint) 2 else 0)
                    }
                }
                CFDL26_NK_TOUCH -> {
                    val base = cfdl26BaseScore()
                    if (base == 0) {
                        0
                    } else {
                        base + (if (mirrorOverlayTouch) 1 else 0) + (if (screenTouch) 1 else 0)
                    }
                }
                CFMOTO_800NK -> {
                    var points = 0
                    if (identity.contains("800nk") || identity.contains("800 nk")) points += 4
                    if (sdkVersion.startsWith("0.9.23") && identity.contains("linux_no_package")) points += 3
                    if (identity.contains("crcp")) points += 2
                    points
                }
                CFMOTO_MTX800 -> {
                    var points = 0
                    if (
                        identity.contains("mtx800") ||
                        identity.contains("mtx 800") ||
                        identity.contains("800mt-x") ||
                        identity.contains("800 mt-x")
                    ) {
                        points += 4
                    }
                    points
                }
                LEGACY_CFDL16 -> {
                    var points = 0
                    if (identity.contains("cfdl16") || identity.contains("bike a")) points += 3
                    points
                }
                CFDL16_MOTOPLAY_LANDSCAPE -> {
                    // Only a positive, present signal counts - see the comment on
                    // CFDL26_PORTRAIT above for why an absent (default-false) flag isn't
                    // treated as evidence either way.
                    var points = 0
                    if (landscapeAdaptive) points += 1
                    points
                }
                CL_C450 -> {
                    var points = 0
                    if (identity.contains("48fb4c")) points += 4
                    if (sdkVersion.startsWith("0.9.23")) points += 1
                    points
                }
                MOTO_HUB_SIMULATOR -> {
                    var points = 0
                    if (identity.contains("moto-hub") || identity.contains("moto hub")) points += 4
                    points
                }
                GENERIC -> 0
            }
        }

        fun defaultAndroidAutoPreset(
            modelId: String?,
            capabilities: TBoxCapabilities?
        ): AndroidAutoVideoPreset = resolve(modelId, capabilities).defaultAndroidAutoPreset
    }
}
