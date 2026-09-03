// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.androidauto

import android.content.Context
import io.motohub.android.aa.AaSelfMode
import io.motohub.android.feature.settings.MotoHubSettings
import io.motohub.android.session.ProjectionEventLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CORE flavor factory. CORE contains the AGPL AA receiver and drives the full session locally —
 * this is the same start/self-mode-trigger/stop coordination that used to live directly in
 * MainActivity, moved here so MainActivity (shared code) never needs to reference
 * AndroidAutoSessionService/AaSelfMode (both Core-only now).
 */
fun createAndroidAutoCoreBridge(context: Context): AndroidAutoCoreBridge =
    CoreAndroidAutoCoreBridge(context.applicationContext)

class CoreAndroidAutoCoreBridge(private val context: Context) : AndroidAutoCoreBridge {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val launchPending = AtomicBoolean(false)

    override val delegatesToCore: Boolean = false

    override fun start(onFailure: (String) -> Unit) {
        if (!launchPending.compareAndSet(false, true)) {
            ProjectionEventLog.warning("ANDROID_AUTO", "Start request ignored because another launch is pending.")
            return
        }
        ProjectionEventLog.record("ANDROID_AUTO", "User requested Android Auto startup.")
        AndroidAutoSessionService.start(context)
        scope.launch {
            val state = withTimeoutOrNull(RECEIVER_READY_TIMEOUT_MS) {
                // A foreground service is started asynchronously. Ignore terminal state left by
                // the previous launch; otherwise `first` can consume that old failure before the
                // new service has published Preparing and no self-mode trigger is sent.
                AndroidAutoRuntime.state
                    .dropWhile {
                        it is AndroidAutoRuntimeState.Idle ||
                            it is AndroidAutoRuntimeState.Stopped ||
                            it is AndroidAutoRuntimeState.Failed
                    }
                    .first {
                        it is AndroidAutoRuntimeState.ReceiverReady ||
                            it is AndroidAutoRuntimeState.Failed
                    }
            }
            when (state) {
                AndroidAutoRuntimeState.ReceiverReady -> {
                    ProjectionEventLog.record("ANDROID_AUTO", "AAP receiver is ready; waiting before self-mode trigger.")
                    delay(RECEIVER_SETTLE_MS)
                    if (AndroidAutoRuntime.state.value is AndroidAutoRuntimeState.ReceiverReady) {
                        AaSelfMode.trigger(context = context) { ProjectionEventLog.record("AAP", it) }
                    }
                }
                is AndroidAutoRuntimeState.Failed -> Unit
                else -> {
                    ProjectionEventLog.error("ANDROID_AUTO", "Timed out while preparing Android Auto.")
                    AndroidAutoSessionService.stop(
                        context,
                        "Android Auto did not become ready in time."
                    )
                }
            }
            launchPending.set(false)
            ProjectionEventLog.debug("ANDROID_AUTO", "Launch coordinator released.")
        }
    }

    override fun stop() {
        AndroidAutoSessionService.stop(context, "Android Auto stopped by the user.")
    }

    override fun release() = Unit

    private companion object {
        const val RECEIVER_READY_TIMEOUT_MS = 10_000L
        const val RECEIVER_SETTLE_MS = 900L
    }
}

/** Used where a feature doesn't need Core-side delegation at all — e.g. an embedded
 *  AA panel, which Core already runs directly (not via this bridge shape). */
object NoopAndroidAutoCoreBridge : AndroidAutoCoreBridge {
    override val delegatesToCore: Boolean = false
    override fun start(onFailure: (String) -> Unit) = Unit
    override fun stop() = Unit
    override fun release() = Unit
}
