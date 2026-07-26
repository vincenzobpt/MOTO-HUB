package io.motohub.android.ipc;

import android.view.Surface;
import io.motohub.android.ipc.IAndroidAutoStateListener;

/**
 * Bound-service contract exposing Core's AGPL-3.0-derived Android Auto AAP
 * receiver (headunit-revived self-mode technique) to another app's process.
 * The caller supplies its own render target; Core's compositor draws the
 * decoded Android Auto video into it directly (Surface is Parcelable and
 * shareable across processes for exactly this purpose).
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

    void registerStateListener(IAndroidAutoStateListener listener);
    void unregisterStateListener(IAndroidAutoStateListener listener);
}
