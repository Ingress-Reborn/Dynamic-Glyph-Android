package org.szres.glyphlens.recognition.image

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Five independent map controls; neither game text nor an earlier Glyph fit is needed. */
class MapControlReader {
    private companion object {
        val BUTTON_SURROUND = intArrayOf(112, 248, 540, 792, 912, 775, 515, 231)
        val LAYER_INTERIOR = intArrayOf(398, 400, 464)
        val BRIEFCASE_INTERIOR = intArrayOf(462, 592, 594)
    }

    enum class Kind { DIAMOND, SIGNAL, LAYERS, PLUS, BRIEFCASE }

    data class Match(val kind: Kind, val centerX: Double, val centerY: Double, val size: Double)

    var lastPixelReadCount = 0
        private set
    var lastReadWasPerformed = false
        private set

    private data class Located(val pattern: MapControlPatterns.Pattern, val left: Double, val top: Double, val scale: Double) {
        val match get() = Match(pattern.kind, left + 15.5 * scale, top + 15.5 * scale, 32 * scale)
    }
    private data class Component(val left: Int, val top: Int, val right: Int, val bottom: Int, val count: Int) {
        val width get() = right - left + 1
        val height get() = bottom - top + 1
    }

    private var cached: Located? = null
    private var nextSearch = Double.NEGATIVE_INFINITY
    private var lastTime = Double.NEGATIVE_INFINITY
    private var width = 0
    private var height = 0
    private var states = ByteArray(0)
    private var queue = IntArray(0)

    fun reset() {
        cached = null
        nextSearch = Double.NEGATIVE_INFINITY
        lastTime = Double.NEGATIVE_INFINITY
    }

    fun read(image: ColorImage, sourceTime: Double): Match? {
        lastPixelReadCount = 0
        lastReadWasPerformed = false
        if (image.width != width || image.height != height || sourceTime < lastTime) {
            reset()
            width = image.width
            height = image.height
        }
        lastTime = sourceTime
        cached?.let {
            lastReadWasPerformed = true
            if (matches(image, it)) return it.match
            cached = null
        }
        if (sourceTime < nextSearch) return null
        nextSearch = sourceTime + 0.25
        lastReadWasPerformed = true
        val step = max(1, ceil(image.width / 320.0).toInt())
        val columns = (image.width + step - 1) / step
        val rows = (image.height + step - 1) / step
        if (states.size != columns * rows) {
            states = ByteArray(columns * rows)
            queue = IntArray(states.size)
        } else states.fill(0)

        for (y in 0 until rows) {
            for (x in 0 until columns) {
                val index = y * columns + x
                if (states[index].toInt() != 0) continue
                if (!isSearchRegion(x, y, columns, rows) || !bright(image, x * step, y * step)) {
                    states[index] = 1
                    continue
                }
                val component = component(image, x, y, columns, rows, step)
                if (component.count < 3 || component.width > columns * 0.18 || component.height > columns * 0.18) continue
                for (pattern in MapControlPatterns.all) {
                    val sx = component.width.toDouble() / pattern.anchorWidth
                    val sy = component.height.toDouble() / pattern.anchorHeight
                    if (abs(sx - sy) > max(sx, sy) * 0.30) continue
                    val scale = (sx + sy) * step / 2
                    val size = scale * 32
                    if (size < image.width * 0.035 || size > image.width * 0.20) continue
                    val left = component.left * step - pattern.anchorLeft * scale
                    val top = component.top * step - pattern.anchorTop * scale
                    for (dy in -1..1) for (dx in -1..1) {
                        val located = Located(pattern, left + dx * step, top + dy * step, scale)
                        if (matches(image, located)) {
                            cached = located
                            return located.match
                        }
                    }
                }
            }
        }
        return null
    }

    private fun component(image: ColorImage, startX: Int, startY: Int, columns: Int, rows: Int, step: Int): Component {
        var head = 0
        var tail = 1
        queue[0] = startY * columns + startX
        states[queue[0]] = 2
        var left = startX
        var right = startX
        var top = startY
        var bottom = startY
        while (head < tail) {
            val current = queue[head++]
            val x = current % columns
            val y = current / columns
            left = min(left, x)
            right = max(right, x)
            top = min(top, y)
            bottom = max(bottom, y)
            for (ny in max(0, y - 1)..min(rows - 1, y + 1)) {
                for (nx in max(0, x - 1)..min(columns - 1, x + 1)) {
                    val next = ny * columns + nx
                    if (states[next].toInt() != 0) continue
                    states[next] = 1
                    if (isSearchRegion(nx, ny, columns, rows) && bright(image, nx * step, ny * step)) {
                        states[next] = 2
                        queue[tail++] = next
                    }
                }
            }
        }
        return Component(left, top, right, bottom, tail)
    }

    private fun isSearchRegion(x: Int, y: Int, columns: Int, rows: Int): Boolean =
        y >= rows * 0.08 && (x >= columns * 0.65 || x <= columns * 0.30 && y <= rows * 0.80)

    private fun matches(image: ColorImage, located: Located): Boolean {
        val pattern = located.pattern
        val right = (located.left + 31 * located.scale).roundToInt()
        val bottom = (located.top + 31 * located.scale).roundToInt()
        if (located.left < 0 || located.top < 0 || right >= image.width || bottom >= image.height) return false
        var misses = 0
        val allowedBrightMisses = pattern.bright.size / 6
        val coloredNeedle = pattern.kind == Kind.DIAMOND
        val neutralStroke = pattern.kind != Kind.DIAMOND && pattern.kind != Kind.PLUS
        // Disabled drawing labels are grey; the signal arcs and body are white.
        val minimumStrength = if (pattern.kind == Kind.SIGNAL) 160 else 100
        for (point in pattern.bright) {
            val x = (located.left + (point % 32) * located.scale).roundToInt()
            val y = (located.top + (point / 32) * located.scale).roundToInt()
            if (!bright(image, x, y, coloredNeedle, minimumStrength, neutralStroke) &&
                !nearbyBright(image, x, y, coloredNeedle, minimumStrength, neutralStroke)
            ) {
                if (++misses > allowedBrightMisses) return false
            }
        }
        val interior = when (pattern.kind) {
            Kind.LAYERS -> LAYER_INTERIOR
            Kind.BRIEFCASE -> BRIEFCASE_INTERIOR
            else -> null
        }
        if (interior != null) {
            // These icons are filled; an outlined letter can match their edge probes.
            var filled = 0
            for (point in interior) {
                val x = (located.left + (point % 32) * located.scale).roundToInt()
                val y = (located.top + (point / 32) * located.scale).roundToInt()
                if (bright(image, x, y, neutral = true)) filled++
            }
            if (filled < 2) return false
        }
        misses = 0
        for (point in pattern.required) {
            val x = (located.left + (point % 32) * located.scale).roundToInt()
            val y = (located.top + (point / 32) * located.scale).roundToInt()
            if (!bright(image, x, y, neutral = true) && !nearbyBright(image, x, y, false, neutral = true) &&
                ++misses > pattern.required.size / 2
            ) return false
        }
        misses = 0
        for (point in pattern.grey) {
            val x = (located.left + (point % 32) * located.scale).roundToInt()
            val y = (located.top + (point / 32) * located.scale).roundToInt()
            if (!needleBase(image, x, y) &&
                !(x > 0 && needleBase(image, x - 1, y)) &&
                !(x + 1 < image.width && needleBase(image, x + 1, y)) &&
                !(y > 0 && needleBase(image, x, y - 1)) &&
                !(y + 1 < image.height && needleBase(image, x, y + 1)) &&
                ++misses > pattern.grey.size / 4
            ) return false
        }
        if (pattern.kind != Kind.PLUS && !hasBlackButtonSurround(image, located)) return false
        misses = 0
        for (point in pattern.dark) {
            val x = (located.left + (point % 32) * located.scale).roundToInt()
            val y = (located.top + (point / 32) * located.scale).roundToInt()
            val (red, green, blue) = pixel(image, x, y)
            if (max(red, max(green, blue)) > 85 && ++misses > pattern.dark.size / 12) return false
        }
        misses = 0
        for (point in pattern.gaps) {
            val x = (located.left + (point % 32) * located.scale).roundToInt()
            val y = (located.top + (point / 32) * located.scale).roundToInt()
            val (red, green, blue) = pixel(image, x, y)
            // Narrow gaps become grey when the control is downscaled.
            if (max(red, max(green, blue)) > 150 && ++misses > pattern.gaps.size / 4) return false
        }
        return true
    }

    private fun hasBlackButtonSurround(image: ColorImage, located: Located): Boolean {
        var misses = 0
        val centerX = located.left + 15.5 * located.scale
        val centerY = located.top + 15.5 * located.scale
        for (point in BUTTON_SURROUND) {
            val x = (located.left + (point % 32) * located.scale).roundToInt()
            val y = (located.top + (point / 32) * located.scale).roundToInt()
            val (red, green, blue) = pixel(image, x, y)
            if (max(red, max(green, blue)) <= 8) continue
            val (insideRed, insideGreen, insideBlue) = pixel(image,
                x + centerX.compareTo(x.toDouble()), y + centerY.compareTo(y.toDouble()))
            // Small controls can blend the case's lower corners into two surround samples.
            if (max(insideRed, max(insideGreen, insideBlue)) > 8 && ++misses > 2) return false
        }
        return true
    }

    private fun needleBase(image: ColorImage, x: Int, y: Int): Boolean {
        val (red, green, blue) = pixel(image, x, y)
        val strongest = max(red, max(green, blue))
        return strongest in 35..135 && strongest - min(red, min(green, blue)) <= 30
    }

    private fun nearbyBright(image: ColorImage, x: Int, y: Int, coloredNeedle: Boolean,
            minimumStrength: Int = 100, neutral: Boolean = false): Boolean =
        x > 0 && bright(image, x - 1, y, coloredNeedle, minimumStrength, neutral) ||
            x + 1 < image.width && bright(image, x + 1, y, coloredNeedle, minimumStrength, neutral) ||
            y > 0 && bright(image, x, y - 1, coloredNeedle, minimumStrength, neutral) ||
            y + 1 < image.height && bright(image, x, y + 1, coloredNeedle, minimumStrength, neutral)

    private fun bright(image: ColorImage, x: Int, y: Int, coloredNeedle: Boolean = false,
            minimumStrength: Int = 100, neutral: Boolean = false): Boolean {
        val (red, green, blue) = pixel(image, x, y)
        val strongest = max(red, max(green, blue))
        // The compass has a red needle; cyan result labels have similar small strokes.
        return strongest >= minimumStrength && (!coloredNeedle || red - green >= 50 && red - blue >= 35) &&
            (!neutral || strongest - min(red, min(green, blue)) <= 40)
    }

    private fun pixel(image: ColorImage, x: Int, y: Int): RgbPixel {
        lastPixelReadCount++
        return image[x, y]
    }
}
