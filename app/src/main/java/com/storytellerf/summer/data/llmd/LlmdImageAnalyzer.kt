package com.storytellerf.summer.data.llmd

import com.storytellerf.summer.data.recognition.RecognitionImageStore
import com.storytellerf.summer.data.recognition.FileRecognitionImageStore
import com.storytellerf.summer.data.recognition.RecognizedBalance
import com.storytellerf.summer.data.recognition.BalanceReadTarget
import com.storytellerf.summer.data.recognition.RecognizedBalances
import com.storytellerf.summer.data.recognition.balancesPrompt
import com.storytellerf.summer.data.recognition.BALANCES_RESPONSE_SCHEMA
import com.storytellerf.summer.data.recognition.parseBalances
import com.storytellerf.summer.data.recognition.RecognizedTransactions
import com.storytellerf.summer.data.recognition.TRANSACTION_EXTRACTION_PROMPT
import com.storytellerf.summer.data.recognition.TRANSACTION_RESPONSE_SCHEMA
import com.storytellerf.summer.data.recognition.parseTransactions
import com.storytellerf.summer.data.recognition.imageHash
import com.storytellerf.summer.data.recognition.FinanceImageAnalyzer
import com.storytellerf.summer.data.recognition.RecognitionImageEncoder
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

class LlmdImageAnalyzer(
    context: Context,
    private val serviceConnection: LlmdServiceConnection,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val imageStore: RecognitionImageStore = FileRecognitionImageStore(context.applicationContext.filesDir),
) : FinanceImageAnalyzer {
    override suspend fun extractBalancesFromImage(imageReference: String, targets: List<BalanceReadTarget>, target: LlmdTarget): Result<RecognizedBalances> =
        analyzeImage(imageReference, target, { buildBalancesExtractionRequest(it, targets) }) { response, image ->
            RecognizedBalances(parseBalances(extractResponseContent(response), targets), imageStore.save(image.jpeg))
        }
    private val appContext = context.applicationContext

    override suspend fun extractBalanceFromImage(imageReference: String, target: LlmdTarget): Result<Double> =
        extractBalanceWithImage(imageReference, target).map { it.balance }

    override suspend fun extractBalanceWithImage(imageReference: String, target: LlmdTarget): Result<RecognizedBalance> =
        analyzeImage(imageReference, target, ::buildBalanceExtractionRequest) { response, image ->
            val balance = parseBalanceFromResponse(response).getOrThrow()
            RecognizedBalance(balance, imageStore.save(image.jpeg))
        }

    override suspend fun extractTransactionsFromImage(imageReference: String, target: LlmdTarget): Result<RecognizedTransactions> =
        analyzeImage(imageReference, target, ::buildTransactionExtractionRequest) { response, image ->
            val records = parseTransactions(extractResponseContent(response))
            RecognizedTransactions(image.hash, records, imageStore.save(image.jpeg))
        }

    private suspend fun <T> analyzeImage(
        imageReference: String,
        target: LlmdTarget,
        buildRequest: (String) -> String,
        parse: (String, PreparedImage) -> T,
    ): Result<T> {
        var preparedImage: PreparedImage? = null
        return try {
            val image = withContext(ioDispatcher) { prepareImage(imageReference.toUri()) }
            preparedImage = image
            appContext.grantUriPermission(target.packageName, image.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val response = serviceConnection.chatCompletion(target, buildRequest(image.uri.toString()))
            withContext(ioDispatcher) { Result.success(parse(response, image)) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            preparedImage?.let { image ->
                withContext(NonCancellable + ioDispatcher) {
                    appContext.revokeUriPermission(image.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    image.file.delete()
                }
            }
        }
    }

    override fun close() {
        serviceConnection.close()
    }

    private fun prepareImage(uri: Uri): PreparedImage {
        val bytes = RecognitionImageEncoder(appContext).encode(uri)
        val imageDirectory = File(appContext.cacheDir, "llmd-images").apply { mkdirs() }
        val file = File.createTempFile("balance-", ".jpg", imageDirectory)
        FileOutputStream(file).use { it.write(bytes) }
        return PreparedImage(
            uri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.llmd-images",
                file,
            ),
            file = file,
            hash = imageHash(bytes),
            jpeg = bytes,
        )
    }
}

private data class PreparedImage(
    val uri: Uri,
    val file: File,
    val hash: String,
    val jpeg: ByteArray,
)

internal fun buildBalanceExtractionRequest(imageUrl: String): String {
    val content = JSONArray().apply {
        put(JSONObject().apply {
            put("type", "text")
            put("text", BALANCE_EXTRACTION_PROMPT)
        })
        put(JSONObject().apply {
            put("type", "image_url")
            put("image_url", JSONObject().apply {
                put("url", imageUrl)
            })
        })
    }

    val messages = JSONArray().apply {
        put(JSONObject().apply {
            put("role", "user")
            put("content", content)
        })
    }

    return JSONObject().apply {
        put("model", MODEL_NAME)
        put("messages", messages)
        put("max_tokens", 100)
        put("response_format", buildBalanceResponseFormat())
    }.toString()
}

private fun buildBalanceResponseFormat(): JSONObject = JSONObject().apply {
    put("type", "json_schema")
    put("json_schema", JSONObject().apply {
        put("name", "balance_extraction")
        put("strict", true)
        put("schema", JSONObject().apply {
            put("type", "number")
            put("description", "The balance amount shown in the screenshot")
        })
    })
}

internal fun parseBalanceFromResponse(responseJson: String): Result<Double> {
    return try {
        val response = JSONObject(responseJson)
        val error = response.optJSONObject("error")
        if (error != null) {
            val errorType = error.optString("type", "unknown")
            if (errorType == "authorization_required") {
                return Result.failure(LlmdAuthorizationException())
            }
            return Result.failure(Exception(error.optString("message", "Unknown error")))
        }

        val choices = response.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            val content = choices.getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
            val balance = JSONTokener(content).nextValue()
            require(balance is Number) { "Structured balance response is not a number" }
            Result.success(balance.toDouble())
        } else {
            Result.failure(Exception("No balance found in image"))
        }
    } catch (error: Exception) {
        Result.failure(error)
    }
}

private const val MODEL_NAME = "gemma-4-E2B-it"

internal fun buildBalancesExtractionRequest(imageUrl: String, targets: List<BalanceReadTarget>): String = JSONObject().apply {
    put("model", MODEL_NAME)
    put("max_tokens", 8192)
    put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray()
        .put(JSONObject().put("type", "text").put("text", balancesPrompt(targets)))
        .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", imageUrl))))))
    put("response_format", JSONObject().put("type", "json_schema").put("json_schema", JSONObject()
        .put("name", "balances_extraction").put("strict", true).put("schema", JSONObject(BALANCES_RESPONSE_SCHEMA))))
}.toString()

const val BALANCE_EXTRACTION_PROMPT = """
Extract the balance amount from this screenshot.
Return the numeric value, including a leading minus sign when the balance is negative.
Do not include currency symbols in the value.
For example, ¥1,234.56 is 1234.56 and -¥45.67 is -45.67.
If you cannot find a balance, return 0.
"""

class LlmdAuthorizationException :
    Exception("Authorization required. Please authorize the app in llmd.")

internal fun buildTransactionExtractionRequest(imageUrl: String): String = JSONObject().apply {
    put("model", MODEL_NAME)
    put("max_tokens", 8192)
    put("messages", JSONArray().put(JSONObject().apply {
        put("role", "user")
        put("content", JSONArray()
            .put(JSONObject().put("type", "text").put("text", TRANSACTION_EXTRACTION_PROMPT))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", imageUrl))))
    }))
    put("response_format", JSONObject().put("type", "json_schema").put("json_schema", JSONObject()
        .put("name", "transaction_extraction").put("strict", true).put("schema", JSONObject(TRANSACTION_RESPONSE_SCHEMA))))
}.toString()

internal fun extractResponseContent(responseJson: String): String {
    val response = JSONObject(responseJson)
    response.optJSONObject("error")?.let { error ->
        if (error.optString("type") == "authorization_required") throw LlmdAuthorizationException()
        throw Exception("Transaction recognition failed in llmd")
    }
    return response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
}
