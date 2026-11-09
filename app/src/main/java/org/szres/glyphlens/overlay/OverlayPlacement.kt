package org.szres.glyphlens.overlay

import org.szres.glyphlens.settings.OverlayWindowGeometry
import kotlin.math.ceil
import kotlin.math.floor

/** Coordinates in the complete captured frame, supplied after all eleven nodes match. */
data class OverlayGameGeometry(
    val arrayTopFraction: Double,
    val timerBottomFraction: Double? = null,
    /** Placement only: a slot-derived estimate never supplies recognition or phase evidence. */
    val timerBottomEstimateFraction: Double? = null,
)

/** An array-only move can uncover the strip; a located timer then refines that default once. */
internal class OverlayPlacement {
    private var placedAboveArray = false
    private var placedUsingEstimate = false
    private var placedAfterTimer = false
    private var placedInitially = false
    private var movedByUser = false
    private var maximumHeight: Int? = null
    private data class DisplaySize(val width: Int, val height: Int, val density: Float)
    private var displaySize: DisplaySize? = null

    fun updateDisplay(width: Int, height: Int, density: Float) {
        val size = DisplaySize(width, height, density)
        val previous = displaySize
        if (size == previous) return
        displaySize = size
        if (previous == null || movedByUser) return
        placedAboveArray = false
        placedUsingEstimate = false
        placedAfterTimer = false
        placedInitially = false
        maximumHeight = null
    }

    fun userMoved() {
        movedByUser = true
    }

    fun initialTop(screenHeight: Int, windowHeight: Int, margin: Int): Int? {
        if (movedByUser || placedInitially || placedAboveArray) return null
        placedInitially = true
        val top = floor(screenHeight * 0.18).toInt()
        val bottom = floor(screenHeight * 0.32).toInt()
        return if (top + windowHeight <= bottom) top
        else (screenHeight - windowHeight - margin).coerceAtLeast(0)
    }

    fun maximumWindowHeight(geometry: OverlayGameGeometry?, screenHeight: Int, margin: Int): Int? {
        // A drag retains the current visual size; new recognition must not resize the user's window.
        if (movedByUser) return maximumHeight
        maximumHeight = if (geometry == null) {
            if (placedAboveArray) maximumHeight
            else floor(screenHeight * 0.32).toInt() - floor(screenHeight * 0.18).toInt()
        } else {
            val timer = geometry.timerBottomFraction ?: geometry.timerBottomEstimateFraction
            timer?.let {
                floor(geometry.arrayTopFraction * screenHeight).toInt() - ceil(it * screenHeight).toInt() - 2 * margin
            } ?: maximumHeight
        }
        return maximumHeight
    }

    fun fitCanvas(
        preferred: OverlayWindowGeometry,
        maximumWindowHeight: Int?,
        controlsHeight: Int,
        minimumWidth: Int,
        current: OverlayWindowGeometry = preferred,
    ): OverlayWindowGeometry {
        val maximumCanvasHeight = maximumWindowHeight?.minus(controlsHeight) ?: return preferred
        if (maximumCanvasHeight >= preferred.canvasHeightPixels) return preferred
        if (maximumCanvasHeight <= 0) return current
        val width = preferred.widthPixels * maximumCanvasHeight / preferred.canvasHeightPixels
        // A newly narrower gap must not expand an already placed window back over the array.
        if (width < minimumWidth) return current
        return OverlayWindowGeometry(width, maximumCanvasHeight)
    }

    fun top(
        geometry: OverlayGameGeometry,
        screenHeight: Int,
        windowHeight: Int,
        margin: Int,
    ): Int? {
        if (movedByUser || placedAfterTimer) return null
        if (geometry.timerBottomFraction == null && placedAboveArray &&
            (geometry.timerBottomEstimateFraction == null || placedUsingEstimate)
        ) return null
        val latestTop = floor(geometry.arrayTopFraction * screenHeight).toInt() - windowHeight - margin
        val timer = geometry.timerBottomFraction ?: geometry.timerBottomEstimateFraction
        val afterTimer = timer?.let { ceil(it * screenHeight).toInt() + margin }
        val top = afterTimer ?: latestTop
        if (top < 0 || top > latestTop) return null
        placedAboveArray = true
        placedAfterTimer = geometry.timerBottomFraction != null
        placedUsingEstimate = geometry.timerBottomEstimateFraction != null
        return top
    }
}
