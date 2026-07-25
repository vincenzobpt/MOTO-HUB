package io.motohub.android.feature.trips

import io.motohub.android.i18n.motoHubText

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import io.motohub.android.i18n.motoHubText
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.motohub.android.MainActivity
import io.motohub.android.R
import io.motohub.android.feature.settings.MotoHubSettings
import io.motohub.android.session.ProjectionEventLog

/** Records a resilient GPS track independently from any projection renderer. */
class TripRecordingService : Service(), LocationListener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val store by lazy { TripStore(this) }
    private val locationManager by lazy { getSystemService(LocationManager::class.java) }
    private val pendingPoints = ArrayList<TripTrackPoint>(POINT_BATCH_SIZE)
    private var activeTrip: TripSummary? = null
    private var accumulator: TripAccumulator? = null
    private var autoOwner: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotificationElapsedMillis = 0L
    private var stopping = false

    private val periodicFlush = object : Runnable {
        override fun run() {
            flushProgress()
            if (!stopping && activeTrip != null) mainHandler.postDelayed(this, FLUSH_INTERVAL_MILLIS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Trip recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = motoHubText("GPS ride recording and trip statistics") }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SAVE -> finish(save = true, reason = "Stopped by the rider")
            ACTION_DISCARD -> finish(save = false, reason = "Discarded by the rider")
            ACTION_STOP_AUTO -> {
                val requestedOwner = intent.getStringExtra(EXTRA_OWNER)
                if (requestedOwner != null && requestedOwner == autoOwner) {
                    finish(save = true, reason = "$requestedOwner projection ended")
                }
            }
            ACTION_START_MANUAL, ACTION_START_AUTO -> startOrResume(intent)
            null -> resumeAfterProcessRestart()
        }
        return if (activeTrip != null) START_STICKY else START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onLocationChanged(location: Location) {
        val activeAccumulator = accumulator ?: return
        val point = activeAccumulator.accept(
            TripLocationSample(
                latitude = location.latitude,
                longitude = location.longitude,
                timestampMillis = location.time.takeIf { it > 0L } ?: System.currentTimeMillis(),
                elapsedRealtimeNanos = location.elapsedRealtimeNanos,
                speedMetersPerSecond = location.speed.takeIf { location.hasSpeed() },
                accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() },
                altitudeMeters = location.altitude.takeIf { location.hasAltitude() }
            )
        )
        if (point != null) {
            pendingPoints += point
            TripRecordingRuntime.appendTrack(point)
        }
        publish(activeAccumulator.snapshot())
        if (pendingPoints.size >= POINT_BATCH_SIZE) flushProgress()
    }

    override fun onProviderDisabled(provider: String) {
        ProjectionEventLog.warning("TRIPS", "Location provider disabled while recording: $provider.")
    }

    private fun startOrResume(intent: Intent) {
        if (activeTrip != null) {
            ProjectionEventLog.debug("TRIPS", "Trip start ignored because a recording is already active.")
            return
        }
        if (!hasLocationPermission()) {
            fail("Precise location permission is required to record a trip.")
            return
        }
        val restored = store.activeTrip()
        val source = if (intent.action == ACTION_START_AUTO) {
            TripRecordingSource.fromStorage(
                intent.getStringExtra(EXTRA_SOURCE) ?: TripRecordingSource.ANDROID_AUTO.name
            )
        } else {
            TripRecordingSource.MANUAL
        }
        val summary = restored?.first ?: store.createTrip(
            motorcycleId = intent.getStringExtra(EXTRA_MOTORCYCLE_ID),
            source = source
        )
        autoOwner = ownerFor(summary.source)
        activate(summary, restored?.second)
    }

    private fun resumeAfterProcessRestart() {
        if (activeTrip != null) return
        val restored = store.activeTrip() ?: run {
            stopSelf()
            return
        }
        if (!hasLocationPermission()) {
            fail("Trip recovery stopped because location permission is no longer available.")
            return
        }
        autoOwner = ownerFor(restored.first.source)
        activate(restored.first, restored.second)
        ProjectionEventLog.record("TRIPS", "Recovered active trip ${restored.first.id} after process restart.")
    }

    private fun activate(summary: TripSummary, lastPoint: TripTrackPoint?) {
        activeTrip = summary
        accumulator = TripAccumulator(summary.startedAtMillis, summary, lastPoint)
        TripRecordingRuntime.beginTrack(store.trackPoints(summary.id))
        stopping = false
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                createNotification("Acquiring GPS position"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
            requestLocationUpdates()
            acquireWakeLock()
            mainHandler.removeCallbacks(periodicFlush)
            mainHandler.postDelayed(periodicFlush, FLUSH_INTERVAL_MILLIS)
            publish(checkNotNull(accumulator).snapshot())
            ProjectionEventLog.record(
                "TRIPS",
                "Trip recording started: id=${summary.id}, source=${summary.source.name}, " +
                    "motorcycleId=${summary.motorcycleId}."
            )
        } catch (failure: Throwable) {
            fail("Trip recording could not start: ${failure.message}", failure)
        }
    }

    private fun requestLocationUpdates() {
        check(
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        ) { "Precise location permission was revoked" }
        val request = LocationRequest.Builder(LOCATION_INTERVAL_MILLIS)
            .setMinUpdateIntervalMillis(MIN_LOCATION_INTERVAL_MILLIS)
            .setMinUpdateDistanceMeters(0f)
            .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
            .build()
        val availableProviders = locationManager.allProviders.toSet()
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER)
            .filter(availableProviders::contains)
        check(providers.isNotEmpty()) { "No Android location provider is available" }
        providers.forEach { provider ->
            locationManager.requestLocationUpdates(
                provider,
                request,
                ContextCompat.getMainExecutor(this),
                this
            )
        }
    }

    private fun flushProgress() {
        val trip = activeTrip ?: return
        val activeAccumulator = accumulator ?: return
        val batch = pendingPoints.toList()
        val snapshot = activeAccumulator.snapshot()
        runCatching { store.persistProgress(trip.id, batch, snapshot) }
            .onSuccess { pendingPoints.clear() }
            .onFailure { failure ->
                ProjectionEventLog.error("TRIPS", "Unable to persist trip progress.", failure)
            }
    }

    private fun finish(save: Boolean, reason: String) {
        val trip = activeTrip ?: run {
            stopSelf()
            return
        }
        stopping = true
        mainHandler.removeCallbacks(periodicFlush)
        val snapshot = checkNotNull(accumulator).snapshot()
        val keep = save && isMeaningful(snapshot)
        val finalized = runCatching {
            store.finalizeTrip(
                tripId = trip.id,
                points = pendingPoints.toList(),
                snapshot = snapshot,
                endedAtMillis = System.currentTimeMillis(),
                keep = keep
            )
        }.onFailure { ProjectionEventLog.error("TRIPS", "Unable to finalize trip ${trip.id}.", it) }
            .isSuccess
        if (!finalized) {
            stopping = false
            mainHandler.postDelayed(periodicFlush, FLUSH_INTERVAL_MILLIS)
            publish(snapshot)
            return
        }
        runCatching { locationManager.removeUpdates(this) }
        ProjectionEventLog.record(
            "TRIPS",
            "Trip recording finished: id=${trip.id}, saved=$keep, " +
                "distance=${snapshot.distanceMeters.toInt()}m, points=${snapshot.pointCount}, reason=$reason."
        )
        pendingPoints.clear()
        if (!keep) TripRecordingRuntime.clearTrack()
        activeTrip = null
        accumulator = null
        autoOwner = null
        releaseWakeLock()
        TripRecordingRuntime.publish(
            TripRecordingState.Finished(
                savedTripId = trip.id.takeIf { keep },
                distanceMeters = snapshot.distanceMeters,
                pointCount = snapshot.pointCount
            )
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(periodicFlush)
        runCatching { locationManager.removeUpdates(this) }
        if (activeTrip != null) flushProgress()
        releaseWakeLock()
        if (TripRecordingRuntime.state.value is TripRecordingState.Recording) {
            TripRecordingRuntime.publish(TripRecordingState.Idle)
        }
        super.onDestroy()
    }

    private fun publish(snapshot: TripRecordingSnapshot) {
        val trip = activeTrip ?: return
        TripRecordingRuntime.publish(
            TripRecordingState.Recording(
                tripId = trip.id,
                source = trip.source,
                startedAtMillis = trip.startedAtMillis,
                speedKmh = snapshot.currentSpeedMetersPerSecond * 3.6f,
                distanceMeters = snapshot.distanceMeters,
                movingTimeMillis = snapshot.movingTimeMillis,
                elapsedTimeMillis = snapshot.elapsedTimeMillis,
                maxSpeedKmh = snapshot.maxSpeedMetersPerSecond * 3.6f,
                pointCount = snapshot.pointCount,
                accuracyMeters = snapshot.accuracyMeters,
                hasFix = snapshot.hasFix
            )
        )
        val nowElapsed = SystemClock.elapsedRealtime()
        if (lastNotificationElapsedMillis == 0L ||
            nowElapsed - lastNotificationElapsedMillis >= NOTIFICATION_UPDATE_INTERVAL_MILLIS
        ) {
            lastNotificationElapsedMillis = nowElapsed
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                createNotification(
                    "${formatTripDistance(snapshot.distanceMeters, MotoHubSettings.distanceUnits(this))} · " +
                        "${formatTripDuration(snapshot.movingTimeMillis)} moving"
                )
            )
        }
    }

    private fun createNotification(content: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(motoHubText("Recording this ride"))
        .setContentText(content)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .addAction(
            R.drawable.ic_notification,
            "Finish",
            PendingIntent.getService(
                this,
                1,
                Intent(this, TripRecordingService::class.java).setAction(ACTION_STOP_SAVE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .build()

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MOTO-HUB:TripRecorder")
            .apply { acquire(WAKE_LOCK_TIMEOUT_MILLIS) }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf(PowerManager.WakeLock::isHeld)?.release() }
        wakeLock = null
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun fail(message: String, failure: Throwable? = null) {
        if (failure == null) ProjectionEventLog.error("TRIPS", message)
        else ProjectionEventLog.error("TRIPS", message, failure)
        TripRecordingRuntime.publish(TripRecordingState.Failed(message))
        activeTrip = null
        accumulator = null
        stopSelf()
    }

    private fun isMeaningful(snapshot: TripRecordingSnapshot): Boolean =
        snapshot.distanceMeters >= MIN_SAVED_DISTANCE_METERS &&
            snapshot.movingTimeMillis >= MIN_SAVED_MOVING_TIME_MILLIS &&
            snapshot.pointCount >= MIN_SAVED_POINTS

    companion object {
        private const val CHANNEL_ID = "moto_hub_trip_recording"
        private const val NOTIFICATION_ID = 4401
        private const val ACTION_START_MANUAL = "io.motohub.android.action.START_TRIP_MANUAL"
        private const val ACTION_START_AUTO = "io.motohub.android.action.START_TRIP_AUTO"
        private const val ACTION_STOP_SAVE = "io.motohub.android.action.STOP_TRIP_SAVE"
        private const val ACTION_STOP_AUTO = "io.motohub.android.action.STOP_TRIP_AUTO"
        private const val ACTION_DISCARD = "io.motohub.android.action.DISCARD_TRIP"
        private const val EXTRA_MOTORCYCLE_ID = "motorcycle_id"
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_OWNER = "owner"
        private const val OWNER_ANDROID_AUTO = "android_auto"
        private const val OWNER_MIRRORING = "mirroring"
        private const val OWNER_RIDE_DASHBOARD = "ride_dashboard"
        private const val OWNER_NAVIGATION = "navigation"
        private const val LOCATION_INTERVAL_MILLIS = 1_000L
        private const val MIN_LOCATION_INTERVAL_MILLIS = 500L
        private const val FLUSH_INTERVAL_MILLIS = 8_000L
        private const val NOTIFICATION_UPDATE_INTERVAL_MILLIS = 5_000L
        private const val POINT_BATCH_SIZE = 8
        private const val MIN_SAVED_DISTANCE_METERS = 100.0
        private const val MIN_SAVED_MOVING_TIME_MILLIS = 15_000L
        private const val MIN_SAVED_POINTS = 2
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 8L * 60L * 60L * 1_000L

        private fun ownerFor(source: TripRecordingSource): String? = when (source) {
            TripRecordingSource.ANDROID_AUTO -> OWNER_ANDROID_AUTO
            TripRecordingSource.MIRRORING -> OWNER_MIRRORING
            TripRecordingSource.RIDE_DASHBOARD -> OWNER_RIDE_DASHBOARD
            TripRecordingSource.NAVIGATION -> OWNER_NAVIGATION
            TripRecordingSource.MANUAL -> null
        }

        fun startManual(context: Context, motorcycleId: String?) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TripRecordingService::class.java)
                    .setAction(ACTION_START_MANUAL)
                    .putExtra(EXTRA_MOTORCYCLE_ID, motorcycleId)
            )
        }

        fun startAuto(context: Context, motorcycleId: String?, source: TripRecordingSource) {
            val owner = ownerFor(source) ?: return startManual(context, motorcycleId)
            ContextCompat.startForegroundService(
                context,
                Intent(context, TripRecordingService::class.java)
                    .setAction(ACTION_START_AUTO)
                    .putExtra(EXTRA_MOTORCYCLE_ID, motorcycleId)
                    .putExtra(EXTRA_SOURCE, source.name)
                    .putExtra(EXTRA_OWNER, owner)
            )
        }

        fun stopAndSave(context: Context) {
            context.startService(Intent(context, TripRecordingService::class.java).setAction(ACTION_STOP_SAVE))
        }

        fun discard(context: Context) {
            context.startService(Intent(context, TripRecordingService::class.java).setAction(ACTION_DISCARD))
        }

        fun stopAuto(context: Context, source: TripRecordingSource) {
            val owner = ownerFor(source) ?: return
            runCatching {
                context.startService(
                    Intent(context, TripRecordingService::class.java)
                        .setAction(ACTION_STOP_AUTO)
                        .putExtra(EXTRA_OWNER, owner)
                )
            }.onFailure { failure ->
                ProjectionEventLog.warning(
                    "TRIPS",
                    "Unable to deliver automatic trip stop for $owner.",
                    failure
                )
            }
        }
    }
}
