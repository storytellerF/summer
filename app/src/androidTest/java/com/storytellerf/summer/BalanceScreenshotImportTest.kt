package com.storytellerf.summer

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import androidx.core.net.toUri
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import com.storytellerf.summer.theme.SummerAppTheme
import androidx.test.platform.app.InstrumentationRegistry
import com.storytellerf.summer.data.DefaultDataRepository
import com.storytellerf.summer.data.db.SummerDatabase
import com.storytellerf.summer.data.db.entity.FundSource
import com.storytellerf.summer.data.llmd.LlmdTarget
import com.storytellerf.summer.data.recognition.*
import com.storytellerf.summer.ui.addbalance.AddBalanceChangeScreen
import com.storytellerf.summer.ui.addbalance.AddBalanceChangeViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.headersOf
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercises image encoding, real Koog request/response handling, preview and atomic Room save. */
class BalanceScreenshotImportTest {
    @get:Rule val compose = createComposeRule()

    @Test fun multipleImagesAndBalances_previewAssignmentThenSaveSharedImagesAndDistinctTimes() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "balance-batch-fixture").apply { mkdirs() }
        val first = File(root, "first.png")
        val second = File(root, "second.png")
        val bitmap = Bitmap.createBitmap(800, 1200, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        listOf(first, second).forEach { file -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        bitmap.recycle()
        val db = Room.inMemoryDatabaseBuilder(context, SummerDatabase::class.java).build()
        val store = ViewModelStore()
        try {
            val repo = DefaultDataRepository(db)
            val a = FundSource(id = repo.insertFundSource(FundSource(name = "Wallet")), name = "Wallet")
            val b = FundSource(id = repo.insertFundSource(FundSource(name = "Bank")), name = "Bank")
            var requests = 0
            val recognizer = KoogImageRecognizer {
                HttpClient(MockEngine { request ->
                    val body = request.body.toByteArray().decodeToString()
                    assertTrue(body.contains("Available cash"))
                    assertTrue(body.contains("Bank"))
                    assertTrue(body.contains("data:image/jpeg;base64,"))
                    val content = if (requests++ == 0)
                        """{"balances":[{"fundSourceId":${a.id},"balance":100.0,"label":"Cash","currency":null},{"fundSourceId":null,"balance":200.0,"label":"Savings","currency":null}]}"""
                        else """{"balances":[{"fundSourceId":${b.id},"balance":200.0,"label":"Savings","currency":null}]}"""
                    val response = buildJsonObject {
                        put("id", "fixture"); put("object", "chat.completion"); put("created", 1); put("model", "fixture")
                        putJsonArray("choices") { addJsonObject {
                            put("index", 0); put("finish_reason", "stop")
                            putJsonObject("message") { put("role", "assistant"); put("content", content) }
                        } }
                    }
                    respond(response.toString(), headers = headersOf("Content-Type", "application/json"))
                })
            }
            val settings = object : RecognitionSettings {
                override val config = MutableStateFlow(RecognitionConfig(RecognitionBackend.OpenRouter,
                    mapOf(RecognitionBackend.OpenRouter to KoogConnection("https://example.com/v1", "fixture", "test-key"))))
                override suspend fun save(backend: RecognitionBackend, connection: KoogConnection) = Unit
                override suspend fun clearConnection(backend: RecognitionBackend) = Unit
            }
            val encoder = RecognitionImageEncoder(context)
            val analyzer = ConfiguredImageAnalyzer(settings, object : FinanceImageAnalyzer {
            }, { encoder.encode(it.toUri()) }, recognizer, FileRecognitionImageStore(root))
            val model = AddBalanceChangeViewModel(repo, analyzer, imageCreationTimeReader = ImageCreationTimeReader {
                if (it == first.toUri().toString()) 1700000000123L else 1700000300456L
            })
            store.put("balance", model)
            val saved = CompletableDeferred<Unit>()
            compose.setContent { SummerAppTheme { AddBalanceChangeScreen(onBack = { saved.complete(Unit) }, viewModel = model) } }
            compose.runOnIdle {
                model.toggleImageTarget(a); model.toggleImageTarget(b)
                model.updateBalanceToRead(a.id, "Available cash")
                model.extractBalancesFromImages(listOf(first.toUri().toString(), second.toUri().toString()))
            }
            compose.waitUntil(30_000) { !model.uiState.value.isImageAnalyzing && model.uiState.value.balanceRows.size == 3 }
            assertNull(model.uiState.value.errorMessage)
            saveDesignScreenshot(compose, "balances-review")
            compose.onNodeWithText("Save selected balances").performClick()
            compose.waitUntil(10_000) { model.uiState.value.errorMessage != null }
            assertTrue(repo.getAllBalanceChanges().first().isEmpty())
            compose.onNodeWithContentDescription("Edit Unassigned balance 2").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Assign Bank").performClick()
            saveDesignScreenshot(compose, "balance-details")
            compose.onNodeWithText("Note (Optional)").performScrollTo().performTextInput("Assigned savings")
            compose.onNodeWithText("Done").performScrollTo().performClick()
            compose.waitUntil(10_000) { model.uiState.value.balanceRows[1].fundSourceId == b.id }
            compose.onNodeWithText("Save selected balances").performClick()
            compose.waitUntil(10_000) { saved.isCompleted }
            val records = repo.getAllBalanceChanges().first()
            assertEquals(3, records.size)
            assertEquals(listOf(1700000300456L, 1700000000123L, 1700000000123L), records.map { it.timestamp })
            assertEquals(1, db.timelineBalanceGroupDao().count())
            assertEquals(3, repo.loadTimelinePage(0, 1).changes.size)
            assertEquals("Assigned savings", records.single { it.note != null }.note)
            records.forEach { assertTrue(File(root, requireNotNull(it.imagePath)).isFile) }
            assertEquals(records[1].imagePath, records[2].imagePath)
            assertEquals(2, requests)
        } finally { compose.runOnIdle { store.clear() }; db.close(); root.deleteRecursively() }
    }
}
