package org.szres.glyphlens.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Predicate;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

// Uses only public UI and framework APIs, so the same test APK can exercise the optimized release.
@RunWith(AndroidJUnit4.class)
public final class ReleaseUiCaptureInstrumentedTest {
    private static final String PLAYER = "org.szres.glyphlens.test.FixtureVideoActivity";
    private static final String STOP_PLAYER = "org.szres.glyphlens.test.STOP_VIDEO";
    private static final String YOUR_TURN = "Your turn · draw the sequence";
    private final Instrumentation instrumentation;
    private final Context context;
    private final UiAutomation automation;

    public ReleaseUiCaptureInstrumentedTest() {
        this(InstrumentationRegistry.getInstrumentation());
    }

    ReleaseUiCaptureInstrumentedTest(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
        context = instrumentation.getTargetContext();
        automation = instrumentation.getUiAutomation();
    }

    @Test
    public void fiveGlyphReleaseJourneyProducesVisualEvidence() throws Throwable {
        verifyJourney("real-recording.mp4", new String[] {"question", "less", "forget", "all", "lie"}, false, false);
    }

    @Test
    public void threeGlyphReleaseJourneyProducesVisualEvidence() throws Throwable {
        verifyJourney("real-recording-093906.mp4", new String[] {"nourish", "xm", "portal"}, false, false);
    }

    @Test
    public void powerSavingReleaseJourneyStartsAndResumesFromIcon() throws Throwable {
        verifyJourney("real-recording.mp4", new String[] {"question", "less", "forget", "all", "lie"}, true, false);
    }

    @Test
    public void autoStopReleaseJourneyRetainsResultAndRestartsRecognition() throws Throwable {
        verifyJourney("real-recording.mp4", new String[] {"question", "less", "forget", "all", "lie"}, false, true);
    }

    private void verifyJourney(String fileName, String[] expectedSequence, boolean powerSaving,
            boolean autoStop) throws Throwable {
        File source = new File(context.getExternalFilesDir(null), fileName);
        File output = new File(context.getExternalFilesDir(null), "live-release-tests/"
                + fileName.replace(".mp4", "") + (powerSaving ? "-power-saving" : "-realtime")
                + (autoStop ? "-auto-stop" : "")
                + "-" + System.currentTimeMillis());
        assertTrue("Cannot create visual evidence directory", output.mkdirs());
        JSONObject report = new JSONObject();
        report.put("sourceFile", source.getAbsolutePath());
        report.put("expectedSequenceForManualReview", new JSONArray(Arrays.asList(expectedSequence)));
        report.put("glyphVerification", "Manual review of screenshots required; no per-Glyph assertion");
        report.put("performanceMeasurement", "Not measured: periodic screenshots affect CPU usage");
        report.put("recognitionMode", powerSaving ? "powerSaving" : "realtime");
        report.put("stopsAfterRound", autoStop);
        report.put("playerProcess", instrumentation.getContext().getPackageName() + ":fixture_video");
        AccessibilityServiceInfo serviceInfo = automation.getServiceInfo();
        int previousFlags = serviceInfo.flags;
        serviceInfo.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        automation.setServiceInfo(serviceInfo);
        boolean requestedCapture = false;
        boolean playerStarted = false;
        Boolean previousFloatingWindow = null;
        Boolean previousPowerSaving = null;
        Boolean previousAutoStop = null;
        try {
            File playbackSource = fixtureForPlayer(source);
            assertTrue("Grant display-over-other-apps before this test", Settings.canDrawOverlays(context));
            launchHome();
            AccessibilityNodeInfo primary = awaitNode("primaryButton", 15_000);
            assertEquals("Capture must be stopped before the UI journey", appString("start_capture"), text(primary));
            previousPowerSaving = awaitNode("powerSavingModeButton", 5_000).isChecked();
            previousAutoStop = awaitNode("autoStopSwitch", 5_000).isChecked();
            // Both optional modes lock the floating switch on; unlock it before reading or restoring it.
            selectAutoStop(false);
            selectMode(false);
            AccessibilityNodeInfo floatingSwitch = awaitNode("floatingWindowSwitch", 5_000);
            previousFloatingWindow = floatingSwitch.isChecked();
            if (!floatingSwitch.isChecked()) click(floatingSwitch);
            selectMode(powerSaving);
            selectAutoStop(autoStop);
            saveScreenshot(new File(output, "home-mode.png"));
            report.put("homeModeScreenshot", "home-mode.png");
            requestedCapture = true;
            click(awaitNode("primaryButton", 5_000));
            grantWholeScreenProjection();
            report.put("systemConsentApprovals", 1);
            awaitButtonText(appString(powerSaving ? "stop_sharing" : "stop_capture"), 15_000);
            playFixture(playbackSource);
            playerStarted = true;
            if (powerSaving) {
                JSONObject standby = new JSONObject();
                report.put("initialStandby", standby);
                observeStandby(output, "initial-standby", standby);
                click(awaitNode("startRecognitionIcon", 5_000));
                awaitNode("dragHandle", 10_000);
                playFixture(playbackSource);
            }
            observePhases(output, report, autoStop);
            if (powerSaving) {
                click(awaitNode("closeOverlay", 5_000));
                JSONObject standby = new JSONObject();
                report.put("afterClose", standby);
                observeStandby(output, "after-close", standby);
                click(awaitNode("startRecognitionIcon", 5_000));
                awaitNode("dragHandle", 10_000);
                assertNoProjectionConsent();
                playFixture(playbackSource);
                File resumedOutput = new File(output, "resumed");
                assertTrue("Cannot create resumed visual evidence directory", resumedOutput.mkdirs());
                JSONObject resumed = new JSONObject();
                resumed.put("screenshotDirectory", "resumed");
                resumed.put("glyphVerification", "Manual review of screenshots required; no per-Glyph assertion");
                report.put("resumedJourney", resumed);
                observePhases(resumedOutput, resumed, false);
                report.put("resumedWithoutAnotherConsent", true);
            }
            if (autoStop) {
                click(awaitNode("restartRecognitionButton", 5_000));
                awaitRecognitionPanel();
                JSONObject restartBeforeReplay = new JSONObject();
                report.put("restartBeforeReplay", restartBeforeReplay);
                observeRestartBeforeReplay(output, restartBeforeReplay);
                playFixture(playbackSource);
                File restartedOutput = new File(output, "restarted");
                assertTrue("Cannot create restarted visual evidence directory", restartedOutput.mkdirs());
                JSONObject restarted = new JSONObject();
                restarted.put("screenshotDirectory", "restarted");
                restarted.put("glyphVerification", "Manual review of screenshots required; no per-Glyph assertion");
                report.put("restartedJourney", restarted);
                observePhases(restartedOutput, restarted, true);
                report.put("restartedWithoutAnotherConsent", true);
                click(awaitNode("closeOverlay", 5_000));
                JSONObject standby = new JSONObject();
                report.put("afterResultClose", standby);
                observeStandby(output, "after-result-close", standby);
            }
            stopSharing();
            assertTrue("Full stop must remove both floating controls",
                    findAppNode("dragHandle") == null && findAppNode("startRecognitionIcon") == null);
            report.put("fullStopVerified", true);
            report.put("status", "ui_phase_checks_passed_manual_glyph_review_required");
        } catch (Throwable failure) {
            report.put("status", "failed");
            report.put("failureClass", failure.getClass().getName());
            report.put("failureMessage", failure.getMessage());
            try {
                saveScreenshot(new File(output, "failure.png"));
            } catch (Throwable screenshotFailure) {
                failure.addSuppressed(screenshotFailure);
            }
            throw failure;
        } finally {
            try {
                writeReport(output, report);
            } finally {
                try {
                    if (playerStarted) {
                        context.startActivity(new Intent().setClassName(instrumentation.getContext().getPackageName(), PLAYER)
                                .setAction(STOP_PLAYER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                        | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                    }
                    if (requestedCapture) {
                        stopSharing();
                    }
                    if (previousPowerSaving != null) {
                        launchHome();
                        if (previousAutoStop != null) selectAutoStop(false);
                        selectMode(false);
                    }
                    if (previousFloatingWindow != null) {
                        AccessibilityNodeInfo floatingSwitch = awaitNode("floatingWindowSwitch", 5_000);
                        if (floatingSwitch.isChecked() != previousFloatingWindow) click(floatingSwitch);
                    }
                    if (previousAutoStop != null) selectAutoStop(previousAutoStop);
                    if (previousPowerSaving != null) selectMode(previousPowerSaving);
                } finally {
                    serviceInfo.flags = previousFlags;
                    automation.setServiceInfo(serviceInfo);
                }
            }
        }
    }

    private void playFixture(File source) {
        context.startActivity(new Intent().setClassName(instrumentation.getContext().getPackageName(), PLAYER)
                .putExtra("video", source.getAbsolutePath()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    private void selectMode(boolean powerSaving) {
        String id = powerSaving ? "powerSavingModeButton" : "realtimeModeButton";
        AccessibilityNodeInfo mode = awaitNode(id, 5_000);
        if (!mode.isChecked()) click(mode);
        assertTrue("The selected recognition mode must be checked", awaitNode(id, 5_000).isChecked());
    }

    private void selectAutoStop(boolean autoStop) {
        AccessibilityNodeInfo control = awaitNode("autoStopSwitch", 5_000);
        if (control.isChecked() != autoStop) click(control);
        assertEquals("Automatic stopping must match the selected setting", autoStop,
                awaitNode("autoStopSwitch", 5_000).isChecked());
    }

    private void awaitRecognitionPanel() {
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            assertNoProjectionConsent();
            if (findAppNode("dragHandle") != null && findAppNode("restartRecognitionButton") == null
                    && findAppNode("startRecognitionIcon") == null) return;
            SystemClock.sleep(100);
        }
        throw new AssertionError("Restart did not replace the retained result with active recognition");
    }

    private void observeRestartBeforeReplay(File output, JSONObject report) throws Exception {
        long started = SystemClock.elapsedRealtime();
        while (SystemClock.elapsedRealtime() - started < 2_000) {
            assertNoProjectionConsent();
            AccessibilityNodeInfo status = findAppNode("dragHandle");
            assertTrue("Restart must keep recognizing the old input until a new round starts",
                    status != null && !appString("overlay_result_ready").equals(text(status)));
            assertTrue("The old input must not immediately recreate the previous result",
                    findAppNode("restartRecognitionButton") == null && findAppNode("startRecognitionIcon") == null);
            SystemClock.sleep(100);
        }
        saveScreenshot(new File(output, "restart-before-replay.png"));
        report.put("observedMillis", SystemClock.elapsedRealtime() - started);
        report.put("oldInputDidNotRestoreResult", true);
        report.put("screenshot", "restart-before-replay.png");
    }

    private void stopSharing() {
        launchHome();
        AccessibilityNodeInfo primary = awaitNode("primaryButton", 10_000);
        if (!appString("start_capture").equals(text(primary))) click(primary);
        awaitButtonText(appString("start_capture"), 15_000);
    }

    private void observeStandby(File output, String name, JSONObject report) throws Exception {
        awaitNode("startRecognitionIcon", 5_000);
        long started = SystemClock.elapsedRealtime();
        while (SystemClock.elapsedRealtime() - started < 3_000) {
            assertNoProjectionConsent();
            AccessibilityNodeInfo icon = findAppNode("startRecognitionIcon");
            assertTrue("The standby icon must remain visible while the movie plays", icon != null && icon.isVisibleToUser());
            assertTrue("Standby must not display the recognition panel",
                    findAppNode("dragHandle") == null && findAppNode("closeOverlay") == null
                            && findAppNode("restartRecognitionButton") == null);
            SystemClock.sleep(100);
        }
        assertNotNull("The standby icon must remain available", findAppNode("startRecognitionIcon"));
        assertTrue("Standby must keep the recognition panel closed",
                findAppNode("dragHandle") == null && findAppNode("closeOverlay") == null
                        && findAppNode("restartRecognitionButton") == null);
        saveScreenshot(new File(output, name + ".png"));
        report.put("observedMillis", SystemClock.elapsedRealtime() - started);
        report.put("standbyIconVisible", true);
        report.put("recognitionPanelAbsent", true);
        report.put("screenshot", name + ".png");
    }

    private File fixtureForPlayer(File source) throws Exception {
        assertTrue("Original fixture missing: " + source, source.isFile());
        File appDataDirectory = source.getParentFile().getParentFile().getParentFile();
        File playbackSource = new File(appDataDirectory,
                instrumentation.getContext().getPackageName() + "/files/" + source.getName());
        // executeShellCommand splits arguments itself; these fixed fixture paths contain no spaces.
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
                        "stat -c %s " + playbackSource.getAbsolutePath())), StandardCharsets.UTF_8))) {
            assertEquals("Preinstall the test APK's fixture: " + playbackSource,
                    Long.toString(source.length()), reader.readLine());
        }
        return playbackSource;
    }

    private void observePhases(File output, JSONObject report, boolean autoStop) throws Exception {
        JSONArray stages = new JSONArray();
        JSONArray screenshots = new JSONArray();
        report.put("stages", stages);
        report.put("screenshots", screenshots);
        long startedAt = SystemClock.elapsedRealtime();
        long nextScreenshotAt = startedAt;
        long firstTerminalAt = -1;
        String previousStatus = null;
        boolean sawPresenting = false;
        String terminalStatus = autoStop ? appString("overlay_result_ready") : YOUR_TURN;
        report.put("terminalPhase", autoStop ? "retainedResult" : "liveInput");
        while (SystemClock.elapsedRealtime() - startedAt < 40_000) {
            assertNoProjectionConsent();
            long now = SystemClock.elapsedRealtime();
            AccessibilityNodeInfo statusNode = findAppNode("dragHandle");
            String status = statusNode == null ? null : text(statusNode);
            if (status != null && !status.equals(previousStatus)) {
                stages.put(new JSONObject().put("elapsedMillis", now - startedAt).put("text", status));
                previousStatus = status;
            }
            if (status != null && status.startsWith("Watching the sequence")) sawPresenting = true;
            if (terminalStatus.equals(status) && firstTerminalAt < 0) {
                assertTrue("The first result or input must follow a visible sequence phase", sawPresenting);
                firstTerminalAt = now;
                report.put(autoStop ? "firstResultElapsedMillis" : "firstInputElapsedMillis", now - startedAt);
            }
            if (firstTerminalAt >= 0) {
                assertEquals("The result or input must remain visible for three seconds", terminalStatus, status);
                if (autoStop) {
                    AccessibilityNodeInfo restart = findAppNode("restartRecognitionButton");
                    assertTrue("A retained result must keep its restart button visible",
                            restart != null && restart.isVisibleToUser() && restart.isEnabled());
                    assertEquals(appString("overlay_start_recognition"), text(restart));
                    assertTrue("A retained result must remain open instead of returning to the icon",
                            findAppNode("startRecognitionIcon") == null && findAppNode("closeOverlay") != null);
                }
            }
            if (now >= nextScreenshotAt) {
                String name = String.format(java.util.Locale.ROOT, "frame-%05d.png", screenshots.length());
                saveScreenshot(new File(output, name));
                screenshots.put(new JSONObject().put("file", name).put("elapsedMillis", now - startedAt)
                        .put("overlayStatus", status == null ? JSONObject.NULL : status));
                nextScreenshotAt = now + 500;
            }
            if (firstTerminalAt >= 0 && now - firstTerminalAt >= 3_000) {
                String terminalScreenshot = autoStop ? "result-after-three-seconds.png" : "input-after-three-seconds.png";
                saveScreenshot(new File(output, terminalScreenshot));
                report.put("terminalScreenshot", terminalScreenshot);
                report.put(autoStop ? "resultObservedMillis" : "inputPhaseObservedMillis", now - firstTerminalAt);
                if (autoStop) report.put("resultRestartControlVisible", true);
                report.put("elapsedMillis", now - startedAt);
                return;
            }
            SystemClock.sleep(100);
        }
        throw new AssertionError("No complete presentation-to-" + (autoStop ? "result" : "input")
                + " UI journey within 40 seconds; last=" + previousStatus);
    }

    private void launchHome() {
        context.startActivity(new Intent().setClassName(context.getPackageName(), "org.szres.glyphlens.ui.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    private String appString(String name) {
        int resource = context.getResources().getIdentifier(name, "string", context.getPackageName());
        assertTrue("Missing public UI string: " + name, resource != 0);
        return context.getString(resource);
    }

    private void awaitButtonText(String expected, long timeoutMillis) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMillis;
        while (SystemClock.elapsedRealtime() < deadline) {
            AccessibilityNodeInfo button = findAppNode("primaryButton");
            if (button != null && expected.equals(text(button))) return;
            SystemClock.sleep(100);
        }
        throw new AssertionError("Primary button did not become: " + expected);
    }

    private AccessibilityNodeInfo awaitNode(String id, long timeoutMillis) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMillis;
        while (SystemClock.elapsedRealtime() < deadline) {
            AccessibilityNodeInfo node = findAppNode(id);
            if (node != null && node.isEnabled()) return node;
            SystemClock.sleep(100);
        }
        throw new AssertionError("UI control not available: " + id);
    }

    private AccessibilityNodeInfo findAppNode(String id) {
        return findInWindows(node -> (context.getPackageName() + ":id/" + id).equals(node.getViewIdResourceName()));
    }

    private void assertNoProjectionConsent() {
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        if (root == null || !"com.android.systemui".equals(String.valueOf(root.getPackageName()))) return;
        AccessibilityNodeInfo consentControl = findNode(root, node ->
                "com.android.systemui:id/screen_share_mode_options".equals(node.getViewIdResourceName())
                        || "android:id/button1".equals(node.getViewIdResourceName()));
        assertTrue("Recognition must continue on the original screen-sharing consent", consentControl == null);
    }

    private void grantWholeScreenProjection() {
        boolean selectedWholeScreen = false;
        boolean openedSelector = false;
        long deadline = SystemClock.elapsedRealtime() + 15_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            if (root != null) {
                AccessibilityNodeInfo denial = findNode(root, node -> node.getViewIdResourceName() != null
                        && node.getViewIdResourceName().endsWith(":id/permission_deny_button"));
                if (denial != null) {
                    click(denial);
                } else {
                    AccessibilityNodeInfo wholeScreen = findNode(root, node -> Arrays.asList(
                            "共享整个屏幕", "整个屏幕", "Entire screen", "Share entire screen").contains(text(node)));
                    AccessibilityNodeInfo selector = findNode(root, node ->
                            "com.android.systemui:id/screen_share_mode_options".equals(node.getViewIdResourceName()));
                    if (wholeScreen != null && openedSelector && !selectedWholeScreen) {
                        click(wholeScreen);
                        selectedWholeScreen = true;
                    } else if (selector != null && !openedSelector && !selectedWholeScreen) {
                        click(selector);
                        openedSelector = true;
                    } else if (selectedWholeScreen || selector == null && !openedSelector) {
                        AccessibilityNodeInfo positive = findNode(root, node ->
                                "android:id/button1".equals(node.getViewIdResourceName()));
                        if (positive != null && "com.android.systemui".equals(String.valueOf(root.getPackageName()))) {
                            click(positive);
                            return;
                        }
                    }
                }
            }
            SystemClock.sleep(100);
        }
        throw new AssertionError("Whole-screen MediaProjection consent was not available");
    }

    private AccessibilityNodeInfo findInWindows(Predicate<AccessibilityNodeInfo> matches) {
        for (AccessibilityWindowInfo window : automation.getWindows()) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null) continue;
            AccessibilityNodeInfo match = findNode(root, matches);
            if (match != null) return match;
        }
        return null;
    }

    private AccessibilityNodeInfo findNode(AccessibilityNodeInfo node, Predicate<AccessibilityNodeInfo> matches) {
        if (matches.test(node)) return node;
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child == null) continue;
            AccessibilityNodeInfo match = findNode(child, matches);
            if (match != null) return match;
        }
        return null;
    }

    private void click(AccessibilityNodeInfo node) {
        for (AccessibilityNodeInfo current = node; current != null; current = current.getParent()) {
            if (current.isClickable() && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
        }
        throw new AssertionError("UI control cannot be clicked: " + node.getViewIdResourceName());
    }

    private String text(AccessibilityNodeInfo node) {
        return node.getText() == null ? "" : node.getText().toString();
    }

    private void saveScreenshot(File destination) throws Exception {
        Bitmap screenshot = automation.takeScreenshot();
        assertNotNull("Screenshot unavailable: " + destination, screenshot);
        try (FileOutputStream stream = new FileOutputStream(destination)) {
            assertTrue("Cannot encode screenshot", screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } finally {
            screenshot.recycle();
        }
    }

    private void writeReport(File output, JSONObject report) throws Exception {
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(new File(output, "report.json")), StandardCharsets.UTF_8)) {
            writer.write(report.toString(2));
        }
    }
}
