package com.somethingobvious.decibel.meter

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Append-only table of floats, written by the audio thread and read by the screen.
 *
 * Rows live in fixed chunks so a long session never copies what it already has, and [size] is
 * volatile so a reader that sees a row also sees the chunk it was written into.
 */
class Series(private val width: Int) {
    private val chunks = arrayOfNulls<FloatArray>(MAX_CHUNKS)

    @Volatile
    var size = 0
        private set

    // Holds 48 hours of ticks, then the meter stops with LOG FULL.
    val isFull: Boolean get() = size == MAX_CHUNKS * CHUNK_ROWS

    fun add(row: FloatArray) {
        if (isFull) return
        val index = size
        val chunk =
            chunks[index / CHUNK_ROWS] ?: FloatArray(
                CHUNK_ROWS * width,
            ).also { chunks[index / CHUNK_ROWS] = it }
        row.copyInto(chunk, index % CHUNK_ROWS * width)
        size = index + 1
    }

    operator fun get(row: Int, column: Int): Float =
        checkNotNull(chunks[row / CHUNK_ROWS])[row % CHUNK_ROWS * width + column]

    private companion object {
        const val CHUNK_ROWS = 72_000
        const val MAX_CHUNKS = 48
    }
}

/** Running figures for a session, in dB relative to full scale so a later calibration applies to all of it. */
data class Stats(
    val ticks: Int,
    /** Ticks that count towards the figures. Taps on the glass and silenced input are left out. */
    val counted: Int,
    val leq: FloatArray,
    val fastMax: FloatArray,
    val fastMin: FloatArray,
    val slowMax: FloatArray,
    val slowMin: FloatArray,
    val peak: FloatArray,
    val doseSeconds: Double,
) {
    val seconds: Double get() = ticks.toDouble() / LevelMeter.TICKS_PER_SECOND

    companion object {
        private val none = FloatArray(Weighting.entries.size) { Float.NaN }
        val EMPTY =
            Stats(
                ticks = 0,
                counted = 0,
                leq = none,
                fastMax = none,
                fastMin = none,
                slowMax = none,
                slowMin = none,
                peak = none,
                doseSeconds = 0.0,
            )
    }
}

/** NIOSH exposure: 85 dBA for 8 hours is a full dose, and every 3 dB more halves the time. */
object Exposure {
    private const val CRITERION_DB = 85.0
    private const val EXCHANGE_DB = 3.0
    private const val CRITERION_SECONDS = 8.0 * 3600.0

    /** Seconds of exposure weighted by 2^(L/3), which lets the calibration offset factor out later. */
    fun weightedSeconds(levelDbfs: Float, seconds: Double): Double = seconds * 2.0.pow(levelDbfs / EXCHANGE_DB)

    /** Fraction of a daily dose, 1.0 being a full one. */
    fun dose(doseSeconds: Double, offsetDb: Double): Double =
        doseSeconds * 2.0.pow((offsetDb - CRITERION_DB) / EXCHANGE_DB) / CRITERION_SECONDS

    fun allowedSeconds(levelDb: Double): Double = CRITERION_SECONDS / 2.0.pow((levelDb - CRITERION_DB) / EXCHANGE_DB)
}

/** One measuring session: every tick's levels, a per-second log for export, and the running [Stats]. */
class Session {
    /** Per tick, Fast then Slow for each weighting: column = weighting * 2 + (0 for Fast, 1 for Slow). */
    val history = Series(Weighting.entries.size * 2)

    /**
     * Per second, Leq, Fast max and peak for each weighting, 1 if the input clipped, then seconds since
     * the session began.
     */
    val seconds = Series(SECOND_COLUMNS)

    /** Where each stretch of measuring began, so a tick index maps back to the clock. */
    val runs = CopyOnWriteArrayList<Run>()

    @Volatile
    var stats = Stats.EMPTY
        private set

    private val count = Weighting.entries.size
    private val energy = DoubleArray(count)
    private val second = FloatArray(SECOND_COLUMNS)
    private var secondEnergy = DoubleArray(count)
    private var secondTicks = 0
    private var secondCounted = 0
    private var secondStart = 0

    data class Run(val firstTick: Int, val wallMillis: Long)

    /** Logs the second in progress as it stands, so a save includes it. */
    fun closeSecond() {
        flushSecond()
    }

    fun begin(wallMillis: Long) {
        flushSecond()
        runs += Run(history.size, wallMillis)
    }

    /**
     * The middle value of [column] over the last [rows] ticks, which a passing thump can't move. Only
     * ticks from the current run count, and null until there are [rows] of them, so a level from
     * before a pause can never stand in for the one now.
     */
    fun recentMedian(column: Int, rows: Int): Float? {
        val end = history.size
        if (end - (runs.lastOrNull()?.firstTick ?: 0) < rows) return null
        val values = FloatArray(minOf(rows, end)) { history[end - 1 - it, column] }
        values.sort()
        return values[values.size / 2]
    }

    fun wallMillisAt(tick: Int): Long {
        val run = runs.lastOrNull { it.firstTick <= tick } ?: return 0L
        return run.wallMillis + (tick - run.firstTick) * MILLIS_PER_TICK
    }

    /**
     * Adds a tick to the history, or returns false once the history is full. When [isCounted] is false
     * the tick still shows on the graph but leaves every figure, the dose and the log alone.
     */
    fun add(tick: Tick, isCounted: Boolean = true, isGap: Boolean = false): Boolean {
        if (history.isFull) return false
        // Nothing was heard during a gap, so the graph shows nothing rather than the level before it.
        history.add(
            FloatArray(count * 2) { i ->
                when {
                    isGap -> LevelMeter.FLOOR_DB
                    i % 2 == 0 -> tick.fast[i / 2]
                    else -> tick.slow[i / 2]
                }
            },
        )
        stats = if (isCounted) counting(tick) else stats.copy(ticks = stats.ticks + 1)
        addToSecond(tick = tick, isCounted = isCounted)
        return true
    }

    private fun counting(tick: Tick): Stats {
        val old = stats
        val counted = old.counted + 1
        val isFirst = old.counted == 0
        for (w in 0 until count) energy[w] += 10.0.pow(tick.leq[w] / TEN.toDouble())
        return Stats(
            ticks = old.ticks + 1,
            counted = counted,
            leq = FloatArray(count) { LevelMeter.decibels(energy[it] / counted) },
            fastMax = extremes(isFirst = isFirst, old = old.fastMax, latest = tick.fastMax, pick = ::max),
            fastMin = extremes(isFirst = isFirst, old = old.fastMin, latest = tick.fast, pick = ::min),
            slowMax = extremes(isFirst = isFirst, old = old.slowMax, latest = tick.slow, pick = ::max),
            slowMin = extremes(isFirst = isFirst, old = old.slowMin, latest = tick.slow, pick = ::min),
            peak = extremes(isFirst = isFirst, old = old.peak, latest = tick.peak, pick = ::max),
            doseSeconds =
                old.doseSeconds +
                    Exposure.weightedSeconds(levelDbfs = tick.leq[Weighting.A.ordinal], seconds = SECONDS_PER_TICK),
        )
    }

    private fun extremes(isFirst: Boolean, old: FloatArray, latest: FloatArray, pick: (Float, Float) -> Float) =
        FloatArray(count) { if (isFirst) latest[it] else pick(old[it], latest[it]) }

    private fun addToSecond(tick: Tick, isCounted: Boolean) {
        if (secondTicks == 0) {
            second.fill(Float.NEGATIVE_INFINITY)
            second[CLIPPED] = 0f
            secondStart = history.size - 1
        }
        secondTicks++
        if (isCounted) addCounted(tick)
        if (secondTicks == LevelMeter.TICKS_PER_SECOND) flushSecond()
    }

    private fun addCounted(tick: Tick) {
        secondCounted++
        for (w in 0 until count) {
            secondEnergy[w] += 10.0.pow(tick.leq[w] / TEN.toDouble())
            second[w * COLUMNS_PER_WEIGHTING + 1] = max(second[w * COLUMNS_PER_WEIGHTING + 1], tick.fastMax[w])
            second[w * COLUMNS_PER_WEIGHTING + 2] = max(second[w * COLUMNS_PER_WEIGHTING + 2], tick.peak[w])
        }
        if (tick.isClipped) second[CLIPPED] = 1f
    }

    private fun flushSecond() {
        if (secondTicks == 0) return
        // A second made only of taps has nothing to report, and decibels(0) leaves it blank in the CSV.
        for (w in 0 until count) {
            second[w * COLUMNS_PER_WEIGHTING] = LevelMeter.decibels(secondEnergy[w] / maxOf(secondCounted, 1))
        }
        val origin = runs.firstOrNull()?.wallMillis ?: 0L
        second[ELAPSED] = (wallMillisAt(secondStart) - origin) / MILLIS_PER_SECOND
        seconds.add(second.copyOf())
        secondEnergy = DoubleArray(count)
        secondTicks = 0
        secondCounted = 0
    }

    companion object {
        /** Column in [seconds] holding 1 when the input clipped during that second. */
        const val CLIPPED = 9

        /** Column in [seconds] holding the seconds since the session began. */
        const val ELAPSED = 10
        private const val TEN = 10f

        private const val COLUMNS_PER_WEIGHTING = 3
        private const val SECOND_COLUMNS = 11
        private const val MILLIS_PER_SECOND = 1000f
        private const val SECONDS_PER_TICK = 1.0 / LevelMeter.TICKS_PER_SECOND
        private const val MILLIS_PER_TICK = 1000L / LevelMeter.TICKS_PER_SECOND
    }
}
