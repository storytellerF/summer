package com.storytellerf.summer.data.recognition

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.core.net.toUri
import com.storytellerf.summer.data.recognition.FinanceImageAnalyzer
import com.storytellerf.summer.data.llmd.LlmdImageAnalyzer
import com.storytellerf.summer.data.llmd.LlmdServiceConnection
import com.storytellerf.summer.data.llmd.LlmdTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Takes a single settings snapshot for each request; changing settings never reroutes an in-flight image. */
class ConfiguredImageAnalyzer(
    private val settings: RecognitionSettings,
    private val llmd: FinanceImageAnalyzer,
    private val readJpeg: suspend (String) -> ByteArray,
    private val remote: RemoteImageRecognizer = KoogImageRecognizer(),
    private val imageStore: RecognitionImageStore,
) : FinanceImageAnalyzer {
    override suspend fun extractBalancesFromImage(imageReference: String, targets: List<BalanceReadTarget>, target: LlmdTarget): Result<RecognizedBalances> = try {
        val config = settings.config.first()
        if (config.backend == RecognitionBackend.Llmd) llmd.extractBalancesFromImage(imageReference, targets, target)
        else {
            val connection = config.connectionFor().validated()
            val jpeg = readJpeg(imageReference)
            val records = remote.recognizeBalances(config.backend, connection, jpeg, targets)
            require(records.isNotEmpty())
            Result.success(RecognizedBalances(records, imageStore.save(jpeg)))
        }
    } catch (error: CancellationException) { throw error }
    catch (error: Exception) { Result.failure(error) }
    override suspend fun extractBalanceFromImage(imageReference: String, target: LlmdTarget): Result<Double> =
        extractBalanceWithImage(imageReference, target).map { it.balance }

    override suspend fun extractBalanceWithImage(imageReference: String, target: LlmdTarget): Result<RecognizedBalance> {
        return try {
            val config = settings.config.first()
            if (config.backend == RecognitionBackend.Llmd) {
                llmd.extractBalanceWithImage(imageReference, target)
            } else {
                val connection = config.connectionFor().validated()
                val jpeg = readJpeg(imageReference)
                val balance = remote.recognize(config.backend, connection, jpeg)
                Result.success(RecognizedBalance(balance, imageStore.save(jpeg)))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    override suspend fun extractTransactionsFromImage(imageReference: String, target: LlmdTarget): Result<RecognizedTransactions> {
        return try {
            val config = settings.config.first()
            if (config.backend == RecognitionBackend.Llmd) {
                llmd.extractTransactionsFromImage(imageReference, target)
            } else {
                val connection = config.connectionFor().validated()
                val jpeg = readJpeg(imageReference)
                val records = remote.recognizeTransactions(config.backend, connection, jpeg)
                Result.success(RecognizedTransactions(imageHash(jpeg), records, imageStore.save(jpeg)))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    override fun close() = llmd.close()
}

fun configuredImageAnalyzer(
    context: Context,
    io: CoroutineDispatcher = Dispatchers.IO,
    remote: RemoteImageRecognizer = KoogImageRecognizer(),
): FinanceImageAnalyzer {
    val appContext = context.applicationContext
    val encoder = RecognitionImageEncoder(appContext)
    return ConfiguredImageAnalyzer(
        settings = DataStoreRecognitionSettings(appContext),
        imageStore = FileRecognitionImageStore(appContext.filesDir),
        llmd = LlmdImageAnalyzer(appContext, LlmdServiceConnection(appContext), io),
        readJpeg = { reference -> withContext(io) { encoder.encode(reference.toUri()) } },
        remote = object : RemoteImageRecognizer {
            override suspend fun recognizeBalances(backend: RecognitionBackend, connection: KoogConnection, jpeg: ByteArray, targets: List<BalanceReadTarget>): List<RecognizedAccountBalance> =
                loggedRecognition(backend, "balances") { remote.recognizeBalances(backend, connection, jpeg, targets) }
            override suspend fun recognize(backend: RecognitionBackend, connection: KoogConnection, jpeg: ByteArray): Double =
                loggedRecognition(backend, "balance") { remote.recognize(backend, connection, jpeg) }

            override suspend fun recognizeTransactions(backend: RecognitionBackend, connection: KoogConnection, jpeg: ByteArray): List<RecognizedTransaction> =
                loggedRecognition(backend, "transactions") { remote.recognizeTransactions(backend, connection, jpeg) }
        },
    )
}

private suspend fun <T> loggedRecognition(backend: RecognitionBackend, kind: String, operation: suspend () -> T): T {
    val started = SystemClock.elapsedRealtime()
    return try {
        operation().also {
            Log.i("ImageRecognition", "operation=$kind provider=${backend.name} outcome=success duration_ms=${SystemClock.elapsedRealtime() - started}")
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        val connectionError = error as? RecognitionConnectionException
        Log.w("ImageRecognition", "operation=$kind provider=${backend.name} outcome=failure status=${connectionError?.statusCode} category=${connectionError?.category ?: error.javaClass.simpleName}")
        throw error
    }
}
