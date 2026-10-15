package com.storytellerf.summer.ui.importtransactions

import com.storytellerf.summer.data.DataRepository
import com.storytellerf.summer.data.db.entity.BalanceImpactRecord
import com.storytellerf.summer.data.db.entity.FundSource
import com.storytellerf.summer.data.llmd.LlmdAuthorizationException
import com.storytellerf.summer.data.llmd.LlmdRecognitionException
import com.storytellerf.summer.data.llmd.LlmdTarget
import com.storytellerf.summer.data.recognition.FinanceImageAnalyzer
import com.storytellerf.summer.data.recognition.formatLocalDateTime
import com.storytellerf.summer.data.recognition.parseLocalDateTime
import com.storytellerf.summer.ui.host.AppDispatchers
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ImportTransactionsHost(
    private val repository: DataRepository,
    private val analyzer: FinanceImageAnalyzer,
    scope: CoroutineScope,
    private val dispatchers: AppDispatchers,
    private val target: Flow<LlmdTarget> = flowOf(LlmdTarget.Release),
    private val timeZone: TimeZone = TimeZone.getDefault(),
) : AutoCloseable {
    private val hostScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + dispatchers.coordination)
    private val form = MutableStateFlow(ImportTransactionsUiState())
    private val effectChannel = Channel<ImportTransactionsEffect>(Channel.BUFFERED)
    private var analysisJob: Job? = null
    private var pendingImage: String? = null
    val effects = effectChannel.receiveAsFlow()
    val uiState = combine(form, repository.getAllFundSources()) { state, sources ->
        state.copy(fundSources = sources.toList())
    }.stateIn(hostScope, SharingStarted.WhileSubscribed(5_000), ImportTransactionsUiState())

    fun selectFundSource(source: FundSource) = hostScope.launch {
        if (!form.value.isSaving && !form.value.isAnalyzing && form.value.rows.isEmpty()) form.update { it.copy(fundSourceId = source.id, error = null) }
    }

    fun clearPreview() = hostScope.launch {
        if (form.value.isSaving || form.value.isAnalyzing) return@launch
        pendingImage = null
        form.update { it.copy(rows = emptyList(), imageHash = null, imagePath = null, error = null) }
    }

    fun updateRow(index: Int, row: TransactionDraft) = hostScope.launch {
        if (!form.value.isSaving && !form.value.isAnalyzing) form.update { state ->
            state.copy(rows = state.rows.mapIndexed { i, current -> if (index == i) row else current }, error = null)
        }
    }

    fun recognize(image: String) = hostScope.launch {
        if (form.value.isSaving) return@launch
        if (form.value.fundSourceId == null) {
            form.update { it.copy(error = "Select an account and its currency before choosing an image") }
            return@launch
        }
        pendingImage = image
        analyze(image)
    }

    private fun analyze(image: String) {
        analysisJob?.cancel()
        form.update { it.copy(isAnalyzing = true, rows = emptyList(), imageHash = null, imagePath = null, error = null) }
        analysisJob = hostScope.launch {
            try {
                val selectedTarget = withContext(dispatchers.io) { target.first() }
                val currency = withContext(dispatchers.io) { repository.getFundSourceById(requireNotNull(form.value.fundSourceId))?.currency }
                    ?: error("Selected account no longer exists")
                form.update { it.copy(currency = currency) }
                val result = withContext(dispatchers.io) { analyzer.extractTransactionsFromImage(image, selectedTarget, currency) }
                ensureActive()
                val error = result.exceptionOrNull()
                if (error is CancellationException) throw error
                if (error is LlmdAuthorizationException) {
                    form.update { it.copy(isAnalyzing = false) }
                    effectChannel.send(ImportTransactionsEffect.Authorize(selectedTarget))
                } else {
                    val recognized = result.getOrThrow()
                    require(recognized.records.isNotEmpty())
                    val rows = withContext(dispatchers.default) {
                        recognized.records.mapIndexed { index, record -> TransactionDraft(
                            imageRow = index,
                            date = record.timestamp?.let { formatLocalDateTime(it, timeZone) }.orEmpty(),
                            amount = record.amount.toString(),
                            note = record.note.orEmpty(),
                            transactionId = record.transactionId.orEmpty(),
                        ) }
                    }
                    pendingImage = null
                    form.update { it.copy(rows = rows, imageHash = recognized.imageHash, imagePath = recognized.imagePath, isAnalyzing = false) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                pendingImage = null
                form.update { it.copy(error = if (error is LlmdRecognitionException) error.message
                    else "Could not recognize transactions. Check recognition settings and use a screenshot with readable amounts and income/expense direction.") }
            } finally {
                if (isActive) form.update { it.copy(isAnalyzing = false) }
            }
        }
    }

    fun onAuthorizationResult(authorized: Boolean) = hostScope.launch {
        val image = pendingImage
        if (authorized && image != null) analyze(image)
        else {
            pendingImage = null
            form.update { it.copy(error = "llmd authorization was not granted") }
        }
    }

    fun save() = hostScope.launch {
        val state = form.value
        if (state.isSaving || state.isAnalyzing) return@launch
        val sourceId = state.fundSourceId
        val hash = state.imageHash
        val selected = state.rows.filter { it.selected }
        if (sourceId == null || hash == null || selected.isEmpty()) {
            form.update { it.copy(error = "Select an account and at least one recognized transaction") }
            return@launch
        }
        form.update { it.copy(isSaving = true, error = null) }
        val records = withContext(dispatchers.default) {
            selected.map { row ->
                val timestamp = parseLocalDateTime(row.date, timeZone)
                val amount = row.amount.trim().toDoubleOrNull()?.takeIf(Double::isFinite)
                if (timestamp == null || amount == null) null else BalanceImpactRecord(
                    fundSourceId = sourceId, timestamp = timestamp, amount = amount,
                    note = row.note.trim().ifEmpty { null }, imageHash = hash, imageRow = row.imageRow,
                    transactionId = row.transactionId.trim().ifEmpty { null }, imagePath = state.imagePath,
                )
            }
        }
        if (records.any { it == null }) {
            form.update { it.copy(isSaving = false, error = "Use valid dates (yyyy-MM-ddTHH:mm:ss) and signed numeric amounts") }
            return@launch
        }
        try {
            val imported = withContext(dispatchers.io) {
                require(repository.getFundSourceById(sourceId)?.currency == state.currency)
                repository.importTransactions(records.filterNotNull())
            }
            if (imported == 0) form.update { it.copy(error = "These screenshot rows have already been imported for this account") }
            else effectChannel.send(ImportTransactionsEffect.Saved(imported))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            form.update { it.copy(error = "Could not save transactions. Check that the account and its currency are unchanged and try again.") }
        } finally {
            if (isActive) form.update { it.copy(isSaving = false) }
        }
    }

    override fun close() {
        hostScope.cancel()
        effectChannel.close()
        analyzer.close()
    }
}

data class TransactionDraft(val imageRow: Int, val date: String, val amount: String, val note: String, val selected: Boolean = true, val transactionId: String = "")
data class ImportTransactionsUiState(
    val fundSources: List<FundSource> = emptyList(), val fundSourceId: Long? = null, val currency: String? = null,
    val rows: List<TransactionDraft> = emptyList(), val imageHash: String? = null, val imagePath: String? = null,
    val isAnalyzing: Boolean = false, val isSaving: Boolean = false, val error: String? = null,
)
sealed interface ImportTransactionsEffect {
    data class Authorize(val target: LlmdTarget) : ImportTransactionsEffect
    data class Saved(val count: Int) : ImportTransactionsEffect
}
