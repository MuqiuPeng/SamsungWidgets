package com.guans.widgets.data

import java.util.Currency
import java.util.Locale

/** 可选货币。名称由系统按当前语言给出，旗帜由 ISO 4217 代码前两位（即国家/地区代码）生成。 */
object Currencies {
    val COMMON = listOf("USD", "CNY", "EUR", "HKD", "JPY", "GBP", "KRW")
    val OTHERS = listOf(
        "AUD", "CAD", "CHF", "SGD", "TWD", "MOP", "NZD", "THB", "MYR", "PHP", "IDR", "VND",
        "INR", "AED", "SAR", "RUB", "TRY", "BRL", "MXN", "ZAR", "SEK", "NOK", "DKK", "PLN",
    )

    fun name(code: String, locale: Locale): String =
        runCatching { Currency.getInstance(code).getDisplayName(locale) }.getOrDefault(code)

    fun flag(code: String): String {
        if (code.length < 2 || code[0] !in 'A'..'Z' || code[1] !in 'A'..'Z') return ""
        return regional(code[0]) + regional(code[1])
    }

    private fun regional(c: Char) = String(Character.toChars(0x1F1E6 + (c - 'A')))
}

/** 大数少保留小数，小数（如 JPY/CNY）多保留几位。 */
fun formatRate(rate: Double): String {
    val pattern = when {
        rate >= 100 -> "%.2f"
        rate >= 1 -> "%.4f"
        else -> "%.6f"
    }
    return String.format(Locale.US, pattern, rate)
}
