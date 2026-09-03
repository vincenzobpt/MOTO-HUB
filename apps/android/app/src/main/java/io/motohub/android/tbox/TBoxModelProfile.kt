// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import io.motohub.android.androidauto.AndroidAutoDisplayMode
import io.motohub.android.androidauto.AndroidAutoVideoPreset
import io.motohub.android.androidauto.TBoxScreenMargins

/**
 * The KOVE 450 Rally's landscape TFT, in the one place [TBoxModelProfile.KOVE_450_RALLY] needs it
 * twice — the declared stream area and the bitrate derived from it must never drift apart. Not in
 * ThinkerRideProtocol: that object holds the wire, and this wire never carries a panel size.
 */
private const val KOVE_450_RALLY_VIDEO_WIDTH = 1280
private const val KOVE_450_RALLY_VIDEO_HEIGHT = 640

/**
 * Pseudo modelId of the KOVE 625X. Its QR carries no model information, so the id is stamped
 * from the dash's network name ([TBoxModelProfile.modelIdForSsid]) rather than read from a code.
 * Top-level like the 450 Rally geometry above: an enum entry cannot reach its own companion.
 */
internal const val KOVE_625X_PROVISIONING_MODEL_ID = "KOVE-625X"

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
    /**
     * Compatibility geometry used only when the T-Box omits its live VideoArea. This is kept
     * separate from the Android Auto source preset because the AA source and physical T-Box
     * projection canvas are not interchangeable.
     */
    val fallbackTBoxVideoArea: TBoxEvent.VideoArea? = null,
    /** Matches OpenCfMoto's requiresSockServerAuth / enableSockServerAuth flag. */
    val requiresSockAuth: Boolean = false,
    /** Capability bitmask returned by the MediaCtrlScreenConf response. */
    val advertisedSupportFunction: Int = 0,
    /**
     * Whether the dash's own `supportExtendProtocol` byte may pick the video frame format
     * (RideDaemonTransport's `plainVideoFramingAllowed`): ext=0 drops the 4-byte frame index,
     * ext=1 keeps it. Every recognised unit streams fine on the indexed framing it already
     * displays, so this stays off for all of them; only [GENERIC] — which by definition claims
     * nothing about the dash — and a framing experiment may hand the choice to the firmware.
     */
    val allowsPlainVideoFraming: Boolean = false,
    /**
     * Firmware closes EasyConn unless both reverse PXC channel sockets receive traffic. Defaults
     * to false only so that a profile written for a dash known not to need it can stay silent;
     * anything unidentified goes through [GENERIC], which turns it on.
     */
    val requiresProactivePxcHeartbeat: Boolean = false,
    /**
     * GOP length in seconds for the TFT AVC encoder; 0 keeps the all-intra stream every dash has
     * always received. Only compatibility-experiment profiles set this, so it can never change
     * the wire format of a dash that streams fine today.
     */
    val encoderKeyframeIntervalSeconds: Int = 0,
    /**
     * Capture frame rate for this dash, or null to keep the negotiated one. Only set it for a dash
     * whose OEM app is known to run slower — a lower rate is a real quality loss everywhere else.
     */
    val encoderFrameRate: Int? = null,
    /**
     * Base bitrate for this dash before the rider's quality setting scales it, or null to keep the
     * negotiated one.
     */
    val encoderBitRate: Int? = null,
    /**
     * Keep the GOP stream on plain periodic IDR keyframes instead of intra refresh. For a dash
     * whose decoder mishandles intra-refresh streams this is the difference between working and
     * freezing: a KOVE 800X froze hard (ignition-cycle hard) 15-30s into every intra-refresh
     * session, while KoveMirror's plain 1s-IDR stream runs indefinitely on the same panel.
     */
    val encoderPlainGopWithoutIntraRefresh: Boolean = false,
    /**
     * Encode exactly [fallbackTBoxVideoArea]'s dimensions instead of the 16-aligned canvas.
     * ThinkerRide declares the stream size to the dash in a header, and the reference app
     * encodes precisely what it declares (600x1024); we used to declare 600 and stream 592.
     * Dimensions must be even — H.264 4:2:0 needs that; the codec pads and crops internally.
     */
    val encoderUsesExactVideoArea: Boolean = false,
    /**
     * Density for the captured virtual display, or null to use the phone's own. The phone's density
     * is right whenever the dash shows a mirror of the phone, and wrong whenever the dash drives a
     * layout of its own at a fixed size: a 1024x464 panel rendered at a modern phone's ~420dpi gets
     * UI sized for a screen three times its width.
     */
    val virtualDisplayDpi: Int? = null,
    /**
     * Yunmo only: capture the display as JPEG stills instead of an H.264 stream.
     *
     * The OEM app for the X-Cape 1200 does exactly this and never runs its own H.264 path - that
     * code exists but nothing in the APK can reach it. A dash that acknowledges every frame while
     * painting none is what you would expect from feeding it a format it does not decode, and no
     * amount of H.264 parameter tuning has moved it in two independent implementations.
     *
     * Off everywhere else, and it can only ever be reached through a Yunmo profile a rider pinned
     * by hand, so no dash that streams today can land on this path.
     */
    val yunmoJpegVideo: Boolean = false,
    /** Which wire protocol the dash speaks; routes the session to the matching transport. */
    /**
     * Wrap every BLE command to a ThinkerRide dash in the OEM's 104-byte `byteCat` frame
     * instead of writing bare JSON (see [ThinkerRideProtocol.byteCatFrames]).
     *
     * Per profile and off by default on purpose: bare JSON is field-proven on a KOVE 800X and
     * framing it everywhere would break the one rider known to stream, while the SiQi firmware
     * on the 450 Rally reads nothing we send unframed.
     */
    val bleUsesByteCatFraming: Boolean = false,
    val transportFamily: TBoxTransportFamily = TBoxTransportFamily.EASYCONN,
    /**
     * Yunmo only: use the OEM map-navigation display path (A0 cmd=6, with each keyframe split into
     * standalone SPS / PPS / coded-picture frames) instead of a plain mirror. This is the newest,
     * still-unconfirmed compatibility experiment for the X-Cape 1200, so it stays off unless a
     * profile opts in — a plain mirror is the safe default for any Yunmo dash.
     */
    val yunmoMapNavExperiment: Boolean = false,
    /**
     * Network-name prefixes that identify this dashboard when nothing else does. Only for a
     * dash whose QR carries no modelId and whose SSID is the one stable thing about it (the
     * KOVE 625X's `KY_ADV_…`); a prefix earns the profile's first [modelIds] entry through
     * [modelIdForSsid]. Empty for every profile a code or CLIENT_INFO can name.
     */
    val ssidPrefixes: Set<String> = emptySet()
) {
    MOTO_HUB_SIMULATOR(
        key = "moto_hub_simulator",
        displayName = "MOTO-HUB T-Box Simulator",
        modelIds = setOf("MOTO-HUB-SIMULATOR"),
        mapTilesRequireCellular = false,
        fallbackTBoxVideoArea = TBoxEvent.VideoArea(800, 480),
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
        fallbackTBoxVideoArea = TBoxEvent.VideoArea(460, 750),
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
        fallbackTBoxVideoArea = TBoxEvent.VideoArea(720, 712),
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
        fallbackTBoxVideoArea = TBoxEvent.VideoArea(544, 512),
        requiresSockAuth = false,
        advertisedSupportFunction = 0
    ),
    /**
     * Compatibility experiment for the Zontes 368G (2025, PKE firmware 1.25.x, HUName JCDZ34-*):
     * the dash completes the whole EasyConn handshake, requests 1280x535, sends STREAM_START and
     * pulls frames, yet its UI never leaves the pairing-QR page (field log 2026-08-03). Manual
     * selection only - [score] never claims it, so no dash ever lands here by detection. Two
     * deltas versus [GENERIC], both suspected requirements of that firmware's decoder:
     * indexed video framing (any non-GENERIC profile keeps the frame index, see
     * RideDaemonTransport's plainVideoFramingAllowed) and a 1s GOP instead of all-intra.
     */
    ZONTES_368G_TEST(
        key = "zontes_368g_test",
        displayName = "Zontes 368G (test)",
        modelIds = emptySet(),
        mapTilesRequireCellular = true,
        supportsScreenTouch = false,
        defaultAndroidAutoPreset = AndroidAutoVideoPreset.LANDSCAPE_1280X720,
        fallbackTBoxVideoArea = TBoxEvent.VideoArea(1280, 535),
        requiresSockAuth = false,
        advertisedSupportFunction = 0,
        requiresProactivePxcHeartbeat = true,
        encoderKeyframeIntervalSeconds = 1
    ),
    /**
     * Second half of the same experiment, after the tester ran [ZONTES_368G_TEST] on the bike
     * (field log 2026-08-11, app 1.1.58). That profile moved two things at once and the log says
     * one of them is wrong: with the frame index kept, the dash — which reports
     * `supportExtendProtocol=0` — closed the video socket itself after 6s (94 frames) and 17s
     * (321 frames), where plain framing had held the full 30s of the dash's own no-video
     * timeout. So the index is not what this firmware wants, and the 1s GOP is the only delta
     * left untested. This profile is [GENERIC]'s wire format — the ext byte decides the framing —
     * with the 1s GOP on top, so the two experiments can be compared one variable apart.
     * Manual selection only, like its sibling: [score] never claims it.
     */
    ZONTES_368G_TEST_B(
        key = "zontes_368g_test_b",
        displayName = "Zontes 368G (test B)",
        modelIds = emptySet(),
        mapTilesRequireCellular = true,
        supportsScreenTouch = false,
        defaultAndroidAutoPreset = AndroidAutoVideoPreset.LANDSCAPE_1280X720,
        fallbackTBoxVideoArea = TBoxEvent.VideoArea(1280, 535),
        requiresSockAuth = false,
        advertisedSupportFunction = 0,
        allowsPlainVideoFraming = true,
        requiresProactivePxcHeartbeat = true,
        encoderKeyframeIntervalSeconds = 1,
        // The 1s GOP IS the experiment, so it must survive whatever the tester's phone can do.
        // Since 1.1.74 a requested GOP falls back to all-intra on a codec without intra refresh
        // (the MTX800 green-macroblock fix), which on the wrong phone would silently turn this
        // profile back into GENERIC and test nothing. Plain periodic IDRs are also exactly what
        // the one Zontes known to work elsewhere is fed - zanderp's open-cfmoto encodes a 1s
        // GOP with no intra refresh at all, and its 125X is community-confirmed.
        encoderPlainGopWithoutIntraRefresh = true
    ),
    /**
     * Compatibility experiment for the Voge dashes (flavor 51, channel 37504, 592x752 portrait
     * panel) whose riders report the clock falling back to 00:00 after a ride. The clock is the
     * symptom, not the fault: the field logs of 2026-08-17 show the dash *rebooting*.
     * `currentHUTime` tracked wall time to within 10ms over 87s across three back-to-back
     * sessions - so it is a real clock, not a drifting counter - and then zeroed twice. The
     * second zero landed 3.4s after the dash stopped answering PXC mid-session, and the
     * recovery attempt that followed found the dash's own Wi-Fi Direct group gone. Two bikes,
     * two phones, one shape: 45-60s of video and the panel goes down.
     *
     * These dashes land on [GENERIC], which is all-intra - 592x752@30 at 4 Mbps where every
     * frame is an IDR, and a keyframe is split into three wire frames. [KOVE_800X] records the
     * same failure from the other side: that panel froze ignition-cycle hard 15-30s into every
     * session until it was given a plain 1s-IDR stream. So this profile is [GENERIC]'s wire
     * format with one video delta, the stream the KOVE needed: a 1s GOP on plain periodic IDR
     * instead of all-intra. Everything else that touches the wire - plain framing decided by
     * the dash's own ext byte, the proactive PXC heartbeat, no sock auth - is copied from
     * [GENERIC] verbatim, so a log that still shows the reboot rules the video stream out
     * instead of leaving two variables in play.
     *
     * The geometry is not a third variable: these dashes report their 592x752 area live and it
     * is learned per SSID, so the declared portrait preset only reproduces what the saved
     * geometry already resolves to (720x1280, field log 2026-08-17). Declaring it matters
     * anyway, because a non-GENERIC profile's preset counts as validated evidence about the
     * hardware - see [hasValidatedAndroidAutoPreset] - and inheriting GENERIC's 800x480
     * landscape would state the opposite of what the panel is.
     *
     * Manual selection only: [score] never claims it, so no Voge that streams fine today can
     * land here by detection.
     */
    VOGE_TEST(
        key = "voge_test",
        displayName = "Voge (test)",
        modelIds = emptySet(),
        mapTilesRequireCellular = true,
        defaultAndroidAutoPreset = AndroidAutoVideoPreset.PORTRAIT_720X1280,
        fallbackTBoxVideoArea = TBoxEvent.VideoArea(592, 752),
        requiresSockAuth = false,
        advertisedSupportFunction = 0,
        allowsPlainVideoFraming = true,
        requiresProactivePxcHeartbeat = true,
        encoderKeyframeIntervalSeconds = 1,
        encoderPlainGopWithoutIntraRefresh = true
    ),
    /**
     * The QJ SRK921 RR's 5-inch dash: an 800x352 video band on a Carbit-licensed EasyConn stack
     * (`flavor 51`, `channel 37303`, `package_name linux_no_package`, `sdkVersion 0.9.23.1`) that
     * takes every frame it is offered and paints none of them.
     *
     * This is the profile [TBoxWireLadder] could not find. Rider 1d316f4b/bffd0679 walked the
     * whole ladder twice - all four rungs, both framings, all-intra and 1s GOP alike - and every
     * rung ended the same way: the socket healthy, `frameTimeouts=0`, `frameRejections=0`, over a
     * thousand frames accepted in seventy seconds, and a rider looking at nothing but the dash's
     * Wi-Fi icon. Two facts narrow what is left. The blackout survives the source: Android Auto
     * and the Ride Dashboard, which share nothing but this transport, are equally blank. And the
     * all-intra rungs do not merely fail, they take the link down - the dash drops its own AP
     * 4s, 27s and 46s into three consecutive sessions at -17dBm, which is not coverage, it is
     * firmware giving up on a stream it cannot keep up with.
     *
     * So the delta here is the one variable the ladder never had a rung for: the *rate*. The
     * reference fork's profile for the other Carbit `flavor 51` units sends 10 fps on a 2s GOP at
     * 2 Mbps rather than the 30 fps all-intra [GENERIC] guesses, and a dash that acknowledges
     * everything while painting nothing is what an over-fed decoder looks like from this side.
     * [encoderPlainGopWithoutIntraRefresh] keeps the keyframes plain: intra refresh is the other
     * thing this family has never been shown to decode, and pinning both at once would leave two
     * variables in play should the next log still be black.
     *
     * Claimed by modelId alone, and deliberately: `37303` belongs to this one dashboard across the
     * whole collector, while `flavor 51` also covers a Voge Valico and two further rebadges that
     * must not be moved onto a 10 fps stream on the strength of a shared licence. [score] returns
     * 0 for the same reason - CLIENT_INFO must never carry this profile to a dash whose QR did
     * not name it.
     */
    QJ_SRK921_RR(
        key = "qj_srk921_rr",
        displayName = "QJ SRK921 RR (test)",
        modelIds = setOf("37303"),
        mapTilesRequireCellular = true,
        supportsScreenTouch = false,
        defaultAndroidAutoPreset = AndroidAutoVideoPreset.LANDSCAPE_800X480,
        fallbackTBoxVideoArea = TBoxEvent.VideoArea(800, 352),
        requiresSockAuth = false,
        // Echo what the dash reports rather than GENERIC's 0, as the reference fork does for every
        // unit in this family; it is the only supportFunction this firmware has ever been seen to
        // send.
        advertisedSupportFunction = 128,
        // Both copied from GENERIC verbatim: the ladder already proved neither is the variable
        // here (indexed and plain framing were each denied twice), so changing them alongside the
        // rate would only make the next log harder to read.
        allowsPlainVideoFraming = true,
        requiresProactivePxcHeartbeat = true,
        encoderKeyframeIntervalSeconds = 2,
        encoderFrameRate = 10,
        encoderBitRate = 2_000_000,
        encoderPlainGopWithoutIntraRefresh = true
    ),
    /**
     * KOVE 800X (and, until they earn their own profiles, other ThinkerRide-family dashes): a
     * 600x1024 portrait TFT paired over BLE, reached through [TBoxTransportFamily.THINKERRIDE].
     * The ThinkerRide protocol never reports a panel size — the phone declares the stream
     * geometry and the dash scales — so [fallbackTBoxVideoArea] IS the negotiated area here,
     * and a future KOVE model with a different panel is a new profile with a different area
     * (pinned via [ProfileOverride] until its QR can be told apart). The modelId is the
     * pseudo-id the ThinkerRide QR dialect records, since the QR itself carries no model
     * information. The stream mirrors the reference implementation EXACTLY — 600x1024 as
     * declared in the video header, plain 1s-IDR GOP (never intra refresh), ~1.8 Mbps
     * (KoveMirror's width*height*3) — because deviating froze the dash: field logs 2026-08-13
     * show every intra-refresh session (592x1024 on the wire, IDR every 10s, 2.5 Mbps) killing
     * the panel 15-30s in, on two riders' bikes, while KoveMirror ran clean on the same
     * hardware. Detection scoring never claims this profile: CLIENT_INFO is an EasyConn
     * concept and does not exist on this wire.
     */
    KOVE_800X(
        key = "kove_800x",
        displayName = "KOVE 800X (ThinkerRide)",
        modelIds = setOf(ThinkerRideProtocol.PROVISIONING_MODEL_ID),
        mapTilesRequireCellular = true,
        defaultAndroidAutoDisplayMode = AndroidAutoDisplayMode.FILL,
        supportsScreenTouch = false,
        defaultAndroidAutoPreset = AndroidAutoVideoPreset.PORTRAIT_720X1280,
        fallbackTBoxVideoArea = TBoxEvent.VideoArea(
            ThinkerRideProtocol.DEFAULT_VIDEO_WIDTH,
            ThinkerRideProtocol.DEFAULT_VIDEO_HEIGHT
        ),
        requiresSockAuth = false,
        advertisedSupportFunction = 0,
        encoderKeyframeIntervalSeconds = 1,
        encoderBitRate = ThinkerRideProtocol.DEFAULT_VIDEO_WIDTH *
            ThinkerRideProtocol.DEFAULT_VIDEO_HEIGHT * 3,
        encoderPlainGopWithoutIntraRefresh = true,
        encoderUsesExactVideoArea = true,
        transportFamily = TBoxTransportFamily.THINKERRIDE
    ),
    /**
     * KOVE 450 Rally (2022 dash, SiQi firmware `SV=3.0.x`): the same ThinkerRide wire as
     * [KOVE_800X] driving a **1280x640 landscape** panel instead of a 600x1024 portrait one.
     * The protocol never reports a panel size — the phone declares it — so a rider on this bike
     * pinned to [KOVE_800X] streams a portrait image into a landscape TFT.
     *
     * Geometry, bitrate and GOP come from the ttarlov/kove-dash reverse-engineering of the OEM
     * `oversea.whbluestar.thinkerride` app plus its own working projection on this exact bike
     * (`refs/kove-dash/proto-poc/PROTOCOL.md`): 1280x640 confirmed rendering, 30fps, 1s GOP,
     * `3 * width * height` — the same `r=3` bitrate tier [KOVE_800X] uses, which is why both
     * profiles compute it the same way rather than sharing a constant.
     *
     * **[modelIds] is deliberately empty even though this dash answers the same QR.** The
     * ThinkerRide QR carries only an SSID and a password, so both KOVE profiles would claim
     * [ThinkerRideProtocol.PROVISIONING_MODEL_ID] — and [fromModelId] resolves an ambiguous
     * modelId to [GENERIC], which for this family is not a milder answer but a broken one:
     * GENERIC is an EasyConn profile, so every existing KOVE rider would silently lose the
     * ThinkerRide transport entirely. A second profile on this wire can therefore only ever be
     * a manual pin, until something on the wire tells the two panels apart.
     */
    KOVE_450_RALLY(
        key = "kove_450_rally",
        displayName = "KOVE 450 Rally (ThinkerRide)",
        modelIds = emptySet(),
        mapTilesRequireCellular = true,
        defaultAndroidAutoDisplayMode = AndroidAutoDisplayMode.FILL,
        supportsScreenTouch = false,
        defaultAndroidAutoPreset = AndroidAutoVideoPreset.LANDSCAPE_1280X720,
        fallbackTBoxVideoArea = TBoxEvent.VideoArea(
            KOVE_450_RALLY_VIDEO_WIDTH,
            KOVE_450_RALLY_VIDEO_HEIGHT
        ),
        requiresSockAuth = false,
        advertisedSupportFunction = 0,
        encoderKeyframeIntervalSeconds = 1,
        encoderBitRate = KOVE_450_RALLY_VIDEO_WIDTH * KOVE_450_RALLY_VIDEO_HEIGHT * 3,
        encoderPlainGopWithoutIntraRefresh = true,
        encoderUsesExactVideoArea = true,
        bleUsesByteCatFraming = true,
        transportFamily = TBoxTransportFamily.THINKERRIDE
    ),
    /**
     * Where every dashboard no other profile claims ends up, whatever badge is on the tank. The
     * geometry here is a starting point, not a measurement: once the dash reports a live video
     * area it is learned per SSID and drives the next session (see TBoxDisplayGeometryStore).
     */
    GENERIC(
        key = "generic",
        displayName = "Generic EasyConn dashboard",
        modelIds = emptySet(),
        mapTilesRequireCellular = true,
        // Existing generic compatibility profile; never treated as live geometry.
        fallbackTBoxVideoArea = TBoxEvent.VideoArea(800, 480),
        // On by default here because GENERIC is what every dashboard we cannot identify lands
        // on - every non-CFMOTO brand included - and that is exactly the population whose
        // firmware behaviour is unknown. A Zontes dash (package tayo.com.ZontesIntelligence,
        // modelId 21334, field log 2026-07-30) sent its opening PXC burst, requested the
        // capture area, sent STREAM_START and one heartbeat, then went silent and closed the
        // socket 96s later while the app streamed 1857 frames into it and told the rider that
        // projection was live. The reference implementation heartbeats unconditionally and
        // documents why: some firmware never initiates heartbeats and then drops the idle PXC
        // socket. Sending a keepalive to a dash that does not need one is harmless; not
        // sending one to a dash that does costs the whole session.
        requiresProactivePxcHeartbeat = true,
        // A dash no profile claims is the only one whose supportExtendProtocol byte we trust
        // over our own default; see [allowsPlainVideoFraming].
        allowsPlainVideoFraming = true
    ),
    /**
     * Moto Morini X-Cape 1200 — the one dash known to speak Yunmo over its `ML*` SoftAP at
     * 192.168.4.1:8200 instead of EasyConn. Reached through [TBoxTransportFamily.YUNMO].
     *
     * [modelIds] is deliberately empty: the pairing QR's `ProductID=00297` is shared with the
     * X-Cape 649 / 700 and the Seiemmezzo, which speak EasyConn, so keying this profile off that
     * id would break those bikes. It is reachable by a manual [ProfileOverride] pin, or by the
     * Yunmo probe in SelectingTBoxTransport when EasyConn discovery finds nothing.
     *
     * Every value here is now read from the OEM app rather than inferred. Ride MO 1.0.23's
     * GoogleMediaCodecH264LiveThread - the class its European build actually runs, decompiled
     * 2026-08-12 - encodes at 10 fps, 2 Mbps, with a **2-second GOP**, and its Trans_Ins_Ex writes
     * a media header whose only non-zero field is the fixed type byte.
     *
     * The GOP is the correction that matters. This profile was all-intra on the reasoning that a
     * dropped P-frame corrupts the picture until the next keyframe; but all-intra makes every
     * frame a keyframe, and every keyframe is split into three wire frames, so the dash was being
     * sent roughly three times the traffic in a shape the OEM never produces. Field sessions with
     * that stream had the dash acknowledging every frame and painting none of them.
     *
     * The dpi is not cosmetic: it is the density of the OEM `NaviVirtualDisplay`, and rendering
     * this canvas at a modern phone's own density sizes the UI for a screen three times as wide.
     */
    MORINI_XCAPE_1200(
        key = "morini_xcape_1200",
        displayName = "Moto Morini X-Cape 1200 (Yunmo)",
        modelIds = emptySet(),
        mapTilesRequireCellular = true,
        supportsScreenTouch = false,
        defaultAndroidAutoPreset = AndroidAutoVideoPreset.LANDSCAPE_800X480,
        fallbackTBoxVideoArea = TBoxEvent.VideoArea(800, 480),
        requiresSockAuth = false,
        advertisedSupportFunction = 0,
        encoderKeyframeIntervalSeconds = 2,
        encoderFrameRate = 10,
        encoderBitRate = 2_000_000,
        virtualDisplayDpi = 187,
        transportFamily = TBoxTransportFamily.YUNMO,
        yunmoMapNavExperiment = true
    ),
    /**
     * X-Cape 1200 driving the dash's plain mirror path (`B0{7}`/`A0{7}`) instead of map-nav.
     *
     * Never tried on this bike: map-nav was turned on in 1.1.52 from an owner's report that the
     * OEM app always drives the navigation path, and that is true — but the OEM drives it while
     * sending *its own map*. A dash told to enter map-nav may well expect a navigation stream with
     * that mode's semantics and decline to paint arbitrary mirrored content, which is exactly what
     * we push (Android Auto, or the Ride Dashboard). A separate prototype for this motorcycle
     * sends no display command at all and its author reports a working connection, which is the
     * other end of the same question: whether this firmware paints without being put into map-nav.
     */
    /**
     * Moto Morini X-Cape 1200 over Yunmo, sending **JPEG stills** rather than H.264.
     *
     * The one wire variable neither this project nor the public reference had ever tested. Ride MO
     * 1.0.23 - the app that does paint this dash - streams JPEG: `deviceStreamType` defaults to
     * `Image` and nothing in the APK ever sets it, so its H.264 live threads are unreachable. Both
     * implementations derived their encoder settings from those unreachable classes and both ended
     * with a dash that acknowledges every frame and shows nothing.
     *
     * Encoder settings are irrelevant here - there is no encoder. The geometry stays what the vc60
     * dumpsys of the OEM measured, 1024x464 at 187 dpi, which the dash reports for itself anyway.
     *
     * There is a cheap way to tell whether this is working even if the screen stays dark: the JPEG
     * path writes a real frame id into the header, where the H.264 path leaves it zero. Acks that
     * come back with **non-zero** ids would be the first genuine sign of life either project has
     * had from this dash.
     */
    MORINI_XCAPE_1200_JPEG(
        key = "morini_xcape_1200_jpeg",
        displayName = "Moto Morini X-Cape 1200 (JPEG, experimental)",
        modelIds = emptySet(),
        mapTilesRequireCellular = true,
        supportsScreenTouch = false,
        defaultAndroidAutoPreset = AndroidAutoVideoPreset.LANDSCAPE_800X480,
        fallbackTBoxVideoArea = TBoxEvent.VideoArea(800, 480),
        requiresSockAuth = false,
        advertisedSupportFunction = 0,
        encoderFrameRate = 10,
        virtualDisplayDpi = 187,
        transportFamily = TBoxTransportFamily.YUNMO,
        yunmoMapNavExperiment = true,
        yunmoJpegVideo = true
    ),

    MORINI_XCAPE_1200_MIRROR(
        key = "morini_xcape_1200_mirror",
        displayName = "Moto Morini X-Cape 1200 (mirror)",
        modelIds = emptySet(),
        mapTilesRequireCellular = true,
        supportsScreenTouch = false,
        defaultAndroidAutoPreset = AndroidAutoVideoPreset.LANDSCAPE_800X480,
        fallbackTBoxVideoArea = TBoxEvent.VideoArea(800, 480),
        requiresSockAuth = false,
        advertisedSupportFunction = 0,
        encoderFrameRate = 10,
        encoderBitRate = 2_000_000,
        virtualDisplayDpi = 187,
        encoderKeyframeIntervalSeconds = 2,
        transportFamily = TBoxTransportFamily.YUNMO,
        yunmoMapNavExperiment = false
    ),

    /**
     * KOVE 625X (2026) — a Wi-Fi SoftAP dash (`KY_ADV_…`) that speaks Yunmo on :8200 like the
     * X-Cape 1200, NOT the BLE-provisioned ThinkerRide chip of the 800X / 450 Rally. Field-proven
     * 2026-09-03 (HONOR MBH-N49, 1.1.110): the dash reports a 640x480 canvas, confirms map-nav,
     * never acknowledges one H.264 frame in four sessions (93% refused) and acknowledges EVERY
     * JPEG still at 7-8 fps in both Android Auto and the Ride Dashboard — it keeps up with the
     * 10 fps tick at quality 60, where the X-Cape takes 2-5 stills a second.
     *
     * Its QR carries no modelId, so the pseudo id is stamped from the network name
     * ([ssidPrefixes] via [modelIdForSsid]) at pairing time and when the garage is loaded.
     * Without it the dash resolved to GENERIC, spent 33 s in EasyConn discovery, and then fell
     * back to the X-Cape's H.264 profile that paints nothing here. Geometry comes from the
     * dash's own dim-query, so [fallbackTBoxVideoArea] only matters before the first reply.
     * The dpi is the X-Cape's, because the profile the rider proved this on carried it.
     */
    KOVE_625X(
        key = "kove_625x",
        displayName = "KOVE 625X (Yunmo, JPEG)",
        modelIds = setOf(KOVE_625X_PROVISIONING_MODEL_ID),
        mapTilesRequireCellular = true,
        supportsScreenTouch = false,
        defaultAndroidAutoPreset = AndroidAutoVideoPreset.LANDSCAPE_800X480,
        fallbackTBoxVideoArea = TBoxEvent.VideoArea(640, 480),
        requiresSockAuth = false,
        advertisedSupportFunction = 0,
        encoderFrameRate = 10,
        virtualDisplayDpi = 187,
        transportFamily = TBoxTransportFamily.YUNMO,
        yunmoMapNavExperiment = true,
        yunmoJpegVideo = true,
        ssidPrefixes = setOf("KY_ADV_")
    );

    companion object {
        /**
         * The pseudo modelId a dashboard earns from its network name alone, or null when no
         * profile claims that prefix. For a dash whose QR carries no modelId (the KOVE 625X)
         * the SSID is the only thing that names the model before the first connect.
         * Case-insensitive, as every SSID comparison in the app already is.
         */
        fun modelIdForSsid(ssid: String?): String? {
            val name = ssid?.trim().orEmpty()
            if (name.isEmpty()) return null
            return entries.firstOrNull { profile ->
                profile.ssidPrefixes.any { name.startsWith(it, ignoreCase = true) }
            }?.modelIds?.firstOrNull()
        }

        /**
         * The profile a remembered transport family routes to. A profile the modelId itself
         * recognises wins when it belongs to that family — the KOVE 625X must not be handed
         * the X-Cape's H.264 settings just because both speak Yunmo — otherwise the family's
         * first entry, which is what the shortcut always picked.
         */
        fun shortcutFor(family: TBoxTransportFamily, modelId: String?): TBoxModelProfile? {
            val recognised = fromModelId(modelId)
            if (recognised != GENERIC && recognised.transportFamily == family) return recognised
            return entries.firstOrNull { it.transportFamily == family }
        }

        /**
         * The profile whose [key] is [key], or null for an unknown one.
         *
         * Exists so a profile can be named across a process boundary. CORE resolves the real
         * profile of a session - which for a dash that answered Yunmo after EasyConn found
         * nothing is NOT what the saved motorcycle's modelId resolves to - and the companion app
         * has to arrive at the same enum entry from the name alone. An unknown key answers null
         * rather than [GENERIC] so a caller can tell "this build has no such profile" from "this
         * dash really is generic".
         */
        fun byKey(key: String?): TBoxModelProfile? {
            val normalized = key?.trim().orEmpty()
            if (normalized.isEmpty()) return null
            return entries.firstOrNull { it.key == normalized }
        }

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
        /** CLIENT_INFO `flavor` of Carbit's white-label EasyConn stack; never a CFMOTO unit. */
        private const val CARBIT_LICENCE_FLAVOR = "51"

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
            // CLIENT_INFO's `flavor` names the manufacturer that licensed the EasyConn stack
            // in this dashboard, and it is the one field here that can rule a family OUT rather
            // than in. 51 is Carbit's white-label stack - the reference fork scores exactly that
            // number for the Morini SoftAP / Alltrhike units, and every flavor-51 dash in the
            // collector is a rebadge (two VOGE, one Benelli) while the CFMOTO reference unit
            // reports 65540. It is used below only to stop a CFMOTO profile being carried by a
            // firmware fingerprint with no CFMOTO identity behind it; a dash that names itself
            // still wins, licence or no licence.
            val carbitLicensed = capabilities.flavor?.trim() == CARBIT_LICENCE_FLAVOR

            fun cfdl26BaseScore(): Int {
                // Identity signals: things only a CFDL26-family CFMOTO dash reports. A modern
                // sdkVersion (1.x) and supportFunction=128 are true of other brands' EasyConn
                // firmware too (a generic Zontes dash reports sdkVersion=1.1.3.2 + 128 and was
                // misclassified as 800NK Advanced by exactly those two), so they only ever
                // corroborate an identity match, never establish one.
                var points = 0
                if (versionName.startsWith("CFDL26")) points += 4
                if (packageName == "com.cfmoto.easyconnect") points += 3
                if (sockAuth) points += 2
                if (identity.contains("cfdl26") || identity.contains("motoplay")) points += 2
                if (points == 0) return 0
                if (sdkVersion.isNotEmpty() && !sdkVersion.startsWith("0.")) points += 2
                if (supportFunction == 128) points += 1
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
                    if (identity.contains("crcp")) points += 2
                    // sdkVersion 0.9.23.x with package linux_no_package is a firmware DIALECT -
                    // the older CFDL16-family EasyConn, which other manufacturers ship too - and
                    // it is the only term here that can carry this profile with nothing else
                    // agreeing. The same discipline cfdl26BaseScore() states applies: it
                    // corroborates a CFMOTO identity, it must not establish one over a licence
                    // saying somebody else built this dash. Rider 36ee9d2c's Benelli TRK 702X
                    // matched on it alone and scored 3, so Core's Android Auto dressed a Benelli
                    // in a CFMOTO panel's 22px top margin (visible: an 800x480 TFT letterboxed to
                    // 763x458) while the Ride Dashboard, which had no capabilities to score at
                    // all, used none.
                    if (sdkVersion.startsWith("0.9.23") &&
                        identity.contains("linux_no_package") &&
                        (points > 0 || !carbitLicensed)
                    ) {
                        points += 3
                    }
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
                    // Primary identification is the QR modelId (66660742). For CLIENT_INFO
                    // scoring the same identity-vs-corroboration split as cfdl26BaseScore()
                    // applies: supportLandscapeAdaptive is a generic EasyConn capability flag
                    // (other brands report it true) and must never claim this profile alone.
                    var points = 0
                    if (identity.contains("cfdl16") || identity.contains("motoplay")) points += 2
                    if (points > 0 && landscapeAdaptive) points += 1
                    points
                }
                CL_C450 -> {
                    var points = 0
                    if (identity.contains("48fb4c")) points += 4
                    // Same rule, same reason, and not hypothetical: with CFMOTO_800NK refused
                    // this lone corroborating point was the next thing standing, and it would
                    // have moved a Benelli's 800x480 panel onto a 544x512 profile on the strength
                    // of both dashes running 0.9.23 firmware.
                    if (sdkVersion.startsWith("0.9.23") && (points > 0 || !carbitLicensed)) points += 1
                    points
                }
                MOTO_HUB_SIMULATOR -> {
                    var points = 0
                    if (identity.contains("moto-hub") || identity.contains("moto hub")) points += 4
                    points
                }
                // Manual-selection experiments: detection must never claim them, or the riders
                // whose Zontes dashes already stream fine would silently change wire format.
                ZONTES_368G_TEST -> 0
                ZONTES_368G_TEST_B -> 0
                // Same rule for the Voge stream experiment: a Voge that streams fine today
                // must never be moved off all-intra by detection.
                VOGE_TEST -> 0
                // Claimed by its modelId, never by CLIENT_INFO: the signals this dash reports
                // (flavor 51, linux_no_package, sdk 0.9.23) are a licence and a firmware dialect
                // that several other brands ship too, and scoring on them would put a Voge and
                // two rebadges on a 10 fps stream meant for one QJ - see the profile's own note.
                QJ_SRK921_RR -> 0
                // ThinkerRide dashes never produce CLIENT_INFO (an EasyConn concept), so scoring
                // has nothing to say; they resolve by the QR's pseudo modelId or a manual pin.
                KOVE_800X -> 0
                // Same wire, different panel, and nothing on that wire tells them apart: a
                // manual pin is the only way here (see the profile's own note).
                KOVE_450_RALLY -> 0
                // Yunmo dashes never produce CLIENT_INFO either, and the X-Cape 1200 shares its
                // QR ProductID with EasyConn Morinis, so detection must never claim it: it is a
                // manual pin only.
                // All three Morini Yunmo profiles score zero on purpose: ProductID 00297 is
                // shared with the EasyConn X-Cape 649/700 and the Seiemmezzo, so letting any
                // of them win on capabilities would route those bikes to the wrong wire.
                // They are reachable only by a rider pinning them.
                MORINI_XCAPE_1200, MORINI_XCAPE_1200_MIRROR, MORINI_XCAPE_1200_JPEG, KOVE_625X -> 0
                GENERIC -> 0
            }
        }

        fun defaultAndroidAutoPreset(
            modelId: String?,
            capabilities: TBoxCapabilities?,
            profileOverride: ProfileOverride? = null
        ): AndroidAutoVideoPreset =
            resolve(modelId, capabilities, profileOverride).defaultAndroidAutoPreset

        /**
         * True when [defaultAndroidAutoPreset] comes from a recognized dash rather than
         * [GENERIC]. Only a recognized profile's orientation is evidence about the hardware;
         * see AndroidAutoCapabilityProfiles.usableSavedGeometryForAuto.
         *
         * A rider who pinned [ProfileOverride.GENERIC] is stating the opposite — that nothing here
         * is known-good — so the override has to reach this answer too, otherwise the pin would
         * silently keep the veto of whichever profile detection had guessed.
         */
        fun hasValidatedAndroidAutoPreset(
            modelId: String?,
            capabilities: TBoxCapabilities?,
            profileOverride: ProfileOverride? = null
        ): Boolean = resolve(modelId, capabilities, profileOverride) != GENERIC

        fun fallbackVideoArea(
            modelId: String?,
            capabilities: TBoxCapabilities?,
            profileOverride: ProfileOverride? = null
        ): TBoxEvent.VideoArea =
            resolve(modelId, capabilities, profileOverride).fallbackTBoxVideoArea
                ?: GENERIC.fallbackTBoxVideoArea
                ?: error("Generic T-Box fallback geometry is not configured.")
    }
}
