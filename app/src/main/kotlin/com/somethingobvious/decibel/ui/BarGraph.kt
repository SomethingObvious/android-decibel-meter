package com.somethingobvious.decibel.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil

private const val LABEL_START = 30
private const val LABEL_STEP = 20
private val LABEL_SIZE = 12.sp
private const val LABEL_LINE = 1.3f
private const val SEGMENT_DB = 2
private const val UNLIT_ALPHA = 0.07f
private val FRAME_INSET = 3.dp
private val BAR_HEIGHT = 16.dp

// The 6 dp gap above, the framed bar and the 7 dp under it for the limit notch.
private val BAR_AND_TICK = 35.dp

@Composable
fun BarGraph(level: Double?, max: Double?, alert: Double?, dimmed: Boolean) {
    val lcd = LocalLcd.current
    val measurer = rememberTextMeasurer()
    val labelStyle = lcdText(LABEL_SIZE, FontWeight.Medium, lcd.dim)
    val labelHeight = with(LocalDensity.current) { (LABEL_SIZE * LABEL_LINE).toDp() }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(BAR_AND_TICK + labelHeight)
            .padding(top = 6.dp),
    ) {
        val inset = FRAME_INSET.toPx()
        val frameBottom = BAR_HEIGHT.toPx() + inset * 2
        val ink = if (dimmed) lcd.dim else lcd.ink
        val stroke = 1.5.dp.toPx()
        drawRect(
            color = ink,
            topLeft = Offset(x = stroke / 2, y = stroke / 2),
            size = Size(width = size.width - stroke, height = frameBottom - stroke),
            style = Stroke(stroke),
        )
        drawSegments(
            level = level,
            lit = { db -> if (dimmed) lcd.dim else lcd.zone(db) },
            unlit = lcd.ink.copy(alpha = UNLIT_ALPHA),
        )
        drawMarks(max = max, alert = alert, ink = ink, caution = lcd.caution)
        drawLabels(measurer = measurer, style = labelStyle, y = frameBottom + 6.dp.toPx())
    }
}

// The held max crosses the frame, the 85 dB limit notches its bottom edge and the alert cuts through it.
private fun DrawScope.drawMarks(max: Double?, alert: Double?, ink: Color, caution: Color) {
    val inset = FRAME_INSET.toPx()
    val frameBottom = BAR_HEIGHT.toPx() + inset * 2
    if (max != null && max >= SCALE_LOW_DB) {
        val x = xFor(max.toFloat())
        drawLine(
            color = ink,
            start = Offset(x = x, y = -inset),
            end = Offset(x = x, y = frameBottom + inset),
            strokeWidth = 3.dp.toPx(),
        )
    }
    val mark = xFor(LIMIT_DB)
    drawLine(
        color = ink,
        start = Offset(x = mark, y = frameBottom - inset),
        end = Offset(x = mark, y = frameBottom + 5.dp.toPx()),
        strokeWidth = 2.dp.toPx(),
    )
    if (alert != null) {
        val x = xFor(alert.toFloat())
        drawLine(
            color = caution,
            start = Offset(x = x, y = -2.dp.toPx()),
            end = Offset(x = x, y = frameBottom + 2.dp.toPx()),
            strokeWidth = 2.dp.toPx(),
        )
    }
}

// Every 20 dB, skipping some when the bar is too narrow for them all. The end labels are pulled in
// to stay on the bar, so a label that would still run into the one before it is left out.
private fun DrawScope.drawLabels(measurer: TextMeasurer, style: TextStyle, y: Float) {
    val gap = 6.dp.toPx()
    val widest = measurer.measure(text = SCALE_HIGH_DB.toInt().toString(), style = style, density = this).size.width
    val spacing = xFor((LABEL_START + LABEL_STEP).toFloat()) - xFor(LABEL_START.toFloat())
    val every = ceil((widest + gap) / spacing).toInt().coerceAtLeast(1)
    var lastRight = Float.NEGATIVE_INFINITY
    for ((i, db) in (LABEL_START..SCALE_HIGH_DB.toInt() step LABEL_STEP).withIndex()) {
        val layout = measurer.measure(text = db.toString(), style = style, density = this)
        val left =
            (xFor(db.toFloat()) - layout.size.width / 2f)
                .coerceIn(minimumValue = 0f, maximumValue = size.width - layout.size.width)
        if (i % every == 0 && left >= lastRight + gap) {
            drawText(layout, topLeft = Offset(x = left, y = y))
            lastRight = left + layout.size.width
        }
    }
}

// Inside the frame, so 30 and 130 sit on its inner edges.
private fun DrawScope.xFor(db: Float): Float {
    val inset = FRAME_INSET.toPx()
    return inset + scaleFraction(db) * (size.width - inset * 2)
}

// One segment per 2 dB, lit up to the level.
private fun DrawScope.drawSegments(level: Double?, lit: (Double) -> Color, unlit: Color) {
    val inset = FRAME_INSET.toPx()
    val segments = ((SCALE_HIGH_DB - SCALE_LOW_DB) / SEGMENT_DB).toInt()
    val gap = 2.5.dp.toPx()
    val segment = (size.width - inset * 2 - gap * (segments - 1)) / segments
    for (i in 0 until segments) {
        val db = SCALE_LOW_DB + i * SEGMENT_DB
        val isLit = level != null && db < level
        drawRect(
            color = if (isLit) lit(db.toDouble()) else unlit,
            topLeft = Offset(x = inset + i * (segment + gap), y = inset),
            size = Size(width = segment, height = BAR_HEIGHT.toPx()),
        )
    }
}
