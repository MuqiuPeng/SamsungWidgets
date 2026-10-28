package com.guans.widgets.data

import android.content.Context

/** 全局设置：要显示的货币对，形如 "USD/CNY"。 */
object Settings {
    private const val PREFS = "settings"
    private const val KEY_PAIRS = "pairs"
    val DEFAULT_PAIRS = listOf("USD/CNY", "EUR/CNY", "HKD/CNY", "JPY/CNY", "USD/KRW")

    fun pairs(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PAIRS, null) ?: return DEFAULT_PAIRS
        return parsePairs(raw).ifEmpty { DEFAULT_PAIRS }
    }

    fun setPairs(context: Context, pairs: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PAIRS, pairs.joinToString(","))
            .apply()
    }

    /** 接受 "usd/cny, EUR-CNY；jpycny" 等写法。 */
    fun parsePairs(raw: String): List<String> =
        raw.split(',', '，', ';', '；', '\n', ' ')
            .map { it.trim().uppercase().replace("-", "/") }
            .mapNotNull {
                when {
                    Regex("[A-Z]{3}/[A-Z]{3}").matches(it) -> it
                    Regex("[A-Z]{6}").matches(it) -> it.substring(0, 3) + "/" + it.substring(3)
                    else -> null
                }
            }
            .distinct()
}
