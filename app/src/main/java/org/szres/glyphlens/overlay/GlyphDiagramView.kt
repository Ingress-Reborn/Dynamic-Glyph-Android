package org.szres.glyphlens.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import androidx.annotation.MainThread
import org.szres.glyphlens.recognition.GlyphDisplaySnapshot
import org.szres.glyphlens.recognition.GlyphTable
import org.szres.glyphlens.settings.OverlayCanvasConfiguration

class GlyphDiagramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val productionFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val productionStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val productionTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val productionPath = Path()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private var renderBitmap: Bitmap? = null
    private var renderBitmapCanvas: Canvas? = null
    private var production: ProductionRender? = null

    /**
     * Displays real recognition state. The caller supplies the snapshot's time base; this method
     * never asks the recognizer to advance or reinterpret it.
     */
    @MainThread
    fun render(
        snapshot: GlyphDisplaySnapshot,
        table: GlyphTable?,
        uptime: Double,
        canvas: OverlayCanvasConfiguration = OverlayCanvasConfiguration.standard,
        readingModeTint: ReadingModeTint = ReadingModeTint.NONE,
    ) {
        val palette = GlyphPalette().withReadingModeTint(readingModeTint)
        production = ProductionRender(
            renderer = GlyphDisplayRenderer(table, palette = palette),
            snapshot = snapshot,
            uptime = uptime,
            canvas = canvas,
        )
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = production ?: return
        if (width > 0 && height > 0) {
            drawProduction(canvas, current)
        }
    }

    private fun drawProduction(canvas: Canvas, production: ProductionRender) {
        val pixelWidth = production.canvas.pixelWidth
        val pixelHeight = production.canvas.pixelHeight
        var bitmap = renderBitmap
        if (bitmap == null || bitmap.width != pixelWidth || bitmap.height != pixelHeight) {
            bitmap = Bitmap.createBitmap(pixelWidth, pixelHeight, Bitmap.Config.ARGB_8888)
            renderBitmap = bitmap
            renderBitmapCanvas = Canvas(bitmap)
        }
        val offscreen = renderBitmapCanvas ?: return
        val plan = production.renderer.plan(
            snapshot = production.snapshot,
            width = pixelWidth.toFloat(),
            height = pixelHeight.toFloat(),
            uptime = production.uptime,
        )
        offscreen.drawColor(plan.statusColor)
        productionFillPaint.color = plan.backgroundColor
        offscreen.drawRoundRect(
            plan.contentBounds.toRectF(),
            plan.innerCornerRadius,
            plan.innerCornerRadius,
            productionFillPaint,
        )
        when (val body = plan.body) {
            is GlyphRenderBody.Message -> drawMessage(offscreen, body)
            is GlyphRenderBody.Tiles -> body.items.forEach { drawTile(offscreen, it) }
        }
        canvas.drawBitmap(bitmap, null, Rect(0, 0, width, height), bitmapPaint)
    }

    private fun drawMessage(canvas: Canvas, message: GlyphRenderBody.Message) {
        productionTextPaint.color = message.color
        productionTextPaint.textSize = message.textSize
        productionTextPaint.textAlign = Paint.Align.LEFT
        val maxWidth = (message.bounds.width - 8f).coerceAtLeast(0f)
        val text = TextUtils.ellipsize(
            message.text,
            productionTextPaint,
            maxWidth,
            TextUtils.TruncateAt.END,
        ).toString()
        val metrics = productionTextPaint.fontMetrics
        val baseline = (message.bounds.top + message.bounds.bottom) / 2f -
            (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(text, message.bounds.left + 4f, baseline, productionTextPaint)
    }

    private fun drawTile(canvas: Canvas, tile: GlyphTilePlan) {
        tile.highlight?.let { highlight ->
            productionStrokePaint.color = highlight.color
            productionStrokePaint.strokeWidth = highlight.strokeWidth
            productionStrokePaint.pathEffect = null
            canvas.drawRoundRect(
                highlight.bounds.toRectF(),
                highlight.cornerRadius,
                highlight.cornerRadius,
                productionStrokePaint,
            )
        }
        when (val content = tile.content) {
            is GlyphTileContent.Diagram -> drawDiagram(canvas, content)
            is GlyphTileContent.Placeholder -> drawPlaceholder(canvas, content)
        }
    }

    private fun drawDiagram(canvas: Canvas, diagram: GlyphTileContent.Diagram) {
        productionStrokePaint.color = diagram.frameColor
        productionStrokePaint.strokeWidth = diagram.frameStrokeWidth
        productionStrokePaint.pathEffect = null
        productionPath.reset()
        diagram.outline.forEachIndexed { index, point ->
            if (index == 0) productionPath.moveTo(point.x, point.y)
            else productionPath.lineTo(point.x, point.y)
        }
        productionPath.close()
        canvas.drawPath(productionPath, productionStrokePaint)

        // Every array node and every visited node is filled, never a hollow detector target.
        productionFillPaint.color = diagram.frameColor
        diagram.nodes.forEach { point ->
            canvas.drawCircle(point.x, point.y, diagram.dotRadius, productionFillPaint)
        }
        productionFillPaint.color = diagram.color.withAlpha(0.35f)
        diagram.visitedNodes.forEach { point ->
            canvas.drawCircle(point.x, point.y, diagram.dotRadius, productionFillPaint)
        }

        productionStrokePaint.color = diagram.color
        productionStrokePaint.strokeWidth = diagram.glyphStrokeWidth
        productionStrokePaint.pathEffect = diagram.dashIntervals.toDashEffect()
        productionPath.reset()
        diagram.segments.forEach { segment ->
            productionPath.moveTo(segment.from.x, segment.from.y)
            productionPath.lineTo(segment.to.x, segment.to.y)
        }
        canvas.drawPath(productionPath, productionStrokePaint)
        productionStrokePaint.pathEffect = null
    }

    private fun drawPlaceholder(canvas: Canvas, placeholder: GlyphTileContent.Placeholder) {
        productionStrokePaint.color = placeholder.color.withAlpha(0.5f)
        productionStrokePaint.strokeWidth = placeholder.strokeWidth
        productionStrokePaint.pathEffect = placeholder.dashIntervals.toDashEffect()
        canvas.drawRect(placeholder.bounds.toRectF(), productionStrokePaint)
        productionStrokePaint.pathEffect = null
        drawCenteredText(
            canvas = canvas,
            text = "?",
            bounds = placeholder.bounds,
            textSize = placeholder.questionTextSize,
            color = placeholder.color,
        )
    }

    private fun drawCenteredText(
        canvas: Canvas,
        text: String,
        bounds: RenderRect,
        textSize: Float,
        color: Int,
    ) {
        productionTextPaint.color = color
        productionTextPaint.textSize = textSize
        productionTextPaint.textAlign = Paint.Align.CENTER
        val metrics = productionTextPaint.fontMetrics
        val x = (bounds.left + bounds.right) / 2f
        val baseline = (bounds.top + bounds.bottom) / 2f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(text, x, baseline, productionTextPaint)
    }

    private data class ProductionRender(
        val renderer: GlyphDisplayRenderer,
        val snapshot: GlyphDisplaySnapshot,
        val uptime: Double,
        val canvas: OverlayCanvasConfiguration,
    )
}

private fun RenderRect.toRectF(): RectF = RectF(left, top, right, bottom)

private fun List<Float>.toDashEffect(): DashPathEffect? =
    if (isEmpty()) null else DashPathEffect(toFloatArray(), 0f)
