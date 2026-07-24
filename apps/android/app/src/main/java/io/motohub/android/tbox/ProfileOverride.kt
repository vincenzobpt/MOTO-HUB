package io.motohub.android.tbox

/**
 * Manual T-Box profile override that the user can set from the Garage.
 * [AUTO] lets the app detect the profile from QR/modelId/CLIENT_INFO;
 * any other entry pins that profile regardless of detection.
 *
 * Mirrors the same profiles available in OpenCfMoto.
 */
enum class ProfileOverride(
    val key: String,
    val label: String,
    val description: String
) {
    AUTO("auto", "Auto", "Detect from the motorcycle (recommended)"),
    LEGACY_CFDL16("legacy_cfdl16", "CFDL16 / Legacy", "CFDL16 / 450SR-style non-touch"),
    CFMOTO_800NK("cfmoto_800nk", "CFMOTO 800NK", "CRCP / sdk 0.9.23.x non-touch"),
    CFMOTO_MTX800("cfmoto_mtx800", "CFMOTO MTX800", "Portrait Wi-Fi Direct dashboard, modelId 66660732"),
    CFDL26_LANDSCAPE("cfdl26_landscape", "800MT (CFDL26)", "CFDL26 MotoPlay landscape touch"),
    CFDL26_PORTRAIT("cfdl26_portrait", "1000 MT-X (CFDL26)", "CFDL26 MotoPlay portrait handlebar-primary"),
    CFDL26_NK_TOUCH("cfdl26_nk_touch", "800NK Advanced (CFDL26)", "Near-square touch panel, 720x712"),
    CFDL16_MOTOPLAY_LANDSCAPE("cfdl16_motoplay_landscape", "MotoPlay Landscape (CFDL16)", "modelId 66660742, Wi-Fi Direct, non-touch"),
    CL_C450("cl_c450", "CL-C450", "Near-square panel, 544x512"),
    MOTO_HUB_SIMULATOR("moto_hub_simulator", "MOTO-HUB Simulator", "Development simulator profile");

    fun resolve(): TBoxModelProfile? = when (this) {
        AUTO -> null
        LEGACY_CFDL16 -> TBoxModelProfile.LEGACY_CFDL16
        CFMOTO_800NK -> TBoxModelProfile.CFMOTO_800NK
        CFMOTO_MTX800 -> TBoxModelProfile.CFMOTO_MTX800
        CFDL26_LANDSCAPE -> TBoxModelProfile.CFDL26_LANDSCAPE
        CFDL26_PORTRAIT -> TBoxModelProfile.CFDL26_PORTRAIT
        CFDL26_NK_TOUCH -> TBoxModelProfile.CFDL26_NK_TOUCH
        CFDL16_MOTOPLAY_LANDSCAPE -> TBoxModelProfile.CFDL16_MOTOPLAY_LANDSCAPE
        CL_C450 -> TBoxModelProfile.CL_C450
        MOTO_HUB_SIMULATOR -> TBoxModelProfile.MOTO_HUB_SIMULATOR
    }

    companion object {
        fun byKey(key: String?): ProfileOverride =
            entries.firstOrNull { it.key == key } ?: AUTO
    }
}
