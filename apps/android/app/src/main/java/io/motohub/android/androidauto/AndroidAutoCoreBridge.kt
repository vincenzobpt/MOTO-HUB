package io.motohub.android.androidauto

/**
 * Flavor seam for how the Android Auto button reaches the AGPL-derived AA pipeline.
 *
 * - CORE runs that pipeline in-process (it owns the `aa/` receiver), so its implementation is a
 *   no-op — MainActivity's local path drives everything.
 * - PRO holds no AGPL code; its implementation delegates to CORE over the AIDL bridge
 *   (IAndroidAutoReceiverService.startFullSession), so the AA video never touches PRO's process.
 *
 * The concrete `createAndroidAutoCoreBridge(context)` factory is provided per source set
 * (src/core, src/pro), resolved at compile time — same pattern as createTBoxSessionEstablisher.
 */
interface AndroidAutoCoreBridge {
    /** True when this flavor delegates AA to CORE (PRO). When false the caller runs its own
     *  local AA path (CORE). */
    val delegatesToCore: Boolean

    /** PRO: bind CORE and start its full AA session. [onFailure] is invoked (main thread) with a
     *  user-facing message if the session can't start. No-op in CORE. */
    fun start(onFailure: (String) -> Unit)

    /** PRO: stop the CORE-driven AA session. No-op in CORE. */
    fun stop()

    /** Release any bound-service connection. */
    fun release()
}
