package io.motohub.android.feature.navigation

import io.motohub.android.i18n.motoHubText

import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.motohub.android.feature.ridedashboard.OpenStreetMapTileProvider
import io.motohub.android.feature.ridedashboard.OsmWorldPixel
import io.motohub.android.feature.ridedashboard.nav.NavPoint
import io.motohub.android.feature.ridedashboard.osmGeoPoint
import io.motohub.android.feature.ridedashboard.osmWorldPixel
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.roundToInt

/**
 * Draws a calculated route (polyline + start/end markers) over OSM tiles,
 * fit to the available space. Mirrors [io.motohub.android.feature.trips.TripMap]
 * (same tile source, same gesture handling) but works with routing points
 * instead of a recorded track, since a route preview has no [io.motohub.android.feature.trips.TripTrackPoint] timestamps.
 */
@Composable
fun RouteMap(
    points: List<NavPoint>,
    modifier: Modifier = Modifier,
    // Lets a rider fine-tune the destination when OpenStreetMap has the
    // street but not the exact house number - a tap anywhere on the map
    // moves the destination there and the caller recalculates the route.
    onAdjustDestination: ((NavPoint) -> Unit)? = null,
    onFullscreen: (() -> Unit)? = null,
    // Raster labels are baked into 256 px map tiles. A wider viewport alone
    // shows more tiles but cannot make their labels readable.
    visualScale: Float = 1f,
    /** POI controls are intentionally exposed only by the fullscreen map. */
    showPoiControls: Boolean = false
) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var tileRevision by remember { mutableIntStateOf(0) }
    val tileProvider = remember {
        OpenStreetMapTileProvider(
            context = context,
            // Shown from the phone's own Navigation tab, not while the
            // phone is joined to the T-Box's internet-less Wi-Fi - same
            // context as TripMap, so tiles should use whatever network the
            // phone already has (Wi-Fi or cellular), not force cellular.
            cellularOnly = false,
            asynchronousDiskReads = true,
            onTileAvailable = { mainHandler.post { tileRevision++ } }
        )
    }
    var mapSize by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableIntStateOf(DEFAULT_ZOOM) }
    var center by remember { mutableStateOf(points.centerPoint()) }
    var hasAutoFitted by remember { mutableStateOf(false) }
    var poiEnabled by remember { mutableStateOf(false) }
    var poiMarkers by remember { mutableStateOf<List<MapPoi>>(emptyList()) }
    var poiLoading by remember { mutableStateOf(false) }
    var poiError by remember { mutableStateOf<String?>(null) }
    var selectedPoi by remember { mutableStateOf<MapPoi?>(null) }
    var selectedPoiCategories by remember { mutableStateOf(MapPoiPreferences.load(context)) }
    var poiMenuExpanded by remember { mutableStateOf(false) }
    var lastPoiQueryKey by remember { mutableStateOf<String?>(null) }
    val renderedPoints = remember(points) { points.downsample(MAX_RENDERED_ROUTE_POINTS) }
    val labelScale = visualScale.coerceAtLeast(1f)
    val poiClient = remember { MapPoiClient(context) }
    val poiQueryKey = "${(center.first * 1_000.0).roundToInt()}:${(center.second * 1_000.0).roundToInt()}:$zoom:${selectedPoiCategories.joinToString(",")}"

    fun renderScaleFor(zoomLevel: Int): Float =
        labelScale * if (zoomLevel >= MAX_ZOOM) MAX_ZOOM_OVER_SCALE else 1f

    fun fitRoute() {
        if (points.isEmpty() || mapSize == IntSize.Zero) return
        val fitted = fitRoute(points, mapSize, labelScale)
        center = fitted.first
        zoom = fitted.second
    }

    DisposableEffect(tileProvider) {
        tileProvider.start()
        onDispose { tileProvider.stop() }
    }
    // Only once, when the route first has something to show - not on every
    // points change, otherwise recalculating after a route-type switch or a
    // tap-to-adjust (see onAdjustDestination) would yank the rider's zoom/pan
    // back to a wide fit right after they set it up. The "Fit" button below
    // still re-centers on demand.
    LaunchedEffect(points, mapSize) {
        if (!hasAutoFitted && points.isNotEmpty() && mapSize != IntSize.Zero) {
            fitRoute()
            hasAutoFitted = true
        }
    }

    LaunchedEffect(showPoiControls, poiEnabled, poiQueryKey, mapSize) {
        if (!showPoiControls || !poiEnabled) {
            poiMarkers = emptyList()
            poiError = null
            poiLoading = false
            selectedPoi = null
            poiMenuExpanded = false
            lastPoiQueryKey = null
            return@LaunchedEffect
        }
        if (lastPoiQueryKey == poiQueryKey) return@LaunchedEffect
        lastPoiQueryKey = poiQueryKey
        if (selectedPoiCategories.isEmpty()) {
            poiMarkers = emptyList()
            poiError = "SELECT AT LEAST ONE CATEGORY"
            poiLoading = false
            return@LaunchedEffect
        }
        if (mapSize == IntSize.Zero || zoom < MIN_POI_ZOOM) {
            poiMarkers = emptyList()
            poiError = "ZOOM IN TO SHOW POI"
            poiLoading = false
            return@LaunchedEffect
        }
        // Wait for a pan/zoom gesture to settle before querying Overpass.
        delay(650L)
        poiLoading = true
        poiError = null
        val result = poiClient.nearby(
            center = NavPoint(center.first, center.second),
            radiusMeters = poiRadiusMeters(zoom),
            categories = selectedPoiCategories
        )
        result.onSuccess {
            poiMarkers = it
            poiError = if (it.isEmpty()) "NO POI NEARBY" else null
        }.onFailure {
            poiError = "POI UNAVAILABLE"
        }
        poiLoading = false
    }

    Box(
        modifier = modifier
            .background(Color(0xFF0A0E0B), RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .onSizeChanged { mapSize = it }
            .pointerInput(points) {
                var accumulatedZoom = 1f
                detectTransformGestures { _, pan, zoomChange, _ ->
                    if (points.isEmpty()) return@detectTransformGestures
                    accumulatedZoom *= zoomChange
                    val oldZoom = zoom
                    val nextZoom = when {
                        log2(accumulatedZoom.toDouble()) > 0.18 ->
                            (zoom + 1).coerceAtMost(MAX_ZOOM)
                        log2(accumulatedZoom.toDouble()) < -0.18 ->
                            (zoom - 1).coerceAtLeast(MIN_ZOOM)
                        else -> zoom
                    }
                    if (nextZoom != oldZoom) accumulatedZoom = 1f
                    val oldWorld = osmWorldPixel(center.first, center.second, oldZoom)
                    val scale = 1 shl (nextZoom - oldZoom).coerceAtLeast(0)
                    val scaledX = if (nextZoom >= oldZoom) oldWorld.x * scale else oldWorld.x / (1 shl (oldZoom - nextZoom))
                    val scaledY = if (nextZoom >= oldZoom) oldWorld.y * scale else oldWorld.y / (1 shl (oldZoom - nextZoom))
                    val panScale = renderScaleFor(oldZoom)
                    val moved = osmGeoPoint(
                        scaledX - pan.x / panScale,
                        scaledY - pan.y / panScale,
                        nextZoom
                    )
                    center = moved.latitude to moved.longitude
                    zoom = nextZoom
                }
            }
            .pointerInput(points, mapSize, center, zoom, onAdjustDestination, poiMarkers, poiEnabled) {
                if (onAdjustDestination == null) return@pointerInput
                detectTapGestures { offset ->
                    if (points.isEmpty() || mapSize == IntSize.Zero) return@detectTapGestures
                    val worldCenter = osmWorldPixel(center.first, center.second, zoom)
                    val tapScale = renderScaleFor(zoom)
                    if (showPoiControls && poiEnabled && poiMarkers.isNotEmpty()) {
                        val tappedPoi = poiMarkers.minByOrNull { poi ->
                            val world = osmWorldPixel(poi.point.latitude, poi.point.longitude, zoom)
                            val markerX = mapSize.width / 2f + (world.x - worldCenter.x).toFloat() * tapScale
                            val markerY = mapSize.height / 2f + (world.y - worldCenter.y).toFloat() * tapScale
                            val dx = markerX - offset.x
                            val dy = markerY - offset.y
                            dx * dx + dy * dy
                        }
                        if (tappedPoi != null) {
                            val world = osmWorldPixel(tappedPoi.point.latitude, tappedPoi.point.longitude, zoom)
                            val markerX = mapSize.width / 2f + (world.x - worldCenter.x).toFloat() * tapScale
                            val markerY = mapSize.height / 2f + (world.y - worldCenter.y).toFloat() * tapScale
                            val dx = markerX - offset.x
                            val dy = markerY - offset.y
                            if (dx * dx + dy * dy <= POI_HIT_RADIUS_PX * POI_HIT_RADIUS_PX) {
                                selectedPoi = tappedPoi
                                return@detectTapGestures
                            }
                        }
                    }
                    selectedPoi = null
                    val worldX = worldCenter.x + (offset.x - mapSize.width / 2f) / tapScale
                    val worldY = worldCenter.y + (offset.y - mapSize.height / 2f) / tapScale
                    val tapped = osmGeoPoint(worldX, worldY, zoom)
                    onAdjustDestination(NavPoint(tapped.latitude, tapped.longitude))
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_EXPRESSION")
            tileRevision
            if (points.isEmpty()) {
                drawGrid()
                return@Canvas
            }
            val worldCenter = osmWorldPixel(center.first, center.second, zoom)
            // CartoDB Voyager labels are baked into raster tiles. Fullscreen uses a
            // visual magnification so the labels themselves become readable; at the
            // provider's real max zoom we retain the existing extra over-zoom too.
            val renderScale = renderScaleFor(zoom)
            scale(renderScale) {
                val logicalHalfWidth = size.width / renderScale / 2.0
                val logicalHalfHeight = size.height / renderScale / 2.0
                val firstTileX = floor((worldCenter.x - logicalHalfWidth) / TILE_SIZE).toInt()
                val lastTileX = floor((worldCenter.x + logicalHalfWidth) / TILE_SIZE).toInt()
                val firstTileY = floor((worldCenter.y - logicalHalfHeight) / TILE_SIZE).toInt()
                val lastTileY = floor((worldCenter.y + logicalHalfHeight) / TILE_SIZE).toInt()
                var loaded = 0
                for (tileY in firstTileY..lastTileY) {
                    for (tileX in firstTileX..lastTileX) {
                        val bitmap = tileProvider.tile(zoom, tileX, tileY) ?: continue
                        val x = size.width / 2f + (tileX * TILE_SIZE - worldCenter.x).toFloat()
                        val y = size.height / 2f + (tileY * TILE_SIZE - worldCenter.y).toFloat()
                        drawImage(bitmap.asImageBitmap(), topLeft = Offset(x, y))
                        loaded++
                    }
                }
                if (loaded == 0) drawGrid()

                if (renderedPoints.size > 1) {
                    val route = Path()
                    renderedPoints.forEachIndexed { index, point ->
                        val world = osmWorldPixel(point.latitude, point.longitude, zoom)
                        val x = size.width / 2f + (world.x - worldCenter.x).toFloat()
                        val y = size.height / 2f + (world.y - worldCenter.y).toFloat()
                        if (index == 0) route.moveTo(x, y) else route.lineTo(x, y)
                    }
                    drawPath(route, Color(0x66C9F53A), style = Stroke(width = 12f))
                    drawPath(route, Color(0xFFC9F53A), style = Stroke(width = 5f))
                }
                drawMarker(points.first(), worldCenter, zoom, Color(0xFF74E6A3), 8f)
                drawMarker(points.last(), worldCenter, zoom, Color(0xFFC9F53A), 10f)
                if (showPoiControls && poiEnabled) {
                    poiMarkers.forEach { poi ->
                        val world = osmWorldPixel(poi.point.latitude, poi.point.longitude, zoom)
                        val x = size.width / 2f + (world.x - worldCenter.x).toFloat()
                        val y = size.height / 2f + (world.y - worldCenter.y).toFloat()
                        if (x in -24f..(size.width + 24f) && y in -24f..(size.height + 24f)) {
                            drawPoiMarker(x, y, poi.category, renderScale)
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp)
        ) {
            // Explicit opaque colors, not the default outlined-button style - a
            // near-transparent button reads poorly now that the map underneath
            // (Voyager tiles) is light instead of dark.
            val mapButtonColors = ButtonDefaults.outlinedButtonColors(
                containerColor = Color(0xE6141B17),
                contentColor = Color.White
            )
            val mapButtonBorder = BorderStroke(1.dp, Color(0x33FFFFFF))
            OutlinedButton(
                onClick = { zoom = (zoom - 1).coerceAtLeast(MIN_ZOOM) },
                colors = mapButtonColors,
                border = mapButtonBorder
            ) { Text("−") }
            OutlinedButton(
                onClick = { zoom = (zoom + 1).coerceAtMost(MAX_ZOOM) },
                colors = mapButtonColors,
                border = mapButtonBorder
            ) { Text("+") }
            OutlinedButton(
                onClick = {
                    points.lastOrNull()?.let { destination ->
                        center = destination.latitude to destination.longitude
                    }
                },
                enabled = points.isNotEmpty(),
                colors = mapButtonColors,
                border = mapButtonBorder
            ) { Text(motoHubText("Dest")) }
            OutlinedButton(
                onClick = ::fitRoute,
                colors = mapButtonColors,
                border = mapButtonBorder
            ) { Text(motoHubText("Fit")) }
        }
        if (showPoiControls) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 62.dp, end = 10.dp)
            ) {
                val poiButtonColors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (poiEnabled) Color(0xFF41651D) else Color(0xE6141B17),
                    contentColor = Color.White
                )
                val poiButtonBorder = BorderStroke(1.dp, Color(0x33FFFFFF))
                OutlinedButton(
                    onClick = {
                        poiEnabled = !poiEnabled
                        if (poiEnabled) selectedPoi = null
                    },
                    colors = poiButtonColors,
                    border = poiButtonBorder
                ) { Text(if (poiEnabled) "POI ON" else "POI") }
                Box {
                    OutlinedButton(
                        onClick = { poiMenuExpanded = true },
                        enabled = poiEnabled,
                        colors = poiButtonColors,
                        border = poiButtonBorder
                    ) { Text(motoHubText("FILTER %1\$d", selectedPoiCategories.size)) }
                    DropdownMenu(
                        expanded = poiMenuExpanded,
                        onDismissRequest = { poiMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(motoHubText("SELECT ALL")) },
                            onClick = {
                                val all = MapPoiCategory.entries.toSet()
                                selectedPoiCategories = all
                                MapPoiPreferences.save(context, all)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(motoHubText("CLEAR ALL")) },
                            onClick = {
                                selectedPoiCategories = emptySet()
                                MapPoiPreferences.save(context, emptySet())
                            }
                        )
                        MapPoiCategory.entries.forEach { category ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = category in selectedPoiCategories,
                                            onCheckedChange = null
                                        )
                                        Text(motoHubText(category.label))
                                    }
                                },
                                onClick = {
                                    val updated = if (category in selectedPoiCategories) {
                                        selectedPoiCategories - category
                                    } else {
                                        selectedPoiCategories + category
                                    }
                                    selectedPoiCategories = updated
                                    MapPoiPreferences.save(context, updated)
                                }
                            )
                        }
                    }
                }
            }
        }
        onFullscreen?.let { openFullscreen ->
            OutlinedButton(
                onClick = openFullscreen,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color(0xE6141B17),
                    contentColor = Color.White
                ),
                border = BorderStroke(1.dp, Color(0x33FFFFFF))
            ) { Text(motoHubText("Full screen")) }
        }
        Text(
            motoHubText("© OpenStreetMap contributors"),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .background(Color(0xB8000000), RoundedCornerShape(topStart = 6.dp))
                .padding(horizontal = 6.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White
        )
        if (showPoiControls && poiEnabled && (poiLoading || poiError != null)) {
            Text(
                text = if (poiLoading) "LOADING POI…" else poiError.orEmpty(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .background(Color(0xCC141B17), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White
            )
        }
        selectedPoi?.let { poi ->
            Text(
                text = "${motoHubText(poi.category.label).uppercase()}  ·  ${poi.name}",
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xE6141B17), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White
            )
        }
    }
}

private fun DrawScope.drawGrid() {
    // Light, matching the OSM tiles this stands in for while they load - a dark placeholder
    // would flash black whenever tiles for a new area aren't cached yet (e.g. after a large
    // position jump), not just on first open.
    drawRect(Color(0xFFE0E0D8))
    val gap = 48.dp.toPx()
    var x = 0f
    while (x <= size.width) {
        drawLine(Color(0x2239FF7E), Offset(x, 0f), Offset(x, size.height), 1f)
        x += gap
    }
    var y = 0f
    while (y <= size.height) {
        drawLine(Color(0x2239FF7E), Offset(0f, y), Offset(size.width, y), 1f)
        y += gap
    }
}

private fun DrawScope.drawPoiMarker(
    x: Float,
    y: Float,
    category: MapPoiCategory,
    renderScale: Float
) {
    val color = when (category) {
        MapPoiCategory.FUEL -> Color(0xFFFFA726)
        MapPoiCategory.FOOD -> Color(0xFFE57373)
        MapPoiCategory.BAKERY -> Color(0xFFFFCC80)
        MapPoiCategory.BAR -> Color(0xFFEF9A9A)
        MapPoiCategory.PARKING -> Color(0xFF64B5F6)
        MapPoiCategory.REST_AREA -> Color(0xFF90A4AE)
        MapPoiCategory.MOTORCYCLE_SERVICE -> Color(0xFFBA68C8)
        MapPoiCategory.CAR_SERVICE -> Color(0xFF9575CD)
        MapPoiCategory.HOTEL -> Color(0xFF4DB6AC)
        MapPoiCategory.CAMPSITE -> Color(0xFF81C784)
        MapPoiCategory.VIEWPOINT -> Color(0xFF4FC3F7)
        MapPoiCategory.ATTRACTION -> Color(0xFFFFB74D)
        MapPoiCategory.SUPERMARKET -> Color(0xFFA5D6A7)
        MapPoiCategory.TOILETS -> Color(0xFF90CAF9)
        MapPoiCategory.DRINKING_WATER -> Color(0xFF80DEEA)
        MapPoiCategory.PICNIC -> Color(0xFFDCE775)
        MapPoiCategory.ATM -> Color(0xFFB0BEC5)
        MapPoiCategory.BANK -> Color(0xFF78909C)
        MapPoiCategory.PHARMACY -> Color(0xFFE57373)
        MapPoiCategory.HOSPITAL -> Color(0xFFD32F2F)
        MapPoiCategory.POLICE -> Color(0xFF5C6BC0)
        MapPoiCategory.FIRE_STATION -> Color(0xFFF4511E)
        MapPoiCategory.POST_OFFICE -> Color(0xFF7986CB)
        MapPoiCategory.BICYCLE_RENTAL -> Color(0xFF26A69A)
        MapPoiCategory.AIRPORT -> Color(0xFF607D8B)
        MapPoiCategory.CHARGING -> Color(0xFFAED581)
    }
    val scale = renderScale.coerceAtLeast(1f)
    val radius = 9f / scale
    drawCircle(Color.Black.copy(alpha = 0.75f), radius + 2f, Offset(x, y))
    drawCircle(color, radius, Offset(x, y))
    drawIntoCanvas { canvas ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.White.toArgb()
            textSize = 9f / scale
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.nativeCanvas.drawText(category.symbol, x, y - (paint.ascent() + paint.descent()) / 2f, paint)
    }
}

private fun poiRadiusMeters(zoom: Int): Int = when {
    zoom >= 17 -> 1_500
    zoom >= 15 -> 2_500
    zoom >= 13 -> 3_000
    else -> 4_000
}

private fun DrawScope.drawMarker(
    point: NavPoint,
    center: OsmWorldPixel,
    zoom: Int,
    color: Color,
    radius: Float
) {
    val world = osmWorldPixel(point.latitude, point.longitude, zoom)
    val offset = Offset(
        size.width / 2f + (world.x - center.x).toFloat(),
        size.height / 2f + (world.y - center.y).toFloat()
    )
    drawCircle(Color(0x99000000), radius + 5f, offset)
    drawCircle(color, radius, offset)
}

private fun List<NavPoint>.centerPoint(): Pair<Double, Double> =
    if (isEmpty()) 0.0 to 0.0 else ((minOf { it.latitude } + maxOf { it.latitude }) / 2.0) to
        ((minOf { it.longitude } + maxOf { it.longitude }) / 2.0)

private fun List<NavPoint>.downsample(maxPoints: Int): List<NavPoint> {
    if (size <= maxPoints) return this
    val step = (size - 1).toDouble() / (maxPoints - 1).toDouble()
    return buildList(maxPoints) {
        repeat(maxPoints - 1) { index -> add(this@downsample[(index * step).toInt()]) }
        add(this@downsample.last())
    }
}

private fun fitRoute(
    points: List<NavPoint>,
    size: IntSize,
    visualScale: Float
): Pair<Pair<Double, Double>, Int> {
    val center = points.centerPoint()
    for (candidate in MAX_ZOOM downTo MIN_ZOOM) {
        val worldPoints = points.map { osmWorldPixel(it.latitude, it.longitude, candidate) }
        val width = (worldPoints.maxOf { it.x } - worldPoints.minOf { it.x }).coerceAtLeast(1.0)
        val height = (worldPoints.maxOf { it.y } - worldPoints.minOf { it.y }).coerceAtLeast(1.0)
        val candidateScale = visualScale * if (candidate >= MAX_ZOOM) MAX_ZOOM_OVER_SCALE else 1f
        val availableWidth = (size.width / candidateScale - MAP_PADDING_PIXELS).coerceAtLeast(1f)
        val availableHeight = (size.height / candidateScale - MAP_PADDING_PIXELS).coerceAtLeast(1f)
        if (width <= availableWidth &&
            height <= availableHeight
        ) {
            return center to candidate
        }
    }
    return center to MIN_ZOOM
}

private const val TILE_SIZE = 256.0
private const val MIN_ZOOM = 3
// 20 is CartoDB Voyager's actual max useful zoom (verified: zoom 21 tiles come back
// blank) - 18 stopped short of native detail, leaving street names unreadable.
private const val MAX_ZOOM = 20
// Applied once zoom hits MAX_ZOOM so labels stay legible past the tile source's
// real detail limit - see the scale() call above.
private const val MAX_ZOOM_OVER_SCALE = 1.6f
private const val DEFAULT_ZOOM = 14
private const val MAP_PADDING_PIXELS = 120
private const val MAX_RENDERED_ROUTE_POINTS = 5_000
private const val MIN_POI_ZOOM = 12
private const val POI_HIT_RADIUS_PX = 32f
