package com.somethingobvious.decibel.meter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class MeterTest {
    private val rate = 48_000
    private val a = Weighting.A.ordinal
    private val c = Weighting.C.ordinal
    private val z = Weighting.Z.ordinal

    private fun tone(hz: Double, amplitude: Double, seconds: Double) =
        FloatArray((rate * seconds).toInt()) { (amplitude * sin(2.0 * PI * hz * it / rate)).toFloat() }

    private fun measure(vararg parts: FloatArray): List<Tick> {
        val meter = LevelMeter(rate)
        val ticks = mutableListOf<Tick>()
        for (part in parts) meter.process(part, part.size) { ticks += it }
        return ticks
    }

    private fun settledLeq(ticks: List<Tick>, weighting: Int) =
        ticks.drop(SETTLE_TICKS).map { it.leq[weighting].toDouble() }.average()

    @Test
    fun `weightings follow IEC 61672 at its table frequencies`() {
        // Nominal values from the standard. The bilinear transform drifts up to 0.65 dB by 8 kHz.
        val table =
            listOf(
                31.5 to (-39.4 to -3.0),
                63.0 to (-26.2 to -0.8),
                100.0 to (-19.1 to -0.3),
                250.0 to (-8.6 to 0.0),
                1000.0 to (0.0 to 0.0),
                4000.0 to (1.0 to -0.8),
                8000.0 to (-1.1 to -3.0),
            )
        for ((hz, expected) in table) {
            val ticks = measure(tone(hz = hz, amplitude = AMPLITUDE, seconds = 2.0))
            val flat = settledLeq(ticks, z)
            assertEquals("A at $hz Hz", expected.first, settledLeq(ticks, a) - flat, TOLERANCE_DB)
            assertEquals("C at $hz Hz", expected.second, settledLeq(ticks, c) - flat, TOLERANCE_DB)
        }
    }

    @Test
    fun `a sine reads its RMS and its peak`() {
        val ticks = measure(tone(hz = 1000.0, amplitude = AMPLITUDE, seconds = 1.0))
        // 0.1 peak is -20 dBFS, and its RMS is 3.01 dB under that.
        assertEquals(-23.01, settledLeq(ticks, z), 0.02)
        assertEquals(-20.0, ticks.last().peak[z].toDouble(), 0.05)
        assertEquals(-23.01, ticks.last().fast[z].toDouble(), 0.1)
        assertFalse(ticks.any { it.isClipped })
    }

    @Test
    fun `fast and slow decay at their time constants`() {
        val ticks = measure(tone(hz = 1000.0, amplitude = AMPLITUDE, seconds = 3.0), FloatArray(rate))
        val before = ticks[3 * LevelMeter.TICKS_PER_SECOND - 1]
        val half = ticks[3 * LevelMeter.TICKS_PER_SECOND + LevelMeter.TICKS_PER_SECOND / 2 - 1]
        // An exponential average falls 10 log10(e) / tau dB per second: 34.7 for Fast, 4.34 for Slow.
        assertEquals(-17.37, (half.fast[z] - before.fast[z]).toDouble(), 0.3)
        assertEquals(-2.17, (half.slow[z] - before.slow[z]).toDouble(), 0.1)
    }

    @Test
    fun `the first tick starts settled instead of climbing from silence`() {
        val first = measure(tone(hz = 1000.0, amplitude = AMPLITUDE, seconds = 0.05)).single()
        assertEquals(-23.01, first.slow[z].toDouble(), 0.1)
    }

    @Test
    fun `full scale input is flagged as clipped`() {
        assertTrue(measure(tone(hz = 1000.0, amplitude = 1.0, seconds = 0.1)).any { it.isClipped })
    }

    @Test
    fun `an hour at 94 dB is a full NIOSH dose`() {
        val session = Session()
        val offset = 130.0
        val level = (94.0 - offset).toFloat()
        val tick =
            Tick(
                fast = FloatArray(3) { level },
                slow = FloatArray(3) { level },
                leq = FloatArray(3) { level },
                peak = FloatArray(3) { level },
                isClipped = false,
            )
        repeat(3600 * LevelMeter.TICKS_PER_SECOND) { session.add(tick) }
        assertEquals(1.0, Exposure.dose(doseSeconds = session.stats.doseSeconds, offsetDb = offset), 1e-6)
        assertEquals(3600.0, Exposure.allowedSeconds(94.0), 1e-6)
        assertEquals(3600.0, session.stats.seconds, 1e-9)
    }

    @Test
    fun `session leq averages energy, not decibels`() {
        val session = Session()
        for (level in listOf(-40f, -20f)) {
            val tick =
                Tick(
                    fast = FloatArray(3) { level },
                    slow = FloatArray(3) { level },
                    leq = FloatArray(3) { level },
                    peak = FloatArray(3) { level },
                    isClipped = false,
                )
            repeat(10) { session.add(tick) }
        }
        assertEquals(-22.97, session.stats.leq[a].toDouble(), 0.01)
        assertEquals(-40f, session.stats.fastMin[a])
        assertEquals(-20f, session.stats.fastMax[a])
        assertEquals(1, session.seconds.size)
    }

    @Test
    fun `a tap is drawn but left out of the figures`() {
        fun tick(level: Float) =
            Tick(
                fast = FloatArray(3) { level },
                slow = FloatArray(3) { level },
                leq = FloatArray(3) { level },
                peak = FloatArray(3) { level },
                isClipped = false,
            )
        val session = Session()
        repeat(19) { session.add(tick(-40f)) }
        session.add(tick = tick(0f), isCounted = false)
        assertEquals(20, session.stats.ticks)
        assertEquals(19, session.stats.counted)
        assertEquals(-40f, session.stats.fastMax[a])
        assertEquals(-40.0, session.stats.leq[a].toDouble(), 0.01)
        assertEquals(-40.0, session.seconds[0, 0].toDouble(), 0.01)
        assertEquals(0f, session.history[19, 0])
    }

    @Test
    fun `muted samples leave the averages where they were`() {
        val meter = LevelMeter(rate)
        val ticks = mutableListOf<Tick>()
        val quiet = tone(hz = 1000.0, amplitude = AMPLITUDE, seconds = 1.0)
        val tap = tone(hz = 1000.0, amplitude = 1.0, seconds = 0.2)
        meter.process(quiet, quiet.size) { ticks += it }
        meter.process(tap, tap.size, isMuted = true) { ticks += it }
        val muted = ticks.takeLast(tap.size / (rate / LevelMeter.TICKS_PER_SECOND))
        assertTrue(muted.all { it.isMuted })
        assertTrue(muted.all { abs(it.slow[z] + 23.01f) < 0.1f })
        assertTrue(muted.all { abs(it.fastMax[z] + 23.01f) < 0.2f })
    }

    @Test
    fun `fast max catches a burst shorter than a tick`() {
        val size = rate / LevelMeter.TICKS_PER_SECOND
        // Loud for the first fifth of the tick, then silent, so Fast falls for the 40 ms left: 34.7 dB/s, 1.39 dB.
        val burst = FloatArray(size) { if (it < size / 5) (0.5 * sin(2.0 * PI * 1000.0 * it / rate)).toFloat() else 0f }
        val quiet = tone(hz = 1000.0, amplitude = AMPLITUDE / 10, seconds = 0.5)
        val tick = measure(quiet, burst)[quiet.size / size]
        assertEquals(1.39, (tick.fastMax[z] - tick.fast[z]).toDouble(), 0.1)
    }

    @Test
    fun `a lower clip level flags quieter input`() {
        val meter = LevelMeter(sampleRate = rate, clipLevel = 0.5)
        val ticks = mutableListOf<Tick>()
        val loud = tone(hz = 1000.0, amplitude = 0.6, seconds = 0.1)
        meter.process(loud, loud.size) { ticks += it }
        assertTrue(ticks.any { it.isClipped })
        assertFalse(measure(loud).any { it.isClipped })
    }

    @Test
    fun `the match uses the middle of the recent levels`() {
        val session = Session()
        assertEquals(null, session.recentMedian(column = 0, rows = 5))
        for (level in listOf(-90f, -40f, -41f, 0f, -39f, -40f)) {
            session.add(
                Tick(
                    fast = FloatArray(3) { level },
                    slow = FloatArray(3) { level },
                    leq = FloatArray(3) { level },
                    peak = FloatArray(3) { level },
                    isClipped = false,
                ),
            )
        }
        assertEquals(-40f, session.recentMedian(column = 0, rows = 5))
    }

    @Test
    fun `the match only uses the run going now`() {
        val session = Session()
        val quiet =
            Tick(
                fast = FloatArray(3) { -40f },
                slow = FloatArray(3) { -40f },
                leq = FloatArray(3) { -40f },
                peak = FloatArray(3) { -40f },
                isClipped = false,
            )
        session.begin(wallMillis = 0L)
        repeat(5) { session.add(quiet) }
        session.begin(wallMillis = 60_000L)
        session.add(quiet)
        assertEquals(null, session.recentMedian(column = 0, rows = 5))
    }

    @Test
    fun `a gap is logged as nothing heard`() {
        val session = Session()
        val loud =
            Tick(
                fast = FloatArray(3) { -10f },
                slow = FloatArray(3) { -10f },
                leq = FloatArray(3) { -10f },
                peak = FloatArray(3) { -10f },
                isClipped = false,
            )
        session.add(tick = loud, isCounted = false, isGap = true)
        assertEquals(LevelMeter.FLOOR_DB, session.history[0, 0])
        assertEquals(0, session.stats.counted)
    }

    @Test
    fun `series rows survive a chunk boundary`() {
        val series = Series(2)
        repeat(72_001) { series.add(floatArrayOf(it.toFloat(), -it.toFloat())) }
        assertEquals(71_999f, series[71_999, 0])
        assertEquals(-72_000f, series[72_000, 1])
    }

    @Test
    fun `tick times follow each run of measuring`() {
        val session = Session()
        val tick =
            Tick(
                fast = FloatArray(3),
                slow = FloatArray(3),
                leq = FloatArray(3),
                peak = FloatArray(3),
                isClipped = false,
            )
        session.begin(1_000L)
        repeat(40) { session.add(tick) }
        session.begin(60_000L)
        repeat(20) { session.add(tick) }
        assertEquals(2_950L, session.wallMillisAt(39))
        assertEquals(60_500L, session.wallMillisAt(50))
    }

    private companion object {
        const val AMPLITUDE = 0.1
        const val TOLERANCE_DB = 0.7
        const val SETTLE_TICKS = 10
    }
}
