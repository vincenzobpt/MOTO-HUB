package io.motohub.android.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.session.TBoxConnectionMode
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

/** Stores multiple motorcycle profiles and keeps each Wi-Fi password encrypted. */
class MotorcycleProfileStore(context: Context) {
    private val applicationContext = context.applicationContext
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )
    private val legacyPreferences = applicationContext.getSharedPreferences(
        LEGACY_PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun loadAll(): List<MotorcycleProfile> {
        val serialized = preferences.getString(KEY_PROFILES, null)
        if (!serialized.isNullOrBlank()) {
            return runCatching { decodeProfiles(JSONArray(serialized)) }
                .getOrElse {
                    clear()
                    emptyList()
                }
        }

        return migrateLegacyProfile()?.let { legacy ->
            saveAll(listOf(legacy), legacy.id)
            listOf(legacy)
        }.orEmpty()
    }

    fun load(): MotorcycleProfile? {
        val profiles = loadAll()
        val activeId = preferences.getString(KEY_ACTIVE_ID, null)
        return profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()
    }

    fun save(profile: MotorcycleProfile, makeActive: Boolean = true): Result<Unit> = runCatching {
        val profiles = loadAll()
            .filterNot { it.id == profile.id }
            .plus(profile)
        saveAll(profiles, if (makeActive) profile.id else preferences.getString(KEY_ACTIVE_ID, null))
    }

    fun setActive(profileId: String): Result<Unit> = runCatching {
        check(loadAll().any { it.id == profileId }) { "Motorcycle profile not found." }
        check(preferences.edit().putString(KEY_ACTIVE_ID, profileId).commit()) {
            "Android did not update the active motorcycle."
        }
    }

    fun delete(profileId: String): Result<Unit> = runCatching {
        val profiles = loadAll().filterNot { it.id == profileId }
        val activeId = preferences.getString(KEY_ACTIVE_ID, null)
        val nextActiveId = when {
            activeId != profileId -> activeId
            else -> profiles.firstOrNull()?.id
        }
        saveAll(profiles, nextActiveId)
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun saveAll(profiles: List<MotorcycleProfile>, activeId: String?) {
        val array = JSONArray()
        profiles.forEach { profile ->
            val encryptedPassword = encrypt(profile.password)
            array.put(
                JSONObject().apply {
                    put(KEY_PROFILE_ID, profile.id)
                    put(KEY_SSID, profile.ssid)
                    put(KEY_PASSWORD_IV, encryptedPassword.iv)
                    put(KEY_PASSWORD_CIPHERTEXT, encryptedPassword.ciphertext)
                    putNullable(KEY_MODEL_ID, profile.modelId)
                    putNullable(KEY_DISPLAY_NAME, profile.displayName)
                    putNullable(KEY_PHOTO_PATH, profile.photoPath)
                    putNullable(KEY_PROFILE_OVERRIDE_KEY, profile.profileOverrideKey)
                    put(KEY_CONNECTION_MODE, profile.connectionMode.name)
                    if (profile.fuelTankRangeKm != null) put(KEY_FUEL_TANK_RANGE_KM, profile.fuelTankRangeKm)
                }
            )
        }
        check(
            preferences.edit()
                .putString(KEY_PROFILES, array.toString())
                .apply {
                    if (activeId == null) remove(KEY_ACTIVE_ID) else putString(KEY_ACTIVE_ID, activeId)
                }
                .commit()
        ) { "Android did not save the motorcycle profiles." }
    }

    private fun decodeProfiles(array: JSONArray): List<MotorcycleProfile> = buildList {
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            val ssid = item.optString(KEY_SSID).trim()
            if (ssid.isEmpty()) continue
            add(
                MotorcycleProfile(
                    id = item.optString(KEY_PROFILE_ID).ifBlank { UUID.randomUUID().toString() },
                    ssid = ssid,
                    password = decrypt(
                        item.getString(KEY_PASSWORD_IV),
                        item.getString(KEY_PASSWORD_CIPHERTEXT)
                    ),
                    modelId = item.optNullableString(KEY_MODEL_ID),
                    displayName = item.optNullableString(KEY_DISPLAY_NAME),
                    photoPath = item.optNullableString(KEY_PHOTO_PATH),
                    fuelTankRangeKm = item.optDouble(KEY_FUEL_TANK_RANGE_KM, 0.0).takeIf { it > 0 },
                    profileOverrideKey = item.optNullableString(KEY_PROFILE_OVERRIDE_KEY),
                    connectionMode = item.optString(KEY_CONNECTION_MODE)
                        .let { raw -> TBoxConnectionMode.entries.firstOrNull { it.name == raw } }
                        ?: TBoxConnectionMode.AUTO
                )
            )
        }
    }

    private fun migrateLegacyProfile(): MotorcycleProfile? {
        val ssid = legacyPreferences.getString(LEGACY_KEY_SSID, null)?.trim().orEmpty()
        if (ssid.isEmpty()) return null
        val iv = legacyPreferences.getString(LEGACY_KEY_PASSWORD_IV, null) ?: return null
        val ciphertext = legacyPreferences.getString(LEGACY_KEY_PASSWORD_CIPHERTEXT, null) ?: return null
        return runCatching {
            MotorcycleProfile(
                id = UUID.randomUUID().toString(),
                ssid = ssid,
                password = decrypt(iv, ciphertext),
                modelId = legacyPreferences.getString(LEGACY_KEY_MODEL_ID, null),
                displayName = legacyPreferences.getString(LEGACY_KEY_DISPLAY_NAME, null)
            )
        }.getOrNull()
    }

    private fun encrypt(value: String): EncryptedValue {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return EncryptedValue(
            iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            ciphertext = Base64.encodeToString(
                cipher.doFinal(value.toByteArray(Charsets.UTF_8)),
                Base64.NO_WRAP
            )
        )
    }

    private fun decrypt(iv: String, ciphertext: String): String {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, Base64.decode(iv, Base64.NO_WRAP))
        )
        return cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    private fun JSONObject.putNullable(key: String, value: String?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private data class EncryptedValue(
        val iv: String,
        val ciphertext: String
    )

    private companion object {
        const val PREFERENCES_NAME = "motorcycle_profiles"
        const val LEGACY_PREFERENCES_NAME = "motorcycle_profile"
        const val KEY_PROFILES = "profiles"
        const val KEY_ACTIVE_ID = "active_id"
        const val KEY_PROFILE_ID = "id"
        const val KEY_SSID = "ssid"
        const val KEY_PASSWORD_IV = "password_iv"
        const val KEY_PASSWORD_CIPHERTEXT = "password_ciphertext"
        const val KEY_MODEL_ID = "model_id"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_PHOTO_PATH = "photo_path"
        const val KEY_FUEL_TANK_RANGE_KM = "fuel_tank_range_km"
        const val KEY_PROFILE_OVERRIDE_KEY = "profile_override_key"
        const val KEY_CONNECTION_MODE = "connection_mode"
        const val LEGACY_KEY_SSID = "ssid"
        const val LEGACY_KEY_PASSWORD_IV = "password_iv"
        const val LEGACY_KEY_PASSWORD_CIPHERTEXT = "password_ciphertext"
        const val LEGACY_KEY_MODEL_ID = "model_id"
        const val LEGACY_KEY_DISPLAY_NAME = "display_name"
        const val KEY_ALIAS = "moto_hub_tbox_credentials"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
    }
}
