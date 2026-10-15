package com.storytellerf.summer

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.core.net.toUri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.storytellerf.summer.data.DefaultDataRepository
import com.storytellerf.summer.data.db.SummerDatabase
import com.storytellerf.summer.data.db.entity.FundSource
import com.storytellerf.summer.data.llmd.LlmdTarget
import com.storytellerf.summer.data.llmd.LlmdImageAnalyzer
import com.storytellerf.summer.data.llmd.LlmdServiceConnection
import com.storytellerf.summer.data.recognition.*
import com.storytellerf.summer.ui.components.formatSignedMoney
import com.storytellerf.summer.ui.importtransactions.ImportTransactionsScreen
import com.storytellerf.summer.ui.importtransactions.ImportTransactionsViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.headersOf
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** External public fixtures are supplied by scripts/test-transactions.py; never bundled in the APK. */
class TransactionScreenshotImportTest {
    @get:Rule val compose = createComposeRule()

    @Test fun externalScreenshotThroughEncoderPreviewAndDatabase() = runTest(timeout = 180.seconds) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = File(context.noBackupFilesDir, "transaction-test-image")
        val groundTruth = File(context.noBackupFilesDir, "transaction-test-expected.json")
        assumeTrue("Run scripts/test-transactions.py to provide a public screenshot and expected rows", image.isFile && groundTruth.isFile)
        val expectedJson = groundTruth.readText()
        val expectedCount = Json.parseToJsonElement(expectedJson).jsonObject.getValue("transactions").jsonArray.size
        val expected = if (expectedCount == 0) emptyList() else parseTransactions(expectedJson)
        val live = InstrumentationRegistry.getArguments().getString("transaction_live") == "true"
        val llmdBuild = InstrumentationRegistry.getArguments().getString("transaction_llmd")
        val llmdTarget = when (llmdBuild) {
            "release" -> LlmdTarget.Release
            "alpha" -> LlmdTarget.Alpha
            "debug" -> LlmdTarget.Debug
            null -> LlmdTarget.Release
            else -> error("Unknown LLMD build")
        }
        val keyFile = File(context.noBackupFilesDir, "transaction-test-key")
        if (live) assumeTrue("Live recognition credentials are missing", keyFile.isFile)
        val model = InstrumentationRegistry.getArguments().getString("transaction_model") ?: "test-vision"
        val uploaded = CompletableDeferred<ByteArray>()
        val recognizer = if (live) KoogImageRecognizer(::liveRecognitionTransport) else KoogImageRecognizer {
            HttpClient(MockEngine { request ->
                val body = request.body.toByteArray().decodeToString()
                assertTrue(body.contains("transactionId"))
                val encoded = Regex("data:image/jpeg;base64,([^\"]+)").find(body)?.groupValues?.get(1)
                assertNotNull("The request must contain the compressed screenshot inline", encoded)
                uploaded.complete(Base64.decode(encoded, Base64.DEFAULT))
                val response = buildJsonObject {
                    put("id", "test"); put("object", "chat.completion"); put("created", 1); put("model", "test-vision")
                    putJsonArray("choices") { addJsonObject {
                        put("index", 0); put("finish_reason", "stop")
                        putJsonObject("message") { put("role", "assistant"); put("content", expectedJson) }
                    } }
                }
                respond(response.toString(), headers = headersOf("Content-Type", "application/json"))
            })
        }
        val settings = object : RecognitionSettings {
            override val config = MutableStateFlow(RecognitionConfig(if (llmdBuild != null) RecognitionBackend.Llmd else RecognitionBackend.OpenRouter,
                mapOf(RecognitionBackend.OpenRouter to KoogConnection(
                    if (live) "https://openrouter.ai/api/v1" else "https://api.example.com/v1",
                    model, if (live) keyFile.readText().trim() else "test-key"))))
            override suspend fun save(backend: RecognitionBackend, connection: KoogConnection) = Unit
            override suspend fun clearConnection(backend: RecognitionBackend) = Unit
        }
        val images = File(context.cacheDir, "transaction-fixture-images").apply { mkdirs() }
        val database = Room.inMemoryDatabaseBuilder(context, SummerDatabase::class.java).build()
        val store = ViewModelStore()
        try {
            val repository = DefaultDataRepository(database)
            val currency = requireNotNull(com.storytellerf.summer.data.currencyCode(
                InstrumentationRegistry.getArguments().getString("transaction_currency") ?: "CNY"))
            val sourceId = repository.insertFundSource(FundSource(name = "Fixture Account", currency = currency))
            val account = FundSource(id = sourceId, name = "Fixture Account", currency = currency)
            val encoder = RecognitionImageEncoder(context)
            val imageStore = FileRecognitionImageStore(images)
            val llmd = if (llmdBuild != null) LlmdImageAnalyzer(context, LlmdServiceConnection(context), imageStore = imageStore)
                else object : FinanceImageAnalyzer {}
            val configured = ConfiguredImageAnalyzer(settings, llmd,
                { encoder.encode(it.toUri()) }, recognizer, imageStore)
            val recognitionFailure = CompletableDeferred<Throwable?>()
            val analyzer = object : FinanceImageAnalyzer {
                override suspend fun extractTransactionsFromImage(imageReference: String, target: LlmdTarget, currency: String?) =
                    configured.extractTransactionsFromImage(imageReference, target, currency).also { result ->
                        recognitionFailure.complete(result.exceptionOrNull())
                        result.exceptionOrNull()?.let { error ->
                            val connection = error as? RecognitionConnectionException
                            android.util.Log.w("ScreenshotTest", "failure=${error.javaClass.simpleName} status=${connection?.statusCode} category=${connection?.category} parser=${(error as? InvalidTransactionResponseException)?.failureType} origin=${error.stackTrace.firstOrNull()}")
                        }
                    }
                override fun close() = configured.close()
            }
            val viewModel = ImportTransactionsViewModel(repository, analyzer, flowOf(llmdTarget))
            store.put("import", viewModel)
            val saved = CompletableDeferred<Unit>()
            compose.setContent { ImportTransactionsScreen(onBack = { saved.complete(Unit) }, viewModel = viewModel) }
            compose.runOnIdle { viewModel.selectFundSource(account); viewModel.recognize(image.toUri().toString()) }
            compose.waitUntil(120_000) { !viewModel.uiState.value.isAnalyzing && (viewModel.uiState.value.rows.isNotEmpty() || viewModel.uiState.value.error != null) }
            if (expected.isEmpty()) {
                assertTrue("Expected an empty/mismatched-currency response, not a connection or image failure",
                    recognitionFailure.await() is InvalidTransactionResponseException)
                assertTrue(viewModel.uiState.value.rows.isEmpty())
                assertNotNull(viewModel.uiState.value.error)
                assertTrue(database.balanceImpactRecordDao().getInRange(null, null).isEmpty())
                assertFalse(File(images, "recognition-images").exists())
                return@runTest
            }
            assertNull(viewModel.uiState.value.error)
            assertEquals(expected.size, viewModel.uiState.value.rows.size)
            assertEquals("Recognized amounts must match the manually checked screenshot",
                expected.map { it.amount }.sorted(), viewModel.uiState.value.rows.map { it.amount.toDouble() }.sorted())
            expected.sortedBy { it.amount }.zip(viewModel.uiState.value.rows.sortedBy { it.amount.toDouble() }).forEach { (truth, row) ->
                assertEquals("Recognized dates must match screenshot evidence before manual correction",
                    truth.timestamp?.let { formatLocalDateTime(it) }.orEmpty(), row.date)
            }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(formatSignedMoney(expected.first().amount, currency)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(formatSignedMoney(expected.first().amount, currency)).performScrollTo().assertIsDisplayed()
            if (expected.any { it.timestamp == null }) {
                // Unknown screenshot dates must block persistence until the user supplies verified dates.
                compose.onNodeWithText("Import selected transactions").performClick()
                compose.waitUntil(10_000) { viewModel.uiState.value.error != null }
                assertTrue(database.balanceImpactRecordDao().getInRange(null, null).isEmpty())
                compose.runOnIdle {
                    viewModel.uiState.value.rows.forEachIndexed { index, row ->
                        if (row.date.isBlank()) viewModel.updateRow(index, row.copy(date = "2020-01-02T03:04:05"))
                    }
                }
                compose.waitUntil(10_000) { viewModel.uiState.value.rows.all { it.date.isNotBlank() } }
            }
            compose.onNodeWithText("Import selected transactions").performClick()
            compose.waitUntil(10_000) { saved.isCompleted }
            val records = database.balanceImpactRecordDao().getInRange(null, null)
            assertEquals(expected.size, records.size)
            val actual = records.sortedWith(compareBy({ it.timestamp }, { it.amount }))
            expected.sortedWith(compareBy({ it.timestamp ?: parseLocalDateTime("2020-01-02T03:04:05") }, { it.amount })).zip(actual).forEach { (truth, record) ->
                assertEquals(truth.timestamp ?: parseLocalDateTime("2020-01-02T03:04:05"), record.timestamp)
                assertEquals(truth.amount, record.amount, 0.005)
                assertEquals(truth.transactionId, record.transactionId)
            }
            val path = records.map { it.imagePath }.distinct().single()
            assertNotNull(path)
            val retained = File(images, path!!)
            assertTrue(retained.isFile)
            assertTrue(retained.length() <= 500_000)
            val decoded = BitmapFactory.decodeFile(retained.path)
            assertNotNull(decoded)
            assertTrue(maxOf(decoded.width, decoded.height) <= 1600)
            decoded.recycle()
            if (!live && llmdBuild == null) assertArrayEquals(uploaded.await(), retained.readBytes())
            assertEquals(imageHash(retained.readBytes()), records.first().imageHash)
            compose.runOnIdle { viewModel.save() }
            compose.waitUntil(10_000) { viewModel.uiState.value.error != null }
            assertEquals(expected.size, database.balanceImpactRecordDao().getInRange(null, null).size)
        } finally {
            compose.runOnIdle { store.clear() }
            database.close()
            images.deleteRecursively()
        }
    }
}
