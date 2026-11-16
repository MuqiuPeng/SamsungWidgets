package com.guans.widgets.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 最近 [DAYS] 天的日汇率，用于走势曲线。
 * 每天一个点：currency-api 当天（UTC）的日快照，以 USD 为基准，只存用到的货币。
 * 更早的日期自动清理；缺的日期按需从 currency-api 回补。
 */
object RateHistory {
    const val DAYS = 30
    private const val PREFS = "rate_history"

    /** 走势曲线上的一个点。 */
    data class Point(val date: LocalDate, val rate: Double)

    /** 记下某天的快照（只保留 [codes]），并清理窗口外的日期。 */
    fun record(context: Context, date: LocalDate, rates: Map<String, Double>, codes: Set<String>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val all = HistoryStore.decodeAll(prefs.all)
        val merged = HistoryStore.merge(all, date, rates.filterKeys { it in codes })
        val kept = HistoryStore.prune(merged, today())
        prefs.edit().clear().apply {
            for ((d, r) in kept) putString(d.toString(), HistoryStore.encode(r))
        }.apply()
    }

    /** 某个货币对在窗口内的日汇率，按日期从早到晚。 */
    fun series(context: Context, pair: String): List<Point> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return HistoryStore.series(HistoryStore.decodeAll(prefs.all), pair, today())
    }

    /**
     * 补齐窗口内缺 [codes] 的日期（最多 [DAYS] 个请求，并发 6 个）。
     * 当天快照可能还没生成，取不到的日期跳过，下次再补。返回补到的天数。
     */
    suspend fun backfill(context: Context, codes: Set<String>): Int = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val have = HistoryStore.decodeAll(prefs.all)
        val missing = HistoryStore.missingDates(have, codes, today())
        if (missing.isEmpty()) return@withContext 0
        val limit = Semaphore(6)
        val fetched = coroutineScope {
            missing.map { date ->
                async {
                    limit.withPermit {
                        runCatching { RateRepository.fetchSnapshot(date) }.getOrNull()
                            ?.takeIf { it.date == date }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        val keep = codes + "USD"
        for (table in fetched) record(context, table.date!!, table.rates, keep)
        fetched.size
    }

    private fun today(): LocalDate = LocalDate.now(ZoneOffset.UTC)
}

/** 纯数据部分，便于单元测试：日期 → (货币 → 1 USD 兑多少该货币)。 */
object HistoryStore {

    fun encode(rates: Map<String, Double>): String =
        rates.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }

    fun decode(raw: String): Map<String, Double> =
        raw.split(',').mapNotNull { part ->
            val (code, value) = part.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            value.toDoubleOrNull()?.let { code to it }
        }.toMap()

    fun decodeAll(prefs: Map<String, *>): Map<LocalDate, Map<String, Double>> =
        prefs.mapNotNull { (key, value) ->
            val date = runCatching { LocalDate.parse(key) }.getOrNull() ?: return@mapNotNull null
            (value as? String)?.let { date to decode(it) }
        }.toMap()

    /** 同一天已有的货币保留，新货币补进去。 */
    fun merge(
        all: Map<LocalDate, Map<String, Double>>,
        date: LocalDate,
        rates: Map<String, Double>,
    ): Map<LocalDate, Map<String, Double>> = all + (date to (all[date].orEmpty() + rates))

    /** 窗口：今天及之前 [RateHistory.DAYS] - 1 天。 */
    fun window(today: LocalDate): List<LocalDate> =
        (RateHistory.DAYS - 1 downTo 0).map { today.minusDays(it.toLong()) }

    fun prune(all: Map<LocalDate, Map<String, Double>>, today: LocalDate): Map<LocalDate, Map<String, Double>> {
        val first = today.minusDays((RateHistory.DAYS - 1).toLong())
        return all.filterKeys { !it.isBefore(first) && !it.isAfter(today) }
    }

    fun missingDates(all: Map<LocalDate, Map<String, Double>>, codes: Set<String>, today: LocalDate): List<LocalDate> =
        window(today).filter { date -> all[date]?.keys?.containsAll(codes) != true }

    fun series(all: Map<LocalDate, Map<String, Double>>, pair: String, today: LocalDate): List<RateHistory.Point> =
        window(today).mapNotNull { date ->
            all[date]?.let { RateRepository.cross(it, pair) }?.let { RateHistory.Point(date, it) }
        }
}
