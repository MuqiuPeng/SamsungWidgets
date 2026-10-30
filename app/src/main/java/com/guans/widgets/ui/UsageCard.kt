package com.guans.widgets.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.guans.widgets.R
import com.guans.widgets.data.ErrorLog
import com.guans.widgets.data.UsageSource
import com.guans.widgets.data.formatElapsed
import com.guans.widgets.data.formatRemaining
import com.guans.widgets.widgets.routine.RoutineTicker
import com.guans.widgets.widgets.routine.UsageScheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 关联一个网站账号：显示真实额度，或引导登录。 */
@Composable
fun UsageCard(source: UsageSource, now: Long) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var connected by remember { mutableStateOf(source.connected(context)) }
    var cached by remember { mutableStateOf(source.cached(context)) }
    var loggingIn by remember { mutableStateOf(false) }

    DisposableEffect(source) {
        val prefs = source.prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            connected = source.connected(context)
            cached = source.cached(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!connected || cached.needsLogin) {
                Text(
                    stringResource(if (connected) R.string.claude_relogin_long else R.string.claude_login_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(onClick = { loggingIn = true }) { Text(stringResource(source.loginRes)) }
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
                    TextButton(onClick = { UsageScheduler.refreshNow(context) }) { Text(stringResource(R.string.refresh_now)) }
                    TextButton(onClick = {
                        source.disconnect(context)
                        if (!UsageSource.anyConnected(context)) UsageScheduler.cancel(context)
                        scope.launch { RoutineTicker.pushAll(context) }
                    }) { Text(stringResource(R.string.claude_disconnect)) }
                }
            }
        }
    }

    if (loggingIn) {
        val onLoggedIn = { cookie: String ->
            loggingIn = false
            scope.launch {
                source.saveLogin(context, cookie)
                UsageSource.endLogin()
                UsageScheduler.schedule(context)
                UsageScheduler.refreshNow(context)
            }
            Unit
        }
        if (source.sessionPageUrl != null) {
            PasteLoginDialog(source, onDismiss = { loggingIn = false }, onLoggedIn = onLoggedIn)
        } else {
            LoginDialog(source, onDismiss = { loggingIn = false }, onLoggedIn = onLoggedIn)
        }
    }
}

/** 内嵌网页登录不了时：在手机浏览器里打开会话页，把内容粘贴回来。 */
@Composable
private fun PasteLoginDialog(source: UsageSource, onDismiss: () -> Unit, onLoggedIn: (String) -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(source.loginRes)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.codex_paste_steps))
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source.sessionPageUrl)))
                }) { Text(stringResource(R.string.codex_paste_open)) }
                OutlinedButton(onClick = {
                    text = clipboard.getText()?.text.orEmpty()
                    failed = false
                }) { Text(stringResource(R.string.codex_paste_clipboard)) }
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        failed = false
                    },
                    maxLines = 3,
                    isError = failed,
                    label = { Text(stringResource(R.string.codex_paste_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (failed) Text(stringResource(R.string.codex_paste_error), color = MaterialTheme.colorScheme.error)
                Text(
                    stringResource(R.string.codex_paste_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cookie = source.cookieFromPaste(text)
                if (cookie != null) onLoggedIn(cookie) else failed = true
            }) { Text(stringResource(R.string.routine_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** 登录页打不开时，把页面报错记进错误日志。这时还没登录，日志里没有账号信息。 */
private class LoginChromeClient(private val context: Context) : WebChromeClient() {
    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
        if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
            ErrorLog.note(context, "login page", message.message().take(200))
        }
        return true
    }
}

private class LoginViewClient(private val context: Context) : WebViewClient() {
    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        ErrorLog.note(context, "login page", "${request.url.host}: ${error.errorCode} ${error.description}")
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
        if (response.statusCode >= 400) ErrorLog.note(context, "login page", "${request.url.host}: HTTP ${response.statusCode}")
    }
}

/** 全屏登录页。账号由你自己在网页里登录，检测到登录成功后交回 Cookie。 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LoginDialog(source: UsageSource, onDismiss: () -> Unit, onLoggedIn: (String) -> Unit) {
    var ready by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose { UsageSource.endLogin() }
    }
    LaunchedEffect(Unit) {
        UsageSource.beginLogin()
        ready = true
        while (true) {
            delay(1_000)
            source.loginCookie()?.let {
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
                    Text(stringResource(source.loginRes), style = MaterialTheme.typography.titleMedium)
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
                                settings.databaseEnabled = true
                                settings.useWideViewPort = true
                                settings.loadWithOverviewMode = true
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                webChromeClient = LoginChromeClient(context)
                                webViewClient = LoginViewClient(context)
                                loadUrl(source.loginUrl)
                            }
                        },
                        onRelease = { it.destroy() },
                    )
                }
            }
        }
    }
}
