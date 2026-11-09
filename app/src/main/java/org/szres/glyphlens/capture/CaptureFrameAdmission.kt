package org.szres.glyphlens.capture

/** Applies the iOS 20 Hz limit before copying the ImageReader pixel buffer. */
internal class CaptureFrameAdmission {
    private var lastAcceptedNanos: Long? = null

    fun accept(timestampNanos: Long, metadata: CapturedFrameMetadata): Boolean {
        if (metadata.continuityBroken) {
            lastAcceptedNanos = timestampNanos
            return true
        }
        if (metadata.status == CaptureFrameStatus.IDLE) return false
        val previous = lastAcceptedNanos
        if (previous != null && timestampNanos >= previous && timestampNanos - previous < 49_000_000L) {
            return false
        }
        lastAcceptedNanos = timestampNanos
        return true
    }
}
