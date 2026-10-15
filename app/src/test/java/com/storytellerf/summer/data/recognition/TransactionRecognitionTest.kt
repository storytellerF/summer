package com.storytellerf.summer.data.recognition

import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class TransactionRecognitionTest {
    @Test fun copiedDateEvidenceOverridesGeneratedDates_andNeverCompletesRelativeDates() {
        val zone = TimeZone.getTimeZone("UTC")
        for (date in listOf("今天 10:59", "昨天", "TODAY", "10:59", "11-05", null)) {
            val evidence = date?.let { "\"$it\"" } ?: "null"
            val rows = parseTransactions("""{"transactions":[{"timestamp":"2023-10-27T10:59:00","dateText":$evidence,"amount":-20.7,"note":null,"transactionId":null,"currency":null}]}""", zone)
            assertNull(rows.single().timestamp)
        }
        for (date in listOf("2018-11-05 10:59", "2018年11月05日 10:59:00", "2018/11/05T10:59:00", "Nov 5, 2018 10:59")) {
            assertEquals(parseLocalDateTime("2018-11-05T10:59:00", zone), parseScreenshotDate(date, zone))
        }
        val copied = parseTransactions("""{"transactions":[{"timestamp":"2023-10-27T10:59:00","dateText":"2018-11-05 10:59","amount":-20.7,"note":null,"transactionId":null,"currency":null}]}""", zone)
        assertEquals(parseLocalDateTime("2018-11-05T10:59:00", zone), copied.single().timestamp)
        assertEquals(parseLocalDateTime("2018-11-05T00:00:00", zone), parseScreenshotDate("2018-11-05", zone))
        assertNull(parseScreenshotDate("2018-02-30 10:59", zone))
    }
    @Test fun generatedJsonStillRequiresAllFieldsAndRejectsExtraFieldsAndWrongTypes() {
        for (content in listOf(
            """{"transactions":[{"amount":10}]}""",
            """{"transactions":[{"timestamp":null,"amount":10,"note":null,"transactionId":null,"currency":null,"confidence":1}]}""",
            """{"transactions":[{"timestamp":null,"amount":"ten","note":null,"transactionId":null,"currency":null}]}""",
            """{"transactions":[{"timestamp":null,"amount":"10","note":null,"transactionId":null,"currency":null}]}""",
        )) assertThrows(InvalidTransactionResponseException::class.java) { parseTransactions(content) }
    }
    @Test fun mixedCurrenciesKeepSelectedAndUndeterminedRows_inVisibleOrder() {
        val content = """{"transactions":[
            {"timestamp":null,"amount":-10,"note":"Selected","transactionId":"USD-1","currency":"usd"},
            {"timestamp":null,"amount":-20,"note":"Other currency","transactionId":"EUR-1","currency":"EUR"},
            {"timestamp":null,"amount":-30,"note":"Undetermined","transactionId":null,"currency":null}
        ]}"""
        val rows = parseTransactions(content, currency = "USD")
        assertEquals(listOf(-10.0, -30.0), rows.map { it.amount })
        assertEquals(listOf("USD", null), rows.map { it.currency })
        assertEquals(listOf("USD-1", null), rows.map { it.transactionId })
        assertEquals(3, parseTransactions(content).size)
    }

    @Test fun accountCurrencyAcceptsEuroAndUnknownCurrency_butRejectsAnExplicitMismatch() {
        val content = """{"transactions":[{"timestamp":null,"amount":-12.5,"note":null,"transactionId":null,"currency":"EUR"}]}"""
        assertEquals("EUR", parseTransactions(content, currency = "EUR").single().currency)
        assertThrows(InvalidTransactionResponseException::class.java) { parseTransactions(content, currency = "USD") }
        assertNull(parseTransactions(content.replace("\"EUR\"", "null"), currency = "USD").single().currency)
    }

    @Test fun modelJsonFence_isAccepted_butSurroundingProseIsRejected() {
        val json = """{"transactions":[{"timestamp":"2018-11-05T10:59:00","amount":-20.70,"note":"饿了么","transactionId":null,"currency":null}]}"""
        for (tag in listOf("json", "JSON", "")) {
            assertEquals(parseTransactions(json), parseTransactions("  ```$tag\n$json\n```  "))
        }
        for (content in listOf("Here is the result:\n```json\n$json\n```", "```json\n$json\n```\nExplanation")) {
            assertThrows(InvalidTransactionResponseException::class.java) { parseTransactions(content) }
        }
    }

    @Test fun signedTransactions_preserveOriginalIds_andUseLocalTime() {
        val values = parseTransactions("""{"transactions":[
            {"timestamp":"2026-10-06T12:30:00","amount":-12.50,"note":"Shop","transactionId":" TX-001 ","currency":null},
            {"timestamp":"2026-10-06T00:00:00","amount":200.0,"note":null,"transactionId":null,"currency":null}]}""", TimeZone.getTimeZone("Asia/Shanghai"))
        assertEquals(-12.50, values[0].amount, 0.0)
        assertEquals("TX-001", values[0].transactionId)
        assertEquals("2026-10-06T04:30:00", formatLocalDateTime(requireNotNull(values[0].timestamp), TimeZone.getTimeZone("UTC")))
        assertNull(values[1].transactionId)
    }

    @Test fun unknownDatesRemainNull_forManualReviewInsteadOfBeingGuessed() {
        val records = parseTransactions("""{"transactions":[{"timestamp":null,"amount":-9.5,"note":"Purchase","transactionId":null,"currency":null}]}""")
        assertNull(records.single().timestamp)
        assertEquals(-9.5, records.single().amount, 0.0)
    }

    @Test fun completeDatesWithoutLeadingZeroesKeepAllComponents_andInvalidDatesStillFail() {
        val zone = TimeZone.getTimeZone("UTC")
        assertEquals(parseLocalDateTime("2018-11-05T10:59:00", zone), parseLocalDateTime("2018-11-5T10:59:0", zone))
        assertEquals(parseLocalDateTime("2026-01-02T03:04:05", zone), parseLocalDateTime("2026-1-2T3:4:5", zone))
        for (date in listOf("2026-2-30T3:4:5", "2026-1-2T25:4:5", "2026-1-2T3:4", "1-2T3:4:5", "today")) {
            assertNull(parseLocalDateTime(date, zone))
        }
    }

    @Test fun missingDatesAndMalformedResponses_doNotCreateTransactions() {
        for (content in listOf("{\"transactions\":[]}", "```json {} ```",
            """{"transactions":[{"timestamp":"2026-02-30T12:00:00","amount":5,"note":null,"transactionId":null,"currency":null}]}""",
            """{"transactions":[{"timestamp":"10-06","amount":5,"note":null,"transactionId":null,"currency":null}]}""")) {
            assertThrows(InvalidTransactionResponseException::class.java) { parseTransactions(content) }
        }
        assertNull(parseLocalDateTime("2026-10-06T25:00:00"))
        assertNull(parseLocalDateTime("2026-10-06T12:00:00junk"))
        assertEquals(imageHash(byteArrayOf(1, 2)), imageHash(byteArrayOf(1, 2)))
        assertNotEquals(imageHash(byteArrayOf(1, 2)), imageHash(byteArrayOf(1, 3)))
    }
}
