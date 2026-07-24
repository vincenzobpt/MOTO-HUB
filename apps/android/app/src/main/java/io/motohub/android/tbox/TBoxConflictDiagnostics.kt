package io.motohub.android.tbox

/** Detects the characteristic local-port failure produced when another EasyConn app is active. */
internal object TBoxConflictDiagnostics {
    const val PORT_CONFLICT_MESSAGE =
        "Another EasyConn session is already using the local connection ports (10920-10922). " +
            "Stop any active MOTO-HUB session or force-stop the official CFMOTO/EasyConnect app, " +
            "then retry the connection."

    fun isPortConflict(message: String?): Boolean {
        val detail = message.orEmpty().lowercase()
        if (detail.isBlank()) return false
        val mentionsLinkPort = listOf("10920", "10921", "10922").any(detail::contains)
        return "address already in use" in detail ||
            "port already in use" in detail ||
            (mentionsLinkPort && ("bind" in detail || "listen" in detail || "held" in detail))
    }

    fun userFacingMessage(message: String): String =
        if (isPortConflict(message)) PORT_CONFLICT_MESSAGE else message
}
