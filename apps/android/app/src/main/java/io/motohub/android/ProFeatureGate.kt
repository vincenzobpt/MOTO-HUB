package io.motohub.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Stage 1 of the Core/Pro split removed the GPL T-Box transport (hudlib) from the PRO flavor — it
 * now reaches Core's transport only over the AIDL bridge. The streaming features (Android Auto,
 * Mirroring, Ride Dashboard) still run their pipelines locally, but in PRO those pipelines hit the
 * AIDL stub transport (empty discovery/negotiation) and crash with `NoSuchElementException`.
 *
 * Until Stage 2 routes each of these through Core, PRO must not run the local path. This is the
 * single choke point: every service `start()` companion calls it, so no UI/intent/auto entry path
 * can slip through. In CORE it always returns false and changes nothing.
 *
 * @return true if the feature is unavailable in this flavor (caller must not proceed).
 */
internal fun proFeatureUnavailable(context: Context, featureLabel: String): Boolean {
    if (!BuildConfig.IS_PRO) return false
    // Toast must be posted on the main looper — service start() is sometimes reached off it.
    Handler(Looper.getMainLooper()).post {
        Toast.makeText(
            context.applicationContext,
            "$featureLabel in MOTO-HUB Pro arriverà a breve (per ora usa MOTO-HUB Core).",
            Toast.LENGTH_LONG
        ).show()
    }
    return true
}
