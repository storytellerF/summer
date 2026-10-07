package com.storytellerf.summer.data.recognition

import org.junit.Assert.*
import org.junit.Test

class BalanceRecognitionTest {
    private val targets = listOf(BalanceReadTarget(1, "Wallet", "Available balance"), BalanceReadTarget(2, "Bank", "Account balance"))
    @Test fun selectedBalancesAndUnassignedReadableValuesArePreserved_includingZeroAndNegative() {
        val records = parseBalances("""{"balances":[{"fundSourceId":1,"balance":0.0,"label":"Available balance"},{"fundSourceId":2,"balance":-50.5,"label":null},{"fundSourceId":null,"balance":123.0,"label":"Unreadable account"}]}""", targets)
        assertEquals(listOf(0.0, -50.5, 123.0), records.map { it.balance })
        assertNull(records.last().fundSourceId)
        assertTrue(balancesPrompt(targets).contains("Available balance"))
        assertTrue(balancesPrompt(targets).contains("fundSourceId"))
    }
    @Test fun emptyUnknownIdsAndProseAreRejected_withoutGuessingAnAccount() {
        for (content in listOf("""{"balances":[]}""", """{"balances":[{"fundSourceId":99,"balance":10,"label":null}]}""", "Balance is 10")) {
            assertThrows(InvalidBalancesResponseException::class.java) { parseBalances(content, targets) }
        }
    }
}
