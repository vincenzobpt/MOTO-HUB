package io.motohub.android.tbox

/** Coordinate rectangle reported by the touch controller before projection-canvas mapping. */
data class TBoxTouchBounds(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int
) {
    init {
        require(left >= 0 && top >= 0) { "Touch offsets cannot be negative" }
        require(width > 0 && height > 0) { "Touch bounds must be positive" }
    }
}

/**
 * Normalises T-Box touch coordinates into the exact AVC canvas used by the compositor.
 * Android Auto source overrides are intentionally absent from this transform.
 */
class TBoxTouchTransform(
    val input: TBoxTouchBounds,
    val outputWidth: Int,
    val outputHeight: Int
) {
    init {
        require(outputWidth > 0 && outputHeight > 0) { "Touch output must be positive" }
    }

    fun map(x: Int, y: Int): Pair<Int, Int>? {
        val relativeX = x - input.left
        val relativeY = y - input.top
        if (relativeX !in 0 until input.width || relativeY !in 0 until input.height) return null
        return scaleAxis(relativeX, input.width, outputWidth) to
            scaleAxis(relativeY, input.height, outputHeight)
    }

    companion object {
        fun forVideoConfiguration(configuration: TBoxVideoConfiguration): TBoxTouchTransform =
            TBoxTouchTransform(
                input = TBoxTouchBounds(
                    left = 0,
                    top = 0,
                    width = configuration.rawArea.width,
                    height = configuration.rawArea.height
                ),
                outputWidth = configuration.encoderProfile.width,
                outputHeight = configuration.encoderProfile.height
            )

        private fun scaleAxis(value: Int, inputSize: Int, outputSize: Int): Int {
            if (inputSize <= 1 || outputSize <= 1) return 0
            return (value.toLong() * (outputSize - 1) / (inputSize - 1)).toInt()
                .coerceIn(0, outputSize - 1)
        }
    }
}
