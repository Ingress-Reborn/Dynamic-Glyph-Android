package org.szres.glyphlens.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
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
    public void openingAppStartsFiveGlyphRecognitionWithDefaultMapHiding() throws Throwable {
        verifyJourney("real-recording.mp4", new String[] {"question", "less", "forget", "all", "lie"}, true, false, false);
    }

    @Test
    public void threeGlyphReleaseJourneyProducesVisualEvidence() throws Throwable {
        verifyJourney("real-recording-093906.mp4", new String[] {"nourish", "xm", "portal"}, false, false, false);
    }

    @Test
    public void hiddenReleaseJourneyRevealsPredictionsAndRestoresFromNotification() throws Throwable {
        verifyJourney("real-recording.mp4", new String[] {"question", "less", "forget", "all", "lie"}, false, true, false);
    }

    @Test
    public void mapIconsWorkWithoutTitles() throws Throwable {
        verifyJourney("real-recording.mp4", new String[] {"question", "less", "forget", "all", "lie"}, true, false, true);
    }

    @Test
    public void deviceCalibrationSurvivesCaptureRestartAndCanBeReset() throws Throwable {
        String calibrationKey = "recognition.deviceCalibration";
        File source = new File(context.getExternalFilesDir(null), "device-recording-184123.mp4");
        File output = new File(context.getExternalFilesDir(null),
                "live-release-tests/device-calibration-" + System.currentTimeMillis());
        assertTrue("Cannot create calibration evidence directory", output.mkdirs());
        JSONObject report = new JSONObject()
                .put("sourceFile", source.getAbsolutePath())
                .put("expectedGlyphForManualReview", "idea")
                .put("glyphVerification", "Manual review of cold, restored and recalibrated input screenshots required");
        SharedPreferences preferences = context.getSharedPreferences("glyph-lens", Context.MODE_PRIVATE);
        String previousCalibration = preferences.getString(calibrationKey, null);
        Boolean previousFloatingWindow = preferences.contains("display.floatingWindow")
                ? preferences.getBoolean("display.floatingWindow", true) : null;
        Boolean previousAutoHide = preferences.contains("display.hidesAfterDrawing")
                ? preferences.getBoolean("display.hidesAfterDrawing", true) : null;
        AccessibilityServiceInfo serviceInfo = automation.getServiceInfo();
        int previousFlags = serviceInfo.flags;
        serviceInfo.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        automation.setServiceInfo(serviceInfo);
        boolean requestedCapture = false;
        boolean playerStarted = false;
        try {
            File playbackSource = fixtureForPlayer(source);
            assertTrue("Grant display-over-other-apps before this test", Settings.canDrawOverlays(context));
            assertTrue("Cannot start with an uncalibrated profile", preferences.edit().remove(calibrationKey)
                    .putBoolean("display.floatingWindow", true)
                    .putBoolean("display.hidesAfterDrawing", false).commit());
            String savedCalibration = null;
            for (String run : new String[] {"cold", "restored"}) {
                requestedCapture = true;
                context.startActivity(new Intent().setClassName(context.getPackageName(), "org.szres.glyphlens.ui.MainActivity")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                grantWholeScreenProjection();
                awaitButtonText(appString("stop_capture"), 15_000);
                if (savedCalibration != null) {
                    assertEquals("Capture restart must preserve the device calibration", savedCalibration,
                            preferences.getString(calibrationKey, null));
                    report.put("profileSurvivesCaptureRestart", true);
                }
                playerStarted = true;
                playFixture(playbackSource, false);
                observeDeviceGlyph(output, run, report);
                savedCalibration = preferences.getString(calibrationKey, null);
                assertNotNull("The blank grid must save a device calibration", savedCalibration);
                report.put(run + "Calibration", savedCalibration);
                stopFixture();
                playerStarted = false;
                if ("cold".equals(run)) {
                    stopSharing();
                    requestedCapture = false;
                }
            }
            launchHome();
            awaitButtonText(appString("stop_capture"), 5_000);
            AccessibilityNodeInfo toggle = awaitNode("overlayButton", 5_000);
            if (appString("hide_overlay").equals(text(toggle))) click(toggle);
            click(awaitNode("settingsButton", 5_000));
            awaitCalibrationStatus("settings_calibration_saved");
            saveScreenshot(new File(output, "settings-saved.png"));
            click(awaitNode("settingsResetCalibrationButton", 5_000));
            awaitCalibrationStatus("settings_calibration_pending");
            assertTrue("Reset must remove the saved device profile", !preferences.contains(calibrationKey));
            assertTrue("Reset must leave floating-window settings unchanged",
                    preferences.getBoolean("display.floatingWindow", false)
                            && !preferences.getBoolean("display.hidesAfterDrawing", true));
            awaitNoFloatingSurface();
            saveScreenshot(new File(output, "settings-reset.png"));
            report.put("liveResetClearsCalibration", true);
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
            awaitButtonText(appString("stop_capture"), 5_000);
            assertNoProjectionConsent();
            playerStarted = true;
            playFixture(playbackSource, false);
            observeDeviceGlyph(output, "recalibrated", report);
            assertNotNull("The next blank grid must calibrate again after reset",
                    preferences.getString(calibrationKey, null));
            report.put("recalibratesWithoutRestartOrConsent", true);
            stopFixture();
            playerStarted = false;
            stopSharing();
            requestedCapture = false;
            report.put("systemConsentApprovals", 2);
            report.put("status", "ui_checks_passed_manual_screenshot_review_required");
        } catch (Throwable failure) {
            report.put("status", "failed").put("failureMessage", failure.getMessage());
            saveScreenshot(new File(output, "failure.png"));
            throw failure;
        } finally {
            try {
                if (playerStarted) stopFixture();
                if (requestedCapture) stopSharing();
                SharedPreferences.Editor restore = preferences.edit();
                if (previousCalibration == null) restore.remove(calibrationKey);
                else restore.putString(calibrationKey, previousCalibration);
                if (previousFloatingWindow == null) restore.remove("display.floatingWindow");
                else restore.putBoolean("display.floatingWindow", previousFloatingWindow);
                if (previousAutoHide == null) restore.remove("display.hidesAfterDrawing");
                else restore.putBoolean("display.hidesAfterDrawing", previousAutoHide);
                assertTrue("Cannot restore preferences after calibration journey", restore.commit());
            } finally {
                serviceInfo.flags = previousFlags;
                automation.setServiceInfo(serviceInfo);
                writeReport(output, report);
            }
        }
    }

    private void observeDeviceGlyph(File output, String run, JSONObject report) throws Exception {
        long started = SystemClock.elapsedRealtime();
        while (SystemClock.elapsedRealtime() - started < 10_000) {
            assertNoProjectionConsent();
            AccessibilityNodeInfo status = findAppNode("dragHandle");
            if (status != null && YOUR_TURN.equals(text(status))) {
                awaitNode("glyphDiagram", 1_000);
                String screenshot = run + "-input.png";
                saveScreenshot(new File(output, screenshot));
                report.put(run + "InputElapsedMillis", SystemClock.elapsedRealtime() - started);
                report.put(run + "InputScreenshot", screenshot);
                return;
            }
            SystemClock.sleep(50);
        }
        throw new AssertionError("The device recording did not reach recognized input in its first playback: " + run);
    }

    private void awaitCalibrationStatus(String stringName) {
        String expected = appString(stringName);
        long deadline = SystemClock.elapsedRealtime() + 5_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            AccessibilityNodeInfo status = findAppNode("settingsCalibrationStatus");
            if (status != null && expected.equals(text(status))) return;
            SystemClock.sleep(50);
        }
        throw new AssertionError("Device calibration status did not become: " + expected);
    }

    private void stopFixture() {
        context.startActivity(new Intent().setClassName(instrumentation.getContext().getPackageName(), PLAYER)
                .setAction(STOP_PLAYER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    private void verifyJourney(String fileName, String[] expectedSequence,
            boolean autoHide, boolean hiddenJourney, boolean hideHeaderText) throws Throwable {
        File source = new File(context.getExternalFilesDir(null), fileName);
        File output = new File(context.getExternalFilesDir(null), "live-release-tests/"
                + fileName.replace(".mp4", "") + "-auto-start"
                + (autoHide ? "-auto-hide" : "")
                + (hiddenJourney ? "-hidden" : "")
                + (hideHeaderText ? "-masked-title" : "")
                + "-" + System.currentTimeMillis());
        assertTrue("Cannot create visual evidence directory", output.mkdirs());
        JSONObject report = new JSONObject();
        report.put("sourceFile", source.getAbsolutePath());
        report.put("expectedSequenceForManualReview", new JSONArray(Arrays.asList(expectedSequence)));
        report.put("glyphVerification", "Manual review of screenshots required; no per-Glyph assertion");
        report.put("performanceMeasurement", "Not measured: periodic screenshots affect CPU usage");
        report.put("hidesAfterDrawing", autoHide);
        report.put("playerProcess", instrumentation.getContext().getPackageName() + ":fixture_video");
        report.put("headerTextMasked", hideHeaderText);
        SharedPreferences preferences = context.getSharedPreferences("glyph-lens", Context.MODE_PRIVATE);
        Boolean previousFloatingWindow = preferences.contains("display.floatingWindow")
                ? preferences.getBoolean("display.floatingWindow", true) : null;
        Boolean previousAutoHide = preferences.contains("display.hidesAfterDrawing")
                ? preferences.getBoolean("display.hidesAfterDrawing", true) : null;
        AccessibilityServiceInfo serviceInfo = automation.getServiceInfo();
        int previousFlags = serviceInfo.flags;
        serviceInfo.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        automation.setServiceInfo(serviceInfo);
        boolean requestedCapture = false;
        boolean playerStarted = false;
        try {
            File playbackSource = fixtureForPlayer(source);
            assertTrue("Grant display-over-other-apps before this test", Settings.canDrawOverlays(context));
            assertTrue("Cannot restore default overlay settings for this journey", preferences.edit()
                    .remove("display.floatingWindow").remove("display.hidesAfterDrawing").commit());
            requestedCapture = true;
            context.startActivity(new Intent().setClassName(context.getPackageName(), "org.szres.glyphlens.ui.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            grantWholeScreenProjection();
            report.put("systemConsentApprovals", 1);
            report.put("startedWithoutStartButton", true);
            awaitButtonText(appString("stop_capture"), 15_000);
            assertTrue("The home page must not contain recognition mode or overlay settings",
                    findAppNode("recognitionModeGroup") == null && findAppNode("floatingWindowSwitch") == null
                            && findAppNode("autoHideSwitch") == null);
            click(awaitNode("settingsButton", 5_000));
            assertTrue("Floating window must default to enabled", awaitNode("floatingWindowSwitch", 5_000).isChecked());
            assertTrue("Map hiding must default to enabled", awaitNode("autoHideSwitch", 5_000).isChecked());
            report.put("defaultFloatingWindowEnabled", true);
            report.put("defaultMapHidingEnabled", true);
            saveScreenshot(new File(output, "settings-defaults.png"));
            click(awaitNode("floatingWindowSwitch", 5_000));
            awaitCheckedState("floatingWindowSwitch", false);
            assertTrue("Disabling the floating window must preserve map hiding",
                    awaitNode("autoHideSwitch", 5_000).isChecked());
            selectAutoHide(false);
            assertTrue("Disabling map hiding must not enable the floating window",
                    !awaitNode("floatingWindowSwitch", 5_000).isChecked());
            selectAutoHide(true);
            assertTrue("Enabling map hiding must not enable the floating window",
                    !awaitNode("floatingWindowSwitch", 5_000).isChecked());
            awaitNoFloatingSurface();
            report.put("overlaySettingsIndependent", true);
            click(awaitNode("floatingWindowSwitch", 5_000));
            awaitCheckedState("floatingWindowSwitch", true);
            awaitNode("glyphDiagram", 5_000);
            report.put("floatingSettingAppliesDuringCapture", true);
            if (!autoHide) selectAutoHide(false);
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
            awaitButtonText(appString("stop_capture"), 5_000);
            assertNoProjectionConsent();
            AccessibilityNodeInfo overlayToggle = awaitNode("overlayButton", 5_000);
            if (appString("hide_overlay").equals(text(overlayToggle))) click(overlayToggle);
            saveScreenshot(new File(output, "home-auto-start.png"));
            report.put("homeScreenshot", "home-auto-start.png");
            JSONObject hidden = new JSONObject();
            report.put("beforePlaybackHidden", hidden);
            observeHidden(output, "before-playback-hidden", hidden);
            playerStarted = true;
            playFixture(playbackSource, hideHeaderText);
            if (autoHide) observeMapHideCycle(output, report, fileName);
            else observePhases(output, report, true);
            if (autoHide) {
                assertNoProjectionConsent();
                playFixture(playbackSource, hideHeaderText);
                File nextOutput = new File(output, "next-round");
                assertTrue("Cannot create next-round visual evidence directory", nextOutput.mkdirs());
                JSONObject next = new JSONObject();
                next.put("screenshotDirectory", "next-round");
                next.put("glyphVerification", "Manual review of screenshots required; no per-Glyph assertion");
                report.put("nextRound", next);
                observeMapHideCycle(nextOutput, next, fileName);
                report.put("nextRoundShownAndHiddenWithoutRestartOrConsent", true);
                showFromNotification(output, "map-notification", report);
                JSONObject mapHidden = new JSONObject();
                report.put("mapStillHidesAfterNotification", mapHidden);
                observeMapHideAfterNotification(output, "map-after-notification", mapHidden);
            }
            if (hiddenJourney) {
                click(awaitNode("closeOverlay", 5_000));
                JSONObject dismissed = new JSONObject();
                report.put("firstRoundDismissed", dismissed);
                observeHidden(output, "first-round-dismissed", dismissed);
                playFixture(playbackSource, hideHeaderText);
                File nextOutput = new File(output, "next-round");
                assertTrue("Cannot create next-round evidence directory", nextOutput.mkdirs());
                JSONObject next = new JSONObject();
                next.put("screenshotDirectory", "next-round");
                next.put("glyphVerification", "Manual review of screenshots required; no per-Glyph assertion");
                report.put("nextRound", next);
                observePhases(nextOutput, next, true);
                report.put("nextRoundRevealedWithoutRestartOrConsent", true);
                click(awaitNode("closeOverlay", 5_000));
                JSONObject nextHidden = new JSONObject();
                report.put("secondRoundDismissed", nextHidden);
                observeHidden(output, "second-round-dismissed", nextHidden);
                showFromNotification(output, "live-notification", report);
                awaitRecognitionPanel();
                saveScreenshot(new File(output, "restored-live-result.png"));
            }
            stopSharing();
            requestedCapture = false;
            assertNoProjectionConsent();
            assertTrue("Full stop must remove the floating controls",
                    findAppNode("glyphDiagram") == null && findAppNode("closeOverlay") == null);
            report.put("fullStopVerified", true);
            report.put("status", "ui_checks_passed_manual_screenshot_review_required");
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
                    if (requestedCapture) stopSharing();
                    SharedPreferences.Editor restore = preferences.edit();
                    if (previousFloatingWindow == null) restore.remove("display.floatingWindow");
                    else restore.putBoolean("display.floatingWindow", previousFloatingWindow);
                    if (previousAutoHide == null) restore.remove("display.hidesAfterDrawing");
                    else restore.putBoolean("display.hidesAfterDrawing", previousAutoHide);
                    assertTrue("Cannot restore previous overlay settings", restore.commit());
                } finally {
                    serviceInfo.flags = previousFlags;
                    automation.setServiceInfo(serviceInfo);
                }
            }
        }
    }

    private void playFixture(File source, boolean hideHeaderText) {
        context.startActivity(new Intent().setClassName(instrumentation.getContext().getPackageName(), PLAYER)
                .putExtra("video", source.getAbsolutePath()).putExtra("hideHeaderText", hideHeaderText)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            for (AccessibilityWindowInfo window : automation.getWindows()) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root != null && root.isVisibleToUser()
                        && instrumentation.getContext().getPackageName().equals(String.valueOf(root.getPackageName()))) return;
            }
            SystemClock.sleep(50);
        }
        throw new AssertionError("The fixture video window did not become visible: " + source.getName());
    }

    private void selectAutoHide(boolean autoHide) {
        AccessibilityNodeInfo control = awaitNode("autoHideSwitch", 5_000);
        if (control.isChecked() != autoHide) click(control);
        awaitCheckedState("autoHideSwitch", autoHide);
    }

    private void awaitCheckedState(String id, boolean expected) {
        long deadline = SystemClock.elapsedRealtime() + 5_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            AccessibilityNodeInfo control = findAppNode(id);
            if (control != null && control.isChecked() == expected) return;
            SystemClock.sleep(50);
        }
        throw new AssertionError("Control " + id + " did not become checked=" + expected);
    }

    private void awaitRecognitionPanel() {
        awaitNode("glyphDiagram", 10_000);
        awaitNode("dragHandle", 5_000);
        assertNoProjectionConsent();
    }

    private void stopSharing() {
        launchHome();
        AccessibilityNodeInfo primary = awaitNode("primaryButton", 10_000);
        if (!appString("start_capture").equals(text(primary))) click(primary);
        awaitButtonText(appString("start_capture"), 15_000);
    }

    private void observeHidden(File output, String name, JSONObject report) throws Exception {
        observeHidden(output, name, report, 3_000);
    }

    private void observeHidden(File output, String name, JSONObject report, long durationMillis) throws Exception {
        awaitNoFloatingSurface();
        long started = SystemClock.elapsedRealtime();
        while (SystemClock.elapsedRealtime() - started < durationMillis) {
            assertNoProjectionConsent();
            assertTrue("A dismissed window must remain completely hidden in the same round",
                    findAppNode("glyphDiagram") == null && findAppNode("dragHandle") == null && findAppNode("closeOverlay") == null);
            SystemClock.sleep(100);
        }
        saveScreenshot(new File(output, name + ".png"));
        report.put("observedMillis", SystemClock.elapsedRealtime() - started);
        report.put("recognitionPanelAbsent", true);
        report.put("screenshot", name + ".png");
    }

    private void observeMapHideAfterNotification(File output, String name, JSONObject report) throws Exception {
        long started = SystemClock.elapsedRealtime();
        long hiddenSince = -1;
        int visibilityResets = 0;
        while (SystemClock.elapsedRealtime() - started < 5_000) {
            assertNoProjectionConsent();
            boolean hidden = findAppNode("glyphDiagram") == null && findAppNode("dragHandle") == null
                    && findAppNode("closeOverlay") == null;
            long now = SystemClock.elapsedRealtime();
            if (hidden) {
                if (hiddenSince < 0) hiddenSince = now;
                if (now - hiddenSince >= 750) {
                    saveScreenshot(new File(output, name + ".png"));
                    report.put("observedMillis", now - started);
                    report.put("stableHiddenMillis", now - hiddenSince);
                    report.put("visibilityResets", visibilityResets);
                    report.put("recognitionPanelAbsent", true);
                    report.put("screenshot", name + ".png");
                    return;
                }
            } else {
                if (hiddenSince >= 0) visibilityResets++;
                hiddenSince = -1;
            }
            SystemClock.sleep(50);
        }
        throw new AssertionError("After notification Show on the map, all floating controls must stay hidden for 750 ms within five seconds");
    }

    private void awaitNoFloatingSurface() {
        long deadline = SystemClock.elapsedRealtime() + 5_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (findAppNode("glyphDiagram") == null && findAppNode("dragHandle") == null
                    && findAppNode("closeOverlay") == null) return;
            SystemClock.sleep(50);
        }
        throw new AssertionError("The floating panel did not disappear");
    }

    private void showFromNotification(File output, String name, JSONObject report) throws Exception {
        runShellCommand("cmd statusbar expand-notifications");
        String actionText = appString("notification_show_overlay");
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        long nextExpandRequestAt = 0;
        int expandRequests = 0;
        try {
            while (SystemClock.elapsedRealtime() < deadline) {
                AccessibilityNodeInfo action = findSystemNode(node -> actionText.equalsIgnoreCase(text(node))
                        && node.isVisibleToUser());
                if (action != null) {
                    saveScreenshot(new File(output, name + ".png"));
                    click(action);
                    report.put(name, new JSONObject().put("clickedActionText", actionText)
                            .put("actualNotificationClick", true).put("expandRequests", expandRequests)
                            .put("screenshot", name + ".png"));
                    return;
                }
                AccessibilityNodeInfo title = findSystemNode(node -> node.isVisibleToUser()
                        && appString("notification_title").equals(text(node)));
                for (AccessibilityNodeInfo card = title; card != null; card = card.getParent()) {
                    if ("com.android.systemui:id/expandableNotificationRow".equals(card.getViewIdResourceName())) {
                        if (card.isVisibleToUser() && SystemClock.elapsedRealtime() >= nextExpandRequestAt
                                && card.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND)) {
                            // Expansion is idempotent; clicking the toggle can collapse an updated notification.
                            card.performAction(AccessibilityNodeInfo.ACTION_EXPAND);
                            expandRequests++;
                            nextExpandRequestAt = SystemClock.elapsedRealtime() + 250;
                        }
                        break;
                    }
                }
                SystemClock.sleep(100);
            }
            throw new AssertionError("The notification's Show floating window action was not available");
        } catch (Exception | AssertionError failure) {
            String screenshotName = name + "-failure.png";
            String treeName = name + "-failure-ui.json";
            try {
                saveScreenshot(new File(output, screenshotName));
                report.put(name + "FailureScreenshot", screenshotName);
            } catch (Exception | AssertionError evidenceFailure) {
                failure.addSuppressed(evidenceFailure);
            }
            try {
                JSONArray windows = new JSONArray();
                for (AccessibilityWindowInfo window : automation.getWindows()) {
                    Rect bounds = new Rect();
                    window.getBoundsInScreen(bounds);
                    JSONObject entry = new JSONObject().put("id", window.getId()).put("type", window.getType())
                            .put("layer", window.getLayer()).put("title", String.valueOf(window.getTitle()))
                            .put("active", window.isActive()).put("focused", window.isFocused())
                            .put("bounds", bounds.toShortString());
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root != null) entry.put("root", accessibilityTree(root));
                    windows.put(entry);
                }
                JSONObject evidence = new JSONObject().put("actionText", actionText)
                        .put("notificationTitle", appString("notification_title"))
                        .put("expandRequests", expandRequests).put("windows", windows);
                try (OutputStreamWriter writer = new OutputStreamWriter(
                        new FileOutputStream(new File(output, treeName)), StandardCharsets.UTF_8)) {
                    writer.write(evidence.toString(2));
                }
                report.put(name + "FailureUiTree", treeName);
            } catch (Exception | AssertionError evidenceFailure) {
                failure.addSuppressed(evidenceFailure);
            }
            throw failure;
        } finally {
            runShellCommand("cmd statusbar collapse");
        }
    }

    private JSONObject accessibilityTree(AccessibilityNodeInfo node) throws Exception {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        JSONObject tree = new JSONObject().put("package", String.valueOf(node.getPackageName()))
                .put("resourceId", String.valueOf(node.getViewIdResourceName()))
                .put("class", String.valueOf(node.getClassName())).put("text", text(node))
                .put("contentDescription", String.valueOf(node.getContentDescription()))
                .put("visible", node.isVisibleToUser()).put("clickable", node.isClickable())
                .put("enabled", node.isEnabled()).put("bounds", bounds.toShortString());
        JSONArray actions = new JSONArray();
        for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) {
            actions.put(action.getId());
        }
        tree.put("actions", actions);
        JSONArray children = new JSONArray();
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child != null) children.put(accessibilityTree(child));
        }
        return tree.put("children", children);
    }

    private AccessibilityNodeInfo findSystemNode(Predicate<AccessibilityNodeInfo> matches) {
        return findInWindows(node -> "com.android.systemui".equals(String.valueOf(node.getPackageName()))
                && matches.test(node));
    }

    private void runShellCommand(String command) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)),
                StandardCharsets.UTF_8))) {
            while (reader.readLine() != null) { }
        }
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

    private void observePhases(File output, JSONObject report, boolean expectsAutomaticReveal) throws Exception {
        JSONArray stages = new JSONArray();
        JSONArray screenshots = new JSONArray();
        report.put("stages", stages);
        report.put("screenshots", screenshots);
        long startedAt = SystemClock.elapsedRealtime();
        long nextScreenshotAt = startedAt;
        long firstInputAt = -1;
        String previousStatus = null;
        boolean sawPresenting = false;
        report.put("terminalPhase", "liveInput");
        while (SystemClock.elapsedRealtime() - startedAt < 30_000) {
            assertNoProjectionConsent();
            long now = SystemClock.elapsedRealtime();
            AccessibilityNodeInfo statusNode = findAppNode("dragHandle");
            String status = statusNode == null ? null : text(statusNode);
            if (status != null && !status.equals(previousStatus)) {
                if (expectsAutomaticReveal && previousStatus == null) {
                    assertTrue("The hidden window must first reappear during sequence presentation",
                            status.startsWith("Watching the sequence"));
                    report.put("automaticRevealElapsedMillis", now - startedAt);
                    saveScreenshot(new File(output, "automatic-reveal.png"));
                }
                stages.put(new JSONObject().put("elapsedMillis", now - startedAt).put("text", status));
                previousStatus = status;
            }
            if (status != null && status.startsWith("Watching the sequence")) sawPresenting = true;
            if (YOUR_TURN.equals(status) && firstInputAt < 0) {
                assertTrue("Input must follow a visible sequence phase", sawPresenting);
                firstInputAt = now;
                report.put("firstInputElapsedMillis", now - startedAt);
            }
            if (firstInputAt >= 0) {
                assertEquals("The input sequence must remain visible for three seconds", YOUR_TURN, status);
            }
            if (now >= nextScreenshotAt) {
                String name = String.format(java.util.Locale.ROOT, "frame-%05d.png", screenshots.length());
                saveScreenshot(new File(output, name));
                screenshots.put(new JSONObject().put("file", name).put("elapsedMillis", now - startedAt)
                        .put("overlayStatus", status == null ? JSONObject.NULL : status));
                nextScreenshotAt = now + 500;
            }
            if (firstInputAt >= 0 && now - firstInputAt >= 3_000) {
                saveScreenshot(new File(output, "input-after-three-seconds.png"));
                report.put("terminalScreenshot", "input-after-three-seconds.png");
                report.put("inputPhaseObservedMillis", now - firstInputAt);
                report.put("elapsedMillis", now - startedAt);
                return;
            }
            SystemClock.sleep(100);
        }
        throw new AssertionError("No complete presentation-to-input UI journey in the first playback; last=" + previousStatus);
    }

    private void observeMapHideCycle(File output, JSONObject report, String fileName) throws Exception {
        JSONArray screenshots = new JSONArray();
        report.put("screenshots", screenshots);
        report.put("mapControlVerification", "UI visibility timing and screenshots; review the independently visible map icons in automatic-hidden.png");
        boolean newThreeGlyphMovie = "real-recording-124013.mp4".equals(fileName);
        long revealDeadline = newThreeGlyphMovie ? 12_000 : 16_000;
        long drawingScreenshotAt = newThreeGlyphMovie ? 13_000 : 17_000;
        long earliestMapControlsAt = newThreeGlyphMovie ? 18_000 : 24_000;
        long firstPlaybackDeadline = newThreeGlyphMovie ? 24_000 : 31_000;
        long startedAt = SystemClock.elapsedRealtime();
        long nextScreenshotAt = startedAt;
        long firstVisibleAt = -1;
        boolean savedInputScreenshot = false;
        // These bounds belong to the fixtures; completed-text pages precede the first actual map icons.
        while (SystemClock.elapsedRealtime() - startedAt < firstPlaybackDeadline) {
            assertNoProjectionConsent();
            long now = SystemClock.elapsedRealtime();
            long elapsed = now - startedAt;
            AccessibilityNodeInfo diagram = findAppNode("glyphDiagram");
            boolean visible = diagram != null && diagram.isVisibleToUser();
            assertTrue("Automatic hiding must remove the title and X control row",
                    findAppNode("dragHandle") == null && findAppNode("closeOverlay") == null);
            if (visible) {
                if (firstVisibleAt < 0) {
                    firstVisibleAt = now;
                    assertTrue("The prediction canvas must appear before the input observation window", elapsed < revealDeadline);
                    report.put("automaticRevealElapsedMillis", elapsed);
                    saveScreenshot(new File(output, "automatic-reveal.png"));
                }
                if (!savedInputScreenshot && elapsed >= drawingScreenshotAt) {
                    saveScreenshot(new File(output, "during-drawing.png"));
                    report.put("duringDrawingScreenshotElapsedMillis", elapsed);
                    savedInputScreenshot = true;
                }
            } else if (firstVisibleAt >= 0) {
                assertTrue("The canvas must remain through drawing and completion text until a map icon appears",
                        elapsed >= earliestMapControlsAt && savedInputScreenshot);
                report.put("automaticHideElapsedMillis", elapsed);
                report.put("visibleDurationMillis", now - firstVisibleAt);
                saveScreenshot(new File(output, "automatic-hidden.png"));
                JSONObject hidden = new JSONObject();
                report.put("afterAutomaticHide", hidden);
                observeHidden(output, "after-automatic-hide", hidden, 750);
                report.put("mapHiddenWithinFirstPlayback", true);
                report.put("elapsedMillis", SystemClock.elapsedRealtime() - startedAt);
                return;
            }
            if (now >= nextScreenshotAt) {
                String name = String.format(java.util.Locale.ROOT, "frame-%05d.png", screenshots.length());
                saveScreenshot(new File(output, name));
                screenshots.put(new JSONObject().put("file", name).put("elapsedMillis", elapsed)
                        .put("glyphCanvasVisible", visible));
                nextScreenshotAt = now + 500;
            }
            SystemClock.sleep(100);
        }
        throw new AssertionError("No prediction canvas followed by map-icon hiding within the first playback");
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
            if (node != null && node.isEnabled() && node.isVisibleToUser()) return node;
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
                AccessibilityNodeInfo allowNotifications = findNode(root, node -> node.getViewIdResourceName() != null
                        && node.getViewIdResourceName().endsWith(":id/permission_allow_button"));
                if (allowNotifications != null) {
                    click(allowNotifications);
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
