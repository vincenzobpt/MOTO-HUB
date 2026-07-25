package io.motohub.android.feature.ridedashboard

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import io.motohub.android.session.ProjectionEventLog
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.snapshotter.MapSnapshot
import org.maplibre.android.snapshotter.MapSnapshotter
import org.maplibre.android.module.http.HttpRequestImpl
import org.maplibre.android.maps.Style
import java.util.concurrent.atomic.AtomicBoolean
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer

/**
 * Produces MapLibre vector-map snapshots for the encoder renderer.
 *
 * The Ride Dashboard owns an encoder thread rather than a Compose View, so a native MapView
 * cannot be embedded directly. MapSnapshotter renders the MapLibre style off-screen and the
 * latest completed bitmap is consumed by the TFT Canvas renderer.
 */
class MapLibreSnapshotProvider(
    context: Context,
    private val cellularOnly: Boolean = true,
    private val onSnapshotAvailable: (() -> Unit)? = null
) {
    private val applicationContext = context.applicationContext
    private val connectivityManager = applicationContext.getSystemService(ConnectivityManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stopped = AtomicBoolean(true)
    @Volatile private var cellularNetwork: Network? = null
    @Volatile private var cellularCallbackRegistered = false
    private val cellularCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            cellularNetwork = network
            lastRequestKey = null
            installHttpClient(network)
        }

        override fun onLost(network: Network) {
            if (cellularNetwork == network) {
                cellularNetwork = null
                HttpRequestImpl.setOkHttpClient(null)
            }
        }
    }
    private var snapshotter: MapSnapshotter? = null
    private var lastRequestKey: String? = null
    private var requestSerial = 0L

    @Volatile
    private var latestBitmap: Bitmap? = null

    fun start() {
        stopped.set(false)
        mainHandler.post {
            runCatching {
                if (!MapLibre.hasInstance()) MapLibre.getInstance(applicationContext)
            }.onFailure {
                ProjectionEventLog.error("RIDE_MAP", "Unable to initialize MapLibre.", it)
            }
        }
        if (!cellularOnly || cellularCallbackRegistered) return
        runCatching {
            connectivityManager.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                cellularCallback
            )
            cellularCallbackRegistered = true
            connectivityManager.allNetworks.firstOrNull { network ->
                connectivityManager.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
            }?.let { network ->
                cellularNetwork = network
                installHttpClient(network)
            }
        }.onFailure {
            ProjectionEventLog.warning("RIDE_MAP", "Unable to observe cellular network for MapLibre.", it)
        }
    }

    fun stop() {
        if (stopped.getAndSet(true)) return
        mainHandler.post {
            requestSerial++
            snapshotter?.cancel()
            snapshotter = null
            latestBitmap = null
            lastRequestKey = null
            if (cellularCallbackRegistered) {
                runCatching { connectivityManager.unregisterNetworkCallback(cellularCallback) }
                cellularCallbackRegistered = false
            }
            HttpRequestImpl.setOkHttpClient(null)
        }
    }

    fun bitmap(): Bitmap? = latestBitmap

    fun request(
        latitude: Double,
        longitude: Double,
        zoom: Int,
        bearingDegrees: Float,
        tiltDegrees: Float,
        width: Int,
        height: Int,
        settings: MapLibreMapSettings
    ) {
        if (stopped.get() || width <= 0 || height <= 0) return
        val safeLatitude = latitude.coerceIn(-85.0, 85.0)
        val safeLongitude = ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        val safeBearing = ((bearingDegrees % 360f) + 360f) % 360f
        val safeTilt = tiltDegrees.coerceIn(0f, 60f)
        val key = "${safeLatitude.roundedKey()}:${safeLongitude.roundedKey()}:$zoom:" +
            "${safeBearing.roundedKey()}:${safeTilt.roundedKey()}:$width:$height:" +
            "${settings.baseStyle.name}:${settings.labelScale.name}"
        if (key == lastRequestKey) return
        lastRequestKey = key
        mainHandler.post {
            if (stopped.get()) return@post
            val serial = ++requestSerial
            snapshotter?.cancel()
            val options = MapSnapshotter.Options(width.coerceAtMost(MAX_SNAPSHOT_DIMENSION), height.coerceAtMost(MAX_SNAPSHOT_DIMENSION))
                .withStyleBuilder(
                    Style.Builder().fromUri(
                        "https://tiles.openfreemap.org/styles/${settings.baseStyle.stylePath}"
                    )
                )
                .withCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(safeLatitude, safeLongitude))
                        .zoom(zoom.toDouble())
                        .bearing(safeBearing.toDouble())
                        .tilt(safeTilt.toDouble())
                        .build()
            )
            val active = MapSnapshotter(applicationContext, options)
            snapshotter = active
            active.setObserver(object : MapSnapshotter.Observer {
                override fun onDidFinishLoadingStyle() {
                    applyLabelScale(active, settings.labelScale)
                }

                override fun onStyleImageMissing(imageId: String) = Unit
            })
            active.start(
                object : MapSnapshotter.SnapshotReadyCallback {
                    override fun onSnapshotReady(snapshot: MapSnapshot) {
                        if (serial != requestSerial || stopped.get()) return
                        latestBitmap = snapshot.bitmap
                        onSnapshotAvailable?.invoke()
                    }
                },
                { failure ->
                    if (serial == requestSerial && !stopped.get()) {
                        lastRequestKey = null
                        ProjectionEventLog.warning(
                            "RIDE_MAP",
                            "MapLibre snapshot failed ($failure); waiting for the next camera update."
                        )
                    }
                }
            )
        }
    }

    private fun Double.roundedKey(): String = "%.4f".format(java.util.Locale.US, this)
    private fun Float.roundedKey(): String = "%.1f".format(java.util.Locale.US, this)

    private fun applyLabelScale(snapshotter: MapSnapshotter, scale: MapLabelScale) {
        // OpenFreeMap styles keep the main symbol layers stable across Liberty,
        // Bright, Dark and Fiord. Set explicit sizes after the style loads so
        // the preference affects road, place, POI and water labels alike.
        LABEL_LAYER_SIZES.forEach { (layerId, baseSize) ->
            (snapshotter.getLayer(layerId) as? SymbolLayer)?.setProperties(
                textSize(baseSize * scale.factor)
            )
        }
    }

    private fun installHttpClient(network: Network) {
        // MapLibre's native file source uses a process-wide OkHttp factory. Supplying the
        // cellular socket factory keeps vector tiles reachable while the app is process-bound
        // to the internet-less T-Box Wi-Fi network.
        HttpRequestImpl.setOkHttpClient(
            OkHttpClient.Builder()
                .socketFactory(network.socketFactory)
                .build()
        )
    }

    private companion object {
        const val MAX_SNAPSHOT_DIMENSION = 1024
        val LABEL_LAYER_SIZES = mapOf(
            "country-label" to 13f,
            "state-label" to 12f,
            "place-label" to 12f,
            "place-label-city" to 12f,
            "place-label-town" to 11f,
            "place-label-village" to 10f,
            "road-label" to 10f,
            "road-label-minor" to 9f,
            "road-label-major" to 10f,
            "road-label-highway" to 11f,
            "road-shield" to 10f,
            "poi-label" to 9f,
            "transit-label" to 9f,
            "waterway-label" to 9f,
            "natural-label" to 9f
        )
    }
}
