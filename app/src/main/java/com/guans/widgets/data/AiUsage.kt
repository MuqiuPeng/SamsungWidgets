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
 * 读取网站用量页背后的接口，拿到 5 小时和每周额度的真实重置时间。
 *
 * 这些都不是公开接口，随时可能变。网站由 Cloudflare 保护，所以不用普通 HTTP 请求，
 * 而是在隐藏的 WebView 里发起。登录 Cookie 平时用 Android Keystore 加密保存，
 * 只在读取时放进 WebView，读完立刻清掉。读取只是查询，不会开始新的 5 小时窗口。
 */
abstract class UsageSource(
    val loginUrl: String,
    private val origin: String,
    private val cookieDomain: String,
    private val sessionCookie: String,
    prefsName: String,
    private val sessionPreset: RoutinePreset,
    private val weeklyPreset: RoutinePreset,
    @StringRes val sectionRes: Int,
    @StringRes val noteRes: Int,
    @StringRes val loginRes: Int,
) {
    private val prefsName = prefsName

    /** 在同源页面里运行，用 ToolboxBridge.done 交回 {status, body}。 */
    protected abstract val fetchScript: String

    protected abstract fun parse(usage: JSONObject): Usage

    data class Usage(val sessionReset: Long, val sessionPercent: Int, val weeklyReset: Long, val weeklyPercent: Int)

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    fun connected(context: Context): Boolean = prefs(context).contains("cookie")

    fun owns(preset: RoutinePreset): Boolean = preset == sessionPreset || preset == weeklyPreset

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
            sessionPreset -> cached.sessionReset to cached.sessionPercent
            weeklyPreset -> cached.weeklyReset to cached.weeklyPercent
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
        CookieManager.getInstance().getCookie(origin)?.takeIf { it.contains(sessionCookie) }

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

    // ---------- 读取

    /** 读取一次并写入缓存；失败时保留上一次的时间，并记下原因。 */
    suspend fun refresh(context: Context) = lock.withLock {
        if (loginOpen) return@withLock
        val saved = prefs(context).getString("cookie", null) ?: return@withLock
        val cookie = runCatching { GitHubAuth.decrypt(saved) }.getOrNull()
        val response = if (cookie == null) {
            null
        } else {
            runCatching { fetch(context, cookie) }
                .onFailure { ErrorLog.record(context, "usage $prefsName", it) }
                .getOrNull()
        }
        apply(context, response)
    }

    private fun apply(context: Context, response: JSONObject?) {
        val status = response?.optInt("status") ?: -1
        val body = response?.optString("body").orEmpty()
        val usage = if (status == 200) runCatching { parse(JSONObject(body)) }.getOrNull() else null
        val edit = prefs(context).edit()
        if (usage != null) {
            edit.putLong("session_reset", usage.sessionReset)
                .putInt("session_percent", usage.sessionPercent)
                .putLong("weekly_reset", usage.weeklyReset)
                .putInt("weekly_percent", usage.weeklyPercent)
                .putLong("updated_at", System.currentTimeMillis())
                .remove("error")
        } else {
            // 只记状态码：响应里有账号信息
            ErrorLog.note(context, "usage $prefsName", "HTTP $status")
            val expired = status == 401 || (status == 403 && body.trimStart().startsWith("{"))
            edit.putString("error", if (expired) ERROR_LOGIN else ERROR_FAILED)
        }
        edit.apply()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun fetch(context: Context, cookie: String): JSONObject? = withContext(Dispatchers.Main) {
        val cookies = CookieManager.getInstance()
        cookie.split("; ").forEach { cookies.setCookie(origin, "$it; Domain=$cookieDomain; Path=/; Secure; HttpOnly") }
        val result = CompletableDeferred<JSONObject>()
        val webView = WebView(context.applicationContext)
        try {
            webView.settings.javaScriptEnabled = true
            webView.addJavascriptInterface(Bridge(result), "ToolboxBridge")
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript(fetchScript, null)
                }
            }
            // 用一个很小的页面取得同源环境
            webView.loadUrl("$origin/robots.txt")
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

    companion object {
        private const val TIMEOUT_MS = 30_000L
        private const val ERROR_LOGIN = "login"
        private const val ERROR_FAILED = "failed"

        /** WebView 的 Cookie 全应用共用，读取和登录不能同时进行。 */
        private val lock = Mutex()

        @Volatile
        private var loginOpen = false

        /** 打开登录页前调用：暂停后台读取，从干净的 Cookie 开始。 */
        suspend fun beginLogin() = lock.withLock {
            loginOpen = true
            clearCookies()
        }

        fun endLogin() {
            loginOpen = false
        }

        suspend fun clearCookies() = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                CookieManager.getInstance().removeAllCookies { continuation.resume(Unit) }
            }
        }

        val all: List<UsageSource> get() = listOf(ClaudeUsage, CodexUsage)

        fun anyConnected(context: Context): Boolean = all.any { it.connected(context) }

        fun live(context: Context, preset: RoutinePreset): LiveReset? =
            all.firstOrNull { it.owns(preset) }?.live(context, preset)
    }
}

object ClaudeUsage : UsageSource(
    loginUrl = "https://claude.ai/login",
    origin = "https://claude.ai",
    cookieDomain = ".claude.ai",
    sessionCookie = "sessionKey=",
    prefsName = "claude_usage",
    sessionPreset = RoutinePreset.CLAUDE_SESSION,
    weeklyPreset = RoutinePreset.CLAUDE_WEEKLY,
    sectionRes = R.string.section_claude,
    noteRes = R.string.claude_section_note,
    loginRes = R.string.claude_login,
) {
    /** 接口路径是 2026-10 在网站上实测的。 */
    override val fetchScript = """
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

    override fun parse(usage: JSONObject): Usage {
        val session = usage.optJSONObject("five_hour")
        val weekly = usage.optJSONObject("seven_day")
        return Usage(time(session), session?.optInt("utilization") ?: 0, time(weekly), weekly?.optInt("utilization") ?: 0)
    }

    private fun time(window: JSONObject?): Long {
        if (window == null || window.isNull("resets_at")) return 0L
        return runCatching { OffsetDateTime.parse(window.getString("resets_at")).toInstant().toEpochMilli() }.getOrDefault(0L)
    }
}

object CodexUsage : UsageSource(
    loginUrl = "https://chatgpt.com/",
    origin = "https://chatgpt.com",
    cookieDomain = ".chatgpt.com",
    sessionCookie = "__Secure-next-auth.session-token",
    prefsName = "codex_usage",
    sessionPreset = RoutinePreset.CODEX_SESSION,
    weeklyPreset = RoutinePreset.CODEX_WEEKLY,
    sectionRes = R.string.section_codex,
    noteRes = R.string.codex_section_note,
    loginRes = R.string.codex_login,
) {
    private const val DAY_SECONDS = 86_400

    /** 接口路径是 2026-10 在网站上实测的；访问令牌只留在页面里，不保存。 */
    override val fetchScript = """
        (async () => {
          const report = async (r) => ToolboxBridge.done(JSON.stringify({ status: r.status, body: await r.text() }));
          try {
            const session = await fetch('/api/auth/session');
            if (!session.ok) return report(session);
            const token = (await session.json()).accessToken;
            if (!token) return ToolboxBridge.done(JSON.stringify({ status: 401, body: '{}' }));
            report(await fetch('/backend-api/wham/usage', { headers: { authorization: 'Bearer ' + token } }));
          } catch (e) {
            ToolboxBridge.done(JSON.stringify({ status: 0, body: String(e) }));
          }
        })();
    """.trimIndent()

    /** 字段名不代表窗口长度，按 limit_window_seconds 区分 5 小时和每周。 */
    override fun parse(usage: JSONObject): Usage {
        val limits = usage.getJSONObject("rate_limit")
        val windows = listOf("primary_window", "secondary_window").mapNotNull(limits::optJSONObject)
        val session = windows.firstOrNull { it.optInt("limit_window_seconds") < DAY_SECONDS }
        val weekly = windows.firstOrNull { it.optInt("limit_window_seconds") >= DAY_SECONDS }
        return Usage(time(session), percent(session), time(weekly), percent(weekly))
    }

    private fun percent(window: JSONObject?): Int = window?.optDouble("used_percent", 0.0)?.toInt() ?: 0

    /** 没开始的窗口总是显示“现在起满额”，重置时间会一直往后推，所以当作未开始。 */
    private fun time(window: JSONObject?): Long {
        if (window == null) return 0L
        val idle = percent(window) == 0 && window.optLong("reset_after_seconds") >= window.optLong("limit_window_seconds")
        return if (idle) 0L else window.optLong("reset_at") * 1000
    }
}
