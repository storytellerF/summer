package com.storytellerf.summer.ui.recognition

import com.storytellerf.summer.data.llmd.*
import com.storytellerf.summer.ui.host.AppDispatchers
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

data class LlmdStatusState(
    val statuses: Map<LlmdTarget, LlmdStatus> = LlmdTarget.entries.associateWith { LlmdStatus.Checking },
    val refreshing: Boolean = false,
)

sealed interface LlmdStatusEffect {
    data class Authorize(val target: LlmdTarget) : LlmdStatusEffect
    data class Open(val target: LlmdTarget) : LlmdStatusEffect
}

class LlmdStatusHost(
    private val reader: LlmdStatusReader,
    parentScope: CoroutineScope,
    private val dispatchers: AppDispatchers,
) : AutoCloseable {
    private val scope = CoroutineScope(parentScope.coroutineContext +
        SupervisorJob(parentScope.coroutineContext[Job]) + dispatchers.coordination)
    private val state = MutableStateFlow(LlmdStatusState())
    val uiState = state.asStateFlow()
    private val events = Channel<LlmdStatusEffect>(Channel.BUFFERED)
    val effects = events.receiveAsFlow()
    private var refreshing: Job? = null
    private var observing: Job? = null

    fun start() = scope.launch {
        if (observing?.isActive == true) return@launch
        observing = scope.launch {
            while (isActive) { refresh(); delay(5_000) }
        }
    }

    fun stop() = scope.launch {
        observing?.cancel()
        refreshing?.cancel()
        while (events.tryReceive().isSuccess) { /* Drop actions belonging to a hidden screen. */ }
        state.value = state.value.copy(refreshing = false)
    }

    fun refresh() = scope.launch {
        if (refreshing?.isActive == true) return@launch
        refreshing = scope.launch {
            state.value = state.value.copy(refreshing = true)
            for (target in LlmdTarget.entries) {
                val status = try { withContext(dispatchers.io) { reader.read(target) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LlmdStatus.Unavailable("Could not read LLMD status. Try refreshing.") }
                state.value = state.value.copy(statuses = state.value.statuses + (target to status))
            }
            state.value = state.value.copy(refreshing = false)
        }
    }

    fun authorize(target: LlmdTarget) = scope.launch {
        if (state.value.statuses[target] == LlmdStatus.AuthorizationRequired) events.send(LlmdStatusEffect.Authorize(target))
    }

    fun open(target: LlmdTarget) = scope.launch {
        if (state.value.statuses[target] !in listOf(LlmdStatus.NotInstalled, LlmdStatus.Checking)) events.send(LlmdStatusEffect.Open(target))
    }

    fun launchFailed(target: LlmdTarget) = scope.launch {
        state.value = state.value.copy(statuses = state.value.statuses +
            (target to LlmdStatus.Unavailable("Could not open LLMD. Try refreshing.")))
    }

    override fun close() { scope.cancel(); events.close() }
}
