package io.motohub.android.ipc;

import android.view.Surface;
import io.motohub.android.ipc.IAndroidAutoStateListener;
import io.motohub.android.ipc.AndroidAutoSettingsParcel;

/**
 * Bound-service contract exposing Core's AGPL-3.0-derived Android Auto AAP
 * receiver (headunit-revived self-mode technique) to another app's process.
 *
 * Two independent modes, both guarded against the same underlying resource
 * (Core's AA receiver binds one fixed local port; only one mode can be
 * active at a time — attempting the second while the first is active fails
 * cleanly, it does not crash either side):
 *
 * - attachOutputSurface/detachOutputSurface: the caller supplies its own
 *   render target; Core's compositor draws the decoded Android Auto video
 *   into it directly (Surface is Parcelable and shareable across processes
 *   for exactly this purpose). No T-Box involved — this is for an
 *   embedded/local preview inside the caller's own UI.
 * - startFullSession/stopFullSession: triggers Core's own existing
 *   full-Android-Auto pipeline (io.motohub.android.androidauto.
 *   AndroidAutoSessionService) exactly as Core's own UI does today —
 *   decoded video is encoded and pushed to the real T-Box hardware. The
 *   caller does not supply a Surface; it only starts/stops the session and
 *   observes state via the listener below.
 *
 * sendKey/sendScroll are declared for forward compatibility but return false
 * today: Core's current public AA input channel only implements touch
 * (see io.motohub.android.aa.AaInput). Key/scroll dispatch needs its own
 * public implementation before these do anything.
 */
interface IAndroidAutoReceiverService {
    boolean attachOutputSurface(in Surface surface, int width, int height);
    void detachOutputSurface();

    /** action: 0=DOWN, 1=UP, 2=MOVE (see io.motohub.android.aa.AaInput). Coordinates in the surface's own space. */
    boolean sendTouch(int action, int x, int y);
    boolean sendKey(int keycode);
    boolean sendScroll(int delta);

    /** Applies the caller's Android-Auto settings snapshot to Core before startFullSession, so
     *  the session Core runs honors settings configured in the companion app. Call before
     *  startFullSession(). */
    void applyAndroidAutoSettings(in AndroidAutoSettingsParcel settings);

    /** Toggles day/night on Core's currently-running Android Auto session. Returns false if no
     *  session is active. */
    boolean setNightMode(boolean isNight);

    /** Starts/stops Core's own full Android Auto session (video goes to the real T-Box, not
     *  to a caller-supplied Surface). See class doc above. */
    boolean startFullSession();
    void stopFullSession();

    void registerStateListener(IAndroidAutoStateListener listener);
    void unregisterStateListener(IAndroidAutoStateListener listener);

    /**
     * Starts/stops Core's own Ride Dashboard session with Android Auto as the embedded map
     * panel (io.motohub.android.feature.ridedashboard.RideDashboardSessionService, map source
     * ANDROID_AUTO) — the composited dashboard+AA video is rendered and pushed to the real T-Box
     * entirely inside Core, exactly as when Core's own UI runs it. A caller with no local GPL/AGPL
     * code (Pro) cannot run this panel itself: embedded AA needs the same AGPL receiver as
     * startFullSession, decoding into the SAME compositor used by the dashboard's own renderer,
     * which only exists in Core. Guarded against the same port-5288 resource as startFullSession —
     * only one of startFullSession/startEmbeddedDashboardSession can be active at a time.
     * State is reported on a listener channel separate from registerStateListener's (that one is
     * full-AA-screen state; conflating the two would make Pro's Android Auto screen react to a
     * Ride Dashboard session it didn't start, or vice versa).
     */
    boolean startEmbeddedDashboardSession();
    void stopEmbeddedDashboardSession();

    void registerEmbeddedDashboardStateListener(IAndroidAutoStateListener listener);
    void unregisterEmbeddedDashboardStateListener(IAndroidAutoStateListener listener);
}
