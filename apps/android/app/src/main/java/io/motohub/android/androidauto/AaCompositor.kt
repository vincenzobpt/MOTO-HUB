package io.motohub.android.androidauto

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch

/** GPU compositor that fills the TFT while keeping the complete source in the phone preview. */
class AaCompositor(
    private val log: (String) -> Unit,
    private val displayMode: AndroidAutoDisplayMode,
    private val sourceGeometry: DisplayGeometry,
    touchSurface: DisplayGeometry = sourceGeometry,
    private var screenMargins: TBoxScreenMargins = TBoxScreenMargins.NONE
) {
    private val thread = HandlerThread("aa-compositor").apply { start() }
    private val handler = Handler(thread.looper)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderWindowSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var previewWindowSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uTexMatrix = 0
    private var uCropMatrix = 0
    private var textureId = 0
    private lateinit var surfaceTexture: SurfaceTexture

    @Volatile
    var inputSurface: Surface? = null
        private set

    @Volatile private var canvasW = 0
    @Volatile private var canvasH = 0
    @Volatile private var srcW = 0
    @Volatile private var srcH = 0
    @Volatile private var previewCanvasW = 0
    @Volatile private var previewCanvasH = 0
    @Volatile private var previewVpX = 0
    @Volatile private var previewVpY = 0
    @Volatile private var previewVpW = 0
    @Volatile private var previewVpH = 0
    @Volatile private var touchUiW = touchSurface.width
    @Volatile private var touchUiH = touchSurface.height
    @Volatile private var tftClipLeft = 0
    @Volatile private var tftClipTop = 0
    @Volatile private var tftClipW = 0
    @Volatile private var tftClipH = 0
    @Volatile private var tftViewport: PreviewViewport? = null

    private val texMatrix = FloatArray(16)
    private val tftMatrix = FloatArray(16)
    private val previewMatrix = FloatArray(16)
    @Volatile private var hasContent = false
    @Volatile private var pendingFrame = false
    private var lastDrawMs = 0L
    @Volatile private var frameCap = DEFAULT_FRAME_CAP
    @Volatile private var lastSourceFrameNanos = 0L

    // The decoder may keep producing frames while Android Auto shows a static screen. Coalesce
    // those frames and use a slow redraw only as a transport keep-alive.
    private val keepAliveTickMs = 150L
    private val idleRedrawMs = 2_000L

    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(4 * 4 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(
                floatArrayOf(
                    -1f, -1f, 0f, 0f,
                    1f, -1f, 1f, 0f,
                    -1f, 1f, 0f, 1f,
                    1f, 1f, 1f, 1f
                )
            )
            position(0)
        }

    /** True once EGL/GL initialized and [inputSurface] is ready; false leaves the compositor unusable. */
    fun start(): Boolean {
        val latch = CountDownLatch(1)
        var initialized = false
        handler.post {
            try {
                initEgl()
                initGl()
                surfaceTexture = SurfaceTexture(textureId).apply {
                    setDefaultBufferSize(
                        sourceGeometry.width,
                        sourceGeometry.height
                    )
                    setOnFrameAvailableListener({ handler.post(::onFrame) }, handler)
                }
                inputSurface = Surface(surfaceTexture)
                handler.postDelayed(keepAlive, keepAliveTickMs)
                log(
                    "[COMPOSITOR] ready source=${sourceGeometry.width}x${sourceGeometry.height}"
                )
                initialized = true
            } catch (failure: Throwable) {
                log("[COMPOSITOR] init failed: $failure")
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        return initialized
    }

    fun setOutput(encoderSurface: Surface, cw: Int, ch: Int, sw: Int, sh: Int) {
        handler.post {
            try {
                encoderWindowSurface = replaceWindowSurface(encoderWindowSurface, encoderSurface, "encoder")
                canvasW = cw
                canvasH = ch
                srcW = sw
                srcH = sh
                configureTftViewport()
                log(
                    "[COMPOSITOR] TFT=${cw}x$ch source=${sw}x$sh mode=$displayMode " +
                        "viewport=${tftViewport?.width}x${tftViewport?.height} " +
                        "@(${tftViewport?.x},${tftViewport?.y})"
                )
                if (hasContent) drawFrame()
            } catch (failure: Throwable) {
                log("[COMPOSITOR] setOutput failed: $failure")
            }
        }
    }

    /** Caps source redraws during thermal/link adaptation; keep-alive redraws remain enabled. */
    fun setFrameCap(frameRate: Int) {
        frameCap = frameRate.coerceIn(1, DEFAULT_FRAME_CAP)
        lastSourceFrameNanos = 0L
        pendingFrame = false
    }

    fun setTouchSurface(surface: DisplayGeometry) {
        handler.post {
            touchUiW = surface.width
            touchUiH = surface.height
            if (canvasW > 0 && canvasH > 0 && srcW > 0 && srcH > 0) {
                configureTftViewport()
            }
        }
    }

    /**
     * Applies a changed TFT safe-margin setting to a running session immediately, instead of
     * requiring the rider to stop and restart Android Auto for it to take effect.
     */
    fun refreshMargins(margins: TBoxScreenMargins) {
        handler.post {
            if (screenMargins == margins) return@post
            screenMargins = margins
            log("[COMPOSITOR] screen margins updated: $margins")
            if (canvasW > 0 && canvasH > 0 && srcW > 0 && srcH > 0) {
                configureTftViewport()
                if (hasContent) drawFrame()
            }
        }
    }

    fun setPreview(surface: Surface, width: Int, height: Int) {
        handler.post {
            try {
                previewWindowSurface = replaceWindowSurface(previewWindowSurface, surface, "preview")
                previewCanvasW = width
                previewCanvasH = height
                computePreviewViewport()
                log(
                    "[COMPOSITOR] phone preview=${width}x$height rect=" +
                        "${previewVpW}x$previewVpH @($previewVpX,$previewVpY)"
                )
                if (hasContent) drawFrame()
            } catch (failure: Throwable) {
                log("[COMPOSITOR] preview attach failed: $failure")
            }
        }
    }

    fun clearPreview() {
        handler.post {
            previewWindowSurface = destroyWindowSurface(previewWindowSurface)
            previewCanvasW = 0
            previewCanvasH = 0
            previewVpX = 0
            previewVpY = 0
            previewVpW = 0
            previewVpH = 0
            log("[COMPOSITOR] phone preview detached")
        }
    }

    /** Detaches the encoder surface before its MediaCodec is released during link recovery. */
    fun clearOutput() {
        val latch = CountDownLatch(1)
        handler.post {
            try {
                encoderWindowSurface = destroyWindowSurface(encoderWindowSurface)
                canvasW = 0
                canvasH = 0
                tftViewport = null
                log("[COMPOSITOR] TFT encoder output detached")
            } finally {
                latch.countDown()
            }
        }
        latch.await()
    }

    fun mapCanvasToSource(cx: Int, cy: Int): Pair<Int, Int>? {
        return tftViewport?.mapToSource(cx, cy)
    }

    /** Maps a T-Box canvas point into the touchscreen dimensions advertised to Android Auto. */
    fun mapCanvasToUi(cx: Int, cy: Int): Pair<Int, Int>? {
        if (tftClipW > 0 && tftClipH > 0 &&
            (cx < tftClipLeft || cy < tftClipTop ||
                cx >= tftClipLeft + tftClipW || cy >= tftClipTop + tftClipH)
        ) return null
        val source = mapCanvasToSource(cx, cy) ?: return null
        return mapSourceToUi(source.first, source.second)
    }

    fun mapPreviewToSource(px: Int, py: Int): Pair<Int, Int>? {
        if (previewVpW <= 0 || previewVpH <= 0) return null
        return currentPreviewViewport().mapToSource(px, py)
    }

    fun mapPreviewToUi(px: Int, py: Int): Pair<Int, Int>? =
        mapPreviewToSource(px, py)?.let { mapSourceToUi(it.first, it.second) }

   fun mapSourceToUi(sourceX: Int, sourceY: Int): Pair<Int, Int>? {
        if (sourceX < screenMargins.left || sourceY < screenMargins.top ||
            sourceX >= srcW - screenMargins.right || sourceY >= srcH - screenMargins.bottom
        ) return null
       val uiW = touchUiW.coerceIn(1, srcW.coerceAtLeast(1))
        val uiH = touchUiH.coerceIn(1, srcH.coerceAtLeast(1))
        // The touch surface is srcW/H trimmed by screenMargins.left/top/right/bottom (see
        // AndroidAutoCapabilityProfile.touchSurface and setTouchSurface's caller), so the true
        // left/top offset is screenMargins.left/top - NOT (srcW - uiW) / 2, which silently assumed
        // the trim was split evenly and was wrong for any asymmetric margin (e.g. left=0, right=100).
        val uiX = sourceX - screenMargins.left
        val uiY = sourceY - screenMargins.top
        if (uiX !in 0 until uiW || uiY !in 0 until uiH) return null
        return uiX to uiY
    }

    private fun configureTftViewport() {
        val canvas = DisplayGeometry(canvasW, canvasH)
        val source = DisplayGeometry(srcW, srcH)
        val available = DisplayGeometry(
            width = (canvas.width - screenMargins.left - screenMargins.right).coerceAtLeast(1),
            height = (canvas.height - screenMargins.top - screenMargins.bottom).coerceAtLeast(1)
        )
        Matrix.setIdentityM(tftMatrix, 0)
        tftClipLeft = screenMargins.left
        tftClipTop = screenMargins.top
        tftClipW = available.width
        tftClipH = available.height
        tftViewport = when (displayMode) {
            // The touch/UI surface describes input coordinates only. It must not trim the video:
            // on an 800x384 TFT using an 800x480 AA stream, trimming it made FIT and STRETCH
            // indistinguishable and exposed an inactive strip at the bottom of the display.
            AndroidAutoDisplayMode.LETTERBOX -> calculatePreviewViewport(available, source).offsetBy(
                screenMargins.left,
                screenMargins.top
            )
            AndroidAutoDisplayMode.STRETCH -> {
                val stretchViewport = calculateStretchViewport(
                    canvas = available,
                    source = source
                )
                stretchViewport.copy(
                    x = stretchViewport.x + screenMargins.left,
                    y = stretchViewport.y + screenMargins.top
                )
            }
            AndroidAutoDisplayMode.FILL -> calculateFillViewport(available, source).offsetBy(
                screenMargins.left,
                screenMargins.top
            )
        }
        computePreviewViewport()
    }

    private fun configureCropMatrix(viewport: PreviewViewport) {
        Matrix.setIdentityM(tftMatrix, 0)
        tftMatrix[0] = viewport.sourceWidth.toFloat() / viewport.source.width
        tftMatrix[5] = viewport.sourceHeight.toFloat() / viewport.source.height
        tftMatrix[12] = viewport.sourceLeft.toFloat() / viewport.source.width
        tftMatrix[13] = viewport.sourceTop.toFloat() / viewport.source.height
    }

    private fun computePreviewViewport() {
        if (previewCanvasW <= 0 || previewCanvasH <= 0) return
        val source = previewSourceGeometry()
        val viewport = calculatePreviewViewport(
            canvas = DisplayGeometry(previewCanvasW, previewCanvasH),
            source = source
        )
        previewVpX = viewport.x
        previewVpY = viewport.y
        previewVpW = viewport.width
        previewVpH = viewport.height
        Matrix.setIdentityM(previewMatrix, 0)
    }

    private fun currentPreviewViewport() = PreviewViewport(
        x = previewVpX,
        y = previewVpY,
        width = previewVpW,
        height = previewVpH,
        source = previewSourceGeometry(),
        sourceLeft = 0,
        sourceTop = 0
    )

    private fun previewSourceGeometry() = DisplayGeometry(
        width = srcW.takeIf { it > 0 } ?: sourceGeometry.width,
        height = srcH.takeIf { it > 0 } ?: sourceGeometry.height
    )

    private fun PreviewViewport.offsetBy(dx: Int, dy: Int): PreviewViewport = copy(
        x = x + dx,
        y = y + dy
    )

    private fun onFrame() {
        try {
            surfaceTexture.updateTexImage()
        } catch (_: Throwable) {
            return
        }
        hasContent = true
        val now = System.nanoTime()
        val interval = 1_000_000_000L / frameCap.coerceAtLeast(1)
        val idleMs = android.os.SystemClock.uptimeMillis() - lastDrawMs
        if (lastSourceFrameNanos == 0L || idleMs >= interval / 1_000_000L) {
            lastSourceFrameNanos = now
            pendingFrame = false
            drawFrame()
        } else {
            // SurfaceTexture already contains the newest frame; flush it on the next pacing tick.
            pendingFrame = true
        }
    }

    private val keepAlive = object : Runnable {
        override fun run() {
            if (hasContent && encoderWindowSurface != EGL14.EGL_NO_SURFACE) {
                val idleMs = android.os.SystemClock.uptimeMillis() - lastDrawMs
                val intervalMs = 1_000L / frameCap.coerceAtLeast(1)
                if (pendingFrame && idleMs >= intervalMs) {
                    pendingFrame = false
                    drawFrame()
                } else if (idleMs >= idleRedrawMs) {
                    drawFrame()
                }
            }
            handler.postDelayed(this, keepAliveTickMs)
        }
    }

    private fun drawFrame() {
        if (!::surfaceTexture.isInitialized) return
        surfaceTexture.getTransformMatrix(texMatrix)
        val viewport = tftViewport
        if (encoderWindowSurface != EGL14.EGL_NO_SURFACE && viewport != null) {
            drawTarget(
                encoderWindowSurface,
                viewport.x,
                viewport.y,
                viewport.width,
                viewport.height,
               tftMatrix,
                recordable = true,
                clipX = tftClipLeft,
                clipY = tftClipTop,
                clipWidth = tftClipW,
                clipHeight = tftClipH,
                framebufferHeight = canvasH
            )
            lastDrawMs = android.os.SystemClock.uptimeMillis()
        }
        if (previewWindowSurface != EGL14.EGL_NO_SURFACE) {
            drawTarget(
                previewWindowSurface,
                previewVpX,
                previewVpY,
                previewVpW,
                previewVpH,
                previewMatrix,
                recordable = false
            )
        }
    }

    private fun drawTarget(
        target: EGLSurface,
        viewportX: Int,
        viewportY: Int,
        viewportWidth: Int,
        viewportHeight: Int,
       contentMatrix: FloatArray,
        recordable: Boolean,
        clipX: Int = 0,
        clipY: Int = 0,
        clipWidth: Int = 0,
        clipHeight: Int = 0,
        framebufferHeight: Int = 0
    ) {
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        if (!EGL14.eglMakeCurrent(eglDisplay, target, target, eglContext)) return
       GLES20.glClearColor(0f, 0f, 0f, 1f)
       GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (clipWidth > 0 && clipHeight > 0 && framebufferHeight > 0) {
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
            GLES20.glScissor(clipX, framebufferHeight - clipY - clipHeight, clipWidth, clipHeight)
        }
       GLES20.glViewport(viewportX, viewportY, viewportWidth, viewportHeight)
        GLES20.glUseProgram(program)

        quad.position(0)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPosition)
        quad.position(2)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES20.glUniformMatrix4fv(uCropMatrix, 1, false, contentMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
       GLES20.glDisableVertexAttribArray(aPosition)
       GLES20.glDisableVertexAttribArray(aTexCoord)
        if (clipWidth > 0 && clipHeight > 0 && framebufferHeight > 0) {
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        }

        if (recordable) {
            EGLExt.eglPresentationTimeANDROID(eglDisplay, target, System.nanoTime())
        }
        EGL14.eglSwapBuffers(eglDisplay, target)
    }

    fun release() {
        handler.removeCallbacks(keepAlive)
        handler.post {
            runCatching { inputSurface?.release() }
            inputSurface = null
            runCatching { if (::surfaceTexture.isInitialized) surfaceTexture.release() }
            encoderWindowSurface = destroyWindowSurface(encoderWindowSurface)
            previewWindowSurface = destroyWindowSurface(previewWindowSurface)
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    eglDisplay,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )
                if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, pbuffer)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
        }
        thread.quitSafely()
    }

    /**
     * Destroys [current] and creates [surface]'s replacement. Never throws: on create failure the
     * old handle is already gone (destroyed above), so returning it here would leave the caller's
     * field pointing at a destroyed EGLSurface - drawFrame() would then eglMakeCurrent()/
     * eglSwapBuffers() on a dead surface instead of skipping the target. Always assign the result.
     */
    private fun replaceWindowSurface(current: EGLSurface, surface: Surface, tag: String): EGLSurface {
        destroyWindowSurface(current)
        return try {
            EGL14.eglCreateWindowSurface(
                eglDisplay,
                eglConfig,
                surface,
                intArrayOf(EGL14.EGL_NONE),
                0
            ).also {
                check(it != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed: ${EGL14.eglGetError()}" }
            }
        } catch (failure: Throwable) {
            log("[COMPOSITOR] $tag surface creation failed: $failure")
            EGL14.EGL_NO_SURFACE
        }
    }

    private fun destroyWindowSurface(surface: EGLSurface): EGLSurface {
        if (surface != EGL14.EGL_NO_SURFACE && eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglDestroySurface(eglDisplay, surface)
        }
        return EGL14.EGL_NO_SURFACE
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }
        val configAttributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val configCount = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, configAttributes, 0, configs, 0, 1, configCount, 0)) {
            "eglChooseConfig failed"
        }
        eglConfig = checkNotNull(configs[0])
        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            eglConfig,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0
        )
        pbuffer = EGL14.eglCreatePbufferSurface(
            eglDisplay,
            eglConfig,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
            0
        )
        EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)
    }

    private fun initGl() {
        val vertexShader = """
            uniform mat4 uTexMatrix;
            uniform mat4 uCropMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * uCropMatrix * aTexCoord).xy;
            }
        """.trimIndent()
        val fragmentShader = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() { gl_FragColor = texture2D(sTexture, vTexCoord); }
        """.trimIndent()
        program = linkProgram(vertexShader, fragmentShader)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uCropMatrix = GLES20.glGetUniformLocation(program, "uCropMatrix")
        Matrix.setIdentityM(texMatrix, 0)
        Matrix.setIdentityM(tftMatrix, 0)
        Matrix.setIdentityM(previewMatrix, 0)

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )
    }

    private fun linkProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        return GLES20.glCreateProgram().also { result ->
            GLES20.glAttachShader(result, vertex)
            GLES20.glAttachShader(result, fragment)
            GLES20.glLinkProgram(result)
            val status = IntArray(1)
            GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { GLES20.glGetProgramInfoLog(result) }
        }
    }

    private fun compileShader(type: Int, source: String): Int = GLES20.glCreateShader(type).also { shader ->
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { GLES20.glGetShaderInfoLog(shader) }
    }

    private companion object {
        const val DEFAULT_FRAME_CAP = 30
    }
}
