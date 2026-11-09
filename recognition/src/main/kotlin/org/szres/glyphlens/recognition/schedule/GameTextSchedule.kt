package org.szres.glyphlens.recognition.schedule

/** One OCR lane; only its matching completion can release the source frame. */
class GameTextSchedule {
    enum class Mode { header }

    data class Request(val id: Int, val mode: Mode, val sourceTime: Double)

    var inFlight: Request? = null
        private set

    private var lastSubmission = Double.NEGATIVE_INFINITY
    private var nextID = 0

    fun take(
        at: Double,
        minimumInterval: Double = 0.1,
        readsHeader: Boolean = true,
        headerInterval: Double? = null,
        admitsHeader: () -> Boolean = { true },
    ): Request? {
        if (!at.isFinite() || !readsHeader || inFlight != null) return null
        val elapsed = at - lastSubmission
        if (at >= lastSubmission && elapsed < minimumInterval) return null
        if (headerInterval != null && at >= lastSubmission && elapsed < headerInterval - 0.002) return null
        if (!admitsHeader()) return null

        lastSubmission = at
        nextID += 1
        return Request(nextID, Mode.header, at).also { inFlight = it }
    }

    fun finish(request: Request) {
        if (inFlight == request) inFlight = null
    }
}
