// The single seam between the shared UI (HubViewModel) and the flavor-specific way a T-Box
// session is established:
//   CORE flavor → LocalTBoxSessionEstablisher: joins Wi-Fi + runs EasyConn discovery locally via
//                 the GPL hudlib transport (RideDaemonTransport).
//   PRO flavor  → AidlTBoxSessionEstablisher: contains no GPL code; asks CORE to do all of the
//                 above over the AIDL bridge, then installs an AIDL-backed transport so PRO can
//                 still push encoded video to the (Core-owned) T-Box.
// createTBoxSessionEstablisher(context) is defined once per flavor (src/core and src/pro).
package io.motohub.android.tbox

import android.content.Context
import io.motohub.android.session.MotorcycleProfile

interface TBoxSessionEstablisher {
    val transport: TBoxTransport
    val networkConnector: TBoxNetworkConnector

    /**
     * Establishes the connection and installs the session into TBoxSessionRegistry.
     * Returns true when the session is READY. The callbacks let the shared UI keep its exact
     * phase/error presentation without knowing whether the work ran locally or in Core.
     */
    suspend fun connectAndInstall(
        profile: MotorcycleProfile,
        onNetworkConnected: suspend () -> Unit,
        onNetworkError: (Throwable) -> Unit,
        onDiscoveryError: (Throwable) -> Unit
    ): Boolean

    /**
     * Best-effort signal that an in-flight [connectAndInstall] should abort as soon as possible.
     * CORE's connect runs entirely as cancellable local coroutines, so cancelling the caller's Job
     * (as HubViewModel.cancelConnection already does) is sufficient there and this is a no-op.
     * PRO's connect blocks on a synchronous AIDL call into Core; cancelling PRO's own Job does NOT
     * interrupt that in-flight Binder call (no suspension point inside it), so PRO must actively
     * ask Core to abort its side — see AidlTBoxSessionEstablisher.
     */
    fun cancelPendingConnect() = Unit
}
