package com.storytellerf.summer.ui.recognition

import com.storytellerf.summer.data.llmd.*
import com.storytellerf.summer.testing.createHostTestEnvironment
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.coroutines.ContinuationInterceptor
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LlmdStatusHostTest {
    @Test fun variantsStayIndependent_errorsDoNotBlockOthers_andActionsUseTheirOwnTarget() = runTest {
        val environment = createHostTestEnvironment()
        val visited = mutableListOf<LlmdTarget>()
        val available = LlmdStatus.Available(LlmdServiceStatus.Off, null, null, listOf("actual-a", "actual-b"))
        val host = LlmdStatusHost(LlmdStatusReader { target ->
            assertSame(environment.ioDispatcher, currentCoroutineContext()[ContinuationInterceptor])
            visited.add(target)
            when (target) {
                LlmdTarget.Release -> LlmdStatus.AuthorizationRequired
                LlmdTarget.Alpha -> error("unreachable")
                LlmdTarget.Debug -> available
            }
        }, environment.scope, environment.dispatchers)
        val effects = mutableListOf<LlmdStatusEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { host.effects.collect { effects.add(it) } }
        host.refresh(); advanceUntilIdle()
        assertEquals(LlmdTarget.entries, visited)
        assertEquals(LlmdStatus.AuthorizationRequired, host.uiState.value.statuses[LlmdTarget.Release])
        assertTrue(host.uiState.value.statuses[LlmdTarget.Alpha] is LlmdStatus.Unavailable)
        assertFalse((host.uiState.value.statuses.getValue(LlmdTarget.Alpha) as LlmdStatus.Unavailable).installationKnown)
        assertEquals(available, host.uiState.value.statuses[LlmdTarget.Debug])
        assertFalse(host.uiState.value.refreshing)
        host.authorize(LlmdTarget.Debug)
        host.authorize(LlmdTarget.Release)
        host.open(LlmdTarget.Alpha)
        advanceUntilIdle()
        assertEquals(listOf(LlmdStatusEffect.Authorize(LlmdTarget.Release), LlmdStatusEffect.Open(LlmdTarget.Alpha)), effects)
        host.close(); environment.close()
    }

    @Test fun stopCancelsLateReads_andResumeRefreshesAuthorizationAndServiceChanges() = runTest {
        val environment = createHostTestEnvironment()
        val late = CompletableDeferred<Unit>()
        var first = true
        var reads = 0
        val host = LlmdStatusHost(LlmdStatusReader {
            reads++
            if (first) {
                first = false
                withContext(NonCancellable) { late.await() }
                LlmdStatus.AuthorizationRequired
            } else LlmdStatus.Available(LlmdServiceStatus.Ready, "explicit-model", "explicit-model", listOf("explicit-model"))
        }, environment.scope, environment.dispatchers)
        host.refresh(); runCurrent()
        host.stop(); runCurrent()
        late.complete(Unit); runCurrent()
        assertEquals(LlmdStatus.Checking, host.uiState.value.statuses[LlmdTarget.Release])
        assertFalse(host.uiState.value.refreshing)
        host.start(); runCurrent()
        assertEquals("explicit-model", (host.uiState.value.statuses.getValue(LlmdTarget.Release) as LlmdStatus.Available).activeModel)
        val before = reads
        advanceTimeBy(5_000); runCurrent()
        assertEquals(before + 3, reads)
        host.stop(); runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(before + 3, reads)
        host.close(); environment.close()
    }

    @Test fun statusParsingDoesNotPickAnImportedModel_whenNoModelIsRunning() {
        val inventory = """{"object":"list","data":[{"id":"actual-a"},{"id":"actual-b"}]}"""
        val off = parseLlmdStatus("""{"serviceState":"off","activeModel":null,"selectedModel":null}""", inventory) as LlmdStatus.Available
        assertEquals(LlmdServiceStatus.Off, off.service)
        assertNull(off.activeModel)
        assertEquals(listOf("actual-a", "actual-b"), off.models)
        val missing = parseLlmdStatus("""{"serviceState":"ready"}""", inventory) as LlmdStatus.Available
        assertNull(missing.activeModel)
        val denied = """{"error":{"type":"authorization_required"}}"""
        assertEquals(LlmdStatus.AuthorizationRequired, parseLlmdStatus(denied, inventory))
        assertEquals(LlmdStatus.AuthorizationRequired, parseLlmdStatus("""{"serviceState":"ready"}""", denied))
    }
}
