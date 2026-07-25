package io.motohub.android.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log

/**
 * Binds to Core's IpcBridgeService for T-Box transport access. The caller's OWN manifest must
 * still declare (neither can be enforced from here — omitting either fails differently):
 *   <queries><package android:name="io.motohub.android"/></queries>            (or bindService() silently returns false)
 *   <uses-permission android:name="io.motohub.android.permission.BIND_CORE_SERVICE"/>  (or bindService() throws SecurityException)
 */
class TBoxTransportClient(
    private val context: Context,
    private val corePackage: String = "io.motohub.android",
    private val onSessionReady: () -> Unit = {},
    private val onSessionLost: () -> Unit = {}
) {
    private var service: ITBoxTransportService? = null
    private var bound = false

    private val sessionListener = object : ITBoxSessionListener.Stub() {
        override fun onSessionReady() = this@TBoxTransportClient.onSessionReady()
        override fun onSessionLost() = this@TBoxTransportClient.onSessionLost()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val bound = ITBoxTransportService.Stub.asInterface(binder)
            service = bound
            bound.registerSessionListener(sessionListener)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    /** Returns false if the bind call itself failed (see manifest requirements above). */
    fun bind(): Boolean {
        val intent = Intent(IpcBridgeContract.BIND_ACTION_TBOX_TRANSPORT).apply {
            setPackage(corePackage)
        }
        return try {
            val ok = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            bound = ok
            ok
        } catch (e: SecurityException) {
            Log.e(TAG, "bind denied - is BIND_CORE_SERVICE declared in the caller's manifest? ${e.message}")
            false
        }
    }

    fun unbind() {
        if (!bound) return
        service?.unregisterSessionListener(sessionListener)
        context.unbindService(connection)
        service = null
        bound = false
    }

    /** True once the bound-service connection has actually been established (bind() is async). */
    val isConnected: Boolean get() = service != null

    fun isSessionReady(): Boolean = service?.isSessionReady() ?: false

    fun getActiveMotorcycle(): MotorcycleSummary? = service?.getActiveMotorcycle()

    /**
     * Asks Core to start the T-Box video session (EasyConn video + TFT area negotiation) and
     * returns the negotiated capture area, or null if it failed / the service isn't bound.
     * Must succeed before offerAccessUnit() delivers any frames.
     */
    fun startVideoSession(): EncoderProfileParcel? = service?.startVideoSession()

    fun offerAccessUnit(accessUnit: ByteArray): Boolean = service?.offerAccessUnit(accessUnit) ?: false

    /** Asks Core to establish the T-Box connection in its own process (it owns the GPL transport). */
    fun connect(request: MotorcycleConnectRequest): Boolean = service?.connect(request) ?: false

    /** Aborts an in-flight connect() on Core's side; see ITBoxTransportService.aidl. */
    fun cancelConnect() {
        service?.cancelConnect()
    }

    fun disconnect() {
        service?.disconnect()
    }

    private companion object {
        const val TAG = "TBoxTransportClient"
    }
}
