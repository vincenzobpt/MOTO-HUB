package io.motohub.android.aa

import android.util.Log

/**
 * Logging shim for the ported Android Auto (AAP) receiver.
 *
 * Mirrors the small subset of headunit-revived's `AppLog` API used by the ported files
 * (printf-style i/d/w/v/e), so those files port with minimal edits. Every line is also
 * forwarded to [sink] — wired to MOTO-HUB's on-screen log — prefixed
 * with the `[AA]` stage tag per the project logging convention.
 */
object AaLog {
    const val TAG = "MotoHubAA"

    /** Flip to true for very chatty per-message logging during bring-up debugging. */
    @Volatile var LOG_VERBOSE = false

    /** Routes AA logs into the app's on-screen event log. */
    @Volatile var sink: ((String) -> Unit)? = null

    private fun fmt(msg: String, args: Array<out Any?>): String {
        if (args.isEmpty()) return msg
        return try {
            String.format(msg, *args)
        } catch (e: Exception) {
            msg + " " + args.joinToString(" ") { it.toString() }
        }
    }

    private fun emit(msg: String) {
        Log.i(TAG, msg)
        try { sink?.invoke("[AA] $msg") } catch (_: Exception) {}
    }

    fun i(msg: String, vararg args: Any?) = emit(fmt(msg, args))
    fun d(msg: String, vararg args: Any?) = emit(fmt(msg, args))
    fun v(msg: String, vararg args: Any?) { if (LOG_VERBOSE) emit(fmt(msg, args)) }
    fun w(msg: String, vararg args: Any?) = emit("W: " + fmt(msg, args))

    fun e(msg: String, vararg args: Any?) {
        // If a Throwable was passed as the trailing arg (AppLog.e("msg", exception) style),
        // append its message/stack rather than feeding it to String.format.
        val tr = args.lastOrNull() as? Throwable
        if (tr != null) {
            val rest = args.copyOfRange(0, args.size - 1)
            emit("E: " + fmt(msg, rest) + " :: " + Log.getStackTraceString(tr))
        } else {
            emit("E: " + fmt(msg, args))
        }
    }

    fun e(tr: Throwable) = emit("E: " + Log.getStackTraceString(tr))
}
