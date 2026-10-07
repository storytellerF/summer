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
            override suspend fun extractBalanceFromImage(imageReference: String, target: LlmdTarget) = Result.success(0.0)
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
            override suspend fun extractBalanceFromImage(imageReference: String, target: LlmdTarget) = Result.success(0.0)
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

    @Test fun screenshotCreationTimeIsReadOnIo_andPreservedToTheMillisecond() = runTest {
        val env = createHostTestEnvironment()
        val source = FundSource(id = 1, name = "Wallet")
        val repo = FakeDataRepository(fundSources = listOf(source))
        val host = AddBalanceChangeHost(repo, FakeImageAnalyzer(env.ioDispatcher, Result.success(90.0)),
            env.scope, env.dispatchers, imageCreationTimeReader = ImageCreationTimeReader {
                assertEquals(env.ioDispatcher, currentCoroutineContext()[ContinuationInterceptor])
                1700000000123L
            }, now = { 1800000000000L })
        try {
            host.selectFundSource(source); host.extractBalanceFromImage("image"); advanceUntilIdle()
            val effect = async { host.effects.first() }
            host.saveBalanceChange(); advanceUntilIdle(); effect.await()
            assertEquals(1700000000123L, repo.insertedBalanceChanges.single().timestamp)
        } finally { host.close(); env.close() }
    }

    @Test fun absentCreationMetadataDefaultsToCurrentTime() = runTest {
        val env = createHostTestEnvironment()
        val host = AddBalanceChangeHost(FakeDataRepository(), FakeImageAnalyzer(env.ioDispatcher, Result.success(90.0)),
            env.scope, env.dispatchers, now = { 1700000000000L })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { host.uiState.collect() }
        try {
            host.extractBalanceFromImage("image"); advanceUntilIdle()
            assertEquals(1700000000000L, host.uiState.value.timestamp)
        } finally { host.close(); env.close() }
    }

    @Test fun delayedImageMetadataDoesNotOverwriteManualTime_orAuthorizationRetry() = runTest {
        val env = createHostTestEnvironment()
        val metadata = CompletableDeferred<Long?>()
        val host = AddBalanceChangeHost(FakeDataRepository(), FakeImageAnalyzer(env.ioDispatcher, Result.failure(LlmdAuthorizationException())),
            env.scope, env.dispatchers, imageCreationTimeReader = ImageCreationTimeReader { metadata.await() })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { host.uiState.collect() }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { host.effects.collect() }
        try {
            host.extractBalanceFromImage("image")
            host.updateDateTime("2020-02-01T00:00:00")
            advanceUntilIdle()
            metadata.complete(1700000000123L); advanceUntilIdle()
            assertEquals("2020-02-01T00:00:00", host.uiState.value.dateTime)
            host.onAuthorizationResult(true); advanceUntilIdle()
            assertEquals("2020-02-01T00:00:00", host.uiState.value.dateTime)
        } finally { host.close(); env.close() }
    }

    @Test
    fun recognizedBalance_savesItsRetainedImagePath() = runTest {
        val env = createHostTestEnvironment()
        val source = FundSource(id = 1, name = "Wallet")
        val repo = FakeDataRepository(fundSources = listOf(source))
        val analyzer = object : FinanceImageAnalyzer {
            override suspend fun extractBalanceFromImage(imageReference: String, target: LlmdTarget) = Result.success(12.5)
            override suspend fun extractBalanceWithImage(imageReference: String, target: LlmdTarget) =
                Result.success(com.storytellerf.summer.data.recognition.RecognizedBalance(12.5, "recognition-images/balance.jpg"))
        }
        val host = AddBalanceChangeHost(repo, analyzer, env.scope, env.dispatchers)
        try {
            host.selectFundSource(source)
            host.extractBalanceFromImage("image")
            advanceUntilIdle()
            val saved = async { host.effects.first() }
            host.saveBalanceChange()
            advanceUntilIdle()
            assertEquals(AddBalanceChangeEffect.Saved, saved.await())
            assertEquals("recognition-images/balance.jpg", repo.insertedBalanceChanges.single().imagePath)
        } finally { host.close(); env.close() }
    }

    @Test
    fun saveImmediatelyAfterImport_doesNotSaveThePreviousBalance() = runTest {
        val environment = createHostTestEnvironment()
        val source = FundSource(id = 1, name = "Wallet")
        val repository = FakeDataRepository(fundSources = listOf(source))
        val analyzer = object : FinanceImageAnalyzer {
            override suspend fun extractBalanceFromImage(imageReference: String, target: LlmdTarget): Result<Double> =
                awaitCancellation()
        }
        val host = AddBalanceChangeHost(repository, analyzer, environment.scope, environment.dispatchers)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { host.uiState.collect() }
        host.selectFundSource(source)
        host.updateBalance("10.00")
        advanceUntilIdle()

        host.extractBalanceFromImage("content://test/pending")
        host.saveBalanceChange()
        advanceUntilIdle()
        assertTrue(host.uiState.value.isImageAnalyzing)
        assertTrue(repository.insertedBalanceChanges.isEmpty())
        host.close()
        environment.close()
    }

    @Test
    fun successfulAnalysisAndSave_useInjectedDispatchersAndPublishEffect() = runTest {
        val environment = createHostTestEnvironment()
        val fundSource = FundSource(id = 1, name = "Wallet")
        val repository = FakeDataRepository(
            fundSources = listOf(fundSource),
            expectedDefaultDispatcher = environment.defaultDispatcher,
            expectedIoDispatcher = environment.ioDispatcher,
        )
        val analyzer = FakeImageAnalyzer(
            expectedDispatcher = environment.ioDispatcher,
            result = Result.success(380.0),
        )
        val host = AddBalanceChangeHost(
            repository = repository,
            imageAnalyzer = analyzer,
            scope = environment.scope,
            dispatchers = environment.dispatchers,
            imageAnalysisTarget = flowOf(LlmdTarget.Alpha),
        )
        val effects = mutableListOf<AddBalanceChangeEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            host.uiState.collect()
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            host.effects.collect(effects::add)
        }

        advanceUntilIdle()
        assertEquals(listOf(fundSource), host.uiState.value.fundSources)

        host.extractBalanceFromImage("content://test/balance")
        advanceUntilIdle()
        assertEquals("380.00", host.uiState.value.balance)
        assertEquals("content://test/balance", analyzer.lastImageReference)
        assertEquals(LlmdTarget.Alpha, analyzer.lastTarget)

        host.selectFundSource(fundSource)
        host.updateNote("Groceries")
        host.saveBalanceChange()
        advanceUntilIdle()
        environment.close()

        assertEquals(380.0, repository.insertedBalanceChanges.single().newBalance, 0.0)
        assertEquals("Groceries", repository.insertedBalanceChanges.single().note)
        assertTrue(AddBalanceChangeEffect.Saved in effects)
        assertEquals("", host.uiState.value.balance)
        assertFalse(host.uiState.value.isSaving)
        host.close()
        assertTrue(analyzer.closed)
    }

    @Test
    fun authorizationFailure_isPublishedAsOneTimeEffect() = runTest {
        val environment = createHostTestEnvironment()
        val analyzer = FakeImageAnalyzer(
            expectedDispatcher = environment.ioDispatcher,
            result = Result.failure(LlmdAuthorizationException()),
        )
        val host = AddBalanceChangeHost(
            repository = FakeDataRepository(
                expectedDefaultDispatcher = environment.defaultDispatcher,
                expectedIoDispatcher = environment.ioDispatcher,
            ),
            imageAnalyzer = analyzer,
            scope = environment.scope,
            dispatchers = environment.dispatchers,
            imageAnalysisTarget = flowOf(LlmdTarget.Debug),
        )
        val effects = mutableListOf<AddBalanceChangeEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            host.effects.collect(effects::add)
        }

        host.extractBalanceFromImage("content://test/protected")
        advanceUntilIdle()
        environment.close()

        assertEquals(
            listOf(AddBalanceChangeEffect.RequestAuthorization(LlmdTarget.Debug)),
            effects,
        )
        assertFalse(host.uiState.value.isImageAnalyzing)
    }
}

private class FakeImageAnalyzer(
    private val expectedDispatcher: CoroutineDispatcher,
    private val result: Result<Double>,
) : FinanceImageAnalyzer {
    var lastImageReference: String? = null
    var lastTarget: LlmdTarget? = null
    var closed = false

    override suspend fun extractBalanceFromImage(
        imageReference: String,
        target: LlmdTarget,
    ): Result<Double> {
        check(currentCoroutineContext()[ContinuationInterceptor] === expectedDispatcher)
        lastImageReference = imageReference
        lastTarget = target
        return result
    }

    override fun close() {
        closed = true
    }
}
