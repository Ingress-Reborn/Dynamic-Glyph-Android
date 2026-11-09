package org.szres.glyphlens.recognition.image

import org.szres.glyphlens.recognition.RgbaFrame
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A cheap grid and array-area luminance reading, taken before detailed analysis. */
data class LumaGateReading(
    val darkFraction: Double,
    val brightFraction: Double,
    /** Mean luminance of each cell of the 12×24 whole-frame grid, row-major. */
    val cells: List<Int>,
    val isCandidate: Boolean,
) {
    /** Largest cell change; [Int.MAX_VALUE] for differently shaped or empty grids. */
    fun difference(from: LumaGateReading): Int {
        if (cells.size != from.cells.size || cells.isEmpty()) return Int.MAX_VALUE
        var largest = 0
        for (index in cells.indices) {
            largest = max(largest, abs(cells[index] - from.cells[index]))
        }
        return largest
    }

    /** Mean of the cells' means: the frame's overall brightness. */
    val meanLuma: Double
        get() = if (cells.isEmpty()) 0.0 else cells.sum().toDouble() / cells.size
}

class LumaGate(step: Int = 4) {
    val step: Int = max(1, step)
    private val samplingStep = this.step

    /** [luma] returns full-range luminance in 0...255. */
    fun read(width: Int, height: Int, luma: (Int, Int) -> Int): LumaGateReading? {
        if (width <= 0 || height <= 0) return null
        val sums = IntArray(GRID_COLUMNS * GRID_ROWS)
        val counts = IntArray(GRID_COLUMNS * GRID_ROWS)
        val top = (ARRAY_ROW_START * height).toInt()
        val bottom = (ARRAY_ROW_END * height).toInt()
        val left = (ARRAY_COLUMN_START * width).toInt()
        val right = (ARRAY_COLUMN_END * width).toInt()
        var dark = 0
        var bright = 0
        var area = 0
        var y = samplingStep / 2
        while (y < height) {
            val row = min(GRID_ROWS - 1, y * GRID_ROWS / height) * GRID_COLUMNS
            val inArrayRows = y >= top && y < bottom
            var x = samplingStep / 2
            while (x < width) {
                val value = luma(x, y)
                val cell = row + min(GRID_COLUMNS - 1, x * GRID_COLUMNS / width)
                sums[cell] += value
                counts[cell]++
                if (inArrayRows && x >= left && x < right) {
                    area++
                    if (value < DARK_LEVEL) dark++
                    else if (value > BRIGHT_LEVEL) bright++
                }
                x += samplingStep
            }
            y += samplingStep
        }
        val cells = sums.indices.map { index ->
            if (counts[index] > 0) (sums[index] / counts[index]).coerceIn(0, 255) else 0
        }
        val darkFraction = if (area > 0) dark.toDouble() / area else 1.0
        val brightFraction = if (area > 0) bright.toDouble() / area else 0.0
        return LumaGateReading(
            darkFraction = darkFraction,
            brightFraction = brightFraction,
            cells = cells,
            isCandidate = darkFraction >= MINIMUM_DARK_FRACTION ||
                brightFraction <= MAXIMUM_BRIGHT_FRACTION,
        )
    }

    fun read(image: LuminanceImage): LumaGateReading? =
        read(image.width, image.height) { x, y -> image[x, y] }

    /** Reads an RGBA capture directly; no BGRA channel swap or video-range stretch. */
    fun read(frame: RgbaFrame): LumaGateReading? = readRgba(
        frame.pixels,
        frame.width,
        frame.height,
        bytesPerRow = frame.width * 4,
    )

    /** Packed 32-bit RGBA, red first, using the BT.601 fixed-point gate approximation. */
    fun readRgba(
        pixels: ByteArray,
        width: Int,
        height: Int,
        bytesPerRow: Int = width * 4,
    ): LumaGateReading? {
        if (width <= 0 || height <= 0) return null
        require(bytesPerRow >= width * 4) { "RGBA row stride is too small" }
        val required = (height - 1).toLong() * bytesPerRow + width * 4L
        require(required <= pixels.size) { "RGBA buffer is truncated" }
        return read(width, height) { x, y ->
            val pixel = y * bytesPerRow + x * 4
            val red = pixels[pixel].toInt() and 0xff
            val green = pixels[pixel + 1].toInt() and 0xff
            val blue = pixels[pixel + 2].toInt() and 0xff
            (29 * blue + 150 * green + 77 * red) shr 8
        }
    }

    /** An 8-bit luma plane, optionally stretched from 16...235 video range. */
    fun readPlane(
        plane: ByteArray,
        width: Int,
        height: Int,
        bytesPerRow: Int = width,
        videoRange: Boolean,
    ): LumaGateReading? {
        if (width <= 0 || height <= 0) return null
        require(bytesPerRow >= width) { "Luma row stride is too small" }
        val required = (height - 1).toLong() * bytesPerRow + width
        require(required <= plane.size) { "Luma plane is truncated" }
        return read(width, height) { x, y ->
            val value = plane[y * bytesPerRow + x].toInt() and 0xff
            if (videoRange) VIDEO_RANGE_TABLE[value] else value
        }
    }

    companion object {
        const val DARK_LEVEL = 20
        const val BRIGHT_LEVEL = 60
        const val MINIMUM_DARK_FRACTION = 0.6
        const val MAXIMUM_BRIGHT_FRACTION = 0.25
        const val ARRAY_ROW_START = 0.30
        const val ARRAY_ROW_END = 0.85
        const val ARRAY_COLUMN_START = 0.03
        const val ARRAY_COLUMN_END = 0.97
        const val GRID_COLUMNS = 12
        const val GRID_ROWS = 24

        @JvmStatic
        val VIDEO_RANGE_TABLE: List<Int> = (0 until 256).map { value ->
            (((value - 16) * 255 + 109) / 219).coerceIn(0, 255)
        }
    }
}

/** Decides analysis cadence and how much of the pipeline a look should receive. */
class AnalysisRateController(val policy: Policy = Policy()) {
    enum class Mode { ACTIVE, RESTING, QUIET }

    data class Policy(
        var activeInterval: Double = 0.049,
        var quietInterval: Double = 0.249,
        var constrainedActiveInterval: Double = 0.099,
        var constrainedQuietInterval: Double = 0.499,
        var settle: Double = 2.0,
        var changeThreshold: Int = 3,
        var switchMeanJump: Double = 8.0,
        var switchWindow: Double = 0.3,
        var switchBurst: Double = 1.0,
    )

    data class Decision(val mode: Mode, val interval: Double)

    var mode: Mode = Mode.ACTIVE
        private set
    var enteredCandidate: Boolean = false
        private set
    var switchedScreen: Boolean = false
        private set

    private data class MeanAt(val time: Double, val mean: Double)

    private val recentMeans = mutableListOf<MeanAt>()
    private var burstUntil = Double.NEGATIVE_INFINITY
    private var quietSince: Double? = null
    private var hasReading = false
    private var lastCandidate = true
    private var lastTime = Double.NEGATIVE_INFINITY

    fun decide(
        at: Double,
        gate: LumaGateReading?,
        busy: Boolean,
        inHack: Boolean,
        constrained: Boolean,
    ): Decision {
        if (at < lastTime) reset()
        lastTime = at
        var entered = false
        var switched = false
        if (gate != null) {
            entered = hasReading && !lastCandidate && gate.isCandidate
            hasReading = true
            lastCandidate = gate.isCandidate
            val mean = gate.meanLuma
            recentMeans.removeAll { at - it.time > policy.switchWindow }
            switched = gate.isCandidate && recentMeans.any {
                abs(it.mean - mean) >= policy.switchMeanJump
            }
            if (switched) {
                recentMeans.clear()
                burstUntil = at + policy.switchBurst
            }
            recentMeans.add(MeanAt(at, mean))
        }
        val decision = pace(
            at,
            interesting = busy || inHack,
            evidence = gate != null,
            inHack = inHack,
            constrained = constrained,
        )
        enteredCandidate = entered && decision.mode != Mode.ACTIVE
        switchedScreen = !enteredCandidate && lastCandidate && at < burstUntil &&
            decision.mode != Mode.ACTIVE
        if (enteredCandidate || (switchedScreen && switched)) {
            return Decision(Mode.ACTIVE, policy.activeInterval)
        }
        if (switchedScreen) {
            return Decision(
                Mode.ACTIVE,
                if (constrained && !inHack) policy.constrainedActiveInterval else policy.activeInterval,
            )
        }
        return decision
    }

    /** Advances the same clock when a capture source reports unchanged/still content. */
    fun advanceStill(at: Double, busy: Boolean, inHack: Boolean, constrained: Boolean): Decision {
        enteredCandidate = false
        switchedScreen = false
        if (!at.isFinite() || at < lastTime) return decision(inHack, constrained)
        return pace(
            at,
            interesting = busy || inHack,
            evidence = hasReading,
            inHack = inHack,
            constrained = constrained,
        )
    }

    private fun pace(
        at: Double,
        interesting: Boolean,
        evidence: Boolean,
        inHack: Boolean,
        constrained: Boolean,
    ): Decision {
        if (interesting) {
            quietSince = null
            mode = Mode.ACTIVE
        } else if (evidence || mode != Mode.ACTIVE) {
            val since = quietSince ?: at
            quietSince = since
            if (at - since >= policy.settle) {
                mode = if (lastCandidate) Mode.RESTING else Mode.QUIET
            }
        }
        return decision(inHack, constrained)
    }

    private fun decision(inHack: Boolean, constrained: Boolean): Decision {
        val halved = constrained && !inHack
        val interval = if (mode == Mode.ACTIVE) {
            if (halved) policy.constrainedActiveInterval else policy.activeInterval
        } else {
            if (halved) policy.constrainedQuietInterval else policy.quietInterval
        }
        return Decision(mode, interval)
    }

    fun reset() {
        mode = Mode.ACTIVE
        quietSince = null
        hasReading = false
        lastCandidate = true
        lastTime = Double.NEGATIVE_INFINITY
        enteredCandidate = false
        switchedScreen = false
        recentMeans.clear()
        burstUntil = Double.NEGATIVE_INFINITY
    }

}
