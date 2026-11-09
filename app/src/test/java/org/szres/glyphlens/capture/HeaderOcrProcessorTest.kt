package org.szres.glyphlens.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.szres.glyphlens.diagnostics.PowerMeter
import org.szres.glyphlens.diagnostics.PowerMetric
import org.szres.glyphlens.ocr.GameOcrOperation
import org.szres.glyphlens.ocr.GameOcrOutcome
import org.szres.glyphlens.ocr.GameOcrWork
import org.szres.glyphlens.recognition.CaptureSessionID
import org.szres.glyphlens.recognition.RgbaFrame
import org.szres.glyphlens.recognition.image.ColorImage
import org.szres.glyphlens.recognition.image.ImageFrame
import org.szres.glyphlens.recognition.schedule.GameTextSchedule
import org.szres.glyphlens.recognition.text.GameLanguage
import org.szres.glyphlens.recognition.text.GameLanguageMemory
import org.szres.glyphlens.recognition.text.GamePhase
import org.szres.glyphlens.recognition.text.HeaderReading
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class HeaderOcrProcessorTest {
    private val session = CaptureSessionID(41uL)
    private val frame = RgbaFrame(20, 40, 2_000_000_000L, ByteArray(20 * 40 * 4))
    private val image = ImageFrame.fromRgba(frame)
    private val color = ColorImage(20, 40, ByteArray(20 * 40 * 3))
    private val languages = GameLanguageMemory(GameLanguage.english, isAutomatic = false)

    @Test
    fun publicDiagnosticsExposeLaneAndMeterMeasuresTheActualHeaderRead() {
        var now = 1_000_000_000L
        val meter = PowerMeter(clock = { now })
        meter.reset()
        val workers = ArrayDeque<Runnable>()
        val processor = HeaderOcrProcessor(
            languages = languages,
            completionExecutor = Executor { it.run() },
            workerExecutor = Executor { workers += it },
            operation = GameOcrOperation { work ->
                now += 25_000_000L
                GameOcrOutcome(
                    header = HeaderReading(GamePhase.command, "command", 1.0, work.request.sourceTime),
                )
            },
            closeWorker = {},
            meter = meter,
        )
        try {
            val diagnostics: CaptureDiagnostics = processor
            val request = processor.submit(
                frame = frame,
                image = image,
                color = color,
                session = session,
                inHack = false,
                constrained = false,
            )
            assertTrue(diagnostics.isReadingText)
            assertEquals(request, diagnostics.inFlightTextRead)
            assertEquals(1, workers.size)

            workers.removeFirst().run()

            assertFalse(diagnostics.isReadingText)
            assertNull(diagnostics.inFlightTextRead)
            assertEquals(1, diagnostics.snapshotTextReadMetrics().completed)
            val power = meter.snapshot()
            assertEquals(1, power.total(PowerMetric.HEADER_OUTSIDE).count)
            assertEquals(1, power.total(PowerMetric.HEADER_READ).count)
            assertEquals(25.0, power.timing(PowerMetric.HEADER_READ).p50Milliseconds!!, 1e-9)
            assertEquals(25.0, power.timing(PowerMetric.HEADER_READ).p95Milliseconds!!, 1e-9)
        } finally {
            processor.close()
        }
    }

    @Test
    fun synchronousReadRunsOffCaptureAndCompletionReturnsWithOriginalRequest() {
        val capture = Executors.newSingleThreadExecutor { Thread(it, "capture-test") }
        val worker = Executors.newSingleThreadExecutor { Thread(it, "ocr-test") }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val readThread = AtomicReference<String>()
        val completionThread = AtomicReference<String>()
        val submitted = AtomicReference<GameOcrWork>()
        val operation = GameOcrOperation { work ->
            submitted.set(work)
            readThread.set(Thread.currentThread().name)
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            GameOcrOutcome(
                header = HeaderReading(GamePhase.command, "command", 1.0, work.request.sourceTime),
            )
        }
        val processor = HeaderOcrProcessor(
            languages = languages,
            completionExecutor = Executor { command ->
                capture.execute {
                    completionThread.set(Thread.currentThread().name)
                    command.run()
                    completed.countDown()
                }
            },
            workerExecutor = Executor { command -> worker.execute(command) },
            operation = operation,
            closeWorker = {},
        )
        try {
            val request = capture.submit<GameTextSchedule.Request> {
                processor.submit(
                    frame = frame,
                    image = image,
                    color = color,
                    session = session,
                    inHack = true,
                    constrained = false,
                )!!
            }
            assertEquals(2.0, request.get(1, TimeUnit.SECONDS).sourceTime, 0.0)
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val second = capture.submit<GameTextSchedule.Request?> {
                processor.submit(
                    frame = frame,
                    image = image,
                    color = color,
                    session = session,
                    inHack = true,
                    constrained = false,
                )
            }
            assertNull("a second OCR lane must not open", second.get(1, TimeUnit.SECONDS))
            release.countDown()
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertEquals("ocr-test", readThread.get())
            assertEquals("capture-test", completionThread.get())
            assertEquals(request.get(), submitted.get().request)
            assertEquals(session, submitted.get().session)
            assertEquals(
                GamePhase.command,
                processor.evidence.reading(2.0, session)?.phase,
            )
        } finally {
            release.countDown()
            capture.submit(processor::close).get()
            capture.shutdownNow()
            worker.shutdownNow()
        }
    }

    @Test
    fun completionKeepsTheSourceFrameAgeAndSession() {
        val workers = ArrayDeque<Runnable>()
        val processor = HeaderOcrProcessor(
            languages = languages,
            completionExecutor = Executor { it.run() },
            workerExecutor = Executor { workers += it },
            operation = GameOcrOperation { work ->
                GameOcrOutcome(HeaderReading(GamePhase.presenting, "INCOMING GLYPH SEQUENCE", 1.0, work.request.sourceTime))
            },
            closeWorker = {},
        )
        try {
            processor.submit(frame, image, color, session, inHack = true, constrained = false)
            workers.removeFirst().run()

            assertEquals(2.0, processor.evidence.reading(2.34, session)?.sourceTime)
            assertNull(processor.evidence.reading(2.36, session))
            assertNull(processor.evidence.reading(1.99, session))
            assertNull(processor.evidence.reading(2.0, CaptureSessionID(42uL)))
        } finally {
            processor.close()
        }
    }

    @Test
    fun closingBeforeCompletionRetiresEvidenceAndSuppressesTheCallback() {
        val completions = ArrayDeque<Runnable>()
        var callbacks = 0
        var closes = 0
        val processor = HeaderOcrProcessor(
            languages = languages,
            completionExecutor = Executor { completions += it },
            workerExecutor = Executor { it.run() },
            operation = GameOcrOperation { work ->
                GameOcrOutcome(HeaderReading(GamePhase.presenting, "INCOMING GLYPH SEQUENCE", 1.0, work.request.sourceTime))
            },
            closeWorker = { closes++ },
        )
        processor.onCompletion = { callbacks++ }
        processor.submit(frame, image, color, session, inHack = true, constrained = false)
        assertTrue(processor.isReadingText)

        processor.close()
        completions.removeFirst().run()
        processor.close()

        assertFalse(processor.isReadingText)
        assertNull(processor.evidence.latestReading(session))
        assertNull(processor.submit(frame, image, color, session, inHack = true, constrained = false))
        assertEquals(0, callbacks)
        assertEquals(1, closes)
        assertEquals(1, processor.snapshotTextReadMetrics().staleCompletions)
    }

    @Test
    fun quietModeDoesNotStartOcrAndAFailedReadReleasesItsLane() {
        val workers = ArrayDeque<Runnable>()
        val processor = HeaderOcrProcessor(
            languages = languages,
            completionExecutor = Executor { it.run() },
            workerExecutor = Executor { workers += it },
            operation = GameOcrOperation { throw IllegalStateException("recognizer unavailable") },
            closeWorker = {},
        )
        try {
            assertNull(processor.submit(
                frame, image, color, session,
                readsHeader = false, inHack = false, constrained = false,
            ))
            assertTrue(workers.isEmpty())
            processor.submit(frame, image, color, session, inHack = true, constrained = false)
            workers.removeFirst().run()

            assertFalse(processor.isReadingText)
            assertNull(processor.evidence.latestReading(session))
            assertEquals(1, processor.snapshotTextReadMetrics().failed)
            val nextFrame = RgbaFrame(frame.width, frame.height, 2_200_000_000L, frame.pixels)
            processor.submit(
                nextFrame, ImageFrame.fromRgba(nextFrame), color, session,
                inHack = true, constrained = false,
            )
            assertEquals(1, workers.size)
            workers.removeFirst().run()
        } finally {
            processor.close()
        }
    }
}
