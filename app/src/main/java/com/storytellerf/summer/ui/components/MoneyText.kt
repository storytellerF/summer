package com.storytellerf.summer.ui.components

import java.util.Locale
import kotlin.math.abs

internal fun formatMoney(value: Double): String = "${if (value < 0) "−" else ""}¥${"%,.2f".format(Locale.US, abs(value))}"
internal fun formatSignedMoney(value: Double): String = "${if (value > 0) "+" else ""}${formatMoney(value)}"
