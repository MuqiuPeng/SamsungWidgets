package com.guans.widgets.ui

import com.guans.widgets.data.ErrorLog
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.guans.widgets.R
import com.guans.widgets.data.GitHub
import com.guans.widgets.data.GitHubAuth
import com.guans.widgets.update.UpdateChecker
import com.guans.widgets.widgets.github.GitHubNotifier

/** 已关联的 GitHub 账号，以及“关联账号”按钮。 */
@Composable
fun AccountsCard(accounts: List<String>, onLink: () -> Unit, onRemove: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        if (!GitHubAuth.configured) {
            Text(
                stringResource(R.string.github_not_configured),
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Card
        }
        accounts.forEach { login ->
            var menu by remember { mutableStateOf(false) }
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text(login) },
                trailingContent = {
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.github_unlink), color = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; onRemove(login) },
                            )
                        }
                    }
                },
            )
        }
        TextButton(onClick = onLink, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.github_link))
        }
    }
}

/** 设备授权：显示验证码（已复制到剪贴板），打开 GitHub 页面确认，确认后自动完成。 */
@Composable
fun LinkAccountDialog(onDismiss: () -> Unit, onLinked: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var code by remember { mutableStateOf<GitHubAuth.DeviceCode?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        error = null
        code = null
        try {
            val device = GitHubAuth.start()
            code = device
            clipboard.setText(AnnotatedString(device.userCode))
            GitHubAuth.await(context, device)
            onLinked()
        } catch (e: GitHubAuth.AuthException) {
            ErrorLog.note(context, "github link", e.reason)
            error = context.getString(
                when (e.reason) {
                    "access_denied" -> R.string.github_link_denied
                    "expired_token" -> R.string.github_link_expired
                    else -> R.string.github_link_failed
                },
            )
        } catch (e: Exception) {
            ErrorLog.record(context, "github link", e)
            error = context.getString(R.string.github_link_failed)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.github_link)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val current = code
                val message = error
                when {
                    message != null -> Text(message, color = MaterialTheme.colorScheme.error)
                    current == null -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    else -> {
                        Text(stringResource(R.string.github_link_steps), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            current.userCode,
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                        Text(
                            stringResource(R.string.github_link_waiting),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            val current = code
            when {
                error != null -> TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.github_retry)) }
                current != null -> TextButton(onClick = { UpdateChecker.openInBrowser(context, current.verificationUri) }) {
                    Text(stringResource(R.string.github_open_page))
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** 按账号分组列出可选的仓库（含私有）；已添加的不可再选。选中返回 "owner/repo@login"。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoPicker(existing: Set<String>, onDismiss: () -> Unit, onPicked: (String) -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val accounts = remember { GitHubAuth.accounts(context) }
    // 每个账号的仓库：没有记录表示加载中；空列表表示该账号还没安装 GitHub App
    val lists = remember { mutableStateMapOf<String, List<String>>() }
    val failed = remember { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(Unit) {
        accounts.forEach { account ->
            runCatching { GitHub.listRepos(context, account) }
                .onSuccess { lists[account.login] = it }
                .onFailure { failed[account.login] = true }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            stringResource(R.string.pick_repo),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            accounts.forEach { account ->
                val login = account.login
                item(key = "h-$login") {
                    Text(
                        login,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                val list = lists[login]
                when {
                    failed[login] == true -> item(key = "f-$login") {
                        Text(
                            stringResource(R.string.repos_load_failed),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                        )
                    }
                    list == null -> item(key = "l-$login") {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    list.isEmpty() -> item(key = "e-$login") { InstallHint() }
                    else -> items(list, key = { "$login:$it" }) { repo ->
                        val taken = repo in existing
                        ListItem(
                            modifier = Modifier
                                .clickable(enabled = !taken) { onPicked(GitHub.entry(repo, login)) }
                                .padding(horizontal = 8.dp),
                            headlineContent = {
                                Text(
                                    repo,
                                    color = if (taken) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                )
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
            item(key = "install") {
                TextButton(
                    onClick = { UpdateChecker.openInBrowser(context, GitHubAuth.installUrl) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) { Text(stringResource(R.string.github_install_more)) }
            }
        }
    }
}

@Composable
private fun InstallHint() {
    val context = LocalContext.current
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(
            stringResource(R.string.github_not_installed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { UpdateChecker.openInBrowser(context, GitHubAuth.installUrl) }) {
            Text(stringResource(R.string.github_install))
        }
    }
}

/** 通知开关：Actions 失败 / 恢复、新 Release。Android 13 起打开时先申请通知权限。 */
@Composable
fun NotificationSettings() {
    val context = LocalContext.current
    var actions by remember { mutableStateOf(GitHub.notifyActions(context)) }
    var releases by remember { mutableStateOf(GitHub.notifyReleases(context)) }
    var allowed by remember { mutableStateOf(GitHubNotifier.canPost(context)) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }

    fun ensurePermission() {
        if (!allowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            request.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Card(Modifier.fillMaxWidth()) {
        SwitchRow(stringResource(R.string.notify_actions), actions) {
            actions = it
            GitHub.setNotifyActions(context, it)
            if (it) ensurePermission()
        }
        SwitchRow(stringResource(R.string.notify_releases), releases) {
            releases = it
            GitHub.setNotifyReleases(context, it)
            if (it) ensurePermission()
        }
        if (!allowed && (actions || releases)) {
            TextButton(onClick = { ensurePermission() }, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)) {
                Text(stringResource(R.string.notify_allow))
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { onChange(!checked) },
        headlineContent = { Text(label) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}
