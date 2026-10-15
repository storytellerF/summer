package com.storytellerf.summer.data.llmd

import com.storytellerf.summer.data.recognition.RecognitionImageStore
import com.storytellerf.summer.data.recognition.FileRecognitionImageStore
import com.storytellerf.summer.data.recognition.BalanceReadTarget
import com.storytellerf.summer.data.recognition.RecognizedBalances
import com.storytellerf.summer.data.recognition.balancesPrompt
import com.storytellerf.summer.data.recognition.parseBalances
import com.storytellerf.summer.data.recognition.RecognizedTransactions
import com.storytellerf.summer.data.recognition.transactionsPrompt
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

class LlmdImageAnalyzer(
    context: Context,
    private val serviceConnection: LlmdServiceConnection,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val imageStore: RecognitionImageStore = FileRecognitionImageStore(context.applicationContext.filesDir),
) : FinanceImageAnalyzer {
    override suspend fun extractBalancesFromImage(imageReference: String, targets: List<BalanceReadTarget>, target: LlmdTarget): Result<RecognizedBalances> =
        analyzeImage(imageReference, target, { uri, model -> buildBalancesExtractionRequest(uri, targets, model) }) { response, image ->
            RecognizedBalances(parseBalances(extractResponseContent(response), targets), imageStore.save(image.jpeg))
        }
    private val appContext = context.applicationContext

    override suspend fun extractTransactionsFromImage(imageReference: String, target: LlmdTarget, currency: String?): Result<RecognizedTransactions> =
        analyzeImage(imageReference, target, { uri, model -> buildTransactionExtractionRequest(uri, model, currency) }) { response, image ->
            val records = parseTransactions(extractResponseContent(response), currency = currency)
            RecognizedTransactions(image.hash, records, imageStore.save(image.jpeg))
        }

    private suspend fun <T> analyzeImage(
        imageReference: String,
        target: LlmdTarget,
        buildRequest: (String, String) -> String,
        parse: (String, PreparedImage) -> T,
    ): Result<T> {
        var preparedImage: PreparedImage? = null
        return try {
            val image = withContext(ioDispatcher) { prepareImage(imageReference.toUri()) }
            preparedImage = image
            appContext.grantUriPermission(target.packageName, image.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val response = serviceConnection.chatCompletion(target, buildRequest(image.uri.toString(), serviceConnection.activeModel(target)))
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

internal fun buildBalancesExtractionRequest(imageUrl: String, targets: List<BalanceReadTarget>, model: String): String = JSONObject().apply {
    put("model", model)
    put("max_tokens", 8192)
    put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray()
        .put(JSONObject().put("type", "text").put("text", balancesPrompt(targets)))
        .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", imageUrl))))))
    // LiteRT-LM's guided generation drops readable values with the installed Gemma model.
    // The shared prompt requests JSON; parseBalances still enforces the complete field contract.
    put("response_format", JSONObject().put("type", "text"))
}.toString()

class LlmdAuthorizationException :
    Exception("Authorization required. Please authorize the app in llmd.")

internal fun buildTransactionExtractionRequest(imageUrl: String, model: String, currency: String? = null): String = JSONObject().apply {
    put("model", model)
    put("max_tokens", 8192)
    put("messages", JSONArray().put(JSONObject().apply {
        put("role", "user")
        put("content", JSONArray()
            .put(JSONObject().put("type", "text").put("text", transactionsPrompt(currency)))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", imageUrl))))
    }))
    // Keep screenshot reasoning available; parseTransactions rejects malformed output.
    put("response_format", JSONObject().put("type", "text"))
}.toString()

internal fun extractResponseContent(responseJson: String): String {
    val response = JSONObject(responseJson)
    response.optJSONObject("error")?.let { error ->
        if (error.optString("type") == "authorization_required") throw LlmdAuthorizationException()
        throw llmdRecognitionError(error.optString("type"), error.optString("message"))
    }
    return response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
}
