package org.szres.glyphlens.recognition.image

/** Hand-authored indicator/window/timer coordinates, independent of production geometry. */
internal object SyntheticSlotBands {
    const val WIDTH = 603
    const val HEIGHT = 323
    const val FRAME_HEIGHT = 1311
    private val violet = intArrayOf(185, 165, 240)
    private val gold = intArrayOf(240, 180, 50)

    class Canvas {
        val pixels = ByteArray(WIDTH * HEIGHT * 3)

        fun fill(rows: IntRange, columns: IntRange, color: IntArray) {
            for (y in rows) {
                for (x in columns) {
                    val offset = (y * WIDTH + x) * 3
                    pixels[offset] = color[0].toByte()
                    pixels[offset + 1] = color[1].toByte()
                    pixels[offset + 2] = color[2].toByte()
                }
            }
        }

        fun band(): FrameTopBand = FrameTopBand(ColorImage(WIDTH, HEIGHT, pixels), FRAME_HEIGHT)
    }

    fun outlinedBoxes(
        lefts: List<Int> = listOf(117, 213, 309, 405),
        highlighted: Int? = null,
        rows: IntRange = 145 until 225,
    ): Canvas {
        val canvas = Canvas()
        for ((index, left) in lefts.withIndex()) {
            val color = if (index == highlighted) gold else violet
            for (y in rows) {
                for (x in left until left + 80) {
                    if (y >= rows.first + 4 && y < rows.last - 3 &&
                        x >= left + 4 && x < left + 76
                    ) continue
                    val offset = (y * WIDTH + x) * 3
                    canvas.pixels[offset] = color[0].toByte()
                    canvas.pixels[offset + 1] = color[1].toByte()
                    canvas.pixels[offset + 2] = color[2].toByte()
                }
            }
        }
        return canvas
    }

    fun banner(
        bottom: Int,
        left: Int = 10,
        right: Int = 593,
        highlighted: Int? = null,
    ): FrameTopBand {
        val canvas = outlinedBoxes(highlighted = highlighted)
        canvas.fill(60 until bottom, left until right, intArrayOf(128, 130, 135))
        return canvas.band()
    }

    fun windowAboveStrip(): FrameTopBand {
        val canvas = outlinedBoxes()
        for (y in 130 until 139) {
            canvas.fill(y until y + 1, 20 until 583, if (y < 137) intArrayOf(78, 80, 90) else intArrayOf(8, 12, 18))
        }
        return canvas.band()
    }

    fun windowOverStrip(): FrameTopBand {
        val canvas = Canvas()
        for (left in listOf(117, 213, 309, 405)) {
            canvas.fill(210 until 225, left until left + 4, violet)
            canvas.fill(210 until 225, left + 76 until left + 80, violet)
            canvas.fill(221 until 225, left until left + 80, violet)
        }
        canvas.fill(80 until 200, 20 until 583, intArrayOf(8, 12, 18))
        canvas.fill(200 until 208, 20 until 583, intArrayOf(78, 80, 90))
        return canvas.band()
    }

    fun singleMarker(center: Int = 301): FrameTopBand {
        val canvas = Canvas()
        val left = center - 35
        for (y in 150 until 220) {
            for (x in left until left + 70) {
                if (y >= 154 && y < 216 && x >= left + 4 && x < left + 66) continue
                val offset = (y * WIDTH + x) * 3
                canvas.pixels[offset] = violet[0].toByte()
                canvas.pixels[offset + 1] = violet[1].toByte()
                canvas.pixels[offset + 2] = violet[2].toByte()
            }
        }
        return canvas.band()
    }

    fun timer(magentaProgress: Boolean = false, markers: Boolean = true): FrameTopBand {
        val canvas = if (markers) outlinedBoxes(highlighted = 0) else Canvas()
        // Independently chosen visible rail endpoints and a centered shrinking progress run.
        canvas.fill(300 until 303, 66 until 538, violet)
        canvas.fill(
            300 until 303,
            180 until 424,
            if (magentaProgress) intArrayOf(190, 45, 130) else gold,
        )
        return canvas.band()
    }
}
