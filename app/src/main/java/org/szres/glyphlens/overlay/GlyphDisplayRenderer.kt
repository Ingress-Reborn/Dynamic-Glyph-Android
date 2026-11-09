package org.szres.glyphlens.overlay

import org.szres.glyphlens.recognition.GlyphDiagramLayout
import org.szres.glyphlens.recognition.GlyphDisplaySnapshot
import org.szres.glyphlens.recognition.GlyphPoint
import org.szres.glyphlens.recognition.GlyphTable
import org.szres.glyphlens.recognition.StatusTone
import org.szres.glyphlens.recognition.TileStyle
import org.szres.glyphlens.recognition.TrackingState
import org.szres.glyphlens.recognition.tileStyles
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Platform-neutral colours and geometry consumed by [GlyphDiagramView]. */
internal data class GlyphPalette(
    val background: Int = rgb(0.04, 0.05, 0.08),
    val confirmed: Int = rgb(0.80, 0.78, 1.00),
    val observing: Int = rgb(1.00, 0.78, 0.30),
    val predicted: Int = rgb(0.45, 0.85, 0.95),
    val unknown: Int = rgb(0.55, 0.55, 0.62),
    val warning: Int = rgb(1.00, 0.42, 0.42),
    val primaryText: Int = rgb(0.95, 0.95, 1.00),
    val idle: Int = rgb(0.42, 0.44, 0.52),
    val secondaryText: Int = rgb(0.62, 0.63, 0.72),
    val frame: Int = rgb(0.30, 0.31, 0.38),
)

internal data class RenderRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun inset(horizontal: Float, vertical: Float): RenderRect = RenderRect(
        left + horizontal,
        top + vertical,
        right - horizontal,
        bottom - vertical,
    )
}

internal data class RenderPoint(val x: Float, val y: Float)
internal data class RenderSegment(val from: RenderPoint, val to: RenderPoint)

internal sealed interface GlyphRenderBody {
    data class Message(
        val text: String,
        val color: Int,
        val bounds: RenderRect,
        val textSize: Float,
    ) : GlyphRenderBody

    data class Tiles(val items: List<GlyphTilePlan>) : GlyphRenderBody
}

internal data class GlyphRenderPlan(
    val statusColor: Int,
    val backgroundColor: Int,
    val ringWidth: Float,
    val contentBounds: RenderRect,
    val innerCornerRadius: Float,
    val body: GlyphRenderBody,
)

internal data class GlyphTilePlan(
    val index: Int,
    val bounds: RenderRect,
    val color: Int,
    val highlight: SlotHighlight?,
    val content: GlyphTileContent,
)

internal data class SlotHighlight(
    val bounds: RenderRect,
    val color: Int,
    val strokeWidth: Float,
    val cornerRadius: Float,
)

internal sealed interface GlyphTileContent {
    data class Diagram(
        val color: Int,
        val frameColor: Int,
        val outline: List<RenderPoint>,
        val nodes: List<RenderPoint>,
        val visitedNodes: List<RenderPoint>,
        val segments: List<RenderSegment>,
        val dotRadius: Float,
        val frameStrokeWidth: Float,
        val glyphStrokeWidth: Float,
        val dashIntervals: List<Float>,
    ) : GlyphTileContent

    data class Placeholder(
        val color: Int,
        val bounds: RenderRect,
        val strokeWidth: Float,
        val dashIntervals: List<Float>,
        val questionTextSize: Float,
    ) : GlyphTileContent
}

/**
 * Converts display state to drawing instructions. Recognition and prediction stay in the snapshot;
 * this class only chooses their visual representation and geometry.
 */
internal class GlyphDisplayRenderer(
    private val table: GlyphTable?,
    val palette: GlyphPalette = GlyphPalette(),
    private val layout: GlyphDiagramLayout = GlyphDiagramLayout(inset = 0.06),
) {
    fun plan(
        snapshot: GlyphDisplaySnapshot,
        width: Float,
        height: Float,
        uptime: Double,
        windowCornerRadius: Float = 0f,
    ): GlyphRenderPlan {
        require(width.isFinite() && width > 0f) { "width must be finite and positive" }
        require(height.isFinite() && height > 0f) { "height must be finite and positive" }
        require(windowCornerRadius.isFinite() && windowCornerRadius >= 0f) {
            "windowCornerRadius must be finite and non-negative"
        }

        val full = RenderRect(0f, 0f, width, height)
        val halfShortSide = min(width, height) / 2f
        val ringWidth = min(max(MINIMUM_RING_WIDTH, height * 0.08f), halfShortSide)
        val content = full.inset(ringWidth, ringWidth)
        val innerRadius = min(
            max(0f, windowCornerRadius - ringWidth),
            min(content.height, content.width) / 2f,
        )
        val rowInset = min(ringWidth + 5f, halfShortSide)
        val row = full.inset(rowInset, rowInset)
        val tone = snapshot.displayStatus(atUptime = uptime).tone
        return GlyphRenderPlan(
            statusColor = statusColor(tone),
            backgroundColor = palette.background,
            ringWidth = ringWidth,
            contentBounds = content,
            innerCornerRadius = innerRadius,
            body = tilesOrWaiting(snapshot, row, uptime),
        )
    }

    private fun tilesOrWaiting(
        snapshot: GlyphDisplaySnapshot,
        bounds: RenderRect,
        uptime: Double,
    ): GlyphRenderBody {
        val styles = snapshot.tileStyles
        if (styles.isEmpty()) return message(waitingText(snapshot, uptime), palette.secondaryText, bounds)
        val columnWidth = bounds.width / styles.size
        val tiles = styles.mapIndexed { index, style ->
            val left = bounds.left + index * columnWidth
            val tileBounds = RenderRect(left, bounds.top, left + columnWidth, bounds.bottom)
            tile(style, index, tileBounds, snapshot)
        }
        return GlyphRenderBody.Tiles(tiles)
    }

    private fun tile(
        style: TileStyle,
        index: Int,
        bounds: RenderRect,
        snapshot: GlyphDisplaySnapshot,
    ): GlyphTilePlan {
        val color = color(style)
        val highlighted = snapshot.currentSlotIndex == index || snapshot.inputSlotIndex == index
        val highlight = if (highlighted) {
            SlotHighlight(
                bounds = bounds.inset(bounds.width * 0.04f, bounds.height * 0.02f),
                color = color.withAlpha(0.8f),
                strokeWidth = max(1.5f, bounds.height * 0.035f),
                cornerRadius = 4f,
            )
        } else {
            null
        }
        val diagramBounds = bounds.inset(bounds.width * 0.12f, bounds.height * 0.08f)
        val content = when (style) {
            is TileStyle.Confirmed,
            is TileStyle.Observing,
            is TileStyle.Inferred,
            is TileStyle.Predicted -> diagram(style, diagramBounds, color)
            is TileStyle.Alternatives,
            TileStyle.Uncertain,
            TileStyle.Missing,
            TileStyle.Pending -> placeholder(diagramBounds, color)
        }
        return GlyphTilePlan(
            index = index,
            bounds = bounds,
            color = color,
            highlight = highlight,
            content = content,
        )
    }

    private fun diagram(style: TileStyle, bounds: RenderRect, color: Int): GlyphTileContent {
        val id = style.glyph
        val path = id?.let { table?.path(forID = it) }
            ?: return placeholder(bounds, palette.unknown)
        val placed = layout.frame(
            inOrigin = GlyphPoint(bounds.left.toDouble(), bounds.top.toDouble()),
            size = GlyphPoint(bounds.width.toDouble(), bounds.height.toDouble()),
        )

        fun point(unit: GlyphPoint): RenderPoint = RenderPoint(
            (placed.origin.x + unit.x * placed.side).toFloat(),
            (placed.origin.y + unit.y * placed.side).toFloat(),
        )

        val dot = max(1.5, placed.side * 0.022).toFloat()
        return GlyphTileContent.Diagram(
            color = color,
            frameColor = palette.frame,
            outline = layout.outline().map(::point),
            nodes = layout.nodePositions().map(::point),
            // Filled dots deliberately differ from the game's hollow detector targets.
            visitedNodes = layout.visitedNodes(of = path).map(::point),
            segments = layout.segments(forPath = path).map { segment ->
                RenderSegment(point(segment.from), point(segment.to))
            },
            dotRadius = dot,
            frameStrokeWidth = max(1.0, placed.side * 0.02).toFloat(),
            glyphStrokeWidth = max(2.0, placed.side * 0.045).toFloat(),
            dashIntervals = if (style.isDashed) {
                listOf((placed.side * 0.09).toFloat(), (placed.side * 0.06).toFloat())
            } else {
                emptyList()
            },
        )
    }

    private fun placeholder(bounds: RenderRect, color: Int): GlyphTileContent.Placeholder {
        val placed = layout.frame(
            inOrigin = GlyphPoint(bounds.left.toDouble(), bounds.top.toDouble()),
            size = GlyphPoint(bounds.width.toDouble(), bounds.height.toDouble()),
        )
        val square = RenderRect(
            placed.origin.x.toFloat(),
            placed.origin.y.toFloat(),
            (placed.origin.x + placed.side).toFloat(),
            (placed.origin.y + placed.side).toFloat(),
        )
        return GlyphTileContent.Placeholder(
            color = color,
            bounds = square.inset(square.width * 0.18f, square.height * 0.18f),
            strokeWidth = 2f,
            dashIntervals = listOf(6f, 5f),
            questionTextSize = max(10.0, placed.side * 0.6).toFloat(),
        )
    }

    private fun message(text: String, color: Int, bounds: RenderRect): GlyphRenderBody.Message =
        GlyphRenderBody.Message(
            text = text,
            color = color,
            bounds = bounds,
            textSize = max(10f, min(bounds.height * 0.5f, bounds.width / 24f)),
        )

    private fun waitingText(snapshot: GlyphDisplaySnapshot, uptime: Double): String {
        if (snapshot.indicatorsObstructed) return snapshot.statusLine(atUptime = uptime)
        return when (snapshot.state) {
            TrackingState.COMMAND -> "Get ready — the sequence is coming."
            TrackingState.COMPLETED -> "This hack's sequence wasn't caught."
            else -> "Start a Glyph hack in Ingress."
        }
    }

    private fun color(style: TileStyle): Int = when (style) {
        is TileStyle.Confirmed -> palette.confirmed
        is TileStyle.Observing, is TileStyle.Inferred -> palette.observing
        is TileStyle.Predicted, is TileStyle.Alternatives -> palette.predicted

        TileStyle.Uncertain, TileStyle.Missing -> palette.warning
        TileStyle.Pending -> palette.unknown
    }

    private fun statusColor(tone: StatusTone): Int = when (tone) {
        StatusTone.WARNING -> palette.warning
        StatusTone.TENTATIVE -> palette.observing
        StatusTone.IDLE -> palette.idle
        StatusTone.COLLECTING -> palette.predicted
        StatusTone.DRAWING -> palette.confirmed
        StatusTone.DONE -> palette.primaryText
    }

    private companion object {
        const val MINIMUM_RING_WIDTH = 4f
    }
}

internal fun Int.withAlpha(alpha: Float): Int {
    val channel = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
    return (this and 0x00ffffff) or (channel shl 24)
}

private fun rgb(red: Double, green: Double, blue: Double): Int {
    fun channel(value: Double): Int = (value.coerceIn(0.0, 1.0) * 255.0).roundToInt()
    return (0xff shl 24) or (channel(red) shl 16) or (channel(green) shl 8) or channel(blue)
}
