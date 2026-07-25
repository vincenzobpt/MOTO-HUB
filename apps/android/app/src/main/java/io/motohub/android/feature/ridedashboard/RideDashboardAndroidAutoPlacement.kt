package io.motohub.android.feature.ridedashboard

import io.motohub.android.androidauto.AndroidAutoDisplayMode

internal data class RideDashboardAndroidAutoPlacement(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

internal fun calculateRideDashboardAndroidAutoPlacement(
    containerLeft: Float,
    containerTop: Float,
    containerRight: Float,
    containerBottom: Float,
    sourceWidth: Int,
    sourceHeight: Int,
    fill: Boolean,
    alignFillToTop: Boolean = false,
    maxFillOverflowFraction: Float? = null
): RideDashboardAndroidAutoPlacement {
    val containerWidth = (containerRight - containerLeft).coerceAtLeast(1f)
    val containerHeight = (containerBottom - containerTop).coerceAtLeast(1f)
    val widthScale = containerWidth / sourceWidth.coerceAtLeast(1)
    val heightScale = containerHeight / sourceHeight.coerceAtLeast(1)
    val fitScale = minOf(widthScale, heightScale)
    val fillScale = maxOf(widthScale, heightScale)
    val scale = if (fill) {
        val maximumOverflow = maxFillOverflowFraction
        if (maximumOverflow == null) {
            fillScale
        } else {
            val cappedWidthScale = containerWidth * (1f + maximumOverflow) / sourceWidth.coerceAtLeast(1)
            val cappedHeightScale = containerHeight * (1f + maximumOverflow) / sourceHeight.coerceAtLeast(1)
            val overflowCap = if (widthScale >= heightScale) cappedHeightScale else cappedWidthScale
            minOf(fillScale, maxOf(fitScale, overflowCap))
        }
    } else {
        fitScale
    }
    val contentWidth = sourceWidth * scale
    val contentHeight = sourceHeight * scale
    val left = containerLeft + (containerWidth - contentWidth) / 2f
    val top = if (fill && alignFillToTop && contentHeight > containerHeight) {
        containerTop
    } else {
        containerTop + (containerHeight - contentHeight) / 2f
    }
    return RideDashboardAndroidAutoPlacement(
        left = left,
        top = top,
        right = left + contentWidth,
        bottom = top + contentHeight
    )
}

internal fun calculateRideDashboardAndroidAutoPlacement(
    containerLeft: Float,
    containerTop: Float,
    containerRight: Float,
    containerBottom: Float,
    sourceWidth: Int,
    sourceHeight: Int,
    displayMode: AndroidAutoDisplayMode,
    alignFillToTop: Boolean = false
): RideDashboardAndroidAutoPlacement = when (displayMode) {
    AndroidAutoDisplayMode.LETTERBOX -> calculateRideDashboardAndroidAutoPlacement(
        containerLeft = containerLeft,
        containerTop = containerTop,
        containerRight = containerRight,
        containerBottom = containerBottom,
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight,
        fill = false
    )
    AndroidAutoDisplayMode.STRETCH -> RideDashboardAndroidAutoPlacement(
        left = containerLeft,
        top = containerTop,
        right = containerRight,
        bottom = containerBottom
    )
    AndroidAutoDisplayMode.FILL -> calculateRideDashboardAndroidAutoPlacement(
        containerLeft = containerLeft,
        containerTop = containerTop,
        containerRight = containerRight,
        containerBottom = containerBottom,
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight,
        fill = true,
        alignFillToTop = alignFillToTop
    )
}
