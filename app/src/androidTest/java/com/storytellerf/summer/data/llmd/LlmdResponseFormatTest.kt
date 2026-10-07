package com.storytellerf.summer.data.llmd

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LlmdResponseFormatTest {
    @Test
    fun transactionRequestRequiresOriginalTransactionId_andStrictSchema() {
        val request = JSONObject(buildTransactionExtractionRequest("content://test/image.jpg"))
        val schema = request.getJSONObject("response_format").getJSONObject("json_schema")
        assertTrue(schema.getBoolean("strict"))
        val row = schema.getJSONObject("schema").getJSONObject("properties")
            .getJSONObject("transactions").getJSONObject("items")
        assertTrue(row.getJSONArray("required").toString().contains("transactionId"))
        assertTrue(row.getJSONObject("properties").has("transactionId"))
    }

    @Test
    fun requestUsesStrictBalanceJsonSchema() {
        val request = JSONObject(
            buildBalanceExtractionRequest("content://com.storytellerf.summer.llmd-images/test.jpg"),
        )
        val responseFormat = request.getJSONObject("response_format")
        val jsonSchema = responseFormat.getJSONObject("json_schema")
        val schema = jsonSchema.getJSONObject("schema")

        assertEquals("json_schema", responseFormat.getString("type"))
        assertEquals("balance_extraction", jsonSchema.getString("name"))
        assertTrue(jsonSchema.getBoolean("strict"))
        assertEquals("number", schema.getString("type"))
        assertEquals(
            "content://com.storytellerf.summer.llmd-images/test.jpg",
            request.getJSONArray("messages")
                .getJSONObject(0)
                .getJSONArray("content")
                .getJSONObject(1)
                .getJSONObject("image_url")
                .getString("url"),
        )
    }

    @Test
    fun responseReadsStructuredNegativeBalance() {
        val result = parseBalanceFromResponse(
            """{"choices":[{"message":{"content":"-1234.56"}}]}""",
        )

        assertEquals(-1234.56, result.getOrThrow(), 0.0)
    }

    @Test
    fun responseRejectsUnstructuredContent() {
        val result = parseBalanceFromResponse(
            """{"choices":[{"message":{"content":"balance is 1234.56"}}]}""",
        )

        assertTrue(result.isFailure)
    }
}
