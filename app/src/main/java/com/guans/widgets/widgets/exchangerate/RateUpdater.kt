package com.guans.widgets.widgets.exchangerate

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
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
import com.guans.widgets.data.RateRepository
import com.guans.widgets.data.Settings
import java.util.concurrent.TimeUnit

/** 每个小工具实例的 Glance 状态键。 */
object RateKeys {
    /** 每行 "USD/CNY=6.6662"，取不到的币种为 "USD/XXX="。 */
    val RATES = stringPreferencesKey("rates")
    val UPDATED_AT = longPreferencesKey("updated_at")
    val SOURCE = stringPreferencesKey("source")
    val ERROR = stringPreferencesKey("error")
    val LOADING = booleanPreferencesKey("loading")
}

/** 拉取汇率并推送给所有汇率小工具；最后一次成功结果另存一份，供新添加的实例直接显示。 */
object RateUpdater {
    private const val CACHE = "rate_cache"

    suspend fun refresh(context: Context) {
        val ids = GlanceAppWidgetManager(context).getGlanceIds(ExchangeRateWidget::class.java)
        if (ids.isEmpty()) return
        pushAll(context, ids) { it[RateKeys.LOADING] = true }

        try {
            val table = RateRepository.fetchUsdTable()
            val encoded = Settings.pairs(context).joinToString("\n") { pair ->
                "$pair=${RateRepository.cross(table, pair) ?: ""}"
            }
            val now = System.currentTimeMillis()
            context.getSharedPreferences(CACHE, Context.MODE_PRIVATE).edit()
                .putString("rates", encoded)
                .putLong("updated_at", now)
                .putString("source", table.source)
                .apply()
            pushAll(context, ids) {
                it[RateKeys.RATES] = encoded
                it[RateKeys.UPDATED_AT] = now
                it[RateKeys.SOURCE] = table.source
                it.remove(RateKeys.ERROR)
                it[RateKeys.LOADING] = false
            }
        } catch (e: Exception) {
            pushAll(context, ids) {
                it[RateKeys.ERROR] = e.message ?: e.javaClass.simpleName
                it[RateKeys.LOADING] = false
            }
        }
    }

    /** 实例状态为空时，用最后一次成功的结果填充。 */
    fun seedFromCache(context: Context, prefs: MutablePreferences) {
        if (prefs[RateKeys.RATES] != null) return
        val cache = context.getSharedPreferences(CACHE, Context.MODE_PRIVATE)
        val rates = cache.getString("rates", null) ?: return
        prefs[RateKeys.RATES] = rates
        prefs[RateKeys.UPDATED_AT] = cache.getLong("updated_at", 0L)
        prefs[RateKeys.SOURCE] = cache.getString("source", "") ?: ""
    }

    private suspend fun pushAll(
        context: Context,
        ids: List<GlanceId>,
        block: (MutablePreferences) -> Unit,
    ) {
        val widget = ExchangeRateWidget()
        ids.forEach { id ->
            updateAppWidgetState(context, id) { block(it) }
            widget.update(context, id)
        }
    }
}

class RateRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        RateUpdater.refresh(applicationContext)
        return Result.success()
    }
}

/** 系统允许的最短周期是 15 分钟；更快的更新靠点击小工具上的刷新按钮。 */
object RateScheduler {
    private const val PERIODIC = "exchange_rate_periodic"
    private const val ONCE = "exchange_rate_once"

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<RateRefreshWorker>(15, TimeUnit.MINUTES)
            .setConstraints(online)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun refreshNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<RateRefreshWorker>().setConstraints(online).build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(PERIODIC)
            cancelUniqueWork(ONCE)
        }
    }
}
