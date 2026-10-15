package com.storytellerf.summer.ui.fundsources

import com.storytellerf.summer.data.DataRepository
import com.storytellerf.summer.data.db.entity.FundSource
import com.storytellerf.summer.data.llmd.LlmdTarget
import com.storytellerf.summer.data.llmd.LlmdTargetSettings
import com.storytellerf.summer.ui.host.AppDispatchers
import com.storytellerf.summer.ui.host.withDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FundSourcesHost(
    private val repository: DataRepository,
    private val settings: LlmdTargetSettings,
    private val scope: CoroutineScope,
    private val dispatchers: AppDispatchers,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    val uiState: StateFlow<FundSourcesUiState> = combine(
        repository.getAllFundSources(),
        settings.selectedTarget,
    ) { fundSources, selectedTarget ->
        FundSourcesUiState.Success(
            fundSources = fundSources.toList(),
            selectedLlmdTarget = selectedTarget,
        ) as FundSourcesUiState
    }
        .flowOn(dispatchers.default)
        .catch { emit(FundSourcesUiState.Error(it.message ?: "Unknown error")) }
        .stateIn(
            scope = scope.withDispatcher(dispatchers.default),
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = FundSourcesUiState.Loading,
        )

    fun addFundSource(name: String, currency: String = "CNY") {
        launchIo { repository.insertFundSource(FundSource(name = name.trim(), currency = requireNotNull(com.storytellerf.summer.data.currencyCode(currency)))) }
    }

    fun updateFundSource(fundSource: FundSource) {
        launchIo {
            repository.updateFundSource(fundSource.copy(currency = requireNotNull(com.storytellerf.summer.data.currencyCode(fundSource.currency)), updatedAt = currentTimeMillis()))
        }
    }

    fun deleteFundSource(fundSource: FundSource) {
        launchIo { repository.deleteFundSource(fundSource) }
    }

    fun selectLlmdTarget(target: LlmdTarget) {
        launchIo { settings.selectTarget(target) }
    }

    private fun launchIo(block: suspend () -> Unit) {
        scope.launch(dispatchers.io) {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            }
        }
    }
}

sealed interface FundSourcesUiState {
    data object Loading : FundSourcesUiState
    data class Error(val message: String) : FundSourcesUiState
    data class Success(
        val fundSources: List<FundSource>,
        val selectedLlmdTarget: LlmdTarget,
    ) : FundSourcesUiState
}
