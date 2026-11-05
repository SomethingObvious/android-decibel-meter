package com.somethingobvious.decibel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.somethingobvious.decibel.Touches

private val KEY_CORNER = 6.dp

/** Tall enough for a thumb, and the travel under it makes the press felt. */
val KEY_HEIGHT = 60.dp
private val keyShape = RoundedCornerShape(KEY_CORNER)

private val LABEL_PADDING = 6.dp

// A little short of a full fit, so rounding never clips the last letter.
private const val FIT_MARGIN = 0.98f

/** The space between keys in a row. */
val KEY_GAP = 10.dp
private val CHOICE_SIZE = 17.sp

private val keyLabel =
    TextStyle(
        fontFamily = KeyFont,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        letterSpacing = 0.075.em,
    )

/** One key on the pad. [description] is what TalkBack reads, and [state] what a lit or cycling key shows. */
data class KeySpec(
    val label: String,
    val description: String,
    val onClick: () -> Unit,
    val isAccent: Boolean = false,
    val isLit: Boolean = false,
    val state: String? = null,
    val choices: List<String>? = null,
    val chosen: Int = 0,
    /** Every label this key can show, so the pad is sized for the widest of them. */
    val labels: List<String> = listOf(label),
)

/** A hard key: a cap on a dark skirt, pressed down flat onto it. */
@Composable
fun RowScope.Key(spec: KeySpec, height: Dp = KEY_HEIGHT, labelScale: Float = 1f) {
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val isFocused by interaction.collectIsFocusedAsState()
    val ring =
        when {
            isFocused -> Palette.keyText
            spec.isLit -> Palette.amber
            else -> null
        }
    // TalkBack runs the clickable's action, not one set in the semantics below, so the mark goes on this one.
    val tap = Touches.marked(spec.onClick)
    Box(
        Modifier
            .weight(1f)
            .height(height)
            .keyFace(isPressed = isPressed, accent = spec.isAccent, ring = ring)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = tap)
            .clearAndSetSemantics {
                contentDescription = spec.description
                role = Role.Button
                spec.state?.let { stateDescription = it }
            },
        contentAlignment = Alignment.Center,
    ) {
        val choices = spec.choices
        if (choices == null) {
            BasicText(
                text = spec.label,
                style =
                    keyLabel.copy(
                        color = if (spec.isAccent) Palette.onAmber else Palette.keyText,
                        fontSize = keyLabel.fontSize.scaledBy(labelScale),
                    ),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(horizontal = LABEL_PADDING),
            )
        } else {
            ChoiceLabel(choices = choices, chosen = spec.chosen, scale = labelScale)
        }
    }
}

@Composable
private fun ChoiceLabel(choices: List<String>, chosen: Int, scale: Float) {
    BasicText(
        text =
            buildAnnotatedString {
                choices.forEachIndexed { i, choice ->
                    if (i > 0) append(" ")
                    val style =
                        if (i == chosen) {
                            SpanStyle(color = Palette.amberTop, textDecoration = TextDecoration.Underline)
                        } else {
                            SpanStyle(color = Palette.keyChoice)
                        }
                    withStyle(style) { append(choice) }
                }
            },
        style = keyLabel.copy(fontSize = CHOICE_SIZE.scaledBy(scale)),
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.padding(horizontal = LABEL_PADDING),
    )
}

@Composable
fun KeyRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.padding(bottom = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(KEY_GAP),
        content = content,
    )
}

private val keyTravel = 5.dp

private val amberBrush = Brush.verticalGradient(listOf(Palette.amberTop, Palette.amber))

// The skirt stays put under the cap, so a pressed key sinks onto it and hides it.
private fun Modifier.keyFace(isPressed: Boolean, accent: Boolean, ring: Color?): Modifier =
    drawBehind {
        drawRoundRect(
            color = if (accent) Palette.amberSkirt else Palette.keySkirt,
            topLeft = Offset(x = 0f, y = keyTravel.toPx()),
            size = size,
            cornerRadius = CornerRadius(KEY_CORNER.toPx()),
        )
    }.graphicsLayer { translationY = if (isPressed) keyTravel.toPx() else 0f }
        .clip(keyShape)
        .then(if (accent) Modifier.background(amberBrush) else Modifier.background(Palette.key).capEdge())
        .then(if (ring != null) Modifier.border(2.dp, ring, keyShape) else Modifier)

// Light catches the top edge of a flat cap.
private fun Modifier.capEdge(): Modifier =
    drawBehind {
        drawLine(
            color = Palette.keyEdge,
            start = Offset(x = 0f, y = 0.5.dp.toPx()),
            end = Offset(x = size.width, y = 0.5.dp.toPx()),
            strokeWidth = 1.dp.toPx(),
        )
    }

/**
 * The scale for every label on the pad: the largest at which each one fits a key [keyWidth] wide,
 * so big text shrinks them all alike instead of leaving some larger than others.
 */
@Composable
fun rememberLabelScale(keys: List<KeySpec>, keyWidth: Dp): Float {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val room = with(density) { (keyWidth - LABEL_PADDING * 2).toPx() }
    return remember(keys.map { it.labels }, room, density) {
        val fits =
            keys.flatMap { spec ->
                val choices = spec.choices
                val texts = if (choices == null) spec.labels else listOf(choices.joinToString(" "))
                val style = if (choices == null) keyLabel else keyLabel.copy(fontSize = CHOICE_SIZE)
                texts.map { text ->
                    val layout = measurer.measure(text = text, style = style, density = density)
                    room / layout.size.width.coerceAtLeast(1)
                }
            }
        (fits.minOrNull() ?: 1f).times(FIT_MARGIN).coerceAtMost(1f)
    }
}

// Android scales small text up more than large text, so a size is scaled as a physical size and turned back into sp.
@Composable
private fun TextUnit.scaledBy(scale: Float): TextUnit = with(LocalDensity.current) { (toDp() * scale).toSp() }
