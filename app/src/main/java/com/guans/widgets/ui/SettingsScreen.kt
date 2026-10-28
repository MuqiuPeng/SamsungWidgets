package com.guans.widgets.ui

import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.LocaleListCompat
import com.guans.widgets.R
import com.guans.widgets.data.Currencies
import com.guans.widgets.data.Settings
import com.guans.widgets.data.formatRate
import com.guans.widgets.widgets.exchangerate.RateScheduler
import com.guans.widgets.widgets.exchangerate.RateUpdater
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onAddToHome: () -> Unit) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val pairs = remember { mutableStateListOf<String>().apply { addAll(Settings.pairs(context)) } }
    var refreshMinutes by remember { mutableIntStateOf(Settings.refreshMinutes(context)) }
    var cache by remember { mutableStateOf(RateUpdater.cached(context)) }
    var picking by remember { mutableStateOf(false) }

    // 后台刷新写入缓存后，页面上的汇率跟着更新
    DisposableEffect(Unit) {
        val prefs = RateUpdater.cachePrefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            cache = RateUpdater.cached(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun savePairs() {
        Settings.setPairs(context, pairs.toList())
        RateScheduler.refreshNow(context)
    }

    val rates = cache?.rates?.toMap().orEmpty()

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) }) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                SectionHeader(stringResource(R.string.section_pairs), stringResource(R.string.pairs_hint))
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    if (pairs.isEmpty()) {
                        Text(
                            stringResource(R.string.pairs_empty),
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    pairs.forEachIndexed { index, pair ->
                        if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        PairRow(
                            pair = pair,
                            rate = rates[pair],
                            locale = locale,
                            canMoveUp = index > 0,
                            canMoveDown = index < pairs.lastIndex,
                            onReverse = {
                                val (from, to) = pair.split('/')
                                val reversed = "$to/$from"
                                if (reversed !in pairs) {
                                    pairs[index] = reversed
                                    savePairs()
                                }
                            },
                            onMoveUp = { pairs.add(index - 1, pairs.removeAt(index)); savePairs() },
                            onMoveDown = { pairs.add(index + 1, pairs.removeAt(index)); savePairs() },
                            onDelete = { pairs.removeAt(index); savePairs() },
                        )
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = { picking = true }, enabled = pairs.size < Settings.MAX_PAIRS) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.add_pair))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.pairs_limit, Settings.MAX_PAIRS),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val current = cache
                    Text(
                        text = if (current == null || current.updatedAt == 0L) {
                            stringResource(R.string.never_updated)
                        } else {
                            stringResource(
                                R.string.last_updated,
                                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(current.updatedAt)),
                                current.source,
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { RateScheduler.refreshNow(context) }) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.refresh_now))
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.section_refresh), stringResource(R.string.refresh_note)) }
            item {
                RefreshOptions(selected = refreshMinutes) { minutes ->
                    refreshMinutes = minutes
                    Settings.setRefreshMinutes(context, minutes)
                    RateScheduler.reschedule(context)
                }
            }

            item { SectionHeader(stringResource(R.string.section_language)) }
            item { LanguageOptions() }

            item {
                SectionHeader(
                    stringResource(R.string.section_widget),
                    stringResource(R.string.add_to_home_hint, stringResource(R.string.app_name)),
                )
            }
            item {
                OutlinedButton(onClick = onAddToHome) {
                    Text(stringResource(R.string.add_to_home))
                }
            }
        }
    }

    if (picking) {
        PairPicker(
            existing = pairs.toSet(),
            locale = locale,
            onDismiss = { picking = false },
            onPicked = { pair ->
                picking = false
                if (pair !in pairs && pairs.size < Settings.MAX_PAIRS) {
                    pairs.add(pair)
                    savePairs()
                }
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String? = null) {
    Column(Modifier.padding(top = 16.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PairRow(
    pair: String,
    rate: Double?,
    locale: Locale,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onReverse: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    val (from, to) = pair.split('/')
    var menu by remember { mutableStateOf(false) }
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Text(Currencies.flag(from) + Currencies.flag(to), fontSize = 22.sp) },
        headlineContent = { Text("$from → $to") },
        supportingContent = {
            Text(
                "${Currencies.name(from, locale)} → ${Currencies.name(to, locale)}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(rate?.let(::formatRate) ?: "—", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.pair_reverse)) }, onClick = { menu = false; onReverse() })
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RefreshOptions(selected: Int, onSelect: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Settings.REFRESH_OPTIONS.forEach { minutes ->
            FilterChip(
                selected = minutes == selected,
                onClick = { onSelect(minutes) },
                label = {
                    Text(
                        if (minutes == 0) stringResource(R.string.refresh_manual)
                        else stringResource(R.string.refresh_minutes, minutes),
                    )
                },
            )
        }
    }
}

/** 应用内语言：空 tag 表示跟随系统。切换后 AppCompat 会自动重建界面。 */
@Composable
private fun LanguageOptions() {
    val current = AppCompatDelegate.getApplicationLocales()
    val selected = if (current.isEmpty) "" else current[0]?.language.orEmpty()
    val options = listOf(
        "" to stringResource(R.string.language_system),
        "zh" to stringResource(R.string.language_zh),
        "en" to stringResource(R.string.language_en),
    )
    Card(Modifier.fillMaxWidth()) {
        options.forEach { (language, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = language == selected, role = Role.RadioButton) {
                        val tag = when (language) {
                            "zh" -> "zh-CN"
                            else -> language
                        }
                        AppCompatDelegate.setApplicationLocales(
                            if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
                            else LocaleListCompat.forLanguageTags(tag),
                        )
                    }
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                RadioButton(selected = language == selected, onClick = null)
                Spacer(Modifier.width(12.dp))
                Text(label, modifier = Modifier.padding(vertical = 12.dp))
            }
        }
    }
}

/** 两步选择：先选基准货币，再选目标货币；已添加的组合不可再选。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PairPicker(
    existing: Set<String>,
    locale: Locale,
    onDismiss: () -> Unit,
    onPicked: (String) -> Unit,
) {
    var base by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        ) {
            if (base != null) {
                IconButton(onClick = { base = null }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            } else {
                Spacer(Modifier.width(16.dp))
            }
            Text(
                text = base?.let { stringResource(R.string.pick_quote, it) } ?: stringResource(R.string.pick_base),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }

        val from = base
        val common = Currencies.COMMON.filter { it != from }
        val others = Currencies.OTHERS.filter { it != from }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item { PickerHeader(stringResource(R.string.common_currencies)) }
            items(common, key = { "c-$it" }) { code -> PickerRow(code, from, existing, locale) { pick(it, from, onPicked) { base = it } } }
            item { PickerHeader(stringResource(R.string.all_currencies)) }
            items(others, key = { "o-$it" }) { code -> PickerRow(code, from, existing, locale) { pick(it, from, onPicked) { base = it } } }
        }
    }
}

private inline fun pick(code: String, base: String?, onPicked: (String) -> Unit, setBase: (String) -> Unit) {
    if (base == null) setBase(code) else onPicked("$base/$code")
}

@Composable
private fun PickerHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun PickerRow(code: String, base: String?, existing: Set<String>, locale: Locale, onClick: (String) -> Unit) {
    val taken = base != null && "$base/$code" in existing
    ListItem(
        modifier = Modifier.clickable(enabled = !taken) { onClick(code) }.padding(horizontal = 8.dp),
        leadingContent = { Text(Currencies.flag(code), fontSize = 26.sp) },
        headlineContent = {
            Text(code, color = if (taken) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
        },
        supportingContent = { Text(Currencies.name(code, locale)) },
        trailingContent = if (taken) {
            { Text(stringResource(R.string.pair_exists), style = MaterialTheme.typography.labelMedium) }
        } else {
            null
        },
    )
}
