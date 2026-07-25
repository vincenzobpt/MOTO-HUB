package io.motohub.android.feature.ridedashboard.nav

import android.content.Context
import io.motohub.android.session.ProjectionEventLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

interface GeocodingClient {
    suspend fun search(query: String, near: NavPoint?, limit: Int = 5): Result<List<NavPlace>>
}

/** Resolves free-text destinations to coordinates using Photon (Komoot, OSM data). */
class PhotonGeocodingClient(
    context: Context,
    private val cellularOnly: Boolean = true
) : GeocodingClient {
    private val applicationContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(query: String, near: NavPoint?, limit: Int): Result<List<NavPlace>> =
        withContext(Dispatchers.IO) {
            runCatching {
                // No real address is anywhere near this long - failing fast here (no
                // network call at all) protects any caller, not just the search field
                // that already caps input length, from an oversized request Photon's
                // edge rejects with an opaque HTTP 403.
                check(query.length <= MAX_QUERY_LENGTH) { "Search query is too long." }
                withCellularNetwork(applicationContext, cellularOnly) { network ->
                    val encodedQuery = URLEncoder.encode(query, "UTF-8")
                    val biasParams = near?.let { "&lat=${it.latitude}&lon=${it.longitude}" }.orEmpty()
                    val url = URL("$BASE_URL?q=$encodedQuery&limit=$limit$biasParams&lang=en")
                    val connection = (network?.openConnection(url) ?: url.openConnection())
                        as HttpURLConnection
                    try {
                        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
                        connection.readTimeout = READ_TIMEOUT_MILLIS
                        connection.setRequestProperty("Accept", "application/json")
                        check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                            "Photon HTTP ${connection.responseCode}"
                        }
                        val body = connection.inputStream.use { it.readBytes().decodeToString() }
                        parseFeatureCollection(body)
                    } finally {
                        connection.disconnect()
                    }
                }
            }.onFailure {
                ProjectionEventLog.warning("RIDE_NAV", "Photon geocoding failed: ${it.message}", it)
            }
        }

    private fun parseFeatureCollection(body: String): List<NavPlace> {
        val root = json.parseToJsonElement(body).jsonObject
        val features = root["features"]?.jsonArray ?: JsonArray(emptyList())
        return features.mapNotNull { feature ->
            val featureObject = feature.jsonObject
            val geometry = featureObject["geometry"]?.jsonObject ?: return@mapNotNull null
            val coordinates = geometry["coordinates"]?.jsonArray ?: return@mapNotNull null
            if (coordinates.size < 2) return@mapNotNull null
            val longitude = coordinates[0].jsonPrimitive.double
            val latitude = coordinates[1].jsonPrimitive.double
            val properties = featureObject["properties"]?.jsonObject
            NavPlace(label = formatLabel(properties), point = NavPoint(latitude, longitude))
        }
    }

    private fun formatLabel(properties: JsonObject?): String {
        if (properties == null) return "Unnamed location"
        val name = properties["name"]?.jsonPrimitive?.content
        val street = properties["street"]?.jsonPrimitive?.content
        val housenumber = properties["housenumber"]?.jsonPrimitive?.content
        val city = properties["city"]?.jsonPrimitive?.content
        val state = properties["state"]?.jsonPrimitive?.content
        val country = properties["country"]?.jsonPrimitive?.content

        // Photon leaves "name" blank for plain street addresses (it's only set for
        // named POIs/places) - falling back to street + housenumber here is what
        // makes a specific address distinguishable from the city it's in, instead
        // of every result in the same city collapsing to an identical label.
        val streetAddress = when {
            street != null && housenumber != null -> "$street $housenumber"
            else -> street
        }
        val primary = name ?: streetAddress
        val secondary = streetAddress.takeIf { it != null && it != primary }

        return listOfNotNull(primary, secondary, city ?: state, country).distinct().joinToString(", ")
            .ifBlank { "Unnamed location" }
    }

    private companion object {
        const val BASE_URL = "https://photon.komoot.io/api/"
        const val CONNECT_TIMEOUT_MILLIS = 5_000
        const val READ_TIMEOUT_MILLIS = 8_000
        const val MAX_QUERY_LENGTH = 200
    }
}
