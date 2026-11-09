package org.szres.glyphlens.overlay

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.szres.glyphlens.R
import org.szres.glyphlens.recognition.AcceptanceMode
import org.szres.glyphlens.recognition.CaptureLiveness
import org.szres.glyphlens.recognition.CaptureSessionID
import org.szres.glyphlens.recognition.GlyphDisplaySnapshot
import org.szres.glyphlens.recognition.GlyphID
import org.szres.glyphlens.recognition.GlyphTable
import org.szres.glyphlens.recognition.PhaseSource
import org.szres.glyphlens.recognition.SequenceOutlook
import org.szres.glyphlens.recognition.SlotObservation
import org.szres.glyphlens.recognition.TrackingState
import org.szres.glyphlens.settings.AppPreferences
import org.szres.glyphlens.settings.OverlayCanvasConfiguration
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class OverlayControllerInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation
    private val starts = AtomicInteger()
    private val stops = AtomicInteger()
    private lateinit var controller: OverlayController
    private var originalAccessibilityFlags: Int? = null

    @Before
    fun prepareRealOverlay() {
        assumeTrue("Display-over-other-apps permission is required", Settings.canDrawOverlays(context))
        automation.serviceInfo = automation.serviceInfo.apply {
            originalAccessibilityFlags = flags
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        instrumentation.runOnMainSync {
            controller = OverlayController(
                context,
                onStartRecognition = {
                    starts.incrementAndGet()
                    controller.show()
                },
                onClose = {
                    stops.incrementAndGet()
                    controller.showStandby()
                },
            )
        }
    }

    @After
    fun removeOverlay() {
        if (::controller.isInitialized) instrumentation.runOnMainSync { controller.hide() }
        originalAccessibilityFlags?.let { previous ->
            automation.serviceInfo = automation.serviceInfo.apply { flags = previous }
        }
    }

    @Test
    fun iconStartsPanelAndCloseReturnsToIcon() {
        instrumentation.runOnMainSync {
            controller.showStandby()
            assertTrue(controller.isVisible)
            assertTrue(controller.isStandbyVisible)
        }
        assertTrue(awaitNode("startRecognitionIcon").performAction(AccessibilityNodeInfo.ACTION_CLICK))
        val close = awaitNode("closeOverlay")
        assertEquals(1, starts.get())
        instrumentation.runOnMainSync {
            assertTrue(controller.isVisible)
            assertFalse(controller.isStandbyVisible)
        }
        awaitUi("Standby icon remained beside the recognition panel") { node("startRecognitionIcon") == null }

        assertTrue(close.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        awaitNode("startRecognitionIcon")
        assertEquals(1, stops.get())
        instrumentation.runOnMainSync {
            assertTrue(controller.isVisible)
            assertTrue(controller.isStandbyVisible)
            controller.hide()
            assertFalse(controller.isVisible)
            assertFalse(controller.isStandbyVisible)
        }
        awaitUi("Hide left an overlay attached") {
            node("startRecognitionIcon") == null && node("closeOverlay") == null
        }
    }

    @Test
    fun draggingIconDoesNotStartAndSmallTapMovementStillStarts() {
        instrumentation.runOnMainSync { controller.showStandby() }
        val before = Rect().also { awaitNode("startRecognitionIcon").getBoundsInScreen(it) }
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        gesture(before.exactCenterX(), before.exactCenterY(), touchSlop * 4f)

        val after = Rect()
        awaitUi("The real icon did not move after a drag") {
            node("startRecognitionIcon")?.getBoundsInScreen(after)
            after.left - before.left > touchSlop
        }
        assertEquals("Dragging the standby icon must not start recognition", 0, starts.get())
        instrumentation.runOnMainSync { assertTrue(controller.isStandbyVisible) }

        gesture(after.exactCenterX(), after.exactCenterY(), touchSlop / 2f)
        val close = awaitNode("closeOverlay")
        assertEquals("A tap with small finger movement must start once", 1, starts.get())
        assertTrue(close.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        val restored = Rect().also { awaitNode("startRecognitionIcon").getBoundsInScreen(it) }
        assertEquals("Returning to standby must retain the dragged icon position", after, restored)
    }

    @Test
    fun frozenResultSurvivesPreferenceChangeAndCanRestartOrClose() {
        val preferences = AppPreferences(context)
        val previousColors = preferences.colorsFloatingWindowByMode
        val previousCaptureProtection = preferences.protectsFloatingWindowFromCapture
        val previousCanvas = preferences.overlayCanvas
        val density = context.resources.displayMetrics.density
        val screen = checkNotNull(automation.takeScreenshot())
        val narrowGapFraction = 144.0 * density / screen.height
        screen.recycle()
        val table = GlyphTable.parse("see,03\nbegin,06a7")
        val snapshot = GlyphDisplaySnapshot(
            captureSessionID = CaptureSessionID(1UL),
            hackID = 1,
            revision = 1,
            liveness = CaptureLiveness.LIVE,
            state = TrackingState.INPUT,
            phaseSource = PhaseSource.HEADER_TEXT,
            acceptance = AcceptanceMode.TrackingSequence,
            sourceFrameTime = 5.0,
            evidenceArrivalUptime = 10.0,
            expectedSlotCount = 5,
            slots = listOf("see", "begin", "see", "begin", "see").mapIndexed { index, name ->
                SlotObservation(index = index, status = SlotObservation.Status.CONFIRMED, glyph = GlyphID(name))
            },
            currentSlotIndex = null,
            inputSlotIndex = null,
            slotCountConflict = false,
            isFrozen = true,
            provisionalGlyph = null,
            provisionalExtensionCount = 0,
            outlook = SequenceOutlook.unavailable,
            calibrationSummary = "11 rings",
        )
        val resultStatus = context.getString(R.string.overlay_result_ready)

        try {
            instrumentation.runOnMainSync {
                preferences.colorsFloatingWindowByMode = false
                preferences.protectsFloatingWindowFromCapture = false
                preferences.overlayCanvas = OverlayCanvasConfiguration(width = 256, height = 192)
                controller.show()
                controller.updateGameGeometry(
                    OverlayGameGeometry(arrayTopFraction = 0.65, timerBottomFraction = 0.65 - narrowGapFraction),
                )
            }
            val narrowBounds = Rect()
            awaitUi("The fixture did not produce a narrow recognition window") {
                node("closeOverlay")?.window?.getBoundsInScreen(narrowBounds)
                narrowBounds.width() > 0 && narrowBounds.width() < 148f * density
            }
            instrumentation.runOnMainSync {
                controller.showResult(snapshot, table, uptime = 10.0)
                assertTrue(controller.isVisible)
                assertFalse(controller.isStandbyVisible)
            }
            awaitNode("restartRecognitionButton")
            awaitUi("The completed round did not display its paused result status") {
                node("dragHandle")?.text?.toString() == resultStatus && node("startRecognitionIcon") == null
            }
            awaitResultControlsInsidePanel()
            saveScreenshot("frozen-result.png")

            instrumentation.runOnMainSync { preferences.colorsFloatingWindowByMode = true }
            instrumentation.waitForIdleSync()
            assertEquals(resultStatus, awaitNode("dragHandle").text.toString())
            val restart = awaitNode("restartRecognitionButton")
            awaitResultControlsInsidePanel()
            assertEquals("Changing appearance must not start another round", 0, starts.get())
            saveScreenshot("frozen-result-after-preference.png")

            assertTrue(restart.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            awaitUi("Restart kept the result-only controls visible") {
                node("restartRecognitionButton") == null &&
                    node("dragHandle")?.text?.toString() == snapshot.statusLine(10.0)
            }
            assertEquals(1, starts.get())
            instrumentation.runOnMainSync {
                assertTrue(controller.isVisible)
                assertFalse(controller.isStandbyVisible)
            }
            assertTrue(awaitNode("closeOverlay").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            awaitNode("startRecognitionIcon")
            assertEquals(1, stops.get())
            awaitUi("Closing the recognition panel left its result controls attached") {
                node("closeOverlay") == null && node("restartRecognitionButton") == null
            }
        } finally {
            instrumentation.runOnMainSync {
                controller.hide()
                preferences.colorsFloatingWindowByMode = previousColors
                preferences.protectsFloatingWindowFromCapture = previousCaptureProtection
                preferences.overlayCanvas = previousCanvas
            }
        }
    }

    private fun awaitResultControlsInsidePanel() {
        awaitUi("The paused result clipped its restart button, close button, or drag title") {
            val close = node("closeOverlay") ?: return@awaitUi false
            val restart = node("restartRecognitionButton") ?: return@awaitUi false
            val title = node("dragHandle") ?: return@awaitUi false
            val window = close.window ?: return@awaitUi false
            val windowBounds = Rect().also { window.getBoundsInScreen(it) }
            val closeBounds = Rect().also { close.getBoundsInScreen(it) }
            val restartBounds = Rect().also { restart.getBoundsInScreen(it) }
            val titleBounds = Rect().also { title.getBoundsInScreen(it) }
            !closeBounds.isEmpty && !restartBounds.isEmpty && !titleBounds.isEmpty &&
                windowBounds.contains(closeBounds) && windowBounds.contains(restartBounds) &&
                windowBounds.contains(titleBounds)
        }
    }

    private fun saveScreenshot(name: String) {
        instrumentation.waitForIdleSync()
        automation.waitForIdle(100, 5_000)
        val screenshot = checkNotNull(automation.takeScreenshot())
        try {
            val directory = File(context.getExternalFilesDir(null), "overlay-tests")
            assertTrue("Cannot create screenshot output directory", directory.isDirectory || directory.mkdirs())
            File(directory, name).outputStream().use { output ->
                assertTrue("Cannot encode overlay screenshot", screenshot.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            screenshot.recycle()
        }
    }

    private fun gesture(x: Float, y: Float, distance: Float) {
        val downTime = SystemClock.uptimeMillis()
        for ((action, atX) in listOf(
            MotionEvent.ACTION_DOWN to x,
            MotionEvent.ACTION_MOVE to x + distance,
            MotionEvent.ACTION_UP to x + distance,
        )) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, atX, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                assertTrue("System rejected the pointer event", automation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
            SystemClock.sleep(20)
        }
        instrumentation.waitForIdleSync()
    }

    private fun awaitNode(id: String): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        awaitUi("Overlay control unavailable: $id") {
            found = node(id)
            found != null
        }
        return checkNotNull(found)
    }

    private fun awaitUi(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        throw AssertionError(message)
    }

    private fun node(id: String): AccessibilityNodeInfo? {
        val resource = "${context.packageName}:id/$id"
        fun find(current: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (current.viewIdResourceName == resource) return current
            for (index in 0 until current.childCount) {
                val child = current.getChild(index) ?: continue
                find(child)?.let { return it }
            }
            return null
        }
        return automation.windows.firstNotNullOfOrNull { window -> window.root?.let(::find) }
    }
}
