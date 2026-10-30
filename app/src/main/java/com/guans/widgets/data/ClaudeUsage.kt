package com.guans.widgets.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.StringRes
import com.guans.widgets.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.time.OffsetDateTime
import kotlin.coroutines.resume

/** 额度的真实重置时间；小工具到点后显示 idleRes。failed 表示最近一次读取失败，此时退回手动计时。 */
data class LiveReset(val resetAt: Long, val percent: Int, @StringRes val idleRes: Int, val failed: Boolean)

/**
 * 读取 claude.ai 用量页背后的接口，拿到 5 小时和每周额度的真实重置时间。
 *
 * 这不是公开接口，随时可能变。它由网站的 Cloudflare 保护，所以不用普通 HTTP 请求，
 * 而是在隐藏的 WebView 里发起。登录 Cookie 平时用 Android Keystore 加密保存，
 * 只在读取时放进 WebView，读完立刻清掉。读取只是查询，不会开始新的 5 小时窗口。
 */
object ClaudeUsage {
    const val LOGIN_URL = "https://claude.ai/login"
    private const val ORIGIN = "https://claude.ai"
    private const val SESSION_COOKIE = "sessionKey="
    private const val PREFS = "claude_usage"
    private const val TIMEOUT_MS = 30_000L
    private const val ERROR_LOGIN = "login"
    private const val ERROR_FAILED = "failed"

    /** 接口路径是 2026-10 在网站上实测的；只把状态码和响应体交回来。 */
    private val FETCH_SCRIPT = """
        (async () => {
          const report = async (r) => ToolboxBridge.done(JSON.stringify({ status: r.status, body: await r.text() }));
          try {
            const headers = { accept: 'application/json' };
            const orgs = await fetch('/api/organizations', { headers });
            if (!orgs.ok) return report(orgs);
            const list = await orgs.json();
            const org = list.find((o) => (o.capabilities || []).includes('chat')) || list[0];
            report(await fetch('/api/organizations/' + org.uuid + '/usage', { headers }));
          } catch (e) {
            ToolboxBridge.done(JSON.stringify({ status: 0, body: String(e) }));
          }
        })();
    """.trimIndent()

    private val lock = Mutex()

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun connected(context: Context): Boolean = prefs(context).contains("cookie")

    data class Cached(
        val sessionReset: Long,
        val sessionPercent: Int,
        val weeklyReset: Long,
        val weeklyPercent: Int,
        val updatedAt: Long,
        val error: String?,
    ) {
        val needsLogin: Boolean get() = error == ERROR_LOGIN
    }

    fun cached(context: Context): Cached {
        val prefs = prefs(context)
        return Cached(
            sessionReset = prefs.getLong("session_reset", 0L),
            sessionPercent = prefs.getInt("session_percent", 0),
            weeklyReset = prefs.getLong("weekly_reset", 0L),
            weeklyPercent = prefs.getInt("weekly_percent", 0),
            updatedAt = prefs.getLong("updated_at", 0L),
            error = prefs.getString("error", null),
        )
    }

    fun live(context: Context, preset: RoutinePreset): LiveReset? {
        if (!connected(context)) return null
        val cached = cached(context)
        val (reset, percent) = when (preset) {
            RoutinePreset.CLAUDE_SESSION -> cached.sessionReset to cached.sessionPercent
            RoutinePreset.CLAUDE_WEEKLY -> cached.weeklyReset to cached.weeklyPercent
            else -> return null
        }
        val idle = when (cached.error) {
            null -> R.string.claude_idle
            ERROR_LOGIN -> R.string.claude_relogin
            else -> R.string.claude_unavailable
        }
        return LiveReset(reset, percent, idle, failed = cached.error != null)
    }

    // ---------- 登录

    fun loginCookie(): String? =
        CookieManager.getInstance().getCookie(ORIGIN)?.takeIf { it.contains(SESSION_COOKIE) }

    /** 登录成功后调用：Cookie 加密保存，WebView 里不留。 */
    suspend fun saveLogin(context: Context, cookie: String) {
        store(context, cookie)
        prefs(context).edit().remove("error").apply()
        clearCookies()
    }

    fun disconnect(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun store(context: Context, cookie: String) {
        prefs(context).edit().putString("cookie", GitHubAuth.encrypt(cookie)).apply()
    }

    suspend fun clearCookies() = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            CookieManager.getInstance().removeAllCookies { continuation.resume(Unit) }
        }
    }

    // ---------- 读取

    /** 读取一次并写入缓存；失败时保留上一次的时间，并记下原因。 */
    suspend fun refresh(context: Context) = lock.withLock {
        val saved = prefs(context).getString("cookie", null) ?: return@withLock
        val cookie = runCatching { GitHubAuth.decrypt(saved) }.getOrNull()
        val response = if (cookie == null) {
            null
        } else {
            runCatching { fetch(context, cookie) }
                .onFailure { ErrorLog.record(context, "claude usage", it) }
                .getOrNull()
        }
        apply(context, response)
    }

    private fun apply(context: Context, response: JSONObject?) {
        val status = response?.optInt("status") ?: -1
        val body = response?.optString("body").orEmpty()
        val usage = if (status == 200) runCatching { JSONObject(body) }.getOrNull() else null
        val edit = prefs(context).edit()
        if (usage != null) {
            val session = usage.optJSONObject("five_hour")
            val weekly = usage.optJSONObject("seven_day")
            edit.putLong("session_reset", time(session))
                .putInt("session_percent", session?.optInt("utilization") ?: 0)
                .putLong("weekly_reset", time(weekly))
                .putInt("weekly_percent", weekly?.optInt("utilization") ?: 0)
                .putLong("updated_at", System.currentTimeMillis())
                .remove("error")
        } else {
            // 只记状态码：响应里可能有账号信息
            ErrorLog.note(context, "claude usage", "HTTP $status")
            val expired = status == 401 || (status == 403 && body.trimStart().startsWith("{"))
            edit.putString("error", if (expired) ERROR_LOGIN else ERROR_FAILED)
        }
        edit.apply()
    }

    private fun time(window: JSONObject?): Long {
        if (window == null || window.isNull("resets_at")) return 0L
        return runCatching { OffsetDateTime.parse(window.getString("resets_at")).toInstant().toEpochMilli() }.getOrDefault(0L)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun fetch(context: Context, cookie: String): JSONObject? = withContext(Dispatchers.Main) {
        val cookies = CookieManager.getInstance()
        cookie.split("; ").forEach { cookies.setCookie(ORIGIN, "$it; Domain=.claude.ai; Path=/; Secure; HttpOnly") }
        val result = CompletableDeferred<JSONObject>()
        val webView = WebView(context.applicationContext)
        try {
            webView.settings.javaScriptEnabled = true
            webView.addJavascriptInterface(Bridge(result), "ToolboxBridge")
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript(FETCH_SCRIPT, null)
                }
            }
            // 用一个很小的页面取得同源环境
            webView.loadUrl("$ORIGIN/robots.txt")
            withTimeoutOrNull(TIMEOUT_MS) { result.await() }.also {
                // 网站可能换了新 Cookie，保存最新的
                loginCookie()?.let { latest -> store(context, latest) }
            }
        } finally {
            webView.destroy()
            clearCookies()
        }
    }

    private class Bridge(private val result: CompletableDeferred<JSONObject>) {
        @JavascriptInterface
        fun done(json: String) {
            val response = runCatching { JSONObject(json) }.getOrNull() ?: return
            // Cloudflare 的验证页不是结果：验证通过后页面重新加载，会再读一次，直到超时
            val challenge = response.optInt("status") == 403 && !response.optString("body").trimStart().startsWith("{")
            if (!challenge) result.complete(response)
        }
    }
}
