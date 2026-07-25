package io.motohub.android.ipc;

import io.motohub.android.ipc.MotorcycleSummary;
import io.motohub.android.ipc.EncoderProfileParcel;
import io.motohub.android.ipc.MotorcycleConnectRequest;
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

    /**
     * Starts the T-Box video session inside Core: EasyConn video start + live TFT area
     * negotiation (both need the GPL transport Core owns). Blocking; returns the negotiated
     * capture area (width/height are the raw TFT area — the caller applies its own quality and
     * bitrate), or null if negotiation failed. offerAccessUnit() only delivers frames after this
     * has returned successfully.
     */
    EncoderProfileParcel startVideoSession();

    /** Same call shape as the in-process VideoAccessUnitSink.offerAccessUnit(). */
    boolean offerAccessUnit(in byte[] accessUnit);

    /**
     * Establishes the T-Box connection inside Core's process (Wi-Fi join + EasyConn discovery,
     * which need the GPL transport and the socket binding Core owns) and installs the session.
     * Blocking; returns true once the session is READY. Session ready/lost is also reported to
     * registered listeners.
     */
    boolean connect(in MotorcycleConnectRequest request);

    /**
     * Aborts an in-flight connect() as quickly as possible and cleans up any partial state it
     * left behind. Safe to call even if no connect() is in flight, or if it already finished.
     * A blocked connect() call returns false shortly after this is received.
     */
    void cancelConnect();

    /** Tears down the active T-Box session established via connect(). */
    void disconnect();

    void registerSessionListener(ITBoxSessionListener listener);
    void unregisterSessionListener(ITBoxSessionListener listener);
}
