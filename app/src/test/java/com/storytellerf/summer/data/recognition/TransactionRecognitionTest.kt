package com.storytellerf.summer.data.recognition

import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class TransactionRecognitionTest {
    @Test fun modelJsonFence_isAccepted_butSurroundingProseIsRejected() {
        val json = """{"transactions":[{"timestamp":"2018-11-05T10:59:00","amount":-20.70,"note":"饿了么","transactionId":null}]}"""
        for (tag in listOf("json", "JSON", "")) {
            assertEquals(parseTransactions(json), parseTransactions("  ```$tag\n$json\n```  "))
        }
        for (content in listOf("Here is the result:\n```json\n$json\n```", "```json\n$json\n```\nExplanation")) {
            assertThrows(InvalidTransactionResponseException::class.java) { parseTransactions(content) }
        }
    }

    @Test fun signedTransactions_preserveOriginalIds_andUseLocalTime() {
        val values = parseTransactions("""{"transactions":[
            {"timestamp":"2026-10-06T12:30:00","amount":-12.50,"note":"Shop","transactionId":" TX-001 "},
            {"timestamp":"2026-10-06T00:00:00","amount":200.0,"note":null,"transactionId":null}]}""", TimeZone.getTimeZone("Asia/Shanghai"))
        assertEquals(-12.50, values[0].amount, 0.0)
        assertEquals("TX-001", values[0].transactionId)
        assertEquals("2026-10-06T04:30:00", formatLocalDateTime(requireNotNull(values[0].timestamp), TimeZone.getTimeZone("UTC")))
        assertNull(values[1].transactionId)
    }

    @Test fun unknownDatesRemainNull_forManualReviewInsteadOfBeingGuessed() {
        val records = parseTransactions("""{"transactions":[{"timestamp":null,"amount":-9.5,"note":"Purchase","transactionId":null}]}""")
        assertNull(records.single().timestamp)
        assertEquals(-9.5, records.single().amount, 0.0)
    }

    @Test fun missingDatesAndMalformedResponses_doNotCreateTransactions() {
        for (content in listOf("{\"transactions\":[]}", "```json {} ```",
            """{"transactions":[{"timestamp":"2026-02-30T12:00:00","amount":5,"note":null,"transactionId":null}]}""",
            """{"transactions":[{"timestamp":"10-06","amount":5,"note":null,"transactionId":null}]}""")) {
            assertThrows(InvalidTransactionResponseException::class.java) { parseTransactions(content) }
        }
        assertNull(parseLocalDateTime("2026-10-06T25:00:00"))
        assertNull(parseLocalDateTime("2026-10-06T12:00:00junk"))
        assertEquals(imageHash(byteArrayOf(1, 2)), imageHash(byteArrayOf(1, 2)))
        assertNotEquals(imageHash(byteArrayOf(1, 2)), imageHash(byteArrayOf(1, 3)))
    }
}
