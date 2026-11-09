package com.dynamicglyph.display

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.dynamicglyph.recognition.GlyphDiagramLayout
import com.dynamicglyph.recognition.GlyphDisplaySnapshot
import com.dynamicglyph.recognition.GlyphPath
import com.dynamicglyph.recognition.GlyphTable
import com.dynamicglyph.recognition.SlotObservation
import com.dynamicglyph.recognition.Vec2
import kotlin.math.abs
import kotlin.math.max

/**
 * Floating overlay that shows the recognised sequence over the game.
 *
 * Replaces the iOS Picture-in-Picture window and the Live Activity. Both of
 * those exist on iOS because the app cannot draw over another app; on Android a
 * `TYPE_APPLICATION_OVERLAY` window does it directly, so there is one display
 * surface instead of two.
 *
 * The panel redraws on a fixed 15 Hz tick rather than on every snapshot: the
 * tracker publishes at up to 20 Hz and most of those frames change nothing.
 */
class GlyphOverlayWindow(private val context: Context) {

    companion object {
        private const val TAG = "GlyphOverlay"
        private const val WIDTH_DP = 340
        private const val HEIGHT_DP = 132
        private const val TICK_MS = 66L
    }

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var panel: GlyphPanelView? = null
    private var params: WindowManager.LayoutParams? = null
    private var showing = false

    /** A message to display before the first snapshot arrives. Set from any thread. */
    @Volatile
    private var pendingStatus: String? = null

    /** Why the window could not be added, if it failed. Readable from the service. */
    @Volatile
    var lastError: String? = null
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            panel?.invalidate()
            mainHandler.postDelayed(this, TICK_MS)
        }
    }

    val isShowing: Boolean get() = showing

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (showing) return

        val density = context.resources.displayMetrics.density
        val width = (WIDTH_DP * density).toInt()
        val height = (HEIGHT_DP * density).toInt()

        val layoutParams = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Not focusable so the game keeps input focus, but still touchable so
            // the panel can be dragged out of the way.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (72 * density).toInt()
        }

        val view = GlyphPanelView(context, density)
        view.statusText = pendingStatus
        view.setOnTouchListener(DragListener(layoutParams))

        try {
            windowManager.addView(view, layoutParams)
        } catch (e: Exception) {
            // Previously this swallowed the exception and returned, so the user
            // saw nothing at all and had no idea why. Surface it: a missing
            // overlay permission on HyperOS is the most common cause, but the
            // actual exception should be visible either way.
            val reason = "${e.javaClass.simpleName}: ${e.message}"
            lastError = reason
            Log.e(TAG, "Could not add the overlay window: $reason", e)
            mainHandler.post {
                android.widget.Toast.makeText(
                    context,
                    "悬浮窗显示失败：$reason\n请检查「悬浮窗」和「后台弹出界面」权限",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        lastError = null
        panel = view
        params = layoutParams
        showing = true
        mainHandler.post(tick)
        Log.i(TAG, "Overlay shown ${width}x$height")
    }

    fun hide() {
        if (!showing) return
        mainHandler.removeCallbacks(tick)
        panel?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "Could not remove the overlay window", e)
            }
        }
        panel = null
        params = null
        showing = false
    }

    /**
     * Show a plain-language status on the panel before any snapshot arrives.
     * Safe to call from the capture thread; the write lands on the main thread.
     */
    fun setStatus(text: String?) {
        pendingStatus = text
        mainHandler.post {
            panel?.statusText = text
            panel?.invalidate()
        }
    }

    fun update(snapshot: GlyphDisplaySnapshot?) {
        mainHandler.post {
            panel?.snapshot = snapshot
            panel?.invalidate()
        }
    }

    fun setGlyphTable(table: GlyphTable?) {
        mainHandler.post { panel?.table = table }
    }

    /** Drag the panel; the game keeps receiving everything outside it. */
    private class DragListener(
        private val params: WindowManager.LayoutParams
    ) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - downX).toInt()
                    params.y = startY + (event.rawY - downY).toInt()
                    val manager = view.context.getSystemService(Context.WINDOW_SERVICE)
                        as WindowManager
                    try {
                        manager.updateViewLayout(view, params)
                    } catch (e: Exception) {
                        Log.w("GlyphOverlay", "Could not move the overlay", e)
                    }
                    return true
                }
            }
            return false
        }
    }

    /**
     * Draws the panel. Reads only the snapshot: no decisions are made here.
     */
    private class GlyphPanelView(
        context: Context,
        private val density: Float
    ) : View(context) {

        var snapshot: GlyphDisplaySnapshot? = null
        var table: GlyphTable? = null

        /**
         * Plain-language status shown until the first recognition snapshot, and
         * again if the pipeline reports a failure. May contain newlines.
         */
        var statusText: String? = null

        private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(235, 10, 13, 20)
            style = Paint.Style.FILL
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f * density
        }
        private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(90, 77, 79, 97)
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
        }
        private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val predictedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            pathEffect = android.graphics.DashPathEffect(
                floatArrayOf(8f * density, 5f * density), 0f
            )
        }
        private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = SECONDARY_TEXT
            textSize = 11f * density
            textAlign = Paint.Align.CENTER
        }
        private val indexPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = IDLE
            textSize = 9f * density
            textAlign = Paint.Align.CENTER
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val radius = 14f * density

            canvas.drawRoundRect(RectF(0f, 0f, w, h), radius, radius, backgroundPaint)

            val current = snapshot
            if (current == null) {
                val message = statusText ?: "Waiting for capture"
                ringPaint.color = if (statusText == null) IDLE else WARNING
                canvas.drawRoundRect(
                    RectF(2f * density, 2f * density, w - 2f * density, h - 2f * density),
                    radius, radius, ringPaint
                )
                val lines = message.split("\n")
                val lineHeight = 17f * density
                val startY = h / 2f - (lines.size - 1) * lineHeight / 2f
                for (index in lines.indices) {
                    canvas.drawText(lines[index], w / 2f, startY + index * lineHeight, statusPaint)
                }
                return
            }

            // Status ring, the equivalent of the iOS window's edge colour.
            ringPaint.color = toneColor(current.statusTone)
            canvas.drawRoundRect(
                RectF(2f * density, 2f * density, w - 2f * density, h - 2f * density),
                radius, radius, ringPaint
            )

            val top = 12f * density
            val bottom = h - 24f * density
            val count = max(1, current.slots.size)
            val padding = 8f * density
            val slotWidth = (w - 2 * padding) / count

            if (current.slots.isEmpty()) {
                canvas.drawText(
                    "No glyph sequence yet", w / 2f, (top + bottom) / 2f, statusPaint
                )
            } else {
                for (slot in current.slots) {
                    val left = padding + slot.index * slotWidth
                    drawSlot(canvas, slot, current, left, top, left + slotWidth, bottom)
                }
            }

            canvas.drawText(current.statusLine, w / 2f, h - 9f * density, statusPaint)
        }

        private fun drawSlot(
            canvas: Canvas,
            slot: GlyphDisplaySnapshot.SlotDisplay,
            snapshot: GlyphDisplaySnapshot,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float
        ) {
            val inset = 3f * density
            val tile = RectF(left + inset, top + inset, right - inset, bottom - inset)
            val tone = statusColor(slot.status)

            tilePaint.color = Color.argb(38, Color.red(tone), Color.green(tone), Color.blue(tone))
            canvas.drawRoundRect(tile, 8f * density, 8f * density, tilePaint)

            val layout = GlyphDiagramLayout.inRect(
                tile.left.toDouble(), tile.top.toDouble(),
                tile.right.toDouble(), tile.bottom.toDouble()
            )

            drawFramework(canvas, layout)

            val glyph = slot.glyph
            val path = if (glyph != null) table?.pathFor(glyph) else null
            if (path != null) {
                glyphPaint.color = tone
                canvas.drawPath(pathFor(layout, path), glyphPaint)
            } else if (slot.status == SlotObservation.Status.PENDING) {
                // An untouched slot is a dim outline only.
                framePaint.color = Color.argb(45, 77, 79, 97)
                canvas.drawPath(outlinePath(layout), framePaint)
                framePaint.color = Color.argb(90, 77, 79, 97)
            }

            // Predicted continuation for a slot the game has not shown yet.
            if (slot.glyph == null && snapshot.prediction != null) {
                val predicted = predictedGlyphFor(slot.index, snapshot)
                val predictedPath = if (predicted != null) table?.pathFor(predicted) else null
                if (predictedPath != null) {
                    predictedPaint.color = PREDICTED
                    canvas.drawPath(pathFor(layout, predictedPath), predictedPaint)
                }
            }

            canvas.drawText(
                (slot.index + 1).toString(),
                tile.centerX(),
                tile.bottom - 3f * density,
                indexPaint
            )
        }

        private fun predictedGlyphFor(
            index: Int,
            snapshot: GlyphDisplaySnapshot
        ): com.dynamicglyph.recognition.GlyphID? {
            val prediction = snapshot.prediction ?: return null
            if (!prediction.isResolved) return null
            val observed = prediction.observed.size
            if (index < observed) return null
            return prediction.remaining.getOrNull(index - observed)
        }

        private fun drawFramework(canvas: Canvas, layout: GlyphDiagramLayout) {
            framePaint.color = Color.argb(90, 77, 79, 97)
            canvas.drawPath(outlinePath(layout), framePaint)

            // Small filled dots, not rings: the hollow ring is exactly what the
            // node detector locks onto, and the app must never calibrate on its
            // own overlay.
            nodePaint.color = Color.argb(150, 77, 79, 97)
            for (node in layout.nodes) {
                canvas.drawCircle(node.x.toFloat(), node.y.toFloat(), 1.4f * density, nodePaint)
            }
        }

        private fun outlinePath(layout: GlyphDiagramLayout): Path {
            val path = Path()
            val outline = layout.outline
            if (outline.isEmpty()) return path
            path.moveTo(outline[0].x.toFloat(), outline[0].y.toFloat())
            for (index in 1 until outline.size) {
                path.lineTo(outline[index].x.toFloat(), outline[index].y.toFloat())
            }
            path.close()
            return path
        }

        private fun pathFor(layout: GlyphDiagramLayout, glyph: GlyphPath): Path {
            val path = Path()
            val positions = layout.nodePositions
            var started = false
            for (node in glyph.nodes) {
                val point = positions[node.rawValue]
                if (!started) {
                    path.moveTo(point.x.toFloat(), point.y.toFloat())
                    started = true
                } else {
                    path.lineTo(point.x.toFloat(), point.y.toFloat())
                }
            }
            return path
        }

        private fun statusColor(status: SlotObservation.Status): Int = when (status) {
            SlotObservation.Status.CONFIRMED -> CONFIRMED
            SlotObservation.Status.OBSERVING -> OBSERVING
            SlotObservation.Status.INFERRED -> OBSERVING
            SlotObservation.Status.UNCERTAIN -> WARNING
            SlotObservation.Status.MISSING -> IDLE
            SlotObservation.Status.PENDING -> UNKNOWN
        }

        private fun toneColor(tone: GlyphDisplaySnapshot.StatusTone): Int = when (tone) {
            GlyphDisplaySnapshot.StatusTone.WARNING -> WARNING
            GlyphDisplaySnapshot.StatusTone.TENTATIVE -> OBSERVING
            GlyphDisplaySnapshot.StatusTone.IDLE -> IDLE
            GlyphDisplaySnapshot.StatusTone.COLLECTING -> OBSERVING
            GlyphDisplaySnapshot.StatusTone.DRAWING -> CONFIRMED
            GlyphDisplaySnapshot.StatusTone.DONE -> PREDICTED
        }
    }
}

/**
 * Palette, converted from the original's 0..1 triples.
 *
 * File-scope rather than class-scope so both the window and the view it hosts
 * read the same values without one nesting inside the other.
 */
private val BACKGROUND = Color.rgb(10, 13, 20)
private val CONFIRMED = Color.rgb(204, 199, 255)
private val OBSERVING = Color.rgb(255, 199, 77)
private val PREDICTED = Color.rgb(115, 217, 242)
private val UNKNOWN = Color.rgb(140, 140, 158)
private val WARNING = Color.rgb(255, 107, 107)
private val PRIMARY_TEXT = Color.rgb(242, 242, 255)
private val IDLE = Color.rgb(107, 112, 133)
private val SECONDARY_TEXT = Color.rgb(158, 161, 184)
private val FRAME = Color.rgb(77, 79, 97)
