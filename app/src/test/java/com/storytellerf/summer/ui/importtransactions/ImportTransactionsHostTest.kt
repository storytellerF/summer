package com.storytellerf.summer.ui.importtransactions

import com.storytellerf.summer.data.db.entity.FundSource
import com.storytellerf.summer.data.llmd.LlmdAuthorizationException
import com.storytellerf.summer.data.llmd.LlmdTarget
import com.storytellerf.summer.data.recognition.*
import com.storytellerf.summer.testing.FakeDataRepository
import com.storytellerf.summer.testing.createHostTestEnvironment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ImportTransactionsHostTest {
    private val source = FundSource(id = 1, name = "Wallet")
    private val recognition = RecognizedTransactions("image-a", listOf(
        RecognizedTransaction(1_790_000_000_000, -12.5, "Shop", "TX-001"),
        RecognizedTransaction(1_790_000_001_000, 25.0, "Income", "TX-002"),
    ), imagePath = "recognition-images/image-a.jpg")

    @Test fun changingTheAccountRequiresDiscardingThePreview_andDiscardKeepsSavedOrdersUntouched() = runTest {
        val env = createHostTestEnvironment()
        val other = FundSource(id = 2, name = "Bank")
        val repo = FakeDataRepository(fundSources = listOf(source, other))
        val host = ImportTransactionsHost(repo, analyzer { Result.success(recognition) }, env.scope, env.dispatchers)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { host.uiState.collect() }
        try {
            host.selectFundSource(source); host.recognize("image"); advanceUntilIdle()
            host.selectFundSource(other); advanceUntilIdle()
            assertEquals(source.id, host.uiState.value.fundSourceId)
            assertEquals(2, host.uiState.value.rows.size)
            host.clearPreview(); host.selectFundSource(other); advanceUntilIdle()
            assertEquals(other.id, host.uiState.value.fundSourceId)
            assertTrue(host.uiState.value.rows.isEmpty())
            assertNull(host.uiState.value.imageHash)
            assertNull(host.uiState.value.imagePath)
            assertTrue(repo.importedTransactions.value.isEmpty())
            host.save(); advanceUntilIdle()
            assertTrue(repo.importedTransactions.value.isEmpty())
        } finally { host.close(); env.close() }
    }

    @Test fun previewIsRequired_editsAndSelectionAreSaved_onceDespiteRapidTaps() = runTest {
        val env = createHostTestEnvironment()
        val repo = FakeDataRepository(fundSources = listOf(source), expectedIoDispatcher = env.ioDispatcher)
        val host = ImportTransactionsHost(repo, analyzer { Result.success(recognition) }, env.scope, env.dispatchers)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { host.uiState.collect() }
        try {
            host.selectFundSource(source); host.recognize("image")
            advanceUntilIdle()
            assertTrue(repo.importedTransactions.value.isEmpty())
            host.updateRow(0, host.uiState.value.rows[0].copy(amount = "-15.5"))
            host.updateRow(1, host.uiState.value.rows[1].copy(selected = false))
            advanceUntilIdle()
            val saved = async { host.effects.first() }
            host.save(); host.save()
            advanceUntilIdle()
            assertEquals(1, (saved.await() as ImportTransactionsEffect.Saved).count)
            assertEquals(-15.5, repo.importedTransactions.value.single().amount, 0.0)
            assertEquals("TX-001", repo.importedTransactions.value.single().transactionId)
            assertEquals(recognition.imagePath, repo.importedTransactions.value.single().imagePath)
            host.recognize("different-screenshot")
            advanceUntilIdle()
            host.updateRow(1, host.uiState.value.rows[1].copy(selected = false))
            advanceUntilIdle()
            host.save()
            advanceUntilIdle()
            assertEquals(1, repo.importedTransactions.value.size)
            assertTrue(host.uiState.value.error!!.contains("already"))
        } finally { host.close(); env.close() }
    }

    @Test fun invalidDatePreventsTheWholeBatch_andAuthorizationRetriesPendingImage() = runTest {
        val env = createHostTestEnvironment()
        val repo = FakeDataRepository(fundSources = listOf(source))
        var calls = 0
        val host = ImportTransactionsHost(repo, analyzer {
            calls++
            if (calls == 1) Result.failure(LlmdAuthorizationException()) else Result.success(recognition)
        }, env.scope, env.dispatchers)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { host.uiState.collect() }
        try {
            val effect = async { host.effects.first() }
            host.selectFundSource(source); host.recognize("image")
            advanceUntilIdle()
            assertTrue(effect.await() is ImportTransactionsEffect.Authorize)
            host.onAuthorizationResult(true)
            advanceUntilIdle()
            host.updateRow(0, host.uiState.value.rows[0].copy(date = "2026-02-30T00:00:00"))
            advanceUntilIdle()
            host.save()
            advanceUntilIdle()
            assertTrue(repo.importedTransactions.value.isEmpty())
            assertFalse(host.uiState.value.isSaving)
            assertEquals(2, calls)
        } finally { host.close(); env.close() }
    }

    @Test fun closingHost_cancelsRecognitionAndClosesAnalyzer() = runTest {
        val env = createHostTestEnvironment()
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        var closed = false
        val analyzer = object : FinanceImageAnalyzer {
            override suspend fun extractBalanceFromImage(imageReference: String, target: LlmdTarget) = Result.success(0.0)
            override suspend fun extractTransactionsFromImage(imageReference: String, target: LlmdTarget): Result<RecognizedTransactions> {
                started.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
            override fun close() { closed = true }
        }
        val host = ImportTransactionsHost(FakeDataRepository(), analyzer, env.scope, env.dispatchers)
        host.recognize("image")
        advanceUntilIdle()
        assertTrue(started.isCompleted)
        host.close(); advanceUntilIdle()
        assertTrue(cancelled.isCompleted)
        assertTrue(closed)
        env.close()
    }

    private fun analyzer(result: suspend () -> Result<RecognizedTransactions>) = object : FinanceImageAnalyzer {
        override suspend fun extractBalanceFromImage(imageReference: String, target: LlmdTarget) = Result.success(0.0)
        override suspend fun extractTransactionsFromImage(imageReference: String, target: LlmdTarget) = result()
    }
}
