package io.motohub.android.feature.ridedashboard

import android.content.Context
import io.motohub.android.androidauto.AndroidAutoCoreBridge
import io.motohub.android.androidauto.NoopAndroidAutoCoreBridge

/**
 * CORE flavor factory. CORE runs Ride Dashboard (with embedded Android Auto) locally already, so
 * this is a no-op — MainActivity's shared code takes its own local RideDashboardSessionService
 * path when [AndroidAutoCoreBridge.delegatesToCore] is false.
 */
fun createRideDashboardEmbeddedAaBridge(context: Context): AndroidAutoCoreBridge = NoopAndroidAutoCoreBridge
