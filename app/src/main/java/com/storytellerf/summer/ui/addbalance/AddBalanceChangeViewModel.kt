package com.storytellerf.summer.ui.addbalance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.storytellerf.summer.data.DataRepository
import com.storytellerf.summer.data.db.entity.FundSource
import com.storytellerf.summer.data.recognition.FinanceImageAnalyzer
import com.storytellerf.summer.data.recognition.ImageCreationTimeReader
import com.storytellerf.summer.data.llmd.LlmdTarget
import com.storytellerf.summer.ui.host.AppDispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

class AddBalanceChangeViewModel(
    repository: DataRepository,
    private val imageAnalyzer: FinanceImageAnalyzer,
    imageAnalysisTarget: Flow<LlmdTarget> = flowOf(LlmdTarget.Release),
    dispatchers: AppDispatchers = AppDispatchers.Runtime,
    imageCreationTimeReader: ImageCreationTimeReader = ImageCreationTimeReader { null },
) : ViewModel() {
    private val host = AddBalanceChangeHost(
        repository = repository,
        imageAnalyzer = imageAnalyzer,
        scope = viewModelScope,
        dispatchers = dispatchers,
        imageAnalysisTarget = imageAnalysisTarget,
        imageCreationTimeReader = imageCreationTimeReader,
    )
    val uiState: StateFlow<AddBalanceChangeUiState> = host.uiState
    val effects: SharedFlow<AddBalanceChangeEffect> = host.effects

    fun selectFundSource(fundSource: FundSource) {
        host.selectFundSource(fundSource)
    }

    fun updateBalance(balance: String) {
        host.updateBalance(balance)
    }

    fun updateNote(note: String) {
        host.updateNote(note)
    }

    fun updateDateTime(dateTime: String) {
        host.updateDateTime(dateTime)
    }

    fun toggleImageTarget(source: FundSource) { host.toggleImageTarget(source) }
    fun selectEntryMode(mode: BalanceEntryMode) { host.selectEntryMode(mode) }
    fun updateBalanceToRead(sourceId: Long, label: String) { host.updateBalanceToRead(sourceId, label) }
    fun extractBalancesFromImages(images: List<String>) { host.extractBalancesFromImages(images) }
    fun updateBalanceRow(key: String, row: BalanceDraft) { host.updateBalanceRow(key, row) }
    fun clearBalancePreview() { host.clearBalancePreview() }

    fun onAuthorizationResult(authorized: Boolean) {
        host.onAuthorizationResult(authorized)
    }

    fun saveBalanceChange() {
        host.saveBalanceChange()
    }

    override fun onCleared() {
        host.close()
        super.onCleared()
    }

    class Factory(
        private val repository: DataRepository,
        private val imageAnalyzer: FinanceImageAnalyzer,
        private val imageAnalysisTarget: Flow<LlmdTarget> = flowOf(LlmdTarget.Release),
        private val dispatchers: AppDispatchers = AppDispatchers.Runtime,
        private val imageCreationTimeReader: ImageCreationTimeReader = ImageCreationTimeReader { null },
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AddBalanceChangeViewModel::class.java))
            return AddBalanceChangeViewModel(
                repository,
                imageAnalyzer,
                imageAnalysisTarget,
                dispatchers,
                imageCreationTimeReader,
            ) as T
        }
    }
}
