package org.szres.glyphlens.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.util.DisplayMetrics
import android.util.Size
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.annotation.MainThread
import org.szres.glyphlens.R
import org.szres.glyphlens.diagnostics.PowerMeter
import org.szres.glyphlens.recognition.GlyphDisplaySnapshot
import org.szres.glyphlens.recognition.GlyphTable
import org.szres.glyphlens.settings.AppPreferences
import org.szres.glyphlens.settings.OverlayWindowGeometry

@MainThread
@SuppressLint("InflateParams")
class OverlayController(
    context: Context,
    private val onClose: () -> Unit,
) {
    private val applicationContext = context.applicationContext
    private val preferences = AppPreferences(applicationContext)
    private val preferenceStore = applicationContext.getSharedPreferences(
        AppPreferences.PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val windowManager = applicationContext.getSystemService(WindowManager::class.java)
    private val view: View = LayoutInflater.from(applicationContext).inflate(R.layout.overlay_glyph, null)
    private val glyphDiagram: GlyphDiagramView = view.findViewById(R.id.glyphDiagram)
    private val statusLabel: TextView = view.findViewById(R.id.dragHandle)
    private val controls: View = view.findViewById(R.id.overlayControls)
    private val closeButton: Button = view.findViewById(R.id.closeOverlay)
    private val layoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 16
        // Leave the array clear until its measured geometry can place the window precisely.
        y = (screenSize().height * 0.18).toInt()
    }
    private var attached = false
    private var preferencesRegistered = false
    private var latest: LatestRender? = null
    private val placement = OverlayPlacement()
    private var gameGeometry: OverlayGameGeometry? = null

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == AppPreferences.CANVAS_WIDTH_KEY ||
            key == AppPreferences.CANVAS_HEIGHT_KEY ||
            key == AppPreferences.CANVAS_RESOLUTION_KEY ||
            key == AppPreferences.MODE_COLORS_KEY ||
            key == AppPreferences.AUTO_HIDE_KEY ||
            key == "pip.preventsCapture"
        ) {
            if (latest != null) renderCurrent() else applyPreferences()
        }
    }

    init {
        closeButton.contentDescription = applicationContext.getString(R.string.overlay_hide)
        closeButton.setOnClickListener { onClose() }
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyAutomaticPlacement() }
        installDragHandler(statusLabel)
        installDragHandler(glyphDiagram)
        applyPreferences()
    }

    val isVisible: Boolean
        get() = attached

    fun updateGameGeometry(geometry: OverlayGameGeometry?) {
        gameGeometry = geometry
        applyPreferences()
        applyAutomaticPlacement()
    }

    fun resetAutomaticPlacement() {
        placement.resetAutomaticPlacement()
        layoutParams.x = 16
        layoutParams.y = (screenSize().height * 0.18).toInt()
    }

    /** Production entry point for CaptureService; call on the main thread with one shared uptime. */
    fun render(snapshot: GlyphDisplaySnapshot, table: GlyphTable?, uptime: Double) {
        latest = LatestRender(snapshot, table, uptime)
        renderCurrent()
    }

    private fun renderCurrent() {
        val current = latest ?: return
        applyPreferences()
        statusLabel.text = current.snapshot.statusLine(current.uptime)
        val tint = ReadingModeTint.displayed(
            enabled = preferences.colorsFloatingWindowByMode,
            reading = PowerMeter.shared.readingMode,
        )
        glyphDiagram.render(
            snapshot = current.snapshot,
            table = current.table,
            uptime = current.uptime,
            canvas = preferences.overlayCanvas,
            readingModeTint = tint,
        )
    }

    fun show() {
        renderCurrent()
        showPanel()
    }

    private fun showPanel() {
        if (attached) return
        hide()
        applyPreferences()
        addWindow(view, layoutParams)
        attached = true
    }

    private fun addWindow(window: View, params: WindowManager.LayoutParams) {
        if (!preferencesRegistered) {
            preferenceStore.registerOnSharedPreferenceChangeListener(preferenceListener)
            preferencesRegistered = true
        }
        try {
            windowManager.addView(window, params)
        } catch (error: RuntimeException) {
            preferenceStore.unregisterOnSharedPreferenceChangeListener(preferenceListener)
            preferencesRegistered = false
            throw error
        }
    }

    fun hide() {
        if (preferencesRegistered) {
            preferenceStore.unregisterOnSharedPreferenceChangeListener(preferenceListener)
            preferencesRegistered = false
        }
        if (attached) {
            windowManager.removeView(view)
            attached = false
        }
    }

    /** Applies to an attached window only; never reopens a hidden window or restarts capture. */
    private fun applyPreferences() {
        val hideControls = preferences.hidesAfterDrawing
        val controlsVisibility = if (hideControls) View.GONE else View.VISIBLE
        val controlsChanged = controls.visibility != controlsVisibility
        controls.visibility = controlsVisibility
        glyphDiagram.contentDescription = if (hideControls) applicationContext.getString(R.string.drag_window) else null
        val metrics = applicationContext.resources.displayMetrics
        val screen = screenSize()
        placement.updateDisplay(screen.width, screen.height, metrics.density)
        val horizontalMargin = (16f * metrics.density).toInt().coerceAtLeast(0)
        val preferred = OverlayWindowGeometry.fit(
            canvas = preferences.overlayCanvas,
            density = metrics.density,
            availableWidthPixels = (metrics.widthPixels - horizontalMargin * 2).coerceAtLeast(1),
        )
        val diagramParams = glyphDiagram.layoutParams
        val controlHeight = if (hideControls) 0 else controls.layoutParams.height
        val minimumControlWidth = if (hideControls) 1 else closeButton.layoutParams.width + controlHeight
        val geometry = placement.fitCanvas(
            preferred = preferred,
            maximumWindowHeight = placement.maximumWindowHeight(
                gameGeometry, screen.height, (8f * metrics.density).toInt(),
            ),
            controlsHeight = controlHeight + view.paddingTop + view.paddingBottom,
            minimumWidth = minimumControlWidth,
            current = if (attached) OverlayWindowGeometry(diagramParams.width, diagramParams.height) else preferred,
        )
        val contentWidth = geometry.widthPixels
        val contentHeight = geometry.canvasHeightPixels
        val changed = controlsChanged || layoutParams.width != contentWidth + view.paddingLeft + view.paddingRight ||
            diagramParams.width != geometry.widthPixels || diagramParams.height != contentHeight
        layoutParams.width = contentWidth + view.paddingLeft + view.paddingRight
        layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT
        if (diagramParams.width != geometry.widthPixels || diagramParams.height != contentHeight) {
            diagramParams.width = geometry.widthPixels
            diagramParams.height = contentHeight
            glyphDiagram.layoutParams = diagramParams
        }
        val secure = preferences.protectsFloatingWindowFromCapture
        layoutParams.flags = if (secure) {
            layoutParams.flags or WindowManager.LayoutParams.FLAG_SECURE
        } else {
            layoutParams.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
        }
        if (changed && attached) windowManager.updateViewLayout(view, layoutParams)
        else if (attached && secure != wasSecure) windowManager.updateViewLayout(view, layoutParams)
        wasSecure = secure
    }

    private var wasSecure = false

    private fun applyAutomaticPlacement() {
        if (!attached || view.height == 0) return
        val controlHeight = if (controls.visibility == View.GONE) 0 else controls.layoutParams.height
        val expectedHeight = glyphDiagram.layoutParams.height + controlHeight +
            view.paddingTop + view.paddingBottom
        // Resizing is asynchronous; place only after the new canvas height has been laid out.
        if (view.height != expectedHeight) return
        val metrics = applicationContext.resources.displayMetrics
        val height = screenSize().height
        val margin = (8f * metrics.density).toInt()
        val geometry = gameGeometry
        val top = if (geometry != null) {
            placement.top(geometry, height, view.height, margin)
        } else {
            placement.initialTop(height, view.height, margin)
        } ?: return
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        // WindowManager's y can start below the system status bar; recognition uses the whole screen.
        layoutParams.y = top - (location[1] - layoutParams.y)
        windowManager.updateViewLayout(view, layoutParams)
    }

    @Suppress("DEPRECATION")
    private fun screenSize(): Size = if (Build.VERSION.SDK_INT >= 30) {
        windowManager.maximumWindowMetrics.bounds.let { Size(it.width(), it.height()) }
    } else {
        DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }
            .let { Size(it.widthPixels, it.heightPixels) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installDragHandler(handle: View) {
        handle.setOnTouchListener { target, event ->
            if (target === glyphDiagram && controls.visibility != View.GONE) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = layoutParams.x
                    dragStartY = layoutParams.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val newX = dragStartX + (event.rawX - touchStartX).toInt()
                    val newY = dragStartY + (event.rawY - touchStartY).toInt()
                    if (newX != layoutParams.x || newY != layoutParams.y) {
                        placement.userMoved()
                        layoutParams.x = newX
                        layoutParams.y = newY
                        windowManager.updateViewLayout(view, layoutParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    target.performClick()
                    true
                }
                else -> false
            }
        }
    }

    private data class LatestRender(
        val snapshot: GlyphDisplaySnapshot,
        val table: GlyphTable?,
        val uptime: Double,
    )

    private var dragStartX = 0
    private var dragStartY = 0
    private var touchStartX = 0f
    private var touchStartY = 0f
}
