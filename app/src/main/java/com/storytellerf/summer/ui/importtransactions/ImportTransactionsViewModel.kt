package com.storytellerf.summer.ui.importtransactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.storytellerf.summer.data.DataRepository
import com.storytellerf.summer.data.db.entity.FundSource
import com.storytellerf.summer.data.llmd.LlmdTarget
import com.storytellerf.summer.data.recognition.FinanceImageAnalyzer
import com.storytellerf.summer.ui.host.AppDispatchers
import kotlinx.coroutines.flow.Flow

class ImportTransactionsViewModel(
    repository: DataRepository, analyzer: FinanceImageAnalyzer, target: Flow<LlmdTarget>,
    dispatchers: AppDispatchers = AppDispatchers.Runtime,
) : ViewModel() {
    private val host = ImportTransactionsHost(repository, analyzer, viewModelScope, dispatchers, target)
    val uiState = host.uiState
    val effects = host.effects
    fun selectFundSource(source: FundSource) { host.selectFundSource(source) }
    fun updateRow(index: Int, row: TransactionDraft) { host.updateRow(index, row) }
    fun recognize(image: String) { host.recognize(image) }
    fun onAuthorizationResult(authorized: Boolean) { host.onAuthorizationResult(authorized) }
    fun save() { host.save() }
    fun clearPreview() { host.clearPreview() }
    override fun onCleared() { host.close(); super.onCleared() }

    class Factory(private val repository: DataRepository, private val analyzer: FinanceImageAnalyzer, private val target: Flow<LlmdTarget>) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ImportTransactionsViewModel::class.java))
            return ImportTransactionsViewModel(repository, analyzer, target) as T
        }
    }
}
