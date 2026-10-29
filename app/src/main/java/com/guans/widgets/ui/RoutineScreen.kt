package com.guans.widgets.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.guans.widgets.R
import com.guans.widgets.data.Routine
import com.guans.widgets.data.RoutinePreset
import com.guans.widgets.data.Routines
import com.guans.widgets.data.formatElapsed
import com.guans.widgets.data.formatRemaining
import com.guans.widgets.data.formatStatus
import com.guans.widgets.widgets.routine.RoutineTicker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date

/** 习惯计时小工具的设置：事项、手动记录与修正时间。 */
@Composable
fun RoutineScreen(contentPadding: PaddingValues, onAddToHome: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var routines by remember { mutableStateOf(Routines.all(context)) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var picking by remember { mutableStateOf(false) }
    var timeFor by remember { mutableStateOf<Routine?>(null) }

    // 小工具上点过之后，这里跟着更新；页面开着时每分钟重算一次“多久之前”
    DisposableEffect(Unit) {
        val prefs = Routines.prefs(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            routines = Routines.all(context)
            now = System.currentTimeMillis()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = System.currentTimeMillis()
        }
    }

    fun changed(block: () -> Unit) {
        block()
        routines = Routines.all(context)
        now = System.currentTimeMillis()
        scope.launch { RoutineTicker.pushAll(context) }
    }

    fun move(from: Int, to: Int) = changed {
        val order = routines.map { it.preset }.toMutableList()
        order.add(to, order.removeAt(from))
        Routines.setOrder(context, order)
    }

    LazyColumn(contentPadding = screenPadding(contentPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            SectionHeader(stringResource(R.string.section_routines), stringResource(R.string.routines_hint))
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                if (routines.isEmpty()) {
                    Text(
                        stringResource(R.string.routines_empty),
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                routines.forEachIndexed { index, routine ->
                    if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    RoutineRow(
                        routine = routine,
                        now = now,
                        canMoveUp = index > 0,
                        canMoveDown = index < routines.lastIndex,
                        onDone = { changed { Routines.markDone(context, routine.preset) } },
                        onSetTime = { timeFor = routine },
                        onUndo = { changed { Routines.undo(context, routine.preset) } },
                        onMoveUp = { move(index, index - 1) },
                        onMoveDown = { move(index, index + 1) },
                        onDelete = { changed { Routines.remove(context, routine.preset) } },
                    )
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { picking = true }, enabled = routines.size < Routines.MAX_ITEMS) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.add_routine))
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.routines_limit, Routines.MAX_ITEMS),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionHeader(
                stringResource(R.string.section_widget),
                stringResource(R.string.add_to_home_hint, stringResource(R.string.widget_routine_name)) +
                    "\n" + stringResource(R.string.routine_widget_note),
            )
        }
        item {
            OutlinedButton(onClick = onAddToHome) {
                Text(stringResource(R.string.add_to_home))
            }
        }
    }

    if (picking) {
        RoutinePicker(
            existing = routines.map { it.preset }.toSet(),
            onDismiss = { picking = false },
            onPicked = { preset ->
                picking = false
                changed { Routines.add(context, preset) }
            },
        )
    }
    timeFor?.let { routine ->
        LastDonePicker(
            initial = routine.lastDone.takeIf { it > 0 } ?: System.currentTimeMillis(),
            onDismiss = { timeFor = null },
            onPicked = { time ->
                timeFor = null
                changed { Routines.markDone(context, routine.preset, time) }
            },
        )
    }
}

@Composable
private fun RoutineRow(
    routine: Routine,
    now: Long,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onDone: () -> Unit,
    onSetTime: () -> Unit,
    onUndo: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(stringResource(routine.preset.nameRes)) },
        supportingContent = {
            Column {
                val started = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                val last = if (routine.preset.isLoop) {
                    if (routine.lastDone > 0) {
                        formatStatus(context, routine, now) + "\n" +
                            stringResource(R.string.routine_loop_started, started.format(Date(routine.lastDone)))
                    } else {
                        formatStatus(context, routine, now)
                    }
                } else if (routine.lastDone > 0) {
                    formatElapsed(context, routine.elapsed(now)) + " · " +
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(routine.lastDone))
                } else {
                    formatElapsed(context, null)
                }
                Text(last, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDone) {
                    Icon(
                    Icons.Default.Check,
                    contentDescription = stringResource(
                        if (routine.preset.isLoop) R.string.routine_loop_restart else R.string.routine_mark_done,
                    ),
                )
                }
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = {
                            Text(stringResource(if (routine.preset.isLoop) R.string.routine_set_start else R.string.routine_set_time))
                        },
                        onClick = { menu = false; onSetTime() },
                    )
                    if (routine.lastDone > 0) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.routine_undo)) }, onClick = { menu = false; onUndo() })
                    }
                    if (canMoveUp) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.pair_move_up)) }, onClick = { menu = false; onMoveUp() })
                    }
                    if (canMoveDown) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.pair_move_down)) }, onClick = { menu = false; onMoveDown() })
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.pair_delete), color = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        },
    )
}

/** 按分类列出可选事项；已添加的不可再选。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoutinePicker(existing: Set<RoutinePreset>, onDismiss: () -> Unit, onPicked: (RoutinePreset) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            stringResource(R.string.pick_routine),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            RoutinePreset.Category.entries.forEach { category ->
                item(key = "h-${category.name}") {
                    Text(
                        stringResource(category.nameRes),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                items(RoutinePreset.entries.filter { it.category == category }, key = { it.name }) { preset ->
                    val taken = preset in existing
                    ListItem(
                        modifier = Modifier.clickable(enabled = !taken) { onPicked(preset) }.padding(horizontal = 8.dp),
                        headlineContent = {
                            Text(
                                stringResource(preset.nameRes),
                                color = if (taken) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        supportingContent = if (preset.isLoop) {
                            { Text(loopHint(preset)) }
                        } else {
                            null
                        },
                        trailingContent = if (taken) {
                            { Text(stringResource(R.string.pair_exists), style = MaterialTheme.typography.labelMedium) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

/** 补记或修正上次完成的时间：先选日期（不晚于今天），再选时刻。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LastDonePicker(initial: Long, onDismiss: () -> Unit, onPicked: (Long) -> Unit) {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(initial).atZone(zone).toLocalDateTime()
    var date by remember { mutableStateOf<LocalDate?>(null) }

    val pickedDate = date
    if (pickedDate == null) {
        val today = LocalDate.now(zone)
        val dateState = rememberDatePickerState(
            // DatePicker 以 UTC 零点表示日期
            initialSelectedDateMillis = start.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(today)

                override fun isSelectableYear(year: Int): Boolean = year <= today.year
            },
        )
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(
                    enabled = dateState.selectedDateMillis != null,
                    onClick = {
                        date = dateState.selectedDateMillis?.let {
                            Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                        }
                    },
                ) { Text(stringResource(R.string.routine_next)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        ) {
            DatePicker(state = dateState, showModeToggle = false)
        }
    } else {
        val timeState = rememberTimePickerState(initialHour = start.hour, initialMinute = start.minute)
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.routine_pick_time)) },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    val time = LocalDateTime.of(pickedDate, LocalTime.of(timeState.hour, timeState.minute))
                        .atZone(zone).toInstant().toEpochMilli()
                    onPicked(time.coerceAtMost(System.currentTimeMillis()))
                }) { Text(stringResource(R.string.routine_confirm)) }
            },
            dismissButton = { TextButton(onClick = { date = null }) { Text(stringResource(R.string.back)) } },
        )
    }
}

@Composable
private fun loopHint(preset: RoutinePreset): String {
    val hours = (preset.loopMs / 3_600_000L).toInt()
    return if (hours % 24 == 0) stringResource(R.string.routine_loop_hint_days, hours / 24)
    else stringResource(R.string.routine_loop_hint_hours, hours)
}
