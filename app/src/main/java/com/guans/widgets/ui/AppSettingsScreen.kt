package com.guans.widgets.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.guans.widgets.BuildConfig
import com.guans.widgets.R
import com.guans.widgets.update.UpdateChecker

/** 应用设置：语言、更新、关于。 */
@Composable
fun AppSettingsScreen(contentPadding: PaddingValues, checkingUpdates: Boolean, onCheckUpdates: () -> Unit) {
    val context = LocalContext.current
    LazyColumn(contentPadding = screenPadding(contentPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SectionHeader(stringResource(R.string.section_language)) }
        item { LanguageOptions() }

        item { SectionHeader(stringResource(R.string.section_updates), stringResource(R.string.update_auto_note)) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                ) {
                    Text(
                        stringResource(R.string.version_label, BuildConfig.VERSION_NAME),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onCheckUpdates, enabled = !checkingUpdates) {
                        Text(stringResource(if (checkingUpdates) R.string.checking_updates else R.string.check_updates))
                    }
                }
            }
        }

        item { SectionHeader(stringResource(R.string.section_about)) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.about_text), style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(
                    onClick = { UpdateChecker.openInBrowser(context, UpdateChecker.REPO_URL) },
                    modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
                ) {
                    Text(stringResource(R.string.about_source))
                }
            }
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
                        val tag = if (language == "zh") "zh-CN" else language
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
