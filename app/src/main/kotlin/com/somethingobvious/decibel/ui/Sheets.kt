package com.somethingobvious.decibel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.somethingobvious.decibel.MeterState
import com.somethingobvious.decibel.R
import com.somethingobvious.decibel.Reading
import com.somethingobvious.decibel.Response
import com.somethingobvious.decibel.Settings
import com.somethingobvious.decibel.SettingsStore
import com.somethingobvious.decibel.Touches
import com.somethingobvious.decibel.meter.LevelMeter
import kotlin.math.roundToInt

private const val ALERT_LOW = 60f
private const val ALERT_HIGH = 120f
private const val ALERT_DEFAULT = 85.0
private const val STEP_FINE = 0.1
private const val STEP_COARSE = 1.0
private const val TENTHS_PER_DB = 10.0
private const val MATCH_TICKS = 5 * LevelMeter.TICKS_PER_SECOND
private val REFERENCE_RANGE = 0.0..200.0

// Written out, so a comma-decimal locale can't turn "-1.0" into "-1,".
private val steps = listOf(-STEP_COARSE to "-1", -STEP_FINE to "-0.1", STEP_FINE to "+0.1", STEP_COARSE to "+1")

private val sheetTitle: TextStyle
    @Composable get() =
        MaterialTheme.typography.titleLarge.copy(
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
private val sectionTitle: TextStyle
    @Composable get() =
        MaterialTheme.typography.titleSmall.copy(
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
private val body: TextStyle
    @Composable get() = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Sheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = {
            Touches.mark()
            onDismiss()
        },
        // The whole sheet, drag handle included, rather than just the content under it.
        modifier = Modifier.marksTouches(),
        // Open at full height, so the calibration field sits above the keyboard rather than under it.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentWindowInsets = { WindowInsets.ime.union(WindowInsets.navigationBars).union(WindowInsets.statusBars) },
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            // A TalkBack double tap reaches the mic but not the screen, so the toggle marks it.
            .toggleable(checked, role = Role.Switch) { on ->
                Touches.mark()
                onChange(on)
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .padding(end = 16.dp),
        ) {
            Text(title, style = sectionTitle)
            Text(detail, style = body)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
fun MenuSheet(settings: Settings, controls: MeterControls, onDismiss: () -> Unit) {
    Sheet(onDismiss) {
        Text(stringResource(R.string.settings), style = sheetTitle, modifier = Modifier.semantics { heading() })
        SwitchRow(
            title = stringResource(R.string.background_title),
            detail = stringResource(R.string.background_detail),
            checked = settings.isBackgroundOn,
            onChange = controls::setBackground,
        )
        SwitchRow(
            title = stringResource(R.string.alert_title),
            detail = stringResource(R.string.alert_detail),
            checked = settings.alertDb != null,
        ) { on -> SettingsStore.update { it.copy(alertDb = if (on) ALERT_DEFAULT else null) } }
        val alert = settings.alertDb
        if (alert != null) {
            val whole = alert.roundToInt()
            val alertLevel = stringResource(R.string.alert_level)
            val spokenAlert = pluralStringResource(id = R.plurals.alert_decibels, count = whole, whole)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = alert.toFloat(),
                    onValueChange = { v -> SettingsStore.update { it.copy(alertDb = v.roundToInt().toDouble()) } },
                    valueRange = ALERT_LOW..ALERT_HIGH,
                    steps = (ALERT_HIGH - ALERT_LOW).toInt() - 1,
                    colors =
                        SliderDefaults.colors(
                            activeTickColor = Color.Transparent,
                            inactiveTickColor = Color.Transparent,
                        ),
                    modifier =
                        Modifier
                            .weight(1f)
                            .semantics {
                                contentDescription = alertLevel
                                stateDescription = spokenAlert
                            },
                )
                Text(
                    stringResource(R.string.db_whole, whole),
                    style = sectionTitle,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
        }
        HorizontalDivider()
        HowItMeasures()
    }
}

@Composable
private fun HowItMeasures() {
    Text(stringResource(R.string.how_title), style = sectionTitle, modifier = Modifier.semantics { heading() })
    Text(stringResource(R.string.how_filters), style = body)
    Text(stringResource(R.string.how_dose), style = body)
    Text(stringResource(R.string.how_over), style = body)
    Text(stringResource(R.string.how_taps), style = body)
    Text(stringResource(R.string.how_certified), style = body)
}

@Composable
private fun StepRow(onStep: (Double) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((step, label) in steps) {
            val spoken = stringResource(R.string.db_value, label)
            OutlinedButton(
                onClick = Touches.marked { onStep(step) },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                modifier =
                    Modifier
                        .weight(1f)
                        .semantics { contentDescription = spoken },
            ) {
                Text(label, style = sectionTitle)
            }
        }
    }
}

// The middle of the last five seconds of Slow, so a keyboard tap while typing can't pull the match.
private fun steadyLevel(state: MeterState, settings: Settings): Double? =
    state.session
        .recentMedian(column = settings.weighting.ordinal * 2 + Response.SLOW.ordinal, rows = MATCH_TICKS)
        ?.takeIf { state.isRunning }
        ?.let { Reading.level(it, Reading.offset(state, settings)) }

// Top-aligned so the error line under the field never pushes the button off centre.
@Composable
private fun ReferenceRow(
    reference: String,
    onChange: (String) -> Unit,
    isInvalid: Boolean,
    canMatch: Boolean,
    onMatch: () -> Unit,
) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = reference,
            onValueChange = { text ->
                Touches.mark()
                onChange(text)
            },
            label = { Text(stringResource(R.string.it_reads)) },
            suffix = { Text(stringResource(R.string.unit_db)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onMatch() }),
            isError = isInvalid,
            supportingText = { if (isInvalid) Text(stringResource(R.string.reference_range)) },
            modifier = Modifier.weight(1f),
        )
        Button(enabled = canMatch, onClick = onMatch, modifier = Modifier.padding(top = 12.dp)) {
            Text(stringResource(R.string.match))
        }
    }
}

@Composable
fun CalibrationSheet(meter: State<MeterState>, settings: Settings, onDismiss: () -> Unit) {
    // Read here, so only the sheet follows the meter while it is open.
    val state = meter.value
    var reference by rememberSaveable { mutableStateOf("") }
    val cal = settings.calibrationDb
    val now = steadyLevel(state = state, settings = settings)

    fun setCal(db: Double) =
        SettingsStore.update { it.copy(calibrationDb = (db * TENTHS_PER_DB).roundToInt() / TENTHS_PER_DB) }
    val target = reference.replace(oldChar = ',', newChar = '.').toDoubleOrNull()?.takeIf { it in REFERENCE_RANGE }

    fun match() {
        if (target != null && now != null) {
            setCal(cal + target - now)
            reference = ""
        }
    }
    Sheet(onDismiss) {
        Text(
            stringResource(R.string.calibration_title),
            style = sheetTitle,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.db_value, signed(cal)),
            style =
                TextStyle(
                    fontFamily = LcdFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 40.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
        )
        StepRow { step -> setCal(cal + step) }
        HorizontalDivider()
        Text(stringResource(R.string.match_title), style = sectionTitle, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.match_how, settings.weighting.name), style = body)
        ReferenceRow(
            reference = reference,
            onChange = { reference = it },
            isInvalid = reference.isNotBlank() && target == null,
            canMatch = target != null && now != null,
            onMatch = Touches.marked(::match),
        )
        Text(
            if (state.isRunning) {
                stringResource(
                    R.string.phone_reads,
                    Reading.format(now),
                    Reading.name(settings.weighting, Response.SLOW),
                )
            } else {
                stringResource(R.string.run_first)
            },
            style = body,
        )
        TextButton(onClick = Touches.marked { setCal(0.0) }) { Text(stringResource(R.string.default_cal)) }
        Text(stringResource(state.source?.defaultCal ?: R.string.default_cal_mic), style = body)
    }
}
