package org.szres.glyphlens.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureSizeTest {
    @Test fun liveCaptureUsesTheSameAnalysisDimensionsAsRecordingReplay() {
        assertEquals(CaptureSize(603, 1333, 480), CaptureSize.forContent(1216, 2688, 480))
        assertEquals(CaptureSize(603, 1072, 420), CaptureSize.forContent(1080, 1920, 420))
        assertEquals(CaptureSize(603, 1340, 560), CaptureSize.forContent(1440, 3200, 560))
    }

    @Test fun smallerContentIsNotUpscaledAndRotationPreservesAspectRatio() {
        assertEquals(CaptureSize(480, 800, 240), CaptureSize.forContent(480, 800, 240))
        assertEquals(CaptureSize(603, 273, 480), CaptureSize.forContent(2688, 1216, 480))
    }
}
