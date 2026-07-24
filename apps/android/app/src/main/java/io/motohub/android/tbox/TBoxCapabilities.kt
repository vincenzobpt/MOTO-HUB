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
    val bluetoothSettings: Boolean? = null
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
        bluetoothSettings = fields["supportBTSetting"].asBoolean()
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

private fun Any?.asBoolean(): Boolean? = when (this) {
    is Boolean -> this
    is String -> toBooleanStrictOrNull()
    else -> null
}

private val CLIENT_INFO_KEYS = setOf(
    "HUName",
    "carBrand",
    "carModel",
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
    "supportBTSetting"
)
