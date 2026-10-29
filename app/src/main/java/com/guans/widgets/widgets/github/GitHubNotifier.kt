package com.guans.widgets.widgets.github

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.guans.widgets.R
import com.guans.widgets.data.CiState
import com.guans.widgets.data.GitHub
import com.guans.widgets.data.RepoStatus

/**
 * 后台刷新时比较前后两次的状态，有变化就发系统通知：
 * Actions 由通过变失败、由失败恢复，以及出了新 Release。
 * 第一次拿到某个仓库的数据时不通知（没有可比较的旧状态）。
 */
object GitHubNotifier {
    private const val CHANNEL = "github"

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun notifyChanges(context: Context, previous: Map<String, RepoStatus>, current: List<RepoStatus>) {
        if (!canPost(context)) return
        current.forEach { now ->
            val before = previous[now.fullName] ?: return@forEach
            if (GitHub.notifyActions(context)) {
                if (now.ci == CiState.FAILURE && before.ci != CiState.FAILURE) {
                    post(context, now, "ci", context.getString(R.string.notify_ci_failed, now.name),
                        "https://github.com/${now.fullName}/actions")
                } else if (now.ci == CiState.SUCCESS && before.ci == CiState.FAILURE) {
                    post(context, now, "ci", context.getString(R.string.notify_ci_fixed, now.name),
                        "https://github.com/${now.fullName}/actions")
                }
            }
            if (GitHub.notifyReleases(context) && now.release != null && before.release != null && now.release != before.release) {
                post(context, now, "release", context.getString(R.string.notify_release, now.name, now.release),
                    "https://github.com/${now.fullName}/releases/tag/${now.release}")
            }
        }
    }

    private fun post(context: Context, repo: RepoStatus, kind: String, title: String, url: String) {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            "${repo.fullName}/$kind".hashCode(),
            Intent(Intent.ACTION_VIEW, Uri.parse(url)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_code)
            .setContentTitle(title)
            .setContentText(repo.fullName)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching {
            // 同一仓库同一类通知只保留最新一条
            NotificationManagerCompat.from(context).notify("${repo.fullName}/$kind".hashCode(), notification)
        }
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.notify_channel_github), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }
}
