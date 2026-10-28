package com.guans.widgets.widgets.exchangerate

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
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

/** 最后一次成功拉取的结果。 */
data class CachedRates(val rates: List<Pair<String, Double?>>, val updatedAt: Long, val source: String)

fun decodeRates(encoded: String?): List<Pair<String, Double?>> =
    encoded.orEmpty().lines().filter { it.isNotBlank() }.map { line ->
        val (pair, value) = line.split('=', limit = 2)
        pair to value.toDoubleOrNull()
    }

/** 拉取汇率、写入缓存并推送给所有汇率小工具；缓存同时供设置页和新添加的实例使用。 */
object RateUpdater {
    private const val CACHE = "rate_cache"

    fun cachePrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(CACHE, Context.MODE_PRIVATE)

    fun cached(context: Context): CachedRates? {
        val cache = cachePrefs(context)
        val rates = cache.getString("rates", null) ?: return null
        return CachedRates(decodeRates(rates), cache.getLong("updated_at", 0L), cache.getString("source", "") ?: "")
    }

    suspend fun refresh(context: Context) {
        val ids = GlanceAppWidgetManager(context).getGlanceIds(ExchangeRateWidget::class.java)
        pushAll(context, ids) { it[RateKeys.LOADING] = true }

        try {
            val table = RateRepository.fetchUsdTable()
            val encoded = Settings.pairs(context).joinToString("\n") { pair ->
                "$pair=${RateRepository.cross(table, pair) ?: ""}"
            }
            val now = System.currentTimeMillis()
            cachePrefs(context).edit()
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
        val cache = cachePrefs(context)
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

/** 按设置的间隔定时刷新（最短 15 分钟），更快的更新靠小工具上的刷新按钮。 */
object RateScheduler {
    private const val PERIODIC = "exchange_rate_periodic"
    private const val ONCE = "exchange_rate_once"

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(context: Context) {
        val minutes = Settings.refreshMinutes(context)
        val manager = WorkManager.getInstance(context)
        if (minutes <= 0) {
            manager.cancelUniqueWork(PERIODIC)
            return
        }
        val request = PeriodicWorkRequestBuilder<RateRefreshWorker>(minutes.toLong(), TimeUnit.MINUTES)
            .setConstraints(online)
            .build()
        manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** 设置变化后调用：桌面上没有汇率小工具时不安排定时任务。 */
    fun reschedule(context: Context) {
        val ids = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, ExchangeRateWidgetReceiver::class.java))
        if (ids.isNotEmpty()) schedule(context)
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
