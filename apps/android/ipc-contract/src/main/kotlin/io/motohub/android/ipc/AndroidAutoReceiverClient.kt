// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import android.view.Surface

/**
 * Binds to Core's IpcBridgeService for Android Auto control (full session → real T-Box or a
 * decoded output Surface for PRO's embedded Dashboard, see IAndroidAutoReceiverService.aidl).
 * The caller's OWN manifest must still declare (neither can
 * be enforced from here — omitting either fails differently):
 *   <queries><package android:name="io.motohub.android"/></queries>            (or bindService() silently returns false)
 *   <uses-permission android:name="io.motohub.android.permission.BIND_CORE_SERVICE"/>  (or bindService() throws SecurityException)
 */
class AndroidAutoReceiverClient(
    private val context: Context,
    private val corePackage: String = "io.motohub.android",
    private val onStateChanged: (state: Int, message: String) -> Unit = { _, _ -> }
) {
    private var service: IAndroidAutoReceiverService? = null
    private var bound = false

    private val stateListener = object : IAndroidAutoStateListener.Stub() {
        override fun onStateChanged(state: Int, message: String) =
            this@AndroidAutoReceiverClient.onStateChanged(state, message)
    }

    /**
     * Counts the connections this client has made, so callers can tell "still the same Core" from
     * "Core died and came back".
     *
     * The binding itself survives a Core restart — Android re-connects it — but everything the
     * caller registered ON that binder does not: the remote callback lists live in Core's process
     * and the new one starts empty. A listener registered once at session start therefore went
     * quiet for the rest of the ride, with nothing anywhere saying so. Watching this value is how
     * a caller knows to register again.
     */
    @Volatile
    var connectionGeneration: Int = 0
        private set

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val bound = IAndroidAutoReceiverService.Stub.asInterface(binder)
            service = bound
            connectionGeneration++
            bound.registerStateListener(stateListener)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    fun bind(): Boolean {
        val intent = Intent(IpcBridgeContract.BIND_ACTION_ANDROID_AUTO_RECEIVER).apply {
            setPackage(corePackage)
        }
        return try {
            // BIND_ALLOW_ACTIVITY_STARTS keeps Core eligible to start its Android Auto receiver
            // while PRO is foreground and the output Surface is being attached.
            val ok = context.bindService(
                intent,
                connection,
                Context.BIND_AUTO_CREATE or Context.BIND_ALLOW_ACTIVITY_STARTS
            )
            bound = ok
            ok
        } catch (e: SecurityException) {
            Log.e(TAG, "bind denied - is BIND_CORE_SERVICE declared in the caller's manifest? ${e.message}")
            false
        }
    }

    fun unbind() {
        if (!bound) return
        service?.unregisterStateListener(stateListener)
        context.unbindService(connection)
        service = null
        bound = false
    }

    /** True once the bound-service connection is established (bind() is async). */
    val isConnected: Boolean get() = service != null

    /** Asks Core to decode Android Auto into a caller-owned Surface. */
    fun attachOutputSurface(surface: Surface, width: Int, height: Int): Boolean =
        service?.attachOutputSurface(surface, width, height) ?: false

    fun detachOutputSurface() {
        service?.detachOutputSurface()
    }

    fun attachPreviewSurface(surface: Surface, width: Int, height: Int): Boolean =
        service?.attachPreviewSurface(surface, width, height) ?: false

    fun detachPreviewSurface() {
        service?.detachPreviewSurface()
    }

    fun applyAndroidAutoSettings(settings: AndroidAutoSettingsParcel) {
        service?.applyAndroidAutoSettings(settings)
    }

    fun setNightMode(isNight: Boolean): Boolean = service?.setNightMode(isNight) ?: false

    fun sendKey(keycode: Int): Boolean = service?.sendKey(keycode) ?: false

    fun sendScroll(delta: Int): Boolean = service?.sendScroll(delta) ?: false

    fun sendTouch(action: Int, x: Int, y: Int): Boolean = service?.sendTouch(action, x, y) ?: false

    fun sendPreviewTouch(action: Int, x: Int, y: Int): Boolean =
        service?.sendPreviewTouch(action, x, y) ?: false

    fun startFullSession(): Boolean = service?.startFullSession() ?: false

    fun stopFullSession() {
        service?.stopFullSession()
    }

    /** Safe against an older Core that predates the guidance methods: returns false instead
     *  of throwing when the remote transaction is unknown. */
    fun registerNavigationGuidanceListener(listener: INavigationGuidanceListener): Boolean =
        runCatching { service?.registerNavigationGuidanceListener(listener) }.isSuccess &&
            service != null

    fun unregisterNavigationGuidanceListener(listener: INavigationGuidanceListener) {
        runCatching { service?.unregisterNavigationGuidanceListener(listener) }
    }

    /**
     * Watches the handlebar gestures Core recognises, so the teaching wizard can see the press
     * the rider was just asked to make. Same older-Core tolerance as the guidance listener above:
     * false rather than a throw, and the wizard then behaves exactly as it did before this
     * existed.
     */
    fun registerHandlebarGestureListener(listener: IHandlebarGestureListener): Boolean =
        runCatching { service?.registerHandlebarGestureListener(listener) }.isSuccess &&
            service != null

    fun unregisterHandlebarGestureListener(listener: IHandlebarGestureListener) {
        runCatching { service?.unregisterHandlebarGestureListener(listener) }
    }

    /**
     * Asks Core to claim Android Auto's audio streams and hand them over, or to stop. False
     * against a Core that predates the call, or when nothing is bound - the same tolerance as
     * the listeners above, so a companion on a newer contract degrades to hearing nothing.
     */
    fun setProjectionAudioWanted(wanted: Boolean): Boolean =
        runCatching { service?.setProjectionAudioWanted(wanted) }.getOrNull() ?: false

    /** The audio pipe, framed by [ProjectionAudioFraming]; null when Core cannot provide one. */
    fun openProjectionAudioStream(): android.os.ParcelFileDescriptor? =
        runCatching { service?.openProjectionAudioStream() }.getOrNull()

    fun closeProjectionAudioStream() {
        runCatching { service?.closeProjectionAudioStream() }
    }

    private companion object {
        const val TAG = "AndroidAutoReceiverClient"
    }
}
