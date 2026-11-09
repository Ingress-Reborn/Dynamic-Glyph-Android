package org.szres.glyphlens.overlay

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.MainThread
import org.szres.glyphlens.R
import org.szres.glyphlens.recognition.GlyphDisplaySnapshot
import org.szres.glyphlens.recognition.GlyphTable
import org.szres.glyphlens.settings.OverlayCanvasConfiguration
import kotlin.math.roundToInt

/** An in-app preview drawn by the exact production GlyphDiagramView/renderer, never a placeholder. */
class OverlayPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    val statusView = TextView(context)
    val diagramView = GlyphDiagramView(context)
    private var canvasConfiguration = OverlayCanvasConfiguration.standard

    var renderedSnapshot: GlyphDisplaySnapshot? = null
        private set

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setBackgroundResource(R.drawable.panel_background)
        val padding = dp(10)
        setPadding(padding, padding, padding, padding)
        statusView.apply {
            setTextColor(context.getColor(R.color.accent))
            textSize = 12f
            maxLines = 2
            gravity = Gravity.CENTER_VERTICAL
        }
        addView(statusView, LayoutParams(LayoutParams.MATCH_PARENT, dp(32)))
        addView(diagramView, LayoutParams(LayoutParams.MATCH_PARENT, dp(64)))
        contentDescription = context.getString(R.string.overlay_preview_accessibility)
    }

    @MainThread
    fun render(
        snapshot: GlyphDisplaySnapshot,
        table: GlyphTable?,
        uptime: Double,
        canvas: OverlayCanvasConfiguration,
        label: String? = null,
    ) {
        renderedSnapshot = snapshot
        canvasConfiguration = canvas
        statusView.text = listOfNotNull(label, snapshot.statusLine(uptime)).joinToString(" · ")
        diagramView.render(snapshot, table, uptime, canvas)
        contentDescription = statusView.text
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight)
            .coerceAtLeast(dp(48))
        val height = (available / canvasConfiguration.aspectRatio).roundToInt().coerceAtLeast(dp(32))
        val diagramParams = diagramView.layoutParams
        if (diagramParams.height != height) {
            diagramParams.height = height
            diagramView.layoutParams = diagramParams
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
