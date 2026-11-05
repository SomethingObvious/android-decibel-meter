package com.somethingobvious.decibel.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.somethingobvious.decibel.R
import com.somethingobvious.decibel.Response
import com.somethingobvious.decibel.Settings
import com.somethingobvious.decibel.SettingsStore
import com.somethingobvious.decibel.meter.Weighting

enum class Pad { HOLD, MENU, CAL }

@Composable
fun keys(
    isRunning: Boolean,
    settings: Settings,
    isHolding: Boolean,
    controls: MeterControls,
    onPad: (Pad) -> Unit,
): List<KeySpec> {
    val weighting = settings.weighting
    val response = settings.response
    val runLabel = stringResource(R.string.key_run_label)
    val pauseLabel = stringResource(R.string.key_pause_label)
    return listOf(
        KeySpec(
            label = "A C Z",
            description = stringResource(R.string.key_weighting),
            state = stringResource(R.string.key_weighted, weighting.name),
            choices = Weighting.entries.map { it.name },
            chosen = weighting.ordinal,
            onClick = {
                SettingsStore.update { current ->
                    current.copy(weighting = Weighting.entries[(weighting.ordinal + 1) % Weighting.entries.size])
                }
            },
        ),
        KeySpec(
            label = "F S",
            description = stringResource(R.string.key_response),
            state = stringResource(response.word),
            choices = Response.entries.map { it.letter },
            chosen = response.ordinal,
            onClick = {
                SettingsStore.update { current ->
                    current.copy(response = Response.entries[(response.ordinal + 1) % Response.entries.size])
                }
            },
        ),
        KeySpec(
            label = stringResource(R.string.hold_label),
            description = stringResource(R.string.key_hold),
            onClick = { onPad(Pad.HOLD) },
            isLit = isHolding,
            state = stringResource(if (isHolding) R.string.holding else R.string.key_off),
        ),
        key(label = R.string.key_menu_label, description = R.string.key_menu, onClick = { onPad(Pad.MENU) }),
        key(label = R.string.key_reset_label, description = R.string.key_reset, onClick = controls::reset),
        key(label = R.string.key_save_label, description = R.string.key_save, onClick = controls::save),
        key(label = R.string.key_cal_label, description = R.string.key_cal, onClick = { onPad(Pad.CAL) }),
        KeySpec(
            label = if (isRunning) pauseLabel else runLabel,
            labels = listOf(runLabel, pauseLabel),
            description = stringResource(if (isRunning) R.string.key_pause else R.string.key_run),
            onClick = controls::run,
            isAccent = true,
        ),
    )
}

@Composable
private fun key(
    @StringRes label: Int,
    @StringRes description: Int,
    onClick: () -> Unit,
) = KeySpec(label = stringResource(label), description = stringResource(description), onClick = onClick)

@Composable
fun Keypad(keys: List<KeySpec>, columns: Int, keyHeight: Dp = KEY_HEIGHT) {
    BoxWithConstraints {
        val scale = rememberLabelScale(keys = keys, keyWidth = (maxWidth - KEY_GAP * (columns - 1)) / columns)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            keys.chunked(columns).forEach { row ->
                KeyRow { row.forEach { Key(spec = it, height = keyHeight, labelScale = scale) } }
            }
        }
    }
}
