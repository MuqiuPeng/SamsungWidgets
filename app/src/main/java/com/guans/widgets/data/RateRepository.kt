package com.guans.widgets.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 以 USD 为基准的汇率表：1 USD = rates[code] 单位该货币。
 * live 表示盘中数据；否则是 date（UTC）当天的日快照。
 */
data class UsdTable(
    val source: String,
    val rates: Map<String, Double>,
    val live: Boolean = false,
    val date: LocalDate? = null,
)

/**
 * 免费、无需 API key 的数据源，按顺序回退。
 * 所有源都换算成 USD 基准表，交叉汇率 A/B = rates[B] / rates[A]，一次请求覆盖所有货币对。
 */
object RateRepository {

    private interface Source {
        fun fetch(): UsdTable
    }

    /** 盘中更新，是这几个里更新最勤的（非官方外汇 API，取中间价）。 */
    private object Coinbase : Source {
        override fun fetch(): UsdTable {
            val rates = JSONObject(get("https://api.coinbase.com/v2/exchange-rates?currency=USD"))
                .getJSONObject("data").getJSONObject("rates")
            val map = rates.keys().asSequence().associateWith { rates.getString(it).toDouble() }
            return UsdTable("Coinbase", map, live = true)
        }
    }

    /** fawazahmed0/exchange-api，每日快照，可按日期取历史；jsDelivr 与 Cloudflare 两个镜像。 */
    private class CurrencyApi(private val date: LocalDate?, private val mirror: Int) : Source {
        override fun fetch(): UsdTable {
            val tag = date?.toString() ?: "latest"
            val url = if (mirror == 0) {
                "https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@$tag/v1/currencies/usd.min.json"
            } else {
                "https://$tag.currency-api.pages.dev/v1/currencies/usd.min.json"
            }
            val json = JSONObject(get(url))
            val usd = json.getJSONObject("usd")
            val map = usd.keys().asSequence().associate { it.uppercase() to usd.getDouble(it) }
            return UsdTable("currency-api", map, date = LocalDate.parse(json.getString("date")))
        }
    }

    /** ExchangeRate-API 开放端点，每日更新。 */
    private object OpenErApi : Source {
        override fun fetch(): UsdTable {
            val json = JSONObject(get("https://open.er-api.com/v6/latest/USD"))
            val rates = json.getJSONObject("rates")
            val map = rates.keys().asSequence().associateWith { rates.getDouble(it) }
            val date = Instant.ofEpochSecond(json.getLong("time_last_update_unix")).atZone(ZoneOffset.UTC).toLocalDate()
            return UsdTable("ExchangeRate-API", map, date = date)
        }
    }

    // Cloudflare 镜像的 latest 比 jsDelivr 的更新及时，排在前面
    private val sources: List<Source> = listOf(Coinbase, CurrencyApi(null, 1), CurrencyApi(null, 0), OpenErApi)

    suspend fun fetchUsdTable(): UsdTable = firstAvailable(sources)

    /** currency-api 指定日期（UTC）的日快照。 */
    suspend fun fetchSnapshot(date: LocalDate): UsdTable =
        firstAvailable(listOf(CurrencyApi(date, 0), CurrencyApi(date, 1)))

    private suspend fun firstAvailable(candidates: List<Source>): UsdTable = withContext(Dispatchers.IO) {
        var last: Exception? = null
        for (source in candidates) {
            try {
                val table = source.fetch()
                if (table.rates.isNotEmpty()) return@withContext table.copy(rates = table.rates + ("USD" to 1.0))
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("no source available")
    }

    fun cross(table: UsdTable, pair: String): Double? = cross(table.rates, pair)

    fun cross(rates: Map<String, Double>, pair: String): Double? {
        val (from, to) = pair.split('/')
        val a = rates[from] ?: return null
        val b = rates[to] ?: return null
        return b / a
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        conn.setRequestProperty("User-Agent", "Toolbox")
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode} from $url")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
