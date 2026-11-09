package org.szres.glyphlens.capture

import kotlin.math.roundToInt

internal data class CaptureSize(val width: Int, val height: Int, val densityDpi: Int) {
    companion object {
        // Matches the iOS broadcast input and Android replay, including their pixel thresholds.
        private const val MAXIMUM_WIDTH = 603

        fun forContent(width: Int, height: Int, densityDpi: Int): CaptureSize {
            val outputWidth = minOf(width, MAXIMUM_WIDTH)
            val outputHeight = (height.toDouble() * outputWidth / width).roundToInt()
            return CaptureSize(outputWidth, outputHeight, densityDpi)
        }
    }
}
