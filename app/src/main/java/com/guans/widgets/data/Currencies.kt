package com.guans.widgets.data

import java.util.Currency
import java.util.Locale

/** 可选货币。名称由系统按当前语言给出。 */
object Currencies {
    val COMMON = listOf("USD", "CNY", "EUR", "HKD", "JPY", "GBP", "KRW")
    val OTHERS = listOf(
        "AUD", "CAD", "CHF", "SGD", "TWD", "MOP", "NZD", "THB", "MYR", "PHP", "IDR", "VND",
        "INR", "AED", "SAR", "RUB", "TRY", "BRL", "MXN", "ZAR", "SEK", "NOK", "DKK", "PLN",
    )

    fun name(code: String, locale: Locale): String =
        runCatching { Currency.getInstance(code).getDisplayName(locale) }.getOrDefault(code)
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
