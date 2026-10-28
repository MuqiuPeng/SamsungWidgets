package com.guans.widgets.data

import android.content.Context

/** 全局设置：要显示的货币对（形如 "USD/CNY"）和自动刷新间隔。 */
object Settings {
    private const val PREFS = "settings"
    private const val KEY_PAIRS = "pairs"
    private const val KEY_REFRESH = "refresh_minutes"

    const val MAX_PAIRS = 8
    val DEFAULT_PAIRS = listOf("USD/CNY", "EUR/CNY", "HKD/CNY", "JPY/CNY", "USD/KRW")

    /** 0 表示只手动刷新。15 分钟是系统允许的最短周期。 */
    val REFRESH_OPTIONS = listOf(15, 30, 60, 0)

    private val PAIR = Regex("[A-Z]{3}/[A-Z]{3}")

    fun pairs(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_PAIRS, null) ?: return DEFAULT_PAIRS
        return raw.split(',').filter { PAIR.matches(it) }.distinct()
    }

    fun setPairs(context: Context, pairs: List<String>) {
        prefs(context).edit().putString(KEY_PAIRS, pairs.joinToString(",")).apply()
    }

    fun refreshMinutes(context: Context): Int = prefs(context).getInt(KEY_REFRESH, 15)

    fun setRefreshMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_REFRESH, minutes).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
