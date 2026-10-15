package com.storytellerf.summer.data

import java.util.Currency
import java.util.Locale

fun currencyCode(value: String): String? = runCatching {
    val code = value.trim().uppercase(Locale.ROOT)
    Currency.getInstance(code).takeIf { it.defaultFractionDigits >= 0 }?.currencyCode
}.getOrNull()
