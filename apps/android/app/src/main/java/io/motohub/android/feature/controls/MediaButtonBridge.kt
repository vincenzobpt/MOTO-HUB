// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.controls

import io.motohub.android.i18n.motoHubText

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import io.motohub.android.R
import io.motohub.android.androidauto.AndroidAutoInputCodes
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

private const val DOUBLE_PRESS_VOLUME_STEPS = 3

/** Converts the motorcycle's Bluetooth AVRCP gestures into active-mode input events. */
class MediaButtonBridge(
    private val context: Context,
    private val log: (String) -> Unit,
    private val targetName: String = TARGET_ANDROID_AUTO,
    private val gestureHandler: ((HandlebarGesture) -> Boolean)? = null
) {
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager by lazy { context.getSystemService(AudioManager::class.java) }
    /** AVRCP "now playing" appearance only — the motorcycle must see a normal media player. */
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    /**
     * What the FOCUS request and the silent track actually use. Navigation-guidance usage ducks
     * other players instead of competing media-vs-media: a media-usage MAY_DUCK request is the
     * weakest possible claim and loses to any real player, which is how the buttons used to die
     * the moment Spotify started. Ported from open-cfmoto's navAttrs.
     */
    private val navAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var session: MediaSession? = null
    private var volumeProvider: VolumeProvider? = null
    private var volumeObserver: ContentObserver? = null
    private var focusRequest: AudioFocusRequest? = null
    private var silentTrack: AudioTrack? = null
    private var pinnedVolume = -1
    private var previousVolume = -1
    private var pendingCapture = false
    /** Set once the track appearance has been put on the AVRCP wire, so the keep-alive stops
     *  re-announcing it every four seconds — see [publishMetadata]. */
    private var appearancePublished = false
    /** Same idea for the MediaStyle notification: re-posting an identical one every tick buys
     *  nothing and is the other half of what [refreshPlayingAppearance] used to redo. */
    private var notificationPosted = false
    @Volatile private var ignoreVolumeChanges = false
    /** Set at audio-focus loss, cleared once the reclaim has re-pinned the volume. The
     *  assistant's ducking moves the media stream in exactly that window (field log
     *  2026-07-31: 119 -> 100, a delta of -19 that no 10-step rocker press can produce), and
     *  reading that through [consumeVolumeChange] injected a phantom rotary scroll into
     *  Android Auto while the rider was mid-sentence. [ignoreVolumeChanges] cannot cover
     *  this: it only wraps each setStreamVolume call for the BT absolute-volume echo, while
     *  the focus-loss window is ~1s wide. A real press dropped in that second is the cheaper
     *  mistake — the rider is talking to the assistant, not scrolling. */
    @Volatile private var focusLossVolumeGuard = false
    /**
     * The same suppression for a DUCK, which is not a focus loss and so never armed the guard
     * above.
     *
     * A ducking app (a navigation prompt, a notification sound, the assistant on OEM stacks that
     * only ever send CAN_DUCK) does not take our focus, but with Bluetooth absolute volume its
     * duck is still written into STREAM_MUSIC - the one stream the pin watches - and comes back
     * here as a delta of exactly the shape a rocker press has. That is the "rotary ghost": a
     * scroll nobody performed, arriving whenever the phone made a sound. Timed rather than
     * latched, because a duck has no matching "un-duck" callback to clear it on.
     */
    @Volatile private var duckVolumeGuardUntil = 0L
    /**
     * Whether our focus request is currently granted. A duck leaves this true: we still hold the
     * request, someone else is merely louder for a moment. Only a full loss clears it, and only
     * then is re-requesting the focus the right thing to do - see [requestMediaFocus].
     */
    @Volatile private var focusHeld = false
    private val volumePoll = object : Runnable {
        override fun run() {
            if (!captureActive) return
            consumeVolumeChange()
            handler.postDelayed(this, VOLUME_POLL_INTERVAL_MILLIS)
        }
    }

    @Volatile var captureActive: Boolean = false
        private set

    fun start() {
        handler.post {
            if (session != null) return@post
            try {
                session = MediaSession(context, "MOTO-HUB handlebar controls").apply {
                    setCallback(callback)
                    setPlaybackState(
                        PlaybackState.Builder()
                            .setActions(mediaActions())
                            .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                            .build()
                    )
                    setPlaybackToLocal(audioAttributes)
                }
                bridges[targetName] = this
                registerVolumeObserver()
                log("[BTN] AVRCP bridge registered for $targetName; capture is disabled until it streams")
                if (pendingCapture) {
                    pendingCapture = false
                    enableCapture()
                }
            } catch (failure: Throwable) {
                log("[BTN] Unable to create AVRCP bridge: ${failure.message}")
            }
        }
    }

    fun setCaptureActive(enabled: Boolean) {
        handler.post {
            pendingCapture = enabled
            if (captureActive == enabled) return@post
            if (enabled) enableCapture() else disableCapture()
        }
    }

    /** Forces an already connected AVRCP peer to re-read this session as the active media player. */
    fun reassertCaptureAfterTransportReady() {
        handler.postDelayed({
            if (!captureActive || session == null) {
                log("[BTN] $targetName media re-assert skipped because capture is not active")
                return@postDelayed
            }
            log("[BTN] $targetName transport ready; re-asserting media focus for AVRCP")
            cancelMediaNotification()
            // Soft re-announce: toggle the session's active state to give the AVRCP peer a
            // play-state transition to notice, without abandoning and re-requesting audio
            // focus in between (see requestMediaFocus() for why that combination could stick).
            // Keep-alive is paused for the flip: its refresh forces isActive=true, and a tick
            // landing inside the gap would collapse the transition the dash needs to notice.
            stopKeepAlive()
            session?.isActive = false
            handler.postDelayed({
                if (!captureActive || session == null) return@postDelayed
                session?.isActive = true
                // Forced: the whole point of this path is a transition the AVRCP peer notices.
                publishMetadata(force = true)
                postMediaNotification(force = true)
                if (usesVolumeGestures) pinVolume()
                startKeepAlive()
                log("[BTN] $targetName media focus re-asserted; handlebar input ready")
            }, REASSERT_GAP_MILLIS)
        }, REASSERT_SETTLE_MILLIS)
    }

    fun stop() {
        handler.post {
            pendingCapture = false
            selectDownAt = 0L
            repeatLatched.clear()
            trackDownAt.clear()
            cancelPendingTaps()
            disableCapture()
            unregisterVolumeObserver()
            try { session?.isActive = false } catch (_: Throwable) {}
            try { session?.release() } catch (_: Throwable) {}
            session = null
            bridges.remove(targetName, this)
            log("[BTN] AVRCP bridge stopped for $targetName")
        }
    }

    /** Whether this phone should hold the media volume in order to read volume-key presses. */
    private var usesVolumeGestures = true

    /** True only while the calibration wizard is on screen asking the rider for presses. */
    private var calibrationCapturing = false

    /**
     * Opens and closes the window in which the volume pin is held unconditionally, so the wizard
     * can actually see an AVRCP rocker - see the note in [volumeGesturesInUse].
     *
     * Opening it also forgets a previous "this dash has no rocker" observation: the rider is
     * answering that question by hand right now, and the stored answer is checked ahead of the
     * taught gestures - so without this, a volume gesture taught in the wizard would be thrown
     * away again the moment the wizard closed.
     */
    fun setCalibrating(active: Boolean) {
        handler.post {
            if (calibrationCapturing == active) return@post
            calibrationCapturing = active
            if (active) HandlebarCalibration.clearVolumeRockerSilent(context)
            refreshVolumeGestureUse()
        }
    }

    /**
     * Re-evaluates [usesVolumeGestures] against the current calibration, live. Computed only at
     * [enableCapture], the answer went stale the moment the rider taught the handlebar with a
     * session running — on a dash whose volume presses never arrive (CFDL16) the pin then kept
     * hijacking the phone's own volume keys as fake handlebar presses until the NEXT session.
     * Called when the calibration wizard closes and after a companion sync imports calibration.
     */
    fun refreshVolumeGestureUse() {
        handler.post {
            if (!captureActive) return@post
            val use = volumeGesturesInUse()
            if (use == usesVolumeGestures) return@post
            usesVolumeGestures = use
            if (use) {
                if (previousVolume < 0) {
                    previousVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                }
                pinVolume()
                log("[BTN] calibration says volume keys arrive; media volume pinned")
            } else {
                pinnedVolume = -1
                if (previousVolume >= 0) {
                    ignoreVolumeChanges = true
                    try {
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, previousVolume, 0)
                    } catch (_: Throwable) {
                    } finally {
                        handler.postDelayed({ ignoreVolumeChanges = false }, REPIN_IGNORE_MILLIS)
                    }
                }
                log("[BTN] calibration says volume keys never arrive; pin released, phone volume keys are yours again")
            }
        }
    }

    /** Last media volume seen by the observer, for the diagnostic trace only. */
    private var lastObservedVolume = -1

    private var volumeSilenceProbe: Runnable? = null

    /**
     * Settles "does this handlebar even have a volume rocker?" by watching, instead of waiting
     * for the rider to open the calibration wizard and say so.
     *
     * A rider who never calibrates keeps the assumed rocker forever, so on a dashboard that keeps
     * its rocker to itself the media volume stays pinned for the whole session and the PHONE's
     * own volume buttons keep being read as handlebar presses. That is the CFDL16 complaint, and
     * before this it only ever ended by hand.
     *
     * Armed only where the answer is genuinely unknown: capture running, pin taken, nothing
     * taught, nothing already inferred. Any real volume press cancels it - and clears a previous
     * inference - so a rocker that is merely idle for two minutes is never mistaken for an absent
     * one.
     */
    private fun scheduleVolumeSilenceProbe() {
        cancelVolumeSilenceProbe()
        if (HandlebarCalibration.isCalibrated(context)) return
        if (HandlebarCalibration.isVolumeRockerSilent(context)) return
        val probe = Runnable {
            volumeSilenceProbe = null
            if (!captureActive || !usesVolumeGestures) return@Runnable
            if (HandlebarCalibration.isCalibrated(context)) return@Runnable
            log(
                "[BTN] no volume press in ${VOLUME_SILENCE_PROBE_MILLIS / 1000}s of capture; " +
                    "treating this handlebar as having no volume rocker and releasing the pin"
            )
            HandlebarCalibration.noteVolumeRockerSilent(context)
            refreshVolumeGestureUse()
        }
        volumeSilenceProbe = probe
        handler.postDelayed(probe, VOLUME_SILENCE_PROBE_MILLIS)
    }

    private var bluetoothWaitReceiver: BroadcastReceiver? = null

    /**
     * Waits for Bluetooth to come back, then starts capture.
     *
     * Without this the skip above would trade one fault for another: before it, a session started
     * with Bluetooth off left the bridge running uselessly but ready, so switching Bluetooth on
     * mid-ride made the handlebar work. Refusing to start and never looking again would have made
     * that recoverable case permanent until the rider restarted the whole session.
     *
     * Only the adapter turning on is watched. A permission cannot be granted without leaving the
     * app, and coming back re-runs the session's own start path.
     */
    /**
     * Re-attempts a capture that was skipped for want of the Bluetooth grant.
     *
     * [awaitBluetooth] watches the ADAPTER, and a permission arriving is not an adapter event -
     * nothing is broadcast when a rider answers a runtime dialog. Which is exactly the sequence
     * the companion app's card now produces: it sends the rider to this app to grant the
     * permission while a session is already running, and without this the handlebar would stay
     * dead until the next session start, on the ride they granted it for.
     */
    private fun retryAfterBluetoothGrant() {
        handler.post {
            if (!pendingCapture || captureActive) return@post
            if (!BluetoothStatus.canReceiveHandlebarKeys(context)) return@post
            log("[BTN] Bluetooth is allowed now; starting handlebar capture")
            enableCapture()
        }
    }

    /**
     * Re-applies the input protocol to a session already running.
     *
     * The mode is read at [enableCapture] and nowhere else, so a rider who switched protocol
     * mid-session changed a preference and nothing else: the bridge kept decoding the old one
     * until the next session start, with no way to tell from the outside. Support 0df154af
     * switched AVRCP → HID → AVRCP inside eleven minutes while an Android Auto session ran, and
     * the log shows all three switches and no consequence of any of them.
     *
     * The un-blocking case is the one that matters most. AVRCP capture with no Bluetooth grant
     * ends in [awaitBluetooth], waiting for an adapter event that HID does not need and will
     * never produce - so switching to HID has to retry the capture itself, or the rider's fix for
     * the exact problem they were told about does nothing until they restart the session.
     */
    private fun inputModeChanged() {
        handler.post {
            val mode = HandlebarControlStore.inputMode(context)
            if (!captureActive) {
                if (!pendingCapture) return@post
                // Skipped earlier for want of Bluetooth: only HID can proceed without it, and
                // enableCapture re-checks the rest for itself.
                if (mode != HandlebarInputMode.HID) return@post
                log("[BTN] input protocol is HID now, which needs no Bluetooth grant; starting handlebar capture")
                enableCapture()
                return@post
            }
            log("[BTN] input protocol changed to ${mode.id} mid-session; re-applying it")
            // HID takes its volume keys as ordinary key events, so the pin that AVRCP needs is
            // wrong for it (and vice versa). This is the same live re-decision the calibration
            // wizard triggers, for the same reason.
            refreshVolumeGestureUse()
        }
    }

    private fun awaitBluetooth() {
        if (bluetoothWaitReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) != BluetoothAdapter.STATE_ON) return
                handler.post {
                    if (!pendingCapture || captureActive) return@post
                    if (!BluetoothStatus.canReceiveHandlebarKeys(context)) return@post
                    log("[BTN] Bluetooth is back; starting handlebar capture")
                    enableCapture()
                }
            }
        }
        runCatching {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            bluetoothWaitReceiver = receiver
        }.onFailure { log("[BTN] could not watch for Bluetooth coming back: ${it.message}") }
    }

    private fun cancelBluetoothWait() {
        bluetoothWaitReceiver?.let { receiver ->
            runCatching { context.unregisterReceiver(receiver) }
        }
        bluetoothWaitReceiver = null
    }

    private fun cancelVolumeSilenceProbe() {
        volumeSilenceProbe?.let(handler::removeCallbacks)
        volumeSilenceProbe = null
    }

    /**
     * A volume press did arrive: this handlebar has a rocker. Cancels the probe, and undoes an
     * earlier inference - a dash can start answering after a firmware update or a reconnect, and
     * an inference that could never be revoked would be worse than the assumption it replaced.
     */
    private fun noteVolumePressObserved() {
        cancelVolumeSilenceProbe()
        if (HandlebarCalibration.isVolumeRockerSilent(context)) {
            log("[BTN] a volume press arrived after all; restoring the volume rocker")
            HandlebarCalibration.clearVolumeRockerSilent(context)
        }
    }

    /**
     * True unless the rider has taught the app that their handlebar's volume presses never
     * arrive. Before calibration the answer is yes: most dashboards do send them, and a
     * missed gesture is worse than a held volume.
     */
    private fun volumeGesturesInUse(): Boolean {
        // HID mode gets real, discrete KEYCODE_VOLUME_UP/DOWN key-down events from
        // HandlebarHidCaptureService (see onHidKeyEvent) - there is nothing to infer from
        // watching the media stream drift, and pinning it here would fight the Accessibility
        // Service for ownership of the same physical press: a rider's "up" button would both
        // navigate AND visibly move the pinned volume, or worse, the pin's own re-write could
        // be misread as a second press. AVRCP's pin-and-watch trick is only needed because
        // AVRCP volume changes never arrive as ordinary key events in the first place.
        if (HandlebarControlStore.inputMode(context) == HandlebarInputMode.HID) return false
        // While the wizard is asking, the pin is taken whatever the stored answer says - because
        // otherwise the answer can never change. An AVRCP volume rocker reaches the phone as a
        // change in the stream level and NOTHING else, and [consumeVolumeChange] is the only
        // code that reads that, and it only runs while the pin is held. So a rider whose stored
        // calibration has no volume gesture could never teach one: every wizard step for the
        // wheel looked dead, which is exactly what a CFMOTO 800MT-X rider saw before pressing
        // Skip on all fifteen steps (field log 7c7e9e44, 2026-08-28).
        if (calibrationCapturing) return true
        // An uncalibrated handlebar assumes a rocker, which is right for most dashboards and
        // wrong forever for the ones that never send one. The silence probe settles it without
        // asking the rider (see [scheduleVolumeSilenceProbe]).
        if (HandlebarCalibration.isVolumeRockerSilent(context)) return false
        if (!HandlebarCalibration.isCalibrated(context)) return true
        return HandlebarCalibration.pressFor(context, HandlebarGesture.VOLUME_UP) != null ||
            HandlebarCalibration.pressFor(context, HandlebarGesture.VOLUME_DOWN) != null ||
            HandlebarCalibration.pressFor(context, HandlebarGesture.VOLUME_UP_DOUBLE) != null ||
            HandlebarCalibration.pressFor(context, HandlebarGesture.VOLUME_DOWN_DOUBLE) != null
    }

    private fun enableCapture() {
        if (session == null) {
            log("[BTN] Cannot enable capture before the $targetName service is ready")
            return
        }
        // Everything below costs the rider something: the media volume is pinned, audio focus is
        // taken, a silent track plays. All of it is paid for by handlebar presses that arrive over
        // Bluetooth - so with no adapter, the adapter off, or no permission to use it, there is
        // nothing to gain and the rider simply loses their own volume buttons. Checked here for
        // the same reason TBoxWifiDirectConnector checks NEARBY_WIFI_DEVICES before calling the
        // framework: the failure is knowable in advance, and waiting for it to prove itself costs
        // the rider the whole session.
        //
        // Not a check on whether the motorcycle is connected. A dash that is off or out of range
        // will turn up during the ride, and refusing to listen for it would be the worse mistake.
        //
        // HID mode is exempt: canReceiveHandlebarKeys() checks BLUETOOTH_CONNECT, which this app
        // needs to ask the Bluetooth stack "is anything connected" for the AVRCP path - but a HID
        // remote's key events arrive as ordinary input through HandlebarHidCaptureService's
        // Accessibility Service, which needs no Bluetooth permission of its own to see them.
        // Field report 2026-08-13: capture stayed permanently skipped on a phone that had never
        // granted BLUETOOTH_CONNECT, even though the HID remote's presses were already reaching
        // the Accessibility Service and being dropped downstream for exactly this reason.
        val hidMode = HandlebarControlStore.inputMode(context) == HandlebarInputMode.HID
        if (!hidMode && !BluetoothStatus.canReceiveHandlebarKeys(context)) {
            // The package name is not decoration. MOTO-HUB is two apps sharing one diagnostics
            // log, and this line used to say "this app" in a file where two apps are talking:
            // rider 315e0af3 sent seven reports carrying it, every one of them from CORE, while
            // the companion app's own bridge printed "capture enabled" a few lines away. Naming
            // the package is what turns the sentence into a diagnosis.
            log(
                "[BTN] capture skipped: no usable Bluetooth for ${context.packageName} " +
                    "(adapter off, or BLUETOOTH_CONNECT never granted to it), so no handlebar " +
                    "press can arrive - leaving the media volume and audio focus alone"
            )
            awaitBluetooth()
            return
        }
        cancelBluetoothWait()
        captureActive = true
        focusLossVolumeGuard = false
        // Pinning the media volume is how a volume-key press becomes readable as a gesture -
        // the app holds the level and treats any drift as the rider pressing up or down. On a
        // dashboard that never sends those presses to the phone (a CFDL16 keeps its rocker's
        // short press for its own volume display, road test 2026-07-29) the pin buys nothing
        // and costs the rider control of their own volume, so it is only taken when the rider
        // has a volume-key press that actually arrives.
        usesVolumeGestures = volumeGesturesInUse()
        if (usesVolumeGestures) {
            if (previousVolume < 0) {
                previousVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            }
            pinVolume()
            scheduleVolumeSilenceProbe()
        } else {
            log("[BTN] volume keys are not delivered by this dashboard; leaving the media volume alone")
        }
        // installRemoteVolume() matters even more in HID mode than in AVRCP: field trace
        // 2026-08-13 showed KEYCODE_VOLUME_UP/DOWN from a HID remote never reaching
        // HandlebarHidCaptureService at all - MediaSessionService's dispatchVolumeKeyEvent
        // claimed them first and routed them to Android Auto's OWN app (gearhead), which was
        // holding the relevant session. A VolumeProvider is what lets THIS app win that routing
        // instead (see onAdjustVolume below); without one, whichever app the system picks gets
        // the press and this bridge never sees a KeyEvent to filter in the first place. See
        // onHidVolumeKey for how HID mode's callback differs from AVRCP's (onPhoneVolumeKey).
        installRemoteVolume()
        val granted = requestMediaFocus()
        startSilentTrack()
        session?.isActive = true
        publishMetadata()
        postMediaNotification()
        startKeepAlive()
        // Polling continues either way: with gestures off it only feeds the diagnostic trace,
        // which is what proves whether the dashboard moves this phone's volume at all.
        startVolumePolling()
        log("[BTN] capture enabled; audio focus=${if (granted) "granted" else "denied"}")
    }

    /**
     * Requests a transient, ducking focus rather than exclusive [AudioManager.AUDIOFOCUS_GAIN].
     * This bridge only needs enough focus to keep the AVRCP session addressable by the
     * motorcycle's Bluetooth stack (see [startSilentTrack]) - it does not play real audio - so
     * there is no reason to hold exclusive focus indefinitely. Exclusive GAIN combined with the
     * abandon/reacquire cycle previously in [reassertCaptureAfterTransportReady] could leave
     * some OEM Bluetooth stacks (observed on Samsung) with the audio route stuck after a
     * transport-recovery reassert; TRANSIENT_MAY_DUCK plus a focus-preserving reassert avoids
     * dropping and re-taking focus altogether. The request rides [navAttributes], accepts a
     * delayed grant, and never pauses when ducked — losing it entirely is handled by
     * [onAudioFocusChange], which schedules a reclaim instead of silently giving the buttons up.
     */
    private fun requestMediaFocus(): Boolean {
        // The request object is reused, never abandoned and rebuilt. Abandoning hands the
        // rider's music app a GAIN, and the request that follows hands it a duck - and on
        // stacks that implement ducking as an absolute-volume write, that round trip lands in
        // STREAM_MUSIC, the one stream the pin watches, where it is indistinguishable from a
        // rocker press. The keep-alive ran the pair every twelve idle seconds, so the music
        // breathed and phantom scrolls arrived on a timer for the whole ride. Re-requesting a
        // focus already held changes nothing in the focus stack, so the periodic re-request
        // stays exactly as often as it was: it is the abandon that did the damage.
        val request = focusRequest ?: AudioFocusRequest.Builder(
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        )
            .setAudioAttributes(navAttributes)
            .setOnAudioFocusChangeListener(::onAudioFocusChange)
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(false)
            .build()
            .also { focusRequest = it }
        val granted = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) {
            val regained = !focusHeld
            focusHeld = true
            // A grant that arrives synchronously fires no AUDIOFOCUS_GAIN callback, so on that
            // path nothing else would ever lower the guard the preceding loss put up, and the
            // rocker stays dead for the rest of the session. Only on a genuine transition back
            // to us: re-pinning while something else is still ducking would fight the duck.
            if (regained) releaseVolumeGuard("focus granted")
        }
        return granted
    }

    /**
     * Lets volume moves count as gestures again, re-pinning the reference level first.
     *
     * The pin is what every delta is measured against, and whatever ducked the stream moved it
     * away from that. Clearing the guard without re-pinning hands [consumeVolumeChange] the
     * ducked level as one fresh, large delta - the phantom press the guard exists to prevent,
     * fired at the exact moment it is lowered.
     */
    private fun releaseVolumeGuard(reason: String) {
        duckVolumeGuardUntil = 0L
        if (!focusLossVolumeGuard) return
        if (usesVolumeGestures && pinnedVolume >= 0) pinVolume()
        focusLossVolumeGuard = false
        log("[BTN] volume gestures re-enabled ($reason)")
    }

    // ── keeping ownership of the motorcycle's buttons ────────────────────────────────────────────
    //
    // The dash routes AVRCP keys to whichever player looks most alive. Any app that takes audio
    // focus or posts a fresher MediaSession silently steals the handlebar: without an active
    // defense the buttons die at the first Spotify play / nav prompt / notification sound and
    // never come back until the session restarts. Ported from open-cfmoto (field-proven there).

    /** Last time a bike media key was handled — skip focus re-requests while the rider is tapping:
     *  re-requesting focus mid-tap makes the BT stack re-deliver the same press. */
    @Volatile private var lastKeyAt = 0L
    private var keepAliveTicks = 0
    private var reclaimPending = false
    private var lastReclaimAt = 0L
    private val reclaimRunnable = Runnable {
        reclaimPending = false
        if (captureActive) reclaimCapture("focus-loss")
    }
    private val keepAliveRunnable = object : Runnable {
        override fun run() {
            if (!captureActive || session == null) return
            keepAliveTicks++
            refreshPlayingAppearance(reason = "keep-alive")
            val idle = SystemClock.elapsedRealtime() - lastKeyAt > KEY_IDLE_BEFORE_FOCUS_MILLIS
            // Unchanged in cadence: this is the net that catches a focus lost without a
            // callback. What changed is that [requestMediaFocus] no longer abandons the
            // request first, so the pass costs the rider's music nothing.
            if (idle && keepAliveTicks % 3 == 0) requestMediaFocus()
            handler.postDelayed(this, KEEP_ALIVE_MILLIS)
        }
    }

    private fun startKeepAlive() {
        handler.removeCallbacks(keepAliveRunnable)
        if (captureActive) handler.postDelayed(keepAliveRunnable, KEEP_ALIVE_MILLIS)
    }

    private fun stopKeepAlive() {
        handler.removeCallbacks(keepAliveRunnable)
    }

    private fun onAudioFocusChange(change: Int) {
        val name = when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> "GAIN"
            AudioManager.AUDIOFOCUS_LOSS -> "LOSS"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "LOSS_TRANSIENT"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "LOSS_TRANSIENT_CAN_DUCK"
            else -> "focus=$change"
        }
        log("[BTN] audio focus -> $name")
        if (!captureActive) return
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                focusHeld = true
                releaseVolumeGuard("focus regained")
                startSilentTrack()
                refreshPlayingAppearance(reason = "focus-gain")
            }
            // Another app is playing over us — expected with MAY_DUCK. Keep the session hot but
            // do not fight for focus: stealing it back exclusively would pause the rider's music.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // A duck keeps our focus, so the reclaim has nothing to do - but the duck itself
                // still moves the media stream, and on a Bluetooth absolute-volume route that
                // write is indistinguishable from a rocker press by the time it reaches
                // [consumeVolumeChange]. Suppress gestures for as long as a duck plausibly runs.
                duckVolumeGuardUntil = SystemClock.elapsedRealtime() + DUCK_VOLUME_GUARD_MILLIS
                refreshPlayingAppearance(reason = "ducked")
            }
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Whoever took our focus (typically the assistant) is about to duck the media
                // stream; nothing the volume does between here and the reclaim's re-pin is a
                // rider gesture.
                focusHeld = false
                focusLossVolumeGuard = true
                scheduleReclaim(name)
            }
        }
    }

    private fun scheduleReclaim(reason: String) {
        if (!captureActive) return
        val now = SystemClock.elapsedRealtime()
        if (reclaimPending) return
        reclaimPending = true
        val delay = if (now - lastReclaimAt < RECLAIM_MIN_GAP_MILLIS) {
            RECLAIM_MIN_GAP_MILLIS
        } else {
            RECLAIM_DELAY_MILLIS
        }
        log("[BTN] media focus lost ($reason); reclaiming the handlebar in ${delay}ms")
        handler.postDelayed(reclaimRunnable, delay)
    }

    private fun cancelReclaim() {
        reclaimPending = false
        handler.removeCallbacks(reclaimRunnable)
    }

    /** Pull AVRCP ownership back with duckable nav focus + a session flip — never pauses music.
     *  Keep-alive is paused for the flip (see [reassertCaptureAfterTransportReady]). */
    private fun reclaimCapture(reason: String) {
        if (!captureActive) return
        lastReclaimAt = SystemClock.elapsedRealtime()
        log("[BTN] reclaiming the handlebar ($reason)")
        runCatching {
            stopKeepAlive()
            requestMediaFocus()
            startSilentTrack()
            session?.isActive = false
            handler.postDelayed({
                if (!captureActive) return@postDelayed
                runCatching {
                    refreshPlayingAppearance(reason = "reclaim")
                    // A full AUDIOFOCUS_LOSS (not just a duck) means something else - Android
                    // Auto's own audio/call activity, observed field-side 2026-08-13 - may have
                    // become the system's volume-key routing target while it held focus.
                    // refreshPlayingAppearance only re-asserts the MediaSession's playing state;
                    // it does not re-attach the VolumeProvider, so without this call hardware
                    // volume presses kept moving the phone's REAL media volume for the next
                    // 15+ seconds after "handlebar reclaimed" already logged - the session was
                    // reclaimed, but volume-key ownership specifically was not.
                    installRemoteVolume()
                    if (usesVolumeGestures) pinVolume()
                    // The pin above just reasserted the reference level (under its own ignore
                    // window), so volume moves are readable as gestures again - including any
                    // duck window that was still running when the loss arrived.
                    focusLossVolumeGuard = false
                    duckVolumeGuardUntil = 0L
                    startKeepAlive()
                    log("[BTN] handlebar reclaimed")
                }.onFailure { log("[BTN] reclaim failed: ${it.message}") }
            }, REASSERT_GAP_MILLIS)
        }.onFailure { log("[BTN] reclaim failed: ${it.message}") }
    }

    /**
     * Keep the session ACTIVE — and its MediaStyle notification up — so we stay the button target.
     *
     * The keep-alive calls this every [KEEP_ALIVE_MILLIS], so it must stay silent on the AVRCP
     * wire: only a deliberate re-announcement re-publishes the track appearance, because every
     * publish puts the dash's "now playing" card back on the rider's screen (see
     * [publishMetadata]). `isActive` and the notification are local — the dash does not react
     * to them — so those are safe to re-assert on every tick.
     */
    private fun refreshPlayingAppearance(reason: String) {
        runCatching {
            // The frequent reasons stay silent on the AVRCP wire: the keep-alive fires every
            // four seconds, and a duck fires at every notification sound or navigation prompt.
            // Only the rare, genuine transitions - focus regained, session reclaimed - are worth
            // making the dash re-read the track, at the price of its "now playing" card.
            val announce = reason != "keep-alive" && reason != "ducked"
            publishMetadata(force = announce)
            session?.isActive = true
            postMediaNotification()
            if (reason != "keep-alive") log("[BTN] playing appearance refreshed ($reason)")
        }.onFailure { log("[BTN] refreshPlayingAppearance failed: ${it.message}") }
    }

    private fun disableCapture() {
        if (!captureActive && focusRequest == null) return
        captureActive = false
        focusLossVolumeGuard = false
        duckVolumeGuardUntil = 0L
        focusHeld = false
        // The next capture has to announce itself to the dash from scratch.
        appearancePublished = false
        cancelSelectStuckWatchdog()
        selectDownAt = 0L
        selectPressSpent = false
        repeatLatched.clear()
        trackDownAt.clear()
        cancelPendingTaps()
        cancelVolumeSilenceProbe()
        cancelBluetoothWait()
        stopKeepAlive()
        cancelReclaim()
        cancelMediaNotification()
        stopSilentTrack()
        handler.removeCallbacks(volumePoll)
        if (volumeProvider != null) {
            try { session?.setPlaybackToLocal(audioAttributes) } catch (_: Throwable) {}
            volumeProvider = null
        }
        try { focusRequest?.let(audioManager::abandonAudioFocusRequest) } catch (_: Throwable) {}
        focusRequest = null
        if (previousVolume >= 0) {
            try { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, previousVolume, 0) } catch (_: Throwable) {}
        }
        previousVolume = -1
        pinnedVolume = -1
        session?.isActive = false
        log("[BTN] capture disabled; normal media controls restored")
    }

    /** Pins to the rider's own listening volume (captured in [enableCapture]) rather than a
     *  fixed midpoint, so capture doesn't silently jump the phone to half volume. Falls back
     *  to the midpoint only when no prior volume is known. */
    private fun pinVolume() {
        val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val preferred = previousVolume.takeIf { it > 0 } ?: (maximum / 2)
        pinnedVolume = preferred.coerceIn(1, (maximum - 1).coerceAtLeast(1))
        ignoreVolumeChanges = true
        try { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, pinnedVolume, 0) } catch (_: Throwable) {}
        handler.postDelayed({ ignoreVolumeChanges = false }, 150)
        volumeProvider?.currentVolume = pinnedVolume
    }

    /**
     * Routes the PHONE's own volume keys away from the pinned stream while capture holds it.
     *
     * With a local-playback session the phone's hardware volume keys write straight into
     * STREAM_MUSIC — the exact stream the pin watches — so every press was read back as a
     * handlebar gesture (field report 2026-07-31: the rider's volume keys drove the Ride
     * Dashboard, showing up as whatever press the calibration had bound to the volume
     * gestures). A remote-volume session makes the system hand those key presses to this
     * [VolumeProvider] instead, where they do what volume keys must do: move the rider's real
     * listening volume, with the pin following so gesture detection never sees them.
     *
     * The motorcycle is unaffected: dashes change the stream through the Bluetooth stack
     * (AVRCP absolute-volume lands in AudioService directly, never in a session's
     * VolumeProvider), so their presses still drift the pinned stream and stay readable as
     * gestures by [consumeVolumeChange]. The one stack-dependent assumption is that this
     * session stays the system's volume-key target — the same always-playing appearance the
     * keep-alive already defends for AVRCP routing.
     */
    private fun installRemoteVolume() {
        val max = runCatching { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
            .getOrDefault(15).coerceAtLeast(1)
        val current = runCatching { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) }
            .getOrDefault(max / 2).coerceIn(0, max)
        val provider = object : VolumeProvider(VOLUME_CONTROL_ABSOLUTE, max, current) {
            override fun onAdjustVolume(direction: Int) {
                if (direction == 0) return
                handler.post {
                    // AVRCP: this is how the PHONE's own hardware volume keys are told apart
                    // from the dash's absolute-volume writes (see the class doc above). HID:
                    // there is no separate "dash" signal to protect - a HID remote's volume
                    // button IS one of these discrete key events, indistinguishable from the
                    // phone's own buttons, so THIS is the handlebar press (see onHidVolumeKey).
                    if (HandlebarControlStore.inputMode(context) == HandlebarInputMode.HID) {
                        onHidVolumeKey(direction)
                    } else {
                        onPhoneVolumeKey(direction)
                    }
                }
            }

            override fun onSetVolumeTo(volume: Int) {
                handler.post { onPhoneVolumeSet(volume) }
            }
        }
        try {
            session?.setPlaybackToRemote(provider)
            volumeProvider = provider
            log("[BTN] phone volume keys rerouted: they move the listening volume, never the handlebar")
        } catch (failure: Throwable) {
            log("[BTN] remote volume install failed (${failure.message}); phone volume keys may still register as presses")
        }
    }

    /**
     * One HID remote volume press, delivered via [installRemoteVolume]'s VolumeProvider instead
     * of [HandlebarHidCaptureService]'s Accessibility Service - on at least one real device
     * (Pixel 8, field trace 2026-08-13) MediaSessionService's dispatchVolumeKeyEvent claimed the
     * raw KeyEvent for Android Auto's own app before the Accessibility Service ever saw it, so
     * this is the path that actually fires for volume in practice. A genuine discrete key press
     * with a direction, not an absolute value to infer a gesture from - reuses the same
     * tap/double-tap detection [onKeyDown] gives an AVRCP dash's literal key press.
     */
    private fun onHidVolumeKey(direction: Int) {
        if (!captureActive) return
        val single = if (direction > 0) HandlebarGesture.VOLUME_UP else HandlebarGesture.VOLUME_DOWN
        val double = if (direction > 0) HandlebarGesture.VOLUME_UP_DOUBLE else HandlebarGesture.VOLUME_DOWN_DOUBLE
        log("[BTN] HID volume ${if (direction > 0) "up" else "down"} (via VolumeProvider) -> $targetName")
        detectDoubleTap(single, double, forceDouble = false)
    }

    /** One phone volume-key press: step the real listening volume, same stride the OS would use. */
    private fun onPhoneVolumeKey(direction: Int) {
        if (!captureActive) return
        val max = runCatching { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
            .getOrDefault(15).coerceAtLeast(1)
        // One hardware-key press moves about a fifteenth of the range whatever the scale
        // (1 step of 15, ten of 160 on a OnePlus CPH2653) — same rule as interpretVolumeDelta.
        val step = maxOf(max / 15, 1)
        val base = if (captureActive && pinnedVolume >= 0) {
            pinnedVolume
        } else {
            runCatching { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(max / 2)
        }
        val target = (base + direction * step).coerceIn(0, max)
        setListeningVolume(target)
        log("[BTN] phone volume key ${if (direction > 0) "up" else "down"} -> listening volume $target/$max (not a handlebar press)")
    }

    /** The phone's volume dialog slider aimed at this session: a real listening-volume change. */
    private fun onPhoneVolumeSet(volume: Int) {
        if (!captureActive) return
        setListeningVolume(volume)
        log("[BTN] phone volume slider -> listening volume $volume (not a handlebar press)")
    }

    /**
     * Set volume from external UI (e.g. Controls slider) without counting as a handlebar
     * press. While capture is active this must also move [pinnedVolume] to the same value:
     * [consumeVolumeChange] treats any gap between the live stream volume and [pinnedVolume]
     * as a handlebar gesture, so leaving the pin behind would make it look like the rider
     * just pressed volume up/down and immediately snap the slider's new value back to the
     * stale pin. The value is also kept off the literal 0/max endpoints while capturing, same
     * as [pinVolume], so up/down drift stays detectable in both directions.
     */
    fun setListeningVolume(level: Int) {
        try {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val requested = level.coerceIn(0, max)
            val v = if (captureActive) {
                requested.coerceIn(1, (max - 1).coerceAtLeast(1))
            } else {
                requested
            }
            previousVolume = v
            if (captureActive) pinnedVolume = v
            ignoreVolumeChanges = true
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
            } finally {
                handler.postDelayed({ ignoreVolumeChanges = false }, 150)
            }
            volumeProvider?.currentVolume = v
        } catch (e: Exception) {
            log("[BTN] setListeningVolume failed: $e")
        }
    }

    /** Current music stream volume and max (for the Controls slider). */
    fun volumeLevels(): Pair<Int, Int> {
        val max = try { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } catch (_: Exception) { 15 }
        val now = try { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } catch (_: Exception) { max / 2 }
        return now.coerceIn(0, max) to max.coerceAtLeast(1)
    }

    private fun registerVolumeObserver() {
        if (volumeObserver != null) return
        volumeObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                consumeVolumeChange()
            }
        }.also { observer ->
            context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        }
    }

    private fun startVolumePolling() {
        handler.removeCallbacks(volumePoll)
        handler.post(volumePoll)
    }

    /** Some Android builds do not notify Settings.System for Bluetooth absolute-volume changes. */
    private fun consumeVolumeChange() {
        if (!captureActive) return
        val observed = runCatching {
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        }.getOrNull() ?: return
        // Diagnostic first, before every guard below can swallow it: the open question on a
        // CFDL16 is whether a short rocker press moves the phone's volume AT ALL (in which
        // case it is recoverable) or stays inside the dashboard (in which case nothing can
        // reach us). Only a trace that survives the guards can tell the two apart.
        val ducking = SystemClock.elapsedRealtime() < duckVolumeGuardUntil
        if (observed != lastObservedVolume) {
            log(
                "[BTN] media volume observed $lastObservedVolume -> $observed " +
                    "(pinned=$pinnedVolume, ignoring=$ignoreVolumeChanges, " +
                    "focusLossGuard=$focusLossVolumeGuard, duckGuard=$ducking, " +
                    "gestures=$usesVolumeGestures)"
            )
            lastObservedVolume = observed
        }
        if (!usesVolumeGestures || pinnedVolume < 0) return
        if (ignoreVolumeChanges) return
        // Focus just went to another player (assistant, nav prompt): every volume move until
        // the reclaim re-pins is the system ducking, not a rocker press. Do not snap the
        // volume back either — fighting the duck would make the assistant blast over itself.
        if (focusLossVolumeGuard) return
        // Same, for a duck that took no focus at all — the case that produced a scroll every
        // time the phone made a sound. Do not re-pin here either, for the same reason.
        if (ducking) return
        val current = observed
        if (current == pinnedVolume) return
        val delta = current - pinnedVolume
        // Re-pin under the ignore guard, like pinVolume()/setListeningVolume(): with Bluetooth
        // absolute volume the write round-trips through the peer, and reading that in-flight
        // echo back as a fresh delta would fabricate a phantom press.
        ignoreVolumeChanges = true
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, pinnedVolume, 0)
        } catch (_: Throwable) {
        } finally {
            handler.postDelayed({ ignoreVolumeChanges = false }, REPIN_IGNORE_MILLIS)
        }
        val single = if (delta > 0) HandlebarGesture.VOLUME_UP else HandlebarGesture.VOLUME_DOWN
        log("[BTN] volume ${if (delta > 0) "UP" else "DOWN"}; pinned=$pinnedVolume, delta=$delta")
        // Reached only past every guard above (ignore window, focus-loss duck, unchanged level),
        // so this is a real press off the handlebar - the one thing that proves a rocker exists.
        noteVolumePressObserved()
        val streamMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        when (val read = interpretVolumeDelta(delta, HandlebarControlStore.action(context, single), streamMax)) {
            null -> Unit
            is VolumeDeltaRead.ScrollClicks -> repeat(read.count) { dispatch(read.gesture) }
            is VolumeDeltaRead.Tap -> detectDoubleTap(read.single, read.double, read.forceDouble)
        }
    }

    private fun unregisterVolumeObserver() {
        volumeObserver?.let { observer ->
            runCatching { context.contentResolver.unregisterContentObserver(observer) }
        }
        volumeObserver = null
    }

    private class TapState(var pending: Runnable? = null, var lastAt: Long = 0L)
    private val taps = HashMap<HandlebarGesture, TapState>()

    /**
     * Single vs double on one channel. In EAGER mode ([shouldDispatchSingleEagerly]) the single
     * fires immediately and `pending` is only a "fired recently" marker — a second press inside
     * the window fires the double on top, unless the single drives a repeatable action, where a
     * fast second click is the rider using the control normally ([resolveTapDispatch]). In
     * deferred mode the single waits out the window, which is what tells the two apart at the
     * cost of latency on every press.
     */
    private fun detectDoubleTap(
        single: HandlebarGesture,
        double: HandlebarGesture,
        forceDouble: Boolean
    ) {
        val state = taps.getOrPut(single) { TapState() }
        val now = SystemClock.uptimeMillis()
        val decision = resolveTapDispatch(
            forceDouble = forceDouble,
            eagerSingle = shouldDispatchSingleEagerly(double),
            hasPending = state.pending != null,
            gapMillis = now - state.lastAt,
            echoRefractoryMillis = ECHO_REFRACTORY_MILLIS,
            repeatableSingle = isRepeatableAction(HandlebarControlStore.action(context, single))
        )
        state.lastAt = now
        when (decision) {
            TapDispatch.SUPPRESS_ECHO -> Unit
            TapDispatch.DOUBLE -> {
                state.pending?.let(handler::removeCallbacks)
                state.pending = null
                dispatch(double)
            }
            TapDispatch.SINGLE_NOW -> {
                dispatch(single)
                val marker = Runnable { state.pending = null }
                state.pending = marker
                handler.postDelayed(marker, HandlebarTimingPrefs.doubleTapMillis(context))
            }
            TapDispatch.SINGLE_DEFERRED -> {
                val pending = Runnable {
                    state.pending = null
                    dispatch(single)
                }
                state.pending = pending
                handler.postDelayed(pending, HandlebarTimingPrefs.doubleTapMillis(context))
            }
        }
    }

    /**
     * Eager when the rider asked for snappy singles (default), or when the double gesture maps
     * to nothing — then there is nothing to disambiguate and waiting is pure lag.
     *
     * NEVER eager while the calibration wizard is listening: eager mode publishes the single
     * before the double on a double press, and the wizard records the first gesture it sees —
     * at the "double press" step that would record the single AND release it from the press
     * taught moments earlier (one command, one press). Deferred dispatch publishes exactly the
     * disambiguated gesture; latency does not matter while teaching.
     */
    private fun shouldDispatchSingleEagerly(double: HandlebarGesture): Boolean {
        if (HandlebarGestureFeed.isCaptureOnly()) return false
        return HandlebarTimingPrefs.eagerSingles(context) ||
            HandlebarControlStore.action(context, double) == HandlebarAction.NONE
    }

    private fun cancelPendingTaps() {
        taps.values.forEach { state -> state.pending?.let(handler::removeCallbacks) }
        taps.clear()
    }

    private fun dispatch(gesture: HandlebarGesture) {
        // Before the capture gate: a handlebar press that arrives while capture is off is still a
        // press the rider made, and not showing it is how "is this thing even listening" becomes
        // an evening of guessing.
        HandlebarPressHud.pressed(context, gesture.label)
        if (!captureActive) return
        // Published before anything consumes it, so the mapping screen shows what the
        // handlebar sent even when the gesture is unmapped or swallowed by the dashboard.
        HandlebarGestureFeed.publish(gesture)
        if (HandlebarGestureFeed.isCaptureOnly()) {
            log("[BTN] ${gesture.label} observed for calibration; not acted on")
            return
        }
        val handledByTarget = runCatching { gestureHandler?.invoke(gesture) == true }
            .onFailure { log("[BTN] $targetName gesture handler failed: ${it.message}") }
            .getOrDefault(false)
        if (handledByTarget) {
            log("[BTN] ${gesture.label} -> $targetName")
            HandlebarPressHud.performed(context, gesture.label, targetName)
            return
        }
        val action = HandlebarControlStore.action(context, gesture)
        log("[BTN] ${gesture.label} -> ${action.label}")
        // On the picture as well as in the log: mid-ride nobody reads a log, and "did that do
        // anything" is the question this whole screen keeps failing to answer.
        HandlebarPressHud.performed(context, gesture.label, action.label)
        HandlebarActionRunner.run(context, action, log)
    }

    /**
     * Feeds one raw key event captured system-wide by [HandlebarHidCaptureService] (HID mode
     * only). Two shapes of HID remote are supported:
     *
     * - Volume/select/track keycodes ([isVolumeKey]/[isSelectKey]/[isTrackKey]) — the common
     *   case for a remote whose five buttons mirror AVRCP's (up/down/play/back/forward), just
     *   delivered as ordinary HID keys instead of over a MediaSession. These route through the
     *   SAME [onKeyDown]/[onKeyUp] tap/hold/double-tap machinery AVRCP uses, so
     *   [HandlebarControlStore]'s calibration and the mapping screen apply identically regardless
     *   of which transport the press arrived over — a HID remote's "up" button IS
     *   [HandlebarGesture.VOLUME_UP], the same gesture id an AVRCP dash's volume rocker produces.
     * - D-pad arrow keycodes — for remotes wired as a literal 4-way pad instead. Fixed mapping,
     *   not run through the calibration wizard: there is no "up press" left to teach when the
     *   remote already reports which direction was pressed.
     *
     * Anything else is logged and left unconsumed, so a rider hitting an unrecognized button can
     * find the keycode in the diagnostic log and report it instead of the button silently doing
     * nothing.
     */
    fun onHidKeyEvent(keyCode: Int, action: Int, repeatCount: Int): Boolean {
        if (!captureActive) return false
        dpadKeyTarget(keyCode)?.let { target ->
            if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) {
                log("[BTN] HID D-pad ${KeyEvent.keyCodeToString(keyCode)} -> $targetName")
                HandlebarActionRunner.sendKey(target, log)
            }
            return true
        }
        if (!isVolumeKey(keyCode) && !isSelectKey(keyCode) && !isTrackKey(keyCode)) {
            log("[BTN] HID key ${KeyEvent.keyCodeToString(keyCode)} ($keyCode) not recognized; ignored")
            return false
        }
        when (action) {
            KeyEvent.ACTION_DOWN -> onKeyDown(keyCode, repeatCount)
            KeyEvent.ACTION_UP -> onKeyUp(keyCode)
        }
        return true
    }

    private fun dpadKeyTarget(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> AndroidAutoInputCodes.KEY_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> AndroidAutoInputCodes.KEY_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> AndroidAutoInputCodes.KEY_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> AndroidAutoInputCodes.KEY_RIGHT
        KeyEvent.KEYCODE_DPAD_CENTER -> AndroidAutoInputCodes.KEY_ENTER
        else -> null
    }

    fun injectSimulatorGesture(gesture: HandlebarGesture) {
        handler.post {
            log("[BTN] simulator injected ${gesture.label}")
            dispatch(gesture)
        }
    }

    private val callback = object : MediaSession.Callback() {
        override fun onMediaButtonEvent(intent: Intent): Boolean {
            @Suppress("DEPRECATION")
            val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
            // Raw trace before every guard below: a keycode this bridge does not handle, or a
            // press landing while capture is off, was otherwise dropped without a line in the
            // shared log — indistinguishable from a dash that sends nothing at all (Zontes
            // 703RR report 2026-08-04: teach screen never lit up, no way to tell which).
            if (event == null) {
                log("[BTN] raw media button intent without a KeyEvent")
                return false
            }
            val actionName = when (event.action) {
                KeyEvent.ACTION_DOWN -> "down"
                KeyEvent.ACTION_UP -> "up"
                else -> "action=${event.action}"
            }
            log(
                "[BTN] raw ${KeyEvent.keyCodeToString(event.keyCode)} $actionName " +
                    "repeat=${event.repeatCount}" +
                    if (captureActive) "" else " (capture inactive; ignored)"
            )
            if (!captureActive) return false
            if (isSelfInjected(event.keyCode)) {
                log("[BTN] ignoring ${KeyEvent.keyCodeToString(event.keyCode)} - this app dispatched it")
                return true
            }
            val handled = isSelectKey(event.keyCode) || event.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT ||
                event.keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS ||
                event.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ||
                event.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND
            if (!handled) return false
            when (event.action) {
                KeyEvent.ACTION_DOWN -> onKeyDown(event.keyCode, event.repeatCount)
                KeyEvent.ACTION_UP -> onKeyUp(event.keyCode)
            }
            return handled
        }

        override fun onPlay() {
            if (captureActive && selectDownAt == 0L) dispatchSelectTap()
        }

        override fun onPause() {
            if (captureActive && selectDownAt == 0L) dispatchSelectTap()
        }
    }

    private var selectDownAt = 0L
    private var lastSelectDispatchAt = 0L
    /**
     * A select press whose command has already been dispatched, so its eventual release is
     * spent: on its key-down for a dashboard that reports no releases, by a key-repeat that
     * latched a hold, or by [selectStuckWatchdog].
     */
    private var selectPressSpent = false

    /**
     * Resolves a select press whose release never arrived.
     *
     * Some dashboards send one event per press and no release at all; others report releases
     * until they reboot mid-ride and then stop. Either way the press instant stayed recorded
     * for good, and since a press is only started when none is outstanding, every later press
     * was discarded — as were the semantic play/pause callbacks, which stand down while a raw
     * key press is in flight. The symptom is exactly what riders described: OK works once,
     * then nothing until the session is restarted.
     *
     * [SELECT_STUCK_TIMEOUT_MILLIS] is far longer than any hold a rider can configure, so a
     * genuine long press has always resolved through its own release long before this runs.
     */
    private val selectStuckWatchdog = Runnable { resolveOutstandingSelectPress("no release arrived") }

    private fun armSelectStuckWatchdog() {
        handler.removeCallbacks(selectStuckWatchdog)
        handler.postDelayed(selectStuckWatchdog, SELECT_STUCK_TIMEOUT_MILLIS)
    }

    private fun cancelSelectStuckWatchdog() {
        handler.removeCallbacks(selectStuckWatchdog)
    }

    /**
     * Ends an outstanding select press without its release, dispatching what that press had
     * earned, and marks it spent so a release arriving late runs nothing on top of it.
     */
    private fun resolveOutstandingSelectPress(reason: String) {
        val startedAt = selectDownAt
        if (startedAt == 0L) return
        cancelSelectStuckWatchdog()
        val heldMillis = SystemClock.elapsedRealtime() - startedAt
        selectDownAt = 0L
        if (selectPressSpent) {
            log("[BTN] select press ended after ${heldMillis}ms with no release ($reason)")
            return
        }
        selectPressSpent = true
        val isLong = HandlebarTimingPrefs.holdsEnabled(context) &&
            heldMillis >= HandlebarTimingPrefs.selectHoldMillis(context)
        log(
            "[BTN] select still down after ${heldMillis}ms ($reason); " +
                "resolving it as a ${if (isLong) "hold" else "tap"}"
        )
        if (isLong) {
            taps[HandlebarGesture.ENTER]?.pending?.let(handler::removeCallbacks)
            taps[HandlebarGesture.ENTER]?.pending = null
            dispatch(HandlebarGesture.ENTER_LONG)
        } else {
            dispatchSelectTap()
        }
    }

    /** Press instants of non-select media keys, kept only to time their release in the log. */
    private val trackDownAt = mutableMapOf<Int, Long>()
    /** Keys whose current press already fired a hold via key-repeat — their release is spent. */
    private val repeatLatched = mutableSetOf<Int>()

    private fun isSelectKey(keyCode: Int) = keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
        keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE ||
        keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
        // HID-only aliases: a remote's "call/select" button commonly reports as one of these
        // instead of a MEDIA_PLAY* keycode. Harmless to recognize on the AVRCP path too - no
        // Bluetooth AVRCP peer has ever been observed sending either through onMediaButtonEvent.
        keyCode == KeyEvent.KEYCODE_HEADSETHOOK ||
        keyCode == KeyEvent.KEYCODE_ENTER

    /** HID-only: a literal volume keycode, delivered as a discrete press with a real key-up -
     *  unlike AVRCP's absolute-volume writes, there is nothing to infer here (see
     *  [volumeGesturesInUse]), so this is handled entirely in [onKeyDown]/[onKeyUp]. */
    private fun isVolumeKey(keyCode: Int) =
        keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN

    private fun onKeyDown(keyCode: Int, repeatCount: Int = 0) {
        lastKeyAt = SystemClock.elapsedRealtime()
        if (repeatCount > 0) {
            onKeyRepeat(keyCode)
            return
        }
        log("[BTN] media key ${KeyEvent.keyCodeToString(keyCode)} down ($keyCode)")
        if (isVolumeKey(keyCode)) {
            val single = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                HandlebarGesture.VOLUME_UP
            } else {
                HandlebarGesture.VOLUME_DOWN
            }
            val double = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                HandlebarGesture.VOLUME_UP_DOUBLE
            } else {
                HandlebarGesture.VOLUME_DOWN_DOUBLE
            }
            detectDoubleTap(single, double, forceDouble = false)
            return
        }
        when {
            isSelectKey(keyCode) -> {
                // A second press with no release in between is proof the first one ended: a
                // rider cannot press a button twice without letting go of it.
                if (selectDownAt != 0L) resolveOutstandingSelectPress("a new press arrived")
                selectDownAt = SystemClock.elapsedRealtime()
                selectPressSpent = false
                armSelectStuckWatchdog()
                // A dashboard that has never reported a release gives us one event per press
                // and nothing else - the same rule the track keys below already follow, and
                // for the same reason: dispatch here or lose the press entirely.
                if (!HandlebarControlStore.dashboardReportsHolds(context)) {
                    selectPressSpent = true
                    dispatchSelectTap()
                }
            }
            isTrackKey(keyCode) -> {
                trackDownAt[keyCode] = SystemClock.elapsedRealtime()
                // Dashboards that never report a release give us one event per press and
                // nothing else: dispatching here is the only chance to act on it. Where
                // releases DO arrive, the decision waits for one, exactly as select does -
                // a press cannot be known to be a tap until it ends.
                if (!HandlebarControlStore.dashboardReportsHolds(context)) dispatchTrackTap(keyCode)
            }
        }
    }

    /**
     * A key-repeat is proof the button is still down — a hold source independent of a timed
     * release, which some dashes never send. Fires the long gesture once per press (latched)
     * and marks the eventual release as spent. Skipped when the press was already dispatched
     * as a tap on its key-down (track keys before [HandlebarControlStore.dashboardReportsHolds]
     * is learned): firing a hold on top of that tap would run two actions for one press.
     */
    private fun onKeyRepeat(keyCode: Int) {
        if (!HandlebarTimingPrefs.holdsEnabled(context)) return
        if (keyCode in repeatLatched) return
        val longGesture: HandlebarGesture
        val singleGesture: HandlebarGesture
        val downAt: Long
        when {
            isSelectKey(keyCode) -> {
                longGesture = HandlebarGesture.ENTER_LONG
                singleGesture = HandlebarGesture.ENTER
                downAt = selectDownAt
            }
            isTrackKey(keyCode) -> {
                if (!HandlebarControlStore.dashboardReportsHolds(context)) return // tap already sent on down
                if (isForwardKey(keyCode)) {
                    longGesture = HandlebarGesture.TRACK_FORWARD_LONG
                    singleGesture = HandlebarGesture.TRACK_FORWARD
                } else {
                    longGesture = HandlebarGesture.TRACK_BACK_LONG
                    singleGesture = HandlebarGesture.TRACK_BACK
                }
                downAt = trackDownAt[keyCode] ?: 0L
            }
            else -> return
        }
        if (HandlebarControlStore.action(context, longGesture) == HandlebarAction.NONE) return
        // A repeat proves the button is down, but the HOLD fires at the rider's configured
        // hold time, not at whatever repeat delay this BT stack happens to use (some start
        // repeating at ~300ms — well inside what the rider still means as a tap). Repeats
        // keep arriving, so a later one crosses the threshold and latches then.
        if (downAt == 0L) return // repeat without a recorded press we own
        if (SystemClock.elapsedRealtime() - downAt < HandlebarTimingPrefs.selectHoldMillis(context)) return
        repeatLatched.add(keyCode)
        // The hold below is this press's command; the stuck watchdog must not fire a second
        // one if the release then fails to arrive.
        if (isSelectKey(keyCode)) selectPressSpent = true
        taps[singleGesture]?.pending?.let(handler::removeCallbacks)
        taps[singleGesture]?.pending = null
        log("[BTN] media key ${KeyEvent.keyCodeToString(keyCode)} key-repeat -> hold")
        dispatch(longGesture)
    }

    private fun isTrackKey(keyCode: Int) = keyCode == KeyEvent.KEYCODE_MEDIA_NEXT ||
        keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ||
        keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS ||
        keyCode == KeyEvent.KEYCODE_MEDIA_REWIND

    private fun isForwardKey(keyCode: Int) = keyCode == KeyEvent.KEYCODE_MEDIA_NEXT ||
        keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD

    private fun dispatchTrackTap(keyCode: Int) {
        if (isForwardKey(keyCode)) {
            detectDoubleTap(
                HandlebarGesture.TRACK_FORWARD,
                HandlebarGesture.TRACK_FORWARD_DOUBLE,
                forceDouble = false
            )
        } else {
            detectDoubleTap(
                HandlebarGesture.TRACK_BACK,
                HandlebarGesture.TRACK_BACK_DOUBLE,
                forceDouble = false
            )
        }
    }

    private fun onKeyUp(keyCode: Int) {
        lastKeyAt = SystemClock.elapsedRealtime()
        if (repeatLatched.remove(keyCode)) {
            // This press already fired its hold from key-repeat; its release is spent.
            if (isSelectKey(keyCode)) {
                cancelSelectStuckWatchdog()
                selectDownAt = 0L
                selectPressSpent = false
            } else {
                trackDownAt.remove(keyCode)
            }
            return
        }
        val holdsEnabled = HandlebarTimingPrefs.holdsEnabled(context)
        if (!isSelectKey(keyCode)) {
            if (!isTrackKey(keyCode)) return
            val downAt = trackDownAt.remove(keyCode)
            if (downAt == null) {
                log("[BTN] media key ${KeyEvent.keyCodeToString(keyCode)} released with no recorded press")
                return
            }
            val heldMillis = SystemClock.elapsedRealtime() - downAt
            val holdsKnown = HandlebarControlStore.dashboardReportsHolds(context)
            log("[BTN] media key ${KeyEvent.keyCodeToString(keyCode)} released after ${heldMillis}ms")
            if (!holdsKnown) {
                // First release this dashboard has ever reported: it can time a hold after
                // all. The press just gone was already dispatched on its key-down, so only
                // the NEXT one takes the deferred path - no gesture is lost learning this.
                HandlebarControlStore.setDashboardReportsHolds(context, true)
                log("[BTN] dashboard reports key releases; hold on previous/next is now available")
                return
            }
            if (holdsEnabled && heldMillis >= HandlebarTimingPrefs.selectHoldMillis(context)) {
                dispatch(
                    if (isForwardKey(keyCode)) HandlebarGesture.TRACK_FORWARD_LONG
                    else HandlebarGesture.TRACK_BACK_LONG
                )
            } else {
                dispatchTrackTap(keyCode)
            }
            return
        }
        cancelSelectStuckWatchdog()
        val startedAt = selectDownAt
        selectDownAt = 0L
        val spent = selectPressSpent
        selectPressSpent = false
        if (spent) {
            // Already dispatched — on its key-down, or by the stuck watchdog. A release
            // arriving at all is this dashboard proving it can time one, which is what makes
            // holds available on every press after this one. Nothing else to run here: the
            // press has had its command.
            if (!HandlebarControlStore.dashboardReportsHolds(context)) {
                HandlebarControlStore.setDashboardReportsHolds(context, true)
                log("[BTN] dashboard reports key releases; hold on select is now available")
            }
            return
        }
        if (startedAt == 0L) {
            dispatchSelectTap()
            return
        }
        val heldMillis = SystemClock.elapsedRealtime() - startedAt
        val isLong = holdsEnabled && heldMillis >= HandlebarTimingPrefs.selectHoldMillis(context)
        log("[BTN] select released after ${heldMillis}ms: ${if (isLong) "hold" else "tap"}")
        if (isLong) {
            taps[HandlebarGesture.ENTER]?.pending?.let(handler::removeCallbacks)
            taps[HandlebarGesture.ENTER]?.pending = null
            dispatch(HandlebarGesture.ENTER_LONG)
        } else {
            dispatchSelectTap()
        }
    }

    /**
     * Dispatches a short select tap, de-duplicating against a paired semantic
     * ([MediaSessionCompat.Callback.onPlay]/[onPause]) and raw-key event for the same
     * physical press: some AVRCP peers emit both for one button action, and each path
     * independently believes it is the only handler for that press. [SELECT_DEDUP_MILLIS]
     * is well under the fastest configured double-tap window (200ms), so a genuine second
     * tap from the rider is never swallowed - only a same-press echo arriving within tens
     * of milliseconds is.
     */
    private fun dispatchSelectTap() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSelectDispatchAt < SELECT_DEDUP_MILLIS) return
        lastSelectDispatchAt = now
        selectPressed()
    }

    private fun selectPressed() {
        detectDoubleTap(HandlebarGesture.ENTER, HandlebarGesture.ENTER_DOUBLE, forceDouble = false)
    }

    private fun startSilentTrack() {
        if (silentTrack != null) return
        runCatching {
            val sampleRate = 8_000
            val frames = sampleRate
            val track = AudioTrack.Builder()
                .setAudioAttributes(navAttributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(frames * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(ShortArray(frames), 0, frames)
            track.setLoopPoints(0, frames, -1)
            // Near-silent, not exactly zero: some OEM audio HALs optimize an all-zero track out
            // of the mix entirely, and with it the "genuinely playing" status that wins AVRCP
            // button routing. 0.01 on the nav stream is inaudible and must not duck music.
            track.setVolume(0.01f)
            track.play()
            silentTrack = track
        }.onFailure { log("[BTN] silent AVRCP track failed: ${it.message}") }
    }

    private fun stopSilentTrack() {
        runCatching { silentTrack?.pause() }
        runCatching { silentTrack?.flush() }
        runCatching { silentTrack?.release() }
        silentTrack = null
    }

    /**
     * Publishes this session's track appearance — once.
     *
     * Neither the metadata nor the playback state ever changes: the title is a constant, the
     * artist is derived from [targetName], which is fixed for the life of the bridge, and the
     * state is always PLAYING. But `setMetadata`/`setPlaybackState` are not free even when the
     * content is identical — each call makes the connected AVRCP peer emit a track-changed /
     * play-status-changed notification, and a CFMOTO dash answers every one of those by putting
     * its "now playing" card back on the screen, over whatever the rider was looking at. The
     * keep-alive ticks every four seconds, so the rider got that popup fifteen times a minute
     * for the whole ride (field report 2026-08-18).
     *
     * What actually keeps this session the AVRCP button target is staying ACTIVE and holding
     * audio focus, which [refreshPlayingAppearance] and the keep-alive still do every tick.
     * Re-sending unchanged metadata was never part of that.
     *
     * [force] is for the deliberate re-announcements — after a transport re-assert or a focus
     * reclaim — where making the dash notice a transition IS the point.
     */
    private fun publishMetadata(force: Boolean = false) {
        if (appearancePublished && !force) return
        session?.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "MOTO-HUB controls")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Handlebar controls for $targetName")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, TRACK_DURATION_MS)
                .build()
        )
        session?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(mediaActions())
                .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                .build()
        )
        appearancePublished = true
    }

    private fun postMediaNotification(force: Boolean = false) {
        if (notificationPosted && !force) return
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Handlebar controls", NotificationManager.IMPORTANCE_LOW)
            )
            manager.notify(
                NOTIFICATION_ID,
                Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(motoHubText("MOTO-HUB controls"))
                    .setContentText(motoHubText("Motorcycle buttons control %1\$s", targetName))
                    .setStyle(Notification.MediaStyle().setMediaSession(session?.sessionToken))
                    .setOngoing(true)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .build()
            )
            notificationPosted = true
        }.onFailure { log("[BTN] media notification failed: ${it.message}") }
    }

    private fun cancelMediaNotification() {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) }
        notificationPosted = false
    }

    private fun mediaActions() = PlaybackState.ACTION_PLAY or
        PlaybackState.ACTION_PAUSE or
        PlaybackState.ACTION_PLAY_PAUSE or
        PlaybackState.ACTION_SKIP_TO_NEXT or
        PlaybackState.ACTION_SKIP_TO_PREVIOUS or
        PlaybackState.ACTION_FAST_FORWARD or
        PlaybackState.ACTION_REWIND

    companion object {
        const val TARGET_ANDROID_AUTO = "Android Auto"

        private const val CHANNEL_ID = "motohub_handlebar_controls"
        private const val NOTIFICATION_ID = 4203
        private const val TRACK_DURATION_MS = 3_600_000L
        private const val VOLUME_POLL_INTERVAL_MILLIS = 250L
        private const val REASSERT_SETTLE_MILLIS = 3_000L
        private const val REASSERT_GAP_MILLIS = 500L
        /** Session-refresh cadence; a soft focus re-request every 3rd tick when idle. */
        private const val KEEP_ALIVE_MILLIS = 4_000L
        /** Don't re-request audio focus while the rider is actively pressing buttons. */
        private const val KEY_IDLE_BEFORE_FOCUS_MILLIS = 2_500L
        private const val RECLAIM_DELAY_MILLIS = 500L
        private const val RECLAIM_MIN_GAP_MILLIS = 2_000L
        private const val ECHO_REFRACTORY_MILLIS = 80L
        private const val SELECT_DEDUP_MILLIS = 100L
        /** How long a duck plausibly runs; volume moves inside it are not rider gestures. */
        private const val DUCK_VOLUME_GUARD_MILLIS = 1_500L
        /** Well past the longest configurable hold (800ms), so only a lost release reaches it. */
        private const val SELECT_STUCK_TIMEOUT_MILLIS = 5_000L
        private const val REPIN_IGNORE_MILLIS = 80L

        /**
         * How long a pinned session may go without a single volume press before the rocker is
         * taken to be absent. Long enough that a rider who simply has not touched the volume yet
         * is not misread - the cost of waiting is only that the pin stays a little longer, while
         * the cost of deciding too early is a rocker that stops working.
         */
        private const val VOLUME_SILENCE_PROBE_MILLIS = 90_000L
        private val bridges = ConcurrentHashMap<String, MediaButtonBridge>()

        fun setTargetCaptureActive(targetName: String, enabled: Boolean): Boolean {
            val bridge = bridges[targetName] ?: return false
            bridge.setCaptureActive(enabled)
            return true
        }

        fun injectGesture(targetName: String, gesture: HandlebarGesture): Boolean {
            val bridge = bridges[targetName] ?: return false
            bridge.injectSimulatorGesture(gesture)
            return true
        }

        /** Routes one HID key event to whichever live bridge is capturing (see
         *  [HandlebarHidCaptureService]). Returns true once any bridge claims the keycode, so
         *  the Accessibility Service can consume it and stop it reaching the focused app. */
        fun dispatchHidKeyEvent(keyCode: Int, action: Int, repeatCount: Int): Boolean =
            bridges.values.any { it.onHidKeyEvent(keyCode, action, repeatCount) }

        @Volatile private var selfInjectedKey = 0
        @Volatile private var selfInjectedUntil = 0L

        /**
         * Announces a media key this app is about to dispatch itself.
         *
         * Android delivers a dispatched media key to the most recently active session, and during
         * a handlebar capture that can be the fake one this class publishes for AVRCP - so a rider
         * mapping "play/pause" to a controller button would have it come straight back and be read
         * as a handlebar press. Announced here, ignored on arrival, for as long as a round trip
         * plausibly takes.
         */
        fun noteSelfInjectedMediaKey(keyCode: Int) {
            selfInjectedKey = keyCode
            selfInjectedUntil = SystemClock.elapsedRealtime() + SELF_INJECTION_WINDOW_MILLIS
        }

        internal fun isSelfInjected(keyCode: Int): Boolean =
            keyCode == selfInjectedKey && SystemClock.elapsedRealtime() < selfInjectedUntil

        private const val SELF_INJECTION_WINDOW_MILLIS = 400L

        /** Calibration changed — every live bridge re-decides whether to hold the volume pin. */
        fun refreshVolumeGestureUse() {
            bridges.values.forEach { it.refreshVolumeGestureUse() }
        }

        /**
         * The calibration wizard opened or closed. While it is open every live bridge holds the
         * volume pin, so a rocker that only ever arrives as an absolute-volume change is
         * readable by the very screen whose job is to learn it.
         */
        fun setCalibrating(active: Boolean) {
            bridges.values.forEach { it.setCalibrating(active) }
        }

        /**
         * Called when this app has just been granted BLUETOOTH_CONNECT, so a session already
         * running picks the handlebar up without being restarted. See [retryAfterBluetoothGrant].
         */
        fun bluetoothPermissionGranted() {
            bridges.values.forEach { it.retryAfterBluetoothGrant() }
        }

        /**
         * The input protocol changed - here or pushed over the bridge by a companion app - and
         * every live bridge re-applies it instead of running the old one until the next session.
         */
        fun inputModeChanged() {
            bridges.values.forEach { it.inputModeChanged() }
        }

        /** Music volume from the live bridge or plain AudioManager (for the Controls slider). */
        fun volumeLevels(context: Context): Pair<Int, Int> {
            bridges.values.firstOrNull()?.let { return it.volumeLevels() }
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val now = audio.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, max)
            return now to max
        }

        /** Set volume from the Controls slider — routes through the bridge when active to avoid
         *  triggering false handlebar events. */
        fun setVolume(context: Context, level: Int) {
            val bridge = bridges.values.firstOrNull()
            if (bridge != null) {
                bridge.setListeningVolume(level)
                return
            }
            try {
                val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, level.coerceIn(0, max), 0)
            } catch (_: Exception) {}
        }
    }
}

internal enum class TapDispatch { SUPPRESS_ECHO, DOUBLE, SINGLE_NOW, SINGLE_DEFERRED }

/**
 * Pure decision for one press on a tap channel. `hasPending` means either a deferred single
 * waiting to fire or an eager "fired recently" marker — in both cases a second press inside
 * the double-tap window, so it resolves to DOUBLE. The refractory guard runs first: two
 * events within [echoRefractoryMillis] are one physical press echoed by the peer, except when
 * the caller already knows better ([forceDouble], a dash-coalesced volume jump).
 *
 * [repeatableSingle] is the exception that keeps a rotary usable. In eager mode the single has
 * ALREADY fired by the time the second press arrives, so promoting that press to the double
 * runs two different commands for two clicks: scrolling a list at two clicks per second - the
 * ordinary way anyone uses a wheel - dispatched scroll, scroll, then whatever the double is
 * mapped to, which by default is HOME on the up gesture and BACK on the down one. The rider
 * reads that as "the wheel throws me out of the menu". When the single drives a naturally
 * repeatable action ([isRepeatableAction]) a fast second click means "again", so it fires the
 * single again and re-arms the marker.
 *
 * Deferred mode is untouched: nothing has fired yet there, so pairing the two presses into one
 * double is a genuine disambiguation and still runs exactly one command. A rider who wants a
 * double on a rotary-mapped gesture turns eager singles off, which is what that switch is for.
 * [forceDouble] also still wins: a dash-coalesced jump is the dash saying "two presses", which
 * is how BACK and HOME stay reachable from a volume-only handlebar.
 */
internal fun resolveTapDispatch(
    forceDouble: Boolean,
    eagerSingle: Boolean,
    hasPending: Boolean,
    gapMillis: Long,
    echoRefractoryMillis: Long = 80L,
    repeatableSingle: Boolean = false
): TapDispatch = when {
    !forceDouble && gapMillis in 0 until echoRefractoryMillis -> TapDispatch.SUPPRESS_ECHO
    forceDouble -> TapDispatch.DOUBLE
    hasPending && !(eagerSingle && repeatableSingle) -> TapDispatch.DOUBLE
    eagerSingle -> TapDispatch.SINGLE_NOW
    else -> TapDispatch.SINGLE_DEFERRED
}

/**
 * Actions a rider performs in a row rather than once — moving a cursor down a list, stepping a
 * volume, turning a wheel. Two of these in quick succession mean "twice", never "the double
 * gesture"; see [resolveTapDispatch].
 *
 * Deliberately excludes the one-shot verbs (SELECT, BACK, HOME, ASSISTANT, the dashboard and
 * media commands): pressing those twice quickly IS the idiom a double mapping is for.
 */
internal fun isRepeatableAction(action: HandlebarAction): Boolean = when (action) {
    HandlebarAction.SCROLL_FORWARD,
    HandlebarAction.SCROLL_BACK,
    HandlebarAction.DPAD_UP,
    HandlebarAction.DPAD_DOWN,
    HandlebarAction.DPAD_LEFT,
    HandlebarAction.DPAD_RIGHT,
    HandlebarAction.MEDIA_VOLUME_UP,
    HandlebarAction.MEDIA_VOLUME_DOWN -> true
    else -> false
}

internal sealed interface VolumeDeltaRead {
    /** Repeatable scroll presses fused into one write by the poll window — replay each click. */
    data class ScrollClicks(val gesture: HandlebarGesture, val count: Int) : VolumeDeltaRead
    data class Tap(
        val single: HandlebarGesture,
        val double: HandlebarGesture,
        val forceDouble: Boolean
    ) : VolumeDeltaRead
}

/**
 * Interprets one absolute-volume delta. AVRCP volume is cumulative, so the 250ms poll can fuse
 * quick repeated presses into a single larger write. When the single-press gesture maps to a
 * rotary scroll — a naturally repeatable action — a 2-step jump is replayed as two clicks instead
 * of being mistaken for a gesture. Jumps of [DOUBLE_PRESS_VOLUME_STEPS]+ keep the field-proven
 * meaning of a dash-coalesced double press, which is how BACK/HOME stay reachable from a
 * volume-only handlebar.
 *
 * Real motorcycles break the ±1-step assumption: the CFDL16 dash does not nudge the pinned
 * volume, it overwrites the stream with its own absolute value (road test 2026-07-29: pin 159,
 * bike wrote 70 → delta −89), so a jump of a quarter of the stream range or more is read as ONE
 * press of that sign. A genuine double press arrives as two separate overwrites and still
 * becomes a double through the tap window.
 *
 * Every threshold is a FRACTION of [streamMax], never a fixed number of steps. Phones disagree
 * wildly on how fine that scale is: the usual 0-15 moves one step per key press, while a
 * OnePlus CPH2653 runs 0-160 and moves ten (road test 2026-07-29) - and against a fixed
 * 3-step threshold every single press on that phone was read as a double.
 */
internal fun interpretVolumeDelta(
    delta: Int,
    singleAction: HandlebarAction,
    streamMax: Int
): VolumeDeltaRead? {
    if (delta == 0) return null
    val up = delta > 0
    val single = if (up) HandlebarGesture.VOLUME_UP else HandlebarGesture.VOLUME_DOWN
    val double = if (up) HandlebarGesture.VOLUME_UP_DOUBLE else HandlebarGesture.VOLUME_DOWN_DOUBLE
    val magnitude = abs(delta)
    val absoluteOverwriteFloor = maxOf(streamMax / 4, DOUBLE_PRESS_VOLUME_STEPS + 2)
    if (magnitude >= absoluteOverwriteFloor) {
        return VolumeDeltaRead.Tap(single, double, forceDouble = false)
    }
    // Count PRESSES, not raw steps. One press of a hardware volume key moves about a
    // fifteenth of the range whatever the scale - 1 step of 15, ten of 160 - and judging raw
    // steps made every single press on a fine-grained phone look like a double (OnePlus
    // CPH2653, 0-160, road test 2026-07-29). A delta smaller than one press did not come
    // from a key at all but from a peer writing in its own units, like the T-Box simulator's
    // ±1/±3 on a 255-step stream, and there the raw step count keeps its field-proven meaning.
    val singlePressStep = maxOf(streamMax / 15, 1)
    val presses = magnitude / singlePressStep
    val effective = if (presses >= 1) presses else magnitude
    val scrollMapped = singleAction == HandlebarAction.SCROLL_FORWARD ||
        singleAction == HandlebarAction.SCROLL_BACK
    if (scrollMapped && effective in 2 until DOUBLE_PRESS_VOLUME_STEPS) {
        return VolumeDeltaRead.ScrollClicks(single, effective)
    }
    return VolumeDeltaRead.Tap(single, double, effective >= DOUBLE_PRESS_VOLUME_STEPS)
}
