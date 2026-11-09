package org.szres.glyphlens.diagnostics

import org.szres.glyphlens.recognition.image.AnalysisRateController
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Internal measurements for capture and recognition acceptance checks. */
enum class PowerMetric(val label: String) {
    FRAMES_DELIVERED("delivered"),
    FRAMES_ANALYSED("analysed"),
    FRAME_WORK("whole frame"),
    CONVERSION("RGBA->lum"),
    CAPTURE_COPY("capture copy"),
    GRID_HUNT("grid hunt"),
    GLYPH_READ("Glyph read"),
    SLOT_STRIP("slot strip"),
    FRAME_INTAKE("frame intake"),
    SKIPPED_FRAME("skipped frm"),
    DISPLAY_UPDATE("UI update"),
    DISPLAY_LATENCY("display lag");

    companion object {
        val recognitionSteps = listOf(
            FRAME_WORK, CAPTURE_COPY, CONVERSION, GRID_HUNT, GLYPH_READ, SLOT_STRIP,
            FRAME_INTAKE, SKIPPED_FRAME,
        )
        val displaySteps = listOf(DISPLAY_UPDATE, DISPLAY_LATENCY)

        /** Non-overlapping wall-time runs used only for the report's untimed-work pointer. */
        val topLevel = listOf(
            FRAME_WORK, CAPTURE_COPY, FRAME_INTAKE, SKIPPED_FRAME, DISPLAY_UPDATE,
        )
    }
}

/** A session count and its total, longest run, and the end of that run on the meter clock. */
data class PowerTally(
    val count: Long = 0,
    val nanoseconds: Long = 0,
    val maxNanoseconds: Long = 0,
    val maxAt: Long = 0,
) {
    fun add(count: Long = 1, nanoseconds: Long = 0, at: Long): PowerTally {
        require(count >= 0 && nanoseconds >= 0)
        return copy(
            count = this.count + count,
            nanoseconds = this.nanoseconds + nanoseconds,
            maxNanoseconds = max(maxNanoseconds, nanoseconds),
            maxAt = if (nanoseconds > maxNanoseconds) at else maxAt,
        )
    }

    fun merge(other: PowerTally): PowerTally = copy(
        count = count + other.count,
        nanoseconds = nanoseconds + other.nanoseconds,
        maxNanoseconds = max(maxNanoseconds, other.maxNanoseconds),
        maxAt = if (other.maxNanoseconds > maxNanoseconds) other.maxAt else maxAt,
    )

    val averageMilliseconds: Double? get() = if (count > 0) nanoseconds.toDouble() / count / 1e6 else null
    val maxMilliseconds: Double? get() = if (count > 0) maxNanoseconds / 1e6 else null
}

/** Session totals plus fixed one-second buckets; no timestamps, frames, or text are retained. */
class PowerWindow(val metrics: Int) {
    private var totals = Array(metrics) { PowerTally() }
    private var buckets = Array(metrics * seconds) { PowerTally() }
    private val stamps = LongArray(seconds) { Long.MIN_VALUE }

    fun totals(): List<PowerTally> = totals.toList()

    fun record(metric: Int, count: Long = 1, nanoseconds: Long = 0, second: Long, at: Long? = null) {
        require(metric in 0 until metrics && count >= 0 && nanoseconds >= 0)
        val time = at ?: max(0, second) * 1_000_000_000L
        totals[metric] = totals[metric].add(count, nanoseconds, time)
        val slot = slot(second)
        if (stamps[slot] != second) {
            // Totals retain late work even when its rolling bucket has already been reused.
            if (stamps[slot] >= second) return
            stamps[slot] = second
            for (index in slot * metrics until (slot + 1) * metrics) buckets[index] = PowerTally()
        }
        val index = slot * metrics + metric
        buckets[index] = buckets[index].add(count, nanoseconds, time)
    }

    fun count(metric: Int, second: Long): Long {
        val slot = slot(second)
        return if (stamps[slot] == second) buckets[slot * metrics + metric].count else 0
    }

    fun recent(metric: Int, second: Long): PowerTally {
        var tally = PowerTally()
        val oldest = second - seconds
        for (slot in 0 until seconds) {
            if (stamps[slot] <= second && stamps[slot] > oldest) {
                tally = tally.merge(buckets[slot * metrics + metric])
            }
        }
        return tally
    }

    companion object {
        const val seconds = 60

        @JvmStatic
        fun slot(second: Long): Int = (((second % seconds) + seconds) % seconds).toInt()

        @JvmStatic
        fun span(now: Long, start: Long): Double {
            val second = now / 1_000_000_000L
            val windowStart = max(0, second - seconds + 1) * 1_000_000_000L
            val from = max(start, windowStart)
            return if (now > from) (now - from) / 1e9 else 0.0
        }

        @JvmStatic
        fun perMinute(count: Long, span: Double): Double? = if (span >= 1) count * 60.0 / span else null
    }
}

/** Rate of a monotonically growing value, based on the first sample in each recent second. */
class CumulativeWindow {
    private data class Sample(var second: Long = Long.MIN_VALUE, var time: Long = 0, var value: Long = 0)

    private val samples = Array(PowerWindow.seconds) { Sample() }

    fun note(value: Long, at: Long) {
        val second = at / 1_000_000_000L
        val slot = PowerWindow.slot(second)
        if (samples[slot].second >= second) return
        samples[slot] = Sample(second, at, value)
    }

    fun rate(value: Long, at: Long): Double? {
        val now = at / 1_000_000_000L
        val oldest = samples.filter {
            it.second <= now && it.second > now - PowerWindow.seconds
        }.minByOrNull { it.time } ?: return null
        if (at < oldest.time + 1_000_000_000L || value < oldest.value) return null
        return (value - oldest.value).toDouble() / (at - oldest.time)
    }
}

/** Bounded exact samples: percentiles sort only on snapshot, never on a capture thread. */
private class PowerDistribution(val capacity: Int) {
    private val values = LongArray(capacity)
    private var next = 0
    private var retained = 0

    fun add(value: Long) {
        values[next] = value
        next = (next + 1) % capacity
        if (retained < capacity) retained++
    }

    fun percentiles(): Pair<Double?, Double?> {
        if (retained == 0) return null to null
        val sorted = values.copyOf(retained).apply { sort() }
        fun percentile(fraction: Double): Double {
            val rank = ceil(fraction * sorted.size).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1] / 1e6
        }
        return percentile(0.50) to percentile(0.95)
    }
}

/** p50/p95 are nearest-rank values over the most recent [PowerMeter.SAMPLE_CAPACITY] timed runs. */
data class PowerTiming(
    val tally: PowerTally = PowerTally(),
    val p50Milliseconds: Double? = null,
    val p95Milliseconds: Double? = null,
    val retainedSamples: Int = 0,
)

/** How long each gate pace held on the movie/source clock, with paused gaps bounded. */
data class AnalysisModeClock(
    private val totals: DoubleArray = DoubleArray(3),
    private val lastTime: Double? = null,
    private val lastMode: AnalysisRateController.Mode? = null,
) {
    fun note(mode: AnalysisRateController.Mode, at: Double): AnalysisModeClock {
        if (!at.isFinite()) return this
        val updated = totals.copyOf()
        val previousTime = lastTime
        val previousMode = lastMode
        if (previousTime != null && previousMode != null && at >= previousTime) {
            updated[index(previousMode)] += min(at - previousTime, maximumGap)
        }
        return AnalysisModeClock(updated, at, mode)
    }

    fun seconds(mode: AnalysisRateController.Mode): Double = totals[index(mode)]
    val total: Double get() = totals.sum()
    fun percent(mode: AnalysisRateController.Mode): Double? =
        if (total > 0) seconds(mode) / total * 100 else null

    override fun equals(other: Any?): Boolean = other is AnalysisModeClock &&
        totals.contentEquals(other.totals) && lastTime == other.lastTime && lastMode == other.lastMode

    override fun hashCode(): Int = 31 * (31 * totals.contentHashCode() + (lastTime?.hashCode() ?: 0)) +
        (lastMode?.hashCode() ?: 0)

    companion object {
        const val maximumGap = 2.0
        val modes = listOf(
            AnalysisRateController.Mode.QUIET,
            AnalysisRateController.Mode.RESTING,
            AnalysisRateController.Mode.ACTIVE,
        )

        fun index(mode: AnalysisRateController.Mode): Int = when (mode) {
            AnalysisRateController.Mode.QUIET -> 0
            AnalysisRateController.Mode.RESTING -> 1
            AnalysisRateController.Mode.ACTIVE -> 2
        }
    }
}

/** Current gate cap; this is configuration, not a measured recognition rate. */
data class PowerReadingMode(
    val mode: AnalysisRateController.Mode,
    val intervalSeconds: Double,
    val powerSaving: Boolean,
    val inHack: Boolean,
) {
    val ratePerSecond: Int get() = if (intervalSeconds > 0) (1.0 / intervalSeconds).toInt() else 0
}

enum class PowerMemoryKind {
    RGBA_FRAME,
}

/** Bounded capture allocation samples kept only in memory. */
data class PowerMemoryEvent(
    val kind: PowerMemoryKind,
    val atNanoseconds: Long,
    val byteCount: Long? = null,
)

/** Real gauge values only. Null means unavailable; no thermal, power, or CPU value is invented. */
data class PowerGaugeSnapshot(
    val cpuTimeNanoseconds: Long? = null,
    val thermalStatus: Int? = null,
    val lowPowerMode: Boolean? = null,
    val memoryFootprintBytes: Long? = null,
)

fun interface PowerGaugeReader {
    fun read(): PowerGaugeSnapshot

    companion object {
        val unavailable = PowerGaugeReader { PowerGaugeSnapshot() }
    }
}

data class PowerSnapshot(
    val hasSession: Boolean = false,
    val ended: Boolean = false,
    val elapsedSeconds: Double = 0.0,
    val spanSeconds: Double = 0.0,
    val startedAtNanoseconds: Long = 0,
    val totalTallies: List<PowerTally> = List(PowerMetric.entries.size) { PowerTally() },
    val recentTallies: List<PowerTally> = List(PowerMetric.entries.size) { PowerTally() },
    val timings: List<PowerTiming> = List(PowerMetric.entries.size) { PowerTiming() },
    val cpuSeconds: Double? = null,
    val cpuPercentRecent: Double? = null,
    val thermalStatus: Int? = null,
    val worstThermalStatus: Int? = null,
    val lowPowerMode: Boolean? = null,
    val memoryFootprintBytes: Long? = null,
    val modes: AnalysisModeClock = AnalysisModeClock(),
    val readingMode: PowerReadingMode? = null,
    val analysedLastSecond: Long = 0,
    val memoryEvents: List<PowerMemoryEvent> = emptyList(),
) {
    fun total(metric: PowerMetric): PowerTally = totalTallies[metric.ordinal]
    fun recent(metric: PowerMetric): PowerTally = recentTallies[metric.ordinal]
    fun timing(metric: PowerMetric): PowerTiming = timings[metric.ordinal]
    fun perMinute(metric: PowerMetric): Double? = PowerWindow.perMinute(recent(metric).count, spanSeconds)
    fun maxAt(metric: PowerMetric): Double? {
        val tally = total(metric)
        if (tally.maxNanoseconds <= 0) return null
        return if (tally.maxAt > startedAtNanoseconds) {
            (tally.maxAt - startedAtNanoseconds) / 1e9
        } else {
            0.0
        }
    }

    val timedSeconds: Double
        get() = PowerMetric.topLevel.sumOf { total(it).nanoseconds } / 1e9

}

/**
 * Bounded capture counters. Each update performs a clock read, a short lock, scalar
 * updates and one bounded ring write; sorting and formatting happen only in [snapshot]/PowerReport.
 */
class PowerMeter @JvmOverloads constructor(
    private val clock: () -> Long = System::nanoTime,
    private val gauges: PowerGaugeReader = PowerGaugeReader.unavailable,
    sampleCapacity: Int = SAMPLE_CAPACITY,
) {
    private class State {
        var window = PowerWindow(PowerMetric.entries.size)
        var distributions = Array(PowerMetric.entries.size) { PowerDistribution(SAMPLE_CAPACITY) }
        var hasSession = false
        var startedAt = 0L
        var cpuAtStart: Long? = null
        var sampledCpuTime: Long? = null
        var endedAt: Long? = null
        var cpuAtEnd: Long? = null
        var lastSecond = Long.MIN_VALUE
        var modes = AnalysisModeClock()
        var readingMode: PowerReadingMode? = null
        var worstThermal: Int? = null
        var thermalAtEnd: Int? = null
        var lowPowerAtEnd: Boolean? = null
        var memoryAtEnd: Long? = null
        var memoryEvents = arrayOfNulls<PowerMemoryEvent>(MEMORY_CAPACITY)
        var memoryNext = 0
        var memoryRetained = 0
        var lastMemoryFootprint: Long? = null
    }

    private val lock = Any()
    private val configuredSampleCapacity = sampleCapacity.coerceAtLeast(1)
    private var state = State().apply {
        distributions = Array(PowerMetric.entries.size) { PowerDistribution(configuredSampleCapacity) }
        startedAt = clock()
        lastSecond = startedAt / 1_000_000_000L
    }

    fun now(): Long = clock()

    val readingMode: PowerReadingMode?
        get() = synchronized(lock) { state.readingMode }

    /** Latest real gauge sample; capture samples at most once per second, without a read here. */
    val sampledCpuSeconds: Double?
        get() = synchronized(lock) {
            val start = state.cpuAtStart ?: return@synchronized null
            val current = if (state.endedAt != null) state.cpuAtEnd else state.sampledCpuTime
            current?.let { (it - start).coerceAtLeast(0) / 1e9 }
        }

    fun reset() {
        val time = clock()
        val gauge = gauges.read()
        synchronized(lock) {
            state = State().apply {
                hasSession = true
                startedAt = time
                cpuAtStart = gauge.cpuTimeNanoseconds
                sampledCpuTime = gauge.cpuTimeNanoseconds
                lastSecond = time / 1_000_000_000L
                worstThermal = gauge.thermalStatus
                lastMemoryFootprint = gauge.memoryFootprintBytes
                distributions = Array(PowerMetric.entries.size) {
                    PowerDistribution(configuredSampleCapacity)
                }
            }
            cpu = CumulativeWindow()
            gauge.cpuTimeNanoseconds?.let { cpu.note(it, time) }
        }
    }

    private var cpu = CumulativeWindow()

    fun end() {
        val time = clock()
        val gauge = gauges.read()
        synchronized(lock) {
            if (state.endedAt == null) {
                gauge.cpuTimeNanoseconds?.let { cpu.note(it, time) }
                state.endedAt = time
                state.cpuAtEnd = gauge.cpuTimeNanoseconds
                state.thermalAtEnd = gauge.thermalStatus
                state.lowPowerAtEnd = gauge.lowPowerMode
                state.memoryAtEnd = gauge.memoryFootprintBytes ?: state.lastMemoryFootprint
            }
        }
    }

    fun count(metric: PowerMetric, count: Long = 1) = add(metric, count, 0, clock(), timed = false)

    fun record(metric: PowerMetric, nanoseconds: Long) =
        add(
            metric, 1, nanoseconds, clock(), timed = true,
            // Source-to-display latency can legitimately begin at the first frame's arrival,
            // immediately before reset returns. CPU work begun before reset is still excluded.
            rejectPreSessionWork = metric != PowerMetric.DISPLAY_LATENCY,
        )

    fun finish(metric: PowerMetric, since: Long) {
        val end = clock()
        add(metric, 1, (end - since).coerceAtLeast(0), end, timed = true)
    }

    inline fun measure(metric: PowerMetric, block: () -> Unit) {
        val started = now()
        try {
            block()
        } finally {
            finish(metric, started)
        }
    }

    fun noteAnalysisMode(
        mode: AnalysisRateController.Mode,
        at: Double,
        current: PowerReadingMode? = null,
    ) {
        synchronized(lock) {
            if (state.endedAt == null && state.hasSession) {
                state.modes = state.modes.note(mode, at)
                if (current != null) state.readingMode = current
            }
        }
    }

    fun noteMemory(kind: PowerMemoryKind, byteCount: Long? = null) {
        require(byteCount == null || byteCount >= 0)
        val time = clock()
        synchronized(lock) {
            if (state.endedAt != null) return
            val index = state.memoryNext
            state.memoryEvents[index] = PowerMemoryEvent(kind, time, byteCount)
            state.memoryNext = (index + 1) % MEMORY_CAPACITY
            if (state.memoryRetained < MEMORY_CAPACITY) state.memoryRetained++
        }
    }

    private fun add(
        metric: PowerMetric,
        count: Long,
        nanoseconds: Long,
        at: Long,
        timed: Boolean,
        rejectPreSessionWork: Boolean = true,
    ) {
        require(count >= 0 && nanoseconds >= 0)
        val second = at / 1_000_000_000L
        var sample = false
        synchronized(lock) {
            if (state.endedAt != null || at < state.startedAt ||
                (rejectPreSessionWork && nanoseconds > at - state.startedAt)
            ) return
            state.window.record(metric.ordinal, count, nanoseconds, second, at)
            if (timed) state.distributions[metric.ordinal].add(nanoseconds)
            if (second > state.lastSecond) {
                state.lastSecond = second
                sample = true
            }
        }
        if (sample) sampleGauges()
    }

    private fun sampleGauges() {
        val time = clock()
        val gauge = gauges.read()
        synchronized(lock) {
            if (state.endedAt == null) {
                state.sampledCpuTime = gauge.cpuTimeNanoseconds
                gauge.cpuTimeNanoseconds?.let { cpu.note(it, time) }
                gauge.thermalStatus?.let { state.worstThermal = maxOf(state.worstThermal ?: it, it) }
                state.lastMemoryFootprint = gauge.memoryFootprintBytes ?: state.lastMemoryFootprint
            }
        }
    }

    fun snapshot(): PowerSnapshot {
        var time = clock()
        val alreadyEnded = synchronized(lock) { state.endedAt != null }
        val liveGauge = if (alreadyEnded) PowerGaugeSnapshot() else gauges.read()
        var cpuTime = liveGauge.cpuTimeNanoseconds
        synchronized(lock) {
            val endedAt = state.endedAt
            if (endedAt != null) {
                time = endedAt
                cpuTime = state.cpuAtEnd
            } else {
                state.sampledCpuTime = cpuTime
                cpuTime?.let { cpu.note(it, time) }
                liveGauge.thermalStatus?.let {
                    state.worstThermal = maxOf(state.worstThermal ?: it, it)
                }
            }
            val endedCpuTime = cpuTime
            val second = time / 1_000_000_000L
            val totals = state.window.totals()
            val timings = PowerMetric.entries.map { metric ->
                val percentiles = state.distributions[metric.ordinal].percentiles()
                val tally = totals[metric.ordinal]
                val retained = minOf(tally.count.toInt().coerceAtLeast(0), configuredSampleCapacity)
                PowerTiming(tally, percentiles.first, percentiles.second, retained)
            }
            val events = mutableListOf<PowerMemoryEvent>()
            val first = (state.memoryNext - state.memoryRetained + MEMORY_CAPACITY) % MEMORY_CAPACITY
            repeat(state.memoryRetained) { offset ->
                state.memoryEvents[(first + offset) % MEMORY_CAPACITY]?.let(events::add)
            }
            val cpuStart = state.cpuAtStart
            return PowerSnapshot(
                hasSession = state.hasSession,
                ended = state.endedAt != null,
                elapsedSeconds = if (time > state.startedAt) (time - state.startedAt) / 1e9 else 0.0,
                spanSeconds = PowerWindow.span(time, state.startedAt),
                startedAtNanoseconds = state.startedAt,
                totalTallies = totals,
                recentTallies = PowerMetric.entries.map { state.window.recent(it.ordinal, second) },
                timings = timings,
                cpuSeconds = if (cpuStart != null && endedCpuTime != null) {
                    ((endedCpuTime - cpuStart).coerceAtLeast(0)) / 1e9
                } else {
                    null
                },
                cpuPercentRecent = endedCpuTime?.let { cpu.rate(it, time)?.times(100) },
                thermalStatus = if (endedAt != null) state.thermalAtEnd else liveGauge.thermalStatus,
                worstThermalStatus = state.worstThermal,
                lowPowerMode = if (endedAt != null) state.lowPowerAtEnd else liveGauge.lowPowerMode,
                memoryFootprintBytes = if (endedAt != null) {
                    state.memoryAtEnd
                } else {
                    liveGauge.memoryFootprintBytes ?: state.lastMemoryFootprint
                },
                modes = state.modes,
                readingMode = state.readingMode,
                analysedLastSecond = state.window.count(PowerMetric.FRAMES_ANALYSED.ordinal, second - 1),
                memoryEvents = events,
            )
        }
    }

    companion object {
        const val SAMPLE_CAPACITY = 1024
        const val MEMORY_CAPACITY = 64

        internal val sharedAndroidGauges = AndroidPowerGauges()

        /** Process gauges are real Android values; callers can still construct an isolated meter. */
        @JvmStatic
        val shared: PowerMeter by lazy { PowerMeter(gauges = sharedAndroidGauges) }
    }
}

/** Monospaced diagnostic text. Percentiles are measured timings, never power or accuracy claims. */
object PowerReport {
    const val width = 48

    @JvmStatic
    fun lines(snapshot: PowerSnapshot): List<String> {
        if (!snapshot.hasSession) return listOf("No capture session since app start.")
        val lines = mutableListOf<String>()
        val elapsed = snapshot.elapsedSeconds.toLong()
        lines += String.format(
            Locale.US, "%s %d:%02d · CPU %s s · 60 s: %s",
            if (snapshot.ended) "stopped" else "session", elapsed / 60, elapsed % 60,
            snapshot.cpuSeconds?.let(::secondsText) ?: "-",
            snapshot.cpuPercentRecent?.let { String.format(Locale.US, "%.0f%%", it) } ?: "-",
        )
        lines += "thermal ${snapshot.thermalStatus?.toString() ?: "-"} " +
            "(worst ${snapshot.worstThermalStatus?.toString() ?: "-"})"
        lines += "Low Power Mode ${snapshot.lowPowerMode?.let { if (it) "on" else "off" } ?: "unknown"}"
        snapshot.memoryFootprintBytes?.let {
            lines += String.format(Locale.US, "memory footprint %.1f MB", it / 1_048_576.0)
        }

        lines += row("frames", "session", "/min")
        val delivered = snapshot.total(PowerMetric.FRAMES_DELIVERED).count
        val analysed = snapshot.total(PowerMetric.FRAMES_ANALYSED).count
        for (metric in listOf(PowerMetric.FRAMES_DELIVERED, PowerMetric.FRAMES_ANALYSED)) {
            lines += row(metric.label, snapshot.total(metric).count.toString(), rate(snapshot.perMinute(metric)))
        }
        val recentSkipped = snapshot.recent(PowerMetric.FRAMES_DELIVERED).count -
            min(snapshot.recent(PowerMetric.FRAMES_DELIVERED).count, snapshot.recent(PowerMetric.FRAMES_ANALYSED).count)
        lines += row(
            "skipped", (delivered - min(delivered, analysed)).toString(),
            rate(PowerWindow.perMinute(recentSkipped, snapshot.spanSeconds)),
        )
        snapshot.readingMode?.let {
            lines += "${if (snapshot.ended) "last" else "now"} ${it.mode.name.lowercase()} " +
                "${it.ratePerSecond}/s · last s ${snapshot.analysedLastSecond}"
        }
        lines += modeLines(snapshot.modes)

        lines += timingTable("recognition", PowerMetric.recognitionSteps, snapshot, includeEmptyWholeFrame = true)
        lines += timingTable("display", PowerMetric.displaySteps, snapshot)

        lines += row("longest", "max ms", "at")
        for (metric in PowerMetric.recognitionSteps + PowerMetric.displaySteps) {
            if (snapshot.total(metric).maxNanoseconds > 0) {
                lines += row(
                    metric.label, milliseconds(snapshot.total(metric).maxMilliseconds),
                    clock(snapshot.maxAt(metric)),
                )
            }
        }
        if (snapshot.memoryEvents.isNotEmpty()) {
            lines += "memory events"
            snapshot.memoryEvents.takeLast(4).forEach {
                lines += "  ${it.kind.name.lowercase().replace('_', ' ')} " +
                    (it.byteCount?.let { bytes -> "$bytes B" } ?: "")
            }
        }
        val timed = snapshot.timedSeconds
        snapshot.cpuSeconds?.let {
            lines += "timed ${secondsText(timed)} s · untimed ${secondsText(it - timed)} s"
        }
        return lines
    }

    private fun timingTable(
        title: String,
        metrics: List<PowerMetric>,
        snapshot: PowerSnapshot,
        includeEmptyWholeFrame: Boolean = false,
    ): List<String> {
        val lines = mutableListOf(row(title, "n", "avg", "p50", "p95", "max"))
        for (metric in metrics) {
            val timing = snapshot.timing(metric)
            if (timing.tally.count == 0L && !(includeEmptyWholeFrame && metric == PowerMetric.FRAME_WORK)) continue
            lines += row(
                metric.label, timing.tally.count.toString(), milliseconds(timing.tally.averageMilliseconds),
                milliseconds(timing.p50Milliseconds), milliseconds(timing.p95Milliseconds),
                milliseconds(timing.tally.maxMilliseconds),
            )
        }
        return lines
    }

    @JvmStatic
    fun modeLines(modes: AnalysisModeClock): List<String> {
        if (modes.total <= 0) return emptyList()
        return buildList {
            add(row("gate time", "s", "%"))
            for (mode in AnalysisModeClock.modes) {
                add(row(
                    mode.name.lowercase(), String.format(Locale.US, "%.1f", modes.seconds(mode)),
                    modes.percent(mode)?.let { String.format(Locale.US, "%.0f", it) } ?: "-",
                ))
            }
        }
    }

    @JvmStatic
    fun clock(seconds: Double?): String {
        if (seconds == null || !seconds.isFinite() || seconds < 0) return "-"
        val whole = seconds.toLong()
        return if (whole < 6_000) {
            String.format(Locale.US, "%d:%02d", whole / 60, whole % 60)
        } else {
            "${whole / 60}m"
        }
    }

    @JvmStatic
    fun milliseconds(value: Double?): String {
        if (value == null) return "-"
        return String.format(Locale.US, if (value < 10) "%.2f" else if (value < 100) "%.1f" else "%.0f", value)
    }

    @JvmStatic
    fun rate(value: Double?): String = value?.let { String.format(Locale.US, "%.0f", it) } ?: "-"

    @JvmStatic
    fun secondsText(value: Double): String =
        String.format(Locale.US, if (kotlin.math.abs(value) < 100) "%.1f" else "%.0f", value)

    @JvmStatic
    fun row(label: String, vararg columns: String): String = buildString {
        append(if (label.length < 12) label.padEnd(12) else label.take(12))
        columns.forEachIndexed { index, column ->
            val width = if (index == 0) 7 else 6
            append(if (column.length < width) column.padStart(width) else " $column")
        }
    }
}
