package com.storytellerf.summer.data.recognition

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class KoogImageRecognizerTest {
    private val targets = listOf(BalanceReadTarget(1, "Wallet", "Account balance", "EUR"))

    @Test fun bothRecognitionKindsSendNativeStrictSchemaThroughEveryProvider_andKeepInlineImages() = runTest {
        for ((backend, responses) in listOf(
            RecognitionBackend.OpenAI to false,
            RecognitionBackend.OpenAI to true,
            RecognitionBackend.OpenAICompatible to false,
            RecognitionBackend.OpenRouter to false,
            RecognitionBackend.Anthropic to false,
        )) {
            for (balances in listOf(true, false)) {
                var requests = 0
                val recognizer = KoogImageRecognizer {
                    HttpClient(MockEngine { request ->
                        requests++
                        val path = when {
                            backend == RecognitionBackend.Anthropic -> "/custom/v1/messages"
                            responses -> "/custom/v1/responses"
                            else -> "/custom/v1/chat/completions"
                        }
                        assertEquals(path, request.url.encodedPath)
                        assertEquals("api.example.com", request.url.host)
                        assertEquals(if (backend == RecognitionBackend.Anthropic) "test-key" else "Bearer test-key",
                            request.headers[if (backend == RecognitionBackend.Anthropic) "x-api-key" else "Authorization"])
                        val text = request.body.toByteArray().decodeToString()
                        val body = Json.parseToJsonElement(text).jsonObject
                        assertEquals("test-vision", body.getValue("model").jsonPrimitive.content)
                        assertTrue(text.contains("image"))
                        assertTrue(text.contains("AQID"))
                        assertTrue(text.contains("EUR"))
                        val format = when {
                            backend == RecognitionBackend.Anthropic -> body.getValue("output_config").jsonObject.getValue("format").jsonObject
                            responses -> body.getValue("text").jsonObject.getValue("format").jsonObject
                            else -> body.getValue("response_format").jsonObject
                        }
                        assertEquals("json_schema", format.getValue("type").jsonPrimitive.content)
                        val wrapper = if (!responses && backend != RecognitionBackend.Anthropic) format.getValue("json_schema").jsonObject else format
                        if (backend != RecognitionBackend.Anthropic) assertTrue(wrapper.getValue("strict").jsonPrimitive.boolean)
                        assertEquals(Json.parseToJsonElement(if (balances) BALANCES_RESPONSE_SCHEMA else TRANSACTION_RESPONSE_SCHEMA), wrapper.getValue("schema"))
                        if (backend == RecognitionBackend.OpenRouter) assertTrue(body.getValue("provider").jsonObject.getValue("require_parameters").jsonPrimitive.boolean)
                        val content = if (balances) """{"balances":[{"fundSourceId":1,"balance":-245.70,"label":null,"currency":"EUR"}]}"""
                            else """{"transactions":[{"timestamp":null,"amount":-12.5,"note":"Shop","transactionId":"TX-001","currency":"EUR"}]}"""
                        respond(modelResponse(backend, responses, content), headers = headersOf("Content-Type", "application/json"))
                    })
                }
                val connection = KoogConnection("https://api.example.com/custom/v1/", "test-vision", "test-key", responses)
                if (balances) assertEquals(-245.70, recognizer.recognizeBalances(backend, connection, byteArrayOf(1, 2, 3), targets).single().balance, 0.0)
                else {
                    val row = recognizer.recognizeTransactions(backend, connection, byteArrayOf(1, 2, 3), "EUR").single()
                    assertEquals(-12.5, row.amount, 0.0)
                    assertEquals("TX-001", row.transactionId)
                    assertNull(row.timestamp)
                }
                assertEquals(1, requests)
            }
        }
    }

    @Test fun unsupportedSchemaFailsWithoutRetryingAsPlainText_orLeakingProviderResponse() = runTest {
        var requests = 0
        val recognizer = KoogImageRecognizer { HttpClient(MockEngine {
            requests++
            respond("""{"error":{"message":"response_format JSON Schema unsupported; private response"}}""", HttpStatusCode.BadRequest)
        }) }
        try {
            recognizer.recognizeBalances(RecognitionBackend.OpenRouter, KoogConnection("https://api.example.com/v1", "test-vision", "test-key"), byteArrayOf(1), targets)
            fail("Expected schema rejection")
        } catch (error: RecognitionConnectionException) {
            assertEquals("schema_request", error.category)
            assertTrue(error.message!!.contains("JSON Schema"))
            assertFalse(error.message!!.contains("private response"))
            assertNull(error.cause)
        }
        assertEquals(1, requests)
    }

    @Test fun authorizationErrorIsSanitizedAndClassified() = runTest {
        val recognizer = KoogImageRecognizer { HttpClient(MockEngine {
            respond("""{"error":"secret-provider-response"}""", HttpStatusCode.Unauthorized)
        }) }
        try {
            recognizer.recognizeBalances(RecognitionBackend.OpenRouter, KoogConnection("https://api.example.com/v1", "test-vision", "test-key"), byteArrayOf(1), targets)
            fail("Expected authorization error")
        } catch (error: RecognitionConnectionException) {
            assertEquals(401, error.statusCode)
            assertFalse(error.message!!.contains("secret-provider-response"))
            assertNull(error.cause)
        }
    }

    @Test fun scalarAndUnstructuredOutputsCannotBecomeFabricatedZeroBalances() {
        for (value in listOf("0", "UNKNOWN", "", "NaN", "Infinity", "Balance: 123", "1,234.56")) {
            assertThrows(InvalidBalancesResponseException::class.java) { parseBalances(value, targets) }
        }
        assertEquals(0.0, parseBalances("""{"balances":[{"fundSourceId":1,"balance":0,"label":null,"currency":"EUR"}]}""", targets).single().balance, 0.0)
    }

    @Test fun openRouterErrorInsideSuccessfulHttpResponseIsSanitized() = runTest {
        val recognizer = KoogImageRecognizer { HttpClient(MockEngine {
            respond("""{"id":"test","model":"test-vision","choices":[],"error":{"message":"Provider returned error; private response","code":429}}""",
                headers = headersOf("Content-Type", "application/json"))
        }) }
        try {
            recognizer.recognizeBalances(RecognitionBackend.OpenRouter,
                KoogConnection("https://api.example.com/v1", "test-vision", "test-key"), byteArrayOf(1), targets)
            fail("Expected provider rate limit")
        } catch (error: RecognitionConnectionException) {
            assertEquals(429, error.statusCode)
            assertEquals("upstream_provider", error.category)
            assertFalse(error.message!!.contains("private response"))
            assertNull(error.cause)
        }
    }

    private fun modelResponse(backend: RecognitionBackend, responses: Boolean, content: String): String = buildJsonObject {
        put("id", "test"); put("model", "test-vision")
        when {
            backend == RecognitionBackend.Anthropic -> {
                put("type", "message"); put("role", "assistant")
                put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", content) }) })
                put("stop_reason", "end_turn")
            }
            responses -> {
                put("object", "response"); put("created_at", 1); put("status", "completed"); put("parallel_tool_calls", false)
                put("text", buildJsonObject { put("format", buildJsonObject { put("type", "text") }) })
                put("output", buildJsonArray { add(buildJsonObject {
                    put("type", "message"); put("id", "msg_test"); put("role", "assistant"); put("status", "completed")
                    put("content", buildJsonArray { add(buildJsonObject { put("type", "output_text"); put("text", content); put("annotations", buildJsonArray {}) }) })
                }) })
            }
            else -> {
                put("object", "chat.completion"); put("created", 1)
                put("choices", buildJsonArray { add(buildJsonObject {
                    put("index", 0); put("finish_reason", "stop")
                    put("message", buildJsonObject { put("role", "assistant"); put("content", content) })
                }) })
            }
        }
    }.toString()
}
