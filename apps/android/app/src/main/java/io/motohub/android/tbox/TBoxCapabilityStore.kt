package io.motohub.android.tbox

import android.content.Context
import io.motohub.android.session.MotorcycleProfile
import org.json.JSONObject

data class TBoxCapabilitySnapshot(
    val profileId: String,
    val ssid: String,
    val host: TBoxHost? = null,
    val discoveredAtEpochMillis: Long? = null,
    val capabilities: TBoxCapabilities? = null,
    val capabilitiesObservedAtEpochMillis: Long? = null
)

/** Persists only whitelisted, non-secret T-Box metadata for each motorcycle profile. */
class TBoxCapabilityStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    @Synchronized
    fun recordDiscovery(
        profile: MotorcycleProfile,
        host: TBoxHost,
        observedAtEpochMillis: Long = System.currentTimeMillis()
    ) {
        save(
            load(profile)?.copy(
                ssid = profile.ssid,
                host = host,
                discoveredAtEpochMillis = observedAtEpochMillis
            ) ?: TBoxCapabilitySnapshot(
                profileId = profile.id,
                ssid = profile.ssid,
                host = host,
                discoveredAtEpochMillis = observedAtEpochMillis
            )
        )
    }

    @Synchronized
    fun recordCapabilities(
        profile: MotorcycleProfile,
        capabilities: TBoxCapabilities,
        observedAtEpochMillis: Long = System.currentTimeMillis()
    ) {
        save(
            load(profile)?.copy(
                ssid = profile.ssid,
                capabilities = capabilities,
                capabilitiesObservedAtEpochMillis = observedAtEpochMillis
            ) ?: TBoxCapabilitySnapshot(
                profileId = profile.id,
                ssid = profile.ssid,
                capabilities = capabilities,
                capabilitiesObservedAtEpochMillis = observedAtEpochMillis
            )
        )
    }

    fun load(profile: MotorcycleProfile): TBoxCapabilitySnapshot? =
        preferences.getString(key(profile.id), null)
            ?.let { serialized -> runCatching { decode(JSONObject(serialized)) }.getOrNull() }

    fun delete(profileId: String) {
        preferences.edit().remove(key(profileId)).apply()
    }

    private fun save(snapshot: TBoxCapabilitySnapshot) {
        preferences.edit().putString(key(snapshot.profileId), encode(snapshot).toString()).apply()
    }

    private fun encode(snapshot: TBoxCapabilitySnapshot): JSONObject = JSONObject().apply {
        put("profileId", snapshot.profileId)
        put("ssid", snapshot.ssid)
        snapshot.host?.let { host ->
            put("host", JSONObject().apply {
                put("ipAddress", host.ipAddress)
                put("port", host.port)
                put("packageName", host.packageName)
            })
        }
        putNullable("discoveredAt", snapshot.discoveredAtEpochMillis)
        snapshot.capabilities?.let { put("capabilities", encodeCapabilities(it)) }
        putNullable("capabilitiesObservedAt", snapshot.capabilitiesObservedAtEpochMillis)
    }

    private fun decode(json: JSONObject): TBoxCapabilitySnapshot = TBoxCapabilitySnapshot(
        profileId = json.getString("profileId"),
        ssid = json.getString("ssid"),
        host = json.optJSONObject("host")?.let { host ->
            TBoxHost(
                ipAddress = host.getString("ipAddress"),
                port = host.getInt("port"),
                packageName = host.getString("packageName")
            )
        },
        discoveredAtEpochMillis = json.optionalLong("discoveredAt"),
        capabilities = json.optJSONObject("capabilities")?.let(::decodeCapabilities),
        capabilitiesObservedAtEpochMillis = json.optionalLong("capabilitiesObservedAt")
    )

    private fun encodeCapabilities(value: TBoxCapabilities): JSONObject = JSONObject().apply {
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
    }

    private fun decodeCapabilities(json: JSONObject) = TBoxCapabilities(
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
        bluetoothSettings = json.optionalBoolean("bluetoothSettings")
    )

    private fun key(profileId: String) = "profile:$profileId"

    private companion object {
        const val PREFERENCES_NAME = "tbox_capabilities"
    }
}

private fun JSONObject.putNullable(key: String, value: Any?) {
    put(key, value ?: JSONObject.NULL)
}

private fun JSONObject.optionalString(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf(String::isNotBlank)

private fun JSONObject.optionalInt(key: String): Int? =
    if (!has(key) || isNull(key)) null else optInt(key)

private fun JSONObject.optionalLong(key: String): Long? =
    if (!has(key) || isNull(key)) null else optLong(key)

private fun JSONObject.optionalBoolean(key: String): Boolean? =
    if (!has(key) || isNull(key)) null else optBoolean(key)
