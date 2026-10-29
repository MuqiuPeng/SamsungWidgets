package com.guans.widgets.data

import android.content.Context
import android.content.SharedPreferences
import com.guans.widgets.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/** Actions 最近一次运行的结果。 */
enum class CiState { SUCCESS, FAILURE, RUNNING, NONE }

/** 一个仓库在小工具上显示的状态。时间都是毫秒时间戳，0 表示没有。 */
data class RepoStatus(
    val fullName: String,
    val release: String?,
    val releasedAt: Long,
    val ci: CiState,
    val ciAt: Long,
    val openIssues: Int,
    val stars: Int,
    val pushedAt: Long,
) {
    val name: String get() = fullName.substringAfter('/')

    fun toJson(): JSONObject = JSONObject()
        .put("full_name", fullName)
        .put("release", release ?: "")
        .put("released_at", releasedAt)
        .put("ci", ci.name)
        .put("ci_at", ciAt)
        .put("open_issues", openIssues)
        .put("stars", stars)
        .put("pushed_at", pushedAt)

    companion object {
        fun fromJson(json: JSONObject) = RepoStatus(
            fullName = json.getString("full_name"),
            release = json.optString("release").ifEmpty { null },
            releasedAt = json.optLong("released_at"),
            ci = runCatching { CiState.valueOf(json.optString("ci")) }.getOrDefault(CiState.NONE),
            ciAt = json.optLong("ci_at"),
            openIssues = json.optInt("open_issues"),
            stars = json.optInt("stars"),
            pushedAt = json.optLong("pushed_at"),
        )
    }
}

/**
 * GitHub 公开 API，不登录：每个 IP 每小时 60 次。
 * 每个仓库查 3 个接口（仓库信息、最新 Release、最近一次 Actions），都带 ETag；
 * 没变化时 GitHub 返回 304，不计入次数，所以定时刷新基本不耗额度。
 */
object GitHub {
    private const val PREFS = "github"
    const val MAX_REPOS = 6

    /** 0 表示只手动刷新。 */
    val REFRESH_OPTIONS = listOf(15, 30, 60, 0)

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 账号默认取本应用仓库的所有者。 */
    fun account(context: Context): String =
        prefs(context).getString("account", null) ?: UpdateChecker.REPO.substringBefore('/')

    fun repos(context: Context): List<String> {
        val raw = prefs(context).getString("repos", null) ?: return listOf(UpdateChecker.REPO)
        return raw.split(',').filter { it.contains('/') }
    }

    fun setRepos(context: Context, repos: List<String>) {
        prefs(context).edit().putString("repos", repos.take(MAX_REPOS).joinToString(",")).apply()
    }

    fun refreshMinutes(context: Context): Int = prefs(context).getInt("refresh_minutes", 30)

    fun setRefreshMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt("refresh_minutes", minutes).apply()
    }

    // ---------- 缓存：最后一次成功的结果

    data class Cached(val statuses: List<RepoStatus>, val updatedAt: Long, val error: String?)

    fun cached(context: Context): Cached {
        val prefs = prefs(context)
        val array = runCatching { JSONArray(prefs.getString("statuses", "[]")) }.getOrDefault(JSONArray())
        val byName = (0 until array.length()).map { RepoStatus.fromJson(array.getJSONObject(it)) }.associateBy { it.fullName }
        // 按当前设置的顺序，只显示还在列表里的仓库
        val statuses = repos(context).mapNotNull { byName[it] }
        return Cached(statuses, prefs.getLong("updated_at", 0L), prefs.getString("error", null))
    }

    /** 拉取所有选中仓库的状态并写入缓存；有仓库失败时保留它上一次的结果。 */
    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        val previous = cached(context).statuses.associateBy { it.fullName }
        var error: String? = null
        val statuses = repos(context).mapNotNull { repo ->
            runCatching { fetchStatus(context, repo) }
                .onFailure { error = it.message ?: it.javaClass.simpleName }
                .getOrNull() ?: previous[repo]
        }
        prefs(context).edit()
            .putString("statuses", JSONArray(statuses.map { it.toJson() }).toString())
            .putLong("updated_at", if (error == null) System.currentTimeMillis() else prefs(context).getLong("updated_at", 0L))
            .putString("error", error)
            .apply()
    }

    /** 账号下的公开仓库，按最近推送排序，供设置页选择。 */
    suspend fun listRepos(context: Context, account: String = account(context)): List<String> = withContext(Dispatchers.IO) {
        val array = JSONArray(get(context, "https://api.github.com/users/$account/repos?per_page=100&sort=pushed")!!)
        (0 until array.length()).map { array.getJSONObject(it).getString("full_name") }
    }

    private fun fetchStatus(context: Context, repo: String): RepoStatus {
        val info = JSONObject(get(context, "https://api.github.com/repos/$repo")!!)
        val release = get(context, "https://api.github.com/repos/$repo/releases/latest", allowMissing = true)
            ?.let(::JSONObject)
        val runs = JSONObject(get(context, "https://api.github.com/repos/$repo/actions/runs?per_page=1")!!)
            .optJSONArray("workflow_runs")
        val run = if (runs != null && runs.length() > 0) runs.getJSONObject(0) else null
        val ci = when {
            run == null -> CiState.NONE
            run.optString("status") != "completed" -> CiState.RUNNING
            run.optString("conclusion") == "success" -> CiState.SUCCESS
            run.optString("conclusion") in setOf("skipped", "neutral", "cancelled") -> CiState.NONE
            else -> CiState.FAILURE
        }
        return RepoStatus(
            fullName = info.getString("full_name"),
            release = release?.optString("tag_name")?.ifEmpty { null },
            releasedAt = time(release?.optString("published_at")),
            ci = ci,
            ciAt = time(run?.optString("updated_at")),
            // GitHub 把 PR 也算进 open_issues_count
            openIssues = info.optInt("open_issues_count"),
            stars = info.optInt("stargazers_count"),
            pushedAt = time(info.optString("pushed_at")),
        )
    }

    private fun time(iso: String?): Long =
        iso?.takeIf { it.isNotEmpty() && it != "null" }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L

    /** 带 ETag 的 GET：304 时用本地缓存的响应。allowMissing 时 404 返回 null（比如没有 Release）。 */
    private fun get(context: Context, url: String, allowMissing: Boolean = false): String? {
        val etags = context.getSharedPreferences("github_etag", Context.MODE_PRIVATE)
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", "Toolbox")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        val cachedBody = etags.getString("body:$url", null)
        etags.getString("etag:$url", null)?.takeIf { cachedBody != null }?.let { conn.setRequestProperty("If-None-Match", it) }
        try {
            return when (val code = conn.responseCode) {
                304 -> cachedBody
                in 200..299 -> {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.getHeaderField("ETag")?.let { etags.edit().putString("etag:$url", it).putString("body:$url", body).apply() }
                    body
                }
                404 -> if (allowMissing) null else error("HTTP 404: $url")
                403, 429 -> error("rate_limited")
                else -> error("HTTP $code: $url")
            }
        } finally {
            conn.disconnect()
        }
    }
}
