package org.szres.glyphlens.capture

import org.szres.glyphlens.recognition.GlyphDisplaySnapshot
import kotlin.math.ceil

/** Time-only display changes for one immutable snapshot; no recognition evidence is manufactured. */
internal object OverlayDisplayRefresh {
    private const val STALENESS_SECONDS = 1.5

    fun renderKey(snapshot: GlyphDisplaySnapshot, uptime: Double): List<Any?> = listOf(
        snapshot.revision,
        snapshot.displayStatus(uptime),
        snapshot.provisionalGlyph,
        snapshot.provisionalExtensionCount,
        snapshot.calibrationSummary,
    )

    /** Staleness begins strictly after the threshold, so refresh one millisecond past it. */
    fun nextDelayMillis(snapshot: GlyphDisplaySnapshot, uptime: Double): Long? {
        if (!uptime.isFinite() || snapshot.evidenceArrivalUptime <= 0.0) return null
        val next = snapshot.evidenceArrivalUptime + STALENESS_SECONDS
        if (next < uptime) return null
        return (ceil((next - uptime) * 1_000.0).toLong() + 1L).coerceAtLeast(1L)
    }
}
