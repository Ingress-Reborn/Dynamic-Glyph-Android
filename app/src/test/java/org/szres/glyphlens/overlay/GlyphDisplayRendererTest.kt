package org.szres.glyphlens.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.szres.glyphlens.recognition.AcceptanceMode
import org.szres.glyphlens.recognition.CaptureLiveness
import org.szres.glyphlens.recognition.CaptureSessionID
import org.szres.glyphlens.recognition.GlyphDisplaySnapshot
import org.szres.glyphlens.recognition.GlyphID
import org.szres.glyphlens.recognition.GlyphTable
import org.szres.glyphlens.recognition.PhaseSource
import org.szres.glyphlens.recognition.PredictionState
import org.szres.glyphlens.recognition.SequenceOutlook
import org.szres.glyphlens.recognition.SlotObservation
import org.szres.glyphlens.recognition.TrackingState

class GlyphDisplayRendererTest {
    private val see = GlyphID("see")
    private val begin = GlyphID("begin")
    private val table = GlyphTable.parse("see,03\nbegin,06a7")
    private val renderer = GlyphDisplayRenderer(table)

    @Test
    fun statusEdgeUsesSnapshotStatusIncludingStaleness() {
        val model = snapshot()

        val live = renderer.plan(model, width = 384f, height = 96f, uptime = 10.0)
        val stale = renderer.plan(model, width = 384f, height = 96f, uptime = 13.0)

        assertEquals(renderer.palette.predicted, live.statusColor)
        assertEquals(renderer.palette.warning, stale.statusColor)
        assertEquals(7.68f, live.ringWidth, 0.001f)
        assertEquals(live.ringWidth, live.contentBounds.left, 0.001f)
        assertEquals(384f - live.ringWidth, live.contentBounds.right, 0.001f)
    }

    @Test
    fun waitingAndObstructionMessagesComeFromProductionState() {
        val idle = message(snapshot(state = TrackingState.INACTIVE))
        val command = message(snapshot(state = TrackingState.COMMAND))
        val completed = message(snapshot(state = TrackingState.COMPLETED))
        val obstructed = message(snapshot(indicatorsObstructed = true))

        assertEquals("Start a Glyph hack in Ingress.", idle.text)
        assertEquals("Get ready — the sequence is coming.", command.text)
        assertEquals("This hack's sequence wasn't caught.", completed.text)
        assertEquals("Slot row unreadable · keep it visible", obstructed.text)
        assertEquals(renderer.palette.secondaryText, idle.color)
    }

    @Test
    fun confirmedObservingInferredAndPredictedTilesKeepTheirEvidenceStyles() {
        val confirmed = diagram(
            snapshot(slots = listOf(slot(SlotObservation.Status.CONFIRMED)), expectedSlotCount = 1)
        )
        val inferred = diagram(
            snapshot(slots = listOf(slot(SlotObservation.Status.INFERRED)), expectedSlotCount = 1)
        )
        val observing = diagram(
            snapshot(
                slots = listOf(slot(SlotObservation.Status.OBSERVING)),
                expectedSlotCount = 1,
                currentSlotIndex = 0,
                provisionalGlyph = see,
            )
        )
        val predicted = diagram(
            snapshot(
                slots = listOf(slot()),
                expectedSlotCount = 1,
                outlook = outlook(
                    state = PredictionState.RESOLVED,
                    predictedSuffix = listOf(see),
                ),
            )
        )

        assertEquals(renderer.palette.confirmed, confirmed.color)
        assertTrue(confirmed.dashIntervals.isEmpty())
        assertEquals(renderer.palette.observing, inferred.color)
        assertTrue(inferred.dashIntervals.isNotEmpty())
        assertEquals(renderer.palette.observing, observing.color)
        assertTrue(observing.dashIntervals.isNotEmpty())
        assertEquals(renderer.palette.predicted, predicted.color)
        assertTrue(predicted.dashIntervals.isNotEmpty())
    }

    @Test
    fun alternativesUncertainMissingAndPendingRenderPlaceholders() {
        val alternatives = placeholder(
            snapshot(
                slots = listOf(slot()),
                expectedSlotCount = 1,
                outlook = outlook(
                    state = PredictionState.AMBIGUOUS,
                    suffixAlternatives = listOf(listOf(see, begin)),
                ),
            )
        )
        val uncertain = placeholder(
            snapshot(slots = listOf(slot(SlotObservation.Status.UNCERTAIN)), expectedSlotCount = 1)
        )
        val missing = placeholder(
            snapshot(slots = listOf(slot(SlotObservation.Status.MISSING)), expectedSlotCount = 1)
        )
        val pending = placeholder(snapshot(slots = listOf(slot()), expectedSlotCount = 1))

        assertEquals(renderer.palette.predicted, alternatives.color)
        assertEquals(renderer.palette.warning, uncertain.color)
        assertEquals(renderer.palette.warning, missing.color)
        assertEquals(renderer.palette.unknown, pending.color)
        assertEquals(listOf(6f, 5f), pending.dashIntervals)
        assertTrue(pending.questionTextSize >= 10f)
    }

    @Test
    fun missingTableEntryIsAnUnknownPlaceholderNotAnEmptyTile() {
        val unknown = GlyphID("not-in-table")
        val content = oneTile(
            snapshot(
                slots = listOf(SlotObservation(index = 0, status = SlotObservation.Status.CONFIRMED, glyph = unknown)),
                expectedSlotCount = 1,
            )
        ).content as GlyphTileContent.Placeholder

        assertEquals(renderer.palette.unknown, content.color)
    }

    @Test
    fun diagramUsesRealTableGeometryWithFilledArrayAndVisitedNodes() {
        val diagram = diagram(
            snapshot(slots = listOf(slot(SlotObservation.Status.CONFIRMED)), expectedSlotCount = 1)
        )

        assertEquals(11, diagram.nodes.size)
        assertEquals(6, diagram.outline.size)
        assertEquals(2, diagram.visitedNodes.size)
        assertEquals(1, diagram.segments.size)
        val stroke = diagram.segments.single()
        // see = 03 descends from the apex towards the upper-left inner node.
        assertTrue(stroke.from.y < stroke.to.y)
        assertTrue(stroke.from.x > stroke.to.x)
        assertTrue(diagram.dotRadius > 0f)
        assertTrue(diagram.frameStrokeWidth > 0f)
    }

    @Test
    fun currentPresentationAndInputSlotsBothReceiveHighlight() {
        val highlighted = tiles(
            renderer.plan(
                snapshot(
                    slots = listOf(
                        slot(SlotObservation.Status.CONFIRMED, index = 0, glyph = see),
                        slot(SlotObservation.Status.CONFIRMED, index = 1, glyph = begin),
                    ),
                    expectedSlotCount = 2,
                    currentSlotIndex = 0,
                    inputSlotIndex = 1,
                ),
                width = 384f,
                height = 96f,
                uptime = 10.0,
            )
        )
        val plain = oneTile(
            snapshot(slots = listOf(slot(SlotObservation.Status.CONFIRMED)), expectedSlotCount = 1)
        )

        assertNotNull(highlighted[0].highlight)
        assertNotNull(highlighted[1].highlight)
        assertEquals(highlighted[0].color.withAlpha(0.8f), highlighted[0].highlight!!.color)
        assertNull(plain.highlight)
    }

    private fun message(snapshot: GlyphDisplaySnapshot): GlyphRenderBody.Message =
        renderer.plan(snapshot, width = 384f, height = 96f, uptime = 10.0).body
            as GlyphRenderBody.Message

    private fun oneTile(snapshot: GlyphDisplaySnapshot, uptime: Double = 10.0): GlyphTilePlan = tiles(
        renderer.plan(snapshot, width = 384f, height = 96f, uptime = uptime)
    ).single()

    private fun tiles(plan: GlyphRenderPlan): List<GlyphTilePlan> =
        (plan.body as GlyphRenderBody.Tiles).items

    private fun diagram(snapshot: GlyphDisplaySnapshot): GlyphTileContent.Diagram =
        oneTile(snapshot).content as GlyphTileContent.Diagram

    private fun placeholder(snapshot: GlyphDisplaySnapshot): GlyphTileContent.Placeholder =
        oneTile(snapshot).content as GlyphTileContent.Placeholder

    private fun slot(
        status: SlotObservation.Status = SlotObservation.Status.PENDING,
        index: Int = 0,
        glyph: GlyphID? = see,
    ): SlotObservation = SlotObservation(index = index, status = status, glyph = glyph)

    private fun outlook(
        state: PredictionState,
        predictedSuffix: List<GlyphID> = emptyList(),
        suffixAlternatives: List<List<GlyphID>> = emptyList(),
    ) = SequenceOutlook(
        state = state,
        observed = emptyList(),
        expectedLength = 1,
        candidateCount = 1,
        candidates = emptyList(),
        predictedSuffix = predictedSuffix,
        suffixAlternatives = suffixAlternatives,
        observationMayBeComplete = false,
    )

    private fun snapshot(
        slots: List<SlotObservation> = emptyList(),
        expectedSlotCount: Int? = null,
        state: TrackingState = TrackingState.PRESENTING,
        currentSlotIndex: Int? = null,
        inputSlotIndex: Int? = null,
        provisionalGlyph: GlyphID? = null,
        outlook: SequenceOutlook = SequenceOutlook.unavailable,
        indicatorsObstructed: Boolean = false,
    ) = GlyphDisplaySnapshot(
        captureSessionID = CaptureSessionID(1UL),
        hackID = 1,
        revision = 1,
        liveness = CaptureLiveness.LIVE,
        state = state,
        phaseSource = if (state == TrackingState.INPUT) PhaseSource.INPUT_TIMER else PhaseSource.INDICATOR_TRANSITION,
        acceptance = AcceptanceMode.TrackingSequence,
        sourceFrameTime = 5.0,
        evidenceArrivalUptime = 10.0,
        expectedSlotCount = expectedSlotCount,
        slots = slots,
        currentSlotIndex = currentSlotIndex,
        inputSlotIndex = inputSlotIndex,
        indicatorsObstructed = indicatorsObstructed,
        slotCountConflict = false,
        isFrozen = false,
        provisionalGlyph = provisionalGlyph,
        provisionalExtensionCount = 0,
        outlook = outlook,
        calibrationSummary = "11 rings",
    )
}
