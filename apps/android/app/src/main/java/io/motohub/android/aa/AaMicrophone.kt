package io.motohub.android.aa

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import io.motohub.android.aa.proto.Common
import io.motohub.android.aa.proto.Media
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/** Streams the phone or Bluetooth helmet microphone to Android Auto when Assistant opens it. */
class AaMicrophone(
    private val context: Context,
    private val transport: AapTransport,
    private val log: (String) -> Unit
) {
    companion object {
        const val SAMPLE_RATE = 16_000
        private const val CHUNK_SAMPLES = SAMPLE_RATE / 50
    }

    @Volatile private var recording = false
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    @Volatile private var sessionId = 0

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun setSessionId(id: Int) {
        sessionId = id
    }

    fun onRequest(open: Boolean, channel: Int) {
        if (open) start() else stop("Android Auto closed the microphone")
        val status = if (!open || recording) {
            Common.MessageStatus.STATUS_SUCCESS_VALUE
        } else {
            Common.MessageStatus.STATUS_INTERNAL_ERROR_VALUE
        }
        transport.send(
            AapMessage(
                channel,
                Media.MsgType.MEDIA_MESSAGE_MICROPHONE_RESPONSE_VALUE,
                Media.MicrophoneResponse.newBuilder()
                    .setStatus(status)
                    .setSessionId(sessionId)
                    .build()
            )
        )
        log("[MIC] request open=$open -> ${if (recording) "recording" else "closed"}")
    }

    /**
     * Stops recording and releases the recorder without racing [pump]'s thread: it can be
     * blocked inside [AudioRecord.read], and [Thread.interrupt] does not unblock that call
     * (it is not an interruptible-blocking-call in the java.util.concurrent sense), while
     * concurrently calling [AudioRecord.release] on the same object from another thread
     * while a `read()` is in flight is documented as unsafe. [AudioRecord.stop] is safe to
     * call from another thread and causes a blocked `read()` to return promptly, so it is
     * called before joining the worker, and [AudioRecord.release] only after the join.
     */
    fun stop(reason: String) {
        if (!recording && recorder == null) return
        recording = false
        val activeRecorder = recorder
        runCatching { activeRecorder?.stop() }
        val activeWorker = worker
        worker = null
        if (activeWorker != null && activeWorker !== Thread.currentThread()) {
            activeWorker.join(500)
        }
        activeRecorder?.release()
        recorder = null
        releaseBluetoothRoute()
        log("[MIC] stopped: $reason")
    }

    @SuppressLint("MissingPermission")
    private fun start() {
        if (recording) return
        if (!hasPermission()) {
            log("[MIC] RECORD_AUDIO permission is missing; voice input is unavailable")
            return
        }
        try {
            preferBluetoothRoute()
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(CHUNK_SAMPLES * 2 * 4)
            val activeRecorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer
            )
            if (activeRecorder.state != AudioRecord.STATE_INITIALIZED) {
                activeRecorder.release()
                log("[MIC] AudioRecord initialization failed")
                return
            }
            recorder = activeRecorder
            recording = true
            activeRecorder.startRecording()
            worker = thread(name = "motohub-aa-mic", isDaemon = true) { pump(activeRecorder) }
            log("[MIC] recording started at ${SAMPLE_RATE} Hz mono")
        } catch (failure: Throwable) {
            recording = false
            recorder?.release()
            recorder = null
            releaseBluetoothRoute()
            log("[MIC] start failed: $failure")
        }
    }

    private fun pump(activeRecorder: AudioRecord) {
        val samples = ShortArray(CHUNK_SAMPLES)
        while (recording) {
            val count = runCatching {
                activeRecorder.read(samples, 0, samples.size)
            }.getOrDefault(0)
            if (count > 0) {
                runCatching { transport.send(micData(samples, count)) }
                    .onFailure {
                        log("[MIC] send failed: $it")
                        recording = false
                    }
            }
        }
    }

    private fun micData(samples: ShortArray, count: Int): AapMessage {
        val pcmBytes = count * 2
        val payloadLength = 8 + pcmBytes
        val totalSize = AapMessage.HEADER_SIZE + MsgType.SIZE + payloadLength
        val data = ByteArray(totalSize)
        data[0] = Channel.ID_MIC.toByte()
        data[1] = 0x0b
        Utils.intToBytes(MsgType.SIZE + payloadLength, 2, data)
        data[4] = 0
        data[5] = 0
        val payload = ByteBuffer.wrap(
            data,
            AapMessage.HEADER_SIZE + MsgType.SIZE,
            payloadLength
        ).order(ByteOrder.BIG_ENDIAN)
        payload.putLong(SystemClock.elapsedRealtimeNanos() / 1_000L)
        payload.order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until count) payload.putShort(samples[index])
        return AapMessage(
            Channel.ID_MIC,
            0x0b,
            Media.MsgType.MEDIA_MESSAGE_DATA_VALUE,
            AapMessage.HEADER_SIZE + MsgType.SIZE,
            totalSize,
            data
        )
    }

    private fun preferBluetoothRoute() {
        runCatching {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val bluetooth = audioManager.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                }
                if (bluetooth != null) {
                    audioManager.setCommunicationDevice(bluetooth)
                    log("[MIC] using Bluetooth headset microphone")
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.startBluetoothSco()
            }
        }.onFailure { log("[MIC] Bluetooth microphone route unavailable: $it") }
    }

    private fun releaseBluetoothRoute() {
        runCatching {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.stopBluetoothSco()
            }
        }
    }
}
