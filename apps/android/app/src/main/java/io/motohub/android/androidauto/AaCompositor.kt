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
import io.motohub.android.aa.ServiceDiscoveryResponse
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch

/** GPU compositor that fills the TFT while keeping the complete source in the phone preview. */
class AaCompositor(
    private val log: (String) -> Unit,
    private val displayMode: AndroidAutoDisplayMode
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
    @Volatile private var tftViewport: PreviewViewport? = null

    private val texMatrix = FloatArray(16)
    private val tftMatrix = FloatArray(16)
    private val fullSourceMatrix = FloatArray(16)
    @Volatile private var hasContent = false
    private var lastDrawMs = 0L

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

    fun start() {
        val latch = CountDownLatch(1)
        handler.post {
            try {
                initEgl()
                initGl()
                surfaceTexture = SurfaceTexture(textureId).apply {
                    setDefaultBufferSize(
                        ServiceDiscoveryResponse.AA_WIDTH,
                        ServiceDiscoveryResponse.AA_HEIGHT
                    )
                    setOnFrameAvailableListener({ handler.post(::onFrame) }, handler)
                }
                inputSurface = Surface(surfaceTexture)
                handler.postDelayed(keepAlive, KEEPALIVE_INTERVAL_MS)
                log(
                    "[COMPOSITOR] ready source=${ServiceDiscoveryResponse.AA_WIDTH}x" +
                        "${ServiceDiscoveryResponse.AA_HEIGHT}"
                )
            } catch (failure: Throwable) {
                log("[COMPOSITOR] init failed: $failure")
            } finally {
                latch.countDown()
            }
        }
        latch.await()
    }

    fun setOutput(encoderSurface: Surface, cw: Int, ch: Int, sw: Int, sh: Int) {
        handler.post {
            try {
                encoderWindowSurface = replaceWindowSurface(encoderWindowSurface, encoderSurface)
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

    fun setPreview(surface: Surface, width: Int, height: Int) {
        handler.post {
            try {
                previewWindowSurface = replaceWindowSurface(previewWindowSurface, surface)
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

    fun mapCanvasToSource(cx: Int, cy: Int): Pair<Int, Int>? {
        return tftViewport?.mapToSource(cx, cy)
    }

    fun mapPreviewToSource(px: Int, py: Int): Pair<Int, Int>? {
        if (previewVpW <= 0 || previewVpH <= 0) return null
        return currentPreviewViewport().mapToSource(px, py)
    }

    private fun configureTftViewport() {
        val canvas = DisplayGeometry(canvasW, canvasH)
        val source = DisplayGeometry(srcW, srcH)
        tftViewport = when (displayMode) {
            AndroidAutoDisplayMode.LETTERBOX -> calculatePreviewViewport(canvas, source)
            AndroidAutoDisplayMode.STRETCH -> PreviewViewport(
                x = 0,
                y = 0,
                width = canvas.width,
                height = canvas.height,
                source = source
            )
        }
        Matrix.setIdentityM(tftMatrix, 0)
        computePreviewViewport()
    }

    private fun computePreviewViewport() {
        if (previewCanvasW <= 0 || previewCanvasH <= 0) return
        val viewport = calculatePreviewViewport(
            canvas = DisplayGeometry(previewCanvasW, previewCanvasH),
            source = previewSourceGeometry()
        )
        previewVpX = viewport.x
        previewVpY = viewport.y
        previewVpW = viewport.width
        previewVpH = viewport.height
    }

    private fun currentPreviewViewport() = PreviewViewport(
        x = previewVpX,
        y = previewVpY,
        width = previewVpW,
        height = previewVpH,
        source = previewSourceGeometry()
    )

    private fun previewSourceGeometry() = DisplayGeometry(
        width = srcW.takeIf { it > 0 } ?: ServiceDiscoveryResponse.AA_WIDTH,
        height = srcH.takeIf { it > 0 } ?: ServiceDiscoveryResponse.AA_HEIGHT
    )

    private fun onFrame() {
        try {
            surfaceTexture.updateTexImage()
        } catch (_: Throwable) {
            return
        }
        hasContent = true
        drawFrame()
    }

    private val keepAlive = object : Runnable {
        override fun run() {
            if (hasContent && encoderWindowSurface != EGL14.EGL_NO_SURFACE) {
                val idleMs = android.os.SystemClock.uptimeMillis() - lastDrawMs
                if (idleMs >= KEEPALIVE_INTERVAL_MS) drawFrame()
            }
            handler.postDelayed(this, KEEPALIVE_INTERVAL_MS)
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
                recordable = true
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
                fullSourceMatrix,
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
        recordable: Boolean
    ) {
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        if (!EGL14.eglMakeCurrent(eglDisplay, target, target, eglContext)) return
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
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

    private fun replaceWindowSurface(current: EGLSurface, surface: Surface): EGLSurface {
        destroyWindowSurface(current)
        return EGL14.eglCreateWindowSurface(
            eglDisplay,
            eglConfig,
            surface,
            intArrayOf(EGL14.EGL_NONE),
            0
        ).also {
            check(it != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed: ${EGL14.eglGetError()}" }
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
        Matrix.setIdentityM(fullSourceMatrix, 0)

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
        const val KEEPALIVE_INTERVAL_MS = 66L
    }
}
