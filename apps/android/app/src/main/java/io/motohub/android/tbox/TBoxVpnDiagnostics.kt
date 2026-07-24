package io.motohub.android.tbox

/** Converts Android VPN/network errors into an actionable local-network diagnosis. */
internal object TBoxVpnDiagnostics {
    const val VPN_BLOCKING_MESSAGE =
        "A VPN is blocking the motorcycle Wi-Fi. Disable Always-on VPN / Block connections without VPN, " +
            "or allow MOTO-HUB on the local network, then try again."

    fun isVpnBindBlocked(error: Throwable?): Boolean {
        var current = error
        while (current != null) {
            val detail = listOfNotNull(current.message, current.cause?.message)
                .joinToString(" ")
                .lowercase()
            if ("eperm" in detail ||
                "operation not permitted" in detail ||
                "permission denied" in detail && "network" in detail
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    fun userFacingMessage(error: Throwable?, activeVpnLabel: String?): String? {
        if (activeVpnLabel == null && !isVpnBindBlocked(error)) return null
        return VPN_BLOCKING_MESSAGE
    }
}
