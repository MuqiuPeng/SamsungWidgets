package com.guans.widgets.widgets.routine

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
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
import com.guans.widgets.data.ClaudeUsage
import com.guans.widgets.data.RoutinePreset
import com.guans.widgets.data.Routines
import java.util.concurrent.TimeUnit

object RoutineKeys {
    /** 每次推送写入当前时间，让小工具重绘。 */
    val TICK = longPreferencesKey("tick")
    val ID = ActionParameters.Key<String>("routine_id")
}

class RoutineTickWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        RoutineTicker.pushAll(applicationContext)
        return Result.success()
    }
}

class ClaudeRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        ClaudeUsage.refresh(applicationContext)
        RoutineTicker.pushAll(applicationContext)
        return Result.success()
    }
}

/** 关联 claude.ai 后定时读取真实额度；新窗口开始后大约 15 分钟内能读到。 */
object ClaudeScheduler {
    private const val PERIODIC = "claude_usage"
    private const val NOW = "claude_usage_now"

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<ClaudeRefreshWorker>(15, TimeUnit.MINUTES).setConstraints(network).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun refreshNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<ClaudeRefreshWorker>().setConstraints(network).build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(PERIODIC)
            cancelUniqueWork(NOW)
        }
    }
}

/**
 * 小工具上显示的是“多久之前”，需要随时间重绘；不联网，只是重算。
 * Android 限制定时任务最短 15 分钟，所以显示精确到小时。
 */
object RoutineTicker {
    private const val PERIODIC = "routine_tick"
    private const val UNDO_EXPIRE = "routine_undo_expire"
    private const val LOOP_RESET = "routine_loop_reset"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<RoutineTickWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** 撤销按钮过了时间窗口后收起。 */
    fun expireUndoLater(context: Context) {
        val request = OneTimeWorkRequestBuilder<RoutineTickWorker>()
            .setInitialDelay(Routines.UNDO_WINDOW_MS + 5_000, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNDO_EXPIRE, ExistingWorkPolicy.REPLACE, request)
    }

    /** 点一个事项：真实额度只需要重新读取；读取失败时才退回手动计时。 */
    fun tap(context: Context, preset: RoutinePreset) {
        val live = ClaudeUsage.live(context, preset)
        if (live == null || live.failed) {
            Routines.markDone(context, preset)
            expireUndoLater(context)
        }
        if (live != null) ClaudeScheduler.refreshNow(context)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(PERIODIC)
            cancelUniqueWork(UNDO_EXPIRE)
            cancelUniqueWork(LOOP_RESET)
        }
    }

    fun hasWidgets(context: Context): Boolean =
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, RoutineWidgetReceiver::class.java)).isNotEmpty()

    /** 数据变化后调用：重绘所有习惯小工具。 */
    suspend fun pushAll(context: Context) {
        val widget = RoutineWidget()
        val now = System.currentTimeMillis()
        val ids = GlanceAppWidgetManager(context).getGlanceIds(RoutineWidget::class.java)
        ids.forEach { id ->
            updateAppWidgetState(context, id) { it[RoutineKeys.TICK] = now }
            widget.update(context, id)
        }
        if (ids.isNotEmpty()) scheduleLoopReset(context, now)
    }

    /** 循环倒计时到点时立即重绘一次，不用等 15 分钟的定时任务。 */
    private fun scheduleLoopReset(context: Context, now: Long) {
        val next = Routines.all(context).mapNotNull { it.nextReset(now) }.minOrNull() ?: return
        val request = OneTimeWorkRequestBuilder<RoutineTickWorker>()
            .setInitialDelay(next - now + 2_000, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(LOOP_RESET, ExistingWorkPolicy.REPLACE, request)
    }
}
