package io.motohub.android.ipc;

/** Notifies a bound client when Core's T-Box session becomes available or is lost. */
oneway interface ITBoxSessionListener {
    void onSessionReady();
    void onSessionLost();
}
