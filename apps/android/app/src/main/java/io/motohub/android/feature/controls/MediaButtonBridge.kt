package io.motohub.android.feature.controls

import io.motohub.android.i18n.motoHubText

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import io.motohub.android.R
import io.motohub.android.aa.AaInput
import io.motohub.android.aa.AaInputBridge
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
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private var session: MediaSession? = null
    private var volumeObserver: ContentObserver? = null
    private var focusRequest: AudioFocusRequest? = null
    private var silentTrack: AudioTrack? = null
    private var pinnedVolume = -1
    private var previousVolume = -1
    private var pendingCapture = false
    @Volatile private var ignoreVolumeChanges = false
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
            session?.isActive = false
            handler.postDelayed({
                if (!captureActive || session == null) return@postDelayed
                session?.isActive = true
                publishMetadata()
                postMediaNotification()
                pinVolume()
                log("[BTN] $targetName media focus re-asserted; handlebar input ready")
            }, REASSERT_GAP_MILLIS)
        }, REASSERT_SETTLE_MILLIS)
    }

    fun stop() {
        handler.post {
            pendingCapture = false
            selectDownAt = 0L
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

    private fun enableCapture() {
        if (session == null) {
            log("[BTN] Cannot enable capture before the $targetName service is ready")
            return
        }
        captureActive = true
        if (previousVolume < 0) {
            previousVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        }
        pinVolume()
        val granted = requestMediaFocus()
        startSilentTrack()
        session?.isActive = true
        publishMetadata()
        postMediaNotification()
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
     * dropping and re-taking focus altogether.
     */
    private fun requestMediaFocus(): Boolean {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(audioAttributes)
            .setOnAudioFocusChangeListener { }
            .build()
        focusRequest = request
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun disableCapture() {
        if (!captureActive && focusRequest == null) return
        captureActive = false
        selectDownAt = 0L
        cancelPendingTaps()
        cancelMediaNotification()
        stopSilentTrack()
        handler.removeCallbacks(volumePoll)
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
        if (!captureActive || pinnedVolume < 0) return
        if (ignoreVolumeChanges) return
        val current = runCatching {
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        }.getOrNull() ?: return
        if (current == pinnedVolume) return
        val delta = current - pinnedVolume
        try { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, pinnedVolume, 0) } catch (_: Throwable) {}
        val up = delta > 0
        val single = if (up) HandlebarGesture.VOLUME_UP else HandlebarGesture.VOLUME_DOWN
        val double = if (up) HandlebarGesture.VOLUME_UP_DOUBLE else HandlebarGesture.VOLUME_DOWN_DOUBLE
        val forceDouble = abs(delta) >= DOUBLE_PRESS_VOLUME_STEPS
        log("[BTN] volume ${if (delta > 0) "UP" else "DOWN"}; pinned=$pinnedVolume, delta=$delta")
        detectDoubleTap(single, double, forceDouble)
    }

    private fun unregisterVolumeObserver() {
        volumeObserver?.let { observer ->
            runCatching { context.contentResolver.unregisterContentObserver(observer) }
        }
        volumeObserver = null
    }

    private class TapState(var pending: Runnable? = null, var lastAt: Long = 0L)
    private val taps = HashMap<HandlebarGesture, TapState>()

    /** Delays singles long enough to distinguish them from a second press on the same channel. */
    private fun detectDoubleTap(
        single: HandlebarGesture,
        double: HandlebarGesture,
        forceDouble: Boolean
    ) {
        val state = taps.getOrPut(single) { TapState() }
        val now = SystemClock.uptimeMillis()
        if (!forceDouble && now - state.lastAt < ECHO_REFRACTORY_MILLIS) {
            state.lastAt = now
            return
        }
        state.lastAt = now
        val wasPending = state.pending != null
        state.pending?.let(handler::removeCallbacks)
        state.pending = null
        if (forceDouble || wasPending) {
            dispatch(double)
            return
        }
        val pending = Runnable {
            state.pending = null
            dispatch(single)
        }
        state.pending = pending
        handler.postDelayed(pending, HandlebarTimingPrefs.doubleTapMillis(context))
    }

    private fun cancelPendingTaps() {
        taps.values.forEach { state -> state.pending?.let(handler::removeCallbacks) }
        taps.clear()
    }

    private fun dispatch(gesture: HandlebarGesture) {
        if (!captureActive) return
        val handledByTarget = runCatching { gestureHandler?.invoke(gesture) == true }
            .onFailure { log("[BTN] $targetName gesture handler failed: ${it.message}") }
            .getOrDefault(false)
        if (handledByTarget) {
            log("[BTN] ${gesture.label} -> $targetName")
            return
        }
        val action = HandlebarControlStore.action(context, gesture)
        log("[BTN] ${gesture.label} -> ${action.label}")
        when (action) {
            HandlebarAction.NONE -> Unit
            HandlebarAction.SCROLL_FORWARD -> sendScroll(+1)
            HandlebarAction.SCROLL_BACK -> sendScroll(-1)
            HandlebarAction.DPAD_UP -> sendKey(AaInput.KEY_UP)
            HandlebarAction.DPAD_DOWN -> sendKey(AaInput.KEY_DOWN)
            HandlebarAction.DPAD_LEFT -> sendKey(AaInput.KEY_LEFT)
            HandlebarAction.DPAD_RIGHT -> sendKey(AaInput.KEY_RIGHT)
            HandlebarAction.SELECT -> sendKey(AaInput.KEY_ENTER)
            HandlebarAction.BACK -> sendKey(AaInput.KEY_BACK)
            HandlebarAction.HOME -> sendKey(AaInput.KEY_HOME)
            HandlebarAction.ASSISTANT -> sendKey(AaInput.KEY_ASSISTANT)
            HandlebarAction.NAV_1 -> navToSavedPlace(context, 0)
            HandlebarAction.NAV_2 -> navToSavedPlace(context, 1)
            HandlebarAction.NAV_3 -> navToSavedPlace(context, 2)
        }
    }

    fun injectSimulatorGesture(gesture: HandlebarGesture) {
        handler.post {
            log("[BTN] simulator injected ${gesture.label}")
            dispatch(gesture)
        }
    }

    private fun navToSavedPlace(context: Context, slot: Int) {
        val query = SavedPlaces.query(context, slot)
        if (query.isBlank()) {
            log("[BTN] saved place ${slot + 1} is not set — set it in Controls → Saved Places")
            return
        }
        log("[BTN] launching navigation to saved place ${slot + 1}: $query")
        NavLauncher.navigate(context, query, log)
    }

    private fun sendKey(keycode: Int) {
        if (!AaInputBridge.sendKey(keycode)) log("[BTN] Android Auto input is not ready; key=$keycode dropped")
    }

    private fun sendScroll(delta: Int) {
        if (!AaInputBridge.sendScroll(delta)) log("[BTN] Android Auto input is not ready; scroll=$delta dropped")
    }

    private val callback = object : MediaSession.Callback() {
        override fun onMediaButtonEvent(intent: Intent): Boolean {
            @Suppress("DEPRECATION")
            val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
            if (!captureActive || event == null) return false
            val handled = isSelectKey(event.keyCode) || event.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT ||
                event.keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS ||
                event.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ||
                event.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND
            if (!handled) return false
            when (event.action) {
                KeyEvent.ACTION_DOWN -> onKeyDown(event.keyCode)
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

    private fun isSelectKey(keyCode: Int) = keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
        keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE ||
        keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE

    private fun onKeyDown(keyCode: Int) {
        log("[BTN] media key ${KeyEvent.keyCodeToString(keyCode)} down ($keyCode)")
        when {
            isSelectKey(keyCode) -> if (selectDownAt == 0L) {
                selectDownAt = SystemClock.elapsedRealtime()
            }
            keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ->
                detectDoubleTap(
                    HandlebarGesture.TRACK_FORWARD,
                    HandlebarGesture.TRACK_FORWARD_DOUBLE,
                    forceDouble = false
                )
            keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS || keyCode == KeyEvent.KEYCODE_MEDIA_REWIND ->
                detectDoubleTap(
                    HandlebarGesture.TRACK_BACK,
                    HandlebarGesture.TRACK_BACK_DOUBLE,
                    forceDouble = false
                )
        }
    }

    private fun onKeyUp(keyCode: Int) {
        if (!isSelectKey(keyCode)) return
        val startedAt = selectDownAt
        selectDownAt = 0L
        if (startedAt == 0L) {
            dispatchSelectTap()
            return
        }
        val heldMillis = SystemClock.elapsedRealtime() - startedAt
        val isLong = heldMillis >= HandlebarTimingPrefs.selectHoldMillis(context)
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
                .setAudioAttributes(audioAttributes)
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
            track.setVolume(0f)
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

    private fun publishMetadata() {
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
    }

    private fun postMediaNotification() {
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
        }.onFailure { log("[BTN] media notification failed: ${it.message}") }
    }

    private fun cancelMediaNotification() {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) }
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
        const val TARGET_RIDE_DASHBOARD = "Ride Dashboard"

        private const val CHANNEL_ID = "motohub_handlebar_controls"
        private const val NOTIFICATION_ID = 4203
        private const val TRACK_DURATION_MS = 3_600_000L
        private const val VOLUME_POLL_INTERVAL_MILLIS = 250L
        private const val REASSERT_SETTLE_MILLIS = 3_000L
        private const val REASSERT_GAP_MILLIS = 500L
        private const val ECHO_REFRACTORY_MILLIS = 80L
        private const val SELECT_DEDUP_MILLIS = 100L
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

internal fun gestureForVolumeDelta(delta: Int): HandlebarGesture? = when {
    delta == 0 -> null
    delta > 0 && abs(delta) >= DOUBLE_PRESS_VOLUME_STEPS -> HandlebarGesture.VOLUME_UP_DOUBLE
    delta > 0 -> HandlebarGesture.VOLUME_UP
    abs(delta) >= DOUBLE_PRESS_VOLUME_STEPS -> HandlebarGesture.VOLUME_DOWN_DOUBLE
    else -> HandlebarGesture.VOLUME_DOWN
}
