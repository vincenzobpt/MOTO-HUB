package io.motohub.android.ipc;

import io.motohub.android.ipc.MotorcycleSummary;
import io.motohub.android.ipc.EncoderProfileParcel;
import io.motohub.android.ipc.ITBoxSessionListener;

/**
 * Bound-service contract exposing Core's already-established T-Box transport
 * (EasyConn session, H.264 delivery) to another app's process. Core owns the
 * GPL-3.0-derived connection; callers never touch it directly.
 */
interface ITBoxTransportService {
    boolean isSessionReady();

    /** Null when no T-Box session is currently active. Contains no credentials. */
    MotorcycleSummary getActiveMotorcycle();

    /** Null when no session is active or the video area has not yet been negotiated. */
    EncoderProfileParcel getNegotiatedEncoderProfile();

    /** Same call shape as the in-process VideoAccessUnitSink.offerAccessUnit(). */
    boolean offerAccessUnit(in byte[] accessUnit);

    void registerSessionListener(ITBoxSessionListener listener);
    void unregisterSessionListener(ITBoxSessionListener listener);
}
