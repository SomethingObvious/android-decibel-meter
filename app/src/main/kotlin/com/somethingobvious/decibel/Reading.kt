package com.somethingobvious.decibel

import android.content.Context
import android.content.res.Resources
import androidx.annotation.StringRes
import com.somethingobvious.decibel.meter.Exposure
import com.somethingobvious.decibel.meter.LevelMeter
import com.somethingobvious.decibel.meter.Weighting
import java.util.Locale
import kotlin.math.floor

/** Turns full-scale levels into the numbers and words the screen and notification show. */
object Reading {
    private const val SECONDS_PER_HOUR = 3600L
    private const val SECONDS_PER_MINUTE = 60L
    private const val MINUTES_PER_HOUR = 60
    private const val PERCENT = 100.0
    private const val TENTHS = 10.0
    private const val HALF = 0.5
    private const val ALL_DAY_SECONDS = 24.0 * 3600.0
    const val BLANK = "--.-"

    private const val VERY_QUIET_DB = 35.0
    private const val QUIET_DB = 55.0
    private const val CONVERSATION_DB = 70.0
    private const val LOUD_DB = 85.0
    private const val HARMFUL_DB = 100.0

    /** Plain words for a level, so the zone colour is never the only signal. */
    @StringRes
    fun band(db: Double): Int =
        when {
            db < VERY_QUIET_DB -> R.string.band_very_quiet
            db < QUIET_DB -> R.string.band_quiet
            db < CONVERSATION_DB -> R.string.band_conversation
            db < LOUD_DB -> R.string.band_loud
            db < HARMFUL_DB -> R.string.band_harmful_hours
            else -> R.string.band_harmful_minutes
        }

    fun offset(state: MeterState, settings: Settings): Double =
        (state.source ?: MicSource.UNPROCESSED).fullScaleDb + settings.calibrationDb

    // Rounded to the tenth on screen, so a level that reads 85.0 is named and coloured for 85 too.
    fun level(dbfs: Float, offset: Double): Double? =
        if (dbfs.isNaN() || dbfs <= LevelMeter.FLOOR_DB) null else floor((dbfs + offset) * TENTHS + HALF) / TENTHS

    fun format(value: Double?): String = value?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: BLANK

    fun format(dbfs: Float, offset: Double): String = format(level(dbfs, offset))

    fun duration(seconds: Double): String {
        val whole = seconds.toLong()
        val hours = whole / SECONDS_PER_HOUR
        val minutes = whole % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
        val rest = whole % SECONDS_PER_MINUTE
        val locale = Locale.getDefault()
        return if (hours > 0) {
            String.format(locale, "%d:%02d:%02d", hours, minutes, rest)
        } else {
            String.format(locale, "%d:%02d", minutes, rest)
        }
    }

    /** Short span for the dose line, like "2 H 10 MIN". */
    fun span(res: Resources, seconds: Double): String {
        val minutes = (seconds / SECONDS_PER_MINUTE).toInt()
        val hours = minutes / MINUTES_PER_HOUR
        val rest = minutes % MINUTES_PER_HOUR
        return when {
            hours > 0 -> {
                res.getString(
                    R.string.span_hours_minutes,
                    res.getQuantityString(R.plurals.span_hours, hours, hours),
                    res.getQuantityString(R.plurals.span_minutes, rest, rest),
                )
            }

            minutes > 0 -> {
                res.getQuantityString(R.plurals.span_minutes, minutes, minutes)
            }

            else -> {
                res.getString(R.string.span_under_minute)
            }
        }
    }

    // Rounded down, so the digits never read 100.0 while the line under them says it isn't full yet.
    fun dosePercent(state: MeterState, offset: Double): Double =
        floor(Exposure.dose(doseSeconds = state.stats.doseSeconds, offsetDb = offset) * PERCENT * TENTHS) / TENTHS

    /** What the rest of today's dose looks like at the current A-weighted Slow level. */
    fun doseOutlook(res: Resources, state: MeterState, offset: Double): String {
        val dose = Exposure.dose(doseSeconds = state.stats.doseSeconds, offsetDb = offset)
        val now = state.latest?.let { level(it.slow[Weighting.A.ordinal], offset) }
        return when {
            dose >= 1.0 -> {
                res.getString(R.string.dose_reached)
            }

            now == null -> {
                res.getString(R.string.no_reading)
            }

            else -> {
                val left = (1.0 - dose) * Exposure.allowedSeconds(now)
                if (left > ALL_DAY_SECONDS) {
                    res.getString(R.string.dose_safe)
                } else {
                    res.getString(R.string.dose_full_in, span(res, left))
                }
            }
        }
    }

    /** Meter-style names: LAF, LAeq, LCSmax and so on. */
    fun name(weighting: Weighting, response: Response, what: String = ""): String =
        "L${weighting.name}${response.letter}$what"

    fun notificationText(context: Context, state: MeterState, settings: Settings): String {
        val offset = offset(state, settings)
        val w = settings.weighting.ordinal
        val latest = state.latest
        val now = latest?.let { (if (settings.response == Response.FAST) it.fast else it.slow)[w] } ?: Float.NaN
        val label = name(settings.weighting, settings.response)
        val leq = state.stats.leq[w]
        val warning =
            when {
                state.isSilenced -> R.string.flag_mic_busy
                state.isOverLatched -> R.string.flag_over
                state.isAlertLatched -> R.string.flag_alert
                state.isGapLatched -> R.string.flag_gap
                else -> null
            }
        val text =
            context.getString(
                R.string.notification_text,
                label,
                format(now, offset),
                "L${settings.weighting.name}eq",
                format(leq, offset),
                duration(state.stats.seconds),
            )
        return warning?.let { context.getString(R.string.notification_warned, text, context.getString(it)) } ?: text
    }
}
