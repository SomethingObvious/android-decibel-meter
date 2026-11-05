package com.somethingobvious.decibel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.somethingobvious.decibel.Meter
import com.somethingobvious.decibel.MicSource
import com.somethingobvious.decibel.R
import com.somethingobvious.decibel.ScreenModel
import com.somethingobvious.decibel.SettingsStore
import com.somethingobvious.decibel.Touches
import java.util.Locale

private enum class Sheet { NONE, MENU, CALIBRATION }

@Composable
fun MeterScreen(controls: MeterControls, snackbar: SnackbarHostState, model: ScreenModel) {
    // Only the LCD reads every tick. Everything around it waits for the few values it shows.
    val meter = Meter.state.collectAsStateWithLifecycle()
    val isRunning by remember { derivedStateOf { meter.value.isRunning } }
    val source by remember { derivedStateOf { meter.value.source } }
    val held by remember { derivedStateOf { model.held?.takeIf { it.session === meter.value.session } } }
    val settings by SettingsStore.settings.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf(Sheet.NONE) }
    val view = LocalView.current
    DisposableEffect(isRunning) {
        view.keepScreenOn = isRunning
        onDispose { view.keepScreenOn = false }
    }
    val onPad: (Pad) -> Unit = { key ->
        when (key) {
            Pad.HOLD -> model.held = if (held == null) meter.value else null
            Pad.MENU -> sheet = Sheet.MENU
            Pad.CAL -> sheet = Sheet.CALIBRATION
        }
    }
    val keys =
        keys(
            isRunning = isRunning,
            settings = settings,
            isHolding = held != null,
            controls = controls,
            onPad = onPad,
        )
    val faceplate: @Composable (Boolean) -> Unit = { isWide ->
        Faceplate(
            plate = Plate(isRunning = isRunning, source = source, calibrationDb = settings.calibrationDb),
            isWide = isWide,
            onCalibrate = Touches.marked { sheet = Sheet.CALIBRATION },
        )
    }
    val lcd: @Composable (Boolean, Modifier) -> Unit = { isWide, modifier ->
        Bezel(snackbar, modifier) {
            Display(state = held ?: meter.value, settings = settings, isHolding = held != null, isWide = isWide)
        }
    }
    Scaffold(containerColor = Palette.body) { padding ->
        Layout(padding = padding, faceplate = faceplate, lcd = lcd, keys = keys)
    }
    when (sheet) {
        Sheet.MENU -> MenuSheet(settings, controls) { sheet = Sheet.NONE }
        Sheet.CALIBRATION -> CalibrationSheet(meter, settings) { sheet = Sheet.NONE }
        Sheet.NONE -> Unit
    }
}

private val WIDE_KEYPAD_WIDTH = 220.dp

// Four rows of keys under the faceplate still fit a phone held sideways.
private val WIDE_KEY_HEIGHT = 52.dp

// Below this a window wider than tall is a phone in split screen, and the tall layout reads better.
private val WIDE_MIN_WIDTH = 720.dp
private const val TALL_KEYPAD_COLUMNS = 4
private val TALL_MAX_WIDTH = 560.dp
private const val WIDE_KEYPAD_COLUMNS = 2
private val bezelShape = RoundedCornerShape(16.dp)

// The LCD sits in a dark bezel, and messages slide up over the bottom of the glass.
@Composable
private fun Bezel(snackbar: SnackbarHostState, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier
            .clip(bezelShape)
            .background(LocalLcd.current.bezel)
            .border(1.dp, Palette.key, bezelShape)
            .padding(6.dp),
    ) {
        LcdPanel(Modifier.fillMaxSize(), content)
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun Layout(
    padding: PaddingValues,
    faceplate: @Composable (Boolean) -> Unit,
    lcd: @Composable (Boolean, Modifier) -> Unit,
    keys: List<KeySpec>,
) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .marksTouches()
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
    ) {
        if (maxWidth > maxHeight && maxWidth >= WIDE_MIN_WIDTH) {
            // Landscape and unfolded screens: the LCD takes the width and the keys stand beside it.
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                lcd(true, Modifier.weight(1f).fillMaxHeight().padding(top = 12.dp))
                Column(Modifier.width(WIDE_KEYPAD_WIDTH), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    faceplate(true)
                    Spacer(Modifier.weight(1f))
                    Keypad(keys = keys, columns = WIDE_KEYPAD_COLUMNS, keyHeight = WIDE_KEY_HEIGHT)
                }
            }
        } else {
            // On a tablet or an unfolded phone held upright, the instrument keeps a hand-held width.
            Column(
                Modifier
                    .widthIn(max = TALL_MAX_WIDTH)
                    .align(Alignment.TopCenter),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                faceplate(false)
                lcd(false, Modifier.weight(1f).fillMaxWidth())
                Keypad(keys = keys, columns = TALL_KEYPAD_COLUMNS)
            }
        }
    }
}

/** Every touch reaches the mic as a thump, so the meter leaves the moment around it out of the figures. */
fun Modifier.marksTouches(): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                // A finger sliding across the glass is close to silent, so only landing and lifting count.
                if (event.type == PointerEventType.Press || event.type == PointerEventType.Release) Touches.mark()
            }
        }
    }

private val headerStyle =
    TextStyle(
        fontFamily = KeyFont,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        letterSpacing = 1.4.sp,
        color = Palette.keyDim,
    )

private val wordmark = headerStyle.copy(fontSize = 17.sp, letterSpacing = 3.sp, color = Palette.keyText)
private val subtitle = headerStyle.copy(fontSize = 10.sp, letterSpacing = 1.6.sp)

/** What the faceplate shows, so it redraws only when one of these changes. */
private data class Plate(val isRunning: Boolean, val source: MicSource?, val calibrationDb: Double)

@Composable
private fun Faceplate(plate: Plate, isWide: Boolean, onCalibrate: () -> Unit) {
    val source = stringResource(plate.source?.label ?: R.string.source_mic)
    val setup: @Composable () -> Unit = {
        Column(horizontalAlignment = if (isWide) Alignment.Start else Alignment.End) {
            Text(text = source, style = headerStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.faceplate_cal, signed(plate.calibrationDb)), style = headerStyle, maxLines = 1)
        }
    }
    val name: @Composable (Modifier) -> Unit = { modifier ->
        Row(
            modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // The record lamp: lit while the mic is open.
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (plate.isRunning) Palette.led else Palette.key),
            )
            Column {
                Text(
                    text = stringResource(R.string.app_name).uppercase(Locale.ROOT),
                    style = wordmark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.sound_level_meter),
                    style = subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    val modifier =
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = stringResource(R.string.calibrate), role = Role.Button, onClick = onCalibrate)
            .padding(vertical = 4.dp)
    if (isWide) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            name(Modifier)
            setup()
        }
    } else {
        FlowRow(
            modifier,
            horizontalArrangement = Arrangement.SpaceBetween,
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            name(Modifier)
            setup()
        }
    }
}

fun signed(db: Double): String = String.format(Locale.getDefault(), "%+.1f", db)
