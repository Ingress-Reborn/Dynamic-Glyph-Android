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
    /** Corroborating player-input countdown evidence; header text names the phase. */
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
    fun read(frame: ImageFrame): Result<SlotStripReading> = read(frame.topBand)

    fun read(band: FrameTopBand): Result<SlotStripReading> = read(band, null)

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
                val (red, green, blue) = image[x, y]
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
        return Result.success(
            SlotStripReading(
                slotCount = sized.size,
                highlightedIndex = highlights.firstOrNull(),
                markerCentersX = centers.map { it / image.width },
                hasInputTimerBar = hasTimerBar(band),
                isPartlyCovered = firstVisibleRow != null || cutBelowWindow,
            )
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
                val (red, green, blue) = image[x, y]
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
        val rows = band.rows(layout.inputTimerBarTop, layout.inputTimerBarBottom)
        if (rows.isEmpty()) return null
        val image = band.image
        val left = (layout.contentLeft * image.width).toInt()
        val right = (layout.contentRight * image.width).toInt()
        val width = right - left
        val required = (layout.inputTimerBarMinimumWidth * width).toInt()
        val tolerance = layout.inputTimerEndpointTolerance * width
        val expectedLeft = left + layout.inputTimerTrackLeft * width
        val expectedRight = left + layout.inputTimerTrackRight * width
        val maximumGap = max(1, (width * 0.005).toInt())
        for (y in rows) {
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
                val (red, green, blue) = image[x, y]
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
                        if (matchesTrack()) return y
                        start = null
                        longestProgressRun = 0
                    }
                }
            }
            if (matchesTrack()) return y
        }
        return null
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
