package com.storytellerf.summer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import com.storytellerf.summer.theme.SummerAppTheme
import androidx.test.platform.app.InstrumentationRegistry
import com.storytellerf.summer.data.DefaultDataRepository
import com.storytellerf.summer.data.db.SummerDatabase
import com.storytellerf.summer.data.db.entity.FundSource
import com.storytellerf.summer.data.llmd.LlmdTarget
import com.storytellerf.summer.data.recognition.*
import com.storytellerf.summer.ui.importtransactions.ImportTransactionsScreen
import com.storytellerf.summer.ui.importtransactions.ImportTransactionsViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ImportReviewJourneyTest {
    @get:Rule val compose = createComposeRule()

    @Test fun missingDateIsCorrectedInDetails_andExcludedRowsAreNotImported() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, SummerDatabase::class.java).build()
        val store = ViewModelStore()
        try {
            val repo = DefaultDataRepository(database)
            val source = FundSource(id = repo.insertFundSource(FundSource(name = "Daily Wallet")), name = "Daily Wallet")
            val date = requireNotNull(parseLocalDateTime("2026-10-07T09:30:00"))
            val analyzer = object : FinanceImageAnalyzer {
                override suspend fun extractBalanceFromImage(imageReference: String, target: LlmdTarget) = Result.success(0.0)
                override suspend fun extractTransactionsFromImage(imageReference: String, target: LlmdTarget) = Result.success(
                    RecognizedTransactions("review-fixture", listOf(
                        RecognizedTransaction(date, -12.5, "Coffee", "ORDER-001"),
                        RecognizedTransaction(null, 50.0, "Cashback", "ORDER-002"),
                        RecognizedTransaction(date, -9.0, "Overlap", null)), "recognition-images/fixture.jpg"))
            }
            val model = ImportTransactionsViewModel(repo, analyzer, flowOf(LlmdTarget.Release))
            store.put("review", model)
            val saved = CompletableDeferred<Unit>()
            compose.setContent { SummerAppTheme { ImportTransactionsScreen(onBack = { saved.complete(Unit) }, viewModel = model) } }
            compose.waitUntil(10_000) { model.uiState.value.fundSources.isNotEmpty() }
            compose.onNodeWithText("Daily Wallet").performClick()
            compose.waitUntil(10_000) { model.uiState.value.fundSourceId == source.id }
            saveDesignScreenshot(compose, "orders-choose")
            compose.runOnIdle { model.recognize("fixture") }
            compose.waitUntil(10_000) { model.uiState.value.rows.size == 3 && !model.uiState.value.isAnalyzing }
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasText("Import selected transactions") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            saveDesignScreenshot(compose, "orders-review")
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Leave without saving?").assertIsDisplayed()
            compose.onNodeWithText("Keep reviewing").performClick()
            assertFalse(saved.isCompleted)
            assertEquals(3, model.uiState.value.rows.size)
            compose.onNodeWithText("Import selected transactions").performClick()
            compose.waitUntil(10_000) { model.uiState.value.error != null }
            assertTrue(database.balanceImpactRecordDao().getInRange(null, null).isEmpty())
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("Edit Cashback"))
            compose.onNodeWithContentDescription("Edit Cashback").performClick()
            compose.onNodeWithText("Local date and time").performScrollTo().performTextReplacement("2026-10-07T10:00:00")
            compose.onNodeWithText("Done").performScrollTo().performClick()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("Include transaction 3"))
            compose.onNodeWithContentDescription("Include transaction 3").performClick()
            compose.waitUntil(10_000) { !model.uiState.value.rows[2].selected }
            compose.onNodeWithText("How duplicates are checked").performScrollTo().performClick()
            compose.onNodeWithText("Original order IDs detect duplicates across images. Without an ID, only repeated rows from the same image are detected. Check overlapping screenshots yourself.")
                .assertIsDisplayed()
            saveDesignScreenshot(compose, "orders-reviewed")
            compose.onNodeWithText("Import selected transactions").performClick()
            compose.waitUntil(10_000) { saved.isCompleted }
            val records = database.balanceImpactRecordDao().getInRange(null, null)
            assertEquals(setOf("ORDER-001", "ORDER-002"), records.map { it.transactionId }.toSet())
            assertEquals(parseLocalDateTime("2026-10-07T10:00:00"), records.first { it.transactionId == "ORDER-002" }.timestamp)
            assertEquals(50.0, records.first { it.transactionId == "ORDER-002" }.amount, 0.0)
        } finally { compose.runOnIdle { store.clear() }; database.close() }
    }
}
