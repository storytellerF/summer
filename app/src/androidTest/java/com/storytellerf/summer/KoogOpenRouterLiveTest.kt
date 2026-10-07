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
import com.storytellerf.summer.data.recognition.DataStoreRecognitionSettings
import com.storytellerf.summer.data.recognition.KoogConnection
import com.storytellerf.summer.data.recognition.KoogImageRecognizer
import com.storytellerf.summer.data.recognition.RemoteImageRecognizer
import com.storytellerf.summer.data.recognition.RecognitionBackend
import com.storytellerf.summer.data.recognition.configuredImageAnalyzer
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
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in: the runner provisions a key in app-private storage, never as an instrumentation argument. */
class KoogOpenRouterLiveTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun syntheticImage_withMockTransport_isRenderedAndSavedThroughKoog() {
        val recognizer = KoogImageRecognizer {
            HttpClient(MockEngine { request ->
                assertEquals("/api/v1/chat/completions", request.url.encodedPath)
                assertTrue(request.body.toByteArray().decodeToString().contains("data:image/jpeg;base64,"))
                respond("""{"id":"test","model":"test-vision","choices":[{"message":{"role":"assistant","content":"-245.70"},"finish_reason":"stop"}]}""",
                    headers = headersOf("Content-Type", "application/json"))
            })
        }
        exerciseRecognition(KoogConnection("https://api.example.com/api/v1", "test-vision", "instrumentation-placeholder-key"), recognizer)
    }

    @Test fun screenshotBalance_isRecognizedByKoogAndSavedFromTheForm() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val credentialFile = File(context.noBackupFilesDir, "koog-live-test.json")
        assumeTrue("Provision OPENROUTER_API_KEY with scripts/test-openrouter.py to run the live test", credentialFile.isFile)
        val credentials = runBlocking(Dispatchers.IO) { JSONObject(credentialFile.readText()) }
        try {
            runBlocking(Dispatchers.IO) {
                val client = HttpClient(OkHttp)
                try {
                    val response = client.get("https://openrouter.ai/api/v1/key") {
                        header("Authorization", "Bearer ${credentials.getString("apiKey")}")
                    }
                    assertEquals("The API key must authenticate from the emulator", 200, response.status.value)
                } finally {
                    client.close()
                }
            }
            exerciseRecognition(KoogConnection("https://openrouter.ai/api/v1", credentials.getString("model"), credentials.getString("apiKey")),
                KoogImageRecognizer(),
                inputImage = if (credentials.has("image")) File(context.noBackupFilesDir, credentials.getString("image")) else null,
                expectedBalance = credentials.optDouble("expectedBalance", -245.70))
        } finally {
            credentialFile.delete()
        }
    }

    private fun exerciseRecognition(
        connection: KoogConnection,
        recognizer: RemoteImageRecognizer,
        inputImage: File? = null,
        expectedBalance: Double = -245.70,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = DataStoreRecognitionSettings(context)
        val previous = runBlocking(Dispatchers.IO) { settings.config.first() }
        val database = SummerDatabase.getInstance(context)
        val image = inputImage ?: File(context.cacheDir, "synthetic-balance.png")
        val sourceId = runBlocking(Dispatchers.IO) {
            database.fundSourceDao().insert(FundSource(name = "Koog test wallet"))
        }
        try {
            runBlocking(Dispatchers.IO) {
                settings.save(RecognitionBackend.OpenRouter, connection)
                if (inputImage == null) createSyntheticScreenshot(image)
            }
            lateinit var viewModel: AddBalanceChangeViewModel
            compose.runOnUiThread {
                viewModel = ViewModelProvider(compose.activity, AddBalanceChangeViewModel.Factory(
                    DefaultDataRepository(database), configuredImageAnalyzer(context, remote = recognizer), DataStoreLlmdTargetSettings(context).selectedTarget,
                ))[AddBalanceChangeViewModel::class.java]
                compose.activity.setContent {
                    SummerAppTheme { AddBalanceChangeScreen(onBack = {}, viewModel = viewModel) }
                }
            }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Koog test wallet").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Koog test wallet").performScrollTo().performClick()
            // Feed the synthetic image into the same action used by the system image picker.
            viewModel.extractBalanceFromImage(image.toURI().toString())
            compose.waitUntil(120_000) {
                viewModel.uiState.value.balance.isNotBlank() || viewModel.uiState.value.errorMessage != null
            }
            assertNull("Live recognition should succeed: ${viewModel.uiState.value.errorMessage}", viewModel.uiState.value.errorMessage)
            compose.onNodeWithText("New Balance").performScrollTo().assertTextContains(String.format(Locale.ROOT, "%.2f", expectedBalance))
            compose.onNodeWithText("Save Balance Change").performClick()
            compose.waitUntil(10_000) { viewModel.uiState.value.balance.isEmpty() }
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
