package com.storytellerf.summer.ui.fundsources

import com.storytellerf.summer.data.db.entity.FundSource
import com.storytellerf.summer.data.llmd.LlmdTarget
import com.storytellerf.summer.data.llmd.LlmdTargetSettings
import com.storytellerf.summer.testing.FakeDataRepository
import com.storytellerf.summer.testing.createHostTestEnvironment
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FundSourcesHostTest {
    @Test
    fun readsUseDefaultAndCommandsUseIoDispatcher() = runTest {
        val environment = createHostTestEnvironment()
        val wallet = FundSource(id = 1, name = "Wallet")
        val repository = FakeDataRepository(
            fundSources = listOf(wallet),
            expectedDefaultDispatcher = environment.defaultDispatcher,
            expectedIoDispatcher = environment.ioDispatcher,
        )
        val settings = FakeLlmdTargetSettings(
            expectedDefaultDispatcher = environment.defaultDispatcher,
            expectedIoDispatcher = environment.ioDispatcher,
        )
        val host = FundSourcesHost(
            repository = repository,
            settings = settings,
            scope = environment.scope,
            dispatchers = environment.dispatchers,
            currentTimeMillis = { 2_000L },
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            host.uiState.collect()
        }

        advanceUntilIdle()
        val initialState = host.uiState.value
        assertTrue(initialState is FundSourcesUiState.Success)
        assertEquals(
            LlmdTarget.Release,
            (initialState as FundSourcesUiState.Success).selectedLlmdTarget,
        )

        host.addFundSource("Bank", "usd")
        host.updateFundSource(wallet.copy(name = "Cash"))
        host.selectLlmdTarget(LlmdTarget.Alpha)
        advanceUntilIdle()
        environment.close()

        assertEquals("Bank", repository.insertedFundSources.single().name)
        assertEquals("USD", repository.insertedFundSources.single().currency)
        assertEquals(2_000L, repository.updatedFundSources.single().updatedAt)
        assertEquals(LlmdTarget.Alpha, settings.selectedTargetValue)
        val updatedState = host.uiState.value as FundSourcesUiState.Success
        assertEquals(LlmdTarget.Alpha, updatedState.selectedLlmdTarget)
    }
}

private class FakeLlmdTargetSettings(
    private val expectedDefaultDispatcher: CoroutineDispatcher,
    private val expectedIoDispatcher: CoroutineDispatcher,
) : LlmdTargetSettings {
    private val mutableSelectedTarget = MutableStateFlow(LlmdTarget.Release)

    override val defaultTarget: LlmdTarget = LlmdTarget.Release
    override val selectedTarget: Flow<LlmdTarget> = mutableSelectedTarget.onEach {
        check(currentCoroutineContext()[ContinuationInterceptor] === expectedDefaultDispatcher)
    }
    val selectedTargetValue: LlmdTarget get() = mutableSelectedTarget.value

    override suspend fun selectTarget(target: LlmdTarget) {
        check(currentCoroutineContext()[ContinuationInterceptor] === expectedIoDispatcher)
        mutableSelectedTarget.value = target
    }
}
