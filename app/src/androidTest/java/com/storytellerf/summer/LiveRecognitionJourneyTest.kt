package com.storytellerf.summer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.storytellerf.summer.data.DefaultDataRepository
import com.storytellerf.summer.data.db.SummerDatabase
import com.storytellerf.summer.data.db.entity.FundSource
import com.storytellerf.summer.data.llmd.DataStoreLlmdTargetSettings
import com.storytellerf.summer.data.llmd.LlmdTarget
import com.storytellerf.summer.data.recognition.DataStoreRecognitionSettings
import com.storytellerf.summer.data.recognition.KoogConnection
import com.storytellerf.summer.data.recognition.KoogImageRecognizer
import com.storytellerf.summer.data.recognition.RemoteImageRecognizer
import com.storytellerf.summer.data.recognition.RecognitionBackend
import com.storytellerf.summer.data.recognition.configuredImageAnalyzer
import com.storytellerf.summer.data.recognition.FinanceImageAnalyzer
import com.storytellerf.summer.data.recognition.BalanceReadTarget
import com.storytellerf.summer.data.recognition.InvalidBalancesResponseException
import com.storytellerf.summer.theme.SummerAppTheme
import com.storytellerf.summer.ui.addbalance.AddBalanceChangeScreen
import com.storytellerf.summer.ui.addbalance.AddBalanceChangeViewModel
import java.io.File
import java.util.Locale
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.headersOf
import io.ktor.client.request.get
import io.ktor.client.request.header
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in: the runner provisions a key in app-private storage, never as an instrumentation argument. */
class LiveRecognitionJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun syntheticImage_withMockTransport_isRenderedAndSavedThroughKoog() {
        val recognizer = KoogImageRecognizer {
            HttpClient(MockEngine { request ->
                assertEquals("/api/v1/chat/completions", request.url.encodedPath)
                assertTrue(request.body.toByteArray().decodeToString().contains("data:image/jpeg;base64,"))
                val requestJson = JSONObject(request.body.toByteArray().decodeToString())
                assertTrue(requestJson.getJSONObject("response_format").getJSONObject("json_schema").getBoolean("strict"))
                val response = JSONObject("""{"id":"test","model":"test-vision","choices":[{"message":{"role":"assistant"},"finish_reason":"stop"}]}""")
                response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").put("content",
                    """{"balances":[{"fundSourceId":null,"balance":-245.70,"label":"balance","currency":"CNY"}]}""")
                respond(response.toString(), headers = headersOf("Content-Type", "application/json"))
            })
        }
        exerciseRecognition(KoogConnection("https://api.example.com/api/v1", "test-vision", "instrumentation-placeholder-key"), recognizer)
    }

    @Test fun screenshotBalance_isRecognizedAndSavedFromTheForm() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val credentialFile = File(context.noBackupFilesDir, "koog-live-test.json")
        assumeTrue("Provision OPENROUTER_API_KEY with scripts/test-openrouter.py to run the live test", credentialFile.isFile)
        val credentials = runBlocking(Dispatchers.IO) { JSONObject(credentialFile.readText()) }
        val llmdTarget = when (credentials.optString("llmd")) {
            "debug" -> LlmdTarget.Debug
            "alpha" -> LlmdTarget.Alpha
            "release" -> LlmdTarget.Release
            else -> null
        }
        try {
            if (llmdTarget == null) runBlocking(Dispatchers.IO) {
                val client = liveRecognitionTransport()
                try {
                    val response = client.get("https://openrouter.ai/api/v1/key") {
                        header("Authorization", "Bearer ${credentials.getString("apiKey")}")
                    }
                    assertEquals("The API key must authenticate from the device", 200, response.status.value)
                } finally {
                    client.close()
                }
            }
            exerciseRecognition(if (llmdTarget != null) KoogConnection() else KoogConnection("https://openrouter.ai/api/v1", credentials.getString("model"), credentials.getString("apiKey")),
                KoogImageRecognizer(::liveRecognitionTransport),
                inputImage = if (credentials.has("image")) File(context.noBackupFilesDir, credentials.getString("image")) else null,
                expectedBalance = credentials.optDouble("expectedBalance", -245.70),
                currency = credentials.optString("currency", "CNY"), llmdTarget = llmdTarget)
        } finally {
            credentialFile.delete()
        }
    }

    private fun exerciseRecognition(
        connection: KoogConnection,
        recognizer: RemoteImageRecognizer,
        inputImage: File? = null,
        expectedBalance: Double = -245.70,
        currency: String = "CNY",
        llmdTarget: LlmdTarget? = null,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = DataStoreRecognitionSettings(context)
        val previous = runBlocking(Dispatchers.IO) { settings.config.first() }
        val database = SummerDatabase.getInstance(context)
        val image = inputImage ?: File(context.cacheDir, "synthetic-balance.png")
        val sourceId = runBlocking(Dispatchers.IO) {
            database.fundSourceDao().insert(FundSource(name = "Wallet", currency = requireNotNull(com.storytellerf.summer.data.currencyCode(currency))))
        }
        try {
            runBlocking(Dispatchers.IO) {
                settings.save(if (llmdTarget == null) RecognitionBackend.OpenRouter else RecognitionBackend.Llmd, connection)
                if (inputImage == null) createSyntheticScreenshot(image)
            }
            lateinit var viewModel: AddBalanceChangeViewModel
            val configured = configuredImageAnalyzer(context, remote = recognizer)
            val analyzer = object : FinanceImageAnalyzer {
                override suspend fun extractBalancesFromImage(imageReference: String, targets: List<BalanceReadTarget>, target: LlmdTarget) =
                    configured.extractBalancesFromImage(imageReference, targets, target).also { result ->
                        result.exceptionOrNull()?.let { error ->
                            android.util.Log.w("BalanceScreenshotTest", "failure=${error.javaClass.simpleName} parser=${(error as? InvalidBalancesResponseException)?.failureType}")
                        }
                    }
                override fun close() = configured.close()
            }
            compose.runOnUiThread {
                viewModel = ViewModelProvider(compose.activity, AddBalanceChangeViewModel.Factory(
                    DefaultDataRepository(database), analyzer,
                    llmdTarget?.let { flowOf(it) } ?: DataStoreLlmdTargetSettings(context).selectedTarget,
                ))[AddBalanceChangeViewModel::class.java]
                compose.activity.setContent {
                    SummerAppTheme { AddBalanceChangeScreen(onBack = {}, viewModel = viewModel) }
                }
            }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Wallet").fetchSemanticsNodes().isNotEmpty()
            }
            val source = runBlocking(Dispatchers.IO) { database.fundSourceDao().getById(sourceId)!! }
            compose.runOnIdle {
                viewModel.selectFundSource(source)
                viewModel.selectEntryMode(com.storytellerf.summer.ui.addbalance.BalanceEntryMode.Screenshots)
                viewModel.extractBalancesFromImages(listOf(image.toURI().toString()))
            }
            compose.waitUntil(120_000) {
                viewModel.uiState.value.balanceRows.isNotEmpty() || viewModel.uiState.value.errorMessage != null
            }
            assertNull("Live recognition should succeed: ${viewModel.uiState.value.errorMessage}", viewModel.uiState.value.errorMessage)
            val row = viewModel.uiState.value.balanceRows.single()
            assertEquals(expectedBalance, row.balance.toDouble(), 0.0)
            compose.runOnIdle { viewModel.updateBalanceRow(row.key, row.copy(fundSourceId = sourceId)) }
            compose.waitUntil(10_000) { viewModel.uiState.value.balanceRows.single().fundSourceId == sourceId }
            compose.runOnIdle { viewModel.saveBalanceChange() }
            compose.waitUntil(10_000) { viewModel.uiState.value.balanceRows.isEmpty() }
            val balance = runBlocking(Dispatchers.IO) { database.balanceChangeDao().getByFundSource(sourceId).first().single().newBalance }
            assertEquals(expectedBalance, balance, 0.0)
        } finally {
            runBlocking(Dispatchers.IO) {
                image.delete()
                database.balanceChangeDao().getByFundSource(sourceId).first().forEach { database.balanceChangeDao().delete(it) }
                database.fundSourceDao().getById(sourceId)?.let { database.fundSourceDao().delete(it) }
                val original = previous.connectionFor(RecognitionBackend.OpenRouter)
                settings.clearConnection(RecognitionBackend.OpenRouter)
                if (original.apiKey.isNotBlank()) settings.save(RecognitionBackend.OpenRouter, original)
                settings.save(previous.backend, previous.connectionFor())
            }
        }
    }

    private fun createSyntheticScreenshot(file: File) {
        val bitmap = Bitmap.createBitmap(900, 600, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 48f }
            canvas.drawText("Account balance", 60f, 160f, paint)
            paint.textSize = 88f
            canvas.drawText("-245.70", 60f, 300f, paint)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
    }
}
