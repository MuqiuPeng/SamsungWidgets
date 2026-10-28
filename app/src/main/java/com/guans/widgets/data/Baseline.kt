package com.guans.widgets.data

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 日涨跌的基准：前一日的汇率。
 * currency-api 某日的快照在当天 UTC 0 点前后生成，相当于前一交易日收盘。
 * - 当前数据是盘中（Coinbase）：基准取当天（UTC）的快照
 * - 当前数据本身是 D 日快照：基准取 D-1 日快照
 * 基准一天只需下载一次，存在本地。
 */
object Baseline {
    private const val PREFS = "baseline"
    private const val RETRY_MS = 60 * 60 * 1000L

    suspend fun forTable(context: Context, table: UsdTable, codes: Set<String>): Map<String, Double>? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = LocalDate.now(ZoneOffset.UTC)
        // 按明确日期取：CDN 上的 latest 可能缓存了更早的一份
        val wanted = if (table.live) today else (table.date ?: today).minusDays(1)
        val cachedDate = prefs.getString("date", null)?.let(LocalDate::parse)
        val fetchedAt = prefs.getLong("fetched_at", 0L)
        val cachedCodes = prefs.getString("codes", "").orEmpty().split(',').toSet()

        // 当天快照还没生成时先用前一天的，过一小时再试
        val fresh = cachedDate != null && cachedCodes.containsAll(codes) && (
            cachedDate == wanted ||
                (table.live && cachedDate == today.minusDays(1) && System.currentTimeMillis() - fetchedAt < RETRY_MS)
            )
        if (!fresh) {
            val snapshot = if (table.live) {
                runCatching { RateRepository.fetchSnapshot(today) }
                    .getOrElse { RateRepository.fetchSnapshot(today.minusDays(1)) }
            } else {
                RateRepository.fetchSnapshot(wanted)
            }
            val keep = codes + "USD"
            val rates = JSONObject(snapshot.rates.filterKeys { it in keep })
            prefs.edit()
                .putString("date", snapshot.date.toString())
                .putLong("fetched_at", System.currentTimeMillis())
                .putString("codes", keep.joinToString(","))
                .putString("rates", rates.toString())
                .apply()
            return snapshot.rates.filterKeys { it in keep }
        }
        val json = JSONObject(prefs.getString("rates", "{}").orEmpty())
        return json.keys().asSequence().associateWith { json.getDouble(it) }
    }
}
