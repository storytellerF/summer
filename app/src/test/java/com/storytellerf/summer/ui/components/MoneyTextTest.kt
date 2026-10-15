package com.storytellerf.summer.ui.components

import com.storytellerf.summer.data.currencyCode
import org.junit.Assert.*
import org.junit.Test

class MoneyTextTest {
    @Test fun currencyCodesAreValidated_andFormattingUsesTheirFractionDigits() {
        assertEquals("USD", currencyCode(" usd "))
        assertNull(currencyCode("not-a-currency"))
        assertNull(currencyCode("XXX"))
        assertEquals("USD 12.50", formatMoney(12.5, "USD"))
        assertEquals("JPY 1,234", formatMoney(1234.0, "JPY"))
        assertEquals("−KWD 1.234", formatMoney(-1.234, "KWD"))
    }
}
