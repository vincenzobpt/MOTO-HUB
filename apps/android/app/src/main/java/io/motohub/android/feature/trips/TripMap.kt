package io.motohub.android.feature.trips

import io.motohub.android.i18n.motoHubText

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.motohub.android.feature.ridedashboard.OpenStreetMapTileProvider
import io.motohub.android.feature.ridedashboard.osmGeoPoint
import io.motohub.android.feature.ridedashboard.osmWorldPixel
import kotlin.math.floor
import kotlin.math.log2

@Composable
internal fun TripMap(
    points: List<TripTrackPoint>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var tileRevision by remember { mutableIntStateOf(0) }
    val tileProvider = remember {
        OpenStreetMapTileProvider(
            context = context,
            cellularOnly = false,
            asynchronousDiskReads = true,
            onTileAvailable = { mainHandler.post { tileRevision++ } }
        )
    }
    var mapSize by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableIntStateOf(DEFAULT_ZOOM) }
    var center by remember { mutableStateOf(points.centerPoint()) }
    val renderedPoints = remember(points) { points.downsample(MAX_RENDERED_ROUTE_POINTS) }

    fun fitTrack() {
        if (points.isEmpty() || mapSize == IntSize.Zero) return
        val fitted = fitTrack(points, mapSize)
        center = fitted.first
        zoom = fitted.second
    }

    DisposableEffect(tileProvider) {
        tileProvider.start()
        onDispose { tileProvider.stop() }
    }
    LaunchedEffect(points, mapSize) { fitTrack() }

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
                    val moved = osmGeoPoint(scaledX - pan.x, scaledY - pan.y, nextZoom)
                    center = moved.latitude to moved.longitude
                    zoom = nextZoom
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
            val firstTileX = floor((worldCenter.x - size.width / 2.0) / TILE_SIZE).toInt()
            val lastTileX = floor((worldCenter.x + size.width / 2.0) / TILE_SIZE).toInt()
            val firstTileY = floor((worldCenter.y - size.height / 2.0) / TILE_SIZE).toInt()
            val lastTileY = floor((worldCenter.y + size.height / 2.0) / TILE_SIZE).toInt()
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
                drawPath(route, Color(0x6619FF7A), style = Stroke(width = 12f))
                drawPath(route, Color(0xFFC5FF2D), style = Stroke(width = 5f))
            }
            drawMarker(points.first(), worldCenter, zoom, Color(0xFF74E6A3), 8f)
            drawMarker(points.last(), worldCenter, zoom, Color(0xFFC5FF2D), 10f)
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp),
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
                onClick = ::fitTrack,
                colors = mapButtonColors,
                border = mapButtonBorder
            ) { Text(motoHubText("Fit")) }
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
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGrid() {
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

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMarker(
    point: TripTrackPoint,
    center: io.motohub.android.feature.ridedashboard.OsmWorldPixel,
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

private fun List<TripTrackPoint>.centerPoint(): Pair<Double, Double> =
    if (isEmpty()) 0.0 to 0.0 else ((minOf { it.latitude } + maxOf { it.latitude }) / 2.0) to
        ((minOf { it.longitude } + maxOf { it.longitude }) / 2.0)

private fun List<TripTrackPoint>.downsample(maxPoints: Int): List<TripTrackPoint> {
    if (size <= maxPoints) return this
    val step = (size - 1).toDouble() / (maxPoints - 1).toDouble()
    return buildList(maxPoints) {
        repeat(maxPoints - 1) { index -> add(this@downsample[(index * step).toInt()]) }
        add(this@downsample.last())
    }
}

private fun fitTrack(points: List<TripTrackPoint>, size: IntSize): Pair<Pair<Double, Double>, Int> {
    val center = points.centerPoint()
    for (candidate in MAX_ZOOM downTo MIN_ZOOM) {
        val worldPoints = points.map { osmWorldPixel(it.latitude, it.longitude, candidate) }
        val width = (worldPoints.maxOf { it.x } - worldPoints.minOf { it.x }).coerceAtLeast(1.0)
        val height = (worldPoints.maxOf { it.y } - worldPoints.minOf { it.y }).coerceAtLeast(1.0)
        if (width <= (size.width - MAP_PADDING_PIXELS).coerceAtLeast(1) &&
            height <= (size.height - MAP_PADDING_PIXELS).coerceAtLeast(1)
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
private const val DEFAULT_ZOOM = 14
private const val MAP_PADDING_PIXELS = 120
private const val MAX_RENDERED_ROUTE_POINTS = 5_000
