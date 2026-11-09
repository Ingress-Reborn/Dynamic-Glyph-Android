package org.szres.glyphlens.recognition

/** How one slot should be drawn and what evidence it rests on. */
sealed class TileStyle {
    data class Confirmed(val id: GlyphID) : TileStyle()
    data class Observing(val id: GlyphID) : TileStyle()
    data class Inferred(val id: GlyphID) : TileStyle()
    data class Predicted(val id: GlyphID) : TileStyle()
    data class Alternatives(val count: Int) : TileStyle()
    data object Uncertain : TileStyle()
    data object Missing : TileStyle()
    data object Pending : TileStyle()

    val glyph: GlyphID?
        get() = when (this) {
            is Confirmed -> id
            is Observing -> id
            is Inferred -> id
            is Predicted -> id
            is Alternatives, Uncertain, Missing, Pending -> null
        }

    /** Only a confirmed observation is solid; everything else is dashed. */
    val isDashed: Boolean
        get() = this !is Confirmed

    val caption: String
        get() = when (this) {
            is Confirmed -> id.name
            is Observing -> id.name
            is Inferred -> id.name
            is Predicted -> id.name
            is Alternatives -> "$count options"
            Uncertain -> "Unclear"
            Missing -> "Missed"
            Pending -> "—"
        }
}

data class UpcomingGuidance(val firstSlotIndex: Int, val styles: List<TileStyle>)

/** Every known or independently counted slot, in order, as a drawable style. */
val GlyphDisplaySnapshot.tileStyles: List<TileStyle>
    get() {
        val count = maxOf(slots.size, expectedSlotCount ?: 0)
        if (count <= 0) return emptyList()
        val observed = outlook.observed.size
        return (0 until count).map { index ->
            val slot = slots.getOrNull(index)
            when (slot?.status) {
                SlotObservation.Status.CONFIRMED ->
                    slot.glyph?.let { TileStyle.Confirmed(it) } ?: TileStyle.Uncertain
                SlotObservation.Status.INFERRED ->
                    slot.glyph?.let { TileStyle.Inferred(it) } ?: TileStyle.Uncertain
                SlotObservation.Status.OBSERVING -> {
                    val visible = provisionalGlyph
                    if (acceptance.isTracking && index == currentSlotIndex && visible != null) {
                        TileStyle.Observing(visible)
                    } else {
                        TileStyle.Pending
                    }
                }
                SlotObservation.Status.MISSING -> TileStyle.Missing
                SlotObservation.Status.UNCERTAIN -> TileStyle.Uncertain
                SlotObservation.Status.PENDING, null -> {
                    if (index < observed) {
                        TileStyle.Pending
                    } else {
                        val offset = index - observed
                        if (outlook.state == PredictionState.RESOLVED &&
                            offset < outlook.predictedSuffix.size
                        ) {
                            TileStyle.Predicted(outlook.predictedSuffix[offset])
                        } else if (offset < outlook.suffixAlternatives.size) {
                            val choices = outlook.suffixAlternatives[offset]
                            if (choices.size == 1) TileStyle.Predicted(choices[0])
                            else TileStyle.Alternatives(choices.size)
                        } else {
                            TileStyle.Pending
                        }
                    }
                }
            }
        }
    }

/** The second caption line describing the evidence behind a tile. */
fun GlyphDisplaySnapshot.noteFor(style: TileStyle): String? = when (style) {
    is TileStyle.Confirmed -> "Observed"
    is TileStyle.Inferred -> "Inferred · position unverified"
    is TileStyle.Observing -> if (provisionalExtensionCount > 0) {
        "Observing… +$provisionalExtensionCount longer"
    } else {
        "Observing…"
    }
    is TileStyle.Predicted, is TileStyle.Alternatives -> "Predicted"
    TileStyle.Uncertain -> "Not settled"
    TileStyle.Missing -> "No evidence"
    TileStyle.Pending -> null
}

/** A bounded window into tileStyles, or null when guidance would overclaim. */
fun GlyphDisplaySnapshot.upcomingGuidance(
    limit: Int = 2,
    maximumIndicatorAge: Double = 0.75,
): UpcomingGuidance? {
    if (state != TrackingState.INPUT || liveness != CaptureLiveness.LIVE || !acceptance.isTracking ||
        slotCountConflict || indicatorsObstructed ||
        slots.any { it.status == SlotObservation.Status.INFERRED }
    ) {
        return null
    }
    val index = inputSlotIndex ?: return null
    val observedAt = inputSlotObservedTime
    if (observedAt != null && sourceFrameTime - observedAt > maximumIndicatorAge) return null

    val styles = tileStyles
    if (index < 0 || index >= styles.size) return null
    val window = styles.subList(index, minOf(index + maxOf(1, limit), styles.size)).toList()
    if (window.any { it is TileStyle.Observing }) return null
    val sayable = window.any { it.glyph != null || it is TileStyle.Alternatives }
    if (!sayable) return null
    return UpcomingGuidance(index, window)
}
