package io.motohub.android.feature.pairing

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class TBoxQrPayload(
    val ssid: String,
    val password: String,
    val encryption: String?,
    // Opaque T-Box provisioning identifier. It is never interpreted as a motorcycle model.
    val modelId: String?,
    val displayName: String?
)

object TBoxQrParser {
    fun parse(rawValue: String): Result<TBoxQrPayload> = runCatching {
        val uri = URI(rawValue)
        val host = uri.host?.lowercase() ?: error("The QR code does not contain a valid host.")
        check(isEasyConnHost(host)) {
            "The QR code does not belong to an EasyConn T-Box."
        }
        val parameters = uri.rawQuery.orEmpty()
            .split('&')
            .filter(String::isNotBlank)
            .associate { item ->
                val keyAndValue = item.split('=', limit = 2)
                decode(keyAndValue[0]) to decode(keyAndValue.getOrElse(1) { "" })
            }
        val ssid = parameters["ssid"].orEmpty().trim()
        check(ssid.isNotEmpty()) { "The QR code does not contain the T-Box network name." }

        TBoxQrPayload(
            ssid = ssid,
            password = parameters["pwd"].orEmpty(),
            encryption = parameters["auth"],
            modelId = parameters["modelid"],
            displayName = parameters["name"]
        )
    }

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8)

    private fun isEasyConnHost(host: String): Boolean =
        host == "carbit.com" || host.endsWith(".carbit.com") ||
            host == "carbit.com.cn" || host.endsWith(".carbit.com.cn")
}
