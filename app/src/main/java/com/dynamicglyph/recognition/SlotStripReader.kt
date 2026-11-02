package com.dynamicglyph.recognition

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * Reads the slot indicator strip at the top of the glyph hack screen.
 *
 * Ported from Swift SlotStripReader.swift.
 *
 * The game draws one hexagonal indicator per Glyph in the sequence, centred on
 * the screen at a fixed pitch. Unselected indicators are violet; the one being
 * presented is gold. The same row is reused during player input, which is why a
 * selected indicator alone cannot identify presentation — the tracker requires
 * the unselected prelude first.
 */
class SlotStripReader(private val layout: GameUILayout = GameUILayout.ENGLISH_PORTRAIT) {

    data class Reading(
        val slotCount: Int,
        val highlightedIndex: Int?,
        val markerCentersX: List<Double>,
        val hasInputTimerBar: Boolean,
        val isPartlyCovered: Boolean
    )

    enum class Rejection {
        BAND_UNAVAILABLE,
        NO_MARKERS,
        TOO_MANY_MARKERS,
        UNEVEN_SPACING,
        INCONSISTENT_MARKER_WIDTHS,
        MULTIPLE_HIGHLIGHTS,
        INCOMPLETE_MARKER_HEIGHT,
        INCOMPLETE_STRIP,
        COVERED_BY_OVERLAY
    }

    sealed class Result {
        data class Success(val reading: Reading) : Result()
        data class Failure(val rejection: Rejection) : Result()
    }

    /**
     * A selected indicator contributes about 1900 gold and 0 violet pixels; an
     * unselected one about 1000 violet and 0 gold. These thresholds separate
     * cleanly on every fixture.
     */
    private fun isGold(r: Int, g: Int, b: Int): Boolean =
        r > 150 && g > 90 && b < 110 && r - b > 70

    private fun isViolet(r: Int, g: Int, b: Int): Boolean =
        b > 110 && b >= r && b >= g

    fun read(band: ColorImage.FrameTopBand): Result = read(band, belowOverlay = null)

    private fun read(band: ColorImage.FrameTopBand, belowOverlay: Int?): Result {
        val stripRows = band.rows(layout.slotStripTop, layout.slotStripBottom)
        if (stripRows.isEmpty()) return Result.Failure(Rejection.BAND_UNAVAILABLE)

        val image = band.image
        val width = image.width
        val litRows = litRowsFromTop(band, stripRows)
        val firstVisibleRow = firstRowBelowBanner(litRows, band)

        // A banner reaching the bottom hides the strip; it does not establish
        // that the game's indicators disappeared.
        if ((firstVisibleRow ?: stripRows.first) > stripRows.last) {
            return Result.Failure(Rejection.COVERED_BY_OVERLAY)
        }
        val firstRow = max(firstVisibleRow ?: stripRows.first, belowOverlay ?: stripRows.first)
        if (firstRow > stripRows.last) return Result.Failure(Rejection.COVERED_BY_OVERLAY)
        val rows = firstRow..stripRows.last

        // The game's indicators never light a whole row. A row that is, below any
        // banner, is the lower edge of a window over the indicators — including a
        // run at the very top of the strip too short to be a banner: a window
        // ending just above the strip lays its border across the indicator tips,
        // and every status colour of our own window's edge passes as indicator
        // violet or gold.
        val wholeRow = ceil(layout.bannerMinimumRowCoverage * width).toInt()
        val goldColumns = IntArray(width)
        val violetColumns = IntArray(width)
        val firstRows = IntArray(width) { image.height }
        val lastRows = IntArray(width) { -1 }
        var lastCoveredRow: Int? = null

        for (y in rows) {
            var lit = 0
            for (x in 0 until width) {
                val p = image.packed(x, y)
                val r = ColorImage.red(p)
                val g = ColorImage.green(p)
                val b = ColorImage.blue(p)
                if (max(r, max(g, b)) < 60) continue
                lit++
                if (isGold(r, g, b)) {
                    goldColumns[x]++
                } else if (isViolet(r, g, b)) {
                    violetColumns[x]++
                } else {
                    continue
                }
                if (y < firstRows[x]) firstRows[x] = y
                if (y > lastRows[x]) lastRows[x] = y
            }
            if (lit >= wholeRow) lastCoveredRow = y
        }

        val covered = lastCoveredRow
        if (covered != null) {
            // A thin overlay can end just inside the strip, leaving almost the
            // whole indicator row visible. Discard its border and shadow, then
            // require the ordinary full-height, count and geometry checks on what
            // remains. Never read the window's own glyphs as indicators.
            if (belowOverlay != null) return Result.Failure(Rejection.COVERED_BY_OVERLAY)
            val firstUncoveredRow = covered + 1 + layout.bannerEdgeTolerance(band.frameHeight)
            // An edge confined to the strip's first rows reaches no further than
            // the indicator tips, so it is not what hid anything missing below it.
            val endsAtStripTop = covered < stripRows.first + litRows
            return when (val retry = read(band, firstUncoveredRow)) {
                is Result.Success -> retry
                is Result.Failure -> Result.Failure(
                    if (endsAtStripTop) retry.rejection else Rejection.COVERED_BY_OVERLAY
                )
            }
        }

        // Group columns into markers.
        class Marker(val range: IntRange, val gold: Int, val violet: Int)

        val markers = mutableListOf<Marker>()
        var start: Int? = null
        var gold = 0
        var violet = 0
        for (x in 0..width) {
            val on = x < width &&
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
                val first = start
                if (first != null) {
                    markers.add(Marker(first..(x - 1), gold, violet))
                    start = null
                }
            }
        }

        val minimumWidth = layout.slotMarkerMinimumWidth * width
        val maximumWidth = layout.slotMarkerMaximumWidth * width
        val sized = markers.filter {
            val w = (it.range.last - it.range.first + 1).toDouble()
            w >= minimumWidth && w <= maximumWidth
        }

        if (sized.isEmpty()) {
            return Result.Failure(
                if (markers.isEmpty()) Rejection.NO_MARKERS
                else Rejection.INCONSISTENT_MARKER_WIDTHS
            )
        }
        // Every marker that survived the width filter must be a slot: a strip
        // with one indicator rejected as too wide is a misread, not a shorter
        // sequence, so demand that nothing was dropped.
        if (sized.size != markers.size) return Result.Failure(Rejection.INCONSISTENT_MARKER_WIDTHS)
        if (sized.size > SequenceTable.MAXIMUM_LENGTH) {
            return Result.Failure(Rejection.TOO_MANY_MARKERS)
        }

        val markerExtents = sized.map { marker ->
            var top = image.height
            var bottom = -1
            for (x in marker.range) {
                if (firstRows[x] < top) top = firstRows[x]
                if (lastRows[x] > bottom) bottom = lastRows[x]
            }
            Triple(top, bottom, marker.range.last - marker.range.first + 1)
        }

        // A strip under an overlay must retain nearly the full marker height.
        // Reading only the bottom fragments could otherwise silently shorten the
        // row. Below a banner every marker is cut by the same straight edge, so a
        // marker may instead run up to that edge, provided enough of it shows to
        // have kept its full width.
        for (extent in markerExtents) {
            val top = extent.first
            val bottom = extent.second
            val markerWidth = extent.third
            val height = (bottom - top + 1).toDouble()
            if (height >= markerWidth * 0.8) continue

            val visible = firstVisibleRow
            val allowed = belowOverlay == null && visible != null &&
                top <= visible + layout.bannerEdgeTolerance(band.frameHeight) &&
                height >= markerWidth * layout.coveredMarkerMinimumHeight
            if (!allowed) return Result.Failure(Rejection.INCOMPLETE_MARKER_HEIGHT)
        }

        val centers = sized.map { (it.range.first + it.range.last) / 2.0 }

        if (sized.size >= 3) {
            val pitches = (1 until centers.size).map { centers[it] - centers[it - 1] }
            val mean = pitches.sum() / pitches.size
            if (mean <= 0 || pitches.any { abs(it - mean) > mean * layout.slotPitchTolerance }) {
                return Result.Failure(Rejection.UNEVEN_SPACING)
            }
        }

        // The complete row is centred on the screen at a fixed pitch. Merely
        // counting remaining markers would turn four slots with the first two
        // covered into a supposedly verified two-slot sequence. A level 1
        // portal's hack has one Glyph: its lone indicator must sit at the centre,
        // which no marker left over from a longer row does.
        val midpoint = (centers.size - 1) / 2.0
        for (index in centers.indices) {
            val expected = 0.5 + (index - midpoint) * layout.slotCenterPitch
            if (abs(centers[index] / width - expected) > layout.slotCenterTolerance) {
                return Result.Failure(Rejection.INCOMPLETE_STRIP)
            }
        }

        val highlights = sized.indices.filter { sized[it].gold > sized[it].violet }
        if (highlights.size > 1) return Result.Failure(Rejection.MULTIPLE_HIGHLIGHTS)

        // Below a window's edge the indicators were cut only if they run up to
        // it; an edge that merely touches the top of the strip hid nothing.
        val cutBelowWindow = belowOverlay?.let { cut ->
            markerExtents.any { it.first <= cut }
        } ?: false

        return Result.Success(
            Reading(
                slotCount = sized.size,
                highlightedIndex = highlights.firstOrNull(),
                markerCentersX = centers.map { it / width },
                hasInputTimerBar = hasTimerBar(band),
                isPartlyCovered = firstVisibleRow != null || cutBelowWindow
            )
        )
    }

    /**
     * How many rows at the top of the strip something bright hangs over.
     *
     * A notification slides down over the header and the tops of the indicators
     * while the game carries on underneath, and a window placed just above the
     * strip can reach its first rows with its border. Across the strip either
     * lights almost every column of a row, while the game's own indicators leave
     * most of a row dark, so only a run of such rows starting at the top of the
     * strip counts.
     */
    private fun litRowsFromTop(band: ColorImage.FrameTopBand, rows: IntRange): Int {
        val image = band.image
        val required = ceil(layout.bannerMinimumRowCoverage * image.width).toInt()
        var count = 0
        for (y in rows) {
            var lit = 0
            for (x in 0 until image.width) {
                val p = image.packed(x, y)
                if (max(ColorImage.red(p), max(ColorImage.green(p), ColorImage.blue(p))) >= 60) lit++
            }
            if (lit < required) break
            count++
        }
        return count
    }

    /**
     * The first strip row below a banner hanging over the strip, or null when the
     * lit run is too short to be one.
     *
     * The banner's frosted glass passes a blurred, recoloured copy of the
     * indicators, which must not be read as indicators. A dark window, such as a
     * floating overlay, is not a banner: its lower edge inside the strip is
     * reported as [Rejection.COVERED_BY_OVERLAY].
     */
    private fun firstRowBelowBanner(count: Int, band: ColorImage.FrameTopBand): Int? {
        val stripTop = band.rows(layout.slotStripTop, layout.slotStripBottom).first
        val minimumRows = max(1, ceil(layout.bannerMinimumHeight * band.frameHeight).toInt())
        if (count < minimumRows) return null
        // The banner's lower edge is antialiased and shadowed.
        return stripTop + count + layout.bannerEdgeTolerance(band.frameHeight)
    }

    /**
     * The complete countdown track below the indicators. Its centred progress
     * shrinks and turns magenta near the end, while the violet rail still spans
     * the same screen coordinates. A window edge alone is not a timer.
     */
    fun hasTimerBar(band: ColorImage.FrameTopBand): Boolean {
        val rows = band.rows(layout.inputTimerBarTop, layout.inputTimerBarBottom)
        if (rows.isEmpty()) return false

        val image = band.image
        val required = (layout.inputTimerBarMinimumWidth * image.width).toInt()
        val tolerance = layout.inputTimerEndpointTolerance * image.width
        val expectedLeft = layout.inputTimerTrackLeft * image.width
        val expectedRight = layout.inputTimerTrackRight * image.width
        // A few compression/antialiasing pixels may interrupt the thin rail.
        val maximumGap = max(1, (image.width * 0.005).toInt())

        for (y in rows) {
            var start: Int? = null
            var last = -1
            var progressRun = 0
            var longestProgressRun = 0

            fun matchesTrack(): Boolean {
                val s = start ?: return false
                return abs(s - expectedLeft) <= tolerance &&
                    abs(last - expectedRight) <= tolerance &&
                    longestProgressRun >= required
            }

            for (x in 0 until image.width) {
                val p = image.packed(x, y)
                val r = ColorImage.red(p)
                val g = ColorImage.green(p)
                val b = ColorImage.blue(p)
                val magenta = r > 130 && b > 65 && r - g > 45 && b - g > 20
                val progress = isGold(r, g, b) || magenta

                if (progress || isViolet(r, g, b)) {
                    if (start == null) start = x
                    last = x
                    progressRun = if (progress) progressRun + 1 else 0
                    if (progressRun > longestProgressRun) longestProgressRun = progressRun
                } else {
                    progressRun = 0
                    if (start != null && x - last > maximumGap) {
                        if (matchesTrack()) return true
                        start = null
                        longestProgressRun = 0
                    }
                }
            }
            if (matchesTrack()) return true
        }
        return false
    }
}
