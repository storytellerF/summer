package com.storytellerf.summer.ui.components

import java.util.Currency
import java.util.Locale
import kotlin.math.abs

internal fun formatMoney(value: Double, currency: String = "CNY"): String {
    val digits = runCatching { Currency.getInstance(currency).defaultFractionDigits }.getOrDefault(2).coerceAtLeast(0)
    val prefix = if (currency == "CNY") "¥" else "$currency "
    return "${if (value < 0) "−" else ""}$prefix${("%,.${digits}f").format(Locale.US, abs(value))}"
}
internal fun formatSignedMoney(value: Double, currency: String = "CNY"): String =
    "${if (value > 0) "+" else ""}${formatMoney(value, currency)}"
