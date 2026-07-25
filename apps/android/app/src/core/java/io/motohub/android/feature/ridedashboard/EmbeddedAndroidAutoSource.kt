package io.motohub.android.feature.ridedashboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import io.motohub.android.aa.AaInput
import io.motohub.android.androidauto.AaInputBridge
import io.motohub.android.aa.AaReceiver
import io.motohub.android.aa.SingleKeyKeyManager
import io.motohub.android.androidauto.AaCompositor
import io.motohub.android.androidauto.AndroidAutoCapabilityProfile
import io.motohub.android.androidauto.AndroidAutoDisplayMode
import io.motohub.android.androidauto.AndroidAutoNightModeStore
import io.motohub.android.androidauto.AndroidAutoPreviewController
import io.motohub.android.androidauto.AndroidAutoPreviewRuntime
import io.motohub.android.androidauto.TBoxScreenMargins
import io.motohub.android.session.ProjectionEventLog
import java.util.concurrent.atomic.AtomicBoolean

/** Decodes Android Auto into a CPU-readable frame consumed by the dashboard Canvas renderer. */
class EmbeddedAndroidAutoSource(
    context: Context,
    private val capabilityProfile: AndroidAutoCapabilityProfile,
    override val displayMode: AndroidAutoDisplayMode
) : AndroidAutoPreviewController, EmbeddedAndroidAutoVideoSource {
    private val applicationContext = context.applicationContext
    private val frameLock = Any()
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val acceptingFrames = AtomicBoolean(false)
    private val hasFrame = AtomicBoolean(false)
    private val imageThread = HandlerThread("ride-dashboard-aa-frames").apply { start() }
    private val imageHandler = Handler(imageThread.looper)
    private var imageReader: ImageReader? = null
    private var frameBitmap: Bitmap? = null
    private var sourceRect = Rect()
    private var compositor: AaCompositor? = null
    private var receiver: AaReceiver? = null

    override val width: Int get() = capabilityProfile.video.width
    override val height: Int get() = capabilityProfile.video.height

    override fun start(): Boolean {
        RideDashboardAndroidAutoRuntime.publish(RideDashboardAndroidAutoState.Preparing)
        if (!SingleKeyKeyManager.isAvailable(applicationContext)) {
            fail("Android Auto identity is not included in this build.")
            return false
        }
        return runCatching {
            acceptingFrames.set(true)
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            reader.setOnImageAvailableListener({ source -> copyLatestFrame(source) }, imageHandler)
            imageReader = reader

            val activeCompositor = AaCompositor(
                log = ::log,
                displayMode = displayMode,
                sourceGeometry = capabilityProfile.video,
                touchSurface = capabilityProfile.touchSurface,
                screenMargins = capabilityProfile.screenMargins
           )
            check(activeCompositor.start()) { "Android Auto compositor failed to initialize (EGL/GL)" }
            // Keep the compositor preview output available for the phone. The dashboard frame
            // reader is the primary output for this embedded session.
            activeCompositor.setOutput(reader.surface, width, height, width, height)
            val decoderSurface = activeCompositor.inputSurface
                ?: error("Android Auto compositor did not create its decoder surface")
            compositor = activeCompositor

            val activeReceiver = AaReceiver(
                context = applicationContext,
                encoderSurface = decoderSurface,
                log = ::log,
                onVideoReady = {
                    RideDashboardAndroidAutoRuntime.publish(RideDashboardAndroidAutoState.Streaming)
                    ProjectionEventLog.record("RIDE_AA", "Embedded Android Auto video is available.")
                },
                onSessionEnded = { clean, userExit ->
                    if (receiver != null) {
                        if (userExit) {
                            ProjectionEventLog.record("RIDE_AA", "Embedded Android Auto exited by user.")
                            stop()
                        } else {
                            fail(
                                if (clean) "Embedded Android Auto session ended."
                                else "Embedded Android Auto connection closed unexpectedly."
                            )
                        }
                    }
                },
                mapTouchToSource = { x, y ->
                    x.coerceIn(0, width - 1) to y.coerceIn(0, height - 1)
                },
                capabilityProfile = capabilityProfile
            )
            check(activeReceiver.start()) { "Android Auto local port ${AaReceiver.PORT} is unavailable" }
            receiver = activeReceiver
            AndroidAutoPreviewRuntime.install(this)
            RideDashboardAndroidAutoRuntime.publish(RideDashboardAndroidAutoState.ReceiverReady)
            ProjectionEventLog.record(
                "RIDE_AA",
                "Embedded Android Auto receiver ready at ${width}x$height."
            )
            true
        }.getOrElse { failure ->
            val message = "Embedded Android Auto did not start: ${failure.message}"
            stop()
            fail(message, failure)
            false
        }
    }

    override fun draw(canvas: Canvas, destination: RectF): Boolean {
        if (!hasFrame.get()) return false
        synchronized(frameLock) {
            val bitmap = frameBitmap ?: return false
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(bitmap, sourceRect, destination, framePaint)
        }
        return true
    }

   override fun sendSourceTouch(action: Int, pointerId: Int, x: Int, y: Int) {
        val mapped = compositor?.mapSourceToUi(x, y) ?: return
       receiver?.sendSourceTouch(
            action = when (action) {
                AaInput.ACTION_DOWN -> AaInput.ACTION_DOWN
                AaInput.ACTION_MOVE -> AaInput.ACTION_MOVE
                else -> AaInput.ACTION_UP
            },
            pointerId = pointerId,
            sourceX = mapped.first,
            sourceY = mapped.second
        )
    }

    /** Applies a screen-margin change to the running compositor without restarting the source. */
    override fun refreshMargins(margins: TBoxScreenMargins) {
        compositor?.refreshMargins(margins)
    }

    override fun stop() {
        acceptingFrames.set(false)
        AndroidAutoPreviewRuntime.clear(this)
        val activeReceiver = receiver
        receiver = null
        activeReceiver?.stop()
        compositor?.clearPreview()
        compositor?.clearOutput()
        compositor?.release()
        compositor = null
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null
        synchronized(frameLock) {
            frameBitmap?.recycle()
            frameBitmap = null
            sourceRect.setEmpty()
            hasFrame.set(false)
        }
        imageThread.quitSafely()
        RideDashboardAndroidAutoRuntime.publish(RideDashboardAndroidAutoState.Idle)
    }

    override fun attachPreview(surface: Surface, width: Int, height: Int) {
        compositor?.setPreview(surface, width, height)
    }

    override fun detachPreview() {
        compositor?.clearPreview()
    }

    override fun sendPreviewTouch(action: Int, pointerId: Int, x: Int, y: Int) {
        val mapped = compositor?.mapPreviewToUi(x, y) ?: return
        receiver?.sendSourceTouch(action, pointerId, mapped.first, mapped.second)
    }

    override fun sendPreviewKey(keycode: Int): Boolean = AaInputBridge.sendKey(keycode)

    override fun sendPreviewScroll(delta: Int): Boolean = AaInputBridge.sendScroll(delta)

    override fun setPreviewNightMode(isNight: Boolean): Boolean {
        val applied = receiver?.setNightMode(isNight) == true
        if (applied) AndroidAutoNightModeStore(applicationContext).save(isNight)
        return applied
    }

    private fun copyLatestFrame(reader: ImageReader) {
        if (!acceptingFrames.get()) return
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        image.use {
            if (!acceptingFrames.get()) return
            val plane = it.planes.firstOrNull() ?: return
            val pixelStride = plane.pixelStride.coerceAtLeast(1)
            val rowStride = plane.rowStride.coerceAtLeast(width * pixelStride)
            val paddedWidth = rowStride / pixelStride
            synchronized(frameLock) {
                if (!acceptingFrames.get()) return
                val bitmap = frameBitmap?.takeIf {
                    it.width == paddedWidth && it.height == height && !it.isRecycled
                } ?: Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888).also {
                    frameBitmap?.recycle()
                    frameBitmap = it
                }
                plane.buffer.rewind()
                bitmap.copyPixelsFromBuffer(plane.buffer)
                sourceRect.set(0, 0, width, height)
                hasFrame.set(true)
            }
        }
    }

    private fun fail(message: String, failure: Throwable? = null) {
        ProjectionEventLog.error("RIDE_AA", message, failure)
        RideDashboardAndroidAutoRuntime.publish(RideDashboardAndroidAutoState.Failed(message))
    }

    private fun log(message: String) {
        ProjectionEventLog.record("RIDE_AA", message)
    }
}
