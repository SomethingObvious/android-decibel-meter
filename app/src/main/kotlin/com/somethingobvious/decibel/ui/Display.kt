package com.somethingobvious.decibel.ui

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.somethingobvious.decibel.Meter
import com.somethingobvious.decibel.MeterState
import com.somethingobvious.decibel.Problem
import com.somethingobvious.decibel.R
import com.somethingobvious.decibel.Reading
import com.somethingobvious.decibel.Response
import com.somethingobvious.decibel.Settings
import com.somethingobvious.decibel.Window
import com.somethingobvious.decibel.meter.LevelMeter
import com.somethingobvious.decibel.meter.Tick
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// The digits change four times a second, which is as fast as anyone can read them. The bar keeps every tick.
private const val TICKS_PER_DIGIT_CHANGE = 5

// TalkBack hears the level every ten seconds rather than on every change.
private const val TICKS_PER_ANNOUNCEMENT = 200

// The history header's 48 dp row plus its smallest strip.
private val HISTORY_RESERVE = 124.dp

// In the wide layout the readings get a little more than half, so the big digits stay big.
private const val READINGS_WEIGHT = 1.3f

private val clock = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)

@Composable
fun Display(state: MeterState, settings: Settings, isHolding: Boolean, isWide: Boolean) {
    var scrub by remember(state.session) { mutableStateOf<Int?>(null) }
    val offset = Reading.offset(state, settings)
    val ticks = state.stats.ticks
    val trace =
        Trace(
            session = state.session,
            ticks = ticks,
            column = settings.weighting.ordinal * 2 + settings.response.ordinal,
            span = historySpan(settings.window, ticks),
            offset = offset,
            alert = settings.alertDb,
        )
    // A run that has just started has no tick yet, and the digits and TalkBack drop the old one at once.
    val shownTick = remember(ticks / TICKS_PER_DIGIT_CHANGE, state.session, state.latest == null) { state.latest }
    val scrubbed = scrub?.takeIf { it < ticks }
    val level = if (scrubbed != null) trace.level(scrubbed) else live(shownTick, settings, offset)
    val digits = Reading.format(level)
    val liveLevel = live(shownTick, settings, offset)
    val res = LocalResources.current
    val periodic =
        remember(ticks / TICKS_PER_ANNOUNCEMENT, liveLevel == null, state.isRunning, state.session, settings, offset) {
            spokenLevel(res, liveLevel)
        }
    // While scrubbing every step is spoken, since the user is asking for it.
    val spoken =
        if (scrubbed != null) {
            stringResource(R.string.spoken_at, timeAt(state, scrubbed), spokenLevel(res, level))
        } else {
            periodic
        }
    val shown = Shown(digits = digits, level = level, spoken = spoken)
    val readings: @Composable ColumnScope.() -> Unit = {
        Readings(state = state, settings = settings, isHolding = isHolding, scrubbed = scrubbed, shown = shown)
    }
    val history: @Composable ColumnScope.() -> Unit = {
        HistoryPane(settings = settings, trace = trace, scrubbed = scrubbed, onScrub = { scrub = it })
    }
    if (isWide) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            val scroll = rememberScrollState()
            Column(
                Modifier
                    .weight(READINGS_WEIGHT)
                    .fadeWhenMore(scroll, LocalLcd.current.panel)
                    .verticalScroll(scroll),
                content = readings,
            )
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                content = history,
            )
        }
    } else {
        TallPanel(readings = readings, history = history)
    }
}

@Composable
private fun TallPanel(readings: @Composable ColumnScope.() -> Unit, history: @Composable ColumnScope.() -> Unit) {
    // With big text or a short window the whole panel scrolls, and the history keeps a strip it can draw in.
    var readingsHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val historyHeight = (maxHeight - readingsHeight).coerceAtLeast(HISTORY_RESERVE)
        val scroll = rememberScrollState()
        Column(
            Modifier
                .fillMaxSize()
                .fadeWhenMore(scroll, LocalLcd.current.panel)
                .verticalScroll(scroll),
        ) {
            Column(
                Modifier.onSizeChanged { readingsHeight = with(density) { it.height.toDp() } },
                content = readings,
            )
            Column(Modifier.height(historyHeight), content = history)
        }
    }
}

private fun live(tick: Tick?, settings: Settings, offset: Double): Double? =
    tick?.let { shown ->
        val w = settings.weighting.ordinal
        Reading.level(if (settings.response == Response.SLOW) shown.slow[w] else shown.fast[w], offset)
    }

@Composable
private fun ColumnScope.Readings(
    state: MeterState,
    settings: Settings,
    isHolding: Boolean,
    scrubbed: Int?,
    shown: Shown,
) {
    val offset = Reading.offset(state, settings)
    val isScrubbing = scrubbed != null
    val max = if (settings.response == Response.SLOW) state.stats.slowMax else state.stats.fastMax
    StatusRow(state = state, isHolding = isHolding, scrubbed = scrubbed)
    Readout(
        problem = state.problem,
        settings = settings,
        reading = shown,
        dimmed = !state.isRunning && !isScrubbing,
    )
    BarGraph(
        level = live(state.latest, settings, offset),
        max = Reading.level(max[settings.weighting.ordinal], offset),
        alert = settings.alertDb,
        dimmed = isScrubbing || !state.isRunning,
    )
    Parameters(state = state, settings = settings, offset = offset, dimmed = isScrubbing)
}

private fun timeAt(state: MeterState, tick: Int): String =
    clock.format(Instant.ofEpochMilli(state.session.wallMillisAt(tick)).atZone(ZoneId.systemDefault()))

/** What the big readout shows: the digits, the level they stand for, and the slower copy TalkBack reads. */
private data class Shown(val digits: String, val level: Double?, val spoken: String)

private fun historySpan(window: Window, ticks: Int): Int =
    if (window == Window.SESSION) {
        maxOf(ticks, Window.MINUTE.seconds * LevelMeter.TICKS_PER_SECOND)
    } else {
        window.seconds * LevelMeter.TICKS_PER_SECOND
    }

@Composable
private fun StatusRow(state: MeterState, isHolding: Boolean, scrubbed: Int?) {
    val status =
        when {
            scrubbed != null -> {
                stringResource(R.string.status_at, timeAt(state, scrubbed))
            }

            state.isRunning -> {
                stringResource(R.string.status_run, Reading.duration(state.stats.seconds))
            }

            else -> {
                stringResource(R.string.status_paused, Reading.duration(state.stats.seconds))
            }
        }
    // HOLD clears what this screen showed, so a warning that lit a moment later still gets seen.
    val isLatched = state.isOverLatched || state.isAlertLatched || state.isGapLatched
    val acknowledge = { Meter.acknowledge(state) }.takeIf { isLatched }
    FlagRow(
        left = status,
        flags =
            listOf(
                stringResource(R.string.hold_label) to isHolding,
                stringResource(if (state.isSilenced) R.string.flag_mic_busy else R.string.flag_gap) to
                    (state.isSilenced || state.isGapLatched),
                stringResource(R.string.flag_over) to state.isOverLatched,
                stringResource(R.string.flag_alert) to state.isAlertLatched,
            ),
        warnings =
            listOfNotNull(
                stringResource(R.string.holding).takeIf { isHolding },
                stringResource(R.string.spoken_mic_busy).takeIf { state.isSilenced },
                stringResource(R.string.spoken_gap).takeIf { state.isGapLatched && !state.isSilenced },
                stringResource(R.string.spoken_over).takeIf { state.isOverLatched },
                stringResource(R.string.spoken_alert).takeIf { state.isAlertLatched },
            ),
        onAcknowledge = acknowledge,
    )
}

@Composable
private fun Readout(problem: Problem, settings: Settings, reading: Shown, dimmed: Boolean) {
    val name = Reading.name(settings.weighting, settings.response)
    val spokenName = stringResource(settings.response.spokenSetting, settings.weighting.name)
    val spokenReading = stringResource(R.string.spoken_reading, reading.spoken, spokenName)
    val lcd = LocalLcd.current
    when (problem) {
        Problem.NO_PERMISSION -> {
            Notice(title = stringResource(R.string.no_mic_title), body = stringResource(R.string.no_mic_body))
        }

        Problem.MIC_FAILED -> {
            Notice(
                title = stringResource(R.string.mic_failed_title),
                body = stringResource(R.string.mic_failed_body),
            )
        }

        Problem.OTHER_MIC -> {
            Notice(title = stringResource(R.string.other_mic_title), body = stringResource(R.string.other_mic_body))
        }

        Problem.LOG_FULL -> {
            Notice(title = stringResource(R.string.log_full_title), body = stringResource(R.string.log_full_body))
        }

        Problem.NONE -> {
            MainReading(
                label = name,
                detail = stringResource(settings.response.detail, settings.weighting.name),
                value = reading.digits,
                level = reading.level,
                dimmed = dimmed,
                modifier =
                    Modifier.clearAndSetSemantics {
                        contentDescription = spokenReading
                        liveRegion = LiveRegionMode.Polite
                    },
            )
            Row(
                Modifier
                    .heightIn(min = 24.dp)
                    .clearAndSetSemantics { },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                reading.level?.let { level ->
                    Flag(text = stringResource(Reading.band(level)), color = if (dimmed) lcd.dim else lcd.zone(level))
                }
            }
        }
    }
}

@Composable
private fun Notice(title: String, body: String) {
    val spoken = stringResource(R.string.spoken_sentences, title, body)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp)
            .clearAndSetSemantics {
                contentDescription = spoken
                liveRegion = LiveRegionMode.Assertive
            },
    ) {
        Text(title, style = lcdText(30.sp, FontWeight.Bold))
        Text(
            body,
            style = lcdText(15.sp, FontWeight.Normal, LocalLcd.current.dim),
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun Parameters(state: MeterState, settings: Settings, offset: Double, dimmed: Boolean) {
    val w = settings.weighting.ordinal
    val isSlow = settings.response == Response.SLOW
    val weight = settings.weighting.name
    val stats = state.stats
    Column(Modifier.padding(top = 6.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(if (dimmed) LocalLcd.current.dim else LocalLcd.current.ink),
        )
        ParamRow(
            name = "L${weight}eq",
            spokenName = stringResource(R.string.spoken_leq, weight),
            value = Reading.format(stats.leq[w], offset),
            dimmed = dimmed,
        )
        ParamRow(
            name = Reading.name(settings.weighting, settings.response, "max"),
            spokenName = stringResource(settings.response.spokenMax, weight),
            value = Reading.format((if (isSlow) stats.slowMax else stats.fastMax)[w], offset),
            dimmed = dimmed,
        )
        ParamRow(
            name = Reading.name(settings.weighting, settings.response, "min"),
            spokenName = stringResource(settings.response.spokenMin, weight),
            value = Reading.format((if (isSlow) stats.slowMin else stats.fastMin)[w], offset),
            dimmed = dimmed,
        )
        ParamRow(
            name = "L${weight}peak",
            spokenName = stringResource(R.string.spoken_peak, weight),
            value = Reading.format(stats.peak[w], offset),
            dimmed = dimmed,
        )
        ParamRow(
            name = stringResource(R.string.param_dose),
            spokenName = stringResource(R.string.spoken_dose),
            value = Reading.format(Reading.dosePercent(state, offset)),
            isPercent = true,
            dimmed = dimmed,
        )
        Text(
            Reading.doseOutlook(LocalResources.current, state, offset),
            style = lcdText(12.sp, FontWeight.Medium, LocalLcd.current.dim),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private fun spokenLevel(res: Resources, db: Double?): String =
    if (db == null) {
        res.getString(R.string.spoken_no_reading)
    } else {
        res.getString(R.string.spoken_level, Reading.format(db), res.getString(Reading.band(db)))
    }
