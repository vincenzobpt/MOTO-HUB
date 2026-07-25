package io.motohub.android.androidauto

import android.content.Context

/**
 * CORE flavor factory. CORE contains the AA pipeline and drives it locally from MainActivity, so
 * the bridge is a no-op here — [AndroidAutoCoreBridge.delegatesToCore] is false, which tells the
 * shared UI to take its own local path.
 */
fun createAndroidAutoCoreBridge(context: Context): AndroidAutoCoreBridge = NoopAndroidAutoCoreBridge

object NoopAndroidAutoCoreBridge : AndroidAutoCoreBridge {
    override val delegatesToCore: Boolean = false
    override fun start(onFailure: (String) -> Unit) = Unit
    override fun stop() = Unit
    override fun release() = Unit
}
