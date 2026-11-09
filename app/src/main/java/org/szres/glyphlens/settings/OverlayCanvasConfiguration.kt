package org.szres.glyphlens.settings

import kotlin.math.roundToInt

/**
 * Source geometry for the floating window. Width and height define its aspect ratio; resolution
 * defines the off-screen bitmap's actual pixel density, just as the Swift canvas does.
 */
data class OverlayCanvasConfiguration(
    val width: Int = STANDARD_WIDTH,
    val height: Int = STANDARD_HEIGHT,
    val resolution: Int = STANDARD_RESOLUTION,
) {
    init {
        require(width in MIN_WIDTH..MAX_WIDTH) { "width must be in $MIN_WIDTH..$MAX_WIDTH" }
        require(height in MIN_HEIGHT..MAX_HEIGHT) { "height must be in $MIN_HEIGHT..$MAX_HEIGHT" }
        require(resolution in MIN_RESOLUTION..MAX_RESOLUTION) {
            "resolution must be in $MIN_RESOLUTION..$MAX_RESOLUTION"
        }
    }

    val pixelWidth: Int get() = width * resolution
    val pixelHeight: Int get() = height * resolution
    val aspectRatio: Double get() = width.toDouble() / height.toDouble()

    fun ratioLabel(): String {
        val rounded = (aspectRatio * 10.0).roundToInt() / 10.0
        return if (rounded == rounded.toInt().toDouble()) {
            "${rounded.toInt()}:1"
        } else {
            "$rounded:1"
        }
    }

    companion object {
        const val MIN_WIDTH = 256
        const val MAX_WIDTH = 640
        const val MIN_HEIGHT = 64
        const val MAX_HEIGHT = 192
        const val MIN_RESOLUTION = 1
        const val MAX_RESOLUTION = 3
        const val WIDTH_STEP = 16
        const val HEIGHT_STEP = 8
        private const val STANDARD_WIDTH = 640
        private const val STANDARD_HEIGHT = 64
        private const val STANDARD_RESOLUTION = 2

        val standard = OverlayCanvasConfiguration()
        val presets = listOf(
            standard,
            OverlayCanvasConfiguration(width = 512, height = 64),
            OverlayCanvasConfiguration(width = 640, height = 96),
            OverlayCanvasConfiguration(width = 640, height = 128),
        )

        /** Stored values are untrusted and older installs may contain out-of-range dimensions. */
        fun sanitized(width: Int, height: Int, resolution: Int): OverlayCanvasConfiguration =
            OverlayCanvasConfiguration(
                width = width.coerceIn(MIN_WIDTH, MAX_WIDTH),
                height = height.coerceIn(MIN_HEIGHT, MAX_HEIGHT),
                resolution = resolution.coerceIn(MIN_RESOLUTION, MAX_RESOLUTION),
            )
    }
}

/** Actual WindowManager content geometry. The source resolution never stretches the window. */
data class OverlayWindowGeometry(
    val widthPixels: Int,
    val canvasHeightPixels: Int,
) {
    companion object {
        fun fit(
            canvas: OverlayCanvasConfiguration,
            density: Float,
            availableWidthPixels: Int,
        ): OverlayWindowGeometry {
            require(density.isFinite() && density > 0f) { "density must be finite and positive" }
            require(availableWidthPixels > 0) { "available width must be positive" }
            val preferred = (360f * density).roundToInt().coerceAtLeast(1)
            val width = minOf(preferred, availableWidthPixels).coerceAtLeast(1)
            val height = (width / canvas.aspectRatio).roundToInt().coerceAtLeast(1)
            return OverlayWindowGeometry(width, height)
        }
    }
}
