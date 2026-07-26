package io.motohub.android.ipc

/** Int constants used by IAndroidAutoStateListener.onStateChanged — shared vocabulary
 *  for both sides of the Binder call, since AIDL has no sealed-class equivalent. */
object AndroidAutoIpcState {
    const val IDLE = 0
    const val PREPARING = 1
    const val RECEIVER_READY = 2
    const val STREAMING = 3
    const val STOPPED = 4
    const val FAILED = 5
}

/** Also used for io.motohub.android.ipc.IpcBridgeService's own internal AA session bookkeeping. */
object IpcBridgeContract {
    /**
     * Two distinct actions, not one action + an extra: Android caches the IBinder returned by
     * onBind() per Intent.filterEquals(), which ignores extras. A single shared action would
     * make the second bind (regardless of its extra) silently receive the first caller's binder.
     */
    const val BIND_ACTION_TBOX_TRANSPORT = "io.motohub.android.ipc.BIND_TBOX_TRANSPORT"
    const val BIND_ACTION_ANDROID_AUTO_RECEIVER = "io.motohub.android.ipc.BIND_ANDROID_AUTO_RECEIVER"

    /** Signature-level permission a caller must hold to bind IpcBridgeService. */
    const val BIND_PERMISSION = "io.motohub.android.permission.BIND_CORE_SERVICE"
}
