package com.storytellerf.summer.data.llmd

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LlmdResponseFormatTest {
    @Test fun multiBalanceRequestUsesSelectedAccountsAndVariantImageUri() {
        val uri = "content://com.storytellerf.summer.debug.llmd-images/image.jpg"
        val request = JSONObject(buildBalancesExtractionRequest(uri, listOf(
            com.storytellerf.summer.data.recognition.BalanceReadTarget(7, "Wallet", "Available balance", "USD")), "selected-model"))
        val content = request.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals(uri, content.getJSONObject(1).getJSONObject("image_url").getString("url"))
        assertTrue(content.getJSONObject(0).getString("text").contains("Available balance"))
        assertTrue(content.getJSONObject(0).getString("text").contains("USD"))
        assertEquals("selected-model", request.getString("model"))
        assertEquals("text", request.getJSONObject("response_format").getString("type"))
        assertTrue(content.getJSONObject(0).getString("text").contains("fundSourceId"))
    }

    @Test fun serviceErrorsRemainActionable_andAuthorizationStillRequestsConsent() {
        val error = runCatching { extractResponseContent(
            """{"error":{"type":"llmd_error","message":"LiteRT-LM engine is not ready"}}""") }.exceptionOrNull()
        assertTrue(error is LlmdRecognitionException)
        assertTrue(error!!.message!!.contains("engine is unavailable"))
        assertTrue(runCatching { extractResponseContent(
            """{"error":{"type":"authorization_required"}}""") }.exceptionOrNull() is LlmdAuthorizationException)
    }

    @Test
    fun transactionRequestRequestsOriginalTransactionId_withoutGuidedGeneration() {
        val request = JSONObject(buildTransactionExtractionRequest("content://test/image.jpg", "selected-model", "EUR"))
        assertTrue(request.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
            .getJSONObject(0).getString("text").contains("selected account currency is EUR"))
        assertEquals("selected-model", request.getString("model"))
        assertEquals("text", request.getJSONObject("response_format").getString("type"))
        assertTrue(request.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
            .getJSONObject(0).getString("text").contains("transactionId"))
    }

}
