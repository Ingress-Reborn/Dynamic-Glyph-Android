package org.szres.glyphlens.capture

import org.szres.glyphlens.recognition.schedule.GameHeaderReader
import org.szres.glyphlens.recognition.schedule.GameTextSchedule

interface CaptureDiagnostics {
    val isReadingText: Boolean
    val inFlightTextRead: GameTextSchedule.Request?
    fun snapshotTextReadMetrics(): GameHeaderReader.Metrics
}
