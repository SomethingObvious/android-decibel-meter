package com.somethingobvious.decibel

import android.media.AudioRecord
import android.os.SystemClock
import com.somethingobvious.decibel.meter.LevelMeter
import com.somethingobvious.decibel.meter.Tick

private const val WARMUP_TICKS = 10

// Blocks are measured two reads late, so a tap reported just after its thump still mutes it.
private const val TOUCH_DELAY_BLOCKS = 2
private const val TOUCH_BEFORE_MILLIS = 60L
private const val TOUCH_AFTER_MILLIS = 300L
private const val TOUCHES_KEPT = 4
private const val ALERT_REARM_DB = 3.0

/** The first ticks of a run, which settle the filters and never reach the session. */
internal const val CAPTURE_LEAD_TICKS = WARMUP_TICKS + TOUCH_DELAY_BLOCKS

/** When fingers last landed on or left the glass, which the mic hears as a thump. */
object Touches {
    @Volatile
    private var times = emptyList<Long>()

    fun mark() {
        times = (times + SystemClock.uptimeMillis()).takeLast(TOUCHES_KEPT)
    }

    /** [onClick] marking the moment first, for a TalkBack double tap, which the screen never sees as a touch. */
    fun marked(onClick: () -> Unit): () -> Unit =
        {
            mark()
            onClick()
        }

    fun cover(at: Long) = times.any { at in it - TOUCH_BEFORE_MILLIS..it + TOUCH_AFTER_MILLIS }
}

/** Fires once each time the level goes over the alert, and again only after it drops 3 dB under. */
internal class AlertWatch {
    @Volatile
    private var isArmed = true

    fun rearm() {
        isArmed = true
    }

    fun crosses(level: Double, alert: Double?): Boolean {
        val isOver = alert != null && level >= alert
        val hasCrossed = isArmed && isOver
        if (hasCrossed) {
            isArmed = false
        } else if (alert == null || level < alert - ALERT_REARM_DB) {
            isArmed = true
        }
        return hasCrossed
    }
}

/**
 * Reads the mic a tick at a time and measures each block two reads late, so taps are muted. The
 * first half second carries the mic's start-up thump, so the averages start again after it.
 */
internal class Capture(private val record: AudioRecord, source: MicSource) {
    private val meter = LevelMeter(record.sampleRate, source.clipLevel)
    private val size = record.sampleRate / LevelMeter.TICKS_PER_SECOND
    private val ring = Array(TOUCH_DELAY_BLOCKS + 1) { FloatArray(size) }
    private val lengths = IntArray(ring.size)
    private val times = LongArray(ring.size)
    private var reads = 0L
    private var warmup = WARMUP_TICKS

    /** Reads one block and hands over any ticks it completes, with whether the input was digital silence. */
    fun step(onTick: (tick: Tick, isSilent: Boolean) -> Unit): Boolean {
        val slot = (reads % ring.size).toInt()
        val read = record.read(ring[slot], 0, size, AudioRecord.READ_BLOCKING)
        if (read >= 0) {
            lengths[slot] = read
            times[slot] = SystemClock.uptimeMillis()
            reads++
            if (reads > TOUCH_DELAY_BLOCKS) measureOldest(onTick)
        }
        return read >= 0
    }

    // The slot read next holds the oldest block, and its turn to be measured has come.
    private fun measureOldest(onTick: (Tick, Boolean) -> Unit) {
        val due = (reads % ring.size).toInt()
        val samples = ring[due]
        val isSilent = (0 until lengths[due]).all { samples[it] == 0f }
        meter.process(samples, lengths[due], isMuted = isSilent || Touches.cover(times[due])) { tick ->
            when {
                warmup > 1 -> {
                    warmup--
                }

                warmup == 1 -> {
                    warmup--
                    meter.prime()
                }

                else -> {
                    onTick(tick, isSilent)
                }
            }
        }
    }
}
