package com.guans.widgets.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.guans.widgets.R
import com.guans.widgets.data.ClaudeUsage
import com.guans.widgets.data.formatElapsed
import com.guans.widgets.data.formatRemaining
import kotlinx.coroutines.delay

/** 关联 claude.ai：显示真实额度，或引导登录。 */
@Composable
fun ClaudeUsageCard(
    connected: Boolean,
    cached: ClaudeUsage.Cached,
    now: Long,
    onLogin: () -> Unit,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!connected || cached.needsLogin) {
                Text(
                    stringResource(if (connected) R.string.claude_relogin_long else R.string.claude_login_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(onClick = onLogin) { Text(stringResource(R.string.claude_login)) }
            }
            if (connected) {
                fun line(reset: Long) =
                    if (reset > now) formatRemaining(context, reset - now) else context.getString(R.string.claude_idle)
                Text(stringResource(R.string.claude_session_line, line(cached.sessionReset), cached.sessionPercent))
                Text(stringResource(R.string.claude_weekly_line, line(cached.weeklyReset), cached.weeklyPercent))
                Text(
                    when {
                        cached.error != null && !cached.needsLogin -> stringResource(R.string.claude_failed)
                        cached.updatedAt == 0L -> stringResource(R.string.never_updated)
                        else -> stringResource(R.string.github_updated, formatElapsed(context, now - cached.updatedAt))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (cached.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onRefresh) { Text(stringResource(R.string.refresh_now)) }
                    TextButton(onClick = onDisconnect) { Text(stringResource(R.string.claude_disconnect)) }
                }
            }
        }
    }
}

/** 全屏登录页。账号由你自己在网页里登录，检测到登录成功后交回 Cookie。 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ClaudeLoginDialog(onDismiss: () -> Unit, onLoggedIn: (String) -> Unit) {
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        ClaudeUsage.clearCookies()
        ready = true
        while (true) {
            delay(1_000)
            ClaudeUsage.loginCookie()?.let {
                onLoggedIn(it)
                return@LaunchedEffect
            }
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.systemBarsPadding().imePadding()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                    }
                    Text(stringResource(R.string.claude_login), style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    stringResource(R.string.claude_login_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                if (ready) {
                    AndroidView(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        factory = { context ->
                            WebView(context).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                webViewClient = WebViewClient()
                                loadUrl(ClaudeUsage.LOGIN_URL)
                            }
                        },
                        onRelease = { it.destroy() },
                    )
                }
            }
        }
    }
}
