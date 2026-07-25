package io.motohub.android.feature.ridedashboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.StatFs
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import io.motohub.android.androidauto.DisplayGeometry
import io.motohub.android.androidauto.PreviewViewport
import io.motohub.android.feature.ridedashboard.nav.ManeuverDirection
import io.motohub.android.feature.ridedashboard.nav.NavPoint
import io.motohub.android.feature.ridedashboard.nav.NavigationEngine
import io.motohub.android.feature.ridedashboard.nav.NavRoute
import io.motohub.android.feature.ridedashboard.nav.NavigationProgress
import io.motohub.android.feature.ridedashboard.nav.NavigationM2bSettingsStore
import io.motohub.android.feature.ridedashboard.nav.NavigationRuntime
import io.motohub.android.feature.ridedashboard.nav.VoiceGuidance
import io.motohub.android.feature.ridedashboard.nav.maneuverDirection
import io.motohub.android.feature.trips.TripRecordingRuntime
import io.motohub.android.feature.trips.TripRecordingState
import io.motohub.android.feature.trips.TripTrackPoint
import io.motohub.android.feature.settings.MotoHubSettings
import io.motohub.android.session.ProjectionEventLog
import io.motohub.android.units.UnitFormat
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import io.motohub.android.feature.ridedashboard.widget.DashboardLayoutConfig
import io.motohub.android.feature.ridedashboard.widget.DashboardWidget
import io.motohub.android.feature.ridedashboard.widget.DashboardWidgetIDs
import io.motohub.android.feature.ridedashboard.widget.WidgetColors
import io.motohub.android.feature.ridedashboard.widget.WidgetDrawingContext
import io.motohub.android.feature.ridedashboard.widget.DashboardWidgetRegistry
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

class RideDashboardRenderer(
    context: Context,
    private val surface: Surface,
    private val fps: Int,
    private val bitRate: Int,
    private val tBoxLabel: String,
    private val motorcyclePhotoPath: String? = null,
    private val telemetryProvider: RideTelemetryProvider,
    private val layoutController: RideDashboardLayoutController,
    private val mapSource: RideDashboardMapSource,
    private val embeddedAndroidAuto: EmbeddedAndroidAutoSource?,
    cellularOnlyMaps: Boolean = true,
    /**
     * false (default, real T-Box streaming): stretch the design to fill the
     * surface exactly, independently on X and Y - correct there, since the
     * surface is sized to the bike's own fixed TFT resolution and it should
     * be filled edge-to-edge with no wasted panel space.
     * true (phone preview): the SurfaceView's shape changes with the phone's
     * orientation and has no relationship to any real TFT, so stretching it
     * would distort the preview instead of representing it - scale uniformly
     * and letterbox/pillarbox to the actual design aspect ratio instead.
     */
    private val preserveAspectRatio: Boolean = false,
    private val onFailure: (Throwable) -> Unit,
    /** Widget for the left panel slot; mutable so [updateWidgetLayout] can hot-swap it. */
    @Volatile private var leftWidget: DashboardWidget =
        DashboardWidgetRegistry.forId(DashboardLayoutConfig.DEFAULT.leftWidgetId)!!,
    /** Widget for the right panel slot; mutable so [updateWidgetLayout] can hot-swap it. */
    @Volatile private var rightWidget: DashboardWidget =
        DashboardWidgetRegistry.forId(DashboardLayoutConfig.DEFAULT.rightWidgetId)!!
) {
    private data class PixelBounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        fun contains(x: Int, y: Int): Boolean = x >= left && x < right && y >= top && y < bottom
    }

    private data class EmbeddedAndroidAutoTouchViewport(
        val sourceViewport: PreviewViewport,
        val visibleBounds: PixelBounds,
        val excludedBounds: List<PixelBounds>
    ) {
        fun mapToSource(x: Int, y: Int): Pair<Int, Int>? {
            if (!visibleBounds.contains(x, y)) return null
            if (excludedBounds.any { it.contains(x, y) }) return null
            return sourceViewport.mapToSource(x, y)
        }
    }

    private data class StartupPuzzleTile(
        val source: Rect,
        val destination: RectF
    )

    private data class SavedTrackBounds(
        val minLatitude: Double,
        val maxLatitude: Double,
        val minLongitude: Double,
        val maxLongitude: Double
    ) {
        val centerLatitude: Double get() = (minLatitude + maxLatitude) / 2.0
        val centerLongitude: Double get() = (minLongitude + maxLongitude) / 2.0
    }

    private val applicationContext = context.applicationContext
    private val m2bSettings = NavigationM2bSettingsStore(applicationContext)
    private val running = AtomicBoolean(false)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    }
    private val boldTypeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val monoTypeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    private val monoBoldTypeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private val batteryManager = applicationContext.getSystemService(BatteryManager::class.java)
    private val osmSettings = OsmMapSettingsStore.load(applicationContext)
    private val osmTiles = OpenStreetMapTileProvider(
        context = applicationContext,
        style = osmSettings.baseStyle,
        cellularOnly = cellularOnlyMaps
    )
    private val mapLibreSettings = MapLibreMapSettingsStore.load(applicationContext)
    private val mapLibreSnapshots = MapLibreSnapshotProvider(
        context = applicationContext,
        cellularOnly = cellularOnlyMaps
    )
    private val mapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val startupBrandBackdropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(188, 3, 8, 7)
    }
    private val startupBrandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_PRIMARY
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-black", Typeface.BOLD_ITALIC)
        textScaleX = 1.08f
    }
    private val startupBrandOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.rgb(3, 9, 7)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-black", Typeface.BOLD_ITALIC)
        textScaleX = 1.08f
    }
    private val startupBrandArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
        color = COLOR_PRIMARY
    }
    /** Profile image shown while the TFT is still bringing up its first usable frame. */
    private val startupProfileBitmap: Bitmap? = loadStartupProfileBitmap(motorcyclePhotoPath)
    private var startupPuzzleTiles: List<StartupPuzzleTile> = emptyList()
    private var startupPuzzleCanvasWidth = 0
    private var startupPuzzleCanvasHeight = 0
    private var renderThread: Thread? = null
    @Volatile private var frameRateCap = fps.coerceAtLeast(1)
    private var cachedPhoneBattery = 0
    private var cachedCellularStatus = "OFF"
    private var cachedBatteryTempCelsius = -1f
    private var cachedIsCharging = false
    private var cachedStorageUsedPercent = 0
    private var cachedBatteryVoltageMv = 0
    private var cachedWifiRssiDbm = 0
    private var cachedLinearAccelX = 0f
    private var cachedLinearAccelY = 0f
    private var cachedLinearAccelZ = 0f
    private var cachedLinearAccelPeak = 0f
    private var cachedBarometricPressureHpa = 0f
    private var cachedGyroZ = 0f
    @Volatile private var cachedDeviceHeadingDegrees: Float? = null
    private var cachedNavHasRoute = false
    private var cachedNavDistanceRemainingMeters = 0.0
    private var cachedNavDistanceToManeuverMeters = 0.0
    private var cachedNavManeuverType = ""
    private var cachedNavManeuverModifier = ""
    private var cachedNavManeuverInstruction = ""
    private var cachedNavOffRoute = false
    private var cachedMediaTitle = ""
    private var cachedMediaArtist = ""
    private var cachedMediaAlbum = ""
    @Volatile private var cachedMediaArtwork: Bitmap? = null
    private var cachedMediaPositionMs = 0L
    private var cachedMediaDurationMs = 0L
    private var cachedMediaIsPlaying = false
    private val sensorManager = applicationContext.getSystemService(SensorManager::class.java)
    private val rotationMatrix = FloatArray(9)
    private val remappedRotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_LINEAR_ACCELERATION -> {
                    cachedLinearAccelX = event.values[0]
                    cachedLinearAccelY = event.values[1]
                    cachedLinearAccelZ = event.values[2]
                    val mag = kotlin.math.sqrt(
                        event.values[0] * event.values[0] +
                        event.values[1] * event.values[1] +
                        event.values[2] * event.values[2]
                    )
                    if (mag > cachedLinearAccelPeak) cachedLinearAccelPeak = mag
                }
                Sensor.TYPE_PRESSURE -> {
                    cachedBarometricPressureHpa = event.values[0]
                }
                Sensor.TYPE_GYROSCOPE -> {
                    cachedGyroZ = event.values[2]
                }
                Sensor.TYPE_ROTATION_VECTOR,
                Sensor.TYPE_GAME_ROTATION_VECTOR -> updateDeviceHeading(event.values)
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    private var sensorsRegistered = false
    private var lastDeviceStatusRefreshMillis = Long.MIN_VALUE
    private var lastAnimationFrameNanos = System.nanoTime()
    // Start off-screen so the first dashboard frame is an intentional entrance
    // instead of an already-composed static layout.
    private var leftPanelProgress = 0f
    private var rightPanelProgress = 0f
    private var chromeProgress = 0f
    private var mapEntryProgress = 0f
    private var initialEntryAnimation = true
    @Volatile private var androidAutoTouchViewport: EmbeddedAndroidAutoTouchViewport? = null
    private var navigationEngine: NavigationEngine? = null
    private var navigationEngineRoute: NavRoute? = null
    private val voiceGuidance = VoiceGuidance(applicationContext)
    private val widgetCtx: WidgetDrawingContext = WidgetDrawingContext(
        fillPaint = fillPaint,
        strokePaint = strokePaint,
        bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        textPaint = textPaint,
        boldTypeface = boldTypeface,
        monoTypeface = monoTypeface,
        monoBoldTypeface = monoBoldTypeface,
        colors = WidgetColors(
            primary = COLOR_PRIMARY,
            primaryText = COLOR_PRIMARY_TEXT,
            text = COLOR_TEXT,
            muted = COLOR_MUTED,
            border = COLOR_BORDER,
            panel = COLOR_PANEL,
            background = COLOR_BACKGROUND,
            success = COLOR_SUCCESS,
            warning = COLOR_WARNING,
            route = COLOR_ROUTE
        ),
        resources = applicationContext.resources,
        packageName = applicationContext.packageName,
        units = MotoHubSettings.distanceUnits(applicationContext)
    )
    private var lastBearingFrameNanos = System.nanoTime()
    private var smoothedBearingDegrees = 0f
    private var rendererStartUptimeMs = 0L

    fun start() {
        check(running.compareAndSet(false, true)) { "Ride Dashboard renderer is already running" }
        leftPanelProgress = 0f
        rightPanelProgress = 0f
        chromeProgress = 0f
        mapEntryProgress = 0f
        initialEntryAnimation = true
        lastAnimationFrameNanos = System.nanoTime()
        when (mapSource) {
            RideDashboardMapSource.OPEN_STREET_MAP -> osmTiles.start()
            RideDashboardMapSource.MAPLIBRE -> mapLibreSnapshots.start()
            RideDashboardMapSource.ANDROID_AUTO -> Unit
        }
        // Register physical sensors
        sensorManager?.let { sm ->
            sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let { accel ->
                sm.registerListener(sensorListener, accel, SensorManager.SENSOR_DELAY_NORMAL)
            }
            sm.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let { baro ->
                sm.registerListener(sensorListener, baro, SensorManager.SENSOR_DELAY_NORMAL)
            }
            sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { gyro ->
                sm.registerListener(sensorListener, gyro, SensorManager.SENSOR_DELAY_NORMAL)
            }
            // A rotation vector gives a stable heading even when the motorcycle is
            // stationary, where GNSS course/bearing is normally absent. Prefer the
            // magnetic rotation vector; fall back to the game vector on devices that
            // do not expose magnetic fusion.
            val rotation = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
                ?: sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            rotation?.let {
                sm.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME)
            }
            sensorsRegistered = true
        }
        // The sweep clock starts on the first frame that can be presented.
        // A slow T-Box handshake must not consume the intro delay.
        rendererStartUptimeMs = 0L
        startupPuzzleTiles = emptyList()
        startupPuzzleCanvasWidth = 0
        startupPuzzleCanvasHeight = 0
        renderThread = Thread(::renderLoop, "MotoHubRideDashboard").also(Thread::start)
    }

    fun stop() {
        val wasRunning = running.getAndSet(false)
        if (wasRunning) renderThread?.interrupt()
        renderThread?.takeIf { it !== Thread.currentThread() }?.join(RENDER_JOIN_TIMEOUT_MILLIS)
        renderThread = null
        androidAutoTouchViewport = null
        osmTiles.stop()
        mapLibreSnapshots.stop()
        voiceGuidance.shutdown()
        // Unregister sensors
        if (sensorsRegistered) {
            sensorManager?.unregisterListener(sensorListener)
            sensorsRegistered = false
        }
        // Unregister the Now Playing media callback, if one was registered.
        lastMediaController?.unregisterCallback(mediaControllerCallback)
        lastMediaController = null
    }

    fun setFrameRateCap(value: Int) {
        frameRateCap = value.coerceIn(1, fps.coerceAtLeast(1))
    }

    /**
     * Applies a widget layout change picked in [io.motohub.android.feature.ridedashboard.widget.DashboardWidgetPickerScreen]
     * to the panel this renderer is currently streaming, without restarting the
     * dashboard session. Safe to call from any thread: [leftWidget]/[rightWidget] are
     * [Volatile] and only ever read at the start of a frame on the render thread, so a
     * swap here takes effect on the next frame at the latest.
     */
    fun updateWidgetLayout(left: DashboardWidget, right: DashboardWidget) {
        leftWidget = left
        rightWidget = right
    }

    fun mapTouchToAndroidAuto(canvasX: Int, canvasY: Int): Pair<Int, Int>? =
        androidAutoTouchViewport?.mapToSource(canvasX, canvasY)

    private fun renderLoop() {
        var nextFrameNanos = System.nanoTime()
        try {
            while (running.get()) {
                val frameDurationNanos = 1_000_000_000L / frameRateCap.coerceAtLeast(1)
                val remainingNanos = nextFrameNanos - System.nanoTime()
                if (remainingNanos > 0L) {
                    Thread.sleep(
                        remainingNanos / 1_000_000L,
                        (remainingNanos % 1_000_000L).toInt()
                    )
                }
                nextFrameNanos += frameDurationNanos
                if (nextFrameNanos < System.nanoTime() - frameDurationNanos) {
                    nextFrameNanos = System.nanoTime() + frameDurationNanos
                }

                var canvas: Canvas? = null
                try {
                    canvas = surface.lockCanvas(null)
                    drawDashboard(canvas, telemetryProvider.snapshot())
                } finally {
                    if (canvas != null) surface.unlockCanvasAndPost(canvas)
                }
            }
        } catch (_: InterruptedException) {
            // Normal renderer shutdown.
        } catch (failure: Throwable) {
            if (running.compareAndSet(true, false)) onFailure(failure)
        }
    }

    private fun drawDashboard(canvas: Canvas, rawSnapshot: RideTelemetrySnapshot) {
        val nowElapsed = SystemClock.elapsedRealtime()
        val freshFix = rawSnapshot.hasFreshFix(nowElapsed)
        // Startup sweep: after a 4 s pause, animate 0→180→0 KPH over 6 s so the
        // gauge comes alive smoothly on the TFT even before GPS acquires. The
        // synthetic value is deliberately kept out of the shared snapshot: map,
        // ETA, heading-up and every non-gauge widget must always see real telemetry.
        val uptimeMs = SystemClock.uptimeMillis()
        if (rendererStartUptimeMs == 0L) rendererStartUptimeMs = uptimeMs
        val sweepElapsed = uptimeMs - rendererStartUptimeMs
        val sweepPhase = sweepElapsed - SWEEP_START_DELAY_MS
        val snapshot = if (!freshFix) {
            rawSnapshot.copy(speedKph = 0f)
        } else {
            rawSnapshot
        }
        refreshDeviceStatus(nowElapsed)
        refreshNavigation()

        // Populate battery/cellular from the renderer's periodic refresh into the snapshot
        // so widgets can read them directly.
        val enrichedSnapshot = snapshot.copy(
            batteryLevel = cachedPhoneBattery,
            cellularStatus = cachedCellularStatus,
            batteryTemperatureCelsius = cachedBatteryTempCelsius,
            isCharging = cachedIsCharging,
            storageUsedPercent = cachedStorageUsedPercent,
            batteryVoltageMv = cachedBatteryVoltageMv,
            wifiRssiDbm = cachedWifiRssiDbm,
            deviceHeadingDegrees = cachedDeviceHeadingDegrees,
            linearAccelMagnitude =
                kotlin.math.sqrt(cachedLinearAccelX * cachedLinearAccelX + cachedLinearAccelY * cachedLinearAccelY + cachedLinearAccelZ * cachedLinearAccelZ),
            linearAccelPeak = cachedLinearAccelPeak,
            barometricPressureHpa = cachedBarometricPressureHpa,
            gyroZ = cachedGyroZ,
            // Navigation
            navHasRoute = cachedNavHasRoute,
            navDistanceRemainingMeters = cachedNavDistanceRemainingMeters,
            navDistanceToManeuverMeters = cachedNavDistanceToManeuverMeters,
            navManeuverType = cachedNavManeuverType,
            navManeuverModifier = cachedNavManeuverModifier,
            navManeuverInstruction = cachedNavManeuverInstruction,
            navOffRoute = cachedNavOffRoute,
            // Media / Now Playing
            mediaTitle = cachedMediaTitle,
            mediaArtist = cachedMediaArtist,
            mediaAlbum = cachedMediaAlbum,
            mediaArtwork = cachedMediaArtwork,
            mediaPositionMs = cachedMediaPositionMs,
            mediaDurationMs = cachedMediaDurationMs,
            mediaIsPlaying = cachedMediaIsPlaying
        )

        val startupSweep = startupSweepSpeed(sweepPhase)
        widgetCtx.startupSweepActive = startupSweep != null
        val gaugeSnapshot = startupSweep?.let { sweepSpeed ->
            enrichedSnapshot.copy(speedKph = sweepSpeed)
        } ?: enrichedSnapshot

        canvas.drawColor(COLOR_BACKGROUND)
        // Keep the first four seconds deliberately static. The TFT can take a
        // moment to initialise its video surface; sending the sweep immediately
        // made it easy for that animation to finish before the rider ever saw it.
        // A profile photo gives the rider useful, stable content during that
        // handshake, then the normal dashboard (and sweep) starts together.
        if (MotoHubSettings.rideDashboardStartupScreen(applicationContext) &&
            sweepElapsed < SWEEP_START_DELAY_MS
        ) {
            drawStartupProfile(canvas)
            return
        }
        // Do not advance the entrance state while the static puzzle is on
        // screen. The first dashboard frame must begin with panels and map at
        // their closed positions so the slide-in animation remains visible.
        updateLayoutAnimation(layoutController.snapshot())
        if (shouldUsePortraitRideDashboardLayout(canvas.width, canvas.height)) {
            drawPortraitDashboard(canvas, enrichedSnapshot, gaugeSnapshot, freshFix)
            return
        }
        val saveCount = canvas.save()
        if (preserveAspectRatio) {
            val scale = minOf(canvas.width / DESIGN_WIDTH, canvas.height / DESIGN_HEIGHT)
            canvas.translate(
                (canvas.width - DESIGN_WIDTH * scale) / 2f,
                (canvas.height - DESIGN_HEIGHT * scale) / 2f
            )
            canvas.scale(scale, scale)
        } else {
            canvas.scale(canvas.width / DESIGN_WIDTH, canvas.height / DESIGN_HEIGHT)
        }
        val bodyTop = HEADER_HEIGHT * chromeProgress
        val bodyBottom = DESIGN_HEIGHT - RAIL_HEIGHT * chromeProgress
        val leftPanelWidth = SPEED_PANEL_WIDTH * leftPanelProgress
        val rightPanelWidth = METRICS_PANEL_WIDTH * rightPanelProgress
        val mapLeft = leftPanelWidth
        val mapRight = DESIGN_WIDTH - rightPanelWidth

        drawAtmosphere(canvas, bodyTop, bodyBottom)
        drawAnimatedMapPanel(
            canvas = canvas,
            snapshot = enrichedSnapshot,
            freshFix = freshFix,
            left = mapLeft,
            right = mapRight,
            top = bodyTop,
            bottom = bodyBottom,
            chromeAmount = chromeProgress,
            entryProgress = mapEntryProgress
        )
        drawAnimatedWidgetPanel(canvas, enrichedSnapshot, gaugeSnapshot, freshFix, isLeft = true, bodyTop, bodyBottom)
        drawAnimatedWidgetPanel(canvas, enrichedSnapshot, gaugeSnapshot, freshFix, isLeft = false, bodyTop, bodyBottom)
        drawAnimatedHeader(canvas, enrichedSnapshot, freshFix)
        drawAnimatedTechnicalRail(canvas, enrichedSnapshot, freshFix)
        canvas.restoreToCount(saveCount)
    }

    private fun drawStartupProfile(canvas: Canvas) {
        val bitmap = startupProfileBitmap
        if (bitmap != null && bitmap.width > 0 && bitmap.height > 0 && canvas.width > 0 && canvas.height > 0) {
            if (startupPuzzleCanvasWidth != canvas.width || startupPuzzleCanvasHeight != canvas.height) {
                startupPuzzleTiles = createStartupPuzzleTiles(bitmap, canvas.width, canvas.height)
                startupPuzzleCanvasWidth = canvas.width
                startupPuzzleCanvasHeight = canvas.height
            }

            mapPaint.alpha = 255
            startupPuzzleTiles.forEach { tile ->
                drawAspectPreservingPuzzleTile(canvas, bitmap, tile)
            }
        }
        drawStartupBrand(canvas)
    }

    private fun drawStartupBrand(canvas: Canvas) {
        val width = canvas.width.toFloat()
        val height = canvas.height.toFloat()
        if (width <= 0f || height <= 0f) return

        val bandHeight = (height * STARTUP_BRAND_BAND_FRACTION).coerceAtLeast(64f)
        canvas.drawRect(0f, 0f, width, bandHeight, startupBrandBackdropPaint)

        val titleSize = (width * STARTUP_BRAND_TITLE_SCALE).coerceIn(28f, 64f)
        val centreX = width / 2f
        val titleBaseline = bandHeight * 0.86f
        val gaugeWidth = (titleSize * 2.2f).coerceAtLeast(82f)
        val gaugeHeight = titleSize * 1.10f
        val gaugeRect = RectF(
            centreX - gaugeWidth / 2f,
            titleBaseline - gaugeHeight,
            centreX + gaugeWidth / 2f,
            titleBaseline + titleSize * 0.12f
        )

        // Compact tachometer arc behind the wordmark: it remains legible on a
        // 800x480 TFT but gives the logo an unmistakable speed/power identity.
        startupBrandArcPaint.strokeWidth = (titleSize * 0.10f).coerceIn(2f, 5f)
        startupBrandArcPaint.alpha = 105
        canvas.drawArc(gaugeRect, 205f, 130f, false, startupBrandArcPaint)
        startupBrandArcPaint.strokeWidth = (titleSize * 0.045f).coerceIn(1.5f, 3f)
        startupBrandArcPaint.alpha = 255
        canvas.drawArc(gaugeRect, 214f, 105f, false, startupBrandArcPaint)

        val tickRadius = gaugeWidth * 0.46f
        val tickCentreY = titleBaseline - gaugeHeight * 0.04f
        for (index in 0..5) {
            val angle = Math.toRadians(214.0 + index * 21.0)
            val outerX = centreX + kotlin.math.cos(angle).toFloat() * tickRadius
            val outerY = tickCentreY + kotlin.math.sin(angle).toFloat() * tickRadius
            val innerRadius = tickRadius - titleSize * 0.13f
            val innerX = centreX + kotlin.math.cos(angle).toFloat() * innerRadius
            val innerY = tickCentreY + kotlin.math.sin(angle).toFloat() * innerRadius
            startupBrandArcPaint.strokeWidth = (titleSize * 0.035f).coerceIn(1.5f, 2.5f)
            canvas.drawLine(innerX, innerY, outerX, outerY, startupBrandArcPaint)
        }

        // A short needle adds motion without introducing another label.
        startupBrandArcPaint.strokeWidth = (titleSize * 0.06f).coerceIn(2f, 4f)
        val needleAngle = Math.toRadians(314.0)
        val needleLength = gaugeWidth * 0.34f
        canvas.drawLine(
            centreX,
            tickCentreY,
            centreX + kotlin.math.cos(needleAngle).toFloat() * needleLength,
            tickCentreY + kotlin.math.sin(needleAngle).toFloat() * needleLength,
            startupBrandArcPaint
        )
        startupBrandArcPaint.style = Paint.Style.FILL
        canvas.drawCircle(centreX, tickCentreY, (titleSize * 0.07f).coerceAtLeast(2f), startupBrandArcPaint)
        startupBrandArcPaint.style = Paint.Style.STROKE

        startupBrandPaint.textSize = titleSize
        startupBrandOutlinePaint.textSize = titleSize
        startupBrandOutlinePaint.strokeWidth = (titleSize * 0.13f).coerceIn(3f, 8f)
        startupBrandOutlinePaint.alpha = 245
        canvas.drawText("MOTO-HUB", centreX, titleBaseline, startupBrandOutlinePaint)
        startupBrandPaint.alpha = 255
        canvas.drawText("MOTO-HUB", centreX, titleBaseline, startupBrandPaint)
    }

    /**
     * Fills the destination cell without stretching its source piece. When the
     * two rectangles have different aspect ratios, crop the excess source area
     * around its centre (like a photo gallery's center-crop) instead of
     * distorting the motorcycle or the background.
     */
    private fun drawAspectPreservingPuzzleTile(
        canvas: Canvas,
        bitmap: Bitmap,
        tile: StartupPuzzleTile
    ) {
        val sourceWidth = tile.source.width().toFloat()
        val sourceHeight = tile.source.height().toFloat()
        val destinationWidth = tile.destination.width()
        val destinationHeight = tile.destination.height()
        if (sourceWidth <= 0f || sourceHeight <= 0f || destinationWidth <= 0f || destinationHeight <= 0f) {
            return
        }

        val sourceAspect = sourceWidth / sourceHeight
        val destinationAspect = destinationWidth / destinationHeight
        val croppedSource = if (sourceAspect > destinationAspect) {
            val visibleWidth = sourceHeight * destinationAspect
            val left = tile.source.left + (sourceWidth - visibleWidth) / 2f
            Rect(
                left.roundToInt(),
                tile.source.top,
                (left + visibleWidth).roundToInt(),
                tile.source.bottom
            )
        } else {
            val visibleHeight = sourceWidth / destinationAspect
            val top = tile.source.top + (sourceHeight - visibleHeight) / 2f
            Rect(
                tile.source.left,
                top.roundToInt(),
                tile.source.right,
                (top + visibleHeight).roundToInt()
            )
        }
        canvas.drawBitmap(bitmap, croppedSource, tile.destination, mapPaint)
    }

    /**
     * Splits the image into a variable-size grid, then shuffles the source
     * pieces into another variable-size grid covering the complete TFT. This
     * creates a true rectangular photo puzzle: no black gaps, no uncovered
     * pixels, and a different composition each time the dashboard starts.
     */
    private fun createStartupPuzzleTiles(
        bitmap: Bitmap,
        canvasWidth: Int,
        canvasHeight: Int
    ): List<StartupPuzzleTile> {
        val random = Random(System.nanoTime())
        val columns = random.nextInt(4, 7)
        val rows = random.nextInt(3, 5)
        val sourceX = variableBoundaries(columns, bitmap.width.toFloat(), random)
        val sourceY = variableBoundaries(rows, bitmap.height.toFloat(), random)
        val destinationX = variableBoundaries(columns, canvasWidth.toFloat(), random)
        val destinationY = variableBoundaries(rows, canvasHeight.toFloat(), random)
        val sourceOrder = (0 until columns * rows).shuffled(random)

        return buildList(columns * rows) {
            var destinationIndex = 0
            for (row in 0 until rows) {
                for (column in 0 until columns) {
                    val sourceIndex = sourceOrder[destinationIndex]
                    val sourceRow = sourceIndex / columns
                    val sourceColumn = sourceIndex % columns
                    add(
                        StartupPuzzleTile(
                            source = Rect(
                                sourceX[sourceColumn].roundToInt(),
                                sourceY[sourceRow].roundToInt(),
                                sourceX[sourceColumn + 1].roundToInt(),
                                sourceY[sourceRow + 1].roundToInt()
                            ),
                            destination = RectF(
                                destinationX[column],
                                destinationY[row],
                                destinationX[column + 1],
                                destinationY[row + 1]
                            )
                        )
                    )
                    destinationIndex += 1
                }
            }
        }
    }

    private fun variableBoundaries(parts: Int, total: Float, random: Random): FloatArray {
        val weights = FloatArray(parts) { random.nextFloat() * 0.75f + 0.65f }
        val totalWeight = weights.sum()
        val boundaries = FloatArray(parts + 1)
        var accumulated = 0f
        boundaries[0] = 0f
        for (index in weights.indices) {
            accumulated += weights[index] / totalWeight
            boundaries[index + 1] = if (index == weights.lastIndex) total else total * accumulated
        }
        return boundaries
    }

    private fun loadStartupProfileBitmap(path: String?): Bitmap? {
        val photoPath = path?.trim().orEmpty()
        if (photoPath.isEmpty()) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(photoPath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            val sample = calculateInSampleSize(
                width = bounds.outWidth,
                height = bounds.outHeight,
                maxDimension = STARTUP_PROFILE_MAX_DECODE_SIZE
            )
            BitmapFactory.decodeFile(
                photoPath,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            )
        }.getOrNull()
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        while (width / sample > maxDimension || height / sample > maxDimension) {
            sample *= 2
        }
        return sample
    }

    private fun drawPortraitDashboard(
        canvas: Canvas,
        snapshot: RideTelemetrySnapshot,
        gaugeSnapshot: RideTelemetrySnapshot,
        freshFix: Boolean
    ) {
        val viewportHeight = DESIGN_WIDTH * canvas.height / canvas.width
        val saveCount = canvas.save()
        canvas.scale(canvas.width / DESIGN_WIDTH, canvas.width / DESIGN_WIDTH)
        val headerBottom = HEADER_HEIGHT * chromeProgress
        val railHeight = RAIL_HEIGHT * chromeProgress
        val railTop = viewportHeight - railHeight
        val lowerPanelProgress = max(leftPanelProgress, rightPanelProgress)
        val lowerPanelHeight = PORTRAIT_PANEL_HEIGHT * lowerPanelProgress
        val lowerPanelTop = if (lowerPanelHeight <= ANIMATION_SNAP_THRESHOLD) {
            railTop
        } else {
            (railTop - lowerPanelHeight).coerceAtLeast(headerBottom + 420f)
        }
        val mapTop = headerBottom
        val mapBottom = lowerPanelTop

        if (mapSource == RideDashboardMapSource.ANDROID_AUTO) {
            drawPortraitAndroidAutoDashboard(
                canvas = canvas,
                snapshot = snapshot,
                gaugeSnapshot = gaugeSnapshot,
                freshFix = freshFix,
                viewportHeight = viewportHeight,
                headerBottom = headerBottom,
                railTop = railTop
            )
            canvas.restoreToCount(saveCount)
            return
        }

        drawAtmosphereRegion(canvas, 0f, headerBottom, DESIGN_WIDTH, railTop)
        drawAnimatedMapPanel(
            canvas = canvas,
            snapshot = snapshot,
            freshFix = freshFix,
            left = 0f,
            right = DESIGN_WIDTH,
            top = mapTop,
            bottom = mapBottom,
            chromeAmount = chromeProgress,
            entryProgress = mapEntryProgress
        )
        if (lowerPanelHeight > ANIMATION_SNAP_THRESHOLD) {
            val speedRegionRight = if (rightPanelProgress > ANIMATION_SNAP_THRESHOLD) {
                DESIGN_WIDTH / 2f
            } else {
                DESIGN_WIDTH
            }
            if (leftPanelProgress > ANIMATION_SNAP_THRESHOLD) {
                drawPanelInRegion(
                    canvas = canvas,
                    left = 0f,
                    top = lowerPanelTop,
                    right = speedRegionRight,
                    bottom = railTop,
                    sourceLeft = 0f,
                    sourceTop = HEADER_HEIGHT,
                    sourceRight = SPEED_PANEL_WIDTH,
                    sourceBottom = DESIGN_HEIGHT - RAIL_HEIGHT
                ) {
                    leftWidget.draw(canvas,
                        android.graphics.RectF(0f, HEADER_HEIGHT, SPEED_PANEL_WIDTH, DESIGN_HEIGHT - RAIL_HEIGHT),
                        snapshotForWidget(leftWidget, snapshot, gaugeSnapshot), freshFix, isLeftPanel = true, widgetCtx)
                }
            }
            if (rightPanelProgress > ANIMATION_SNAP_THRESHOLD) {
                drawPanelInRegion(
                    canvas = canvas,
                    left = DESIGN_WIDTH / 2f,
                    top = lowerPanelTop,
                    right = DESIGN_WIDTH,
                    bottom = railTop,
                    sourceLeft = METRICS_PANEL_LEFT,
                    sourceTop = HEADER_HEIGHT,
                    sourceRight = DESIGN_WIDTH,
                    sourceBottom = DESIGN_HEIGHT - RAIL_HEIGHT
                ) {
                    rightWidget.draw(canvas,
                        android.graphics.RectF(METRICS_PANEL_LEFT, HEADER_HEIGHT, DESIGN_WIDTH, DESIGN_HEIGHT - RAIL_HEIGHT),
                        snapshotForWidget(rightWidget, snapshot, gaugeSnapshot), freshFix, isLeftPanel = false, widgetCtx)
                }
            }
        }
        if (chromeProgress > ANIMATION_SNAP_THRESHOLD) {
            drawHeader(canvas, snapshot, freshFix)
            drawTechnicalRail(canvas, snapshot, freshFix, top = railTop, bottom = viewportHeight)
        }
        canvas.restoreToCount(saveCount)
    }

    private fun drawPortraitAndroidAutoDashboard(
        canvas: Canvas,
        snapshot: RideTelemetrySnapshot,
        gaugeSnapshot: RideTelemetrySnapshot,
        freshFix: Boolean,
        viewportHeight: Float,
        headerBottom: Float,
        railTop: Float
    ) {
        val leftVisibleTop = railTop - PORTRAIT_PANEL_HEIGHT * leftPanelProgress
        val rightVisibleTop = railTop - PORTRAIT_PANEL_HEIGHT * rightPanelProgress
        val exclusions = buildList {
            if (leftPanelProgress > ANIMATION_SNAP_THRESHOLD) {
                add(RectF(0f, leftVisibleTop, DESIGN_WIDTH / 2f, railTop))
            }
            if (rightPanelProgress > ANIMATION_SNAP_THRESHOLD) {
                add(RectF(DESIGN_WIDTH / 2f, rightVisibleTop, DESIGN_WIDTH, railTop))
            }
        }

        val mapSaveCount = canvas.save()
        val mapOffset = (railTop - headerBottom) * (1f - mapEntryProgress)
        canvas.clipRect(0f, headerBottom + mapOffset, DESIGN_WIDTH, railTop)
        canvas.translate(0f, mapOffset)
        drawEmbeddedAndroidAuto(
            canvas = canvas,
            left = 0f,
            top = headerBottom,
            right = DESIGN_WIDTH,
            bottom = railTop,
            alignFillToTop = true,
            touchExclusions = exclusions
        )
        canvas.restoreToCount(mapSaveCount)
        drawPortraitPanelOverlay(
            canvas = canvas,
            left = 0f,
            right = DESIGN_WIDTH / 2f,
            railTop = railTop,
            progress = leftPanelProgress,
            sourceLeft = 0f,
            sourceRight = SPEED_PANEL_WIDTH
        ) {
            leftWidget.draw(canvas,
                android.graphics.RectF(0f, HEADER_HEIGHT, SPEED_PANEL_WIDTH, DESIGN_HEIGHT - RAIL_HEIGHT),
                snapshotForWidget(leftWidget, snapshot, gaugeSnapshot), freshFix, isLeftPanel = true, widgetCtx)
        }
        drawPortraitPanelOverlay(
            canvas = canvas,
            left = DESIGN_WIDTH / 2f,
            right = DESIGN_WIDTH,
            railTop = railTop,
            progress = rightPanelProgress,
            sourceLeft = METRICS_PANEL_LEFT,
            sourceRight = DESIGN_WIDTH
        ) {
            rightWidget.draw(canvas,
                android.graphics.RectF(METRICS_PANEL_LEFT, HEADER_HEIGHT, DESIGN_WIDTH, DESIGN_HEIGHT - RAIL_HEIGHT),
                snapshotForWidget(rightWidget, snapshot, gaugeSnapshot), freshFix, isLeftPanel = false, widgetCtx)
        }
        drawPortraitChrome(canvas, snapshot, freshFix, viewportHeight, railTop)
    }

    private fun drawPortraitPanelOverlay(
        canvas: Canvas,
        left: Float,
        right: Float,
        railTop: Float,
        progress: Float,
        sourceLeft: Float,
        sourceRight: Float,
        content: () -> Unit
    ) {
        if (progress <= ANIMATION_SNAP_THRESHOLD) return
        val fullTop = railTop - PORTRAIT_PANEL_HEIGHT
        val offset = PORTRAIT_PANEL_HEIGHT * (1f - progress)
        val saveCount = canvas.save()
        canvas.clipRect(left, fullTop + offset, right, railTop)
        canvas.translate(0f, offset)
        drawAtmosphereRegion(canvas, left, fullTop, right, railTop)
        drawPanelInRegion(
            canvas = canvas,
            left = left,
            top = fullTop,
            right = right,
            bottom = railTop,
            sourceLeft = sourceLeft,
            sourceTop = HEADER_HEIGHT,
            sourceRight = sourceRight,
            sourceBottom = DESIGN_HEIGHT - RAIL_HEIGHT,
            content = content
        )
        canvas.restoreToCount(saveCount)
    }

    private fun drawPortraitChrome(
        canvas: Canvas,
        snapshot: RideTelemetrySnapshot,
        freshFix: Boolean,
        viewportHeight: Float,
        railTop: Float
    ) {
        if (chromeProgress <= ANIMATION_SNAP_THRESHOLD) return
        val headerSaveCount = canvas.save()
        canvas.clipRect(0f, 0f, DESIGN_WIDTH, HEADER_HEIGHT * chromeProgress)
        canvas.translate(0f, -HEADER_HEIGHT * (1f - chromeProgress))
        drawHeader(canvas, snapshot, freshFix)
        canvas.restoreToCount(headerSaveCount)

        val railSaveCount = canvas.save()
        canvas.clipRect(0f, railTop, DESIGN_WIDTH, viewportHeight)
        canvas.translate(0f, RAIL_HEIGHT * (1f - chromeProgress))
        drawTechnicalRail(
            canvas,
            snapshot,
            freshFix,
            top = viewportHeight - RAIL_HEIGHT,
            bottom = viewportHeight
        )
        canvas.restoreToCount(railSaveCount)
    }

    private fun drawPanelInRegion(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        sourceLeft: Float,
        sourceTop: Float,
        sourceRight: Float,
        sourceBottom: Float,
        content: () -> Unit
    ) {
        val targetWidth = right - left
        val targetHeight = bottom - top
        val sourceWidth = sourceRight - sourceLeft
        val sourceHeight = sourceBottom - sourceTop
        val scale = minOf(targetWidth / sourceWidth, targetHeight / sourceHeight)
        val saveCount = canvas.save()
        canvas.clipRect(left, top, right, bottom)
        canvas.translate(
            left + (targetWidth - sourceWidth * scale) / 2f,
            top + (targetHeight - sourceHeight * scale) / 2f
        )
        canvas.scale(scale, scale)
        canvas.translate(-sourceLeft, -sourceTop)
        content()
        canvas.restoreToCount(saveCount)
    }

    /** Slides the map upward from below the dashboard during the initial entry. */
    private fun drawAnimatedMapPanel(
        canvas: Canvas,
        snapshot: RideTelemetrySnapshot,
        freshFix: Boolean,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        chromeAmount: Float,
        entryProgress: Float
    ) {
        if (entryProgress <= ANIMATION_SNAP_THRESHOLD) return
        val saveCount = canvas.save()
        val offset = (bottom - top) * (1f - entryProgress)
        canvas.clipRect(left, top + offset, right, bottom)
        canvas.translate(0f, offset)
        drawMapPanel(
            canvas = canvas,
            snapshot = snapshot,
            freshFix = freshFix,
            left = left,
            right = right,
            top = top,
            bottom = bottom,
            chromeAmount = chromeAmount
        )
        canvas.restoreToCount(saveCount)
    }

    private fun drawAtmosphereRegion(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        fillPaint.color = COLOR_PANEL
        canvas.drawRect(left, top, right, bottom, fillPaint)
        strokePaint.color = COLOR_GRID
        strokePaint.strokeWidth = 1f
        var x = left
        while (x <= right) {
            canvas.drawLine(x, top, x, bottom, strokePaint)
            x += 32f
        }
        var y = top
        while (y <= bottom) {
            canvas.drawLine(left, y, right, y, strokePaint)
            y += 32f
        }
        strokePaint.color = COLOR_BORDER
        canvas.drawLine(left, top, right, top, strokePaint)
        canvas.drawLine(left, bottom, right, bottom, strokePaint)
    }

    private fun updateLayoutAnimation(layout: RideDashboardLayoutSnapshot) {
        val now = System.nanoTime()
        val elapsedSeconds = ((now - lastAnimationFrameNanos).coerceAtLeast(0L) / 1_000_000_000.0)
            .coerceAtMost(MAX_ANIMATION_STEP_SECONDS)
        lastAnimationFrameNanos = now
        val response = if (initialEntryAnimation) INITIAL_ENTRY_RESPONSE else ANIMATION_RESPONSE
        val blend = (1.0 - exp(-response * elapsedSeconds)).toFloat()
        leftPanelProgress = animateTowards(
            leftPanelProgress,
            if (layout.leftPanelVisible) 1f else 0f,
            blend
        )
        rightPanelProgress = animateTowards(
            rightPanelProgress,
            if (layout.rightPanelVisible) 1f else 0f,
            blend
        )
        chromeProgress = animateTowards(
            chromeProgress,
            if (layout.chromeVisible) 1f else 0f,
            blend
        )
        mapEntryProgress = animateTowards(mapEntryProgress, 1f, blend)
        if (initialEntryAnimation &&
            mapEntryProgress >= 1f &&
            (layout.leftPanelVisible.not() || leftPanelProgress >= 1f) &&
            (layout.rightPanelVisible.not() || rightPanelProgress >= 1f) &&
            (layout.chromeVisible.not() || chromeProgress >= 1f)
        ) {
            initialEntryAnimation = false
        }
    }

    private fun animateTowards(current: Float, target: Float, blend: Float): Float {
        val next = current + (target - current) * blend
        return if (abs(target - next) < ANIMATION_SNAP_THRESHOLD) target else next
    }

    /**
     * Converts the phone rotation-vector sensor into a compass heading for the device top edge.
     * The display remap is important on the forced-landscape phone/T-Box setup: without it the
     * same physical phone orientation appears 90 degrees off when the Android surface rotates.
     */
    private fun updateDeviceHeading(rotationVector: FloatArray) {
        if (rotationVector.isEmpty()) return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVector)
        val displayRotation = applicationContext
            .getSystemService(WindowManager::class.java)
            ?.defaultDisplay
            ?.rotation
            ?: Surface.ROTATION_0
        val remapped = when (displayRotation) {
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(
                rotationMatrix,
                SensorManager.AXIS_Y,
                SensorManager.AXIS_MINUS_X,
                remappedRotationMatrix
            )
            Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(
                rotationMatrix,
                SensorManager.AXIS_MINUS_X,
                SensorManager.AXIS_MINUS_Y,
                remappedRotationMatrix
            )
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(
                rotationMatrix,
                SensorManager.AXIS_MINUS_Y,
                SensorManager.AXIS_X,
                remappedRotationMatrix
            )
            else -> false
        }
        val matrix = if (remapped) remappedRotationMatrix else rotationMatrix
        SensorManager.getOrientation(matrix, orientationAngles)
        val heading = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
        if (heading.isFinite()) {
            cachedDeviceHeadingDegrees = ((heading % 360f) + 360f) % 360f
        }
    }

    /**
     * Smooths raw GPS bearing toward a target heading, same exponential
     * response as [animateTowards] but wraparound-aware (350deg -> 10deg
     * animates the short 20deg way, not the long 340deg way) since bearing is
     * a compass angle, not a linear value. Below [MIN_HEADING_UP_SPEED_KPH]
     * raw GPS bearing is mostly noise (course-over-ground needs actual
     * movement to be meaningful), so the target freezes at the last smoothed
     * value instead of chasing that noise - this is what was making heading-up
     * rotation flicker/judder at low speed or standstill.
     */
    private fun smoothBearingTowards(rawBearingDegrees: Float?, speedKph: Float): Float {
        val now = System.nanoTime()
        val elapsedSeconds = ((now - lastBearingFrameNanos).coerceAtLeast(0L) / 1_000_000_000.0)
            .coerceAtMost(MAX_ANIMATION_STEP_SECONDS)
        lastBearingFrameNanos = now
        val blend = (1.0 - exp(-BEARING_ANIMATION_RESPONSE * elapsedSeconds)).toFloat()

        val target = if (speedKph >= MIN_HEADING_UP_SPEED_KPH && rawBearingDegrees != null) {
            rawBearingDegrees
        } else {
            smoothedBearingDegrees
        }

        val delta = shortestAngleDeltaDegrees(smoothedBearingDegrees, target)
        val next = if (abs(delta) < BEARING_SNAP_THRESHOLD_DEGREES) target else smoothedBearingDegrees + delta * blend
        smoothedBearingDegrees = ((next % 360f) + 360f) % 360f
        return smoothedBearingDegrees
    }

    private fun drawAtmosphere(canvas: Canvas, top: Float, bottom: Float) {
        fillPaint.color = COLOR_PANEL
        canvas.drawRect(0f, top, DESIGN_WIDTH, bottom, fillPaint)
        strokePaint.color = COLOR_GRID
        strokePaint.strokeWidth = 1f
        var x = 0f
        while (x <= DESIGN_WIDTH) {
            canvas.drawLine(x, top, x, bottom, strokePaint)
            x += 32f
        }
        var y = top
        while (y <= bottom) {
            canvas.drawLine(0f, y, DESIGN_WIDTH, y, strokePaint)
            y += 32f
        }
        strokePaint.color = COLOR_BORDER
        canvas.drawLine(0f, top, DESIGN_WIDTH, top, strokePaint)
        canvas.drawLine(0f, bottom, DESIGN_WIDTH, bottom, strokePaint)
    }

    private fun drawAnimatedHeader(
        canvas: Canvas,
        snapshot: RideTelemetrySnapshot,
        freshFix: Boolean
    ) {
        if (chromeProgress <= ANIMATION_SNAP_THRESHOLD) return
        val saveCount = canvas.save()
        canvas.clipRect(0f, 0f, DESIGN_WIDTH, HEADER_HEIGHT * chromeProgress)
        canvas.translate(0f, -HEADER_HEIGHT * (1f - chromeProgress))
        drawHeader(canvas, snapshot, freshFix)
        canvas.restoreToCount(saveCount)
    }

    private fun drawAnimatedTechnicalRail(
        canvas: Canvas,
        snapshot: RideTelemetrySnapshot,
        freshFix: Boolean
    ) {
        if (chromeProgress <= ANIMATION_SNAP_THRESHOLD) return
        val saveCount = canvas.save()
        canvas.clipRect(
            0f,
            DESIGN_HEIGHT - RAIL_HEIGHT * chromeProgress,
            DESIGN_WIDTH,
            DESIGN_HEIGHT
        )
        canvas.translate(0f, RAIL_HEIGHT * (1f - chromeProgress))
        drawTechnicalRail(canvas, snapshot, freshFix)
        canvas.restoreToCount(saveCount)
    }

    private fun drawHeader(canvas: Canvas, snapshot: RideTelemetrySnapshot, freshFix: Boolean) {
        fillPaint.color = COLOR_BACKGROUND
        canvas.drawRect(0f, 0f, DESIGN_WIDTH, HEADER_HEIGHT, fillPaint)
        fillPaint.color = COLOR_PRIMARY
        canvas.drawPath(
            Path().apply {
                moveTo(18f, 12f)
                lineTo(46f, 12f)
                lineTo(42f, 42f)
                lineTo(14f, 42f)
                close()
            },
            fillPaint
        )
        text(
            canvas,
            "M",
            30f,
            34f,
            18f,
            COLOR_PRIMARY_TEXT,
            Paint.Align.CENTER,
            monoBoldTypeface
        )
        text(canvas, "MOTO//HUB", 56f, 26f, 18f, COLOR_TEXT, typeface = boldTypeface)
        text(canvas, "RIDE / LIVE", 56f, 42f, 11f, COLOR_MUTED, typeface = monoTypeface)

        statusDot(canvas, 372f, 27f, freshFix)
        val locationStatus = when {
            freshFix && snapshot.satellitesUsed > 0 -> "GPS LOCK"
            freshFix -> "LOC FIX"
            else -> "GPS SEARCH"
        }
        text(
            canvas,
            locationStatus,
            384f,
            32f,
            12f,
            if (freshFix) COLOR_TEXT else COLOR_WARNING,
            typeface = monoBoldTypeface
        )
        text(
            canvas,
            "${snapshot.satellitesUsed}/${snapshot.satellitesVisible} SAT",
            478f,
            32f,
            12f,
            COLOR_MUTED,
            typeface = monoTypeface
        )
        text(canvas, "TBOX ${fps} FPS", 578f, 32f, 12f, COLOR_MUTED, typeface = monoTypeface)
        text(
            canvas,
            LocalTime.now().format(TIME_FORMATTER),
            782f,
            34f,
            17f,
            COLOR_TEXT,
            Paint.Align.RIGHT,
            monoBoldTypeface
        )
    }

    private fun drawAnimatedWidgetPanel(
        canvas: Canvas,
        snapshot: RideTelemetrySnapshot,
        gaugeSnapshot: RideTelemetrySnapshot,
        freshFix: Boolean,
        isLeft: Boolean,
        bodyTop: Float,
        bodyBottom: Float
    ) {
        val progress = if (isLeft) leftPanelProgress else rightPanelProgress
        if (progress <= ANIMATION_SNAP_THRESHOLD) return

        val widget = if (isLeft) leftWidget else rightWidget
        val panelWidth = if (isLeft) SPEED_PANEL_WIDTH else METRICS_PANEL_WIDTH
        val visibleWidth = panelWidth * progress

        val saveCount = canvas.save()
        if (isLeft) {
            canvas.clipRect(0f, bodyTop, visibleWidth, bodyBottom)
            canvas.translate(-panelWidth * (1f - leftPanelProgress), 0f)
        } else {
            canvas.translate(METRICS_PANEL_WIDTH * (1f - rightPanelProgress), 0f)
            canvas.clipRect(DESIGN_WIDTH - visibleWidth, bodyTop, DESIGN_WIDTH, bodyBottom)
        }
        val bounds = android.graphics.RectF(
            if (isLeft) 0f else DESIGN_WIDTH - panelWidth,
            bodyTop,
            if (isLeft) panelWidth else DESIGN_WIDTH,
            bodyBottom
        )
        widget.draw(canvas, bounds, snapshotForWidget(widget, snapshot, gaugeSnapshot), freshFix, isLeft, widgetCtx)
        canvas.restoreToCount(saveCount)
    }

    private fun snapshotForWidget(
        widget: DashboardWidget,
        snapshot: RideTelemetrySnapshot,
        gaugeSnapshot: RideTelemetrySnapshot
    ): RideTelemetrySnapshot = if (widget.id == DashboardWidgetIDs.SPEED_GAUGE) gaugeSnapshot else snapshot

    // drawSpeedPanel() removed — replaced by SpeedGaugeWidget via the widget system
    // drawMetricsPanel() removed — replaced by TripMetricsWidget via the widget system

    private fun drawMapPanel(
        canvas: Canvas,
        snapshot: RideTelemetrySnapshot,
        freshFix: Boolean,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        chromeAmount: Float
    ) {
        strokePaint.color = COLOR_BORDER
        strokePaint.strokeWidth = 1f
        if (left > 0f) canvas.drawLine(left, top, left, bottom, strokePaint)
        if (right < DESIGN_WIDTH) canvas.drawLine(right, top, right, bottom, strokePaint)

        val isEmbeddedAndroidAuto = mapSource == RideDashboardMapSource.ANDROID_AUTO
        val headingUpLabel = if (NavigationRuntime.route.value != null && freshFix) "HEADING UP" else "NORTH UP"
        // OSM used to reserve a 38 px header strip plus 16 px borders inside
        // the map panel. Keep only a hairline inset: the mode badge now floats
        // over the map, so tiles can use practically the entire panel height.
        val mapInset = if (isEmbeddedAndroidAuto) 0f else MAP_INSET * chromeAmount
        val mapLeft = left + mapInset
        val mapTop = top + mapInset
        val mapRight = right - mapInset
        val mapBottom = bottom - mapInset
        val centerX = (mapLeft + mapRight) / 2f
        val centerY = (mapTop + mapBottom) / 2f + 5f

        val mapSaveCount = canvas.save()
        canvas.clipRect(mapLeft, mapTop, mapRight, mapBottom)
        if (isEmbeddedAndroidAuto) {
            drawEmbeddedAndroidAuto(
                canvas = canvas,
                left = mapLeft,
                top = mapTop,
                right = mapRight,
                bottom = mapBottom,
            )
            canvas.restoreToCount(mapSaveCount)
            return
        }
        androidAutoTouchViewport = null
        val trackOverlayEnabled = RideDashboardTrackOverlayRuntime.isEnabled()
        val loadedTrip = RideDashboardTrackOverlayRuntime.loadedTrip.value
        val recordingState = TripRecordingRuntime.state.value as? TripRecordingState.Recording
        val track = loadedTrip?.points
            ?: if (trackOverlayEnabled) TripRecordingRuntime.track.value else emptyList()
        val showingSavedTrip = loadedTrip != null
        val followSavedTripWithGps = RideDashboardTrackOverlayRuntime.followLiveGps.value
        // A historical trip is independent from the rider's current GPS fix:
        // anchor the map on the saved trace so loading a trip recorded elsewhere
        // still produces a visible result instead of drawing it off-screen.
        val current = if (showingSavedTrip && !followSavedTripWithGps) null else snapshot.position
        // Smoothed and speed-gated: raw GPS bearing is noisy at low speed and
        // jumps discretely between fixes, which made the whole map judder and
        // flicker when rotated with it directly.
        val bearing = smoothBearingTowards(snapshot.bearingDegrees, snapshot.speedKph)
        val savedTrackBounds = if (showingSavedTrip && !followSavedTripWithGps) savedTrackBounds(track) else null
        val centerLatitude = savedTrackBounds?.centerLatitude
            ?: current?.latitude
            ?: track.lastOrNull()?.latitude
        val centerLongitude = savedTrackBounds?.centerLongitude
            ?: current?.longitude
            ?: track.lastOrNull()?.longitude
        val trackHasLiveFix = !showingSavedTrip && recordingState?.hasFix == true && track.isNotEmpty()
        val zoom = savedTrackBounds?.let {
            zoomForSavedTrack(it, mapRight - mapLeft, mapBottom - mapTop)
        } ?: zoomForSpeed(snapshot.speedKph, OSM_ZOOM)
        // Heading-up + look-ahead camera only while actively navigating with a
        // fresh fix - browsing the dashboard map otherwise stays north-up,
        // matching the "NORTH UP" label shown when idle.
        val headingUpActive = NavigationRuntime.route.value != null && freshFix
        if (mapSource == RideDashboardMapSource.MAPLIBRE) {
            drawMapLibrePanel(
                canvas = canvas,
                mapLeft = mapLeft,
                mapTop = mapTop,
                mapRight = mapRight,
                mapBottom = mapBottom,
                centerLatitude = centerLatitude,
                centerLongitude = centerLongitude,
                zoom = zoom,
                bearing = bearing,
                tiltDegrees = if (headingUpActive) MAPLIBRE_NAVIGATION_TILT_DEGREES else 0f,
                headingUpActive = headingUpActive,
                track = track,
                current = current,
                freshFix = freshFix,
                trackHasLiveFix = trackHasLiveFix
            )
            canvas.restoreToCount(mapSaveCount)
            drawMapLibreAttribution(canvas, mapRight, mapBottom)
            return
        }
        val anchorX = centerX
        val anchorY = if (headingUpActive) {
            mapTop + (mapBottom - mapTop) * LOOK_AHEAD_ANCHOR_FRACTION
        } else {
            centerY
        }
        val rotationDegrees = if (headingUpActive) -bearing else 0f
        if (centerLatitude != null && centerLongitude != null) {
            val worldCenter = osmWorldPixel(centerLatitude, centerLongitude, zoom)
            val rotationSaveCount = canvas.save()
            if (headingUpActive) canvas.rotate(rotationDegrees, anchorX, anchorY)
            val osmMapScale = osmSettings.labelScale.factor
            if (osmMapScale != 1f) canvas.scale(osmMapScale, osmMapScale, anchorX, anchorY)
            // A rotated rectangular viewport needs tiles out to its farthest
            // corner from the anchor in every direction, not just half the
            // panel's width/height (which is only correct when unrotated and
            // anchor == geometric center).
            val halfWidth: Double
            val halfHeight: Double
            if (headingUpActive) {
                val corners = listOf(
                    mapLeft to mapTop, mapRight to mapTop,
                    mapLeft to mapBottom, mapRight to mapBottom
                )
                val maxCornerDistance = corners.maxOf { (x, y) ->
                    kotlin.math.hypot((x - anchorX).toDouble(), (y - anchorY).toDouble())
                }
                halfWidth = maxCornerDistance
                halfHeight = maxCornerDistance
            } else {
                halfWidth = (mapRight - mapLeft) / 2.0
                halfHeight = (mapBottom - mapTop) / 2.0
            }
            val firstTileX = floor((worldCenter.x - halfWidth) / OSM_TILE_SIZE).toInt()
            val lastTileX = floor((worldCenter.x + halfWidth) / OSM_TILE_SIZE).toInt()
            val firstTileY = floor((worldCenter.y - halfHeight) / OSM_TILE_SIZE).toInt()
            val lastTileY = floor((worldCenter.y + halfHeight) / OSM_TILE_SIZE).toInt()
            var loadedTiles = 0
            // Perspective tilt during heading-up navigation: compress Y so the
            // top of the map recedes into the distance, giving a Google Maps
            // navigation feel. The bike anchor stays fixed at the bottom. Applied
            // before the fill rect below, not after - otherwise the fill (drawn at
            // full, unscaled size) stayed put while the tiles drawn afterward got
            // compressed away from the top, leaving the fill's flat light-gray color
            // visible as a band above the tilted map instead of moving with it.
            val perspectiveActive = headingUpActive
            if (perspectiveActive) {
                canvas.scale(1f, PERSPECTIVE_TILT_SCALE, anchorX, anchorY)
            }
            // Fill first to hide any sub-pixel seams between tiles, and to cover any tile
            // that isn't loaded yet (see COLOR_MAP_LOADING) instead of leaving a gap.
            fillPaint.color = COLOR_MAP_LOADING
            canvas.drawRect(mapLeft, mapTop, mapRight, mapBottom, fillPaint)
            for (tileY in firstTileY..lastTileY) {
                for (tileX in firstTileX..lastTileX) {
                    val tile = osmTiles.tile(zoom, tileX, tileY) ?: continue
                    val drawX = anchorX + (tileX * OSM_TILE_SIZE - worldCenter.x).toFloat()
                    val drawY = anchorY + (tileY * OSM_TILE_SIZE - worldCenter.y).toFloat()
                    canvas.drawBitmap(tile, drawX.roundToInt().toFloat(), drawY.roundToInt().toFloat(), mapPaint)
                    loadedTiles++
                }
            }
            if (track.size > 1) {
                val path = Path()
                track.forEachIndexed { index, point ->
                    val worldPoint = osmWorldPixel(point.latitude, point.longitude, zoom)
                    val x = anchorX + (worldPoint.x - worldCenter.x).toFloat()
                    val y = anchorY + (worldPoint.y - worldCenter.y).toFloat()
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                strokePaint.style = Paint.Style.STROKE
                strokePaint.strokeJoin = Paint.Join.ROUND
                strokePaint.strokeCap = Paint.Cap.ROUND
                strokePaint.strokeWidth = 10f
                strokePaint.color = Color.argb(
                    65,
                    Color.red(osmSettings.routeColor.argb),
                    Color.green(osmSettings.routeColor.argb),
                    Color.blue(osmSettings.routeColor.argb)
                )
                canvas.drawPath(path, strokePaint)
                strokePaint.strokeWidth = 4f
                strokePaint.color = osmSettings.routeColor.argb
                canvas.drawPath(path, strokePaint)
                strokePaint.strokeJoin = Paint.Join.MITER
                strokePaint.strokeCap = Paint.Cap.BUTT
            }
            if (track.isNotEmpty()) {
                val pointStep = maxOf(1, track.size / MAX_VISIBLE_TRACK_DOTS)
                track.forEachIndexed { index, point ->
                    if (index % pointStep != 0 && index != track.lastIndex) return@forEachIndexed
                    val worldPoint = osmWorldPixel(point.latitude, point.longitude, zoom)
                    val x = anchorX + (worldPoint.x - worldCenter.x).toFloat()
                    val y = anchorY + (worldPoint.y - worldCenter.y).toFloat()
                    fillPaint.color = Color.argb(
                        65,
                        Color.red(osmSettings.routeColor.argb),
                        Color.green(osmSettings.routeColor.argb),
                        Color.blue(osmSettings.routeColor.argb)
                    )
                    canvas.drawCircle(x, y, 4.5f, fillPaint)
                    fillPaint.color = osmSettings.routeColor.argb
                    canvas.drawCircle(x, y, 2.2f, fillPaint)
                }
            }
            val navigationProgress = drawNavigation(
                canvas = canvas,
                worldCenter = worldCenter,
                zoom = zoom,
                centerX = anchorX,
                centerY = anchorY,
                position = current?.takeIf { freshFix }?.let { NavPoint(it.latitude, it.longitude) }
            )
            // Everything world-projected (tiles, track, route, nav overlay) is
            // drawn; text, the bike marker, the maneuver banner and the trip
            // strip below must stay upright and screen-fixed, so the
            // heading-up rotation ends here. The tint is drawn unrotated too,
            // so it fully covers the panel's actual corners instead of a
            // rotated copy of the same rect potentially leaving small gaps
            // near them.
            canvas.restoreToCount(rotationSaveCount)
            navigationProgress?.let { progress ->
                drawManeuverBanner(canvas, progress, mapLeft, mapTop, mapRight)
                drawTripStrip(canvas, progress, mapLeft, mapBottom, mapRight)
            }
            if (loadedTiles == 0) {
                drawMapGrid(canvas, mapLeft, mapTop, mapRight, mapBottom)
                text(
                    canvas,
                    if (osmTiles.hasCellularNetwork()) "LOADING CARTO MAP" else "MAP NEEDS CELLULAR DATA",
                    centerX,
                    centerY + 52f,
                    13f,
                    COLOR_WARNING,
                    Paint.Align.CENTER,
                    monoBoldTypeface
                )
            } else if (!freshFix && !trackHasLiveFix) {
                text(
                    canvas,
                    "GPS FIX STALE",
                    centerX,
                    mapTop + 22f,
                    11f,
                    COLOR_WARNING,
                    Paint.Align.CENTER,
                    monoBoldTypeface
                )
            }
            // The bike glyph itself stays unrotated (points straight up) when
            // heading-up is active, since the map beneath it already carries
            // the rotation that makes "up" mean "the rider's own heading".
            drawBikeMarker(
                canvas,
                anchorX,
                anchorY,
                freshFix || trackHasLiveFix,
                if (headingUpActive) 0f else bearing,
                accentColor = osmSettings.positionColor.argb
            )
        } else {
            drawMapGrid(canvas, mapLeft, mapTop, mapRight, mapBottom)
            drawBikeMarker(canvas, centerX, centerY, active = false, bearingDegrees = 0f)
            text(
                canvas,
                "ACQUIRING GPS POSITION",
                centerX,
                centerY + 52f,
                13f,
                COLOR_WARNING,
                Paint.Align.CENTER,
                monoBoldTypeface
            )
        }
        canvas.restoreToCount(mapSaveCount)

        if (!isEmbeddedAndroidAuto && chromeAmount > ANIMATION_SNAP_THRESHOLD) {
            drawMapModeBadge(canvas, mapLeft, mapTop, headingUpLabel, chromeAmount)
        }

        fillPaint.color = COLOR_ATTRIBUTION_BACKGROUND
        canvas.drawRoundRect(mapRight - 151f, mapBottom - 21f, mapRight, mapBottom, 5f, 5f, fillPaint)
            text(
                canvas,
                osmSettings.baseStyle.attribution,
            mapRight - 5f,
            mapBottom - 7f,
            9f,
            COLOR_TEXT,
            Paint.Align.RIGHT,
            monoTypeface
        )
        if (chromeAmount > ANIMATION_SNAP_THRESHOLD) {
            text(
                canvas,
                when {
                    showingSavedTrip && followSavedTripWithGps -> "Z$zoom / SAVED TRIP / GPS FOLLOW / ${track.size} PTS"
                    showingSavedTrip -> "Z$zoom / SAVED TRIP / ${track.size} PTS"
                    !trackOverlayEnabled -> "Z$zoom / REC TRACK OFF"
                    recordingState != null && track.isEmpty() -> "Z$zoom / RECORDING / GPS SEARCH"
                    recordingState != null -> "Z$zoom / REC LIVE / ${recordingState.pointCount} PTS"
                    track.isNotEmpty() -> "Z$zoom / LAST TRACK / ${track.size} PTS"
                    else -> "Z$zoom / START RECORDING"
                },
                mapLeft + 4f,
                mapBottom - 7f,
                10f,
                colorWithAlpha(COLOR_TEXT, chromeAmount),
                typeface = monoTypeface
            )
        }
    }

    private fun drawMapModeBadge(
        canvas: Canvas,
        mapLeft: Float,
        mapTop: Float,
        label: String,
        chromeAmount: Float
    ) {
        val badgeLeft = mapLeft + 8f
        val badgeTop = mapTop + 8f
        val badgeRight = badgeLeft + label.length * 7f + 18f
        val badgeBottom = badgeTop + 22f
        fillPaint.color = colorWithAlpha(COLOR_ATTRIBUTION_BACKGROUND, chromeAmount)
        canvas.drawRoundRect(badgeLeft, badgeTop, badgeRight, badgeBottom, 5f, 5f, fillPaint)
        text(
            canvas,
            label,
            badgeLeft + 9f,
            badgeTop + 15f,
            10f,
            colorWithAlpha(COLOR_TEXT, chromeAmount),
            typeface = monoBoldTypeface
        )
    }

    private fun drawMapLibrePanel(
        canvas: Canvas,
        mapLeft: Float,
        mapTop: Float,
        mapRight: Float,
        mapBottom: Float,
        centerLatitude: Double?,
        centerLongitude: Double?,
        zoom: Int,
        bearing: Float,
        tiltDegrees: Float,
        headingUpActive: Boolean,
        track: List<TripTrackPoint>,
        current: RideGeoPoint?,
        freshFix: Boolean,
        trackHasLiveFix: Boolean
    ) {
        if (centerLatitude == null || centerLongitude == null) {
            drawMapGrid(canvas, mapLeft, mapTop, mapRight, mapBottom)
            text(canvas, "ACQUIRING GPS POSITION", (mapLeft + mapRight) / 2f, (mapTop + mapBottom) / 2f,
                13f, COLOR_WARNING, Paint.Align.CENTER, monoBoldTypeface)
            return
        }
        mapLibreSnapshots.request(
            latitude = centerLatitude,
            longitude = centerLongitude,
            zoom = zoom,
            bearingDegrees = if (headingUpActive) bearing else 0f,
            tiltDegrees = tiltDegrees,
            width = (mapRight - mapLeft).roundToInt(),
            height = (mapBottom - mapTop).roundToInt(),
            settings = mapLibreSettings
        )
        val bitmap = mapLibreSnapshots.bitmap()
        if (bitmap == null) {
            drawMapGrid(canvas, mapLeft, mapTop, mapRight, mapBottom)
            text(canvas, "LOADING MAPLIBRE", (mapLeft + mapRight) / 2f, (mapTop + mapBottom) / 2f + 24f,
                13f, COLOR_WARNING, Paint.Align.CENTER, monoBoldTypeface)
            return
        }
        canvas.drawBitmap(bitmap, null, RectF(mapLeft, mapTop, mapRight, mapBottom), mapPaint)
        val centerX = (mapLeft + mapRight) / 2f
        val centerY = (mapTop + mapBottom) / 2f
        val worldCenter = osmWorldPixel(centerLatitude, centerLongitude, zoom)
        val overlaySave = canvas.save()
        if (headingUpActive) canvas.rotate(-bearing, centerX, centerY)
        if (tiltDegrees > 0f) canvas.scale(1f, MAPLIBRE_TILT_SCALE, centerX, centerY)
        fun pointOnMap(point: NavPoint): Pair<Float, Float> {
            val world = osmWorldPixel(point.latitude, point.longitude, zoom)
            return centerX + (world.x - worldCenter.x).toFloat() to
                centerY + (world.y - worldCenter.y).toFloat()
        }
        if (track.size > 1) {
            val path = Path()
            track.forEachIndexed { index, point ->
                val (x, y) = pointOnMap(NavPoint(point.latitude, point.longitude))
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeJoin = Paint.Join.ROUND
            strokePaint.strokeCap = Paint.Cap.ROUND
            strokePaint.strokeWidth = 10f
            strokePaint.color = Color.argb(150, Color.red(mapLibreSettings.routeColor.argb),
                Color.green(mapLibreSettings.routeColor.argb), Color.blue(mapLibreSettings.routeColor.argb))
            canvas.drawPath(path, strokePaint)
            strokePaint.strokeWidth = 4f
            strokePaint.color = mapLibreSettings.routeColor.argb
            canvas.drawPath(path, strokePaint)
        }
        val navigationProgress = drawNavigation(
            canvas = canvas,
            worldCenter = worldCenter,
            zoom = zoom,
            centerX = centerX,
            centerY = centerY,
            position = current?.takeIf { freshFix }?.let { NavPoint(it.latitude, it.longitude) }
        )
        NavigationRuntime.route.value?.points?.lastOrNull()?.let { destination ->
            val (destinationX, destinationY) = pointOnMap(destination)
            fillPaint.color = mapLibreSettings.destinationColor.argb
            canvas.drawCircle(destinationX, destinationY, 8f, fillPaint)
            fillPaint.color = COLOR_BACKGROUND
            canvas.drawCircle(destinationX, destinationY, 3f, fillPaint)
        }
        canvas.restoreToCount(overlaySave)
        navigationProgress?.let { progress ->
            drawManeuverBanner(canvas, progress, mapLeft, mapTop, mapRight)
            drawTripStrip(canvas, progress, mapLeft, mapBottom, mapRight)
        }
        if (!freshFix && !trackHasLiveFix) {
            text(canvas, "GPS FIX STALE", centerX, mapTop + 22f, 11f, COLOR_WARNING, Paint.Align.CENTER, monoBoldTypeface)
        }
        drawBikeMarker(
            canvas,
            centerX,
            centerY,
            active = freshFix || trackHasLiveFix,
            bearingDegrees = if (headingUpActive) 0f else bearing,
            accentColor = mapLibreSettings.positionColor.argb
        )
    }

    private fun drawMapLibreAttribution(canvas: Canvas, mapRight: Float, mapBottom: Float) {
        fillPaint.color = COLOR_ATTRIBUTION_BACKGROUND
        canvas.drawRoundRect(mapRight - 160f, mapBottom - 21f, mapRight, mapBottom, 5f, 5f, fillPaint)
        text(canvas, "© OpenStreetMap / OpenFreeMap", mapRight - 5f, mapBottom - 7f, 9f,
            COLOR_TEXT, Paint.Align.RIGHT, monoTypeface)
    }

    /**
     * Draws the active navigation route (world-projected: rotates with the
     * map during heading-up) and advances [NavigationEngine] progress from
     * the current fix. Returns the progress so the caller can draw the
     * maneuver banner and trip strip afterward, in screen-fixed space - those
     * must never rotate with the map.
     */
    private fun drawNavigation(
        canvas: Canvas,
        worldCenter: OsmWorldPixel,
        zoom: Int,
        centerX: Float,
        centerY: Float,
        position: NavPoint?
    ): NavigationProgress? {
        val route = NavigationRuntime.route.value ?: return null
        if (route.points.size < 2) return null

        val path = Path()
        route.points.forEachIndexed { index, point ->
            val worldPoint = osmWorldPixel(point.latitude, point.longitude, zoom)
            val x = centerX + (worldPoint.x - worldCenter.x).toFloat()
            val y = centerY + (worldPoint.y - worldCenter.y).toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeJoin = Paint.Join.ROUND
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.strokeWidth = 12f
        val navigationRouteColor = navigationRouteColor()
        strokePaint.color = Color.argb(
            145,
            Color.red(navigationRouteColor),
            Color.green(navigationRouteColor),
            Color.blue(navigationRouteColor)
        )
        canvas.drawPath(path, strokePaint)
        strokePaint.strokeWidth = 5f
        strokePaint.color = navigationRouteColor
        canvas.drawPath(path, strokePaint)

        if (route.curvedSegments.isNotEmpty() && m2bSettings.curvedSegmentsHighlighted()) {
            strokePaint.color = if (mapSource == RideDashboardMapSource.MAPLIBRE) {
                mapLibreSettings.curvyRoadColor.argb
            } else {
                osmSettings.curvyRoadColor.argb
            }
            route.curvedSegments.forEach { segment ->
                val curvyPath = Path()
                for (index in segment.startPointIndex..segment.endPointIndex) {
                    val point = route.points.getOrNull(index) ?: continue
                    val worldPoint = osmWorldPixel(point.latitude, point.longitude, zoom)
                    val x = centerX + (worldPoint.x - worldCenter.x).toFloat()
                    val y = centerY + (worldPoint.y - worldCenter.y).toFloat()
                    if (index == segment.startPointIndex) curvyPath.moveTo(x, y) else curvyPath.lineTo(x, y)
                }
                canvas.drawPath(curvyPath, strokePaint)
            }
        }

        strokePaint.strokeJoin = Paint.Join.MITER
        strokePaint.strokeCap = Paint.Cap.BUTT

        if (mapSource != RideDashboardMapSource.MAPLIBRE) {
            val destination = route.points.last()
            val destinationWorld = osmWorldPixel(destination.latitude, destination.longitude, zoom)
            val destinationX = centerX + (destinationWorld.x - worldCenter.x).toFloat()
            val destinationY = centerY + (destinationWorld.y - worldCenter.y).toFloat()
            fillPaint.color = osmSettings.destinationColor.argb
            canvas.drawCircle(destinationX, destinationY, 8f, fillPaint)
            fillPaint.color = COLOR_BACKGROUND
            canvas.drawCircle(destinationX, destinationY, 3f, fillPaint)
        }

        if (position == null) return null
        if (navigationEngineRoute !== route) {
            navigationEngineRoute = route
            navigationEngine = NavigationEngine(route)
        }
        val progress = navigationEngine?.update(position) ?: return null
        NavigationRuntime.publishProgress(progress)
        voiceGuidance.onProgress(progress)
        return progress
    }

    private fun navigationRouteColor(): Int = if (mapSource == RideDashboardMapSource.MAPLIBRE) {
        mapLibreSettings.routeColor.argb
    } else {
        osmSettings.routeColor.argb
    }

    private fun drawManeuverBanner(
        canvas: Canvas,
        progress: NavigationProgress,
        left: Float,
        top: Float,
        right: Float
    ) {
        val maneuver = progress.currentManeuver ?: return
        val bannerHeight = 46f
        fillPaint.color = COLOR_NAV_BANNER
        canvas.drawRect(left, top, right, top + bannerHeight, fillPaint)

        val arrowCenterX = left + 30f
        val arrowCenterY = top + bannerHeight / 2f
        drawManeuverArrow(canvas, maneuverDirection(maneuver.type), arrowCenterX, arrowCenterY, 16f)

        text(
            canvas,
            formatDistanceMeters(progress.distanceToManeuverMeters),
            left + 56f,
            top + 19f,
            15f,
            COLOR_TEXT,
            typeface = boldTypeface
        )
        text(
            canvas,
            maneuver.instruction.ifBlank { "Continue" },
            left + 56f,
            top + 37f,
            12f,
            COLOR_MUTED,
            typeface = monoTypeface
        )
    }

    /**
     * Draws a maneuver-specific glyph rather than one rotated arrow shape,
     * so ARRIVE (pin), ROUNDABOUT (circle+exit) and UTURN (hook) read
     * distinctly at a glance instead of looking like STRAIGHT/back-turn.
     */
    private fun drawManeuverArrow(
        canvas: Canvas,
        direction: ManeuverDirection,
        centerX: Float,
        centerY: Float,
        size: Float
    ) {
        when (direction) {
            ManeuverDirection.ARRIVE -> drawArriveMarker(canvas, centerX, centerY, size)
            ManeuverDirection.ROUNDABOUT -> drawRoundaboutMarker(canvas, centerX, centerY, size)
            ManeuverDirection.UTURN -> drawUTurnMarker(canvas, centerX, centerY, size)
            else -> drawTurnMarker(canvas, direction, centerX, centerY, size)
        }
    }

    /** A bent "road" line (stem + turn segment) with an arrowhead at the tip, for STRAIGHT/LEFT/RIGHT variants. */
    private fun drawTurnMarker(
        canvas: Canvas,
        direction: ManeuverDirection,
        centerX: Float,
        centerY: Float,
        size: Float
    ) {
        val bendDegrees = when (direction) {
            ManeuverDirection.SLIGHT_RIGHT -> 35f
            ManeuverDirection.RIGHT -> 90f
            ManeuverDirection.SHARP_RIGHT -> 135f
            ManeuverDirection.SHARP_LEFT -> -135f
            ManeuverDirection.LEFT -> -90f
            ManeuverDirection.SLIGHT_LEFT -> -35f
            else -> 0f
        }
        val stemLength = size * 1.1f
        val turnLength = size * 0.85f
        val bendRad = Math.toRadians(bendDegrees.toDouble())

        val stemStartX = centerX
        val stemStartY = centerY + stemLength * 0.55f
        val bendX = centerX
        val bendY = centerY - stemLength * 0.15f
        val tipX = (bendX + turnLength * sin(bendRad)).toFloat()
        val tipY = (bendY - turnLength * cos(bendRad)).toFloat()

        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = size * 0.32f
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.strokeJoin = Paint.Join.ROUND
        strokePaint.color = navigationRouteColor()
        val roadPath = Path().apply {
            moveTo(stemStartX, stemStartY)
            lineTo(bendX, bendY)
            lineTo(tipX, tipY)
        }
        canvas.drawPath(roadPath, strokePaint)
        strokePaint.strokeCap = Paint.Cap.BUTT
        strokePaint.strokeJoin = Paint.Join.MITER

        drawArrowhead(canvas, tipX, tipY, bendDegrees, size * 0.42f)
    }

    /** A filled arrowhead pointing [headingDegrees] clockwise from straight up, tip at ([tipX], [tipY]). */
    private fun drawArrowhead(canvas: Canvas, tipX: Float, tipY: Float, headingDegrees: Float, size: Float) {
        val saveCount = canvas.save()
        canvas.rotate(headingDegrees, tipX, tipY)
        val path = Path().apply {
            moveTo(tipX, tipY - size)
            lineTo(tipX - size * 0.6f, tipY + size * 0.35f)
            lineTo(tipX, tipY + size * 0.05f)
            lineTo(tipX + size * 0.6f, tipY + size * 0.35f)
            close()
        }
        fillPaint.color = navigationRouteColor()
        canvas.drawPath(path, fillPaint)
        canvas.restoreToCount(saveCount)
    }

    /** A location-pin glyph: filled circular head over a pointed tail, for ARRIVE. */
    private fun drawArriveMarker(canvas: Canvas, centerX: Float, centerY: Float, size: Float) {
        val headRadius = size * 0.42f
        val headCenterY = centerY - size * 0.15f
        val tail = Path().apply {
            moveTo(centerX, centerY + size * 0.9f)
            lineTo(centerX - headRadius * 0.55f, headCenterY + headRadius * 0.75f)
            lineTo(centerX + headRadius * 0.55f, headCenterY + headRadius * 0.75f)
            close()
        }
        fillPaint.color = navigationRouteColor()
        canvas.drawPath(tail, fillPaint)
        canvas.drawCircle(centerX, headCenterY, headRadius, fillPaint)
        fillPaint.color = COLOR_NAV_BANNER
        canvas.drawCircle(centerX, headCenterY, headRadius * 0.42f, fillPaint)
    }

    /** A circle (the roundabout) with an exit arrowhead, for ROUNDABOUT. */
    private fun drawRoundaboutMarker(canvas: Canvas, centerX: Float, centerY: Float, size: Float) {
        val radius = size * 0.6f
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = size * 0.22f
        strokePaint.strokeCap = Paint.Cap.BUTT
        strokePaint.strokeJoin = Paint.Join.MITER
        strokePaint.color = navigationRouteColor()
        canvas.drawCircle(centerX, centerY, radius, strokePaint)
        drawArrowhead(canvas, centerX, centerY - radius - size * 0.32f, 0f, size * 0.36f)
    }

    /** A hook (up, arc across the top, back down) with an arrowhead on the return leg, for UTURN. */
    private fun drawUTurnMarker(canvas: Canvas, centerX: Float, centerY: Float, size: Float) {
        val legOffset = size * 0.38f
        val hookTop = centerY - size * 0.35f
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = size * 0.28f
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.strokeJoin = Paint.Join.ROUND
        strokePaint.color = navigationRouteColor()
        val returnLegBottomY = centerY + size * 0.25f
        val hookPath = Path().apply {
            moveTo(centerX - legOffset, centerY + size * 0.85f)
            lineTo(centerX - legOffset, hookTop)
            arcTo(
                centerX - legOffset,
                hookTop - legOffset,
                centerX + legOffset,
                hookTop + legOffset,
                180f,
                180f,
                false
            )
            lineTo(centerX + legOffset, returnLegBottomY)
        }
        canvas.drawPath(hookPath, strokePaint)
        strokePaint.strokeCap = Paint.Cap.BUTT
        strokePaint.strokeJoin = Paint.Join.MITER
        drawArrowhead(canvas, centerX + legOffset, returnLegBottomY, 180f, size * 0.4f)
    }

    private fun drawTripStrip(
        canvas: Canvas,
        progress: NavigationProgress,
        left: Float,
        bottom: Float,
        right: Float
    ) {
        val stripHeight = 22f
        val top = bottom - stripHeight
        fillPaint.color = COLOR_NAV_BANNER
        canvas.drawRect(left, top, right, bottom, fillPaint)
        // Right-aligned: the map's own zoom/recording status label already
        // occupies the bottom-left corner at this same height.
        text(
            canvas,
            "${formatDistanceMeters(progress.distanceRemainingMeters)} REMAINING",
            right - 8f,
            bottom - 7f,
            10f,
            COLOR_MUTED,
            align = Paint.Align.RIGHT,
            typeface = monoTypeface
        )
    }

    private fun formatDistanceMeters(meters: Double): String =
        UnitFormat.distance(meters, widgetCtx.units)

    private fun drawEmbeddedAndroidAuto(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        alignFillToTop: Boolean = false,
        touchExclusions: List<RectF> = emptyList()
    ) {
        fillPaint.color = Color.BLACK
        canvas.drawRect(left, top, right, bottom, fillPaint)
        val source = embeddedAndroidAuto
        if (source == null) {
            androidAutoTouchViewport = null
            drawEmbeddedAndroidAutoStatus(canvas, left, top, right, bottom, "ANDROID AUTO UNAVAILABLE")
            return
        }

        val placement = calculateRideDashboardAndroidAutoPlacement(
            containerLeft = left,
            containerTop = top,
            containerRight = right,
            containerBottom = bottom,
            sourceWidth = source.width,
            sourceHeight = source.height,
            displayMode = source.displayMode,
            alignFillToTop = alignFillToTop
        )
        val destination = RectF(
            placement.left,
            placement.top,
            placement.right,
            placement.bottom
        )

        val portrait = shouldUsePortraitRideDashboardLayout(canvas.width, canvas.height)
        val scaleX = canvas.width.toFloat() / DESIGN_WIDTH
        val scaleY = if (portrait) scaleX else canvas.height.toFloat() / DESIGN_HEIGHT
        fun physicalBounds(bounds: RectF) = PixelBounds(
            left = (bounds.left * scaleX).roundToInt(),
            top = (bounds.top * scaleY).roundToInt(),
            right = (bounds.right * scaleX).roundToInt(),
            bottom = (bounds.bottom * scaleY).roundToInt()
        )
        androidAutoTouchViewport = EmbeddedAndroidAutoTouchViewport(
            sourceViewport = PreviewViewport(
                x = (destination.left * scaleX).roundToInt(),
                y = (destination.top * scaleY).roundToInt(),
                width = (destination.width() * scaleX).roundToInt().coerceAtLeast(1),
                height = (destination.height() * scaleY).roundToInt().coerceAtLeast(1),
                source = DisplayGeometry(source.width, source.height)
            ),
            visibleBounds = physicalBounds(RectF(left, top, right, bottom)),
            excludedBounds = touchExclusions.map(::physicalBounds)
        )

        if (!source.draw(canvas, destination)) {
            val status = when (RideDashboardAndroidAutoRuntime.state.value) {
                RideDashboardAndroidAutoState.Idle -> "ANDROID AUTO IDLE"
                RideDashboardAndroidAutoState.Preparing -> "PREPARING ANDROID AUTO"
                RideDashboardAndroidAutoState.ReceiverReady -> "STARTING ANDROID AUTO"
                RideDashboardAndroidAutoState.Streaming -> "WAITING FOR VIDEO FRAME"
                is RideDashboardAndroidAutoState.Failed -> "ANDROID AUTO UNAVAILABLE"
            }
            drawEmbeddedAndroidAutoStatus(canvas, left, top, right, bottom, status)
        }
    }

    private fun drawEmbeddedAndroidAutoStatus(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        status: String
    ) {
        text(
            canvas,
            status,
            (left + right) / 2f,
            (top + bottom) / 2f,
            14f,
            COLOR_MUTED,
            Paint.Align.CENTER,
            monoBoldTypeface
        )
    }

    // drawMetricsPanel() removed — replaced by TripMetricsWidget via the widget system
    // compassPoint() removed — moved to SpeedGaugeWidget

    private fun drawTechnicalRail(
        canvas: Canvas,
        snapshot: RideTelemetrySnapshot,
        freshFix: Boolean,
        top: Float = DESIGN_HEIGHT - RAIL_HEIGHT,
        bottom: Float = DESIGN_HEIGHT
    ) {
        fillPaint.color = COLOR_BACKGROUND
        canvas.drawRect(0f, top, DESIGN_WIDTH, bottom, fillPaint)
        val labels = listOf(
            "GPS" to if (freshFix) snapshot.accuracyMeters?.let { "+/-${it.roundToInt()} M" } ?: "LOCK" else "NO FIX",
            "PHONE" to "$cachedPhoneBattery%",
            "CELL" to cachedCellularStatus,
            "LINK" to String.format(Locale.US, "%.1f MBPS", bitRate / 1_000_000f),
            "TBOX" to tBoxLabel.take(12).uppercase(Locale.US)
        )
        val cellWidth = DESIGN_WIDTH / labels.size
        labels.forEachIndexed { index, (label, value) ->
            val x = index * cellWidth
            if (index > 0) {
                strokePaint.color = COLOR_BORDER
                strokePaint.strokeWidth = 1f
                canvas.drawLine(x, top + 10f, x, bottom - 10f, strokePaint)
            }
            val textY = top + (bottom - top) * 0.62f
            text(canvas, label, x + 14f, textY, 11f, COLOR_MUTED, typeface = monoTypeface)
            text(canvas, value, x + cellWidth - 12f, textY, 13f, COLOR_PRIMARY, Paint.Align.RIGHT, monoBoldTypeface)
        }
    }

    // metric() removed — moved to TripMetricsWidget

    // drawSpeedHistory() removed — moved to TripMetricsWidget

    private fun drawBikeMarker(
        canvas: Canvas,
        x: Float,
        y: Float,
        active: Boolean,
        bearingDegrees: Float,
        accentColor: Int = COLOR_PRIMARY
    ) {
        fillPaint.color = if (active) accentColor else COLOR_MUTED
        canvas.drawCircle(x, y, 17f, fillPaint)
        val saveCount = canvas.save()
        canvas.rotate(bearingDegrees, x, y)
        fillPaint.color = COLOR_PRIMARY_TEXT
        canvas.drawPath(
            Path().apply {
                moveTo(x, y - 11f)
                lineTo(x - 7f, y + 9f)
                lineTo(x, y + 5f)
                lineTo(x + 7f, y + 9f)
                close()
            },
            fillPaint
        )
        canvas.restoreToCount(saveCount)
    }

    private fun drawMapGrid(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ) {
        fillPaint.color = COLOR_MAP_LOADING
        canvas.drawRect(left, top, right, bottom, fillPaint)
        strokePaint.color = COLOR_MAP_GRID
        strokePaint.strokeWidth = 1f
        var x = left
        while (x <= right) {
            canvas.drawLine(x, top, x, bottom, strokePaint)
            x += 32f
        }
        var y = top
        while (y <= bottom) {
            canvas.drawLine(left, y, right, y, strokePaint)
            y += 32f
        }
    }

    private fun statusDot(canvas: Canvas, x: Float, y: Float, active: Boolean) {
        fillPaint.color = if (active) COLOR_SUCCESS_GLOW else COLOR_WARNING_GLOW
        canvas.drawCircle(x, y, 9f, fillPaint)
        fillPaint.color = if (active) COLOR_SUCCESS else COLOR_WARNING
        canvas.drawCircle(x, y, 4f, fillPaint)
    }

    private fun refreshDeviceStatus(nowElapsed: Long) {
        if (lastDeviceStatusRefreshMillis != Long.MIN_VALUE &&
            nowElapsed - lastDeviceStatusRefreshMillis < DEVICE_STATUS_REFRESH_MILLIS
        ) {
            return
        }
        lastDeviceStatusRefreshMillis = nowElapsed
        widgetCtx.units = MotoHubSettings.distanceUnits(applicationContext)
        cachedPhoneBattery = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            .coerceIn(0, 100)
        cachedCellularStatus = if (osmTiles.hasCellularNetwork()) "READY" else "OFF"
        // Battery temperature in tenths of a degree → convert to °C
        val batteryIntent = applicationContext.registerReceiver(
            null,
            android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
        )
        val tempTenths = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        cachedBatteryTempCelsius = if (tempTenths > 0) tempTenths / 10f else -1f
        // Charging status
        val plugged = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        cachedIsCharging = plugged != 0
        // Internal storage usage
        try {
            val statFs = StatFs(applicationContext.filesDir.absolutePath)
            val total = statFs.totalBytes
            val free = statFs.freeBytes
            cachedStorageUsedPercent = if (total > 0) {
                ((total - free) * 100 / total).toInt().coerceIn(0, 100)
            } else 0
        } catch (_: Exception) {
            cachedStorageUsedPercent = 0
        }
        // Battery voltage
        cachedBatteryVoltageMv = batteryIntent?.getIntExtra(
            android.os.BatteryManager.EXTRA_VOLTAGE, 0
        ) ?: 0
        // Wi-Fi T-Box RSSI
        cachedWifiRssiDbm = try {
            val wifiInfo = applicationContext.getSystemService(WifiManager::class.java).connectionInfo
            wifiInfo?.rssi ?: 0
        } catch (_: Exception) {
            0
        }
        // Reset acceleration peak for next refresh window
        cachedLinearAccelPeak = 0f
        refreshMediaNowPlaying()
    }

    private fun refreshNavigation() {
        val progress = NavigationRuntime.progress.value
        cachedNavHasRoute = NavigationRuntime.route.value != null
        cachedNavDistanceRemainingMeters = progress?.distanceRemainingMeters ?: 0.0
        cachedNavDistanceToManeuverMeters = progress?.distanceToManeuverMeters ?: 0.0
        cachedNavManeuverType = progress?.currentManeuver?.type ?: ""
        cachedNavManeuverModifier = progress?.currentManeuver?.modifier ?: ""
        cachedNavManeuverInstruction = progress?.currentManeuver?.instruction ?: ""
        cachedNavOffRoute = progress?.offRoute ?: false
    }

    private fun refreshMediaNowPlaying() {
        try {
            val manager = applicationContext.getSystemService(Context.MEDIA_SESSION_SERVICE)
                as android.media.session.MediaSessionManager
            // A non-null, enabled NotificationListenerService component is required here -
            // without it this throws SecurityException on every call (caught below).
            val listenerComponent = android.content.ComponentName(
                applicationContext, NowPlayingListenerService::class.java
            )
            // When more than one app holds an active media session (very common -
            // e.g. a music app plus a podcast app, assistant, or keyboard clip
            // notification), getActiveSessions() orders them by "most recently
            // active" and that order can shift on every poll even though the
            // rider's actual session hasn't changed. Blindly taking firstOrNull()
            // made the widget flap between sessions (and their differing/missing
            // duration) every ~5s. Prefer whichever session is actually playing,
            // preferring the one already being tracked to avoid flapping between
            // two simultaneously-playing sessions; fall back to a paused tracked
            // session (so pausing doesn't blank the display), then to anything.
            // MediaButtonBridge (handlebar AVRCP capture) publishes its own MediaSession
            // under our own package - a fake "MOTO-HUB controls" track, always reported
            // as STATE_PLAYING at position 0, purely so the motorcycle's Bluetooth stack
            // has something addressable for play/pause/next/prev. It must never be
            // mistaken for the rider's actual music: exclude our own package outright,
            // rather than let it win selection below whenever it toggles active and the
            // real app's session isn't reporting STATE_PLAYING at that exact instant
            // (this was the cause of the position jumping to 0 and back).
            val activeSessions = manager.getActiveSessions(listenerComponent).orEmpty()
                .filterNot { it.packageName == applicationContext.packageName }
            val previousToken = lastMediaController?.sessionToken
            fun isPlaying(candidate: android.media.session.MediaController) =
                candidate.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
            val controller = activeSessions.firstOrNull { it.sessionToken == previousToken && isPlaying(it) }
                ?: activeSessions.firstOrNull(::isPlaying)
                ?: activeSessions.firstOrNull { it.sessionToken == previousToken }
                ?: activeSessions.firstOrNull()
            if (controller == null) {
                lastMediaController?.unregisterCallback(mediaControllerCallback)
                lastMediaController = null
                cachedMediaTitle = ""
                cachedMediaArtist = ""
                cachedMediaAlbum = ""
                cachedMediaArtwork = null
                cachedMediaPositionMs = 0L
                cachedMediaDurationMs = 0L
                cachedMediaIsPlaying = false
                return
            }
            if (lastMediaController?.sessionToken != controller.sessionToken) {
                lastMediaController?.unregisterCallback(mediaControllerCallback)
                // The no-Handler overload creates one bound to the calling thread's
                // Looper - this runs on the plain (Looper-less) render thread, which
                // crashed with "Can't create handler inside thread ... that has not
                // called Looper.prepare()". Dispatch callbacks on the main thread instead.
                controller.registerCallback(mediaControllerCallback, mainThreadHandler)
                lastMediaController = controller
            }
            val meta = controller.metadata
            val newTitle = meta?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: ""
            cachedMediaArtist = meta?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: ""
            cachedMediaAlbum = meta?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM) ?: ""
            cachedMediaArtwork = meta?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: meta?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART)
            cachedMediaDurationMs = meta?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            val state = controller.playbackState
            val newPosition = state?.position ?: 0L
            val newIsPlaying = state?.state == android.media.session.PlaybackState.STATE_PLAYING
            if (!isSpuriousPositionReset(newTitle, newPosition, newIsPlaying)) {
                cachedMediaPositionMs = newPosition
            }
            cachedMediaTitle = newTitle
            cachedMediaIsPlaying = newIsPlaying
            mediaErrorLogged = false
        } catch (failure: Exception) {
            // Notification access not granted, or no active media session.
            // Logged once (not every 5s refresh) so this is diagnosable from
            // Application Logs instead of guessing blind - it stays quiet
            // again as soon as a refresh succeeds.
            if (!mediaErrorLogged) {
                mediaErrorLogged = true
                ProjectionEventLog.warning("NOW_PLAYING", "Could not read the active media session.", failure)
            }
        }
    }

    private var lastMediaController: android.media.session.MediaController? = null
    private var mediaErrorLogged = false
    private val mainThreadHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    private val mediaControllerCallback = object : android.media.session.MediaController.Callback() {
        override fun onMetadataChanged(metadata: android.media.MediaMetadata?) {
            cachedMediaTitle = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: ""
            cachedMediaArtist = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: ""
            cachedMediaAlbum = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM) ?: ""
            cachedMediaArtwork = metadata?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART)
            cachedMediaDurationMs = metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        }
        override fun onPlaybackStateChanged(state: android.media.session.PlaybackState?) {
            val newPosition = state?.position ?: 0L
            val newIsPlaying = state?.state == android.media.session.PlaybackState.STATE_PLAYING
            if (!isSpuriousPositionReset(cachedMediaTitle, newPosition, newIsPlaying)) {
                cachedMediaPositionMs = newPosition
            }
            cachedMediaIsPlaying = newIsPlaying
        }
    }

    /**
     * Some media apps' MediaSession implementations report position 0 on an
     * otherwise-ordinary STATE_PLAYING update for the same track, moments
     * after already reporting a real, well-advanced position (observed with
     * a rider's own Navidrome client - confirmed via Application Logs to be
     * that app's own PlaybackState, not a MOTO-HUB session-selection issue).
     * Treat that specific pattern as noise and keep the last good position
     * rather than let the widget's progress bar visibly snap back and forth.
     * A real track restart/skip-to-start is indistinguishable from this by
     * position alone, so this only guards the same, still-playing track -
     * a genuine new track (different title) is never suppressed.
     */
    private fun isSpuriousPositionReset(title: String, newPosition: Long, newIsPlaying: Boolean): Boolean =
        newIsPlaying &&
            newPosition < SPURIOUS_POSITION_RESET_THRESHOLD_MS &&
            title == cachedMediaTitle &&
            cachedMediaPositionMs >= SPURIOUS_POSITION_RESET_THRESHOLD_MS

    private fun text(
        canvas: Canvas,
        value: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        align: Paint.Align = Paint.Align.LEFT,
        typeface: Typeface = monoTypeface
    ) {
        textPaint.textSize = size
        textPaint.color = color
        textPaint.textAlign = align
        textPaint.typeface = typeface
        canvas.drawText(value, x, y, textPaint)
    }

    private fun colorWithAlpha(color: Int, amount: Float): Int = Color.argb(
        (Color.alpha(color) * amount.coerceIn(0f, 1f)).roundToInt(),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )

    /** Returns the preview-only gauge speed, or null when the sweep is inactive. */
    private fun startupSweepSpeed(sweepPhaseMs: Long): Float? {
        if (sweepPhaseMs !in 0L until SWEEP_DURATION_MS) return null
        val t = (sweepPhaseMs.toFloat() / SWEEP_DURATION_MS).coerceIn(0f, 1f)
        return if (t < SWEEP_RISE_FRACTION) {
            val p = t / SWEEP_RISE_FRACTION
            (1f - (1f - p) * (1f - p) * (1f - p)) * 180f
        } else {
            val p = (t - SWEEP_RISE_FRACTION) / (1f - SWEEP_RISE_FRACTION)
            (1f - p * p * p) * 180f
        }
    }

    // compassPoint() removed — moved to SpeedGaugeWidget

    // formatDistance() removed — moved to TripMetricsWidget

    // formatDuration() removed — moved to TripMetricsWidget

    /** Computes a stable bounding box from a sampled trace, keeping long trips cheap to render. */
    private fun savedTrackBounds(points: List<io.motohub.android.feature.trips.TripTrackPoint>): SavedTrackBounds? {
        if (points.isEmpty()) return null
        val step = maxOf(1, points.size / 256)
        var minLatitude = Double.POSITIVE_INFINITY
        var maxLatitude = Double.NEGATIVE_INFINITY
        var minLongitude = Double.POSITIVE_INFINITY
        var maxLongitude = Double.NEGATIVE_INFINITY
        points.forEachIndexed { index, point ->
            if (index % step != 0 && index != points.lastIndex) return@forEachIndexed
            minLatitude = minOf(minLatitude, point.latitude)
            maxLatitude = maxOf(maxLatitude, point.latitude)
            minLongitude = minOf(minLongitude, point.longitude)
            maxLongitude = maxOf(maxLongitude, point.longitude)
        }
        return SavedTrackBounds(minLatitude, maxLatitude, minLongitude, maxLongitude)
    }

    /** Picks the most detailed OSM zoom that keeps a loaded historical trip visible. */
    private fun zoomForSavedTrack(
        bounds: SavedTrackBounds,
        viewportWidth: Float,
        viewportHeight: Float
    ): Int {
        val horizontalLimit = viewportWidth.coerceAtLeast(1f) * 0.82
        val verticalLimit = viewportHeight.coerceAtLeast(1f) * 0.82
        var selected = 10
        for (zoom in 10..OSM_ZOOM) {
            val worldSize = OSM_TILE_SIZE * (1 shl zoom)
            val xSpan = ((bounds.maxLongitude - bounds.minLongitude) / 360.0) * worldSize
            val ySpan = abs(mercatorY(bounds.minLatitude) - mercatorY(bounds.maxLatitude)) * worldSize
            if (xSpan <= horizontalLimit && ySpan <= verticalLimit) selected = zoom
        }
        return selected
    }

    private fun mercatorY(latitude: Double): Double {
        val clamped = latitude.coerceIn(-85.05112878, 85.05112878)
        val radians = Math.toRadians(clamped)
        return 0.5 - (kotlin.math.ln((1.0 + sin(radians)) / (1.0 - sin(radians))) / (4.0 * Math.PI))
    }

    private companion object {
        const val DESIGN_WIDTH = 800f
        const val DESIGN_HEIGHT = 384f
        const val HEADER_HEIGHT = 54f
        const val RAIL_HEIGHT = 54f
        /** Time reserved for the static motorcycle profile frame before the sweep. */
        const val SWEEP_START_DELAY_MS = 4_000L
        const val STARTUP_PROFILE_MAX_DECODE_SIZE = 1_024
        const val STARTUP_BRAND_BAND_FRACTION = 0.22f
        const val STARTUP_BRAND_TITLE_SCALE = 0.075f
        /** Duration of the 0→180→0 sweep once it starts. */
        const val SWEEP_DURATION_MS = 6_000L
        /** Fraction of sweep time spent rising to 180; the rest falls back to 0. */
        const val SWEEP_RISE_FRACTION = 0.45f
        /** Both side panels use the same width; the map occupies the centre remainder. */
        const val SIDE_PANEL_WIDTH = 216f
        const val SPEED_PANEL_WIDTH = SIDE_PANEL_WIDTH
        const val METRICS_PANEL_LEFT = DESIGN_WIDTH - SIDE_PANEL_WIDTH
        const val METRICS_PANEL_WIDTH = SIDE_PANEL_WIDTH
        const val PORTRAIT_PANEL_HEIGHT = 330f
        const val RENDER_JOIN_TIMEOUT_MILLIS = 1_500L
        const val DEVICE_STATUS_REFRESH_MILLIS = 5_000L
        const val SPURIOUS_POSITION_RESET_THRESHOLD_MS = 3_000L
        const val OSM_ZOOM = 16
        const val OSM_TILE_SIZE = 256.0
        const val MAPLIBRE_NAVIGATION_TILT_DEGREES = 50f
        const val MAPLIBRE_TILT_SCALE = 0.72f
        const val MAP_INSET = 2f
        /** Fraction down the map panel where the bike sits during heading-up navigation (0=top, 1=bottom). */
        const val LOOK_AHEAD_ANCHOR_FRACTION = 0.72f
        const val MAX_VISIBLE_TRACK_DOTS = 180
        /** Deliberately slower than panel cycling so the initial composition reads as an entrance. */
        const val INITIAL_ENTRY_RESPONSE = 5.5
        const val ANIMATION_RESPONSE = 10.0
        const val MAX_ANIMATION_STEP_SECONDS = 0.1
        const val ANIMATION_SNAP_THRESHOLD = 0.002f
        /** Slower than [ANIMATION_RESPONSE] so heading-up map rotation reads as a smooth pan, not a snap. */
        const val BEARING_ANIMATION_RESPONSE = 4.0
        const val BEARING_SNAP_THRESHOLD_DEGREES = 0.05f
        /** Below this speed, raw GPS course-over-ground is mostly noise; heading freezes instead of chasing it. */
        const val MIN_HEADING_UP_SPEED_KPH = 5f
        /** Y-axis compression for faux 3D perspective during navigation (1 = flat, 0.55 ≈ 45° tilt). */
        const val PERSPECTIVE_TILT_SCALE = 0.55f
        val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val COLOR_BACKGROUND = Color.rgb(5, 9, 8)
        val COLOR_PANEL = Color.rgb(9, 15, 13)
        val COLOR_GRID = Color.rgb(12, 30, 23)
        val COLOR_MAP_GRID = Color.rgb(24, 49, 41)
        /**
         * Fill for the OSM map area before tiles are drawn, and for [drawMapGrid]'s
         * no-tiles-loaded placeholder - kept separate from [COLOR_PANEL] (the general dark
         * dashboard chrome background used everywhere else) so the map area stays light,
         * matching MOTO-HUB's OSM tiles, instead of flashing near-black while new tiles
         * download. Most visible when the position jumps somewhere with no cached tiles at
         * all (e.g. a GPS mock/teleport), since every tile is briefly missing at once
         * instead of the usual one-tile-at-a-time loading during normal riding.
         */
        val COLOR_MAP_LOADING = Color.rgb(224, 224, 216)
        val COLOR_ATTRIBUTION_BACKGROUND = Color.argb(205, 5, 12, 10)
        val COLOR_BORDER = Color.rgb(31, 53, 46)
        val COLOR_TEXT = Color.rgb(239, 246, 239)
        val COLOR_MUTED = Color.rgb(139, 159, 151)
        val COLOR_PRIMARY = Color.rgb(194, 255, 45)
        val COLOR_PRIMARY_TEXT = Color.rgb(9, 15, 8)
        val COLOR_ROUTE = Color.rgb(51, 218, 229)
        val COLOR_ROUTE_GLOW = Color.argb(65, 51, 218, 229)
        val COLOR_NAV_ROUTE = Color.rgb(201, 245, 58)
        val COLOR_NAV_ROUTE_GLOW = Color.argb(70, 201, 245, 58)
        val COLOR_NAV_ROUTE_CURVY = Color.rgb(255, 184, 77)
        val COLOR_NAV_BANNER = Color.argb(225, 8, 12, 7)
        val COLOR_SUCCESS = Color.rgb(77, 230, 166)
        val COLOR_SUCCESS_GLOW = Color.argb(55, 77, 230, 166)
        val COLOR_WARNING = Color.rgb(255, 184, 77)
        val COLOR_WARNING_GLOW = Color.argb(55, 255, 184, 77)
    }
}

/**
 * Zooms out as speed increases so a faster-moving rider sees more look-ahead
 * road; zooms in when slow/stationary for maneuver detail (parking, tight
 * junctions). Mirrors the speed brackets common to Google Maps/Waze-style
 * navigation, tuned to [baseZoom] at typical city-riding speed.
 */
internal fun zoomForSpeed(speedKph: Float, baseZoom: Int = 16): Int = when {
    speedKph < 10f -> baseZoom + 1
    speedKph < 50f -> baseZoom
    speedKph < 90f -> baseZoom - 1
    else -> baseZoom - 2
}

/**
 * Signed angular distance from [fromDegrees] to [toDegrees], in (-180, 180],
 * taking the short way around the compass rather than the raw arithmetic
 * difference - e.g. 350 -> 10 is +20 (short way through 0/360), not -340.
 * Both inputs may be any real number (not pre-normalized to [0, 360)).
 */
internal fun shortestAngleDeltaDegrees(fromDegrees: Float, toDegrees: Float): Float {
    var delta = (toDegrees - fromDegrees) % 360f
    if (delta > 180f) delta -= 360f
    if (delta < -180f) delta += 360f
    return delta
}
