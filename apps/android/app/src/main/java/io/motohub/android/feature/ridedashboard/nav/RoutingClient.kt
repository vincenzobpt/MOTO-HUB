package io.motohub.android.feature.ridedashboard.nav

import android.content.Context
import io.motohub.android.BuildConfig
import io.motohub.android.feature.settings.MotoHubSettings
import io.motohub.android.feature.settings.RoutePreference
import io.motohub.android.session.ProjectionEventLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

interface RoutingClient {
    suspend fun route(
        origin: NavPoint,
        destination: NavPoint,
        preference: RoutePreference = RoutePreference.FASTEST
    ): Result<NavRoute>
}

/**
 * Calculates motorcycle routes with the Valhalla engine hosted by Stadia Maps,
 * using the rider's own API key from [NavigationSettingsStore]. There is no
 * bundled key: every installation authenticates with its own.
 * See [ADR-005](../../../../../../../../documentation/decisions/ADR-005-native-navigation-over-google-maps.md)
 * for why this replaces any Google Maps embedding for navigation.
 */
class ValhallaRoutingClient(
    context: Context,
    private val cellularOnly: Boolean = true,
    private val apiKey: String = NavigationSettingsStore.load(context)
) : RoutingClient {
    private val applicationContext = context.applicationContext

    override suspend fun route(
        origin: NavPoint,
        destination: NavPoint,
        preference: RoutePreference
    ): Result<NavRoute> =
        withContext(Dispatchers.IO) {
            runCatching {
                check(apiKey.isNotBlank()) {
                    "No routing API key configured; set one in Settings > Navigation."
                }
                requestValhallaRoute(
                    applicationContext = applicationContext,
                    cellularOnly = cellularOnly,
                    url = URL("$BASE_URL?api_key=$apiKey"),
                    origin = origin,
                    destination = destination,
                    preference = preference
                )
            }.onFailure {
                ProjectionEventLog.warning("RIDE_NAV", "Valhalla routing failed: ${it.message}", it)
            }
        }

    private companion object {
        const val BASE_URL = "https://api.stadiamaps.com/route/v1"
    }
}

/**
 * Calculates motorcycle routes with FOSSGIS's free public Valhalla demo
 * server (https://valhalla1.openstreetmap.de) instead of Stadia Maps - no
 * API key needed, so a rider can try turn-by-turn navigation immediately and
 * decide later whether to create their own [ValhallaRoutingClient] key.
 *
 * This is explicitly a demo/testing service, not a production routing
 * backend: FOSSGIS enforces a strict fair-use rate limit (1 request per
 * caller per second, 100 per second total across all callers worldwide), and
 * asks apps that use it to self-identify with an `X-Client-Id` header - see
 * https://github.com/valhalla/valhalla/discussions/3373. A rider who
 * exceeds the limit sees the existing [io.motohub.android.feature.navigation.NavStatus.RateLimited]
 * message and should create a free personal Stadia key for regular use.
 */
class DemoValhallaRoutingClient(
    context: Context,
    private val cellularOnly: Boolean = true
) : RoutingClient {
    private val applicationContext = context.applicationContext

    override suspend fun route(
        origin: NavPoint,
        destination: NavPoint,
        preference: RoutePreference
    ): Result<NavRoute> =
        withContext(Dispatchers.IO) {
            runCatching {
                requestValhallaRoute(
                    applicationContext = applicationContext,
                    cellularOnly = cellularOnly,
                    url = URL(BASE_URL),
                    origin = origin,
                    destination = destination,
                    preference = preference,
                    extraHeaders = mapOf("X-Client-Id" to CLIENT_ID)
                )
            }.onFailure {
                ProjectionEventLog.warning("RIDE_NAV", "Demo Valhalla routing failed: ${it.message}", it)
            }
        }

    private companion object {
        const val BASE_URL = "https://valhalla1.openstreetmap.de/route"
        const val CLIENT_ID = "io.motohub.android"
    }
}

/**
 * Picks between the rider's own Stadia key and the keyless FOSSGIS demo
 * server based on what's configured in Settings > Navigation, so every route
 * request (initial preview, manual recalculation, and automatic reroute)
 * goes through the same backend instead of drifting mid-navigation.
 */
fun routingClientFor(context: Context, cellularOnly: Boolean = true): RoutingClient =
    if (!NavigationSettingsStore.hasKey(context) && MotoHubSettings.useDemoRoutingServer(context)) {
        DemoValhallaRoutingClient(context, cellularOnly)
    } else {
        ValhallaRoutingClient(context, cellularOnly)
    }

private val valhallaJson = Json { ignoreUnknownKeys = true }

private suspend fun requestValhallaRoute(
    applicationContext: Context,
    cellularOnly: Boolean,
    url: URL,
    origin: NavPoint,
    destination: NavPoint,
    preference: RoutePreference,
    extraHeaders: Map<String, String> = emptyMap()
): NavRoute =
    withCellularNetwork(applicationContext, cellularOnly) { network ->
        val connection = (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty(
                "User-Agent",
                "MOTO-HUB/${BuildConfig.VERSION_NAME} (+https://github.com/vincenzobpt/MOTO-HUB)"
            )
            extraHeaders.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(valhallaRequestBody(origin, destination, preference).toString())
            }
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "Valhalla HTTP ${connection.responseCode}: " +
                    connection.errorStream?.use { it.readBytes().decodeToString() }.orEmpty()
            }
            val body = connection.inputStream.use { it.readBytes().decodeToString() }
            parseValhallaTrip(body)
        } finally {
            connection.disconnect()
        }
    }

private fun valhallaRequestBody(origin: NavPoint, destination: NavPoint, preference: RoutePreference) =
    buildJsonObject {
        put("locations", buildJsonArray {
            add(valhallaLocationObject(origin))
            add(valhallaLocationObject(destination))
        })
        put("costing", "motorcycle")
        // Scenic biases the motorcycle costing away from motorways and toward
        // back roads, a practical proxy for a "fun road" (see NAVIGATION_M2_REQUIREMENTS.md).
        if (preference == RoutePreference.SCENIC) {
            put("costing_options", buildJsonObject {
                put("motorcycle", buildJsonObject {
                    put("use_highways", 0.1)
                    put("use_tolls", 0.2)
                })
            })
        }
        put("units", "kilometers")
        put("language", "en-US")
    }

private fun valhallaLocationObject(point: NavPoint) = buildJsonObject {
    put("lat", point.latitude)
    put("lon", point.longitude)
}

private fun parseValhallaTrip(body: String): NavRoute {
    val trip = valhallaJson.parseToJsonElement(body).jsonObject["trip"]?.jsonObject
        ?: error("Valhalla response has no trip")
    val legs = trip["legs"]?.jsonArray ?: JsonArray(emptyList())
    val summary = trip["summary"]?.jsonObject

    val points = mutableListOf<NavPoint>()
    val maneuvers = mutableListOf<NavManeuver>()
    for (leg in legs) {
        val legObject = leg.jsonObject
        val shape = legObject["shape"]?.jsonPrimitive?.content ?: continue
        val legPoints = decodePolyline(shape)
        val legPointOffset = points.size
        points.addAll(legPoints)

        val legManeuvers = legObject["maneuvers"]?.jsonArray ?: JsonArray(emptyList())
        for (maneuver in legManeuvers) {
            val maneuverObject = maneuver.jsonObject
            val beginIndex = maneuverObject["begin_shape_index"]?.jsonPrimitive?.int ?: 0
            val point = legPoints.getOrNull(beginIndex) ?: continue
            maneuvers.add(
                NavManeuver(
                    type = maneuverObject["type"]?.jsonPrimitive?.int?.toString() ?: "0",
                    modifier = null,
                    instruction = maneuverObject["instruction"]?.jsonPrimitive?.content.orEmpty(),
                    point = point,
                    distanceMeters = (maneuverObject["length"]?.jsonPrimitive?.double ?: 0.0) * 1_000.0,
                    pointIndex = legPointOffset + beginIndex,
                    curviness = 0.0
                )
            )
        }
    }

    val distanceMeters = (summary?.get("length")?.jsonPrimitive?.double ?: 0.0) * 1_000.0
    val durationSeconds = summary?.get("time")?.jsonPrimitive?.double ?: 0.0
    val curvedSegments = detectCurvedSegments(points, curvinessTreshold = 0.35)

    return NavRoute(
        points = points,
        maneuvers = maneuvers,
        distanceMeters = distanceMeters,
        durationSeconds = durationSeconds,
        curvedSegments = curvedSegments
    )
}

private const val CONNECT_TIMEOUT_MILLIS = 6_000
private const val READ_TIMEOUT_MILLIS = 12_000
