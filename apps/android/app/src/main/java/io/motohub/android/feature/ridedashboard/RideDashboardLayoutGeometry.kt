package io.motohub.android.feature.ridedashboard

internal fun shouldUsePortraitRideDashboardLayout(width: Int, height: Int): Boolean {
    if (width <= 0 || height <= 0) return false
    return height.toFloat() / width.toFloat() >= NEAR_SQUARE_PORTRAIT_THRESHOLD
}

private const val NEAR_SQUARE_PORTRAIT_THRESHOLD = 0.95f
