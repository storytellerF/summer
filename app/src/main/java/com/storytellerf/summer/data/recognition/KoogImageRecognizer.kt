package com.storytellerf.summer.data.recognition

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterClientSettings
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.params.LLMParams
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CancellationException

fun interface RemoteImageRecognizer {
    suspend fun recognize(backend: RecognitionBackend, connection: KoogConnection, jpeg: ByteArray): Double
    suspend fun recognizeTransactions(backend: RecognitionBackend, connection: KoogConnection, jpeg: ByteArray): List<RecognizedTransaction> =
        throw UnsupportedOperationException("Transaction recognition is unavailable")
    suspend fun recognizeBalances(backend: RecognitionBackend, connection: KoogConnection, jpeg: ByteArray, targets: List<BalanceReadTarget>): List<RecognizedAccountBalance> =
        throw UnsupportedOperationException("Requested balance recognition is unavailable")
}

class KoogImageRecognizer(
    private val transportFactory: () -> HttpClient = { HttpClient(OkHttp) },
) : RemoteImageRecognizer {
    override suspend fun recognizeBalances(backend: RecognitionBackend, connection: KoogConnection, jpeg: ByteArray, targets: List<BalanceReadTarget>): List<RecognizedAccountBalance> =
        parseBalances(execute(backend, connection, jpeg, balancesPrompt(targets), 8192), targets)
    override suspend fun recognize(backend: RecognitionBackend, connection: KoogConnection, jpeg: ByteArray): Double =
        parseRemoteBalance(execute(backend, connection, jpeg, REMOTE_BALANCE_PROMPT, 1024))

    override suspend fun recognizeTransactions(backend: RecognitionBackend, connection: KoogConnection, jpeg: ByteArray): List<RecognizedTransaction> =
        parseTransactions(execute(backend, connection, jpeg, TRANSACTION_EXTRACTION_PROMPT, 8192))

    private suspend fun execute(backend: RecognitionBackend, connection: KoogConnection, jpeg: ByteArray, instruction: String, maxTokens: Int): String {
        val validated = connection.validated()
        require(backend != RecognitionBackend.Llmd) { "Select an API provider" }
        val model = LLModel(
            provider = when (backend) {
                RecognitionBackend.Anthropic -> LLMProvider.Anthropic
                RecognitionBackend.OpenRouter -> LLMProvider.OpenRouter
                else -> LLMProvider.OpenAI
            },
            id = validated.model,
            capabilities = buildList {
                add(LLMCapability.Completion)
                add(LLMCapability.Vision.Image)
                // Koog 1.3's Anthropic client requires this even when no tools are sent.
                if (backend == RecognitionBackend.Anthropic) add(LLMCapability.Tools)

                if (backend in listOf(RecognitionBackend.OpenAI, RecognitionBackend.OpenAICompatible) &&
                    validated.useResponsesApi) {
                    add(LLMCapability.OpenAIEndpoint.Responses)
                } else if (backend in listOf(RecognitionBackend.OpenAI, RecognitionBackend.OpenAICompatible)) {
                    add(LLMCapability.OpenAIEndpoint.Completions)
                }
            },
        )
        // Explicit Android-compatible engine; no service-loader or desktop-only client dependency.
        val transport = transportFactory()
        try {
            val factory = KtorKoogHttpClient.Factory(transport)
            val timeout = ConnectionTimeoutConfig(requestTimeoutMillis = 90_000, connectTimeoutMillis = 15_000,
                socketTimeoutMillis = 90_000)
            val client: LLMClient = when (backend) {
                RecognitionBackend.Anthropic -> AnthropicLLMClient(
                    apiKey = validated.apiKey,
                    settings = AnthropicClientSettings(modelVersionsMap = mapOf(model to validated.model), baseUrl = "${validated.baseUrl}/", messagesPath = "messages",
                        timeoutConfig = timeout),
                    httpClientFactory = factory,
                )
                RecognitionBackend.OpenRouter -> OpenRouterLLMClient(
                    apiKey = validated.apiKey,
                    settings = OpenRouterClientSettings(baseUrl = "${validated.baseUrl}/", chatCompletionsPath = "chat/completions",
                        timeoutConfig = timeout),
                    httpClientFactory = factory,
                )
                else -> OpenAILLMClient(
                    apiKey = validated.apiKey,
                    settings = OpenAIClientSettings(baseUrl = "${validated.baseUrl}/", chatCompletionsPath = "chat/completions",
                        responsesAPIPath = "responses", timeoutConfig = timeout),
                    httpClientFactory = factory,
                )
            }
            try {
                val response = client.execute(
                    prompt("image-extraction", params = LLMParams(maxTokens = maxTokens)) {
                        user {
                            text(instruction)
                            image(AttachmentSource.Image(AttachmentContent.Binary.Bytes(jpeg), "jpeg", "image/jpeg"))
                        }
                    },
                    model,
                )
                return response.textContent()
            } finally {
                client.close()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: InvalidBalanceResponseException) {
            throw error
        } catch (error: Exception) {
            // Provider exceptions may contain response bodies or credentials. Never show or log them.
            val httpError = generateSequence<Throwable>(error) { it.cause }
                .filterIsInstance<KoogHttpClientException>().firstOrNull()
            throw RecognitionConnectionException(httpError?.statusCode, classifyProviderFailure(httpError?.errorBody))
        } finally {
            transport.close()
        }
    }
}

internal fun parseRemoteBalance(content: String): Double = content.trim().toDoubleOrNull()
    ?.takeIf(Double::isFinite) ?: throw InvalidBalanceResponseException()

class InvalidBalanceResponseException : Exception("The model did not return a valid balance. Check the image and model.")
internal fun classifyProviderFailure(body: String?): String {
    val text = body.orEmpty().lowercase()
    return when {
        "free-models-per-day" in text || "daily limit" in text -> "daily_quota"
        "free-models-per-minute" in text || "per minute" in text -> "minute_quota"
        "upstream" in text || "provider returned error" in text -> "upstream_provider"
        "no endpoints" in text -> "model_unavailable"
        "invalid api key" in text -> "authorization"
        else -> "unspecified"
    }
}

class RecognitionConnectionException(val statusCode: Int? = null, val category: String = "unspecified") : Exception(
    when (statusCode) {
        401, 403 -> "API authorization failed. Check your API key and provider permissions."
        402 -> "The provider requires credits. Choose a free model or check your account."
        429 -> "The provider rate limit was reached. Try again later."
        else -> "Image recognition failed. Check the endpoint, image-capable model, provider limits, and network connection."
    }
)

private const val REMOTE_BALANCE_PROMPT = """
Read the account balance from this screenshot. Return only its numeric value, no prose or markdown.
Remove currency symbols and grouping separators. Preserve a negative sign.
For example, 1,234.56 is 1234.56 and -45.67 is -45.67.
If there is no readable account balance, return the word UNKNOWN. Do not guess or return zero.
"""
