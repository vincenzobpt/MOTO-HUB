package io.motohub.android.feature.ridedashboard.nav

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the user's own Stadia Maps routing API key, encrypted with Android
 * Keystore the same way [io.motohub.android.data.MotorcycleProfileStore]
 * encrypts the T-Box Wi-Fi password. There is no bundled or shared key: every
 * installation uses the key the rider enters in Settings.
 */
object NavigationSettingsStore {
    fun load(context: Context): String {
        val preferences = preferences(context)
        val iv = preferences.getString(KEY_IV, null) ?: return ""
        val ciphertext = preferences.getString(KEY_CIPHERTEXT, null) ?: return ""
        return runCatching { decrypt(iv, ciphertext) }.getOrDefault("")
    }

    fun hasKey(context: Context): Boolean = load(context).isNotBlank()

    fun save(context: Context, apiKey: String) {
        val trimmed = apiKey.trim()
        if (trimmed.isEmpty()) {
            clear(context)
            return
        }
        val encrypted = encrypt(trimmed)
        preferences(context).edit()
            .putString(KEY_IV, encrypted.iv)
            .putString(KEY_CIPHERTEXT, encrypted.ciphertext)
            .apply()
    }

    fun clear(context: Context) {
        preferences(context).edit().clear().apply()
    }

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

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

    private data class EncryptedValue(val iv: String, val ciphertext: String)

    private const val PREFERENCES_NAME = "navigation_routing_key"
    private const val KEY_IV = "iv"
    private const val KEY_CIPHERTEXT = "ciphertext"
    private const val KEY_ALIAS = "moto_hub_navigation_routing_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128
}
