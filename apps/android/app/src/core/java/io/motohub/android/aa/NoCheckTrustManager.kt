// Ported from headunit-revived (AGPLv3): ssl/NoCheckTrustManager.kt
package io.motohub.android.aa

import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

class NoCheckTrustManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate>? = null
}
