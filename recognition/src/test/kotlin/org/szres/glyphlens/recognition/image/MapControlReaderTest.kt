package org.szres.glyphlens.recognition.image

import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class MapControlReaderTest {
    @Test
    fun everyControlIsSufficientWithoutAnyOtherControlOrGlyphCalibration() {
        for (kind in MapControlReader.Kind.entries) {
            val image = controlScene(kind, 603, 1311, 60)
            val found = MapControlReader().read(image, 0.0)
            assertEquals("Only $kind was drawn", kind, found?.kind)
        }
    }

    @Test
    fun independentControlsFollowScaleAndPortraitViewportChanges() {
        for ((width, height) in listOf(320 to 520, 603 to 1311, 804 to 1206)) {
            for (fraction in listOf(0.06, 0.10)) {
                for (kind in MapControlReader.Kind.entries) {
                    val size = (width * fraction).roundToInt()
                    val found = MapControlReader().read(controlScene(kind, width, height, size), 0.0)
                    assertEquals("$kind at ${width}x$height, size $size; $found", kind, found?.kind)
                }
            }
        }
    }

    @Test
    fun controlsCanMoveInsideLetterboxedContentWithoutTheirNeighbours() {
        for (kind in MapControlReader.Kind.entries) {
            val x = if (kind == MapControlReader.Kind.PLUS) 140 else 650
            val found = MapControlReader().read(controlScene(kind, 804, 1600, 68, centerX = x), 0.0)
            assertEquals(kind.toString(), kind, found?.kind)
        }
    }

    @Test
    fun compassNeedleCanRotateIndependentlyOfTheOtherControls() {
        for (rotation in listOf(11.25, 45.0, 90.0, 105.0, 180.0, 270.0)) {
            val scene = controlScene(MapControlReader.Kind.DIAMOND, 603, 1311, 64, rotation = rotation)
            assertEquals("Compass rotation $rotation", MapControlReader.Kind.DIAMOND,
                MapControlReader().read(scene, 0.0)?.kind)
        }
    }

    @Test
    fun aRedNeedleWithoutItsGreyDiamondBaseIsNotTheCompassControl() {
        val fixture = requireNotNull(javaClass.getResourceAsStream("/map-controls/diamond.png"))
            .use { ImageIO.read(it) }
        val erase = fixture.createGraphics()
        erase.color = Color.BLACK
        erase.fillRect(24,56,48,20)
        erase.dispose()
        val scene = canvas(603,1311)
        val graphics = scene.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        graphics.drawImage(fixture,498,296,64,64,null)
        graphics.dispose()
        assertNull(MapControlReader().read(rgb(scene),0.0))
    }

    @Test
    fun cachedControlUsesFewerSamplesAndDisappearanceDoesNotKeepAnOldMatch() {
        val reader = MapControlReader()
        val scene = controlScene(MapControlReader.Kind.BRIEFCASE, 603, 1311, 62)
        assertEquals(MapControlReader.Kind.BRIEFCASE, reader.read(scene, 0.0)?.kind)
        val searchSamples = reader.lastPixelReadCount

        assertEquals(MapControlReader.Kind.BRIEFCASE, reader.read(scene, 0.05)?.kind)
        assertTrue(reader.lastReadWasPerformed)
        assertTrue("Cached samples ${reader.lastPixelReadCount}, initial $searchSamples",
            reader.lastPixelReadCount < searchSamples / 3)
        assertNull(reader.read(blank(603, 1311), 0.10))
    }

    @Test
    fun missesAreThrottledWithoutInventingAPreviousPositiveReading() {
        val reader = MapControlReader()
        val image = blank(603, 1311)
        assertNull(reader.read(image, 0.0))
        assertTrue(reader.lastReadWasPerformed)
        assertTrue(reader.lastPixelReadCount > 0)
        assertNull(reader.read(image, 0.10))
        assertFalse(reader.lastReadWasPerformed)
        assertEquals(0, reader.lastPixelReadCount)
        assertNull(reader.read(image, 0.25))
        assertTrue(reader.lastReadWasPerformed)
        reader.reset()
        assertNull(reader.read(image, 0.26))
        assertTrue(reader.lastReadWasPerformed)
    }

    @Test
    fun blankCirclesDrawingNodesAndAnUnframedCrossAreNotMapControls() {
        val circles = canvas(603, 1311)
        val graphics = circles.createGraphics()
        graphics.color = Color(220, 220, 250)
        graphics.stroke = BasicStroke(3f)
        for ((x, y) in listOf(530 to 270, 530 to 450, 530 to 610, 65 to 430,
                302 to 580, 160 to 720, 444 to 720, 302 to 860, 160 to 1000, 444 to 1000)) {
            graphics.drawOval(x - 12, y - 12, 24, 24)
        }
        graphics.dispose()
        assertNull(MapControlReader().read(rgb(circles), 0.0))

        val cross = canvas(603, 1311)
        val pen = cross.createGraphics()
        pen.color = Color.WHITE
        pen.fillRect(51, 422, 28, 4)
        pen.fillRect(63, 410, 4, 28)
        pen.dispose()
        assertNull(MapControlReader().read(rgb(cross), 0.0))
        assertNull(MapControlReader().read(blank(603, 1311), 0.0))
    }

    @Test
    fun anEmptySquareAndASolidRectangleWithoutAHandleAreNotMapControls() {
        for (size in listOf(28, 44, 60)) {
            val square = canvas(603, 1311)
            val frame = square.createGraphics()
            frame.stroke = BasicStroke(2f)
            frame.color = Color(210, 210, 230)
            frame.drawRect(65 - size / 2, 430 - size / 2, size, size)
            frame.color = Color(140, 130, 235)
            val inset = maxOf(4, size / 8)
            frame.drawRect(65 - size / 2 + inset, 430 - size / 2 + inset,
                size - 2 * inset, size - 2 * inset)
            frame.dispose()
            assertNull("An empty square of size $size has no central plus",
                MapControlReader().read(rgb(square), 0.0))

            val rectangle = canvas(603, 1311)
            val body = rectangle.createGraphics()
            body.color = Color.WHITE
            val bodyHeight = (size * 0.65).roundToInt()
            body.fillRect(530 - size / 2, 1114 - bodyHeight / 2, size, bodyHeight)
            body.dispose()
            assertNull("A solid rectangle of size $size has no briefcase handle",
                MapControlReader().read(rgb(rectangle), 0.0))
        }
    }

    @Test
    fun unrelatedLanguageLabelsDoNotChangeTheIndependentControlMatch() {
        for (label in listOf("GLYPH HACK COMPLETE", "符文入侵完成", "ハック完了", "해킹 완료")) {
            val scene = canvas(603, 1311)
            val graphics = scene.createGraphics()
            graphics.color = Color.WHITE
            graphics.drawString(label, 170, 90)
            graphics.drawString(label, 170, 1040)
            graphics.dispose()
            placeControl(scene, MapControlReader.Kind.SIGNAL, 530, 450, 60, 0.0)
            assertEquals(label, MapControlReader.Kind.SIGNAL, MapControlReader().read(rgb(scene), 0.0)?.kind)
        }
    }

    @Test
    fun actualPredictionWindowAndDisabledDrawingButtonAreNotMapControls() {
        for (width in listOf(603, 804, 1216)) {
            val scale = width / 1216.0
            for ((name, left, top) in listOf(Triple("prediction-window",15,684),
                    Triple("drawing-complete-button",750,2415))) {
                val scene = canvas(width, (2688 * scale).roundToInt())
                val fixture = requireNotNull(javaClass.getResourceAsStream("/map-controls/$name.png"))
                    .use { ImageIO.read(it) }
                val graphics = scene.createGraphics()
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                graphics.drawImage(fixture, (left * scale).roundToInt(), (top * scale).roundToInt(),
                    (fixture.width * scale).roundToInt(), (fixture.height * scale).roundToInt(), null)
                graphics.dispose()
                assertNull("$name at width $width", MapControlReader().read(rgb(scene), 0.0))
            }
        }
    }

    @Test
    fun actualWindowsDoNotCombineTheCloseButtonAndColouredBorderIntoAnIcon() {
        for (name in listOf("prediction-window-frame","input-window-frame","inactive-window-frame")) {
            val source = requireNotNull(javaClass.getResourceAsStream("/map-controls/$name.png"))
                .use { ImageIO.read(it) }
            for (height in listOf(1332,1333)) {
                for (offset in listOf(-1,0,1)) {
                    val scene = canvas(603, height)
                    val graphics = scene.createGraphics()
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                    // Preserve the whole screen's sampling phase before moving by a capture pixel.
                    graphics.drawImage(source, offset, offset, 603, height, null)
                    graphics.dispose()
                    assertNull("$name at 603x$height offset $offset", MapControlReader().read(rgb(scene), 0.0))
                }
            }
        }
    }

    private fun controlScene(
        kind: MapControlReader.Kind,
        width: Int,
        height: Int,
        size: Int,
        centerX: Int = (width * if (kind == MapControlReader.Kind.PLUS) 0.10 else 0.88).roundToInt(),
        rotation: Double = 0.0,
    ): ColorImage {
        val scene = canvas(width, height)
        val yFraction = when (kind) {
            MapControlReader.Kind.DIAMOND -> 0.25
            MapControlReader.Kind.SIGNAL -> 0.35
            MapControlReader.Kind.LAYERS -> 0.45
            MapControlReader.Kind.PLUS -> 0.40
            MapControlReader.Kind.BRIEFCASE -> 0.85
        }
        placeControl(scene, kind, centerX, (height * yFraction).roundToInt(), size, rotation)
        return rgb(scene)
    }

    private fun placeControl(scene: BufferedImage, kind: MapControlReader.Kind,
            centerX: Int, centerY: Int, size: Int, rotation: Double) {
        val name = "/map-controls/${kind.name.lowercase()}.png"
        val fixture = requireNotNull(javaClass.getResourceAsStream(name)).use { ImageIO.read(it) }
        val graphics = scene.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        graphics.translate(centerX.toDouble(), centerY.toDouble())
        graphics.rotate(Math.toRadians(rotation))
        graphics.drawImage(fixture, -size / 2, -size / 2, size, size, null)
        graphics.dispose()
    }

    private fun canvas(width: Int, height: Int) = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)

    private fun blank(width: Int, height: Int) = ColorImage(width, height, ByteArray(width * height * 3))

    private fun rgb(image: BufferedImage): ColorImage {
        val pixels = ByteArray(image.width * image.height * 3)
        var offset = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val pixel = image.getRGB(x, y)
                pixels[offset++] = (pixel ushr 16).toByte()
                pixels[offset++] = (pixel ushr 8).toByte()
                pixels[offset++] = pixel.toByte()
            }
        }
        return ColorImage(image.width, image.height, pixels)
    }
}
