package org.szres.glyphlens.recognition.schedule

import org.szres.glyphlens.recognition.image.FrameTopBand
import org.szres.glyphlens.recognition.image.GameUILayout
import org.szres.glyphlens.recognition.schedule.GameTextSchedule.Request
import org.szres.glyphlens.recognition.text.CaptureSessionID
import org.szres.glyphlens.recognition.text.GameLanguage
import org.szres.glyphlens.recognition.text.GameLanguageMemory
import org.szres.glyphlens.recognition.text.HeaderPhraseMatcher
import org.szres.glyphlens.recognition.text.HeaderReading

/** The OCR adapter retains each admitted frame and finishes it once, even after invalidation. */
class GameHeaderReader(private val configuration: Configuration = Configuration()) {
    data class Configuration(
        val layout: GameUILayout = GameUILayout(),
        val minimumInterval: Double = 0.1,
        val maximumAge: Double = 0.35,
        val skipsUnchangedHeaders: Boolean = true,
        val languages: GameLanguageMemory = GameLanguageMemory(GameLanguage.english, isAutomatic = false),
    )

    data class Metrics(
        var headerInHackSubmitted: Int = 0,
        var headerOutsideSubmitted: Int = 0,
        var headerSkippedUnchanged: Int = 0,
        var submitted: Int = 0,
        var completed: Int = 0,
        var droppedBusy: Int = 0,
        var failed: Int = 0,
        var staleCompletions: Int = 0,
        var lastError: String? = null,
    )

    private data class HeaderBaseline(
        val signature: HeaderSignature,
        val session: CaptureSessionID,
        val language: GameLanguage,
        val sourceTime: Double,
    )

    private data class Owner(
        val request: Request,
        val session: CaptureSessionID,
        val generation: Int,
        val phaseGeneration: Int,
        val language: GameLanguage,
    )

    private val lock = Any()
    private val schedule = GameTextSchedule()
    private var generation = 0
    private var phaseGeneration = 0
    private var owner: Owner? = null
    private var headerBaseline: HeaderBaseline? = null
    private var reading: HeaderReading? = null
    private var readingSession: CaptureSessionID? = null
    private var metrics = Metrics()

    val inFlight: Request? get() = synchronized(lock) { schedule.inFlight }
    val isReading: Boolean get() = synchronized(lock) { schedule.inFlight != null }

    fun submittedLanguage(request: Request): GameLanguage? = synchronized(lock) {
        owner?.takeIf { it.request == request }?.language
    }

    fun submit(
        at: Double,
        session: CaptureSessionID,
        readsHeader: Boolean = true,
        headerInterval: Double? = null,
        constrained: Boolean = false,
        inHack: Boolean = true,
        requiresFreshHeader: Boolean = false,
        headerSignature: HeaderSignature? = null,
        topBand: FrameTopBand? = null,
        layout: GameUILayout = configuration.layout,
    ): Request? = synchronized(lock) {
        if (inHack || requiresFreshHeader) headerBaseline = null
        val busy = schedule.inFlight != null
        var language = configuration.languages.current
        var admittedSignature: HeaderSignature? = null
        val request = schedule.take(
            at = at,
            minimumInterval = configuration.minimumInterval * if (constrained) 2 else 1,
            readsHeader = readsHeader,
            headerInterval = headerInterval,
            admitsHeader = {
                language = configuration.languages.headerLanguage(at)
                if (inHack || requiresFreshHeader || !configuration.skipsUnchangedHeaders) {
                    true
                } else {
                    admittedSignature = headerSignature ?: topBand?.let { HeaderSignature.from(it, layout) }
                    val baseline = headerBaseline
                    val unchanged = admittedSignature != null && baseline != null &&
                        baseline.session == session && baseline.language == language &&
                        at >= baseline.sourceTime && at - baseline.sourceTime < maximumUnchangedAge &&
                        admittedSignature == baseline.signature
                    if (unchanged) metrics.headerSkippedUnchanged += 1
                    !unchanged
                }
            },
        )
        if (request == null) {
            if (busy) metrics.droppedBusy += 1
            return@synchronized null
        }
        // Record admission so late completions cannot refill an invalidated baseline.
        headerBaseline = admittedSignature?.let { HeaderBaseline(it, session, language, at) }
        owner = Owner(request, session, generation, phaseGeneration, language)
        metrics.submitted += 1
        if (inHack) metrics.headerInHackSubmitted += 1 else metrics.headerOutsideSubmitted += 1
        request
    }

    fun finishHeader(request: Request, lines: List<Pair<String, Double>>) {
        val language = submittedLanguage(request) ?: return
        finish(request, header = HeaderPhraseMatcher(language).read(lines).at(request.sourceTime))
    }

    fun finish(request: Request, header: HeaderReading? = null, error: String? = null): Unit = synchronized(lock) {
        val submitted = owner
        if (submitted == null || submitted.request != request) return@synchronized
        owner = null
        schedule.finish(request)
        val valid = submitted.generation == generation && submitted.phaseGeneration == phaseGeneration &&
            (header == null || header.sourceTime == request.sourceTime)
        if (!valid) {
            metrics.staleCompletions += 1
            return@synchronized
        }
        if (error != null) {
            headerBaseline = null
            metrics.failed += 1
            metrics.lastError = error
            return@synchronized
        }
        metrics.completed += 1
        if (header != null) {
            reading = header
            readingSession = submitted.session
            header.language?.let(configuration.languages::found)
        }
    }

    fun reading(frameTime: Double, session: CaptureSessionID): HeaderReading? = synchronized(lock) {
        val value = reading
        if (value == null || readingSession != session) return@synchronized null
        val age = frameTime - value.sourceTime
        if (age >= 0 && age <= configuration.maximumAge) value else null
    }

    fun latestReading(session: CaptureSessionID): HeaderReading? = synchronized(lock) {
        if (readingSession == session) reading else null
    }

    fun snapshotMetrics(): Metrics = synchronized(lock) { metrics.copy() }

    /** In-flight work keeps its lane until completion, but can no longer publish a reading. */
    fun invalidate(): Unit = synchronized(lock) {
        generation += 1
        headerBaseline = null
        reading = null
        readingSession = null
    }

    fun invalidatePhase(): Unit = synchronized(lock) {
        phaseGeneration += 1
        headerBaseline = null
        reading = null
        readingSession = null
    }

    companion object {
        const val maximumUnchangedAge = 1.0
    }
}
