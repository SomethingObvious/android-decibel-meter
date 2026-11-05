package com.somethingobvious.decibel.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.somethingobvious.decibel.Reading
import com.somethingobvious.decibel.meter.Session
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

private const val GRID_START = 40
private const val GRID_END = 120
private const val GRID_STEP = 20
private const val MAX_SAMPLES_PER_COLUMN = 16
private val TICK_LENGTH = 6.dp
private val FLOOR_TRACE = 2.dp

@Composable
fun History(trace: Trace, scrub: Int?, onScrub: (Int?) -> Unit, modifier: Modifier = Modifier) {
    val lcd = LocalLcd.current
    val measurer = rememberTextMeasurer()
    val labelStyle = lcdText(11.sp, FontWeight.Medium, lcd.dim)
    val current by rememberUpdatedState(trace)
    val scrubTo by rememberUpdatedState(onScrub)
    Canvas(
        modifier
            .fillMaxWidth()
            .pointerInput(Unit) { scrubGestures(trace = { current }, onScrub = { scrubTo(it) }) },
    ) {
        val stroke = 1.5.dp.toPx()
        val bottom = size.height - stroke

        fun y(db: Float) = stroke + (bottom - stroke) * (1f - scaleFraction(db))
        drawRect(
            color = lcd.ink,
            topLeft = Offset(x = stroke / 2, y = stroke / 2),
            size = Size(width = size.width - stroke, height = size.height - stroke),
            style = Stroke(stroke),
        )
        drawLevels(
            trace = trace,
            color = { db -> if (scrub != null) lcd.dim else lcd.zone(db) },
            bottom = bottom,
            y = ::y,
        )
        drawGridTicks(color = lcd.ink, y = ::y)
        drawGridLabels(measurer = measurer, labelStyle = labelStyle, panel = lcd.panel, y = ::y)
        val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
        drawRule(color = lcd.ink, y = y(LIMIT_DB), strokeWidth = 1.dp.toPx(), pathEffect = dash)
        trace.alert?.let { alert ->
            drawRule(color = lcd.caution, y = y(alert.toFloat()), strokeWidth = 2.dp.toPx(), pathEffect = dash)
        }
        if (scrub != null) {
            val x = (scrub - trace.start).toFloat() / trace.span * size.width
            drawLine(
                color = lcd.ink,
                start = Offset(x = x, y = 0f),
                end = Offset(x = x, y = size.height),
                strokeWidth = 2.dp.toPx(),
            )
        }
    }
}

/** What the history graph draws: one column of [session.history], over the last [span] ticks. */
class Trace(
    val session: Session,
    val ticks: Int,
    val column: Int,
    val span: Int,
    val offset: Double,
    val alert: Double?,
) {
    val start: Int get() = ticks - span

    /** The level at [tick], or null where the log has a gap. */
    fun level(tick: Int): Double? = Reading.level(session.history[tick, column], offset)
}

// Reports the tick under the finger while it is down, then null once it lifts.
private suspend fun PointerInputScope.scrubGestures(trace: () -> Trace, onScrub: (Int?) -> Unit) {
    fun at(x: Float): Int? {
        val t = trace()
        val tick = t.start + (x / size.width * t.span).roundToInt()
        return tick.takeIf { t.ticks > 0 }?.coerceIn(minimumValue = max(0, t.start), maximumValue = t.ticks - 1)
    }
    awaitEachGesture {
        val down = awaitFirstDown()
        onScrub(at(down.position.x))
        down.consume()
        do {
            val event = awaitPointerEvent()
            event.changes.forEach { change ->
                if (change.pressed) onScrub(at(change.position.x))
                change.consume()
            }
        } while (event.changes.any { it.pressed })
        onScrub(null)
    }
}

private fun DrawScope.drawGridTicks(color: Color, y: (Float) -> Float) {
    for (db in GRID_START..GRID_END step GRID_STEP) {
        val top = y(db.toFloat())
        drawLine(
            color = color,
            start = Offset(x = 0f, y = top),
            end = Offset(x = TICK_LENGTH.toPx(), y = top),
            strokeWidth = 1.5.dp.toPx(),
        )
    }
}

// Drawn after the bars, on a patch of panel, so a full window never hides the scale.
private fun DrawScope.drawGridLabels(measurer: TextMeasurer, labelStyle: TextStyle, panel: Color, y: (Float) -> Float) {
    val first = measurer.measure(text = GRID_START.toString(), style = labelStyle, density = this)
    val spacing = y(GRID_START.toFloat()) - y((GRID_START + GRID_STEP).toFloat())
    val every = ceil((first.size.height + 2.dp.toPx()) / spacing).toInt().coerceAtLeast(1)
    for ((i, db) in (GRID_START..GRID_END step GRID_STEP).withIndex()) {
        if (i % every != 0) continue
        val top = y(db.toFloat())
        val label = measurer.measure(text = db.toString(), style = labelStyle, density = this)
        val left = TICK_LENGTH.toPx() + 2.dp.toPx()
        val labelTop = top - label.size.height / 2f
        drawRect(
            color = panel,
            topLeft = Offset(x = left, y = labelTop),
            size = Size(width = label.size.width + 4.dp.toPx(), height = label.size.height.toFloat()),
        )
        drawText(label, topLeft = Offset(x = left + 2.dp.toPx(), y = labelTop))
    }
}

private fun DrawScope.drawRule(color: Color, y: Float, strokeWidth: Float, pathEffect: PathEffect) {
    drawLine(
        color = color,
        start = Offset(x = 0f, y = y),
        end = Offset(x = size.width, y = y),
        strokeWidth = strokeWidth,
        pathEffect = pathEffect,
    )
}

// One bar per column, as tall as the loudest tick it covers.
private fun DrawScope.drawLevels(trace: Trace, color: (Double) -> Color, bottom: Float, y: (Float) -> Float) {
    // Whole pixels, so every column and every gap is the same width and the bars don't shimmer.
    val columnWidth = max(2f, 1.5.dp.roundToPx().toFloat())
    val left = 1.5.dp.toPx()
    val columns = ((size.width - left * 2) / columnWidth).toInt()
    for (c in 0 until columns) {
        val from = max(0, trace.start + (c.toLong() * trace.span / columns).toInt())
        val to = minOf(trace.ticks, trace.start + ((c + 1L) * trace.span / columns).toInt())
        // Samples at most 16 ticks a column, so a peak shorter than a column can hide in a very long window.
        val stride = max(1, (to - from) / MAX_SAMPLES_PER_COLUMN)
        var peak: Double? = null
        var tick = from
        while (tick < to) {
            val level = trace.level(tick)
            if (level != null) peak = maxOf(level, peak ?: level)
            tick += stride
        }
        val loudest = peak ?: continue
        // At the floor of the scale a bar would have no height, so it keeps a sliver to show the room was measured.
        val top = minOf(y(loudest.toFloat()), bottom - FLOOR_TRACE.toPx())
        drawRect(
            color = color(loudest),
            topLeft = Offset(x = left + c * columnWidth, y = top),
            size = Size(width = columnWidth - 1f, height = bottom - top),
        )
    }
}
