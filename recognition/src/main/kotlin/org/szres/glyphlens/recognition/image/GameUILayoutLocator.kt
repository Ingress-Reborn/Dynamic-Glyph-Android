package org.szres.glyphlens.recognition.image

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Finds the existing indicator/track geometry inside a captured viewport. */
class GameUILayoutLocator {
    var layout: GameUILayout = GameUILayout()
        private set
    var lastSlotResult: Result<SlotStripReading> = Result.failure(SlotStripRejection.NoMarkers)
        private set
    var lastSlotPixelReadCount = 0
        private set
    var lastSlotUsedCache = false
        private set

    private var slotReader = SlotStripReader(layout)
    private var located = false
    private var timerLocated = false
    val hasLocatedTimer: Boolean get() = timerLocated
    private var anchor: NodeArrayDetection? = null
    private var width = 0
    private var height = 0
    private var nextSearch = Double.NEGATIVE_INFINITY
    private var nextTimerSearch = Double.NEGATIVE_INFINITY

    fun reset() {
        layout = GameUILayout()
        lastSlotResult = Result.failure(SlotStripRejection.NoMarkers)
        lastSlotPixelReadCount = 0
        lastSlotUsedCache = false
        slotReader = SlotStripReader(layout)
        located = false
        timerLocated = false
        anchor = null
        width = 0
        height = 0
        nextSearch = Double.NEGATIVE_INFINITY
        nextTimerSearch = Double.NEGATIVE_INFINITY
    }

    /** The image must contain the whole frame, including bands outside the old iOS crop. */
    fun observe(image: ColorImage, calibration: NodeArrayDetection?, sourceTime: Double): GameUILayout {
        if (width != image.width || height != image.height || geometryChanged(calibration)) reset()
        width = image.width
        height = image.height
        if (calibration != null) anchor = calibration
        val band = FrameTopBand(image, image.height)
        lastSlotResult = slotReader.read(band)
        val reading = lastSlotResult.getOrNull()
        val fullWidthArray = calibration != null && abs(calibration.size.x / 0.75 - width) <= width * 0.025
        if (reading != null && (located || fullWidthArray)) {
            located = true
        } else if (calibration != null && sourceTime >= nextSearch) {
            nextSearch = sourceTime + 0.5
            locateStrip(image, calibration)?.let {
                layout = it.layout
                slotReader = it.reader
                lastSlotResult = it.result
                located = true
                timerLocated = false
                nextTimerSearch = Double.NEGATIVE_INFINITY
            }
        }
        lastSlotPixelReadCount = slotReader.lastPixelReadCount
        lastSlotUsedCache = slotReader.usedCachedMarkers
        if (lastSlotResult.getOrNull()?.hasInputTimerBar == true) timerLocated = true
        if (located && !timerLocated && sourceTime >= nextTimerSearch) {
            nextTimerSearch = sourceTime + 0.5
            locateTimer(band, calibration)?.let {
                layout = it
                slotReader = SlotStripReader(layout)
                lastSlotResult = lastSlotResult.map { reading -> reading.copy(hasInputTimerBar = true) }
                timerLocated = true
            }
        }
        return layout
    }

    private fun geometryChanged(fit: NodeArrayDetection?): Boolean {
        val previous = anchor ?: return false
        if (fit == null) return false
        val tolerance = max(2.0, previous.size.x * 0.005)
        return abs(fit.origin.x - previous.origin.x) > tolerance ||
            abs(fit.origin.y - previous.origin.y) > tolerance ||
            abs(fit.size.x - previous.size.x) > tolerance ||
            abs(fit.size.y - previous.size.y) > tolerance
    }

    private data class Marker(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left + 1
        val height get() = bottom - top + 1
        val centerX get() = (left + right) / 2.0
        val centerY get() = (top + bottom) / 2.0
    }

    private data class LocatedStrip(
        val layout: GameUILayout,
        val reader: SlotStripReader,
        val result: Result<SlotStripReading>,
    )

    private fun locateStrip(image: ColorImage, fit: NodeArrayDetection): LocatedStrip? {
        val contentWidth = fit.size.x / 0.75
        val centerX = fit.origin.x + fit.size.x / 2
        val bottom = min(image.height, fit.origin.y.toInt())
        val markers = markers(image, bottom, contentWidth)
        val band = FrameTopBand(image, image.height)
        for (marker in markers) {
            val row = markers.filter {
                abs(it.centerY - marker.centerY) <= min(it.height, marker.height) * 0.25
            }.sortedBy { it.centerX }
            if (row.size !in 1..5) continue
            val pitch = row.zipWithNext { a, b -> b.centerX - a.centerX }.sorted()
            val rowWidth = if (pitch.isEmpty()) contentWidth else pitch[pitch.size / 2] / 0.16
            val rowCenter = (row.first().centerX + row.last().centerX) / 2
            if (abs(rowCenter - centerX) > rowWidth * 0.035) continue
            val left = max(0.0, centerX - rowWidth / 2)
            val right = min(image.width.toDouble(), centerX + rowWidth / 2)
            if (right - left < rowWidth * 0.95) continue
            // Convert the original measured slot/track spacing into width-scaled pixels;
            // this is not an assumed frame aspect ratio. The track is then located by colour.
            val referenceHeight = rowWidth * 1311.0 / 603.0
            val margin = max(2.0, rowWidth * 0.006)
            val top = max(0.0, row.minOf { it.top } - margin)
            val stripBottom = min(image.height.toDouble(), row.maxOf { it.bottom } + 1 + margin)
            val candidate = GameUILayout(
                contentLeft = left / image.width,
                contentRight = right / image.width,
                slotStripTop = top / image.height,
                slotStripBottom = stripBottom / image.height,
                inputTimerBarTop = min(image.height - 1.0, top + referenceHeight * 0.114) / image.height,
                inputTimerBarBottom = min(image.height.toDouble(), top + referenceHeight * 0.142) / image.height,
            )
            val reader = SlotStripReader(candidate)
            val result = reader.read(band)
            if (result.isSuccess) return LocatedStrip(candidate, reader, result)
        }
        return null
    }

    private fun locateTimer(band: FrameTopBand, fit: NodeArrayDetection?): GameUILayout? {
        val bottom = min(fit?.origin?.y ?: (height * 0.6), height.toDouble()) / height
        if (bottom <= layout.slotStripBottom) return null
        val search = layout.copy(inputTimerBarTop = layout.slotStripBottom, inputTimerBarBottom = bottom)
        val row = SlotStripReader(search).timerRow(band) ?: return null
        val margin = max(3, ceil((layout.contentRight - layout.contentLeft) * width * 0.015).toInt())
        return layout.copy(
            inputTimerBarTop = max(0, row - margin).toDouble() / height,
            inputTimerBarBottom = min(height, row + margin + 1).toDouble() / height,
        )
    }

    private fun markers(image: ColorImage, bottom: Int, contentWidth: Double): List<Marker> {
        val visited = BooleanArray(image.width * bottom)
        val stack = ArrayDeque<Int>()
        val found = mutableListOf<Marker>()
        val minimum = contentWidth * 0.085
        val maximum = contentWidth * 0.190
        fun colored(index: Int): Boolean {
            val (red, green, blue) = image[index]
            return SlotStripReader.isGold(red, green, blue) || SlotStripReader.isViolet(red, green, blue)
        }
        for (start in visited.indices) {
            if (visited[start]) continue
            visited[start] = true
            if (!colored(start)) continue
            stack.addLast(start)
            var left = image.width
            var right = 0
            var top = bottom
            var last = 0
            while (stack.isNotEmpty()) {
                val index = stack.removeLast()
                val x = index % image.width
                val y = index / image.width
                left = min(left, x)
                right = max(right, x)
                top = min(top, y)
                last = max(last, y)
                for (ny in max(0, y - 1)..min(bottom - 1, y + 1)) {
                    for (nx in max(0, x - 1)..min(image.width - 1, x + 1)) {
                        val next = ny * image.width + nx
                        if (visited[next]) continue
                        visited[next] = true
                        if (colored(next)) stack.addLast(next)
                    }
                }
            }
            val marker = Marker(left, top, right, last)
            if (marker.width >= minimum && marker.width <= maximum &&
                marker.height >= marker.width * 0.8 && marker.height <= marker.width * 1.4
            ) found += marker
        }
        // The highlighted hexagon has a detached inner outline in actual captures.
        // Its bounding box belongs to the surrounding marker, not a sixth slot.
        return found.filter { marker ->
            found.none { outer ->
                outer !== marker && outer.left <= marker.left && outer.right >= marker.right &&
                    outer.top <= marker.top && outer.bottom >= marker.bottom
            }
        }
    }
}
