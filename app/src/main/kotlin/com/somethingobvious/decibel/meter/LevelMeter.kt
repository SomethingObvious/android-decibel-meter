package com.somethingobvious.decibel.meter

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max

/** One 50 ms step of the meter. Levels are dB relative to digital full scale, indexed by [Weighting.ordinal]. */
data class Tick(
    val fast: FloatArray,
    val slow: FloatArray,
    val leq: FloatArray,
    val peak: FloatArray,
    val isClipped: Boolean,
    /** The highest Fast level at any sample in the tick. */
    val fastMax: FloatArray = fast,
    /** The phone's own noise was held out of the averages, so the tick counts for nothing. */
    val isMuted: Boolean = false,
)

/**
 * Turns raw samples into [Tick]s: each weighting is filtered, then squared into an exponential
 * average with the Fast (125 ms) and Slow (1 s) time constants, an energy sum and a peak.
 *
 * [clipLevel] is the sample size, relative to full scale, past which the reading can't be trusted.
 */
class LevelMeter(sampleRate: Int, private val clipLevel: Double = CLIP_LEVEL) {
    private val filters = Weighting.entries.map { WeightingFilter(it, sampleRate) }
    private val fastAlpha = 1.0 - exp(-1.0 / (sampleRate * FAST_SECONDS))
    private val slowAlpha = 1.0 - exp(-1.0 / (sampleRate * SLOW_SECONDS))
    private val count = Weighting.entries.size
    private val fast = DoubleArray(count)
    private val slow = DoubleArray(count)
    private val fastTop = DoubleArray(count)
    private val energy = DoubleArray(count)
    private val peak = DoubleArray(count)
    private val held = DoubleArray(count)
    private val tickLength = sampleRate / TICKS_PER_SECOND
    private var filled = 0
    private var hasClipped = false
    private var hasMuted = false
    private var isPrimed = false

    /**
     * Measures [length] samples. While [isMuted] the averages are fed the last clean level instead of
     * the samples, so a tap on the glass never reaches Fast, Slow or their maxima.
     */
    fun process(samples: FloatArray, length: Int, isMuted: Boolean = false, onTick: (Tick) -> Unit) {
        for (i in 0 until length) {
            val x = samples[i].toDouble()
            if (abs(x) >= clipLevel) hasClipped = true
            if (isMuted) hasMuted = true
            for (w in 0 until count) {
                val y = filters[w].process(x)
                val square = y * y
                val fed = if (isMuted) held[w] else square
                fast[w] += fastAlpha * (fed - fast[w])
                slow[w] += slowAlpha * (fed - slow[w])
                fastTop[w] = max(fastTop[w], fast[w])
                energy[w] += square
                peak[w] = max(peak[w], abs(y))
            }
            filled++
            if (filled == tickLength) onTick(emit())
        }
    }

    /** Starts the averages again from the next tick, so whatever came before leaves nothing behind. */
    fun prime() {
        isPrimed = false
    }

    private fun emit(): Tick {
        if (!isPrimed && !hasMuted) {
            // Start both averages at this tick's energy instead of climbing out of silence for a second.
            for (w in 0 until count) {
                fast[w] = energy[w] / filled
                slow[w] = fast[w]
                fastTop[w] = fast[w]
                held[w] = fast[w]
            }
            isPrimed = true
        }
        if (!hasMuted) for (w in 0 until count) held[w] = energy[w] / filled
        val tick =
            Tick(
                fast = FloatArray(count) { decibels(fast[it]) },
                slow = FloatArray(count) { decibels(slow[it]) },
                leq = FloatArray(count) { decibels(energy[it] / filled) },
                peak = FloatArray(count) { decibels(peak[it] * peak[it]) },
                isClipped = hasClipped,
                fastMax = FloatArray(count) { decibels(fastTop[it]) },
                isMuted = hasMuted,
            )
        energy.fill(0.0)
        peak.fill(0.0)
        for (w in 0 until count) fastTop[w] = fast[w]
        filled = 0
        hasClipped = false
        hasMuted = false
        return tick
    }

    companion object {
        const val TICKS_PER_SECOND = 20
        const val FAST_SECONDS = 0.125
        const val SLOW_SECONDS = 1.0

        // A sample this close to full scale means the converter ran out of range.
        const val CLIP_LEVEL = 0.989

        // Floor for silence, well under any real mic's self-noise.
        const val FLOOR_DB = -130f

        private const val DB_PER_DECADE = 10.0

        fun decibels(meanSquare: Double): Float =
            if (meanSquare > 0.0) max(FLOOR_DB, (DB_PER_DECADE * log10(meanSquare)).toFloat()) else FLOOR_DB
    }
}
