package org.szres.glyphlens.capture

import org.szres.glyphlens.recognition.RgbaFrame

enum class CaptureFrameStatus { COMPLETE, IDLE, BLANK, SUSPENDED, STOPPED, UNKNOWN }

data class CapturedFrame(
    val rgba: RgbaFrame,
    val status: CaptureFrameStatus = CaptureFrameStatus.COMPLETE,
    val arrivalUptimeNanos: Long,
    val continuityBroken: Boolean = false,
) {
    val sourceTimeSeconds: Double get() = rgba.timestampNanos / 1_000_000_000.0
}

/**
 * Restates ImageReader callbacks without losing a repeated source timestamp or a delivery gap.
 * Android normally omits unchanged frames; a repeated timestamp is nevertheless treated as idle,
 * never as fresh pixels. A backwards source clock or an unexplained gap starts a new phase epoch.
 */
class CaptureSourceMonitor(
    private val stallThresholdNanos: Long = 1_000_000_000L,
) {
    private var lastSourceNanos: Long? = null
    private var lastArrivalNanos: Long? = null

    fun observe(sourceTimestampNanos: Long, arrivalUptimeNanos: Long): CapturedFrameMetadata {
        val previousSource = lastSourceNanos
        val previousArrival = lastArrivalNanos
        val repeated = previousSource != null && sourceTimestampNanos == previousSource
        val sourceGap = if (previousSource == null) 0L else sourceTimestampNanos - previousSource
        val arrivalGap = if (previousArrival == null) {
            0L
        } else {
            (arrivalUptimeNanos - previousArrival).coerceAtLeast(0L)
        }
        val stalled = previousArrival != null && arrivalGap > stallThresholdNanos
        val sourceWentBackwards = previousSource != null && sourceTimestampNanos < previousSource
        val unexplainedSourceGap = !repeated && sourceGap > stallThresholdNanos &&
            sourceGap - arrivalGap > stallThresholdNanos
        val continuityBroken = sourceWentBackwards || stalled || unexplainedSourceGap
        lastSourceNanos = sourceTimestampNanos
        lastArrivalNanos = arrivalUptimeNanos
        return CapturedFrameMetadata(
            status = if (repeated) CaptureFrameStatus.IDLE else CaptureFrameStatus.COMPLETE,
            continuityBroken = continuityBroken,
        )
    }
}

data class CapturedFrameMetadata(
    val status: CaptureFrameStatus,
    val continuityBroken: Boolean,
)
