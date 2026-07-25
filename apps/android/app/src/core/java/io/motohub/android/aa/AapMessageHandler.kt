// Ported from headunit-revived (AGPLv3): aap/AapMessageHandler.kt
package io.motohub.android.aa

internal interface AapMessageHandler {
    @Throws(HandleException::class)
    fun handle(message: AapMessage)

    class HandleException internal constructor(cause: Throwable) : Exception(cause)
}
