package io.motohub.android.feature.ridedashboard

import android.content.Context
import io.motohub.android.aa.AaSelfMode
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

/** CORE flavor factory: the real local self-mode-trigger coordinator (ported unchanged from
 *  MainActivity.startRideDashboardAndroidAuto). */
fun createRideDashboardLocalAndroidAutoTrigger(context: Context): RideDashboardLocalAndroidAutoTrigger =
    CoreRideDashboardLocalAndroidAutoTrigger(context.applicationContext)

class CoreRideDashboardLocalAndroidAutoTrigger(
    private val context: Context
) : RideDashboardLocalAndroidAutoTrigger {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val launchPending = AtomicBoolean(false)

    override fun trigger() {
        if (!launchPending.compareAndSet(false, true)) {
            ProjectionEventLog.warning("RIDE_AA", "Embedded Android Auto launch is already pending.")
            return
        }
        scope.launch {
            val state = withTimeoutOrNull(RECEIVER_READY_TIMEOUT_MS) {
                RideDashboardAndroidAutoRuntime.state
                    .dropWhile {
                        it is RideDashboardAndroidAutoState.Idle ||
                            it is RideDashboardAndroidAutoState.Failed
                    }
                    .first {
                        it is RideDashboardAndroidAutoState.ReceiverReady ||
                            it is RideDashboardAndroidAutoState.Failed
                    }
            }
            when (state) {
                RideDashboardAndroidAutoState.ReceiverReady -> {
                    ProjectionEventLog.record("RIDE_AA", "Embedded AAP receiver ready; triggering Android Auto.")
                    delay(RECEIVER_SETTLE_MS)
                    if (RideDashboardAndroidAutoRuntime.state.value is
                        RideDashboardAndroidAutoState.ReceiverReady
                    ) {
                        AaSelfMode.trigger(
                            context = context,
                            log = { ProjectionEventLog.record("RIDE_AA", it) }
                        )
                    }
                }
                is RideDashboardAndroidAutoState.Failed -> Unit
                else -> ProjectionEventLog.error("RIDE_AA", "Timed out while preparing embedded Android Auto.")
            }
            launchPending.set(false)
        }
    }

    private companion object {
        const val RECEIVER_READY_TIMEOUT_MS = 10_000L
        const val RECEIVER_SETTLE_MS = 900L
    }
}
