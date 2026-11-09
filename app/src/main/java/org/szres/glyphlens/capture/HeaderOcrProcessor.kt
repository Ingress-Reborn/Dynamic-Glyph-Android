package org.szres.glyphlens.capture

import android.os.Handler
import android.os.HandlerThread
import org.szres.glyphlens.diagnostics.PowerMeter
import org.szres.glyphlens.diagnostics.PowerMetric
import org.szres.glyphlens.ocr.GameOcrOperation
import org.szres.glyphlens.ocr.GameOcrOutcome
import org.szres.glyphlens.ocr.GameOcrWork
import org.szres.glyphlens.ocr.MlKitGameOcrOperation
import org.szres.glyphlens.ocr.MlKitTextReader
import org.szres.glyphlens.recognition.RgbaFrame
import org.szres.glyphlens.recognition.image.ColorImage
import org.szres.glyphlens.recognition.image.GameUILayout
import org.szres.glyphlens.recognition.image.ImageFrame
import org.szres.glyphlens.recognition.schedule.GameHeaderReader
import org.szres.glyphlens.recognition.schedule.GameTextSchedule
import org.szres.glyphlens.recognition.text.CaptureSessionID
import org.szres.glyphlens.recognition.text.GameLanguageMemory
import java.util.concurrent.Executor

class HeaderOcrProcessor internal constructor(
    languages: GameLanguageMemory,
    private val completionExecutor: Executor,
    private val workerExecutor: Executor,
    private val operation: GameOcrOperation,
    private val closeWorker: () -> Unit,
    private val meter: PowerMeter? = null,
) : AutoCloseable, CaptureDiagnostics {
    constructor(
        handler: Handler,
        languages: GameLanguageMemory,
        meter: PowerMeter = PowerMeter.shared,
    ) : this(languages, PlatformOcr(handler), meter)

    private constructor(
        languages: GameLanguageMemory,
        platform: PlatformOcr,
        meter: PowerMeter,
    ) : this(
        languages = languages,
        completionExecutor = platform.completionExecutor,
        workerExecutor = platform.workerExecutor,
        operation = platform.operation,
        closeWorker = platform.closeWorker,
        meter = meter,
    )

    val evidence = GameHeaderReader(GameHeaderReader.Configuration(languages = languages))

    override val isReadingText: Boolean get() = evidence.isReading
    override val inFlightTextRead: GameTextSchedule.Request? get() = evidence.inFlight
    override fun snapshotTextReadMetrics(): GameHeaderReader.Metrics = evidence.snapshotMetrics()

    @Volatile private var closed = false
    internal var onCompletion: () -> Unit = {}

    fun submit(
        frame: RgbaFrame,
        image: ImageFrame,
        color: ColorImage,
        session: CaptureSessionID,
        readsHeader: Boolean = true,
        inHack: Boolean,
        constrained: Boolean,
        headerInterval: Double? = null,
        requiresFreshHeader: Boolean = false,
        layout: GameUILayout = GameUILayout(),
    ): GameTextSchedule.Request? {
        if (closed) return null
        val request = evidence.submit(
            at = image.timeSeconds,
            session = session,
            readsHeader = readsHeader,
            headerInterval = headerInterval,
            constrained = constrained,
            inHack = inHack,
            requiresFreshHeader = requiresFreshHeader,
            topBand = image.topBand,
            layout = layout,
        ) ?: return null
        meter?.count(if (inHack) PowerMetric.HEADER_IN_HACK else PowerMetric.HEADER_OUTSIDE)
        val language = checkNotNull(evidence.submittedLanguage(request)) {
            "An admitted OCR request must retain its submitted language"
        }
        val work = GameOcrWork(request, session, frame, color, language, layout.copy())
        workerExecutor.execute { runRead(work) }
        return request
    }

    private fun runRead(work: GameOcrWork) {
        val started = meter?.now()
        val outcome = try {
            operation.read(work)
        } catch (failure: Exception) {
            null
        } finally {
            if (started != null) meter.finish(PowerMetric.HEADER_READ, started)
        }
        completionExecutor.execute { finish(work, outcome) }
    }

    private fun finish(work: GameOcrWork, outcome: GameOcrOutcome?) {
        evidence.finish(
            request = work.request,
            header = outcome?.header,
            error = if (outcome == null) "Header OCR failed for request ${work.request.id}" else null,
        )
        if (!closed) onCompletion()
    }

    override fun close() {
        if (closed) return
        closed = true
        evidence.invalidate()
        closeWorker()
    }
}

private class PlatformOcr(handler: Handler) {
    val completionExecutor = Executor { command -> handler.post(command) }
    private val thread = HandlerThread("glyph-ocr").apply { start() }
    private val workerHandler = Handler(thread.looper)
    private val reader = MlKitTextReader()
    val workerExecutor = Executor { command -> workerHandler.post(command) }
    val operation = MlKitGameOcrOperation(reader)
    val closeWorker: () -> Unit = {
        workerHandler.post {
            reader.close()
            thread.quitSafely()
        }
    }
}
