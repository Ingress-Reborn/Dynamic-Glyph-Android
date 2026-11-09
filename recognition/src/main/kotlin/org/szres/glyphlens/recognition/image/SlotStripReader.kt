package org.szres.glyphlens.recognition.image

import org.szres.glyphlens.recognition.SequenceTable
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/** What the row of slot indicators shows, independent of shape matching. */
data class SlotStripReading(
    val slotCount: Int,
    val highlightedIndex: Int?,
    /** Indicator centres as fractions of frame width, left to right. */
    val markerCentersX: List<Double>,
    /** Independent evidence of the player-input countdown track. */
    val hasInputTimerBar: Boolean,
    val isPartlyCovered: Boolean = false,
)

/** Why a strip could not be read, retained rather than collapsed into null. */
sealed class SlotStripRejection(message: String) : Exception(message) {
    data object BandUnavailable : SlotStripRejection("bandUnavailable")
    data object NoMarkers : SlotStripRejection("noMarkers")
    data object TooManyMarkers : SlotStripRejection("tooManyMarkers")
    data object UnevenSpacing : SlotStripRejection("unevenSpacing")
    data object InconsistentMarkerWidths : SlotStripRejection("inconsistentMarkerWidths")
    data object MultipleHighlights : SlotStripRejection("multipleHighlights")
    data object IncompleteMarkerHeight : SlotStripRejection("incompleteMarkerHeight")
    data object IncompleteStrip : SlotStripRejection("incompleteStrip")
    data object CoveredByOverlay : SlotStripRejection("coveredByOverlay")
}

/** Finds the hexagonal slot indicators and countdown track by colour. */
class SlotStripReader(val layout: GameUILayout = GameUILayout()) {
    private data class CachedMarker(val columns: IntRange, val top: Int, val bottom: Int, val probes: IntArray)
    private data class CachedStrip(
        val width: Int,
        val height: Int,
        val frameHeight: Int,
        val layout: GameUILayout,
        val markers: List<CachedMarker>,
        val centers: List<Double>,
        val guardRows: IntArray,
    )

    private var cachedStrip: CachedStrip? = null
    private var cachedTimerRow: Int? = null
    private var timerWidth = 0
    private var timerHeight = 0
    private var timerFrameHeight = 0
    private var timerLayout: GameUILayout? = null

    var lastPixelReadCount = 0
        private set
    var usedCachedMarkers = false
        private set

    fun read(frame: ImageFrame): Result<SlotStripReading> = read(frame.topBand)

    fun read(band: FrameTopBand): Result<SlotStripReading> {
        lastPixelReadCount = 0
        usedCachedMarkers = false
        cachedStrip?.let { cached ->
            if (cached.width == band.image.width && cached.height == band.image.height &&
                cached.frameHeight == band.frameHeight && cached.layout == layout
            ) readCached(band, cached)?.let {
                usedCachedMarkers = true
                return Result.success(it)
            }
        }
        cachedStrip = null
        return read(band, null)
    }

    private fun read(band: FrameTopBand, belowOverlay: Int?): Result<SlotStripReading> {
        val stripRows = band.rows(layout.slotStripTop, layout.slotStripBottom)
        if (stripRows.isEmpty()) return failure(SlotStripRejection.BandUnavailable)
        val image = band.image
        val left = (layout.contentLeft * image.width).toInt()
        val right = (layout.contentRight * image.width).toInt()
        val width = right - left
        val litRows = litRowsFromTop(band, stripRows)
        val firstVisibleRow = firstRowBelowBanner(litRows, band)
        val stripEnd = stripRows.last + 1
        if ((firstVisibleRow ?: stripRows.first) >= stripEnd) {
            return failure(SlotStripRejection.CoveredByOverlay)
        }
        val firstRow = max(firstVisibleRow ?: stripRows.first, belowOverlay ?: stripRows.first)
        if (firstRow >= stripEnd) return failure(SlotStripRejection.CoveredByOverlay)
        val rows = firstRow until stripEnd

        val wholeRow = ceil(layout.bannerMinimumRowCoverage * width).toInt()
        val goldColumns = IntArray(image.width)
        val violetColumns = IntArray(image.width)
        val firstRows = IntArray(image.width) { image.height }
        val lastRows = IntArray(image.width) { -1 }
        var lastCoveredRow: Int? = null
        for (y in rows) {
            var lit = 0
            for (x in left until right) {
                val (red, green, blue) = pixel(image, x, y)
                if (max(red, max(green, blue)) < 60) continue
                lit++
                if (isGold(red, green, blue)) goldColumns[x]++
                else if (isViolet(red, green, blue)) violetColumns[x]++
                else continue
                firstRows[x] = minOf(firstRows[x], y)
                lastRows[x] = maxOf(lastRows[x], y)
            }
            if (lit >= wholeRow) lastCoveredRow = y
        }

        lastCoveredRow?.let { coveredRow ->
            // A thin window edge is discarded once, then ordinary checks apply below it.
            if (belowOverlay != null) return failure(SlotStripRejection.CoveredByOverlay)
            val firstUncoveredRow = coveredRow + 1 + layout.bannerEdgeTolerance(band.frameHeight)
            val endsAtStripTop = coveredRow < stripRows.first + litRows
            val reread = read(band, firstUncoveredRow)
            return if (reread.isSuccess) reread else failure(
                if (endsAtStripTop) reread.exceptionOrNull() as SlotStripRejection
                else SlotStripRejection.CoveredByOverlay
            )
        }

        data class Marker(val range: IntRange, val gold: Int, val violet: Int)

        val markers = mutableListOf<Marker>()
        var start: Int? = null
        var gold = 0
        var violet = 0
        for (x in left..right) {
            val on = x < right &&
                goldColumns[x] + violetColumns[x] >= layout.slotMarkerMinimumColumnHits
            if (on) {
                if (start == null) {
                    start = x
                    gold = 0
                    violet = 0
                }
                gold += goldColumns[x]
                violet += violetColumns[x]
            } else {
                start?.let { first ->
                    markers.add(Marker(first until x, gold, violet))
                    start = null
                }
            }
        }

        val minimumWidth = layout.slotMarkerMinimumWidth * width
        val maximumWidth = layout.slotMarkerMaximumWidth * width
        val sized = markers.filter { marker ->
            val markerWidth = marker.range.last - marker.range.first + 1
            markerWidth >= minimumWidth && markerWidth <= maximumWidth
        }
        if (sized.isEmpty()) {
            return failure(
                if (markers.isEmpty()) SlotStripRejection.NoMarkers
                else SlotStripRejection.InconsistentMarkerWidths
            )
        }
        if (sized.size != markers.size) return failure(SlotStripRejection.InconsistentMarkerWidths)
        if (sized.size > SequenceTable.maximumLength) return failure(SlotStripRejection.TooManyMarkers)

        data class Extent(val top: Int, val bottom: Int, val width: Int)

        val markerExtents = sized.map { marker ->
            Extent(
                top = marker.range.minOf { firstRows[it] },
                bottom = marker.range.maxOf { lastRows[it] },
                width = marker.range.last - marker.range.first + 1,
            )
        }
        val completeHeight = markerExtents.all { extent ->
            val height = (extent.bottom - extent.top + 1).toDouble()
            if (height >= extent.width * 0.8) return@all true
            if (belowOverlay != null || firstVisibleRow == null) return@all false
            extent.top <= firstVisibleRow + layout.bannerEdgeTolerance(band.frameHeight) &&
                height >= extent.width * layout.coveredMarkerMinimumHeight
        }
        if (!completeHeight) return failure(SlotStripRejection.IncompleteMarkerHeight)

        val centers = sized.map { (it.range.first + it.range.last) / 2.0 }
        if (sized.size >= 3) {
            val pitches = centers.zipWithNext().map { (a, b) -> b - a }
            val mean = pitches.average()
            if (mean <= 0.0 || pitches.any { abs(it - mean) > mean * layout.slotPitchTolerance }) {
                return failure(SlotStripRejection.UnevenSpacing)
            }
        }

        val midpoint = (centers.size - 1) / 2.0
        val completeStrip = centers.withIndex().all { (index, center) ->
            val expected = 0.5 + (index - midpoint) * layout.slotCenterPitch
            abs((center - left) / width - expected) <= layout.slotCenterTolerance
        }
        if (!completeStrip) return failure(SlotStripRejection.IncompleteStrip)

        val highlights = sized.indices.filter { sized[it].gold > sized[it].violet }
        if (highlights.size > 1) return failure(SlotStripRejection.MultipleHighlights)

        val cutBelowWindow = belowOverlay?.let { cut ->
            markerExtents.any { it.top <= cut }
        } ?: false
        val partlyCovered = firstVisibleRow != null || cutBelowWindow
        if (!partlyCovered) {
            val cachedMarkers = sized.mapIndexed { index, marker ->
                val extent = markerExtents[index]
                val colored = mutableListOf<Int>()
                for (y in extent.top..extent.bottom) {
                    for (x in marker.range) {
                        val (red, green, blue) = pixel(image, x, y)
                        if (isGold(red, green, blue) || isViolet(red, green, blue)) colored += y * image.width + x
                    }
                }
                val probes = IntArray(minOf(16, colored.size)) { probe ->
                    colored[probe * (colored.size - 1) / (minOf(16, colored.size) - 1)]
                }
                CachedMarker(marker.range, extent.top, extent.bottom, probes)
            }
            val top = markerExtents.minOf { it.top }
            val bottom = markerExtents.maxOf { it.bottom }
            cachedStrip = CachedStrip(
                image.width, image.height, band.frameHeight, layout.copy(), cachedMarkers,
                centers.map { it / image.width },
                intArrayOf(top + (bottom - top) / 4, (top + bottom) / 2, bottom - (bottom - top) / 4),
            )
        }
        return Result.success(
            SlotStripReading(
                slotCount = sized.size,
                highlightedIndex = highlights.firstOrNull(),
                markerCentersX = centers.map { it / image.width },
                hasInputTimerBar = findTimerRow(band) != null,
                isPartlyCovered = partlyCovered,
            )
        )
    }

    private fun readCached(band: FrameTopBand, cached: CachedStrip): SlotStripReading? {
        val image = band.image
        val gold = IntArray(cached.markers.size)
        val violet = IntArray(cached.markers.size)
        for ((index, marker) in cached.markers.withIndex()) {
            for (position in marker.probes) {
                val (red, green, blue) = pixel(image, position % image.width, position / image.width)
                when {
                    isGold(red, green, blue) -> gold[index]++
                    isViolet(red, green, blue) -> violet[index]++
                    else -> return null
                }
            }
        }
        val left = (layout.contentLeft * image.width).toInt()
        val right = (layout.contentRight * image.width).toInt()
        val step = max(1, (right - left) / 300)
        for (y in cached.guardRows) {
            var lit = 0
            var sampled = 0
            for (x in left until right step step) {
                val (red, green, blue) = pixel(image, x, y)
                sampled++
                if (max(red, max(green, blue)) >= 60) lit++
                val isGold = isGold(red, green, blue)
                val isViolet = isViolet(red, green, blue)
                if (!isGold && !isViolet) continue
                val index = cached.markers.indexOfFirst { x in it.columns }
                if (index < 0) return null
                if (isGold) gold[index]++ else violet[index]++
            }
            if (lit >= ceil(layout.bannerMinimumRowCoverage * sampled).toInt()) return null
        }
        for ((index, marker) in cached.markers.withIndex()) {
            // The active hexagon can retain a violet outer ring around a detached gold outline.
            if (gold[index] == 0 || violet[index] == 0) continue
            gold[index] = 0
            violet[index] = 0
            for (y in marker.top..marker.bottom) {
                for (x in marker.columns) {
                    val (red, green, blue) = pixel(image, x, y)
                    if (isGold(red, green, blue)) gold[index]++
                    else if (isViolet(red, green, blue)) violet[index]++
                }
            }
        }
        val highlights = cached.markers.indices.filter { gold[it] > violet[it] }
        if (highlights.size > 1) return null
        return SlotStripReading(
            cached.markers.size, highlights.firstOrNull(), cached.centers,
            hasInputTimerBar = findTimerRow(band) != null,
        )
    }

    /** How many rows at the top of the strip something bright hangs over. */
    internal fun litRowsFromTop(band: FrameTopBand, rows: IntRange): Int {
        val image = band.image
        val left = (layout.contentLeft * image.width).toInt()
        val right = (layout.contentRight * image.width).toInt()
        val required = ceil(layout.bannerMinimumRowCoverage * (right - left)).toInt()
        var count = 0
        for (y in rows) {
            var lit = 0
            for (x in left until right) {
                val (red, green, blue) = pixel(image, x, y)
                if (max(red, max(green, blue)) >= 60) lit++
            }
            if (lit < required) break
            count++
        }
        return count
    }

    /** First row below a banner, or null when the bright run is too short to be one. */
    internal fun firstRowBelowBanner(litRowsFromTop: Int, band: FrameTopBand): Int? {
        val stripTop = band.rows(layout.slotStripTop, layout.slotStripBottom).first
        val minimumRows = max(1, ceil(layout.bannerMinimumHeight * band.frameHeight).toInt())
        if (litRowsFromTop < minimumRows) return null
        return stripTop + litRowsFromTop + layout.bannerEdgeTolerance(band.frameHeight)
    }

    fun hasTimerBar(frame: ImageFrame): Boolean = hasTimerBar(frame.topBand)

    /** The complete countdown track; a window edge alone is not a timer. */
    fun hasTimerBar(band: FrameTopBand): Boolean = timerRow(band) != null

    internal fun timerRow(band: FrameTopBand): Int? {
        lastPixelReadCount = 0
        return findTimerRow(band)
    }

    private fun findTimerRow(band: FrameTopBand): Int? {
        val rows = band.rows(layout.inputTimerBarTop, layout.inputTimerBarBottom)
        if (rows.isEmpty()) return null
        val image = band.image
        if (timerWidth != image.width || timerHeight != image.height || timerFrameHeight != band.frameHeight ||
            timerLayout != layout
        ) {
            cachedTimerRow = null
            timerWidth = image.width
            timerHeight = image.height
            timerFrameHeight = band.frameHeight
            timerLayout = layout.copy()
        }
        cachedTimerRow?.let { row ->
            if (row in rows && matchesTimerRow(image, row)) return row
        }
        cachedTimerRow = null
        val left = (layout.contentLeft * image.width).toInt()
        val right = (layout.contentRight * image.width).toInt()
        val width = right - left
        val probes = doubleArrayOf(0.25, 0.50, 0.75).map {
            (left + width * (layout.inputTimerTrackLeft + it * (layout.inputTimerTrackRight - layout.inputTimerTrackLeft))).toInt()
        }
        val maximumGap = max(1, (width * 0.005).toInt())
        for (y in rows) {
            if (probes.any { x -> (x..x + maximumGap).none { probe -> isTimerColor(pixel(image, probe, y)) } }) continue
            if (matchesTimerRow(image, y)) {
                cachedTimerRow = y
                return y
            }
        }
        return null
    }

    private fun matchesTimerRow(image: ColorImage, y: Int): Boolean {
        val left = (layout.contentLeft * image.width).toInt()
        val right = (layout.contentRight * image.width).toInt()
        val width = right - left
        val required = (layout.inputTimerBarMinimumWidth * width).toInt()
        val tolerance = layout.inputTimerEndpointTolerance * width
        val expectedLeft = left + layout.inputTimerTrackLeft * width
        val expectedRight = left + layout.inputTimerTrackRight * width
        val maximumGap = max(1, (width * 0.005).toInt())
        var start: Int? = null
        var last = -1
        var progressRun = 0
        var longestProgressRun = 0

        fun matchesTrack(): Boolean {
            val first = start ?: return false
            return abs(first - expectedLeft) <= tolerance &&
                abs(last - expectedRight) <= tolerance &&
                longestProgressRun >= required
        }

        for (x in left until right) {
            val (red, green, blue) = pixel(image, x, y)
            val magenta = red > 130 && blue > 65 && red - green > 45 && blue - green > 20
            val progress = isGold(red, green, blue) || magenta
            if (progress || isViolet(red, green, blue)) {
                if (start == null) start = x
                last = x
                progressRun = if (progress) progressRun + 1 else 0
                longestProgressRun = max(longestProgressRun, progressRun)
            } else {
                progressRun = 0
                if (start != null && x - last > maximumGap) {
                    if (matchesTrack()) return true
                    start = null
                    longestProgressRun = 0
                }
            }
        }
        return matchesTrack()
    }

    private fun isTimerColor(pixel: RgbPixel): Boolean =
        isGold(pixel.red, pixel.green, pixel.blue) || isViolet(pixel.red, pixel.green, pixel.blue) ||
            pixel.red > 130 && pixel.blue > 65 && pixel.red - pixel.green > 45 && pixel.blue - pixel.green > 20

    private fun pixel(image: ColorImage, x: Int, y: Int): RgbPixel {
        lastPixelReadCount++
        return image[x, y]
    }

    companion object {
        internal fun isGold(red: Int, green: Int, blue: Int): Boolean =
            red > 150 && green > 90 && blue < 110 && red - blue > 70

        internal fun isViolet(red: Int, green: Int, blue: Int): Boolean =
            blue > 110 && blue >= red && blue >= green

        private fun failure(rejection: SlotStripRejection): Result<SlotStripReading> =
            Result.failure(rejection)
    }
}
