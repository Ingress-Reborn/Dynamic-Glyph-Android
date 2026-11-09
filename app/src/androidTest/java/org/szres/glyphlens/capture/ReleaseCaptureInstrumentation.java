package org.szres.glyphlens.capture;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.util.Log;

public final class ReleaseCaptureInstrumentation extends Instrumentation {
    private boolean autoStopOnly;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        autoStopOnly = "autoStop".equals(arguments.getString("journey"));
        start();
    }

    @Override
    public void onStart() {
        Bundle results = new Bundle();
        int passed = 0;
        try {
            ReleaseUiCaptureInstrumentedTest journeys = new ReleaseUiCaptureInstrumentedTest(this);
            if (!autoStopOnly) {
                journeys.fiveGlyphReleaseJourneyProducesVisualEvidence();
                passed++;
                journeys.threeGlyphReleaseJourneyProducesVisualEvidence();
                passed++;
                journeys.powerSavingReleaseJourneyStartsAndResumesFromIcon();
                passed++;
            }
            journeys.autoStopReleaseJourneyRetainsResultAndRestartsRecognition();
            passed++;
            results.putInt("passed", passed);
            results.putString("stream", "\nOK (" + passed + " UI journeys); Glyph screenshots require manual review.\n");
            finish(Activity.RESULT_OK, results);
        } catch (Throwable failure) {
            results.putInt("passed", passed);
            results.putString("stream", "\nFAILED\n" + Log.getStackTraceString(failure));
            finish(Activity.RESULT_CANCELED, results);
        }
    }
}
