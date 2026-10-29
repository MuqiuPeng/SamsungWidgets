package com.guans.widgets.data

import android.content.Context

/** 全局设置：要显示的货币对（形如 "USD/CNY"）、自动刷新间隔、小工具背景样式。 */
object Settings {
    private const val PREFS = "settings"
    private const val KEY_PAIRS = "pairs"
    private const val KEY_REFRESH = "refresh_minutes"
    private const val KEY_GLASS = "glass_background"

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

    /** 小工具背景：true 为磨砂玻璃（默认），false 为实色。 */
    fun glassBackground(context: Context): Boolean = prefs(context).getBoolean(KEY_GLASS, true)

    fun setGlassBackground(context: Context, glass: Boolean) {
        prefs(context).edit().putBoolean(KEY_GLASS, glass).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
