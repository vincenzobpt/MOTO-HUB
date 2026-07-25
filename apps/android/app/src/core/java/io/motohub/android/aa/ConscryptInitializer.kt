// Ported from headunit-revived (AGPLv3): ssl/ConscryptInitializer.kt
package io.motohub.android.aa

import java.security.Security

object ConscryptInitializer {
    @Volatile private var initialized = false
    @Volatile private var conscryptAvailable = false

    @Synchronized
    fun initialize(): Boolean {
        if (initialized) return conscryptAvailable
        initialized = true

        try {
            val conscrypt = Class.forName("org.conscrypt.Conscrypt")
            val newProviderMethod = conscrypt.getMethod("newProvider")
            val provider = newProviderMethod.invoke(null) as java.security.Provider
            val result = Security.insertProviderAt(provider, 1)
            conscryptAvailable = result != -1 || Security.getProvider("Conscrypt") != null
            if (conscryptAvailable) {
                AaLog.i("Conscrypt installed as security provider (position: $result)")
            }
        } catch (e: ClassNotFoundException) {
            AaLog.e("Conscrypt library not found", e)
            conscryptAvailable = false
        } catch (e: Exception) {
            AaLog.e("Failed to initialize Conscrypt", e)
            conscryptAvailable = false
        }
        return conscryptAvailable
    }

    fun isAvailable(): Boolean = conscryptAvailable
    fun getProviderName(): String? = if (conscryptAvailable) "Conscrypt" else null
}
