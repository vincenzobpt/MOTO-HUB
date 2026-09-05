// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import org.json.JSONObject

/** Safe, non-secret subset of CLIENT_INFO reported by the EasyConn T-Box. */
data class TBoxCapabilities(
    val huName: String? = null,
    val carBrand: String? = null,
    val carModel: String? = null,
    val packageName: String? = null,
    val pxcVersion: String? = null,
    val sdkVersion: String? = null,
    val versionName: String? = null,
    val versionCode: String? = null,
    val dpi: Int? = null,
    val dpiEnabled: Boolean? = null,
    val productType: Int? = null,
    val screenType: Int? = null,
    val transportType: Int? = null,
    val supportFunction: Int? = null,
    val socketTimeoutPeriodWifi: Int? = null,
    val socketServerAuth: Boolean? = null,
    val screenTouch: Boolean? = null,
    val screenMirroring: Boolean? = null,
    val mirrorReconnect: Boolean? = null,
    val landscapeAdaptive: Boolean? = null,
    val microphone: Boolean? = null,
    val hid: Boolean? = null,
    val mirrorOverlayTouch: Boolean? = null,
    val thirdPartyApps: Boolean? = null,
    val phoneSignal: Boolean? = null,
    val syncCorrectTime: Boolean? = null,
    val bluetoothCall: Boolean? = null,
    val bluetoothSettings: Boolean? = null,
    /**
     * `currentHUTime`: how long the dashboard has been up, in milliseconds, as it reports at
     * CLIENT_INFO. A Voge tracked wall time to within 10ms over 87s with it, so it is a real
     * clock - and one that starts again from zero when the firmware reboots, which is the only
     * direct evidence of a reboot the protocol offers. Some CFMOTO units report an epoch-like
     * number here instead; [TBoxWireLadder] copes with either.
     */
    val huUptimeMillis: Long? = null,
    /**
     * The EasyConn SDK "flavor": which manufacturer licensed this dashboard. Numeric in
     * shipped firmware (65536 CFMOTO, 65540 CFMOTO international, 65561 ZONTES, 65569 Benda,
     * …), a plain string in the MOTO-HUB simulator, so it is kept as text. The SDK pairs it
     * with a phone package name it expects the companion app to use, which is why a rebadged
     * non-CFMOTO dash can complete the handshake and still refuse to project.
     *
     * Declared last, with [channel], so a positional [TBoxCapabilities] call site cannot
     * silently rebind one of the other nullable strings.
     */
    val flavor: String? = null,
    /** CLIENT_INFO echoes the pairing QR's modelId here; useful to confirm the two agree. */
    val channel: String? = null
)

internal fun decodeTBoxCapabilities(payload: ByteArray): TBoxCapabilities? = runCatching {
    val jsonText = payload.toString(Charsets.UTF_8).trim().trimEnd('\u0000')
    val json = JSONObject(jsonText)
    tBoxCapabilitiesFrom(
        CLIENT_INFO_KEYS.associateWith { key ->
            if (!json.has(key) || json.isNull(key)) null else json.get(key)
        }
    )
}.getOrNull()

internal fun tBoxCapabilitiesFrom(fields: Map<String, Any?>): TBoxCapabilities =
    TBoxCapabilities(
        huName = fields["HUName"].asString(),
        carBrand = fields["carBrand"].asString(),
        carModel = fields["carModel"].asString(),
        packageName = fields["package_name"].asString(),
        pxcVersion = fields["pxcVersion"].asString(),
        sdkVersion = fields["sdkVersion"].asString(),
        versionName = fields["version_name"].asString(),
        versionCode = fields["version_code"].asString(),
        dpi = fields["dpi"].asInt(),
        dpiEnabled = fields["enableDPI"].asBoolean(),
        productType = fields["productType"].asInt(),
        screenType = fields["screenType"].asInt(),
        transportType = fields["transportType"].asInt(),
        supportFunction = fields["supportFunction"].asInt(),
        socketTimeoutPeriodWifi = fields["socketTimeoutPeriodWifi"].asInt(),
        socketServerAuth = fields["enableSockServerAuth"].asBoolean(),
        screenTouch = fields["supportScreenTouch"].asBoolean(),
        screenMirroring = fields["supportScreenMirroring"].asBoolean(),
        mirrorReconnect = fields["supportMirrorReconnect"].asBoolean(),
        landscapeAdaptive = fields["supportLandscapeAdaptive"].asBoolean(),
        microphone = fields["supportMic"].asBoolean(),
        hid = fields["supportHID"].asBoolean(),
        mirrorOverlayTouch = fields["supportMirrorOverlayTouch"].asBoolean(),
        thirdPartyApps = fields["supportThirdPartyApp"].asBoolean(),
        phoneSignal = fields["supportPhoneSignal"].asBoolean(),
        syncCorrectTime = fields["supportSyncCorrectTime"].asBoolean(),
        bluetoothCall = fields["supportBTCall"].asBoolean(),
        bluetoothSettings = fields["supportBTSetting"].asBoolean(),
        huUptimeMillis = fields["currentHUTime"].asLong(),
        flavor = fields["flavor"].asString(),
        channel = fields["channel"].asString()
    )

private fun Any?.asString(): String? = when (this) {
    is String -> trim().takeIf(String::isNotEmpty)
    is Number -> toString()
    else -> null
}

private fun Any?.asInt(): Int? = when (this) {
    is Number -> toInt()
    is String -> toIntOrNull()
    else -> null
}

private fun Any?.asLong(): Long? = when (this) {
    is Number -> toLong()
    is String -> trim().toLongOrNull()
    else -> null
}

private fun Any?.asBoolean(): Boolean? = when (this) {
    is Boolean -> this
    is String -> toBooleanStrictOrNull()
    else -> null
}

private val CLIENT_INFO_KEYS = setOf(
    "HUName",
    "carBrand",
    "carModel",
    "flavor",
    "channel",
    "package_name",
    "pxcVersion",
    "sdkVersion",
    "version_name",
    "version_code",
    "dpi",
    "enableDPI",
    "productType",
    "screenType",
    "transportType",
    "supportFunction",
    "socketTimeoutPeriodWifi",
    "enableSockServerAuth",
    "supportScreenTouch",
    "supportScreenMirroring",
    "supportMirrorReconnect",
    "supportLandscapeAdaptive",
    "supportMic",
    "supportHID",
    "supportMirrorOverlayTouch",
    "supportThirdPartyApp",
    "supportPhoneSignal",
    "supportSyncCorrectTime",
    "supportBTCall",
    "supportBTSetting",
    "currentHUTime"
)

/**
 * The wire form both apps share. CLIENT_INFO is read on the EasyConn command socket, which lives
 * in Core, so Core is the only process that ever learns it; the companion app is handed this JSON
 * over the AIDL bridge (getCapabilitiesJson) and decodes it with the function below. Keeping the
 * pair here rather than inside TBoxCapabilityStore is what makes that round trip testable without
 * a Context - and the round trip is the whole contract.
 */
internal fun encodeCapabilities(value: TBoxCapabilities): JSONObject = JSONObject().apply {
    putNullable("huName", value.huName)
    putNullable("carBrand", value.carBrand)
    putNullable("carModel", value.carModel)
    putNullable("packageName", value.packageName)
    putNullable("pxcVersion", value.pxcVersion)
    putNullable("sdkVersion", value.sdkVersion)
    putNullable("versionName", value.versionName)
    putNullable("versionCode", value.versionCode)
    putNullable("dpi", value.dpi)
    putNullable("dpiEnabled", value.dpiEnabled)
    putNullable("productType", value.productType)
    putNullable("screenType", value.screenType)
    putNullable("transportType", value.transportType)
    putNullable("supportFunction", value.supportFunction)
    putNullable("socketTimeoutPeriodWifi", value.socketTimeoutPeriodWifi)
    putNullable("socketServerAuth", value.socketServerAuth)
    putNullable("screenTouch", value.screenTouch)
    putNullable("screenMirroring", value.screenMirroring)
    putNullable("mirrorReconnect", value.mirrorReconnect)
    putNullable("landscapeAdaptive", value.landscapeAdaptive)
    putNullable("microphone", value.microphone)
    putNullable("hid", value.hid)
    putNullable("mirrorOverlayTouch", value.mirrorOverlayTouch)
    putNullable("thirdPartyApps", value.thirdPartyApps)
    putNullable("phoneSignal", value.phoneSignal)
    putNullable("syncCorrectTime", value.syncCorrectTime)
    putNullable("bluetoothCall", value.bluetoothCall)
    putNullable("bluetoothSettings", value.bluetoothSettings)
    putNullable("huUptimeMillis", value.huUptimeMillis)
    putNullable("flavor", value.flavor)
    putNullable("channel", value.channel)
}

internal fun decodeCapabilities(json: JSONObject) = TBoxCapabilities(
    huName = json.optionalString("huName"),
    carBrand = json.optionalString("carBrand"),
    carModel = json.optionalString("carModel"),
    packageName = json.optionalString("packageName"),
    pxcVersion = json.optionalString("pxcVersion"),
    sdkVersion = json.optionalString("sdkVersion"),
    versionName = json.optionalString("versionName"),
    versionCode = json.optionalString("versionCode"),
    dpi = json.optionalInt("dpi"),
    dpiEnabled = json.optionalBoolean("dpiEnabled"),
    productType = json.optionalInt("productType"),
    screenType = json.optionalInt("screenType"),
    transportType = json.optionalInt("transportType"),
    supportFunction = json.optionalInt("supportFunction"),
    socketTimeoutPeriodWifi = json.optionalInt("socketTimeoutPeriodWifi"),
    socketServerAuth = json.optionalBoolean("socketServerAuth"),
    screenTouch = json.optionalBoolean("screenTouch"),
    screenMirroring = json.optionalBoolean("screenMirroring"),
    mirrorReconnect = json.optionalBoolean("mirrorReconnect"),
    landscapeAdaptive = json.optionalBoolean("landscapeAdaptive"),
    microphone = json.optionalBoolean("microphone"),
    hid = json.optionalBoolean("hid"),
    mirrorOverlayTouch = json.optionalBoolean("mirrorOverlayTouch"),
    thirdPartyApps = json.optionalBoolean("thirdPartyApps"),
    phoneSignal = json.optionalBoolean("phoneSignal"),
    syncCorrectTime = json.optionalBoolean("syncCorrectTime"),
    bluetoothCall = json.optionalBoolean("bluetoothCall"),
    bluetoothSettings = json.optionalBoolean("bluetoothSettings"),
    huUptimeMillis = json.optionalLong("huUptimeMillis"),
    flavor = json.optionalString("flavor"),
    channel = json.optionalString("channel")
)

internal fun JSONObject.putNullable(key: String, value: Any?) {
    put(key, value ?: JSONObject.NULL)
}

internal fun JSONObject.optionalString(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf(String::isNotBlank)

internal fun JSONObject.optionalInt(key: String): Int? =
    if (!has(key) || isNull(key)) null else optInt(key)

internal fun JSONObject.optionalLong(key: String): Long? =
    if (!has(key) || isNull(key)) null else optLong(key)

internal fun JSONObject.optionalBoolean(key: String): Boolean? =
    if (!has(key) || isNull(key)) null else optBoolean(key)
