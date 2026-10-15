package com.storytellerf.summer.data.recognition

import org.junit.Assert.*
import org.junit.Test

class BalanceRecognitionTest {
    @Test fun generatedJsonStillRequiresAllFieldsAndRejectsExtraFieldsAndWrongTypes() {
        val targets = listOf(BalanceReadTarget(1, "Wallet", "Account balance"))
        for (content in listOf(
            """{"balances":[{"balance":10}]}""",
            """{"balances":[{"fundSourceId":null,"balance":10,"label":null,"currency":null,"date":"2026-01-01"}]}""",
            """{"balances":[{"fundSourceId":null,"balance":"ten","label":null,"currency":null}]}""",
            """{"balances":[{"fundSourceId":null,"balance":"10","label":null,"currency":null}]}""",
        )) assertThrows(InvalidBalancesResponseException::class.java) { parseBalances(content, targets) }
    }
    @Test fun selectedCurrenciesArePreserved_andExplicitMismatchCannotBeAssigned() {
        val targets = listOf(BalanceReadTarget(1, "Dollar account", "Account balance", "USD"),
            BalanceReadTarget(2, "Euro account", "Account balance", "EUR"))
        val rows = parseBalances("""{"balances":[{"fundSourceId":1,"balance":10,"label":null,"currency":"USD"},{"fundSourceId":null,"balance":20,"label":null,"currency":"EUR"}]}""", targets)
        assertEquals(listOf("USD", "EUR"), rows.map { it.currency })
        assertThrows(InvalidBalancesResponseException::class.java) {
            parseBalances("""{"balances":[{"fundSourceId":1,"balance":20,"label":null,"currency":"EUR"}]}""", targets)
        }
    }

    private val targets = listOf(BalanceReadTarget(1, "Wallet", "Available balance"), BalanceReadTarget(2, "Bank", "Account balance"))
    @Test fun selectedBalancesAndUnassignedReadableValuesArePreserved_includingZeroAndNegative() {
        val records = parseBalances("""{"balances":[{"fundSourceId":1,"balance":0.0,"label":"Available balance","currency":null},{"fundSourceId":2,"balance":-50.5,"label":null,"currency":null},{"fundSourceId":null,"balance":123.0,"label":"Unreadable account","currency":null}]}""", targets)
        assertEquals(listOf(0.0, -50.5, 123.0), records.map { it.balance })
        assertNull(records.last().fundSourceId)
        assertTrue(balancesPrompt(targets).contains("Available balance"))
        assertTrue(balancesPrompt(targets).contains("fundSourceId"))
    }
    @Test fun emptyUnknownIdsAndProseAreRejected_withoutGuessingAnAccount() {
        for (content in listOf("""{"balances":[]}""", """{"balances":[{"fundSourceId":99,"balance":10,"label":null,"currency":null}]}""", "Balance is 10")) {
            assertThrows(InvalidBalancesResponseException::class.java) { parseBalances(content, targets) }
        }
    }
}
