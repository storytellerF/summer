package com.storytellerf.summer.data.recognition

import java.security.MessageDigest
import java.util.TimeZone
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class RecognizedTransaction(val timestamp: Long?, val amount: Double, val note: String?, val transactionId: String? = null)
data class RecognizedTransactions(val imageHash: String, val records: List<RecognizedTransaction>, val imagePath: String? = null)

@Serializable
private data class TransactionPayload(val transactions: List<TransactionValue>)
@Serializable
private data class TransactionValue(val timestamp: String?, val amount: Double, val note: String?, val transactionId: String?)

internal fun parseTransactions(content: String, timeZone: TimeZone = TimeZone.getDefault()): List<RecognizedTransaction> {
    return try {
        val trimmed = content.trim()
        val json = Regex("\\A```(?:json)?\\s*\\n([\\s\\S]*?)\\n```\\z", RegexOption.IGNORE_CASE)
            .matchEntire(trimmed)?.groupValues?.get(1) ?: trimmed
        val values = Json.decodeFromString<TransactionPayload>(json).transactions
        require(values.size in 1..100)
        values.map { value ->
            val timestamp = value.timestamp?.let { requireNotNull(parseLocalDateTime(it, timeZone)) }
            require(value.amount.isFinite())
            RecognizedTransaction(timestamp, value.amount, value.note?.trim()?.takeIf(String::isNotEmpty), value.transactionId?.trim()?.takeIf(String::isNotEmpty))
        }
    } catch (error: Exception) {
        throw InvalidTransactionResponseException()
    }
}

internal fun imageHash(jpeg: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(jpeg).joinToString("") { "%02x".format(it.toInt() and 0xff) }

class InvalidTransactionResponseException : Exception(
    "No valid transactions were recognized. Use a screenshot with full dates, amounts and income/expense direction."
)

internal const val TRANSACTION_EXTRACTION_PROMPT = """
Extract completed CNY transactions from the screenshot, not balances or totals.
Check currency first: a visible EUR/euro/€ or any other foreign currency disqualifies that row.
Never copy foreign-currency amounts into CNY and never convert currencies.
Return only a JSON object with a transactions array. Each transaction has:
 timestamp: a full local date and time in yyyy-MM-ddTHH:mm:ss format, or null when incomplete/relative/unclear;
 amount: signed numeric CNY value, expense negative and income positive;
 note: the merchant or description, or null;
 transactionId: the original transaction/order/reference ID visible for this row, or null.
Never invent or calculate a transactionId, and never use a balance or date as an ID.
If an ID is masked, blurred, truncated or incomplete, return null, never a partial ID.
Use dates and direction visible in the screenshot. For a full date without a time use 00:00:00.
Do not guess missing years or dates: keep the transaction with timestamp null for manual review.
TODAY, YESTERDAY, 今天, 昨天, a clock time, or a day/month without a year do not establish
a full date. Never use the current date, a product name's year, or an assumed reference date.
For example, a completed Alipay expense of -12.50 labelled 今天 10:59 with no full date
becomes {"timestamp":null,"amount":-12.50,"note":null,"transactionId":null}.
Do not guess amounts, currency, or direction. Skip ambiguous amounts/directions,
pending/failed transactions, foreign currencies and summary rows. Preserve visible row order.
If nothing qualifies return {"transactions":[]}. No markdown or explanations.
"""

internal const val TRANSACTION_RESPONSE_SCHEMA = """
{"type":"object","properties":{"transactions":{"type":"array","maxItems":100,"items":{
"type":"object","properties":{"timestamp":{"type":["string","null"]},"amount":{"type":"number"},"note":{"type":["string","null"]},"transactionId":{"type":["string","null"]}},
"required":["timestamp","amount","note","transactionId"],"additionalProperties":false}}},"required":["transactions"],"additionalProperties":false}
"""
