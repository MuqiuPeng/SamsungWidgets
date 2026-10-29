package com.guans.widgets.ui

import com.guans.widgets.data.ErrorLog
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.guans.widgets.BuildConfig
import com.guans.widgets.R
import com.guans.widgets.update.Release
import com.guans.widgets.update.UpdateChecker
import kotlinx.coroutines.launch

private sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val percent: Int) : UpdateState
    data class Failed(val release: Release) : UpdateState
}

/** 应用界面 + 更新提示：启动时静默检查一次，有新版本（且未被跳过）就弹窗。 */
@Composable
fun AppWithUpdates(request: DestinationRequest?, onAddToHome: (Destination) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    // 切换语言会重建界面，用 saveable 避免重复检查
    var autoChecked by rememberSaveable { mutableStateOf(false) }

    fun check(manual: Boolean) {
        if (manual) state = UpdateState.Checking
        scope.launch {
            val release = runCatching { UpdateChecker.latest() }.getOrNull()
            state = when {
                release == null -> {
                    if (manual) Toast.makeText(context, R.string.check_failed, Toast.LENGTH_SHORT).show()
                    UpdateState.Idle
                }
                !UpdateChecker.isNewer(release.version, BuildConfig.VERSION_NAME) -> {
                    if (manual) Toast.makeText(context, R.string.up_to_date, Toast.LENGTH_SHORT).show()
                    UpdateState.Idle
                }
                !manual && release.version == UpdateChecker.skippedVersion(context) -> UpdateState.Idle
                else -> UpdateState.Available(release)
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!autoChecked) {
            autoChecked = true
            check(manual = false)
        }
    }

    fun startUpdate(release: Release) {
        if (release.apkUrl == null) {
            UpdateChecker.openInBrowser(context, release.pageUrl)
            state = UpdateState.Idle
            return
        }
        if (!UpdateChecker.canInstall(context)) {
            Toast.makeText(context, R.string.update_allow_install, Toast.LENGTH_LONG).show()
            UpdateChecker.openInstallPermissionSettings(context)
            return
        }
        state = UpdateState.Downloading(release, 0)
        scope.launch {
            try {
                val apk = UpdateChecker.download(context, release) { percent ->
                    scope.launch { state = UpdateState.Downloading(release, percent) }
                }
                state = UpdateState.Idle
                UpdateChecker.install(context, apk)
            } catch (e: Exception) {
                ErrorLog.record(context, "update download", e)
                state = UpdateState.Failed(release)
            }
        }
    }

    ToolboxApp(
        request = request,
        onAddToHome = onAddToHome,
        checkingUpdates = state is UpdateState.Checking,
        onCheckUpdates = { check(manual = true) },
    )

    when (val s = state) {
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = { state = UpdateState.Idle },
            title = { Text(stringResource(R.string.update_available, s.release.version)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.update_current, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (s.release.notes.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            plainNotes(s.release.notes),
                            modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { startUpdate(s.release) }) { Text(stringResource(R.string.update_now)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        UpdateChecker.skip(context, s.release.version)
                        state = UpdateState.Idle
                    }) { Text(stringResource(R.string.update_skip)) }
                    TextButton(onClick = { state = UpdateState.Idle }) { Text(stringResource(R.string.update_later)) }
                }
            },
        )

        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.update_available, s.release.version)) },
            text = {
                Column {
                    Text(stringResource(R.string.update_downloading, s.percent))
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {},
        )

        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = { state = UpdateState.Idle },
            title = { Text(stringResource(R.string.update_available, s.release.version)) },
            text = { Text(stringResource(R.string.update_failed)) },
            confirmButton = {
                TextButton(onClick = {
                    UpdateChecker.openInBrowser(context, s.release.pageUrl)
                    state = UpdateState.Idle
                }) { Text(stringResource(R.string.update_open_browser)) }
            },
            dismissButton = {
                TextButton(onClick = { state = UpdateState.Idle }) { Text(stringResource(R.string.update_later)) }
            },
        )

        else -> Unit
    }
}

/** Release 说明是 Markdown，弹窗里去掉标题符号、把列表符号换成圆点。 */
private fun plainNotes(markdown: String): String =
    markdown.lines().joinToString("\n") { line ->
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("#") -> trimmed.trimStart('#').trim()
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> "• " + trimmed.substring(2)
            else -> line
        }
    }.trim()
