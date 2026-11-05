package com.somethingobvious.decibel.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.somethingobvious.decibel.R
import com.somethingobvious.decibel.Touches

internal const val SCALE_LOW_DB = 30f
internal const val SCALE_HIGH_DB = 130f
internal const val LIMIT_DB = 85f
internal const val CAUTION_DB = 70f

// An unlit legend: there if you look for it, gone at a glance.
private const val GHOST_ALPHA = 0.12f

private val panelShape = RoundedCornerShape(12.dp)
private const val GLASS_SHADOW_ALPHA = 0.22f

@Composable
fun LcdPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val lcd = LocalLcd.current
    Column(
        modifier
            .clip(panelShape)
            .background(lcd.panel)
            .glassShadow()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
        content = content,
    )
}

// The glass sits below the bezel, so its top edge falls into shadow.
private fun Modifier.glassShadow() =
    drawWithContent {
        drawContent()
        drawRect(
            Brush.verticalGradient(
                listOf(Color.Black.copy(alpha = GLASS_SHADOW_ALPHA), Color.Transparent),
                endY = 20.dp.toPx(),
            ),
        )
    }

@Composable
fun lcdText(size: TextUnit, weight: FontWeight = FontWeight.Medium, color: Color = LocalLcd.current.ink) =
    TextStyle(
        fontFamily = LcdFont,
        fontSize = size,
        fontWeight = weight,
        color = color,
        fontFeatureSettings = "tnum",
    )

/**
 * An LCD flag: a filled box with panel-coloured letters when lit. Unlit, only a ghost of the
 * legend shows, the way an unpowered segment does, so the flags never shift the row around.
 */
@Composable
fun Flag(text: String, isLit: Boolean = true, color: Color = LocalLcd.current.ink) {
    val lcd = LocalLcd.current
    Text(
        text = text,
        style = lcdText(12.sp, FontWeight.Bold, if (isLit) lcd.panel else lcd.ink.copy(alpha = GHOST_ALPHA)),
        softWrap = false,
        modifier =
            Modifier
                .clip(RoundedCornerShape(3.dp))
                .background(if (isLit) color else Color.Transparent)
                .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
fun FlagRow(left: String, flags: List<Pair<String, Boolean>>, warnings: List<String>, onAcknowledge: (() -> Unit)?) {
    val res = LocalResources.current
    val spoken = warnings.reduceOrNull { said, next -> res.getString(R.string.spoken_sentences, said, next) }.orEmpty()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = left, style = lcdText(14.sp), softWrap = false, modifier = Modifier.padding(end = 8.dp))
        val clearWarnings = stringResource(R.string.clear_warnings)
        val acknowledge =
            if (onAcknowledge != null) {
                // TalkBack runs this action, not one set in the semantics below, so the mark goes on this one.
                Modifier.clickable(
                    onClickLabel = clearWarnings,
                    role = Role.Button,
                    onClick = Touches.marked(onAcknowledge),
                )
            } else {
                Modifier
            }
        // The timer changes every second, so the warnings are a node of their own and only they are announced.
        FlowRow(
            modifier =
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .widthIn(min = 48.dp)
                    .then(acknowledge)
                    .clearAndSetSemantics {
                        if (spoken.isNotEmpty()) contentDescription = spoken
                        // A warning that the numbers can't be trusted is worth interrupting for.
                        liveRegion = LiveRegionMode.Assertive
                        if (onAcknowledge != null) role = Role.Button
                    },
            itemVerticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            verticalArrangement = Arrangement.Center,
        ) { flags.forEach { (text, isLit) -> Flag(text = text, isLit = isLit) } }
    }
}

@Composable
fun MainReading(
    label: String,
    detail: String,
    value: String,
    level: Double?,
    dimmed: Boolean,
    modifier: Modifier = Modifier,
) {
    val names: @Composable (Modifier) -> Unit = { placed ->
        Column(placed) {
            Text(label, style = lcdText(22.sp, FontWeight.Bold))
            Text(detail, style = lcdText(13.sp, FontWeight.Medium, LocalLcd.current.dim))
        }
    }
    if (LocalDensity.current.fontScale > STACK_FONT_SCALE) {
        Column(modifier.fillMaxWidth()) {
            names(Modifier)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Digits(value = value, level = level, dimmed = dimmed)
            }
        }
    } else {
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            names(Modifier.padding(bottom = 14.dp, end = 8.dp))
            Digits(value = value, level = level, dimmed = dimmed)
        }
    }
}

// Sized so the widest reading fills the room left, so the digits never jump size when they pass 100.
@Composable
private fun RowScope.Digits(value: String, level: Double?, dimmed: Boolean) {
    val lcd = LocalLcd.current
    BoxWithConstraints(Modifier.weight(1f), contentAlignment = Alignment.BottomEnd) {
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val probe =
            lcdText(size = with(density) { PROBE_DP.dp.toSp() }, weight = FontWeight.ExtraBold)
                .copy(letterSpacing = READING_TRACKING.em)
        val probeWidth =
            remember(probe, measurer, density) {
                val layout = measurer.measure(text = WIDEST_READING, style = probe, density = density)
                layout.size.width
            }
        val fit = PROBE_DP * constraints.maxWidth / probeWidth.coerceAtLeast(1)
        val digits = fit.coerceIn(minimumValue = READING_MIN, maximumValue = READING_MAX)
        Text(
            text = value,
            style =
                lcdText(
                    size = with(density) { digits.dp.toSp() },
                    weight = FontWeight.ExtraBold,
                    color = if (dimmed) lcd.dim else lcd.zone(level),
                ).copy(letterSpacing = READING_TRACKING.em),
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.End,
        )
    }
    Text(
        stringResource(R.string.unit_db),
        style = lcdText(18.sp, FontWeight.Bold),
        modifier = Modifier.padding(bottom = 12.dp, start = 2.dp),
    )
}

// Past this text size the label beside the digits would eat the width they need.
private const val STACK_FONT_SCALE = 1.3f

private const val READING_MIN = 24f
private const val PROBE_DP = 100f
private const val WIDEST_READING = "000.0"
private const val READING_MAX = 112f
private const val READING_TRACKING = -0.04f

// Room for "dB" at 13 sp after a 6 dp gap, in sp so it grows with the text.
private const val UNIT_WIDTH = 30

/** Where [db] sits on the bar graph and history scale, from 0 at the bottom to 1 at the top. */
internal fun scaleFraction(db: Float) =
    (db.coerceIn(minimumValue = SCALE_LOW_DB, maximumValue = SCALE_HIGH_DB) - SCALE_LOW_DB) /
        (SCALE_HIGH_DB - SCALE_LOW_DB)

@Composable
fun ParamRow(name: String, spokenName: String, value: String, isPercent: Boolean = false, dimmed: Boolean = false) {
    val lcd = LocalLcd.current
    val ink = if (dimmed) lcd.dim else lcd.ink
    val unitWidth = with(LocalDensity.current) { UNIT_WIDTH.sp.toDp() }
    val unit = stringResource(if (isPercent) R.string.unit_percent else R.string.unit_db)
    val spoken = stringResource(if (isPercent) R.string.param_percent else R.string.param_decibels, spokenName, value)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(name, style = lcdText(17.sp, FontWeight.Normal, ink))
        Spacer(Modifier.weight(1f))
        Text(value, style = lcdText(17.sp, FontWeight.Bold, ink))
        Text(
            unit,
            style = lcdText(13.sp, FontWeight.Medium, lcd.dim),
            modifier =
                Modifier
                    .width(unitWidth)
                    .padding(start = 6.dp, bottom = 1.dp),
        )
    }
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(lcd.faint),
    )
}

/** Fades the bottom edge while there is more to scroll to, so a short window shows that the panel goes on. */
fun Modifier.fadeWhenMore(scroll: ScrollState, color: Color): Modifier =
    drawWithContent {
        drawContent()
        if (scroll.canScrollForward) {
            val fade = SCROLL_FADE.toPx()
            drawRect(
                Brush.verticalGradient(
                    listOf(Color.Transparent, color),
                    startY = size.height - fade,
                    endY = size.height,
                ),
                topLeft = Offset(x = 0f, y = size.height - fade),
                size = Size(width = size.width, height = fade),
            )
        }
    }

private val SCROLL_FADE = 32.dp
