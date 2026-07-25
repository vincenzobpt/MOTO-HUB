package io.motohub.android.feature.navigation

import android.content.Context
import android.os.SystemClock
import io.motohub.android.feature.ridedashboard.nav.NavPoint
import io.motohub.android.feature.ridedashboard.nav.withCellularNetwork
import io.motohub.android.session.ProjectionEventLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

enum class MapPoiCategory(
    val label: String,
    val symbol: String,
    val queryClause: String
) {
    FUEL("Fuel", "F", "nwr[\"amenity\"=\"fuel\"]"),
    FOOD("Food", "E", "nwr[\"amenity\"~\"restaurant|cafe|fast_food\"]"),
    BAKERY("Bakery", "B", "nwr[\"shop\"=\"bakery\"]"),
    BAR("Bars & pubs", "B", "nwr[\"amenity\"~\"bar|pub\"]"),
    PARKING("Parking", "P", "nwr[\"amenity\"=\"parking\"]"),
    REST_AREA("Rest areas", "R", "nwr[\"highway\"=\"rest_area\"]"),
    MOTORCYCLE_SERVICE("Motorcycle service", "M", "nwr[\"shop\"=\"motorcycle\"]"),
    CAR_SERVICE("Car service", "C", "nwr[\"shop\"=\"car_repair\"]"),
    CHARGING("Charging", "C", "nwr[\"amenity\"=\"charging_station\"]"),
    HOTEL("Hotels & stays", "H", "nwr[\"tourism\"~\"hotel|hostel|guest_house\"]"),
    CAMPSITE("Campsites", "T", "nwr[\"tourism\"=\"camp_site\"]"),
    VIEWPOINT("Viewpoints", "V", "nwr[\"tourism\"=\"viewpoint\"]"),
    ATTRACTION("Attractions", "A", "nwr[\"tourism\"~\"attraction|museum\"]"),
    SUPERMARKET("Supermarkets", "S", "nwr[\"shop\"~\"supermarket|convenience\"]"),
    TOILETS("Toilets", "W", "nwr[\"amenity\"=\"toilets\"]"),
    DRINKING_WATER("Drinking water", "D", "nwr[\"amenity\"=\"drinking_water\"]"),
    PICNIC("Picnic", "P", "nwr[\"leisure\"=\"picnic_table\"]"),
    ATM("ATMs", "A", "nwr[\"amenity\"=\"atm\"]"),
    BANK("Banks", "B", "nwr[\"amenity\"=\"bank\"]"),
    PHARMACY("Pharmacies", "+", "nwr[\"amenity\"=\"pharmacy\"]"),
    HOSPITAL("Hospitals", "H", "nwr[\"amenity\"=\"hospital\"]"),
    POLICE("Police", "P", "nwr[\"amenity\"=\"police\"]"),
    FIRE_STATION("Fire stations", "F", "nwr[\"amenity\"=\"fire_station\"]"),
    POST_OFFICE("Post offices", "O", "nwr[\"amenity\"=\"post_office\"]"),
    BICYCLE_RENTAL("Bicycle rental", "Y", "nwr[\"shop\"=\"bicycle\"]"),
    AIRPORT("Airports", "✈", "nwr[\"aeroway\"=\"aerodrome\"]")
}

data class MapPoi(
    val id: String,
    val category: MapPoiCategory,
    val name: String,
    val point: NavPoint
)

/** Lightweight Overpass reader used only by the fullscreen route map. */
class MapPoiClient(context: Context) {
    private val applicationContext = context.applicationContext

    suspend fun nearby(
        center: NavPoint,
        radiusMeters: Int,
        categories: Set<MapPoiCategory>
    ): Result<List<MapPoi>> = withContext(Dispatchers.IO) {
        if (categories.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("No POI categories selected."))
        }
        val cacheKey = CacheKey(
            latitudeBucket = (center.latitude * 500.0).roundToInt(),
            longitudeBucket = (center.longitude * 500.0).roundToInt(),
            radiusMeters = radiusMeters,
            categories = categories.map { it.name }.sorted().joinToString(",")
        )
        val now = SystemClock.elapsedRealtime()
        synchronized(cacheLock) {
            cache[cacheKey]?.takeIf { now - it.createdAtMillis < CACHE_TTL_MILLIS }
                ?.let { return@withContext Result.success(it.pois) }
            if (now < retryAfterMillis) {
                return@withContext Result.failure(IllegalStateException("POI retry backoff"))
            }
        }

        val result = runCatching {
            // Overpass can time out on one large union query (especially when
            // every filter is selected). Keep each request small and process a
            // few categories in parallel. A partial result is still useful and
            // is preferable to leaving the map in a permanent loading state.
            val categoryResults = categories.toList()
                .chunked(MAX_CONCURRENT_REQUESTS)
                .flatMap { batch ->
                    coroutineScope {
                        batch.map { category ->
                            async {
                                fetchCategory(center, radiusMeters, category)
                            }
                        }.awaitAll()
                    }
                }
            if (categoryResults.all { it.isFailure }) {
                throw categoryResults.firstOrNull()?.exceptionOrNull()
                    ?: IllegalStateException("No Overpass endpoint available")
            }
            categoryResults
                .flatMap { it.getOrDefault(emptyList()) }
                .distinctBy { it.id }
                .take(MAX_RESULTS)
        }
        result.onSuccess { pois ->
            synchronized(cacheLock) {
                cache[cacheKey] = CacheEntry(SystemClock.elapsedRealtime(), pois)
                while (cache.size > MAX_CACHE_ENTRIES) cache.remove(cache.entries.first().key)
                retryAfterMillis = 0L
            }
        }.onFailure {
            synchronized(cacheLock) {
                retryAfterMillis = SystemClock.elapsedRealtime() + FAILURE_BACKOFF_MILLIS
            }
            ProjectionEventLog.warning("RIDE_NAV", "POI lookup failed: ${it.message}", it)
        }
        result
    }

    private suspend fun fetchCategory(
        center: NavPoint,
        radiusMeters: Int,
        category: MapPoiCategory
    ): Result<List<MapPoi>> {
        // The fullscreen map is normally opened while the phone is connected
        // to the T-Box. That Wi-Fi is useful for the bike, but it does not
        // carry Internet traffic; trying it first left the POI badge on
        // "LOADING" until every connect/read timeout elapsed. Prefer a socket
        // explicitly bound to cellular and only fall back to the default
        // network when cellular is not available.
        val cellularResult = fetchCategoryOnNetwork(
            center = center,
            radiusMeters = radiusMeters,
            category = category,
            cellularOnly = true
        )
        if (cellularResult.isSuccess) return cellularResult
        val defaultNetworkResult = fetchCategoryOnNetwork(
            center = center,
            radiusMeters = radiusMeters,
            category = category,
            cellularOnly = false
        )
        if (defaultNetworkResult.isFailure) {
            ProjectionEventLog.warning(
                "RIDE_NAV",
                "POI ${category.name} failed on LTE and the default network: " +
                    (defaultNetworkResult.exceptionOrNull()?.message
                        ?: cellularResult.exceptionOrNull()?.message.orEmpty())
            )
        }
        return defaultNetworkResult
    }

    private suspend fun fetchCategoryOnNetwork(
        center: NavPoint,
        radiusMeters: Int,
        category: MapPoiCategory,
        cellularOnly: Boolean
    ): Result<List<MapPoi>> = runCatching {
        // Keep the request node-only. A single category over a modest radius
        // is fast and reliable this way; asking Overpass for ways/relations
        // as well regularly exceeds its public endpoint timeout and leaves
        // every POI category waiting. The categories most useful while riding
        // (fuel, food, parking and charging) are widely mapped as nodes.
        val clause = category.queryClause.replaceFirst(
            "nwr",
            "node(around:$radiusMeters,${center.latitude},${center.longitude})"
        )
        val query = "[out:json][timeout:8];$clause;out tags;"
        withCellularNetwork(applicationContext, cellularOnly = cellularOnly) { network ->
            var lastFailure: Throwable? = null
            for (endpoint in OVERPASS_ENDPOINTS) {
                try {
                    val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
                    val url = URL("$endpoint?data=$encoded")
                    val connection = (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
                    try {
                        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
                        connection.readTimeout = READ_TIMEOUT_MILLIS
                        connection.setRequestProperty("Accept", "application/json")
                        connection.setRequestProperty("User-Agent", USER_AGENT)
                        check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                            "Overpass HTTP ${connection.responseCode}"
                        }
                        return@withCellularNetwork parse(
                            connection.inputStream.use { it.readBytes().decodeToString() },
                            setOf(category)
                        )
                    } finally {
                        connection.disconnect()
                    }
                } catch (failure: Throwable) {
                    lastFailure = failure
                }
            }
            throw lastFailure ?: IllegalStateException("No Overpass endpoint available")
        }
    }

    private fun parse(body: String, categories: Set<MapPoiCategory>): List<MapPoi> {
        val elements = JSONObject(body).optJSONArray("elements") ?: return emptyList()
        val result = ArrayList<MapPoi>(minOf(elements.length(), MAX_RESULTS))
        for (index in 0 until elements.length()) {
            if (result.size >= MAX_RESULTS) break
            val element = elements.optJSONObject(index) ?: continue
            val tags = element.optJSONObject("tags") ?: continue
            val category = categories.firstOrNull { matches(it, tags) } ?: continue
            val point = when {
                element.has("lat") && element.has("lon") -> NavPoint(
                    element.optDouble("lat"), element.optDouble("lon")
                )
                element.has("center") -> element.optJSONObject("center")?.let {
                    NavPoint(it.optDouble("lat"), it.optDouble("lon"))
                }
                else -> null
            } ?: continue
            if (!point.latitude.isFinite() || !point.longitude.isFinite()) continue
            val name = tags.optString("name").trim().ifBlank { category.label }
            result += MapPoi(
                id = "${element.optString("type")}/${element.optLong("id")}",
                category = category,
                name = name,
                point = point
            )
        }
        return result
    }

    private fun matches(category: MapPoiCategory, tags: JSONObject): Boolean = when (category) {
        MapPoiCategory.FUEL -> tags.optString("amenity") == "fuel"
        MapPoiCategory.FOOD -> tags.optString("amenity") in setOf("restaurant", "cafe", "fast_food")
        MapPoiCategory.BAKERY -> tags.optString("shop") == "bakery"
        MapPoiCategory.BAR -> tags.optString("amenity") in setOf("bar", "pub")
        MapPoiCategory.PARKING -> tags.optString("amenity") == "parking"
        MapPoiCategory.REST_AREA -> tags.optString("highway") == "rest_area"
        MapPoiCategory.MOTORCYCLE_SERVICE -> tags.optString("shop") == "motorcycle"
        MapPoiCategory.CAR_SERVICE -> tags.optString("shop") == "car_repair"
        MapPoiCategory.HOTEL -> tags.optString("tourism") == "hotel"
        MapPoiCategory.CAMPSITE -> tags.optString("tourism") == "camp_site"
        MapPoiCategory.VIEWPOINT -> tags.optString("tourism") == "viewpoint"
        MapPoiCategory.ATTRACTION -> tags.optString("tourism") in setOf("attraction", "museum")
        MapPoiCategory.SUPERMARKET -> tags.optString("shop") in setOf("supermarket", "convenience")
        MapPoiCategory.TOILETS -> tags.optString("amenity") == "toilets"
        MapPoiCategory.DRINKING_WATER -> tags.optString("amenity") == "drinking_water"
        MapPoiCategory.PICNIC -> tags.optString("leisure") == "picnic_table"
        MapPoiCategory.ATM -> tags.optString("amenity") == "atm"
        MapPoiCategory.BANK -> tags.optString("amenity") == "bank"
        MapPoiCategory.PHARMACY -> tags.optString("amenity") == "pharmacy"
        MapPoiCategory.HOSPITAL -> tags.optString("amenity") == "hospital"
        MapPoiCategory.POLICE -> tags.optString("amenity") == "police"
        MapPoiCategory.FIRE_STATION -> tags.optString("amenity") == "fire_station"
        MapPoiCategory.POST_OFFICE -> tags.optString("amenity") == "post_office"
        MapPoiCategory.BICYCLE_RENTAL -> tags.optString("shop") == "bicycle"
        MapPoiCategory.AIRPORT -> tags.optString("aeroway") == "aerodrome"
        MapPoiCategory.CHARGING -> tags.optString("amenity") == "charging_station"
    }

    private companion object {
        data class CacheKey(
            val latitudeBucket: Int,
            val longitudeBucket: Int,
            val radiusMeters: Int,
            val categories: String
        )

        data class CacheEntry(val createdAtMillis: Long, val pois: List<MapPoi>)

        val cache = LinkedHashMap<CacheKey, CacheEntry>(MAX_CACHE_ENTRIES, 0.75f, true)
        val cacheLock = Any()
        var retryAfterMillis = 0L
        val OVERPASS_ENDPOINTS = listOf(
            // This endpoint responded quickly for Porto during diagnostics.
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter"
        )
        const val CONNECT_TIMEOUT_MILLIS = 3_000
        const val READ_TIMEOUT_MILLIS = 6_000
        const val CACHE_TTL_MILLIS = 5 * 60_000L
        const val FAILURE_BACKOFF_MILLIS = 20_000L
        const val MAX_CACHE_ENTRIES = 12
        const val MAX_CONCURRENT_REQUESTS = 3
        const val MAX_RESULTS = 50
        const val USER_AGENT = "MOTO-HUB/0.9 (OpenStreetMap POI viewer)"
    }
}
