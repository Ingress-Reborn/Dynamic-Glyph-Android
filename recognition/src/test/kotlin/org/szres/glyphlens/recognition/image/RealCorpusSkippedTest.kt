package org.szres.glyphlens.recognition.image

import org.junit.Ignore
import org.junit.Test

/** Explicitly unrun corpus checks; no fixture files are present in this Android checkout. */
class RealCorpusSkippedTest {
    @Ignore("SKIPPED: recorded empty-array and annotated Glyph image corpus is unavailable")
    @Test
    fun recordedArrayCalibrationAndGlyphAnnotations() = Unit

    @Ignore("SKIPPED: recorded banner/window/slot/countdown corpus is unavailable")
    @Test
    fun recordedSlotOcclusionAndCountdownAnnotations() = Unit

    @Ignore("SKIPPED: real opaque RGBA golden pixels are unavailable; CoreGraphics byte equivalence is not claimed")
    @Test
    fun rgbaGoldenPixelComparison() = Unit
}
