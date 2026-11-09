package org.szres.glyphlens.recognition.session

import org.szres.glyphlens.recognition.CaptureSessionID
import org.szres.glyphlens.recognition.GlyphID
import org.szres.glyphlens.recognition.GlyphPath
import org.szres.glyphlens.recognition.image.GameUILayout
import org.szres.glyphlens.recognition.image.GlyphFrameObservation
import org.szres.glyphlens.recognition.image.NodeArrayDetection
import org.szres.glyphlens.recognition.image.SlotStripReading
import org.szres.glyphlens.recognition.image.SlotStripRejection

/** The shape half of one sampled frame, reduced so tracking never touches pixels. */
data class ShapeEvidence(
    val kind: Kind,
    val glyph: GlyphID?,
    val edges: Set<GlyphPath.Edge>,
    val extensions: List<GlyphID>,
) {
    enum class Kind {
        UNAVAILABLE,
        NO_STROKE,
        UNRECOGNIZED_STROKE,
        UNEXPLAINED_INK,
        AMBIGUOUS_SHAPE,
        UNIQUE_VISIBLE_SHAPE,
    }

    companion object {
        @JvmStatic
        val unavailable = ShapeEvidence(Kind.UNAVAILABLE, null, emptySet(), emptyList())

        /** Uses the production recognizer's exact-match and edge evidence without reclassifying it. */
        @JvmStatic
        fun from(observation: GlyphFrameObservation?): ShapeEvidence {
            if (observation == null) return unavailable
            return when (observation.state) {
                GlyphFrameObservation.State.UNIQUE_VISIBLE_SHAPE -> ShapeEvidence(
                    Kind.UNIQUE_VISIBLE_SHAPE,
                    observation.exactMatches.firstOrNull(),
                    observation.edges,
                    observation.extensions,
                )
                GlyphFrameObservation.State.AMBIGUOUS_SHAPE -> ShapeEvidence(
                    Kind.AMBIGUOUS_SHAPE, null, observation.edges, observation.extensions,
                )
                GlyphFrameObservation.State.UNEXPLAINED_INK -> ShapeEvidence(
                    Kind.UNEXPLAINED_INK, null, observation.edges, observation.extensions,
                )
                GlyphFrameObservation.State.UNRECOGNIZED_STROKE -> ShapeEvidence(
                    Kind.UNRECOGNIZED_STROKE, null, observation.edges, observation.extensions,
                )
                GlyphFrameObservation.State.NO_STROKE -> ShapeEvidence(
                    Kind.NO_STROKE, null, emptySet(), emptyList(),
                )
                GlyphFrameObservation.State.CALIBRATION_UNAVAILABLE,
                GlyphFrameObservation.State.CALIBRATION_MISMATCH,
                -> unavailable
            }
        }
    }
}

/** Everything one sampled frame contributes to presentation tracking. */
class FrameEvidence(
    val sessionID: CaptureSessionID,
    /** Source-frame timestamp in seconds, never the analysis completion time. */
    val time: Double,
    val slots: SlotStripReading?,
    val slotRejection: SlotStripRejection? = null,
    val shape: ShapeEvidence,
    val hasValidCalibration: Boolean,
    hasInputTimerBar: Boolean = false,
) {
    /** Independent current-frame countdown evidence, including evidence carried by the strip. */
    val hasInputTimerBar: Boolean = hasInputTimerBar || slots?.hasInputTimerBar == true

    companion object {
        /** A cached fit must agree with the current strip before it can support a game phase. */
        @JvmStatic
        fun fromAnalysis(
            sessionID: CaptureSessionID,
            time: Double,
            observation: GlyphFrameObservation?,
            slotResult: Result<SlotStripReading>? = null,
            hasInputTimerBar: Boolean = false,
            layout: GameUILayout = GameUILayout(),
            calibration: NodeArrayDetection? = observation?.calibration,
        ): FrameEvidence {
            val fit = calibration
            val layoutConsistent = fit?.let(layout::isConsistent) == true
            return FrameEvidence(
                sessionID = sessionID,
                time = time,
                slots = slotResult?.getOrNull(),
                slotRejection = slotResult?.exceptionOrNull() as? SlotStripRejection,
                shape = ShapeEvidence.from(observation),
                hasValidCalibration = fit != null && layoutConsistent,
                hasInputTimerBar = hasInputTimerBar,
            )
        }
    }
}
