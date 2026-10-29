package com.guans.widgets.ui

import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import com.guans.widgets.data.CiState
import com.guans.widgets.data.GitHub
import com.guans.widgets.data.GitHubAuth
import com.guans.widgets.data.RepoStatus
import com.guans.widgets.data.formatElapsed
import com.guans.widgets.widgets.github.GitHubScheduler
import com.guans.widgets.widgets.github.GitHubUpdater
import kotlinx.coroutines.launch

/** GitHub 小工具的设置：要看的仓库、刷新间隔、添加到桌面。 */
@Composable
fun GitHubScreen(contentPadding: PaddingValues, onAddToHome: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repos = remember { mutableStateListOf<String>().apply { addAll(GitHub.repos(context)) } }
    var cache by remember { mutableStateOf(GitHub.cached(context)) }
    var refreshMinutes by remember { mutableIntStateOf(GitHub.refreshMinutes(context)) }
    var picking by remember { mutableStateOf(false) }
    var linking by remember { mutableStateOf(false) }
    var accounts by remember { mutableStateOf(GitHubAuth.accounts(context).map { it.login }) }

    // 后台刷新写入缓存后，页面跟着更新
    DisposableEffect(Unit) {
        val prefs = GitHub.prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> cache = GitHub.cached(context) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun saveRepos(refresh: Boolean) {
        GitHub.setRepos(context, repos.toList())
        cache = GitHub.cached(context)
        if (refresh) GitHubScheduler.refreshNow(context) else scope.launch { GitHubUpdater.push(context) }
    }

    val statuses = cache.statuses.associateBy { it.fullName }
    val now = System.currentTimeMillis()

    LazyColumn(contentPadding = screenPadding(contentPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SectionHeader(stringResource(R.string.section_accounts), stringResource(R.string.accounts_hint)) }
        item {
            AccountsCard(
                accounts = accounts,
                onLink = { linking = true },
                onRemove = { login ->
                    GitHubAuth.remove(context, login)
                    GitHub.removeAccountRepos(context, login)
                    accounts = GitHubAuth.accounts(context).map { it.login }
                    repos.clear()
                    repos.addAll(GitHub.repos(context))
                    saveRepos(refresh = false)
                },
            )
        }
        item { SectionHeader(stringResource(R.string.section_repos), stringResource(R.string.repos_hint)) }
        item {
            Card(Modifier.fillMaxWidth()) {
                if (repos.isEmpty()) {
                    Text(
                        stringResource(R.string.repos_empty),
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ReorderableColumn(
                    items = repos.toList(),
                    key = { it },
                    onMove = { from, to -> repos.add(to, repos.removeAt(from)) },
                    onDragEnd = { saveRepos(refresh = false) },
                ) { entry, handle ->
                    RepoRow(GitHub.fullName(entry), statuses[GitHub.fullName(entry)], now, handle) {
                        repos.remove(entry)
                        saveRepos(refresh = false)
                    }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { picking = true }, enabled = accounts.isNotEmpty() && repos.size < GitHub.MAX_REPOS) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.add_repo))
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.repos_limit, GitHub.MAX_REPOS),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when {
                        cache.error == "rate_limited" -> stringResource(R.string.github_rate_limited_long)
                        cache.error == "unauthorized" -> stringResource(R.string.github_unauthorized)
                        cache.updatedAt == 0L -> stringResource(R.string.never_updated)
                        else -> stringResource(R.string.github_updated, formatElapsed(context, now - cache.updatedAt))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (cache.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { GitHubScheduler.refreshNow(context) }) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.refresh_now))
                }
            }
        }

        item { SectionHeader(stringResource(R.string.section_notifications), stringResource(R.string.notify_note)) }
        item { NotificationSettings() }

        item { SectionHeader(stringResource(R.string.section_refresh), stringResource(R.string.github_refresh_note)) }
        item {
            RefreshChips(selected = refreshMinutes) { minutes ->
                refreshMinutes = minutes
                GitHub.setRefreshMinutes(context, minutes)
                GitHubScheduler.reschedule(context)
            }
        }

        item {
            SectionHeader(
                stringResource(R.string.section_widget),
                stringResource(R.string.add_to_home_hint, stringResource(R.string.widget_github_name)),
            )
        }
        item {
            OutlinedButton(onClick = onAddToHome) {
                Text(stringResource(R.string.add_to_home))
            }
        }
    }

    if (linking) {
        LinkAccountDialog(
            onDismiss = { linking = false },
            onLinked = {
                linking = false
                accounts = GitHubAuth.accounts(context).map { it.login }
                picking = true
            },
        )
    }
    if (picking) {
        RepoPicker(
            existing = repos.map { GitHub.fullName(it) }.toSet(),
            onDismiss = { picking = false },
            onPicked = { entry ->
                picking = false
                if (repos.none { GitHub.fullName(it) == GitHub.fullName(entry) } && repos.size < GitHub.MAX_REPOS) {
                    repos.add(entry)
                    saveRepos(refresh = true)
                }
            },
        )
    }
}

@Composable
private fun RepoRow(repo: String, status: RepoStatus?, now: Long, handle: @Composable () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = handle,
        headlineContent = { Text(repo.substringAfter('/')) },
        supportingContent = {
            val parts = buildList {
                status?.let {
                    add(ciLabel(it.ci))
                    if (it.pushedAt > 0) add(stringResource(R.string.github_pushed, formatElapsed(context, now - it.pushedAt)))
                    if (it.openIssues > 0) add(stringResource(R.string.github_issues, it.openIssues))
                }
            }
            Text(parts.joinToString(" · ").ifEmpty { repo })
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(status?.release ?: "—", style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.pair_delete), color = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        },
    )
}

@Composable
private fun ciLabel(ci: CiState): String = stringResource(
    when (ci) {
        CiState.SUCCESS -> R.string.ci_success
        CiState.FAILURE -> R.string.ci_failure
        CiState.RUNNING -> R.string.ci_running
        CiState.NONE -> R.string.ci_none
    },
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RefreshChips(selected: Int, onSelect: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GitHub.REFRESH_OPTIONS.forEach { minutes ->
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

