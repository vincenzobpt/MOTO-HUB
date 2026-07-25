package io.motohub.android.ipc;

/**
 * State values match io.motohub.android.ipc.AndroidAutoIpcState's IDLE/PREPARING/
 * RECEIVER_READY/STREAMING/STOPPED/FAILED constants. message is only populated for
 * STOPPED/FAILED.
 */
oneway interface IAndroidAutoStateListener {
    void onStateChanged(int state, String message);
}
