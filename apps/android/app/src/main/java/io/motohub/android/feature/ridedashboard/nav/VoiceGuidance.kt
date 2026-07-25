package io.motohub.android.feature.ridedashboard.nav

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Announces upcoming maneuvers at fixed distance thresholds. Baseline M1
 * guidance; speed-scaled thresholds and lane guidance are M3 polish.
 */
class VoiceGuidance(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val navigationAudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(navigationAudioAttributes)
        .setOnAudioFocusChangeListener { }
        .setWillPauseWhenDucked(false)
        .build()
    private val duckingLock = Any()
    private val pendingUtteranceIds = mutableSetOf<String>()
    private var duckingFocusHeld = false
    private var textToSpeech: TextToSpeech? = null
    private var ready = false
    private var announcedManeuverPointIndex: Int? = null
    private val announcedThresholds = mutableSetOf<Double>()

    init {
        textToSpeech = TextToSpeech(appContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                textToSpeech?.language = Locale.US
                textToSpeech?.setAudioAttributes(navigationAudioAttributes)
                textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String) = Unit

                    override fun onDone(utteranceId: String) {
                        releaseDuckingFocusWhenIdle(utteranceId)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String) {
                        releaseDuckingFocusWhenIdle(utteranceId)
                    }

                    override fun onError(utteranceId: String, errorCode: Int) {
                        releaseDuckingFocusWhenIdle(utteranceId)
                    }
                })
            }
        }
    }

    fun onProgress(progress: NavigationProgress) {
        val maneuver = progress.currentManeuver ?: return
        if (announcedManeuverPointIndex != maneuver.pointIndex) {
            announcedManeuverPointIndex = maneuver.pointIndex
            announcedThresholds.clear()
        }
        for (threshold in ANNOUNCE_THRESHOLDS_METERS) {
            if (progress.distanceToManeuverMeters <= threshold && announcedThresholds.add(threshold)) {
                speak(announcementText(maneuver.instruction, threshold))
                break
            }
        }
    }

    fun shutdown() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        ready = false
        synchronized(duckingLock) {
            pendingUtteranceIds.clear()
            abandonDuckingFocusLocked()
        }
    }

    private fun announcementText(instruction: String, threshold: Double): String =
        if (threshold >= NEAR_THRESHOLD_METERS) instruction else "In ${threshold.roundToInt()} meters, $instruction"

    private fun speak(text: String) {
        if (!ready || NavigationRuntime.voiceMuted.value) return
        val utteranceId = "nav-${System.nanoTime()}"
        synchronized(duckingLock) {
            pendingUtteranceIds += utteranceId
            requestDuckingFocusLocked()
        }
        val result = textToSpeech?.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
        if (result != TextToSpeech.SUCCESS) releaseDuckingFocusWhenIdle(utteranceId)
    }

    /**
     * Announces navigation as transient guidance. Android then asks the current media app
     * (Spotify, YouTube Music, a radio player, etc.) to lower its own stream; MOTO-HUB never
     * changes the user's music volume directly. Focus is abandoned as soon as the final queued
     * instruction completes, so the other app restores its normal volume automatically.
     */
    private fun requestDuckingFocusLocked() {
        if (duckingFocusHeld) return
        duckingFocusHeld = audioManager.requestAudioFocus(audioFocusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun releaseDuckingFocusWhenIdle(utteranceId: String) {
        synchronized(duckingLock) {
            pendingUtteranceIds.remove(utteranceId)
            if (pendingUtteranceIds.isEmpty()) abandonDuckingFocusLocked()
        }
    }

    private fun abandonDuckingFocusLocked() {
        if (!duckingFocusHeld) return
        try {
            audioManager.abandonAudioFocusRequest(audioFocusRequest)
        } catch (_: Throwable) {
            // The TTS engine can finish while Android is tearing down the activity.
        }
        duckingFocusHeld = false
    }

    private companion object {
        val ANNOUNCE_THRESHOLDS_METERS = listOf(300.0, 100.0, 30.0)
        const val NEAR_THRESHOLD_METERS = 50.0
    }
}
