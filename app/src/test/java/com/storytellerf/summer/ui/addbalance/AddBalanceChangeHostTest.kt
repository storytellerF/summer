package com.storytellerf.summer.ui.addbalance

import com.storytellerf.summer.data.db.entity.FundSource
import com.storytellerf.summer.data.db.entity.BalanceChange
import com.storytellerf.summer.data.recognition.ImageCreationTimeReader
import com.storytellerf.summer.data.recognition.formatLocalDateTime
import com.storytellerf.summer.data.recognition.parseLocalDateTime
import com.storytellerf.summer.data.recognition.FinanceImageAnalyzer
import com.storytellerf.summer.data.llmd.LlmdAuthorizationException
import com.storytellerf.summer.data.llmd.LlmdTarget
import com.storytellerf.summer.testing.FakeDataRepository
import com.storytellerf.summer.testing.createHostTestEnvironment
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddBalanceChangeHostTest {
    @Test fun batchShowsLlmdFailureWithoutLeakingServiceDetails_andKeepsSuccessfulRows() = runTest {
        val env = createHostTestEnvironment()
        val source = FundSource(id = 1, name = "Wallet")
        val analyzer = object : FinanceImageAnalyzer {
            override suspend fun extractBalancesFromImage(imageReference: String,
                targets: List<com.storytellerf.summer.data.recognition.BalanceReadTarget>, target: LlmdTarget): Result<com.storytellerf.summer.data.recognition.RecognizedBalances> =
                if (imageReference == "failed") Result.failure(com.storytellerf.summer.data.llmd.llmdRecognitionError(
                    "llmd_error", "Model file does not exist: /private/model/path"))
                else Result.success(com.storytellerf.summer.data.recognition.RecognizedBalances(
                    listOf(com.storytellerf.summer.data.recognition.RecognizedAccountBalance(1, 10.0, null)), "saved.jpg"))
        }
        val host = AddBalanceChangeHost(FakeDataRepository(fundSources = listOf(source)), analyzer, env.scope, env.dispatchers)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { host.uiState.collect() }
        try {
            host.toggleImageTarget(source)
            host.extractBalancesFromImages(listOf("failed", "successful")); advanceUntilIdle()
            assertEquals(1, host.uiState.value.balanceRows.size)
            assertTrue(host.uiState.value.errorMessage!!.contains("LLMD has no usable model"))
            assertFalse(host.uiState.value.errorMessage!!.contains("/private/"))
            assertFalse(host.uiState.value.isImageAnalyzing)
        } finally { host.close(); env.close() }
    }

    @Test fun screenshotModeCannotSaveAStaleManualDraft_andSwitchingBackPreservesManualInput() = runTest {
        val env = createHostTestEnvironment()
        val source = FundSource(id = 1, name = "Wallet")
        val repo = FakeDataRepository(fundSources = listOf(source))
        val host = AddBalanceChangeHost(repo, FakeImageAnalyzer(env.ioDispatcher, Result.success(90.0)), env.scope, env.dispatchers)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { host.uiState.collect() }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { host.effects.collect() }
        try {
            host.selectFundSource(source); host.updateBalance("25")
            host.selectEntryMode(BalanceEntryMode.Screenshots); advanceUntilIdle()
            assertEquals(BalanceEntryMode.Screenshots, host.uiState.value.entryMode)
            assertEquals(listOf(source.id), host.uiState.value.imageTargets.map { it.fundSourceId })
            host.saveBalanceChange(); advanceUntilIdle()
            assertTrue(repo.insertedBalanceChanges.isEmpty())
            host.selectEntryMode(BalanceEntryMode.Manual); advanceUntilIdle()
            assertEquals("25", host.uiState.value.balance)
            host.saveBalanceChange(); advanceUntilIdle()
            assertEquals(25.0, repo.insertedBalanceChanges.single().newBalance, 0.0)
        } finally { host.close(); env.close() }
    }

    @Test fun multiImagePreviewRequiresTargetsAndAssignment_preservesEachImageTimeAndPath() = runTest {
        val env = createHostTestEnvironment()
        val wallet = FundSource(id = 1, name = "Wallet")
        val bank = FundSource(id = 2, name = "Bank")
        val repo = FakeDataRepository(fundSources = listOf(wallet, bank))
        val calls = mutableListOf<String>()
        val analyzer = object : FinanceImageAnalyzer {
            override suspend fun extractBalancesFromImage(imageReference: String,
                targets: List<com.storytellerf.summer.data.recognition.BalanceReadTarget>, target: LlmdTarget): Result<com.storytellerf.summer.data.recognition.RecognizedBalances> {
                assertEquals(env.ioDispatcher, currentCoroutineContext()[ContinuationInterceptor])
                assertEquals(listOf(1L, 2L), targets.map { it.fundSourceId })
                assertEquals("Available cash", targets.first().balanceToRead)
                calls += imageReference
                val rows = if (imageReference == "first") listOf(
                    com.storytellerf.summer.data.recognition.RecognizedAccountBalance(1, 100.0, "Cash"),
                    com.storytellerf.summer.data.recognition.RecognizedAccountBalance(null, 200.0, "Unknown"))
                    else listOf(com.storytellerf.summer.data.recognition.RecognizedAccountBalance(2, 300.0, "Bank"))
                return Result.success(com.storytellerf.summer.data.recognition.RecognizedBalances(rows, "$imageReference.jpg"))
            }
        }
        val host = AddBalanceChangeHost(repo, analyzer, env.scope, env.dispatchers,
            imageCreationTimeReader = ImageCreationTimeReader { if (it == "first") 1700000000123L else 1700000300456L })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { host.uiState.collect() }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { host.effects.collect() }
        try {
            host.extractBalancesFromImages(listOf("first")); advanceUntilIdle()
            assertTrue(calls.isEmpty())
            host.toggleImageTarget(wallet); host.toggleImageTarget(bank)
            host.updateBalanceToRead(1, "Available cash")
            host.extractBalancesFromImages(listOf("first", "second")); advanceUntilIdle()
            assertEquals(listOf("first", "second"), calls)
            val rows = host.uiState.value.balanceRows
            assertEquals(3, rows.size)
            assertEquals(listOf(1700000000123L, 1700000000123L, 1700000300456L), rows.map { it.timestamp })
            assertEquals(listOf("first.jpg", "first.jpg", "second.jpg"), rows.map { it.imagePath })
            host.saveBalanceChange(); advanceUntilIdle()
            assertTrue(repo.insertedBalanceChanges.isEmpty())
            host.updateBalanceRow(rows[1].key, rows[1].copy(fundSourceId = 2, balance = "210", note = "Assigned"))
            host.updateBalanceRow(rows[2].key, rows[2].copy(selected = false))
            host.saveBalanceChange(); host.saveBalanceChange(); advanceUntilIdle()
            assertEquals(2, repo.insertedBalanceChanges.size)
            assertEquals(210.0, repo.insertedBalanceChanges.last().newBalance, 0.0)
            assertEquals("Assigned", repo.insertedBalanceChanges.last().note)
            assertEquals("first.jpg", repo.insertedBalanceChanges.last().imagePath)
        } finally { host.close(); env.close() }
    }

    @Test fun authorizationResumesCurrentImageWithoutDuplicatingCompletedRows_orReusingItsTime() = runTest {
        val env = createHostTestEnvironment()
        val source = FundSource(id = 1, name = "Wallet")
        val calls = mutableListOf<String>()
        var needsAuthorization = true
        val analyzer = object : FinanceImageAnalyzer {
            override suspend fun extractBalancesFromImage(imageReference: String,
                targets: List<com.storytellerf.summer.data.recognition.BalanceReadTarget>, target: LlmdTarget): Result<com.storytellerf.summer.data.recognition.RecognizedBalances> {
                calls += imageReference
                if (imageReference == "b" && needsAuthorization) { needsAuthorization = false; return Result.failure(LlmdAuthorizationException()) }
                if (imageReference == "bad") return Result.failure(IllegalStateException("private payload"))
                return Result.success(com.storytellerf.summer.data.recognition.RecognizedBalances(
                    listOf(com.storytellerf.summer.data.recognition.RecognizedAccountBalance(1, 10.0, null)), "$imageReference.jpg"))
            }
        }
        val host = AddBalanceChangeHost(FakeDataRepository(fundSources = listOf(source)), analyzer, env.scope, env.dispatchers,
            imageCreationTimeReader = ImageCreationTimeReader { when(it) { "a" -> 100001L; "b" -> 200002L; else -> 300003L } })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { host.uiState.collect() }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { host.effects.collect() }
        try {
            host.toggleImageTarget(source)
            host.extractBalancesFromImages(listOf("a", "b", "c", "bad")); advanceUntilIdle()
            assertEquals(1, host.uiState.value.balanceRows.size)
            host.onAuthorizationResult(true); advanceUntilIdle()
            assertEquals(listOf("a", "b", "b", "c", "bad"), calls)
            assertEquals(listOf(100001L, 200002L, 300003L), host.uiState.value.balanceRows.map { it.timestamp })
            assertTrue(host.uiState.value.errorMessage!!.contains("Image 4"))
            assertFalse(host.uiState.value.errorMessage!!.contains("private payload"))
        } finally { host.close(); env.close() }
    }

    @Test fun defaultTimeUsesClock_andEditedTimeIsSavedWithHistoricalPreviousBalance() = runTest {
        val env = createHostTestEnvironment()
        val source = FundSource(id = 1, name = "Wallet")
        val old = requireNotNull(parseLocalDateTime("2020-01-01T00:00:00"))
        val chosen = requireNotNull(parseLocalDateTime("2020-02-01T00:00:00"))
        val repo = FakeDataRepository(fundSources = listOf(source), balanceChanges = listOf(
            BalanceChange(id = 2, fundSourceId = 1, newBalance = 999.0, timestamp = chosen + 1000),
            BalanceChange(id = 1, fundSourceId = 1, newBalance = 100.0, timestamp = old)))
        val host = AddBalanceChangeHost(repo, FakeImageAnalyzer(env.ioDispatcher, Result.success(90.0)),
            env.scope, env.dispatchers, now = { chosen + 123 })
        try {
            assertEquals(chosen + 123, host.uiState.value.timestamp)
            assertEquals(formatLocalDateTime(chosen + 123), host.uiState.value.dateTime)
            host.selectFundSource(source); host.updateBalance("90")
            host.updateDateTime("invalid")
            host.saveBalanceChange(); advanceUntilIdle()
            assertTrue(repo.insertedBalanceChanges.isEmpty())
            host.updateDateTime("2020-02-01T00:00:00")
            val effect = async { host.effects.first() }
            host.saveBalanceChange(); advanceUntilIdle(); effect.await()
            assertEquals(chosen, repo.insertedBalanceChanges.single().timestamp)
            assertEquals(100.0, repo.insertedBalanceChanges.single().previousBalance!!, 0.0)
        } finally { host.close(); env.close() }
    }

    @Test fun closingHostCancelsBatchRecognition_andRetainsImageTimesFromMetadataOrClock() = runTest {
        val env = createHostTestEnvironment()
        val source = FundSource(id = 1, name = "Wallet")
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val analyzer = object : FinanceImageAnalyzer {
            override suspend fun extractBalancesFromImage(imageReference: String,
                targets: List<com.storytellerf.summer.data.recognition.BalanceReadTarget>, target: LlmdTarget): Result<com.storytellerf.summer.data.recognition.RecognizedBalances> {
                if (imageReference == "pending") {
                    started.complete(Unit)
                    try { awaitCancellation() } finally { cancelled.complete(Unit) }
                }
                return Result.success(com.storytellerf.summer.data.recognition.RecognizedBalances(
                    listOf(com.storytellerf.summer.data.recognition.RecognizedAccountBalance(1, 10.0, null)), "saved.jpg"))
            }
        }
        val host = AddBalanceChangeHost(FakeDataRepository(fundSources = listOf(source)), analyzer, env.scope, env.dispatchers,
            imageCreationTimeReader = ImageCreationTimeReader { image ->
                assertEquals(env.ioDispatcher, currentCoroutineContext()[ContinuationInterceptor])
                if (image == "dated") 1700000000123L else null
            }, now = { 1800000000000L })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { host.uiState.collect() }
        try {
            host.toggleImageTarget(source)
            host.extractBalancesFromImages(listOf("dated", "undated", "pending")); advanceUntilIdle()
            assertEquals(listOf(1700000000123L, 1800000000000L), host.uiState.value.balanceRows.map { it.timestamp })
            assertTrue(started.isCompleted)
            host.close(); advanceUntilIdle()
            assertTrue(cancelled.isCompleted)
        } finally { host.close(); env.close() }
    }
}

private class FakeImageAnalyzer(
    private val expectedDispatcher: CoroutineDispatcher,
    private val result: Result<Double>,
) : FinanceImageAnalyzer {
    override suspend fun extractBalancesFromImage(imageReference: String,
        targets: List<com.storytellerf.summer.data.recognition.BalanceReadTarget>, target: LlmdTarget) = result.map {
        com.storytellerf.summer.data.recognition.RecognizedBalances(
            listOf(com.storytellerf.summer.data.recognition.RecognizedAccountBalance(targets.single().fundSourceId, it, null)), null)
    }
}
