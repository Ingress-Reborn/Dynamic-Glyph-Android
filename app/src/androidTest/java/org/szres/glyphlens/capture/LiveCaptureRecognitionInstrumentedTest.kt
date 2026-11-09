package org.szres.glyphlens.capture

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.WindowManager
import android.widget.Button
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
import org.szres.glyphlens.diagnostics.PowerSnapshot
import org.szres.glyphlens.recognition.CaptureLiveness
import org.szres.glyphlens.recognition.GlyphDisplaySnapshot
import org.szres.glyphlens.recognition.PredictionState
import org.szres.glyphlens.recognition.SlotObservation
import org.szres.glyphlens.recognition.TrackingState
import org.szres.glyphlens.settings.AppPreferences
import org.szres.glyphlens.settings.RecognitionMode
import org.szres.glyphlens.test.FixtureVideoActivity
import org.szres.glyphlens.ui.MainActivity
import java.io.File

/** Exercises system capture at playback speed; movies never enter the recognizer directly. */
@RunWith(AndroidJUnit4::class)
class LiveCaptureRecognitionInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun fiveGlyphVideoIsRecognizedAndPredictedThroughLiveProjection() {
        verifyLiveCapture(
            "real-recording.mp4", listOf("question", "less", "forget", "all", "lie"),
            checksPauseResume = true,
        )
    }

    @Test
    fun threeGlyphVideoIsRecognizedAndPredictedThroughLiveProjection() {
        verifyLiveCapture("real-recording-093906.mp4", listOf("nourish", "xm", "portal"))
    }

    @Test
    fun powerSavingWaitsForIconAndResumesFiveGlyphRecognitionAfterClose() {
        verifyLiveCapture(
            "real-recording.mp4", listOf("question", "less", "forget", "all", "lie"),
            mode = RecognitionMode.POWER_SAVING,
            checksPauseResume = true,
        )
    }

    @Test
    fun realtimeAutomaticallyStopsAfterFiveGlyphsAndRestartsFromRetainedResult() {
        verifyLiveCapture(
            "real-recording.mp4", listOf("question", "less", "forget", "all", "lie"),
            stopsAfterRound = true,
        )
    }

    @Test
    fun powerSavingAutomaticallyStopsAfterFiveGlyphsAndRestartsFromRetainedResult() {
        verifyLiveCapture(
            "real-recording.mp4", listOf("question", "less", "forget", "all", "lie"),
            mode = RecognitionMode.POWER_SAVING,
            stopsAfterRound = true,
        )
    }

    @Test
    fun continuousFiveGlyphRoundsShareOneCaptureSession() {
        verifyLiveCapture(
            "real-recording.mp4", listOf("question", "less", "forget", "all", "lie"),
            continuousRounds = true,
        )
    }

    private fun verifyLiveCapture(
        fileName: String,
        expected: List<String>,
        mode: RecognitionMode = RecognitionMode.REALTIME,
        checksPauseResume: Boolean = false,
        stopsAfterRound: Boolean = false,
        continuousRounds: Boolean = false,
    ) {
        val source = File(context.getExternalFilesDir(null), fileName)
        val outputDirectory = File(context.getExternalFilesDir(null), "live-capture-tests").apply { mkdirs() }
        val reportName = source.nameWithoutExtension +
            (if (mode == RecognitionMode.POWER_SAVING) "-power-saving" else "") +
            (if (stopsAfterRound) "-auto-stop" else "") +
            (if (continuousRounds) "-continuous" else "")
        val output = File(outputDirectory, "$reportName.json")
        assertTrue("Cannot remove the previous live-test result: $output", !output.exists() || output.delete())
        val failureImage = File(outputDirectory, "$reportName-failure.png")
        assertTrue("Cannot remove the previous failure screenshot", !failureImage.exists() || failureImage.delete())
        assumeTrue("Live MediaProjection fixture missing: ${source.absolutePath}", source.isFile)
        assumeTrue("Live overlay regression requires display-over-other-apps permission", Settings.canDrawOverlays(context))
        assertEquals("Live test requires an idle capture service", CaptureService.SessionState.STOPPED, CaptureService.currentState)
        val sourceLength = source.length()
        val sourceModified = source.lastModified()
        val preferences = context.getSharedPreferences(AppPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE)
        val overrides: Map<String, Any?> = mapOf(
            AppPreferences.RECOGNITION_MODE_KEY to mode.rawValue,
            AppPreferences.AUTO_STOP_KEY to stopsAfterRound,
            "display.floatingWindow" to true,
            "game.language" to null,
            "game.detectedLanguage" to null,
        )
        val previous = preferences.all.filterKeys { it in overrides }
        var home: MainActivity? = null
        var playerStarted = false
        var requestedCapture = false
        val trace = LiveTrace()
        val lifecycle = JSONObject()
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
            val startedHome = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            ) as MainActivity
            home = startedHome
            instrumentation.runOnMainSync {
                requestedCapture = true
                assertTrue(startedHome.findViewById<Button>(R.id.primaryButton).performClick())
            }
            grantWholeScreenProjection()
            await("CaptureService did not start after system consent", 15_000) {
                CaptureService.currentState == if (mode == RecognitionMode.POWER_SAVING) {
                    CaptureService.SessionState.STANDBY
                } else {
                    CaptureService.SessionState.RUNNING
                }
            }
            await("The floating overlay must be visible", 5_000) { CaptureService.isOverlayVisible }

            playFixture(playbackSource)
            playerStarted = true
            if (mode == RecognitionMode.POWER_SAVING) {
                assertEquals("Power saving must not acquire any frame before the icon is tapped", 0L, CaptureService.frameCount)
                lifecycle.put("initialStandby", observeStandby())
                playFixture(playbackSource)
                clickOverlayControl("startRecognitionIcon")
                await("The icon did not begin recognition", 15_000) {
                    CaptureService.currentState == CaptureService.SessionState.RUNNING
                }
            }
            val accepted = observePredictionAndFrozenInput(fileName, expected, trace, stopsAfterRound)
            report = liveReport(source, expected, trace, mode, stopsAfterRound, accepted = accepted).apply {
                put("recognitionLifecycle", lifecycle)
            }
            if (continuousRounds) {
                val nextTrace = LiveTrace()
                var nextAcceptance: AcceptedSession? = null
                playFixture(playbackSource)
                try {
                    nextAcceptance = observePredictionAndFrozenInput(
                        fileName, expected, nextTrace, stopsAfterRound = false,
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
                    lifecycle.put("continuousNextRound", liveReport(source, expected, nextTrace, mode, false, accepted = nextAcceptance).apply {
                        put("status", if (nextAcceptance == null) "failed" else "passed")
                    })
                }
            }
            if (checksPauseResume || stopsAfterRound) {
                if (!stopsAfterRound) {
                    clickOverlayControl("closeOverlay")
                    await("X did not stop recognition", 5_000) {
                        CaptureService.currentState == CaptureService.SessionState.STANDBY
                    }
                    // OCR already submitted to the platform may finish after its capture engine closes.
                    SystemClock.sleep(1_000)
                    lifecycle.put("afterClose", observeStandby())
                }
                if (!stopsAfterRound) playFixture(playbackSource)
                clickOverlayControl(if (stopsAfterRound) "restartRecognitionButton" else "startRecognitionIcon")
                await("The icon did not resume the existing screen-sharing session", 15_000) {
                    CaptureService.currentState == CaptureService.SessionState.RUNNING && CaptureService.latestSnapshot != null
                }
                val resumed = requireNotNull(CaptureService.latestSnapshot)
                assertTrue("Resume must create a fresh recognition session", resumed.captureSessionID != accepted.input.captureSessionID)
                assertTrue("Resume must not retain the previous confirmed prefix", resumed.outlook.observed.isEmpty())
                assertTrue("Resume must not retain confirmed Glyphs", resumed.slots.none { it.status == SlotObservation.Status.CONFIRMED })
                if (!stopsAfterRound) assertTrue("Resume must clear the previous frozen input", !resumed.isFrozen)
                lifecycle.put("resumedWithoutAnotherConsent", true)
                lifecycle.put("previousCaptureSessionID", accepted.input.captureSessionID.toString())
                lifecycle.put("resumedCaptureSessionID", resumed.captureSessionID.toString())
                if (stopsAfterRound) {
                    lifecycle.put("waitingForNextRound", observeRestartBeforeNextRound(resumed))
                    playFixture(playbackSource)
                }
                if (mode == RecognitionMode.POWER_SAVING || stopsAfterRound) {
                    val resumedTrace = LiveTrace()
                    var resumedAcceptance: AcceptedSession? = null
                    try {
                        resumedAcceptance = observePredictionAndFrozenInput(
                            fileName, expected, resumedTrace, stopsAfterRound,
                            ignoreStartupInput = stopsAfterRound,
                        )
                    } finally {
                        lifecycle.put("resumedRecognition", liveReport(source, expected, resumedTrace, mode, stopsAfterRound, accepted = resumedAcceptance).apply {
                            put("status", if (resumedAcceptance == null) "failed" else "passed")
                        })
                    }
                }
                if (stopsAfterRound) {
                    clickOverlayControl("closeOverlay")
                    await("X did not dismiss the retained result", 5_000) {
                        CaptureService.currentState == CaptureService.SessionState.STANDBY
                    }
                    lifecycle.put("afterResultClose", observeStandby())
                }
            }
            assertEquals("Playback must leave the source movie intact", sourceLength, source.length())
            assertEquals("Playback must not rewrite the source movie", sourceModified, source.lastModified())
            context.startService(Intent(context, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
            await("Full stop did not release screen sharing", 15_000) {
                CaptureService.currentState == CaptureService.SessionState.STOPPED && !CaptureService.isOverlayVisible
            }
            assertTrue("Full stop must leave recognition inactive", !CaptureService.isRecognizing)
            lifecycle.put("fullStopVerified", true)
            output.writeText(report.toString(2))
        } catch (failure: Throwable) {
            try {
                val failureReport = report ?: liveReport(source, expected, trace, mode, stopsAfterRound)
                failureReport.put("recognitionLifecycle", lifecycle)
                failureReport.put("status", "failed")
                failureReport.put("failureClass", failure.javaClass.name)
                failureReport.put("failureMessage", failure.message)
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

    private fun playFixture(source: File) {
        context.startActivity(
            Intent().setClassName(instrumentation.context.packageName, FixtureVideoActivity::class.java.name)
                .putExtra("video", source.absolutePath)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    private fun observeStandby(): JSONObject {
        await("The standby icon is missing", 5_000) { overlayControl("startRecognitionIcon") != null }
        assertEquals(CaptureService.SessionState.STANDBY, CaptureService.currentState)
        assertTrue("Standby must stop the recognition engine", !CaptureService.isRecognizing)
        assertEquals("Standby must clear the recognition snapshot", null, CaptureService.latestSnapshot)
        val frames = CaptureService.frameCount
        val before = PowerMeter.shared.snapshot()
        val started = SystemClock.elapsedRealtime()
        val cpuBefore = Process.getElapsedCpuTime()
        SystemClock.sleep(3_000)
        val cpuElapsed = Process.getElapsedCpuTime() - cpuBefore
        val elapsed = SystemClock.elapsedRealtime() - started
        val after = PowerMeter.shared.snapshot()
        assertEquals("Standby must not receive frames while the movie plays", frames, CaptureService.frameCount)
        assertEquals(CaptureService.SessionState.STANDBY, CaptureService.currentState)
        assertTrue("Standby must remain inactive", !CaptureService.isRecognizing)
        assertEquals("Late OCR must not republish a closed session", null, CaptureService.latestSnapshot)
        assertTrue("The resume icon must remain visible", CaptureService.isOverlayVisible && overlayControl("startRecognitionIcon") != null)
        for (metric in listOf(PowerMetric.FRAMES_ANALYSED, PowerMetric.HEADER_READ)) {
            assertEquals("Standby must not add $metric work", before.total(metric).count, after.total(metric).count)
        }
        return JSONObject().apply {
            put("frameCountBefore", frames)
            put("frameCountAfter", CaptureService.frameCount)
            put("analysedCountBefore", before.total(PowerMetric.FRAMES_ANALYSED).count)
            put("analysedCountAfter", after.total(PowerMetric.FRAMES_ANALYSED).count)
            put("headerReadCountBefore", before.total(PowerMetric.HEADER_READ).count)
            put("headerReadCountAfter", after.total(PowerMetric.HEADER_READ).count)
            put("measurementElapsedMillis", elapsed)
            put("processCpuMilliseconds", cpuElapsed)
            put("recognitionSnapshotCleared", true)
            put("standbyIconVisible", true)
        }
    }

    private fun observeRestartBeforeNextRound(resumed: GlyphDisplaySnapshot): JSONObject {
        val started = SystemClock.elapsedRealtime()
        val frames = CaptureService.frameCount
        while (SystemClock.elapsedRealtime() - started < 2_000) {
            assertEquals("Restart must wait for the next round while the previous input screen remains visible",
                CaptureService.SessionState.RUNNING, CaptureService.currentState)
            assertTrue("Restart must keep recognition active", CaptureService.isRecognizing)
            val snapshot = requireNotNull(CaptureService.latestSnapshot)
            assertEquals("Waiting must retain the new recognition session", resumed.captureSessionID, snapshot.captureSessionID)
            assertTrue("The old input screen must not restore the previous confirmed prefix", snapshot.outlook.observed.isEmpty())
            assertTrue("The old input screen must not restore confirmed Glyphs", snapshot.slots.none { it.status == SlotObservation.Status.CONFIRMED })
            SystemClock.sleep(100)
        }
        assertTrue("Restart must keep receiving frames before the next round", CaptureService.frameCount > frames)
        assertEquals("The previous input screen must not automatically stop a fresh session",
            CaptureService.SessionState.RUNNING, CaptureService.currentState)
        return JSONObject().apply {
            put("observedMillis", SystemClock.elapsedRealtime() - started)
            put("frameCountBefore", frames)
            put("frameCountAfter", CaptureService.frameCount)
            put("oldSequenceCleared", true)
            put("recognitionStillRunning", true)
        }
    }

    private fun clickOverlayControl(id: String) {
        await("Floating control $id is missing", 5_000) { overlayControl(id) != null }
        click(requireNotNull(overlayControl(id)))
    }

    private fun overlayControl(id: String): AccessibilityNodeInfo? = instrumentation.uiAutomation.windows
        .firstNotNullOfOrNull { window ->
            window.root?.let { root ->
                findNode(root) { it.viewIdResourceName == "${context.packageName}:id/$id" }
            }
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
        val retainedResult: JSONObject?,
        val measurements: JSONObject,
    )

    private fun observePredictionAndFrozenInput(
        fileName: String,
        expected: List<String>,
        trace: LiveTrace,
        stopsAfterRound: Boolean,
        ignoreStartupInput: Boolean = false,
        previousRound: GlyphDisplaySnapshot? = null,
    ): AcceptedSession {
        val measurements = RoundMeasurements()
        val predictions = mutableMapOf<Int, GlyphDisplaySnapshot>()
        var frozen: GlyphDisplaySnapshot? = null
        var frozenAt = 0L
        var frozenFrames = 0L
        var stoppedPowerBaseline: PowerSnapshot? = null
        var last: GlyphDisplaySnapshot? = null
        var nextRoundStarted = false
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + 40_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val snapshot = CaptureService.latestSnapshot
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
                    assertEquals(PredictionState.RESOLVED, outlook.state)
                    assertEquals(expected.drop(2), outlook.predictedSuffix.map { it.name })
                    assertEquals(expected.size, snapshot.expectedSlotCount)
                    assertTrue(snapshot.slots.take(2).all { it.status == SlotObservation.Status.CONFIRMED })
                    if (predictions.put(snapshot.hackID, snapshot) == null) {
                        trace.predictedHackIDs += snapshot.hackID
                        Log.i(TAG, "$fileName hack ${snapshot.hackID}: confirmed-prefix prediction verified")
                    }
                }
                if (frozen == null && snapshot.state == TrackingState.INPUT &&
                    isNextRound &&
                    !(ignoreStartupInput && snapshot.hackID == 0)
                ) {
                    if (previousRound != null) assertTrue("A new round must be observed presenting before its first input", nextRoundStarted)
                    assertTrue("First input must have predicted from its confirmed first two slots", snapshot.hackID in predictions)
                    assertEquals("First input must contain the complete recorded sequence", expected, snapshot.slots.map { it.glyph?.name })
                    assertTrue("Every slot must be confirmed at first input", snapshot.slots.all { it.status == SlotObservation.Status.CONFIRMED })
                    assertTrue("First input must freeze the expected sequence", snapshot.isFrozen)
                    frozen = snapshot
                    frozenAt = SystemClock.elapsedRealtime()
                    if (stopsAfterRound) {
                        await("The completed round did not stop recognition and retain its result", 5_000) {
                            CaptureService.currentState == CaptureService.SessionState.RESULT_READY
                        }
                        frozenFrames = CaptureService.frameCount
                        await("The retained result must offer Start recognition", 5_000) {
                            overlayControl("restartRecognitionButton") != null
                        }
                    }
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
                if (stopsAfterRound) {
                    assertEquals(CaptureService.SessionState.RESULT_READY, CaptureService.currentState)
                    assertTrue("The completed round must stop recognition", !CaptureService.isRecognizing)
                    assertEquals("Retaining the result must not acquire more frames", frozenFrames, CaptureService.frameCount)
                    assertEquals("Retaining the result must not publish new evidence", frozen, current)
                    if (stoppedPowerBaseline == null && retainedMillis >= 1_000) {
                        stoppedPowerBaseline = PowerMeter.shared.snapshot()
                    }
                }
                if (retainedMillis >= 3_000) {
                    val retainedResult = if (stopsAfterRound) {
                        val baseline = requireNotNull(stoppedPowerBaseline)
                        val stoppedPower = PowerMeter.shared.snapshot()
                        for (metric in listOf(PowerMetric.FRAMES_ANALYSED, PowerMetric.HEADER_READ)) {
                            assertEquals("A retained result must not add $metric work", baseline.total(metric).count, stoppedPower.total(metric).count)
                        }
                        assertTrue("The result panel must stay visible", CaptureService.isOverlayVisible && overlayControl("dragHandle") != null)
                        assertTrue("The result restart control must stay visible", overlayControl("restartRecognitionButton") != null)
                        assertEquals("The result panel must replace the standby icon", null, overlayControl("startRecognitionIcon"))
                        JSONObject().apply {
                            put("retainedMillis", retainedMillis)
                            put("frameCountBefore", frozenFrames)
                            put("frameCountAfter", CaptureService.frameCount)
                            put("sourceFrameTimeBefore", frozen.sourceFrameTime)
                            put("sourceFrameTimeAfter", current.sourceFrameTime)
                            put("analysedCountBefore", baseline.total(PowerMetric.FRAMES_ANALYSED).count)
                            put("analysedCountAfter", stoppedPower.total(PowerMetric.FRAMES_ANALYSED).count)
                            put("headerReadCountBefore", baseline.total(PowerMetric.HEADER_READ).count)
                            put("headerReadCountAfter", stoppedPower.total(PowerMetric.HEADER_READ).count)
                            put("resultPanelVisible", true)
                            put("restartControlVisible", true)
                        }
                    } else null
                    Log.i(TAG, "$fileName hack ${current.hackID}: full sequence retained for three seconds; automatic stop=$stopsAfterRound")
                    return AcceptedSession(
                        predictions.getValue(current.hackID), frozen, SystemClock.elapsedRealtime() - startedAt,
                        retainedResult, measurements.finish(),
                    )
                }
            }
            assertTrue("Live capture stopped unexpectedly: ${CaptureService.currentState}",
                CaptureService.currentState == CaptureService.SessionState.RUNNING ||
                    stopsAfterRound && CaptureService.currentState == CaptureService.SessionState.RESULT_READY)
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
    private class LiveTrace {
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
        mode: RecognitionMode,
        stopsAfterRound: Boolean,
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
            put("recognitionMode", mode.rawValue)
            put("stopsAfterRound", stopsAfterRound)
            put("captureState", CaptureService.currentState.name)
            put("powerSaving", mode == RecognitionMode.POWER_SAVING)
            put("frameCount", CaptureService.frameCount)
            put("viewportCalibration", snapshot?.calibrationSummary ?: JSONObject.NULL)
            put("sessionElapsedSeconds", power.elapsedSeconds)
            put("cpuSeconds", power.cpuSeconds ?: JSONObject.NULL)
            put("verifiedPredictionHackIDs", JSONArray(trace.predictedHackIDs))
            accepted?.let {
                put("confirmedSequence", JSONArray(it.input.slots.map { slot -> slot.glyph?.name }))
                put("observedPrefixAtPrediction", JSONArray(it.prediction.outlook.observed.map { glyph -> glyph.name }))
                put("predictedSuffix", JSONArray(it.prediction.outlook.predictedSuffix.map { glyph -> glyph.name }))
                put("inputFreeze", it.input.isFrozen)
                put("roundMeasurements", it.measurements)
                it.retainedResult?.let { retained -> put("retainedResult", retained) }
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
                // Declining optional notifications still continues MainActivity's capture flow.
                val notificationDenial = findNode(root) {
                    it.viewIdResourceName?.endsWith(":id/permission_deny_button") == true
                }
                if (notificationDenial != null) {
                    click(notificationDenial)
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
