package io.motohub.android.feature.ridedashboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import android.util.LruCache
import io.motohub.android.BuildConfig
import io.motohub.android.session.ProjectionEventLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.tan

data class OsmWorldPixel(val x: Double, val y: Double)
data class OsmGeoPoint(val latitude: Double, val longitude: Double)

internal fun osmWorldPixel(latitude: Double, longitude: Double, zoom: Int): OsmWorldPixel {
    val safeLatitude = latitude.coerceIn(-MAX_MERCATOR_LATITUDE, MAX_MERCATOR_LATITUDE)
    val safeLongitude = ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    val latitudeRadians = Math.toRadians(safeLatitude)
    val worldSize = TILE_SIZE * 2.0.pow(zoom)
    return OsmWorldPixel(
        x = (safeLongitude + 180.0) / 360.0 * worldSize,
        y = (1.0 - ln(tan(latitudeRadians) + 1.0 / kotlin.math.cos(latitudeRadians)) / Math.PI) /
            2.0 * worldSize
    )
}

internal fun osmGeoPoint(worldX: Double, worldY: Double, zoom: Int): OsmGeoPoint {
    val worldSize = TILE_SIZE * 2.0.pow(zoom)
    val longitude = worldX / worldSize * 360.0 - 180.0
    val mercator = Math.PI * (1.0 - 2.0 * worldY / worldSize)
    val latitude = Math.toDegrees(kotlin.math.atan(kotlin.math.sinh(mercator)))
    return OsmGeoPoint(
        latitude = latitude.coerceIn(-MAX_MERCATOR_LATITUDE, MAX_MERCATOR_LATITUDE),
        longitude = ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    )
}

/** Fetches only currently visible OSM tiles and binds HTTP to the cellular network. */
class OpenStreetMapTileProvider(
    context: Context,
    private val style: OsmBaseStyle = OsmBaseStyle.CARTO_VOYAGER,
    private val cellularOnly: Boolean = true,
    private val asynchronousDiskReads: Boolean = false,
    private val onTileAvailable: (() -> Unit)? = null
) {
    private val applicationContext = context.applicationContext
    private val connectivityManager = applicationContext.getSystemService(ConnectivityManager::class.java)
    private val cellularNetwork = AtomicReference<Network?>(null)
    private val callbackRegistered = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val mapReadyLogged = AtomicBoolean(false)
    private val lastFailureLogMillis = AtomicLong(Long.MIN_VALUE)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val retryAfter = ConcurrentHashMap<String, Long>()
    private val executor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "MotoHubOsmTile").apply { isDaemon = true }
    }
    private val cacheDirectory = File(applicationContext.cacheDir, "${CACHE_DIRECTORY_PREFIX}_${style.cacheKey}")
    private val memoryCache = object : LruCache<String, Bitmap>(MEMORY_CACHE_KILOBYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = max(1, value.byteCount / 1_024)
    }
    private val cellularCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            cellularNetwork.set(network)
            retryAfter.clear()
            ProjectionEventLog.record("RIDE_MAP", "Cellular network is available for OpenStreetMap tiles.")
        }

        override fun onLost(network: Network) {
            cellularNetwork.compareAndSet(network, null)
            ProjectionEventLog.warning("RIDE_MAP", "Cellular network for OpenStreetMap tiles was lost.")
        }
    }

    fun start() {
        if (callbackRegistered.get() || stopped.get()) return
        cacheDirectory.mkdirs()
        if (staleCacheCleaned.compareAndSet(false, true)) {
            executor.execute {
                runCatching {
                    File(applicationContext.cacheDir, OLD_DARK_CACHE_DIRECTORY_NAME).deleteRecursively()
                }
                runCatching {
                    File(applicationContext.cacheDir, OLD_LIGHT_CACHE_DIRECTORY_NAME).deleteRecursively()
                }
            }
        }
        if (!cellularOnly) return
        runCatching {
            connectivityManager.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                cellularCallback
            )
            callbackRegistered.set(true)
        }.onFailure {
            ProjectionEventLog.warning(
                "RIDE_MAP",
                "Unable to observe a cellular network for OpenStreetMap tiles.",
                it
            )
        }
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        if (callbackRegistered.compareAndSet(true, false)) {
            runCatching { connectivityManager.unregisterNetworkCallback(cellularCallback) }
        }
        executor.shutdownNow()
        memoryCache.evictAll()
        inFlight.clear()
        retryAfter.clear()
    }

    fun hasCellularNetwork(): Boolean = !cellularOnly || cellularNetwork.get() != null

    fun tile(zoom: Int, rawX: Int, y: Int): Bitmap? {
        if (stopped.get()) return null
        val tileCount = 1 shl zoom
        if (y !in 0 until tileCount) return null
        val x = ((rawX % tileCount) + tileCount) % tileCount
        val key = "$zoom-$x-$y"
        memoryCache.get(key)?.let { return it }

        val cachedFile = cacheFile(key)
        if (cachedFile.isFile) {
            if (asynchronousDiskReads) {
                scheduleDiskLoad(zoom, x, y, key, cachedFile)
                return null
            }
            decode(cachedFile)?.let { bitmap ->
                memoryCache.put(key, bitmap)
                if (System.currentTimeMillis() - cachedFile.lastModified() >= CACHE_TTL_MILLIS) {
                    scheduleDownload(zoom, x, y, key, cachedFile)
                }
                return bitmap
            }
            cachedFile.delete()
        }
        scheduleDownload(zoom, x, y, key, cachedFile)
        return null
    }

    private fun scheduleDiskLoad(zoom: Int, x: Int, y: Int, key: String, file: File) {
        if (stopped.get()) return
        if (!inFlight.add(key)) return
        executor.execute {
            val stale = System.currentTimeMillis() - file.lastModified() >= CACHE_TTL_MILLIS
            try {
                val bitmap = decode(file)
                if (bitmap == null) {
                    file.delete()
                } else {
                    memoryCache.put(key, bitmap)
                    if (!stopped.get()) onTileAvailable?.invoke()
                }
            } finally {
                inFlight.remove(key)
                if (!file.isFile || stale) scheduleDownload(zoom, x, y, key, file)
            }
        }
    }

    private fun scheduleDownload(zoom: Int, x: Int, y: Int, key: String, destination: File) {
        if (stopped.get()) return
        val now = SystemClock.elapsedRealtime()
        if (now < (retryAfter[key] ?: 0L)) return
        val network = cellularNetwork.get()
        if (cellularOnly && network == null) {
            retryAfter[key] = now + NO_NETWORK_RETRY_MILLIS
            return
        }
        if (!inFlight.add(key)) return
        executor.execute {
            try {
                download(network, zoom, x, y, key, destination)
                retryAfter.remove(key)
            } catch (failure: Throwable) {
                retryAfter[key] = SystemClock.elapsedRealtime() + DOWNLOAD_RETRY_MILLIS
                logDownloadFailure(failure)
            } finally {
                inFlight.remove(key)
            }
        }
    }

    private fun download(
        network: Network?,
        zoom: Int,
        x: Int,
        y: Int,
        key: String,
        destination: File
    ) {
        val subdomain = ('a' + ((x + y) % 4)).toChar()
        val url = when (style) {
            OsmBaseStyle.CARTO_VOYAGER ->
                URL("https://${subdomain}.basemaps.cartocdn.com/rastertiles/voyager/$zoom/$x/$y.png")
            OsmBaseStyle.CARTO_POSITRON ->
                URL("https://${subdomain}.basemaps.cartocdn.com/light_all/$zoom/$x/$y.png")
            OsmBaseStyle.CARTO_DARK_MATTER ->
                URL("https://${subdomain}.basemaps.cartocdn.com/dark_all/$zoom/$x/$y.png")
            OsmBaseStyle.OSM_STANDARD ->
                URL("https://tile.openstreetmap.org/$zoom/$x/$y.png")
        }
        val connection = (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.useCaches = true
            connection.instanceFollowRedirects = true
            connection.setRequestProperty(
                "User-Agent",
                "MOTO-HUB/${BuildConfig.VERSION_NAME} (+https://github.com/vincenzobpt/MOTO-HUB)"
            )
            if (destination.isFile) connection.ifModifiedSince = destination.lastModified()
            when (connection.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> {
                    destination.setLastModified(System.currentTimeMillis())
                    return
                }
                HttpURLConnection.HTTP_OK -> Unit
                else -> error("OpenStreetMap HTTP ${connection.responseCode}")
            }
            check(connection.contentType?.startsWith("image/png") != false) {
                "OpenStreetMap returned ${connection.contentType}"
            }
            val bytes = connection.inputStream.use { it.readBytes() }
            check(bytes.size in 1..MAX_TILE_BYTES) { "Invalid OpenStreetMap tile size: ${bytes.size}" }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: error("OpenStreetMap tile is not a valid bitmap")
            cacheDirectory.mkdirs()
            synchronized(DISK_CACHE_LOCK) {
                val temporary = File(cacheDirectory, ".$key-${System.nanoTime()}.tmp")
                temporary.writeBytes(bytes)
                if (!temporary.renameTo(destination)) {
                    destination.writeBytes(bytes)
                    temporary.delete()
                }
                destination.setLastModified(System.currentTimeMillis())
                trimDiskCache()
            }
            memoryCache.put(key, bitmap)
            if (!stopped.get()) onTileAvailable?.invoke()
            if (mapReadyLogged.compareAndSet(false, true)) {
                ProjectionEventLog.record("RIDE_MAP", "OpenStreetMap tiles are available to the dashboard renderer.")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun decode(file: File): Bitmap? = BitmapFactory.decodeFile(file.absolutePath)

    private fun cacheFile(key: String): File = File(cacheDirectory, "$key.png")

    private fun trimDiskCache() {
        val files = cacheDirectory.listFiles { file -> file.extension == "png" }.orEmpty()
        if (files.size <= MAX_DISK_TILES) return
        files.sortedBy(File::lastModified)
            .take(files.size - MAX_DISK_TILES)
            .forEach(File::delete)
    }

    private fun logDownloadFailure(failure: Throwable) {
        val now = SystemClock.elapsedRealtime()
        val previous = lastFailureLogMillis.get()
        if (previous != Long.MIN_VALUE && now - previous < FAILURE_LOG_INTERVAL_MILLIS) return
        if (lastFailureLogMillis.compareAndSet(previous, now)) {
            ProjectionEventLog.warning(
                "RIDE_MAP",
                "OpenStreetMap tile download failed: ${failure.message}",
                failure
            )
        }
    }

    private companion object {
        // Each style has its own cache namespace, so changing the style never shows
        // stale tiles from the previous basemap while the new style is downloading.
        const val CACHE_DIRECTORY_PREFIX = "osm_visible_tiles"
        const val OLD_DARK_CACHE_DIRECTORY_NAME = "osm_visible_tiles"
        const val OLD_LIGHT_CACHE_DIRECTORY_NAME = "osm_visible_tiles_light"
        val staleCacheCleaned = AtomicBoolean(false)
        const val MEMORY_CACHE_KILOBYTES = 24 * 1_024
        const val MAX_DISK_TILES = 256
        const val MAX_TILE_BYTES = 2 * 1_024 * 1_024
        const val CACHE_TTL_MILLIS = 7L * 24L * 60L * 60L * 1_000L
        const val NO_NETWORK_RETRY_MILLIS = 5_000L
        const val DOWNLOAD_RETRY_MILLIS = 15_000L
        const val FAILURE_LOG_INTERVAL_MILLIS = 30_000L
        const val CONNECT_TIMEOUT_MILLIS = 5_000
        const val READ_TIMEOUT_MILLIS = 8_000
        val DISK_CACHE_LOCK = Any()
    }
}

private const val TILE_SIZE = 256.0
private const val MAX_MERCATOR_LATITUDE = 85.05112878
