package com.somethingobvious.decibel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.somethingobvious.decibel.R
import com.somethingobvious.decibel.Reading
import com.somethingobvious.decibel.Settings
import com.somethingobvious.decibel.SettingsStore
import com.somethingobvious.decibel.Window

@Composable
fun ColumnScope.HistoryPane(settings: Settings, trace: Trace, scrubbed: Int?, onScrub: (Int?) -> Unit) {
    val setting = stringResource(settings.response.spokenSetting, settings.weighting.name)
    HistoryHeader(
        name = Reading.name(settings.weighting, settings.response),
        spoken = stringResource(R.string.spoken_history, setting),
        window = settings.window,
    )
    // TalkBack can't drag, so it steps through the history with actions instead.
    val step = maxOf(1, trace.span / SCRUB_STEPS)
    val from = scrubbed ?: trace.ticks.dec()
    val earlier = stringResource(R.string.history_earlier)
    val later = stringResource(R.string.history_later)
    val backToLive = stringResource(R.string.history_live)
    val description = stringResource(R.string.history_description)
    val actions =
        buildList {
            add(
                CustomAccessibilityAction(earlier) {
                    onScrub(maxOf(0, trace.start, from - step))
                    true
                },
            )
            if (scrubbed != null) {
                add(
                    CustomAccessibilityAction(later) {
                        onScrub((scrubbed + step).takeIf { it < trace.ticks })
                        true
                    },
                )
                add(
                    CustomAccessibilityAction(backToLive) {
                        onScrub(null)
                        true
                    },
                )
            }
        }
    History(
        trace = trace,
        scrub = scrubbed,
        onScrub = onScrub,
        modifier =
            Modifier
                .weight(1f)
                .heightIn(min = HISTORY_MIN)
                .semantics {
                    contentDescription = description
                    customActions = actions
                },
    )
}

private val HISTORY_MIN = 72.dp

private const val SCRUB_STEPS = 20

@Composable
private fun HistoryHeader(name: String, spoken: String, window: Window) {
    val lcd = LocalLcd.current
    FlowRow(
        Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.history, name),
            style = lcdText(13.sp, FontWeight.Medium, lcd.dim),
            modifier = Modifier.clearAndSetSemantics { contentDescription = spoken },
        )
        Row(Modifier.selectableGroup()) {
            Window.entries.forEach { entry ->
                Box(
                    Modifier
                        .padding(horizontal = 3.dp)
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .selectable(selected = entry == window, role = Role.RadioButton) {
                            SettingsStore.update { it.copy(window = entry) }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    val label = stringResource(entry.label)
                    if (entry == window) {
                        Flag(label)
                    } else {
                        Text(label, style = lcdText(12.sp, FontWeight.Bold, lcd.dim), softWrap = false)
                    }
                }
            }
        }
    }
}
