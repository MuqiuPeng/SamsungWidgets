package com.guans.widgets.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 以 USD 为基准的汇率表：1 USD = rates[code] 单位该货币。 */
data class UsdTable(val source: String, val rates: Map<String, Double>)

/**
 * 免费、无需 API key 的数据源，按顺序回退。
 * 所有源都换算成 USD 基准表，交叉汇率 A/B = rates[B] / rates[A]，一次请求覆盖所有货币对。
 */
object RateRepository {

    private interface Source {
        val name: String
        fun fetch(): Map<String, Double>
    }

    /** 盘中更新，是这几个里更新最勤的（非官方外汇 API，取中间价）。 */
    private object Coinbase : Source {
        override val name = "Coinbase"
        override fun fetch(): Map<String, Double> {
            val rates = JSONObject(get("https://api.coinbase.com/v2/exchange-rates?currency=USD"))
                .getJSONObject("data").getJSONObject("rates")
            return rates.keys().asSequence().associateWith { rates.getString(it).toDouble() }
        }
    }

    /** fawazahmed0/exchange-api，每日更新，jsDelivr 与 Cloudflare 两个镜像。 */
    private class CurrencyApi(override val name: String, private val url: String) : Source {
        override fun fetch(): Map<String, Double> {
            val usd = JSONObject(get(url)).getJSONObject("usd")
            return usd.keys().asSequence().associate { it.uppercase() to usd.getDouble(it) }
        }
    }

    /** ExchangeRate-API 开放端点，每日更新。 */
    private object OpenErApi : Source {
        override val name = "ExchangeRate-API"
        override fun fetch(): Map<String, Double> {
            val rates = JSONObject(get("https://open.er-api.com/v6/latest/USD")).getJSONObject("rates")
            return rates.keys().asSequence().associateWith { rates.getDouble(it) }
        }
    }

    private val sources: List<Source> = listOf(
        Coinbase,
        CurrencyApi("currency-api", "https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@latest/v1/currencies/usd.min.json"),
        CurrencyApi("currency-api", "https://latest.currency-api.pages.dev/v1/currencies/usd.min.json"),
        OpenErApi,
    )

    suspend fun fetchUsdTable(): UsdTable = withContext(Dispatchers.IO) {
        var last: Exception? = null
        for (source in sources) {
            try {
                val rates = source.fetch()
                if (rates.isNotEmpty()) return@withContext UsdTable(source.name, rates + ("USD" to 1.0))
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("no source available")
    }

    fun cross(table: UsdTable, pair: String): Double? {
        val (from, to) = pair.split('/')
        val a = table.rates[from] ?: return null
        val b = table.rates[to] ?: return null
        return b / a
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        conn.setRequestProperty("User-Agent", "SamsungWidgets/0.1")
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode} from $url")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
