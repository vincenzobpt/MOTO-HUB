package io.motohub.android.androidauto

data class PreviewViewport(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val source: DisplayGeometry
) {
    fun mapToSource(canvasX: Int, canvasY: Int): Pair<Int, Int>? {
        val relativeX = canvasX - x
        val relativeY = canvasY - y
        if (relativeX !in 0 until width || relativeY !in 0 until height) return null
        val sourceX = (relativeX.toLong() * source.width / width).toInt()
        val sourceY = (relativeY.toLong() * source.height / height).toInt()
        return sourceX.coerceIn(0, source.width - 1) to
            sourceY.coerceIn(0, source.height - 1)
    }
}

internal fun calculatePreviewViewport(
    canvas: DisplayGeometry,
    source: DisplayGeometry
): PreviewViewport {
    val contentAspect = source.width.toDouble() / source.height
    val canvasAspect = canvas.width.toDouble() / canvas.height
    val width: Int
    val height: Int
    if (contentAspect < canvasAspect) {
        height = canvas.height
        width = (canvas.height * contentAspect).toInt().coerceAtLeast(1)
    } else {
        width = canvas.width
        height = (canvas.width / contentAspect).toInt().coerceAtLeast(1)
    }
    return PreviewViewport(
        x = (canvas.width - width) / 2,
        y = (canvas.height - height) / 2,
        width = width,
        height = height,
        source = source
    )
}
