package com.storytellerf.summer.data.recognition

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class KoogImageRecognizerTest {
    @Test fun koogClients_sendInlineImageAndUseConfiguredApiPaths() = runTest {
        for ((backend, responses) in listOf(
            RecognitionBackend.OpenAI to false,
            RecognitionBackend.OpenAI to true,
            RecognitionBackend.OpenAICompatible to false,
            RecognitionBackend.OpenRouter to false,
            RecognitionBackend.Anthropic to false,
        )) {
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
                    if (backend == RecognitionBackend.Anthropic) {
                        assertEquals("test-key", request.headers["x-api-key"])
                    } else {
                        assertEquals("Bearer test-key", request.headers["Authorization"])
                    }
                    val body = request.body.toByteArray().decodeToString()
                    assertEquals("test-vision", Json.parseToJsonElement(body).jsonObject["model"]!!.jsonPrimitive.content)
                    assertTrue(body.contains("image"))
                    assertTrue(body.contains("AQID")) // In-memory JPEG bytes; never a content:// URI.
                    val response = when {
                        backend == RecognitionBackend.Anthropic -> ANTHROPIC_RESPONSE
                        responses -> RESPONSES_RESPONSE
                        else -> CHAT_RESPONSE
                    }
                    respond(response, headers = headersOf("Content-Type", "application/json"))
                })
            }
            val balance = recognizer.recognize(backend,
                KoogConnection("https://api.example.com/custom/v1/", "test-vision", "test-key", responses),
                byteArrayOf(1, 2, 3))
            assertEquals(-245.70, balance, 0.0)
            assertEquals(1, requests)
        }
    }

    @Test fun authorizationError_isSanitizedAndClassified() = runTest {
        val recognizer = KoogImageRecognizer {
            HttpClient(MockEngine {
                respond("{\"error\":\"secret-provider-response\"}", HttpStatusCode.Unauthorized)
            })
        }
        try {
            recognizer.recognize(RecognitionBackend.OpenRouter,
                KoogConnection("https://api.example.com/v1", "test-vision", "test-key"), byteArrayOf(1))
            fail("Expected authorization error")
        } catch (error: RecognitionConnectionException) {
            assertEquals(401, error.statusCode)
            assertFalse(error.message!!.contains("secret-provider-response"))
            assertNull(error.cause)
        }
    }

    @Test fun invalidModelOutput_cannotSilentlyBecomeZeroOrNonfiniteBalance() {
        for (value in listOf("UNKNOWN", "", "NaN", "Infinity", "Balance: 123", "1,234.56")) {
            assertThrows(InvalidBalanceResponseException::class.java) { parseRemoteBalance(value) }
        }
        assertEquals(0.0, parseRemoteBalance("0"), 0.0)
        assertEquals(-1234.56, parseRemoteBalance(" -1234.56\n"), 0.0)
    }

    @Test fun transactionRecognition_sendsIdInstructions_andReadsSignedRecords() = runTest {
        val content = """{"transactions":[{"timestamp":"2026-10-06T12:30:00","amount":-12.5,"note":"Shop","transactionId":"TX-001"}]}"""
        val recognizer = KoogImageRecognizer {
            HttpClient(MockEngine { request ->
                val body = request.body.toByteArray().decodeToString()
                assertTrue(body.contains("transactionId"))
                assertTrue(body.contains("image"))
                val response = kotlinx.serialization.json.buildJsonObject {
                    put("id", kotlinx.serialization.json.JsonPrimitive("test"))
                    put("object", kotlinx.serialization.json.JsonPrimitive("chat.completion"))
                    put("created", kotlinx.serialization.json.JsonPrimitive(1))
                    put("model", kotlinx.serialization.json.JsonPrimitive("test-vision"))
                    put("choices", kotlinx.serialization.json.buildJsonArray {
                        add(kotlinx.serialization.json.buildJsonObject {
                            put("index", kotlinx.serialization.json.JsonPrimitive(0))
                            put("message", kotlinx.serialization.json.buildJsonObject {
                                put("role", kotlinx.serialization.json.JsonPrimitive("assistant"))
                                put("content", kotlinx.serialization.json.JsonPrimitive(content))
                            })
                            put("finish_reason", kotlinx.serialization.json.JsonPrimitive("stop"))
                        })
                    })
                }.toString()
                respond(response, headers = headersOf("Content-Type", "application/json"))
            })
        }
        val records = recognizer.recognizeTransactions(RecognitionBackend.OpenRouter,
            KoogConnection("https://api.example.com/v1", "test-vision", "test-key"), byteArrayOf(1))
        assertEquals(-12.5, records.single().amount, 0.0)
        assertEquals("TX-001", records.single().transactionId)
    }

    companion object {
        private const val CHAT_RESPONSE = """{"id":"test","object":"chat.completion","created":1,"model":"test-vision","choices":[{"index":0,"message":{"role":"assistant","content":"-245.70"},"finish_reason":"stop"}]}"""
        private const val ANTHROPIC_RESPONSE = """{"id":"test","type":"message","role":"assistant","model":"test-vision","content":[{"type":"text","text":"-245.70"}],"stop_reason":"end_turn"}"""
        private const val RESPONSES_RESPONSE = """{"id":"test","object":"response","created_at":1,"model":"test-vision","status":"completed","parallel_tool_calls":false,"text":{"format":{"type":"text"}},"output":[{"type":"message","id":"msg_test","role":"assistant","status":"completed","content":[{"type":"output_text","text":"-245.70","annotations":[]}]}]}"""
    }
}
