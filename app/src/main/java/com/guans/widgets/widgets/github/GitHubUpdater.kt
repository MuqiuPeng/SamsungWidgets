package com.guans.widgets.widgets.github

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.guans.widgets.data.GitHub
import java.util.concurrent.TimeUnit

object GitHubKeys {
    /** 每次推送写入当前时间，让小工具重绘。 */
    val TICK = longPreferencesKey("tick")
    val LOADING = booleanPreferencesKey("loading")
    val REPO = ActionParameters.Key<String>("repo")
}

/** 拉取仓库状态并重绘所有 GitHub 小工具；数据存在缓存里，设置页也用它。 */
object GitHubUpdater {
    suspend fun refresh(context: Context) {
        push(context, loading = true)
        try {
            GitHub.refresh(context)
        } finally {
            push(context, loading = false)
        }
    }

    /** 只重绘（时间显示随时间变化、设置变了）。 */
    suspend fun push(context: Context, loading: Boolean = false) {
        val widget = GitHubWidget()
        val now = System.currentTimeMillis()
        GlanceAppWidgetManager(context).getGlanceIds(GitHubWidget::class.java).forEach { id ->
            updateAppWidgetState(context, id) {
                it[GitHubKeys.TICK] = now
                it[GitHubKeys.LOADING] = loading
            }
            widget.update(context, id)
        }
    }
}

class GitHubRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        GitHubUpdater.refresh(applicationContext)
        return Result.success()
    }
}

/** 按设置的间隔定时刷新（最短 15 分钟）。 */
object GitHubScheduler {
    private const val PERIODIC = "github_periodic"
    private const val ONCE = "github_once"

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(context: Context) {
        val minutes = GitHub.refreshMinutes(context)
        val manager = WorkManager.getInstance(context)
        if (minutes <= 0) {
            manager.cancelUniqueWork(PERIODIC)
            return
        }
        val request = PeriodicWorkRequestBuilder<GitHubRefreshWorker>(minutes.toLong(), TimeUnit.MINUTES)
            .setConstraints(online)
            .build()
        manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** 设置变化后调用：桌面上没有 GitHub 小工具时不安排定时任务。 */
    fun reschedule(context: Context) {
        if (hasWidgets(context)) schedule(context)
    }

    fun hasWidgets(context: Context): Boolean =
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, GitHubWidgetReceiver::class.java)).isNotEmpty()

    fun refreshNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<GitHubRefreshWorker>().setConstraints(online).build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(PERIODIC)
            cancelUniqueWork(ONCE)
        }
    }
}
