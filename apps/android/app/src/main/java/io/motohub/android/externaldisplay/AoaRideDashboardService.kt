package io.motohub.android.externaldisplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.motohub.android.R
import io.motohub.android.data.MotorcycleProfileStore
import io.motohub.android.encoding.EncoderProfile
import io.motohub.android.feature.ridedashboard.RideDashboardLayoutController
import io.motohub.android.feature.ridedashboard.RideDashboardMapSource
import io.motohub.android.feature.ridedashboard.RideDashboardMapSourceStore
import io.motohub.android.feature.ridedashboard.RideDashboardRuntime
import io.motohub.android.feature.ridedashboard.RideDashboardRuntimeState
import io.motohub.android.feature.ridedashboard.RideDashboardStreamingPipeline
import io.motohub.android.feature.ridedashboard.nav.NavPoint
import io.motohub.android.feature.ridedashboard.nav.OpenMeteoWeatherClient
import io.motohub.android.feature.ridedashboard.nav.runWeatherUpdateLoop
import io.motohub.android.feature.ridedashboard.widget.DashboardLayoutConfig
import io.motohub.android.feature.ridedashboard.widget.DashboardLayoutStore
import io.motohub.android.feature.ridedashboard.widget.DashboardWidgetRegistry
import io.motohub.android.feature.settings.MotoHubSettings
import io.motohub.android.session.ProjectionEventLog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AoaRideDashboardService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startupRequested = AtomicBoolean(false)
    private val framesSent = AtomicLong(0)
    private var pipeline: RideDashboardStreamingPipeline? = null
    private var weatherJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var stopping = false

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.ride_dashboard_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = getString(R.string.ride_dashboard_channel_description) }
        )
        ProjectionEventLog.record("AOA_DASHBOARD", "AOA Ride Dashboard service created.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSession("AOA Ride Dashboard stopped by the user.")
            return START_NOT_STICKY
        }
        if (!startupRequested.compareAndSet(false, true)) {
            ProjectionEventLog.warning("AOA_DASHBOARD", "Duplicate AOA dashboard start request ignored.")
            return START_NOT_STICKY
        }
        val requestedMapSource = intent?.getStringExtra(EXTRA_MAP_SOURCE)
            ?.let { raw -> RideDashboardMapSource.entries.firstOrNull { it.name == raw } }
            ?: RideDashboardMapSourceStore.load(this)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            createNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )
        serviceScope.launch { startDashboard(requestedMapSource) }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopSession("AOA Ride Dashboard service stopped by Android.")
        super.onDestroy()
    }

    private suspend fun startDashboard(requestedMapSource: RideDashboardMapSource) {
        AoaExternalRuntime.publish(AoaExternalRuntimeState.Starting)
        RideDashboardRuntime.publish(RideDashboardRuntimeState.Starting)
        ProjectionEventLog.record("AOA_DASHBOARD", "Starting USB Ride Dashboard pipeline.")
        try {
            val powerManager = getSystemService(PowerManager::class.java)
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "MotoHub:AoaRideDashboardWakeLock"
            ).apply {
                acquire(2 * 60 * 60 * 1000L)
            }
            val session = AoaAccessorySession.open(this).getOrThrow()
            val activeMotorcycle = MotorcycleProfileStore(this).load()
            val layoutConfig = activeMotorcycle?.ssid
                ?.let { DashboardLayoutStore(this).load(it) }
                ?: DashboardLayoutConfig.DEFAULT
            val quality = MotoHubSettings.videoQuality(this)
            val powerMode = MotoHubSettings.videoPowerMode(this)
            val mapSource = requestedMapSource.takeUnless { it == RideDashboardMapSource.ANDROID_AUTO }
                ?: RideDashboardMapSource.OPEN_STREET_MAP.also {
                    ProjectionEventLog.warning(
                        "AOA_DASHBOARD",
                        "Embedded Android Auto is disabled for USB dashboard; using OpenStreetMap instead."
                    )
                }
            val activePipeline = RideDashboardStreamingPipeline(
                context = this,
                encoderProfile = EncoderProfile(
                    width = EXTERNAL_WIDTH,
                    height = EXTERNAL_HEIGHT,
                    frameRate = powerMode.frameRate,
                    bitRate = quality.bitrateFor(EXTERNAL_BITRATE)
                ),
                tBoxLabel = "USB HEAD UNIT",
                motorcyclePhotoPath = activeMotorcycle?.photoPath,
                layoutController = RideDashboardLayoutController(),
                mapSource = mapSource,
                embeddedAndroidAuto = null,
                cellularOnlyMaps = false,
                leftWidget = DashboardWidgetRegistry.forId(layoutConfig.leftWidgetId)
                    ?: DashboardWidgetRegistry.forId(DashboardLayoutConfig.DEFAULT.leftWidgetId)!!,
                rightWidget = DashboardWidgetRegistry.forId(layoutConfig.rightWidgetId)
                    ?: DashboardWidgetRegistry.forId(DashboardLayoutConfig.DEFAULT.rightWidgetId)!!,
                sink = AoaAccessoryVideoSink(session),
                onFrameAccepted = {
                    val accepted = framesSent.incrementAndGet()
                    if (accepted == 1L || accepted % FRAME_LOG_INTERVAL == 0L) {
                        ProjectionEventLog.record("AOA_DASHBOARD", "Dashboard frames sent over USB: $accepted.")
                    }
                },
                onSinkRejected = {
                    if (!stopping) fail("The USB head unit no longer accepts dashboard frames.")
                },
                onFailure = { failure ->
                    if (!stopping) fail("USB Ride Dashboard stopped: ${failure.message}")
                }
            )
            activePipeline.start()
            pipeline = activePipeline
            weatherJob = serviceScope.launch {
                val weatherClient = OpenMeteoWeatherClient(this@AoaRideDashboardService, cellularOnly = false)
                runWeatherUpdateLoop(weatherClient) {
                    activePipeline.telemetryProvider?.snapshot()?.position?.let {
                        NavPoint(it.latitude, it.longitude)
                    }
                }
            }
            AoaExternalRuntime.publish(AoaExternalRuntimeState.Streaming)
            RideDashboardRuntime.publish(RideDashboardRuntimeState.Streaming)
            ProjectionEventLog.record("AOA_DASHBOARD", "USB Ride Dashboard is streaming.")
        } catch (failure: Throwable) {
            fail("USB Ride Dashboard did not start: ${failure.message}")
        }
    }

    private fun fail(message: String) {
        if (stopping) return
        ProjectionEventLog.error("AOA_DASHBOARD", message)
        AoaExternalRuntime.publish(AoaExternalRuntimeState.Failed(message))
        RideDashboardRuntime.publish(RideDashboardRuntimeState.Failed(message))
        stopSession(message)
    }

    @Synchronized
    private fun stopSession(reason: String) {
        if (stopping) return
        stopping = true
        weatherJob?.cancel()
        weatherJob = null
        pipeline?.stop()
        pipeline = null
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
        if (AoaExternalRuntime.state.value !is AoaExternalRuntimeState.Failed) {
            AoaExternalRuntime.publish(AoaExternalRuntimeState.Stopped(reason))
        }
        if (RideDashboardRuntime.state.value !is RideDashboardRuntimeState.Failed) {
            RideDashboardRuntime.publish(RideDashboardRuntimeState.Stopped(reason))
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.ride_dashboard_notification_title))
            .setContentText("Ride Dashboard on external display via USB")
            .setOngoing(true)
            .addAction(
                R.drawable.ic_notification,
                getString(R.string.stop_ride_dashboard),
                PendingIntent.getService(
                    this,
                    0,
                    Intent(this, AoaRideDashboardService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()

    companion object {
        private const val CHANNEL_ID = "aoa_ride_dashboard_v1"
        private const val NOTIFICATION_ID = 4302
        private const val ACTION_STOP = "io.motohub.android.action.STOP_AOA_RIDE_DASHBOARD"
        private const val EXTRA_MAP_SOURCE = "aoa_ride_dashboard_map_source"
        private const val EXTERNAL_WIDTH = 1280
        private const val EXTERNAL_HEIGHT = 720
        private const val EXTERNAL_BITRATE = 4_194_304
        private const val FRAME_LOG_INTERVAL = 120L

        fun start(
            context: Context,
            mapSource: RideDashboardMapSource = RideDashboardMapSourceStore.load(context)
        ) {
            val intent = Intent(context, AoaRideDashboardService::class.java).apply {
                putExtra(EXTRA_MAP_SOURCE, mapSource.name)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, AoaRideDashboardService::class.java).setAction(ACTION_STOP)
            )
        }

        fun isAccessoryConnected(context: Context): Boolean =
            AoaAccessorySession.isAccessoryConnected(context)
    }
}
