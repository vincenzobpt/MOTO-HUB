// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

/**
 * Manual T-Box profile override that the user can set from the Garage.
 * [AUTO] lets the app detect the profile from QR/modelId/CLIENT_INFO;
 * any other entry pins that profile regardless of detection.
 *
 * [GENERIC] is the one entry that pins *less* rather than more. Detection can only recognise
 * dashboards it has seen, so a rider whose dashboard scores against the wrong profile has no way
 * back to the neutral defaults without it — [AUTO] would just re-run the same mistaken match.
 */
enum class ProfileOverride(
    val key: String,
    val label: String,
    val description: String,
    /**
     * This entry exists to answer one open question about one dashboard, not to describe a
     * motorcycle anybody owns.
     *
     * Carried on the entry rather than as a list somewhere in the UI because the list is what
     * rots: the next experiment gets added here, the list is not updated, and it is offered to a
     * rider as if it were their bike. A screen that suggests profiles has to be able to say
     * "this one is a guess" without knowing which guesses exist.
     */
    val experimental: Boolean = false,
    /**
     * Whether this is something to put in front of a rider at all. Only the development
     * simulator says no - it pairs with software running on a laptop, and picking it on a
     * motorcycle can only fail.
     */
    val riderSelectable: Boolean = true
) {
    AUTO("auto", "Auto", "Detect from the motorcycle (recommended)"),
    GENERIC("generic", "Generic dashboard", "Neutral defaults for a dashboard that is not recognised"),
    LEGACY_CFDL16("legacy_cfdl16", "CFDL16 / Legacy", "CFDL16 / 450SR-style non-touch"),
    CFMOTO_800NK("cfmoto_800nk", "CFMOTO 800NK", "CRCP / sdk 0.9.23.x non-touch"),
    CFMOTO_MTX800("cfmoto_mtx800", "CFMOTO MTX800", "Portrait Wi-Fi Direct dashboard, modelId 66660732"),
    CFDL26_LANDSCAPE("cfdl26_landscape", "800MT (CFDL26)", "CFDL26 MotoPlay landscape touch"),
    CFDL26_PORTRAIT("cfdl26_portrait", "1000 MT-X (CFDL26)", "CFDL26 MotoPlay portrait handlebar-primary"),
    CFDL26_NK_TOUCH("cfdl26_nk_touch", "800NK Advanced (CFDL26)", "Near-square touch panel, 720x712"),
    CFDL16_MOTOPLAY_LANDSCAPE("cfdl16_motoplay_landscape", "MotoPlay Landscape (CFDL16)", "modelId 66660742, Wi-Fi Direct, non-touch"),
    CL_C450("cl_c450", "CL-C450", "Near-square panel, 544x512"),
    ZONTES_368G_TEST(
        "zontes_368g_test",
        "Zontes 368G (test)",
        "Experiment for JCDZ dashes stuck on the QR page: indexed framing + 1s GOP",
        experimental = true
    ),
    ZONTES_368G_TEST_B(
        "zontes_368g_test_b",
        "Zontes 368G (test B)",
        "Same experiment with plain framing instead: the dash's ext byte decides, plus a 1s GOP",
        experimental = true
    ),
    VOGE_TEST(
        "voge_test",
        "Voge (test)",
        "Experiment for Voge dashes that reboot mid-ride: 1s plain-IDR GOP instead of all-intra",
        experimental = true
    ),
    QJ_SRK921_RR(
        "qj_srk921_rr",
        "QJ SRK921 RR (test)",
        "Experiment for a dash that takes every frame and shows none: 10 fps on a 2s GOP",
        experimental = true
    ),
    KOVE_800X("kove_800x", "KOVE 800X (ThinkerRide)", "BLE-paired ThinkerRide dash, 600x1024 portrait"),
    KOVE_450_RALLY(
        "kove_450_rally",
        "KOVE 450 Rally (ThinkerRide)",
        "Same BLE-paired ThinkerRide dash, 1280x640 landscape panel"
    ),
    MORINI_XCAPE_1200(
        "morini_xcape_1200",
        "X-Cape 1200 (Yunmo)",
        "Moto Morini X-Cape 1200 SoftAP dash on Yunmo :8200 (not the 649/700/Seiemmezzo)"
    ),
    MORINI_XCAPE_1200_MIRROR(
        "morini_xcape_1200_mirror",
        "X-Cape 1200 (mirror)",
        "Same dash, asked for plain mirroring instead of the navigation display mode",
        experimental = true
    ),
    MORINI_XCAPE_1200_JPEG(
        "morini_xcape_1200_jpeg",
        "Moto Morini X-Cape 1200 (JPEG)",
        "Sends still images instead of video, the way the bike's own app does. Try this if the dash stays black.",
        experimental = true
    ),
    KOVE_625X(
        "kove_625x",
        "KOVE 625X (JPEG)",
        "Wi-Fi dash speaking the X-Cape 1200 protocol with still images; recognised by its KY_ADV_ network name, so Auto normally finds it by itself"
    ),
    MOTO_HUB_SIMULATOR(
        "moto_hub_simulator",
        "MOTO-HUB Simulator",
        "Development simulator profile",
        riderSelectable = false
    );

    fun resolve(): TBoxModelProfile? = when (this) {
        AUTO -> null
        GENERIC -> TBoxModelProfile.GENERIC
        LEGACY_CFDL16 -> TBoxModelProfile.LEGACY_CFDL16
        CFMOTO_800NK -> TBoxModelProfile.CFMOTO_800NK
        CFMOTO_MTX800 -> TBoxModelProfile.CFMOTO_MTX800
        CFDL26_LANDSCAPE -> TBoxModelProfile.CFDL26_LANDSCAPE
        CFDL26_PORTRAIT -> TBoxModelProfile.CFDL26_PORTRAIT
        CFDL26_NK_TOUCH -> TBoxModelProfile.CFDL26_NK_TOUCH
        CFDL16_MOTOPLAY_LANDSCAPE -> TBoxModelProfile.CFDL16_MOTOPLAY_LANDSCAPE
        CL_C450 -> TBoxModelProfile.CL_C450
        ZONTES_368G_TEST -> TBoxModelProfile.ZONTES_368G_TEST
        ZONTES_368G_TEST_B -> TBoxModelProfile.ZONTES_368G_TEST_B
        VOGE_TEST -> TBoxModelProfile.VOGE_TEST
        QJ_SRK921_RR -> TBoxModelProfile.QJ_SRK921_RR
        KOVE_800X -> TBoxModelProfile.KOVE_800X
        KOVE_450_RALLY -> TBoxModelProfile.KOVE_450_RALLY
        MORINI_XCAPE_1200 -> TBoxModelProfile.MORINI_XCAPE_1200
        MORINI_XCAPE_1200_MIRROR -> TBoxModelProfile.MORINI_XCAPE_1200_MIRROR
        MORINI_XCAPE_1200_JPEG -> TBoxModelProfile.MORINI_XCAPE_1200_JPEG
        KOVE_625X -> TBoxModelProfile.KOVE_625X
        MOTO_HUB_SIMULATOR -> TBoxModelProfile.MOTO_HUB_SIMULATOR
    }

    companion object {
        fun byKey(key: String?): ProfileOverride =
            entries.firstOrNull { it.key == key } ?: AUTO
    }
}
