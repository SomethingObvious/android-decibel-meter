package com.somethingobvious.decibel.meter

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/** Frequency weightings from IEC 61672-1. */
enum class Weighting { A, C, Z }

/**
 * A or C weighting as a chain of first-order sections, one per analog pole.
 *
 * Each pole goes through the bilinear transform prewarped to its own frequency. At 48 kHz that
 * stays within 0.7 dB of the analog curve up to 10 kHz, inside the Class 1 tolerances.
 */
class WeightingFilter(weighting: Weighting, sampleRate: Int) {
    private val sections: Array<Section>
    private val gain: Double

    init {
        val k = 2.0 * sampleRate
        val poles =
            when (weighting) {
                Weighting.A -> listOf(F1 to true, F1 to true, F2 to true, F3 to true, F4 to false, F4 to false)
                Weighting.C -> listOf(F1 to true, F1 to true, F4 to false, F4 to false)
                Weighting.Z -> emptyList()
            }
        sections =
            Array(poles.size) { i ->
                val (hz, highPass) = poles[i]
                Section.bilinear(w = k * tan(PI * hz / sampleRate), k = k, highPass = highPass)
            }
        gain = 1.0 / response(REFERENCE_HZ, sampleRate)
    }

    fun process(sample: Double): Double {
        var y = sample * gain
        for (section in sections) y = section.process(y)
        return y
    }

    /** Linear magnitude of the filter at [hz], before the 1 kHz normalisation. */
    fun response(hz: Double, sampleRate: Int): Double {
        val w = 2.0 * PI * hz / sampleRate
        return sections.fold(1.0) { acc, s ->
            val num = hypot(s.b0 + s.b1 * cos(w), -s.b1 * sin(w))
            val den = hypot(1.0 + s.a1 * cos(w), -s.a1 * sin(w))
            acc * num / den
        }
    }

    private class Section(val b0: Double, val b1: Double, val a1: Double) {
        private var x1 = 0.0
        private var y1 = 0.0

        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 - a1 * y1
            x1 = x
            y1 = y
            return y
        }

        companion object {
            /** s / (s + w) when [highPass], else w / (s + w), with s = k (1 - 1/z) / (1 + 1/z). */
            fun bilinear(w: Double, k: Double, highPass: Boolean): Section {
                val norm = k + w
                val a1 = (w - k) / norm
                return if (highPass) {
                    Section(b0 = k / norm, b1 = -k / norm, a1 = a1)
                } else {
                    Section(b0 = w / norm, b1 = w / norm, a1 = a1)
                }
            }
        }
    }

    private companion object {
        // Pole frequencies in Hz from IEC 61672-1 Annex E.
        const val F1 = 20.598997
        const val F2 = 107.65265
        const val F3 = 737.86223
        const val F4 = 12_194.217
        const val REFERENCE_HZ = 1000.0
    }
}
