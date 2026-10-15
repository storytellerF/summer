package com.storytellerf.summer.data.recognition

import java.security.MessageDigest
import java.util.TimeZone
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

data class RecognizedTransaction(val timestamp: Long?, val amount: Double, val note: String?, val transactionId: String? = null, val currency: String? = null)
data class RecognizedTransactions(val imageHash: String, val records: List<RecognizedTransaction>, val imagePath: String? = null)

@Serializable
private data class TransactionPayload(val transactions: List<TransactionValue>)
@Serializable
private data class TransactionValue(val timestamp: String?, val amount: Double, val note: String?, val transactionId: String?, val currency: String?, val dateText: String? = null)

internal fun parseTransactions(content: String, timeZone: TimeZone = TimeZone.getDefault(), currency: String? = null): List<RecognizedTransaction> {
    return try {
        val trimmed = content.trim()
        val json = Regex("\\A```(?:json)?\\s*\\n([\\s\\S]*?)\\n```\\z", RegexOption.IGNORE_CASE)
            .matchEntire(trimmed)?.groupValues?.get(1) ?: trimmed
        val payload = Json.parseToJsonElement(json)
        payload.jsonObject.getValue("transactions").jsonArray.forEach { item ->
            require(!item.jsonObject.getValue("amount").jsonPrimitive.isString) { "amount" }
        }
        val values = Json.decodeFromJsonElement<TransactionPayload>(payload).transactions
        require(values.size in 1..100) { "row_count" }
        values.mapIndexedNotNull { index, value ->
            val recognizedCurrency = value.currency?.uppercase(java.util.Locale.ROOT)
            if (currency != null && recognizedCurrency != null && recognizedCurrency != currency) {
                return@mapIndexedNotNull null
            }
            // New responses copy date evidence; never trust a generated year for a relative label.
            val timestamp = if (payload.jsonObject.getValue("transactions").jsonArray[index].jsonObject.containsKey("dateText")) {
                value.dateText?.let { parseScreenshotDate(it, timeZone) }
            } else value.timestamp?.let { requireNotNull(parseLocalDateTime(it, timeZone)) { "timestamp" } }
            require(value.amount.isFinite()) { "amount" }
            RecognizedTransaction(timestamp, value.amount, value.note?.trim()?.takeIf(String::isNotEmpty), value.transactionId?.trim()?.takeIf(String::isNotEmpty), recognizedCurrency)
        }.also { require(it.isNotEmpty()) { "currency" } }
    } catch (error: Exception) {
        throw InvalidTransactionResponseException(error.message?.takeIf { it in setOf("row_count", "timestamp", "amount", "currency") }
            ?: error.javaClass.simpleName)
    }
}

internal fun imageHash(jpeg: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(jpeg).joinToString("") { "%02x".format(it.toInt() and 0xff) }

class InvalidTransactionResponseException(val failureType: String? = null) : Exception(
    "No valid transactions were recognized. Use a screenshot with readable amounts and income/expense direction."
)

internal const val TRANSACTION_EXTRACTION_PROMPT = """
Read completed transactions from the screenshot, not balances, credit limits or summary totals.
Scan from top to bottom and include every readable transaction row; do not stop after the first or merge rows.
Return only {"transactions":[{"timestamp":null,"dateText":null,"amount":-12.50,"note":"merchant","transactionId":null,"currency":null}]}.
amount: signed numeric value; expenses/payments/支出/付款 are negative, income/refunds/收入/退款
are positive. Use visible signs or direction labels; do not guess amounts or directions.
currency: visible ISO currency code or null when undetermined. A missing currency symbol is
allowed. Symbols such as $ and ¥ are ambiguous; never guess their ISO currency or convert amounts.
For $ or ¥ alone, explicitly set currency:null and keep the readable row for review.
The unambiguous euro symbol € identifies EUR. Do not invent a code from the app or language.
dateText: copy the visible transaction Date/交易时间/支付时间 character for character, without its label.
Copy 今天/昨天/TODAY/YESTERDAY and clock-only or month/day text literally; never expand them into a date.
Do not format, calculate, complete or add date digits. Missing/unreadable date text is JSON null.
timestamp: ALWAYS JSON null. The app formats dateText and requires manual correction for incomplete dates.
note: visible merchant/description, otherwise null.
transactionId: inspect the WHOLE order/transaction/reference ID field for masking BEFORE copying digits.
A blur, opaque patch, mask or cropped edge anywhere in that field means transactionId:null;
do not copy either visible end, because the gap contains unknown characters.
Only a completely clear, uninterrupted ID may produce a string. Never use a balance/date as an ID.
Example: ABC12 [blurred area] 89 -> transactionId:null, never "ABC1289".
Each row includes all six fields. Preserve visible order. Skip explicitly pending/failed rows
and totals. Return {"transactions":[]} only when no readable transactions qualify.
No markdown or explanations.
"""

internal const val TRANSACTION_RESPONSE_SCHEMA = """
{"type":"object","properties":{"transactions":{"type":"array","items":{
"type":"object","properties":{"timestamp":{"type":["string","null"]},"dateText":{"type":["string","null"]},"amount":{"type":"number"},"note":{"type":["string","null"]},"transactionId":{"type":["string","null"]},"currency":{"type":["string","null"]}},
"required":["timestamp","dateText","amount","note","transactionId","currency"],"additionalProperties":false}}},"required":["transactions"],"additionalProperties":false}
"""

internal fun transactionsPrompt(currency: String?): String = TRANSACTION_EXTRACTION_PROMPT +
    (currency?.let { "\nThe selected account currency is $it. Read this currency only; skip explicitly different currencies. Keep rows with undetermined currency for review." } ?: "")
