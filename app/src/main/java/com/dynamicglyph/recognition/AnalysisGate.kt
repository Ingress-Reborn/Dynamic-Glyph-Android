package com.dynamicglyph.recognition

import kotlin.math.abs
import kotlin.math.max

/**
 * Frame-level brightness gate and analysis rate controller.
 * Ported from Swift AnalysisGate.swift.
 *
 * The gate decides whether a frame is worth full analysis. The rate controller
 * decides how often to analyse at all: 20 Hz inside a hack, about 4 Hz when
 * nothing is happening.
 */

/** What one look at a frame says about it. */
class LumaReading(
    /** Over the array area: share of samples darker than [LumaGate.DARK_LEVEL]. */
    val darkFraction: Double,
    /** Over the array area: share of samples brighter than [LumaGate.BRIGHT_LEVEL]. */
    val brightFraction: Double,
    /** Mean luminance of each cell of a coarse grid over the whole frame. */
    val cells: IntArray,
    /** Whether the frame may show a screen any reader cares about. */
    val isCandidate: Boolean
) {
    /** The mean of the cells' means: the frame's overall brightness. */
    val meanLuma: Double
        get() {
            if (cells.isEmpty()) return 0.0
            var total = 0L
            for (value in cells) total += value
            return total.toDouble() / cells.size
        }

    /** The largest change of any cell's mean. */
    fun differenceFrom(other: LumaReading): Int {
        if (cells.size != other.cells.size || cells.isEmpty()) return Int.MAX_VALUE
        var largest = 0
        for (index in cells.indices) {
            largest = max(largest, abs(cells[index] - other.cells[index]))
        }
        return largest
    }
}

class LumaGate(private val step: Int = 4) {

    /**
     * Read the gate. `luma` must return full-range luminance (0 black,
     * 255 white) for a pixel.
     */
    fun read(width: Int, height: Int, luma: (Int, Int) -> Int): LumaReading? {
        if (width <= 0 || height <= 0) return null

        val columns = GRID_COLUMNS
        val rows = GRID_ROWS
        val sums = IntArray(columns * rows)
        val counts = IntArray(columns * rows)

        val top = (ARRAY_ROWS_FIRST * height).toInt()
        val bottom = (ARRAY_ROWS_LAST * height).toInt()
        val left = (ARRAY_COLUMNS_FIRST * width).toInt()
        val right = (ARRAY_COLUMNS_LAST * width).toInt()

        var dark = 0
        var bright = 0
        var area = 0

        var y = step / 2
        while (y < height) {
            val row = minOf(rows - 1, y * rows / height) * columns
            val inArrayRows = y >= top && y < bottom
            var x = step / 2
            while (x < width) {
                val value = luma(x, y)
                val cell = row + minOf(columns - 1, x * columns / width)
                sums[cell] += value
                counts[cell]++
                if (inArrayRows && x >= left && x < right) {
                    area++
                    if (value < DARK_LEVEL) dark++ else if (value > BRIGHT_LEVEL) bright++
                }
                x += step
            }
            y += step
        }

        val cells = IntArray(sums.size) { index ->
            if (counts[index] > 0) sums[index] / counts[index] else 0
        }
        val darkFraction = if (area > 0) dark.toDouble() / area else 1.0
        val brightFraction = if (area > 0) bright.toDouble() / area else 0.0

        return LumaReading(
            darkFraction = darkFraction,
            brightFraction = brightFraction,
            cells = cells,
            // Either one makes a frame a candidate. Game screens are at most
            // 0.15 bright; map frames at least 0.30 bright, and at most 0.44
            // dark even with their background shifted darker.
            isCandidate = darkFraction >= MINIMUM_DARK_FRACTION ||
                brightFraction <= MAXIMUM_BRIGHT_FRACTION
        )
    }

    companion object {
        const val DARK_LEVEL = 20
        const val BRIGHT_LEVEL = 60
        const val MINIMUM_DARK_FRACTION = 0.6
        const val MAXIMUM_BRIGHT_FRACTION = 0.25

        /** Where the hack draws its array. */
        const val ARRAY_ROWS_FIRST = 0.30
        const val ARRAY_ROWS_LAST = 0.85
        const val ARRAY_COLUMNS_FIRST = 0.03
        const val ARRAY_COLUMNS_LAST = 0.97

        const val GRID_COLUMNS = 12
        const val GRID_ROWS = 24

        /**
         * BT.601 luminance of a packed ARGB pixel.
         *
         * The original also stretches Rec.709 video range (16..235) to full
         * range, because CoreVideo hands it video-range buffers. Android's
         * `ImageReader` in `RGBA_8888` is full range already, so stretching here
         * would push every pixel up: a mid grey of 128 would become 130 and, more
         * importantly, a genuinely dark 16 would become 0 while a 30 would become
         * 16, quietly moving the gate's own thresholds. No stretch is applied.
         */
        fun lumaOfArgb(argb: Int): Int {
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            return (77 * r + 150 * g + 29 * b) shr 8
        }
    }
}

/**
 * Decides how often to analyse, and why.
 */
class AnalysisRateController(val policy: Policy = Policy()) {

    data class Policy(
        /** 20 Hz less a millisecond of timestamp jitter. */
        val activeInterval: Double = 0.049,
        /** About 4 Hz, resting or quiet. */
        val quietInterval: Double = 0.249,
        /** Halved, for low power mode or a hot device, outside a hack only. */
        val constrainedActiveInterval: Double = 0.099,
        val constrainedQuietInterval: Double = 0.499,
        /** How long nothing must happen before analysis slows down. */
        val settle: Double = 2.0,
        /** A larger change in any cell's mean counts as a change of screen. */
        val changeThreshold: Int = 3,
        /** A candidate whose mean luma moves this much shows another screen. */
        val switchMeanJump: Double = 8.0,
        /** Covers a fade spread over a few 20 Hz looks. */
        val switchWindow: Double = 0.3,
        /** Seconds at the active rate after a switch of screens. */
        val switchBurst: Double = 1.0
    )

    enum class Mode { ACTIVE, RESTING, QUIET }

    data class Decision(val mode: Mode, val interval: Double)

    var mode: Mode = Mode.ACTIVE
        private set
    var enteredCandidate: Boolean = false
        private set
    var switchedScreen: Boolean = false
        private set

    private val recentMeans = ArrayDeque<Pair<Double, Double>>()
    private var burstUntil = Double.NEGATIVE_INFINITY
    private var quietSince: Double? = null
    private var lastCandidate = true
    private var lastTime = Double.NEGATIVE_INFINITY

    /**
     * What this frame should get, or null to skip it.
     *
     * `inHack` keeps the full rate even when `constrained`, because the blank
     * between two Glyphs lasts about 100 ms and needs two samples.
     */
    fun decide(
        time: Double,
        reading: LumaReading?,
        inHack: Boolean,
        constrained: Boolean
    ): Decision? {
        enteredCandidate = false
        switchedScreen = false

        if (reading != null) {
            // Screen-switch detection: a candidate whose overall brightness
            // differs from any look in the window shows a different screen.
            var switched = false
            for ((seenAt, mean) in recentMeans) {
                if (time - seenAt <= policy.switchWindow &&
                    abs(mean - reading.meanLuma) >= policy.switchMeanJump
                ) {
                    switched = true
                    break
                }
            }
            if (switched) burstUntil = time + policy.switchBurst

            recentMeans.addLast(time to reading.meanLuma)
            while (recentMeans.isNotEmpty() &&
                time - recentMeans.first().first > policy.switchWindow
            ) {
                recentMeans.removeFirst()
            }

            if (reading.isCandidate && !lastCandidate) enteredCandidate = true
            lastCandidate = reading.isCandidate

            if (reading.isCandidate) {
                quietSince = null
            } else if (quietSince == null) {
                quietSince = time
            }
        }

        val activeInterval = if (constrained && !inHack) {
            policy.constrainedActiveInterval
        } else {
            policy.activeInterval
        }
        val quietInterval = if (constrained && !inHack) {
            policy.constrainedQuietInterval
        } else {
            policy.quietInterval
        }

        val bursting = time < burstUntil
        val quietFor = quietSince
        val restingLongEnough = quietFor != null && time - quietFor >= policy.settle
        val candidate = reading?.isCandidate ?: lastCandidate

        // One look immediately for a frame that has just become a candidate:
        // the screen changed, and waiting for the interval would miss the first
        // frames of it.
        if (enteredCandidate) {
            mode = Mode.ACTIVE
            switchedScreen = bursting
            lastTime = time
            return Decision(Mode.ACTIVE, activeInterval)
        }

        val desiredMode: Mode = when {
            inHack || bursting -> Mode.ACTIVE
            candidate && !restingLongEnough -> Mode.ACTIVE
            restingLongEnough && !candidate -> Mode.QUIET
            else -> Mode.RESTING
        }
        val interval = if (desiredMode == Mode.ACTIVE) activeInterval else quietInterval

        if (time - lastTime < interval) return null

        mode = desiredMode
        switchedScreen = bursting
        lastTime = time
        return Decision(desiredMode, interval)
    }

    fun reset() {
        mode = Mode.ACTIVE
        enteredCandidate = false
        switchedScreen = false
        recentMeans.clear()
        burstUntil = Double.NEGATIVE_INFINITY
        quietSince = null
        lastCandidate = true
        lastTime = Double.NEGATIVE_INFINITY
    }
}
