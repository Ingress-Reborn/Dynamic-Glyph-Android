package org.szres.glyphlens.capture

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.szres.glyphlens.R
import org.szres.glyphlens.diagnostics.PowerMeter
import org.szres.glyphlens.diagnostics.PowerMetric
import org.szres.glyphlens.diagnostics.PowerReport
import org.szres.glyphlens.recognition.CaptureLiveness
import org.szres.glyphlens.recognition.GlyphDisplaySnapshot
import org.szres.glyphlens.recognition.PredictionState
import org.szres.glyphlens.recognition.SlotObservation
import org.szres.glyphlens.recognition.TrackingState
import org.szres.glyphlens.settings.AppPreferences
import org.szres.glyphlens.test.FixtureVideoActivity
import org.szres.glyphlens.ui.MainActivity
import java.io.File

/** Exercises system capture at playback speed; movies never enter the recognizer directly. */
@RunWith(AndroidJUnit4::class)
class LiveCaptureRecognitionInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var reportsMapControls = true

    @Test
    fun fiveGlyphVideoIsRecognizedAndPredictedThroughLiveProjection() {
        verifyLiveCapture(
            "real-recording.mp4", listOf("question", "less", "forget", "all", "lie"),
        )
    }

    @Test
    fun threeGlyphVideoIsRecognizedAndPredictedThroughLiveProjection() {
        verifyLiveCapture("real-recording-093906.mp4", listOf("nourish", "xm", "portal"))
    }

    @Test
    fun mapIconsHideResultsAndContinueNextRound() {
        verifyLiveCapture(
            "real-recording.mp4", listOf("question", "less", "forget", "all", "lie"),
            hidesAfterDrawing = true,
        )
    }

    @Test
    fun mapIconsPreserveFiveGlyphsWithHiddenTitles() {
        verifyLiveCapture(
            "real-recording.mp4", listOf("question", "less", "forget", "all", "lie"),
            hidesAfterDrawing = true,
            hidesHeaderText = true,
        )
    }

    @Test
    fun threeGlyphMapIconsHideResultsAfterDirectReturn() {
        verifyLiveCapture(
            "real-recording-093906.mp4", listOf("nourish", "xm", "portal"),
            hidesAfterDrawing = true,
        )
    }

    @Test
    fun newThreeGlyphMapIconsHideResultsAndContinueNextRound() {
        verifyLiveCapture(
            "real-recording-124013.mp4", listOf("advance", "human", "enlightenment"),
            hidesAfterDrawing = true,
        )
    }

    @Test
    fun fiveGlyphFullPlaybackPerformance() {
        verifyLiveCapture(
            "real-recording.mp4", listOf("question", "less", "forget", "all", "lie"),
            hidesAfterDrawing = true,
            performanceWindowMillis = 30_000,
        )
    }

    @Test
    fun continuousFiveGlyphRoundsShareOneCaptureSession() {
        verifyLiveCapture(
            "real-recording.mp4", listOf("question", "less", "forget", "all", "lie"),
            continuousRounds = true,
        )
    }

    @Test
    fun hiddenRecognitionShowsPredictionsAndRestoresFromNotification() {
        verifyLiveCapture(
            "real-recording.mp4", listOf("question", "less", "forget", "all", "lie"),
            checksHiddenPrediction = true,
        )
    }

    private fun verifyLiveCapture(
        fileName: String,
        expected: List<String>,
        hidesAfterDrawing: Boolean = false,
        continuousRounds: Boolean = false,
        checksHiddenPrediction: Boolean = false,
        hidesHeaderText: Boolean = false,
        performanceWindowMillis: Long = 0,
    ) {
        val source = File(context.getExternalFilesDir(null), fileName)
        val outputDirectory = File(context.getExternalFilesDir(null), "live-capture-tests").apply { mkdirs() }
        val reportName = source.nameWithoutExtension +
            (if (hidesAfterDrawing) "-auto-hide" else "") +
            (if (continuousRounds) "-continuous" else "") +
            (if (checksHiddenPrediction) "-hidden" else "") +
            (if (hidesHeaderText) "-masked-title" else "") +
            (if (performanceWindowMillis > 0) "-performance" else "")
        val output = File(outputDirectory, "$reportName.json")
        assertTrue("Cannot remove the previous live-test result: $output", !output.exists() || output.delete())
        val failureImage = File(outputDirectory, "$reportName-failure.png")
        assertTrue("Cannot remove the previous failure screenshot", !failureImage.exists() || failureImage.delete())
        assumeTrue("Live MediaProjection fixture missing: ${source.absolutePath}", source.isFile)
        assumeTrue("Live overlay regression requires display-over-other-apps permission", Settings.canDrawOverlays(context))
        assertEquals("Live test requires an idle capture service", CaptureService.SessionState.STOPPED, CaptureService.currentState)
        reportsMapControls = performanceWindowMillis == 0L
        val preferences = context.getSharedPreferences(AppPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE)
        val overrides: Map<String, Any?> = mapOf(
            AppPreferences.AUTO_HIDE_KEY to hidesAfterDrawing,
            "display.floatingWindow" to true,
        )
        val previous = preferences.all.filterKeys { it in overrides }
        var home: MainActivity? = null
        var playerStarted = false
        var requestedCapture = false
        val trace = LiveTrace(reportsMapControls)
        val lifecycle = JSONObject()
            .put("headerTextMasked", hidesHeaderText)
        var report: JSONObject? = null
        val accessibilityFlags = instrumentation.uiAutomation.serviceInfo.flags

        try {
            instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
            val playbackSource = fixtureForPlayer(source)
            preferences.edit().apply {
                overrides.forEach { (key, value) ->
                    when (value) {
                        null -> remove(key)
                        is Boolean -> putBoolean(key, value)
                        is String -> putString(key, value)
                    }
                }
            }.commit()
            requestedCapture = true
            val startedHome = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            ) as MainActivity
            home = startedHome
            grantWholeScreenProjection()
            await("CaptureService did not start after system consent", 15_000) {
                CaptureService.currentState == CaptureService.SessionState.RUNNING
            }
            await("Automatic recognition must start with no floating panel", 5_000) {
                !CaptureService.isOverlayVisible && overlayControl("glyphDiagram") == null
            }

            if (checksHiddenPrediction) {
                showFromNotification("$reportName-initial")
                val initial = requireNotNull(CaptureService.latestSnapshot)
                lifecycle.put("draggedIntoArray", dragPanelIntoArray())
                clickOverlayControl("closeOverlay")
                lifecycle.put("hiddenBeforePlayback", observeHiddenSession(initial, durationMillis = 1_000))
            }

            val fullPlaybackMeasurements = RoundMeasurements()
            val playbackStartedAt = SystemClock.elapsedRealtime()
            playFixture(playbackSource, hidesHeaderText)
            playerStarted = true
            if (hidesAfterDrawing) {
                await("Automatic recognition must wait without a panel", 5_000) {
                    !CaptureService.isOverlayVisible && overlayControl("glyphDiagram") == null
                }
            }
            val firstPopup = if (checksHiddenPrediction || hidesAfterDrawing) JSONObject() else null
            firstPopup?.let {
                it.put("screenshotPrefix", reportName)
                lifecycle.put("firstAutomaticPopup", it)
            }
            val accepted = observePredictionAndFrozenInput(fileName, expected, trace, hidesAfterDrawing,
                automaticPopup = firstPopup)
            report = liveReport(source, expected, trace, hidesAfterDrawing, accepted = accepted).apply {
                put("recognitionLifecycle", lifecycle)
            }
            if (continuousRounds) {
                val nextTrace = LiveTrace()
                var nextAcceptance: AcceptedSession? = null
                playFixture(playbackSource, hidesHeaderText)
                try {
                    nextAcceptance = observePredictionAndFrozenInput(
                        fileName, expected, nextTrace, hidesAfterDrawing = false,
                        previousRound = accepted.input,
                    )
                    assertEquals("Continuous rounds must use the same capture session",
                        accepted.input.captureSessionID, nextAcceptance.input.captureSessionID)
                    assertTrue("A new round must advance its hack ID", nextAcceptance.input.hackID > accepted.input.hackID)
                    lifecycle.put("continuedWithoutRestart", true)
                    lifecycle.put("previousRoundPrefixCleared", true)
                    lifecycle.put("firstHackID", accepted.input.hackID)
                    lifecycle.put("secondHackID", nextAcceptance.input.hackID)
                    lifecycle.put("captureSessionID", accepted.input.captureSessionID.toString())
                } finally {
                    lifecycle.put("continuousNextRound", liveReport(source, expected, nextTrace, false, accepted = nextAcceptance).apply {
                        put("status", if (nextAcceptance == null) "failed" else "passed")
                    })
                }
            }
            if (checksHiddenPrediction) {
                clickOverlayControl("closeOverlay")
                lifecycle.put("afterClose", observeHiddenSession(accepted.input))
                showFromNotification("$reportName-after-close")
                val restored = requireNotNull(CaptureService.latestSnapshot)
                assertEquals("Notification must retain the active recognition session",
                    accepted.input.captureSessionID, restored.captureSessionID)
                assertEquals("Showing the window must preserve the confirmed sequence", accepted.input.slots, restored.slots)
                lifecycle.put("notificationShowClicked", true)
                lifecycle.put("sameCaptureSessionAfterShow", restored.captureSessionID.toString())
            }
            if (checksHiddenPrediction) {
                clickOverlayControl("closeOverlay")
                await("The second close must leave no floating surface", 5_000) { !CaptureService.isOverlayVisible }
                val nextTrace = LiveTrace()
                val nextPopup = JSONObject().also {
                    it.put("screenshotPrefix", reportName)
                    lifecycle.put("nextAutomaticPopup", it)
                }
                var nextAcceptance: AcceptedSession? = null
                playFixture(playbackSource, hidesHeaderText)
                try {
                    nextAcceptance = observePredictionAndFrozenInput(
                        fileName, expected, nextTrace, hidesAfterDrawing = false,
                        previousRound = accepted.input, automaticPopup = nextPopup,
                    )
                    assertEquals("Hidden rounds must keep the same capture session",
                        accepted.input.captureSessionID, nextAcceptance.input.captureSessionID)
                    assertTrue("The next automatic popup must belong to a later round",
                        nextAcceptance.input.hackID > accepted.input.hackID)
                    lifecycle.put("continuedHiddenWithoutRestart", true)
                } finally {
                    lifecycle.put("hiddenNextRound", liveReport(source, expected, nextTrace, false, accepted = nextAcceptance).apply {
                        put("status", if (nextAcceptance == null) "failed" else "passed")
                    })
                }
            }
            if (performanceWindowMillis > 0) {
                while (SystemClock.elapsedRealtime() - playbackStartedAt < performanceWindowMillis) {
                    val current = requireNotNull(CaptureService.latestSnapshot)
                    assertEquals(accepted.input.captureSessionID, current.captureSessionID)
                    assertEquals("Full playback must retain the five confirmed Glyphs", accepted.input.slots, current.slots)
                    assertEquals(CaptureService.SessionState.RUNNING, CaptureService.currentState)
                    trace.observe(current)
                    SystemClock.sleep(20)
                }
                lifecycle.put("fullPlaybackMeasurements", fullPlaybackMeasurements.finish())
                lifecycle.put("performanceWindowMillis", performanceWindowMillis)
            } else if (hidesAfterDrawing) {
                lifecycle.put("mapReturned", observeMapReturn(accepted.input, trace, reportName))
                showFromNotification("$reportName-on-map", expectsVisible = false)
                lifecycle.put("mapStillHidesAfterNotification", observeHiddenSession(accepted.input, durationMillis = 750))
                val nextTrace = LiveTrace()
                val nextPopup = JSONObject().also {
                    it.put("screenshotPrefix", reportName)
                    lifecycle.put("nextAutomaticPopup", it)
                }
                var nextAcceptance: AcceptedSession? = null
                playFixture(playbackSource, hidesHeaderText)
                try {
                    nextAcceptance = observePredictionAndFrozenInput(
                        fileName, expected, nextTrace, hidesAfterDrawing = true,
                        previousRound = accepted.input, automaticPopup = nextPopup,
                    )
                    assertEquals("Automatic rounds must share the same capture session",
                        accepted.input.captureSessionID, nextAcceptance.input.captureSessionID)
                    lifecycle.put("nextMapReturned", observeMapReturn(nextAcceptance.input, nextTrace, "$reportName-next"))
                    lifecycle.put("continuedAutomaticallyWithoutRestart", true)
                } finally {
                    lifecycle.put("automaticNextRound", liveReport(source, expected, nextTrace, true, accepted = nextAcceptance).apply {
                        put("status", if (nextAcceptance == null) "failed" else "passed")
                    })
                }
            }
            lifecycle.put("completeSession", liveReport(source, expected, trace, hidesAfterDrawing))
            context.startService(Intent(context, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
            await("Full stop did not release screen sharing", 15_000) {
                CaptureService.currentState == CaptureService.SessionState.STOPPED && !CaptureService.isOverlayVisible
            }
            assertTrue("Full stop must leave recognition inactive", !CaptureService.isRecognizing)
            lifecycle.put("fullStopVerified", true)
            output.writeText(report.toString(2))
        } catch (failure: Throwable) {
            try {
                val failureSnapshot = CaptureService.latestSnapshot
                failureSnapshot?.let(trace::observe)
                val failureReport = report ?: liveReport(source, expected, trace, hidesAfterDrawing)
                failureReport.put("finalLiveReport", liveReport(source, expected, trace, hidesAfterDrawing).apply {
                    put("status", "failed")
                })
                failureReport.put("recognitionLifecycle", lifecycle)
                failureReport.put("status", "failed")
                failureReport.put("failureClass", failure.javaClass.name)
                failureReport.put("failureMessage", failure.message)
                failureSnapshot?.let { failureReport.put("failureSnapshot", snapshotJson(it)) }
                output.writeText(failureReport.toString(2))
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                if (screenshot != null) {
                    try {
                        failureImage.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    } finally {
                        screenshot.recycle()
                    }
                }
            } catch (reportFailure: Exception) {
                failure.addSuppressed(reportFailure)
            }
            throw failure
        } finally {
            try {
                if (requestedCapture && CaptureService.currentState != CaptureService.SessionState.STOPPED) {
                    context.startService(Intent(context, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
                    await("CaptureService did not release the live session", 15_000) {
                        CaptureService.currentState == CaptureService.SessionState.STOPPED
                    }
                }
            } finally {
                try {
                    if (playerStarted) {
                        context.startActivity(
                            Intent().setClassName(instrumentation.context.packageName, FixtureVideoActivity::class.java.name)
                                .setAction(FixtureVideoActivity.ACTION_STOP)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        )
                    }
                    instrumentation.runOnMainSync {
                        home?.finish()
                    }
                } finally {
                    instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
                        flags = accessibilityFlags
                    }
                    preferences.edit().apply {
                        overrides.keys.forEach { remove(it) }
                        previous.forEach { (key, value) ->
                            when (value) {
                                is Boolean -> putBoolean(key, value)
                                is String -> putString(key, value)
                            }
                        }
                    }.commit()
                }
            }
        }
    }

    private fun playFixture(source: File, hidesHeaderText: Boolean) {
        context.startActivity(
            Intent().setClassName(instrumentation.context.packageName, FixtureVideoActivity::class.java.name)
                .putExtra("video", source.absolutePath)
                .putExtra("hideHeaderText", hidesHeaderText)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    private fun observeHiddenSession(
        snapshot: GlyphDisplaySnapshot,
        durationMillis: Long = 3_000,
    ): JSONObject {
        await("X must completely hide the floating window", 5_000) {
            !CaptureService.isOverlayVisible && overlayControl("glyphDiagram") == null
        }
        val frames = CaptureService.frameCount
        val before = PowerMeter.shared.snapshot()
        val started = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - started < durationMillis) {
            assertEquals("Hiding must preserve the active capture state", CaptureService.SessionState.RUNNING, CaptureService.currentState)
            assertTrue("Hiding must keep recognition active", CaptureService.isRecognizing)
            val current = requireNotNull(CaptureService.latestSnapshot)
            assertEquals("Hiding must keep the same capture session", snapshot.captureSessionID, current.captureSessionID)
            assertEquals("The hidden observation must remain in the same round", snapshot.hackID, current.hackID)
            assertTrue("The same round must not reopen the dismissed result", !CaptureService.isOverlayVisible)
            assertEquals("The dismissed panel must stay absent", null, overlayControl("glyphDiagram"))
            SystemClock.sleep(100)
        }
        val after = PowerMeter.shared.snapshot()
        assertTrue("Hidden recognition must keep acquiring frames", CaptureService.frameCount > frames)
        assertTrue("Hidden recognition must keep analysing frames",
            after.total(PowerMetric.FRAMES_ANALYSED).count > before.total(PowerMetric.FRAMES_ANALYSED).count)
        assertEquals("Hidden windows must not keep rendering", before.total(PowerMetric.DISPLAY_UPDATE).count,
            after.total(PowerMetric.DISPLAY_UPDATE).count)
        return JSONObject().apply {
            put("observedMillis", SystemClock.elapsedRealtime() - started)
            put("captureSessionID", snapshot.captureSessionID.toString())
            put("captureState", CaptureService.currentState.name)
            put("frameCountBefore", frames)
            put("frameCountAfter", CaptureService.frameCount)
            put("analysedCountBefore", before.total(PowerMetric.FRAMES_ANALYSED).count)
            put("analysedCountAfter", after.total(PowerMetric.FRAMES_ANALYSED).count)
            put("displayUpdateCountBefore", before.total(PowerMetric.DISPLAY_UPDATE).count)
            put("displayUpdateCountAfter", after.total(PowerMetric.DISPLAY_UPDATE).count)
            put("floatingPanelAbsent", true)
        }
    }

    private fun assertControlsHidden() {
        assertEquals("Automatic hiding removes the whole control row", null, overlayControl("overlayControls"))
        assertEquals("Automatic hiding must not expose the title/drag row", null, overlayControl("dragHandle"))
        assertEquals("Automatic hiding must not expose X", null, overlayControl("closeOverlay"))
        assertTrue("The Glyph canvas must stay available while drawing", overlayControl("glyphDiagram")?.isVisibleToUser == true)
    }

    private fun observeMapReturn(
        input: GlyphDisplaySnapshot,
        trace: LiveTrace,
        reportName: String,
    ): JSONObject {
        val measurements = RoundMeasurements()
        val started = SystemClock.elapsedRealtime()
        val deadline = started + 18_000
        var map: GlyphDisplaySnapshot? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            // Accessibility retrieval can span the frame that detects a map icon and removes the window.
            val canvasVisible = CaptureService.isOverlayVisible &&
                overlayControl("glyphDiagram")?.isVisibleToUser == true
            val snapshot = requireNotNull(CaptureService.latestSnapshot)
            trace.observe(snapshot)
            assertEquals("Automatic hiding must not restart capture", input.captureSessionID, snapshot.captureSessionID)
            assertEquals("Map return must come from the first playback of this round", input.hackID, snapshot.hackID)
            assertEquals("Recognition must keep running through map return", CaptureService.SessionState.RUNNING, CaptureService.currentState)
            assertTrue("Recognition must stay active after drawing", CaptureService.isRecognizing)
            if (snapshot.hasMapControl) {
                assertEquals(CaptureLiveness.LIVE, snapshot.liveness)
                assertEquals("Drawing must not rewrite the recognized sequence", input.slots, snapshot.slots)
                map = snapshot
                break
            }
            assertTrue("Completion text and result panels must not hide the result before a map icon appears", canvasVisible)
            SystemClock.sleep(20)
        }
        val mapSnapshot = map ?: throw AssertionError("The recording did not provide a map-control observation before its next loop")
        val mapMeasurements = measurements.finish()
        await("A detected map icon must automatically hide every floating surface", 3_000) {
            !CaptureService.isOverlayVisible && overlayControl("glyphDiagram") == null
        }
        val screenshotName = "$reportName-map-hidden.png"
        saveLiveScreenshot(screenshotName)
        val hidden = observeHiddenSession(mapSnapshot, durationMillis = 750)
        return JSONObject().apply {
            put("elapsedAfterInputCheckMillis", SystemClock.elapsedRealtime() - started)
            put("mapSnapshot", snapshotJson(mapSnapshot))
            put("mapTransitionMeasurements", mapMeasurements)
            put("hiddenOnMap", hidden)
            put("screenshot", screenshotName)
            put("sameCaptureSession", true)
            put("recognitionContinues", true)
        }
    }

    private fun dragPanelIntoArray(): JSONObject {
        val screen = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val screenWidth = screen.width
        val screenHeight = screen.height
        screen.recycle()
        val handle = Rect().also { requireNotNull(overlayControl("dragHandle")).getBoundsInScreen(it) }
        val startX = handle.exactCenterX()
        val startY = handle.exactCenterY()
        val targetY = screenHeight * 0.60f
        val downAt = SystemClock.uptimeMillis()
        for (step in 0..10) {
            val action = when (step) {
                0 -> MotionEvent.ACTION_DOWN
                10 -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action,
                startX, startY + (targetY - startY) * step / 10f, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            try {
                assertTrue("The actual panel drag must be delivered", instrumentation.uiAutomation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
            SystemClock.sleep(25)
        }
        val panel = Rect()
        await("The fixture must move the panel into the array region", 5_000) {
            overlayControl("glyphDiagram")?.window?.getBoundsInScreen(panel)
            panel.top > screenHeight * 0.45
        }
        val screenshotName = "real-recording-hidden-dragged.png"
        saveLiveScreenshot(screenshotName)
        return JSONObject().apply {
            put("screenWidth", screenWidth)
            put("screenHeight", screenHeight)
            put("windowBounds", panel.toShortString())
            put("actualTouchDrag", true)
            put("screenshot", screenshotName)
        }
    }

    private fun recordPopupPlacement(snapshot: GlyphDisplaySnapshot, evidence: JSONObject) {
        val panel = Rect()
        await("The automatic popup must expose its window bounds", 5_000) {
            overlayControl("glyphDiagram")?.window?.getBoundsInScreen(panel)
            !panel.isEmpty
        }
        val screen = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val screenshotName = "${evidence.getString("screenshotPrefix")}-popup-${snapshot.hackID}.png"
        try {
            // The fixed 1216×2688 recording's upper ring starts below y=940; this is an independent fixture boundary.
            val scale = minOf(screen.width / 1216.0, screen.height / 2688.0)
            val arrayClearanceTop = (screen.height - 2688 * scale) / 2 + 940 * scale
            assertTrue("Automatic popup must move out of the array instead of keeping the dragged position: $panel",
                panel.bottom <= arrayClearanceTop)
            File(context.getExternalFilesDir(null), "live-capture-tests/$screenshotName")
                .outputStream().use { assertTrue(screen.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            evidence.put("windowBounds", panel.toShortString())
            evidence.put("arrayClearanceTopPixels", arrayClearanceTop)
            evidence.put("screenshot", screenshotName)
        } finally {
            screen.recycle()
        }
    }

    private fun saveLiveScreenshot(name: String) {
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.getExternalFilesDir(null), "live-capture-tests/$name")
                .outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            screenshot.recycle()
        }
    }

    private fun showFromNotification(reportName: String, expectsVisible: Boolean = true) {
        val label = context.getString(R.string.notification_show_overlay)
        val title = context.getString(R.string.notification_title)
        val frameBeforeNotification = CaptureService.latestSnapshot?.sourceFrameTime ?: 0.0
        runShell("cmd statusbar expand-notifications")
        var expandRequests = 0
        var nextExpandAt = 0L
        try {
            var action: AccessibilityNodeInfo? = null
            await("Notification does not expose its Show floating window action", 5_000) {
                action = findInWindows {
                    it.packageName?.toString() == "com.android.systemui" && it.isVisibleToUser &&
                        it.text?.toString()?.equals(label, ignoreCase = true) == true
                }
                if (action == null && SystemClock.elapsedRealtime() >= nextExpandAt) {
                    var card = findInWindows {
                        it.packageName?.toString() == "com.android.systemui" && it.isVisibleToUser &&
                            it.text?.toString() == title
                    }?.parent
                    while (card != null) {
                        if (card.viewIdResourceName == "com.android.systemui:id/expandableNotificationRow") {
                            if (card.isVisibleToUser && card.actionList.any { it.id == AccessibilityNodeInfo.ACTION_EXPAND }) {
                                // ACTION_EXPAND cannot collapse a row while the shade animation is still updating.
                                card.performAction(AccessibilityNodeInfo.ACTION_EXPAND)
                                expandRequests++
                                nextExpandAt = SystemClock.elapsedRealtime() + 250
                            }
                            break
                        }
                        card = card.parent
                    }
                }
                action != null
            }
            click(requireNotNull(action))
        } catch (failure: Throwable) {
            try {
                saveLiveScreenshot("$reportName-notification-failure.png")
                val windows = JSONArray()
                for (window in instrumentation.uiAutomation.windows) {
                    val bounds = Rect().also(window::getBoundsInScreen)
                    windows.put(JSONObject().apply {
                        put("id", window.id)
                        put("type", window.type)
                        put("title", window.title?.toString())
                        put("active", window.isActive)
                        put("focused", window.isFocused)
                        put("bounds", bounds.toShortString())
                        window.root?.let { put("root", accessibilityTree(it)) }
                    })
                }
                File(context.getExternalFilesDir(null), "live-capture-tests/$reportName-notification-windows.json")
                    .writeText(JSONObject().apply {
                        put("expectedAction", label)
                        put("expectedTitle", title)
                        put("expandRequests", expandRequests)
                        put("windows", windows)
                    }.toString(2))
            } catch (diagnosticFailure: Throwable) {
                failure.addSuppressed(diagnosticFailure)
            }
            throw failure
        } finally {
            runShell("cmd statusbar collapse")
        }
        await("The actual notification action did not respect map auto-hiding", 5_000) {
            val current = CaptureService.latestSnapshot
            val currentMapFrame = expectsVisible || current != null && current.hasMapControl &&
                current.sourceFrameTime > frameBeforeNotification
            currentMapFrame && CaptureService.isOverlayVisible == expectsVisible &&
                (overlayControl("glyphDiagram") != null) == expectsVisible
        }
    }

    private fun accessibilityTree(node: AccessibilityNodeInfo): JSONObject {
        val bounds = Rect().also(node::getBoundsInScreen)
        return JSONObject().apply {
            put("package", node.packageName?.toString())
            put("class", node.className?.toString())
            put("id", node.viewIdResourceName)
            put("text", node.text?.toString())
            put("description", node.contentDescription?.toString())
            put("visible", node.isVisibleToUser)
            put("clickable", node.isClickable)
            put("enabled", node.isEnabled)
            put("bounds", bounds.toShortString())
            put("actions", JSONArray(node.actionList.map { it.id }))
            put("children", JSONArray().apply {
                for (index in 0 until node.childCount) node.getChild(index)?.let { put(accessibilityTree(it)) }
            })
        }
    }

    private fun runShell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
    }

    private fun clickOverlayControl(id: String) {
        await("Floating control $id is missing", 5_000) { overlayControl(id) != null }
        click(requireNotNull(overlayControl(id)))
    }

    private fun overlayControl(id: String): AccessibilityNodeInfo? = findInWindows {
        it.viewIdResourceName == "${context.packageName}:id/$id"
    }

    private fun findInWindows(matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? = instrumentation.uiAutomation.windows
        .firstNotNullOfOrNull { window ->
            window.root?.let { root -> findNode(root, matches) }
        }

    private fun fixtureForPlayer(source: File): File {
        val appDataDirectory = source.parentFile.parentFile.parentFile
        val playbackSource = File(appDataDirectory, "${instrumentation.context.packageName}/files/${source.name}")
        // The player has its own UID; shell can check its preinstalled fixture across Android/data boundaries.
        val size = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("stat -c %s ${playbackSource.absolutePath}"),
        ).bufferedReader().use { it.readText().trim() }
        assertEquals("Preinstall the test APK's player fixture: $playbackSource", source.length().toString(), size)
        return playbackSource
    }

    private data class AcceptedSession(
        val prediction: GlyphDisplaySnapshot,
        val input: GlyphDisplaySnapshot,
        val elapsedMillis: Long,
        val measurements: JSONObject,
    )

    private fun observePredictionAndFrozenInput(
        fileName: String,
        expected: List<String>,
        trace: LiveTrace,
        hidesAfterDrawing: Boolean,
        previousRound: GlyphDisplaySnapshot? = null,
        automaticPopup: JSONObject? = null,
    ): AcceptedSession {
        val measurements = RoundMeasurements()
        val expectsAmbiguousPrefix = fileName == "real-recording-124013.mp4"
        val predictions = mutableMapOf<Int, GlyphDisplaySnapshot>()
        var frozen: GlyphDisplaySnapshot? = null
        var frozenAt = 0L
        var last: GlyphDisplaySnapshot? = null
        var nextRoundStarted = false
        var observedGlyphPhase = false
        var nextRoundHidden = previousRound == null || !CaptureService.isOverlayVisible
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + if (expectsAmbiguousPrefix) 24_000 else 40_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val snapshot = CaptureService.latestSnapshot
            if (snapshot != null && (previousRound == null || snapshot.hackID > previousRound.hackID)) {
                if (snapshot.state == TrackingState.PRESENTING ||
                    snapshot.outlook.observed.isNotEmpty()
                ) {
                    observedGlyphPhase = true
                }
                if (reportsMapControls && observedGlyphPhase && snapshot.hasMapControl) {
                    val evidenceName = "${fileName.removeSuffix(".mp4")}-unexpected-map-${snapshot.hackID}-${SystemClock.elapsedRealtime()}"
                    saveLiveScreenshot("$evidenceName.png")
                    File(context.getExternalFilesDir(null), "live-capture-tests/$evidenceName.json")
                        .writeText(snapshotJson(snapshot).apply {
                            put("sourceFile", fileName)
                            put("elapsedMillis", SystemClock.elapsedRealtime() - startedAt)
                            put("screenshot", "$evidenceName.png")
                        }.toString(2))
                    throw AssertionError("Map control falsely detected during the fixture's Glyph phase before stable input: $evidenceName")
                }
            }
            if (previousRound != null && hidesAfterDrawing && !CaptureService.isOverlayVisible) nextRoundHidden = true
            if (automaticPopup != null && !automaticPopup.has("hackID") && CaptureService.isOverlayVisible &&
                (previousRound == null || snapshot != null && snapshot.hackID > previousRound.hackID) &&
                (!hidesAfterDrawing || nextRoundHidden)
            ) {
                val shown = requireNotNull(snapshot)
                assertTrue("An automatic popup must show the requested new round",
                    previousRound == null || shown.hackID > previousRound.hackID)
                if (expectsAmbiguousPrefix) {
                    assertTrue("An ambiguous prefix must wait for all three Glyphs before showing the result",
                        shown.state == TrackingState.PRESENTING || shown.state == TrackingState.INPUT)
                    assertEquals(expected, shown.slots.map { it.glyph?.name })
                    assertTrue("The popup must contain three actual confirmations, not an inferred final Glyph",
                        shown.slots.all { it.status == SlotObservation.Status.CONFIRMED })
                    assertEquals(expected, shown.outlook.observed.map { it.name })
                    assertTrue(shown.outlook.predictedSuffix.isEmpty())
                    assertEquals(PredictionState.RESOLVED, shown.outlook.state)
                    assertEquals(1, shown.outlook.candidateCount)
                } else {
                    assertEquals("The automatic popup must appear during prediction", TrackingState.PRESENTING, shown.state)
                    assertEquals("The automatic popup must have a unique prediction", PredictionState.RESOLVED, shown.outlook.state)
                    assertTrue("The automatic popup must use a confirmed prefix and a remaining prediction",
                        shown.outlook.observed.isNotEmpty() && shown.outlook.predictedSuffix.isNotEmpty())
                }
                assertEquals("The automatic popup must complete this recording's entire sequence", expected,
                    (shown.outlook.observed + shown.outlook.predictedSuffix).map { it.name })
                assertEquals(expected.size, shown.expectedSlotCount)
                automaticPopup.put("hackID", shown.hackID)
                automaticPopup.put("captureSessionID", shown.captureSessionID.toString())
                automaticPopup.put("elapsedMillis", SystemClock.elapsedRealtime() - startedAt)
                automaticPopup.put("confirmedPrefix", JSONArray(shown.outlook.observed.map { it.name }))
                automaticPopup.put("predictedSuffix", JSONArray(shown.outlook.predictedSuffix.map { it.name }))
                automaticPopup.put("showedAfterAllGlyphsConfirmed", expectsAmbiguousPrefix)
                recordPopupPlacement(shown, automaticPopup)
                if (hidesAfterDrawing) {
                    assertControlsHidden()
                    automaticPopup.put("controlRowAbsent", true)
                    automaticPopup.put("previousRoundHiddenBeforePopup", nextRoundHidden)
                }
            }
            if (previousRound != null && snapshot != null) {
                assertEquals("Continuous recognition must keep the existing capture session",
                    previousRound.captureSessionID, snapshot.captureSessionID)
            }
            if (snapshot != null && snapshot !== last) {
                last = snapshot
                trace.observe(snapshot)
                val outlook = snapshot.outlook
                val isNextRound = previousRound == null || snapshot.hackID > previousRound.hackID
                if (previousRound != null && isNextRound && !nextRoundStarted && snapshot.state == TrackingState.PRESENTING) {
                    assertTrue("A new round must clear the previous confirmed prefix", outlook.observed.isEmpty())
                    assertTrue("A new round must clear previous confirmed Glyphs", snapshot.slots.none { it.status == SlotObservation.Status.CONFIRMED })
                    assertTrue("A new round must release the previous frozen input", !snapshot.isFrozen)
                    nextRoundStarted = true
                }
                if (snapshot.state == TrackingState.PRESENTING &&
                    isNextRound &&
                    outlook.observed.map { it.name } == expected.take(2)
                ) {
                    if (expectsAmbiguousPrefix) {
                        assertEquals(PredictionState.AMBIGUOUS, outlook.state)
                        assertEquals(2, outlook.candidateCount)
                        assertEquals(setOf(
                            listOf("advance", "human", "enlightenment"),
                            listOf("advance", "human", "resistance"),
                        ), outlook.candidates.map { candidate -> candidate.glyphs.map { it.name } }.toSet())
                        assertTrue("Two valid final Glyphs must not produce a unique suffix", outlook.predictedSuffix.isEmpty())
                    } else {
                        assertEquals(PredictionState.RESOLVED, outlook.state)
                        assertEquals(expected.drop(2), outlook.predictedSuffix.map { it.name })
                    }
                    assertEquals(expected.size, snapshot.expectedSlotCount)
                    assertTrue(snapshot.slots.take(2).all { it.status == SlotObservation.Status.CONFIRMED })
                    if (predictions.put(snapshot.hackID, snapshot) == null) {
                        trace.predictedHackIDs += snapshot.hackID
                        Log.i(TAG, "$fileName hack ${snapshot.hackID}: confirmed-prefix prediction verified")
                    }
                }
                if (frozen == null && snapshot.state == TrackingState.INPUT &&
                    isNextRound
                ) {
                    if (previousRound != null) assertTrue("A new round must be observed presenting before its first input", nextRoundStarted)
                    if (automaticPopup != null) assertTrue("Prediction must have automatically shown the hidden panel", automaticPopup.has("hackID"))
                    assertTrue("First input must have verified the prediction state of its confirmed first two slots", snapshot.hackID in predictions)
                    assertEquals("First input must contain the complete recorded sequence", expected, snapshot.slots.map { it.glyph?.name })
                    assertTrue("Every slot must be confirmed at first input", snapshot.slots.all { it.status == SlotObservation.Status.CONFIRMED })
                    assertTrue("First input must freeze the expected sequence", snapshot.isFrozen)
                    frozen = snapshot
                    frozenAt = SystemClock.elapsedRealtime()
                }
            }
            if (frozen != null) {
                val current = requireNotNull(snapshot) { "The completed sequence must remain published" }
                assertEquals("Input must retain the same hack", frozen.hackID, current.hackID)
                assertEquals("Input must stay recognized during the freeze check", TrackingState.INPUT, current.state)
                assertEquals("Player strokes must not replace the expected sequence", frozen.slots, current.slots)
                assertTrue("Expected sequence must stay frozen", current.isFrozen)
                assertEquals(CaptureLiveness.LIVE, current.liveness)
                val retainedMillis = SystemClock.elapsedRealtime() - frozenAt
                if (hidesAfterDrawing) {
                    assertTrue("The result must stay visible during the player's drawing phase", CaptureService.isOverlayVisible)
                    assertControlsHidden()
                }
                if (retainedMillis >= 3_000) {
                    Log.i(TAG, "$fileName hack ${current.hackID}: full sequence retained while drawing for three seconds")
                    return AcceptedSession(
                        predictions.getValue(current.hackID), frozen, SystemClock.elapsedRealtime() - startedAt,
                        measurements.finish(),
                    )
                }
            }
            assertEquals("Live capture stopped unexpectedly", CaptureService.SessionState.RUNNING, CaptureService.currentState)
            SystemClock.sleep(20)
        }
        throw AssertionError(
            "$fileName did not produce prediction and frozen input in the same hack; " +
                "predicted hacks=${predictions.keys}; last=" +
                "${last?.state} / ${last?.hackID} / ${last?.slots} / ${last?.outlook}",
        )
    }

    private class RoundMeasurements {
        private val startedAt = SystemClock.elapsedRealtime()
        private val cpuStarted = Process.getElapsedCpuTime()
        private val framesStarted = CaptureService.frameCount
        private val powerStarted = PowerMeter.shared.snapshot()

        fun finish(): JSONObject {
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            val cpu = Process.getElapsedCpuTime() - cpuStarted
            val power = PowerMeter.shared.snapshot()
            return JSONObject().apply {
                put("elapsedMilliseconds", elapsed)
                put("processCpuMilliseconds", cpu)
                put("equivalentOneCoreCpuPercent", 100.0 * cpu / elapsed)
                put("cpuScope", "Application and instrumentation process; fixture video plays in another process")
                put("frameCountDelta", CaptureService.frameCount - framesStarted)
                put("metrics", JSONObject().apply {
                    for (metric in PowerMetric.entries) {
                        val before = powerStarted.total(metric)
                        val after = power.total(metric)
                        put(metric.name, JSONObject().apply {
                            put("count", after.count - before.count)
                            put("nanoseconds", after.nanoseconds - before.nanoseconds)
                        })
                    }
                })
            }
        }
    }

    /** Counts published source frames, not the tracker's private shape hypotheses. */
    private class LiveTrace(private val includesMapControls: Boolean = true) {
        val startedAtEpochMillis = System.currentTimeMillis()
        val startedAtUptimeMillis = SystemClock.elapsedRealtime()
        val predictedHackIDs = mutableSetOf<Int>()
        val spans = ArrayDeque<StateSpan>()
        var droppedSpans = 0
            private set

        fun observe(snapshot: GlyphDisplaySnapshot) {
            val key = listOf(
                snapshot.hackID, snapshot.state, snapshot.phaseSource, snapshot.expectedSlotCount,
                snapshot.currentSlotIndex, snapshot.inputSlotIndex, snapshot.isFrozen,
                snapshot.indicatorsObstructed, snapshot.slotCountConflict, snapshot.provisionalGlyph,
                snapshot.outlook.state, snapshot.outlook.observed, snapshot.outlook.predictedSuffix,
                snapshot.slots.map { Triple(it.status, it.glyph, it.alternatives) },
                if (includesMapControls) snapshot.hasMapControl else false,
            ).joinToString("|")
            val elapsed = SystemClock.elapsedRealtime() - startedAtUptimeMillis
            val previous = spans.lastOrNull()
            if (previous?.key == key) {
                if (snapshot.sourceFrameTime > previous.last.sourceFrameTime) previous.distinctSourceFrames += 1
                previous.last = snapshot
                previous.lastElapsedMillis = elapsed
            } else {
                if (spans.size == 80) {
                    spans.removeFirst()
                    droppedSpans += 1
                }
                spans.addLast(StateSpan(key, snapshot, snapshot, elapsed, elapsed))
            }
        }
    }

    private data class StateSpan(
        val key: String,
        val first: GlyphDisplaySnapshot,
        var last: GlyphDisplaySnapshot,
        val firstElapsedMillis: Long,
        var lastElapsedMillis: Long,
        var distinctSourceFrames: Int = 1,
    )

    private fun liveReport(
        source: File,
        expected: List<String>,
        trace: LiveTrace,
        hidesAfterDrawing: Boolean,
        accepted: AcceptedSession? = null,
    ): JSONObject {
        val snapshot = CaptureService.latestSnapshot
        val power = PowerMeter.shared.snapshot()
        return JSONObject().apply {
            val metrics = context.resources.displayMetrics
            if (Build.VERSION.SDK_INT >= 30) {
                val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
                put("displayWidthPixels", bounds.width())
                put("displayHeightPixels", bounds.height())
                put("windowBounds", JSONObject().apply {
                    put("left", bounds.left)
                    put("top", bounds.top)
                    put("right", bounds.right)
                    put("bottom", bounds.bottom)
                })
            }
            put("displayDensityDpi", metrics.densityDpi)
            put("status", "passed")
            put("runStartedAtEpochMillis", trace.startedAtEpochMillis)
            put("sourceFile", source.absolutePath)
            put("videoPlaybackProcess", "${instrumentation.context.packageName}:fixture_video")
            put("videoScaleMode", "fitCenter")
            put("expectedSequence", JSONArray(expected))
            put("projectionRunning", CaptureService.currentState == CaptureService.SessionState.RUNNING)
            put("overlayVisible", CaptureService.isOverlayVisible)
            put("elapsedMilliseconds", accepted?.elapsedMillis ?: SystemClock.elapsedRealtime() - trace.startedAtUptimeMillis)
            put("hidesAfterDrawing", hidesAfterDrawing)
            put("captureState", CaptureService.currentState.name)
            put("frameCount", CaptureService.frameCount)
            put("viewportCalibration", snapshot?.calibrationSummary ?: JSONObject.NULL)
            put("sessionElapsedSeconds", power.elapsedSeconds)
            put("cpuSeconds", power.cpuSeconds ?: JSONObject.NULL)
            put("verifiedPredictionHackIDs", JSONArray(trace.predictedHackIDs))
            accepted?.let {
                put("confirmedSequence", JSONArray(it.input.slots.map { slot -> slot.glyph?.name }))
                put("observedPrefixAtPrediction", JSONArray(it.prediction.outlook.observed.map { glyph -> glyph.name }))
                put("predictedSuffix", JSONArray(it.prediction.outlook.predictedSuffix.map { glyph -> glyph.name }))
                put("prefixPredictionSnapshot", snapshotJson(it.prediction).apply {
                    put("candidateSequences", JSONArray(it.prediction.outlook.candidates.map { candidate ->
                        JSONArray(candidate.glyphs.map { glyph -> glyph.name })
                    }))
                })
                put("inputFreeze", it.input.isFrozen)
                put("roundMeasurements", it.measurements)
            }
            put("droppedStateSpans", trace.droppedSpans)
            put("stateSpans", JSONArray(trace.spans.map { span ->
                snapshotJson(span.last).apply {
                    put("firstElapsedMillis", span.firstElapsedMillis)
                    put("lastElapsedMillis", span.lastElapsedMillis)
                    put("firstSourceFrameTime", span.first.sourceFrameTime)
                    put("publishedDistinctSourceFrames", span.distinctSourceFrames)
                }
            }))
            snapshot?.let { put("lastPublishedSnapshot", snapshotJson(it)) }
            put("powerReport", JSONArray(PowerReport.lines(power)))
        }
    }

    private fun snapshotJson(snapshot: GlyphDisplaySnapshot): JSONObject = JSONObject().apply {
        put("captureSessionID", snapshot.captureSessionID.toString())
        put("hackID", snapshot.hackID)
        put("sourceFrameTime", snapshot.sourceFrameTime)
        put("arrivalUptime", snapshot.evidenceArrivalUptime)
        put("state", snapshot.state.name)
        put("phaseSource", snapshot.phaseSource.name)
        if (reportsMapControls) put("hasMapControl", snapshot.hasMapControl)
        put("liveness", snapshot.liveness.name)
        put("expectedSlotCount", snapshot.expectedSlotCount ?: JSONObject.NULL)
        put("currentSlotIndex", snapshot.currentSlotIndex ?: JSONObject.NULL)
        put("inputSlotIndex", snapshot.inputSlotIndex ?: JSONObject.NULL)
        put("frozen", snapshot.isFrozen)
        put("indicatorsObstructed", snapshot.indicatorsObstructed)
        put("slotCountConflict", snapshot.slotCountConflict)
        put("provisionalGlyph", snapshot.provisionalGlyph?.name ?: JSONObject.NULL)
        put("provisionalExtensionCount", snapshot.provisionalExtensionCount)
        put("calibration", snapshot.calibrationSummary)
        put("predictionState", snapshot.outlook.state.name)
        put("confirmedPrefix", JSONArray(snapshot.outlook.observed.map { it.name }))
        put("predictedSuffix", JSONArray(snapshot.outlook.predictedSuffix.map { it.name }))
        put("sequenceCandidateCount", snapshot.outlook.candidateCount)
        put("slots", JSONArray(snapshot.slots.map { slot ->
            JSONObject().apply {
                put("index", slot.index)
                put("status", slot.status.name)
                put("glyph", slot.glyph?.name ?: JSONObject.NULL)
                put("alternatives", JSONArray(slot.alternatives.map { it.name }))
                put("trackerSampleCount", slot.sampleCount)
                put("firstSampleTime", slot.firstSampleTime ?: JSONObject.NULL)
                put("lastSampleTime", slot.lastSampleTime ?: JSONObject.NULL)
                put("boundaryReason", slot.boundaryReason ?: JSONObject.NULL)
            }
        }))
    }

    private fun grantWholeScreenProjection() {
        var selectedWholeScreen = false
        var openedSelector = false
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (root != null) {
                val notificationPermission = findNode(root) {
                    it.viewIdResourceName?.endsWith(":id/permission_allow_button") == true
                }
                if (notificationPermission != null) {
                    click(notificationPermission)
                } else {
                    val wholeScreen = findNode(root) { it.text?.toString() in WHOLE_SCREEN_LABELS }
                    val selector = findNode(root) {
                        it.viewIdResourceName == "com.android.systemui:id/screen_share_mode_options"
                    }
                    when {
                        wholeScreen != null && openedSelector && !selectedWholeScreen -> {
                            click(wholeScreen)
                            selectedWholeScreen = true
                        }
                        selector != null && !openedSelector && !selectedWholeScreen -> {
                            click(selector)
                            openedSelector = true
                        }
                        selectedWholeScreen || selector == null && !openedSelector -> {
                            val positive = findNode(root) { it.viewIdResourceName == "android:id/button1" }
                            if (positive != null && root.packageName?.toString() == "com.android.systemui") {
                                click(positive)
                                return
                            }
                        }
                    }
                }
            }
            SystemClock.sleep(100)
        }
        val root = instrumentation.uiAutomation.rootInActiveWindow
        throw AssertionError("System MediaProjection consent was not available: ${root?.packageName} / $root")
    }

    private fun findNode(node: AccessibilityNodeInfo, matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (matches(node)) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            findNode(child, matches)?.let { return it }
        }
        return null
    }

    private fun click(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null) {
            if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            target = target.parent
        }
        throw AssertionError("System control cannot be clicked: ${node.viewIdResourceName} / ${node.text}")
    }

    private fun await(message: String, timeoutMillis: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        throw AssertionError(message)
    }

    companion object {
        private const val TAG = "LiveCaptureAcceptance"
        private val WHOLE_SCREEN_LABELS = setOf("共享整个屏幕", "整个屏幕", "Entire screen", "Share entire screen")
    }
}
