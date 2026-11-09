package org.szres.glyphlens.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.szres.glyphlens.settings.OverlayWindowGeometry

class OverlayPlacementTest {
    @Test
    fun automaticResultPlacementReleasesADragAndUsesTheCurrentGap() {
        val placement = OverlayPlacement()
        val oldGeometry = OverlayGameGeometry(0.5, 0.3)
        placement.updateDisplay(720, 1280, 2f)
        placement.maximumWindowHeight(oldGeometry, 1280, 16)
        placement.top(oldGeometry, 1280, 150, 16)
        placement.userMoved()
        assertNull(placement.top(oldGeometry, 1280, 150, 16))

        placement.resetAutomaticPlacement()
        val currentGeometry = OverlayGameGeometry(0.369, timerBottomEstimateFraction = 0.246)
        val maximum = placement.maximumWindowHeight(currentGeometry, 1280, 16)
        val fitted = placement.fitCanvas(OverlayWindowGeometry(656, 66), maximum, 96, 160)
        assertEquals(OverlayWindowGeometry(288, 29), fitted)
        val top = placement.top(currentGeometry, 1280, fitted.canvasHeightPixels + 96, 16)!!
        assertEquals(331, top)
        assertTrue(top + fitted.canvasHeightPixels + 96 + 16 <= 472)
        assertNull(placement.top(currentGeometry, 1280, fitted.canvasHeightPixels + 96, 16))
    }

    @Test
    fun anAutomaticPositionIsRecomputedWhenTheDisplayDimensionsChange() {
        val placement = OverlayPlacement()
        placement.updateDisplay(1080, 2144, 2f)
        val tall = OverlayGameGeometry(935.0 / 2144, 733.0 / 2144)
        assertEquals(745, placement.top(tall, 2144, 125, 12))
        placement.updateDisplay(1080, 2144, 2f)
        assertNull(placement.top(tall, 2144, 125, 12))

        placement.updateDisplay(720, 1280, 2f)
        val short = OverlayGameGeometry(0.369, 0.246)
        assertEquals(125, placement.maximumWindowHeight(short, 1280, 16))
        assertEquals(331, placement.top(short, 1280, 125, 16))
        assertNull(placement.top(short, 1280, 125, 16))
    }

    @Test
    fun densityChangesRecomputeAutomaticMarginsEvenWhenPixelDimensionsStayTheSame() {
        val placement = OverlayPlacement()
        val geometry = OverlayGameGeometry(0.5, 0.3)
        placement.updateDisplay(720, 1280, 2f)
        assertEquals(400, placement.top(geometry, 1280, 150, 16))
        placement.updateDisplay(720, 1280, 3f)
        assertEquals(408, placement.top(geometry, 1280, 150, 24))
    }

    @Test
    fun manualPlacementStillOwnsTheWindowAfterADisplayResize() {
        val placement = OverlayPlacement()
        val geometry = OverlayGameGeometry(0.369, 0.246)
        placement.updateDisplay(720, 1280, 2f)
        assertEquals(125, placement.maximumWindowHeight(geometry, 1280, 16))
        placement.top(geometry, 1280, 125, 16)
        placement.userMoved()
        placement.updateDisplay(1080, 2144, 3f)
        assertEquals(125, placement.maximumWindowHeight(geometry, 2144, 24))
        assertNull(placement.top(geometry, 2144, 125, 24))
        assertNull(placement.initialTop(2144, 125, 24))
    }

    @Test
    fun shortDisplayStartsClearOfTheArrayThenFitsAboveItWithoutShrinkingControls() {
        val placement = OverlayPlacement()
        val preferred = OverlayWindowGeometry(widthPixels = 656, canvasHeightPixels = 66)
        val initial = placement.fitCanvas(preferred, placement.maximumWindowHeight(null, 1280, 16), 96, 160)
        assertEquals(preferred, initial)
        val initialTop = placement.initialTop(1280, initial.canvasHeightPixels + 96, 16)!!
        assertEquals(230, initialTop)
        assertTrue(initialTop + initial.canvasHeightPixels + 96 < 472)

        val geometry = OverlayGameGeometry(arrayTopFraction = 0.369, timerBottomEstimateFraction = 0.246)
        val maximumHeight = placement.maximumWindowHeight(geometry, 1280, 16)
        assertEquals(125, maximumHeight)
        val fitted = placement.fitCanvas(preferred, maximumHeight, 96, 160)
        assertEquals(OverlayWindowGeometry(widthPixels = 288, canvasHeightPixels = 29), fitted)
        val top = placement.top(geometry, 1280, fitted.canvasHeightPixels + 96, 16)!!
        assertEquals(331, top)
        assertTrue(top >= 315 + 16)
        assertTrue(top + fitted.canvasHeightPixels + 96 + 16 <= 472)
        assertEquals(OverlayWindowGeometry(656, 66), preferred)
    }

    @Test
    fun estimatedTimerCanRefineAnArrayPlacementBeforeTheRealTimerArrives() {
        val placement = OverlayPlacement()
        assertEquals(798, placement.top(OverlayGameGeometry(935.0 / 2144), 2144, 125, 12))
        assertEquals(742, placement.top(OverlayGameGeometry(935.0 / 2144, timerBottomEstimateFraction = 730.0 / 2144), 2144, 125, 12))
        assertNull(placement.top(OverlayGameGeometry(935.0 / 2144, timerBottomEstimateFraction = 731.0 / 2144), 2144, 125, 12))
        assertEquals(745, placement.top(OverlayGameGeometry(935.0 / 2144, 733.0 / 2144), 2144, 125, 12))
    }

    @Test
    fun fixedControlsAreNeverSqueezedIntoAnImpossibleGap() {
        val placement = OverlayPlacement()
        val preferred = OverlayWindowGeometry(656, 66)
        assertEquals(preferred, placement.fitCanvas(preferred, 95, 96, 160))
        assertEquals(preferred, placement.fitCanvas(preferred, 97, 96, 160))
        assertNull(placement.top(OverlayGameGeometry(0.369, 0.32), 1280, 162, 16))
        assertEquals(269, OverlayPlacement().initialTop(400, 115, 16))
    }

    @Test
    fun anImpossibleLaterTimerDoesNotExpandTheWindowBackAcrossTheArray() {
        val placement = OverlayPlacement()
        val preferred = OverlayWindowGeometry(656, 66)
        val estimated = OverlayGameGeometry(472.0 / 1280, timerBottomEstimateFraction = 315.0 / 1280)
        val fitted = placement.fitCanvas(preferred, placement.maximumWindowHeight(estimated, 1280, 16), 96, 160)
        val top = placement.top(estimated, 1280, fitted.canvasHeightPixels + 96, 16)!!
        val located = OverlayGameGeometry(472.0 / 1280, timerBottomFraction = 336.0 / 1280)
        val retained = placement.fitCanvas(preferred, placement.maximumWindowHeight(located, 1280, 16), 96, 160, fitted)
        assertEquals(fitted, retained)
        assertNull(placement.top(located, 1280, retained.canvasHeightPixels + 96, 16))
        assertTrue(top + retained.canvasHeightPixels + 96 < 472)
    }

    @Test
    fun manualDraggingKeepsTheLastFittedSizeAndPosition() {
        val placement = OverlayPlacement()
        val geometry = OverlayGameGeometry(0.369, timerBottomEstimateFraction = 0.246)
        assertEquals(125, placement.maximumWindowHeight(geometry, 1280, 16))
        placement.userMoved()
        assertEquals(125, placement.maximumWindowHeight(OverlayGameGeometry(0.5, 0.2), 1280, 16))
        assertNull(placement.top(geometry, 1280, 125, 16))
        assertNull(placement.initialTop(1280, 125, 16))
    }

    @Test
    fun temporaryMissingGeometryDoesNotEnlargeAWindowAlreadyFittedAboveTheArray() {
        val placement = OverlayPlacement()
        val geometry = OverlayGameGeometry(0.369, timerBottomEstimateFraction = 0.246)
        assertEquals(125, placement.maximumWindowHeight(geometry, 1280, 16))
        placement.top(geometry, 1280, 125, 16)
        assertEquals(125, placement.maximumWindowHeight(null, 1280, 16))
        assertEquals(125, placement.maximumWindowHeight(OverlayGameGeometry(0.369), 1280, 16))
    }

    @Test
    fun tallLetterboxedRecordingMovesBelowItsActualTimerAndAboveTheArray() {
        val placement = OverlayPlacement()
        val top = placement.top(
            OverlayGameGeometry(arrayTopFraction = 935.0 / 2144, timerBottomFraction = 733.0 / 2144),
            screenHeight = 2144,
            windowHeight = 125,
            margin = 12,
        )!!
        assertEquals(745, top)
        assertTrue("The formerly covered 547..634 slot strip stays visible", top > 634)
        assertTrue(top + 125 + 12 <= 935)
    }

    @Test
    fun anArrayCanUncoverTheStripBeforeTheTimerIsLocated() {
        val placement = OverlayPlacement()
        assertEquals(798, placement.top(OverlayGameGeometry(935.0 / 2144), 2144, 125, 12))
        assertNull(placement.top(OverlayGameGeometry(936.0 / 2144), 2144, 125, 12))
        assertEquals(745, placement.top(OverlayGameGeometry(935.0 / 2144, 733.0 / 2144), 2144, 125, 12))
        assertNull(placement.top(OverlayGameGeometry(936.0 / 2144, 734.0 / 2144), 2144, 125, 12))
    }

    @Test
    fun aManualDragOwnsThePositionEvenWhenNewGeometryArrives() {
        val placement = OverlayPlacement()
        placement.top(OverlayGameGeometry(0.5), 2000, 150, 10)
        placement.userMoved()
        assertNull(placement.top(OverlayGameGeometry(0.5, 0.3), 2000, 150, 10))

        val draggedBeforeRecognition = OverlayPlacement()
        draggedBeforeRecognition.userMoved()
        assertNull(draggedBeforeRecognition.top(OverlayGameGeometry(0.5), 2000, 150, 10))
    }

    @Test
    fun placementScalesWithTheActualScreenInsteadOfADeviceProfile() {
        val geometry = OverlayGameGeometry(arrayTopFraction = 0.5, timerBottomFraction = 0.3)
        assertEquals(310, OverlayPlacement().top(geometry, 1000, 100, 10))
        assertEquals(620, OverlayPlacement().top(geometry, 2000, 200, 20))
    }

    @Test
    fun aWindowTooTallForTheKnownGapIsNotMovedOverTheTimerOrArray() {
        val placement = OverlayPlacement()
        assertNull(placement.top(OverlayGameGeometry(0.5, 0.4), 1000, 100, 10))
        assertEquals(410, placement.top(OverlayGameGeometry(0.5, 0.4), 1000, 70, 10))
    }
}
