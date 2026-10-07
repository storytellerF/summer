package com.storytellerf.summer.ui.addbalance

import com.storytellerf.summer.data.DataRepository
import com.storytellerf.summer.data.db.entity.BalanceChange
import com.storytellerf.summer.data.db.entity.FundSource
import com.storytellerf.summer.data.recognition.FinanceImageAnalyzer
import com.storytellerf.summer.data.recognition.ImageCreationTimeReader
import com.storytellerf.summer.data.recognition.BalanceReadTarget
import com.storytellerf.summer.data.recognition.formatLocalDateTime
import com.storytellerf.summer.data.recognition.parseLocalDateTime
import com.storytellerf.summer.data.llmd.LlmdAuthorizationException
import com.storytellerf.summer.data.llmd.LlmdTarget
import com.storytellerf.summer.ui.host.AppDispatchers
import com.storytellerf.summer.ui.host.withDispatcher
import java.text.NumberFormat
import java.text.ParsePosition
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AddBalanceChangeHost(
    private val repository: DataRepository,
    private val imageAnalyzer: FinanceImageAnalyzer,
    private val scope: CoroutineScope,
    private val dispatchers: AppDispatchers,
    private val imageAnalysisTarget: Flow<LlmdTarget> = flowOf(LlmdTarget.Release),
    private val imageCreationTimeReader: ImageCreationTimeReader = ImageCreationTimeReader { null },
    private val now: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val hostScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + dispatchers.coordination)
    private val formState = MutableStateFlow(freshForm())
    private var dateTimeRevision = 0L
    private val pendingImageReference = MutableStateFlow<String?>(null)
    private val mutableEffects = MutableSharedFlow<AddBalanceChangeEffect>()
    private var imageAnalysisJob: Job? = null
    private var pendingBatch: PendingBalanceBatch? = null

    val effects: SharedFlow<AddBalanceChangeEffect> = mutableEffects.asSharedFlow()

    val uiState: StateFlow<AddBalanceChangeUiState> = combine(
        formState,
        repository.getAllFundSources(),
    ) { state, fundSources ->
        state.copy(fundSources = fundSources.toList())
    }
        .flowOn(dispatchers.default)
        .catch { error ->
            emit(
                formState.value.copy(
                    errorMessage = error.message ?: "Failed to load fund sources",
                )
            )
        }
        .stateIn(
            scope = hostScope.withDispatcher(dispatchers.default),
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = formState.value,
        )

    fun selectFundSource(fundSource: FundSource) = hostScope.launch {
        if (!formState.value.isSaving && !formState.value.isImageAnalyzing) {
            formState.update { it.copy(selectedFundSource = fundSource, errorMessage = null) }
        }
    }

    fun selectEntryMode(mode: BalanceEntryMode) = hostScope.launch {
        if (formState.value.isSaving || formState.value.isImageAnalyzing || formState.value.entryMode == mode) return@launch
        pendingBatch = null
        pendingImageReference.value = null
        formState.update { state -> state.copy(entryMode = mode, balanceRows = emptyList(), errorMessage = null,
            imageTargets = state.imageTargets.ifEmpty {
                state.selectedFundSource?.let { listOf(BalanceReadTarget(it.id, it.name, "Account balance")) }.orEmpty()
            }) }
    }

    fun toggleImageTarget(source: FundSource) = hostScope.launch {
        if (!formState.value.isSaving && !formState.value.isImageAnalyzing && formState.value.balanceRows.isEmpty()) formState.update { state ->
            state.copy(imageTargets = if (state.imageTargets.any { it.fundSourceId == source.id }) state.imageTargets.filterNot { it.fundSourceId == source.id }
                else state.imageTargets + BalanceReadTarget(source.id, source.name, "Account balance"), errorMessage = null)
        }
    }

    fun updateBalanceToRead(sourceId: Long, label: String) = hostScope.launch {
        if (!formState.value.isSaving && !formState.value.isImageAnalyzing && formState.value.balanceRows.isEmpty()) formState.update { state ->
            state.copy(imageTargets = state.imageTargets.map { if (it.fundSourceId == sourceId) it.copy(balanceToRead = label) else it })
        }
    }

    fun updateBalanceRow(key: String, row: BalanceDraft) = hostScope.launch {
        if (!formState.value.isSaving && !formState.value.isImageAnalyzing) formState.update { state ->
            state.copy(balanceRows = state.balanceRows.map { if (it.key == key) row.copy(key = key, imagePath = it.imagePath, imageIndex = it.imageIndex) else it }, errorMessage = null)
        }
    }

    fun clearBalancePreview() = hostScope.launch {
        if (!formState.value.isSaving && !formState.value.isImageAnalyzing) {
            pendingBatch = null
            formState.update { it.copy(balanceRows = emptyList(), errorMessage = null) }
        }
    }

    fun extractBalancesFromImages(images: List<String>) = hostScope.launch {
        if (formState.value.isSaving || formState.value.isImageAnalyzing) return@launch
        val targets = formState.value.imageTargets.toList()
        if (images.isEmpty() || images.size > 20 || targets.size !in 1..30 || targets.any { it.balanceToRead.isBlank() }) {
            formState.update { it.copy(errorMessage = "Select accounts and balance labels first; choose 1–20 images") }
            return@launch
        }
        pendingImageReference.value = null
        pendingBatch = PendingBalanceBatch(images.toList(), targets, fallbackTimestamp = now())
        formState.update { it.copy(entryMode = BalanceEntryMode.Screenshots, balanceRows = emptyList(), errorMessage = null) }
        analyzeBatch()
    }

    private fun analyzeBatch() {
        imageAnalysisJob?.cancel()
        formState.update { it.copy(isImageAnalyzing = true) }
        imageAnalysisJob = hostScope.launch {
            try {
                val target = withContext(dispatchers.io) { imageAnalysisTarget.first() }
                while (true) {
                    val batch = pendingBatch ?: break
                    if (batch.index >= batch.images.size) {
                        pendingBatch = null
                        formState.update { it.copy(errorMessage = batch.errors.takeIf { errors -> errors.isNotEmpty() }?.joinToString("\n")) }
                        break
                    }
                    val image = batch.images[batch.index]
                    val timestamp = batch.timestamp ?: withContext(dispatchers.io) {
                        imageCreationTimeReader.readCreationTime(image)?.takeIf { it > 0 } ?: batch.fallbackTimestamp
                    }
                    pendingBatch = batch.copy(timestamp = timestamp)
                    val result = withContext(dispatchers.io) { imageAnalyzer.extractBalancesFromImage(image, batch.targets, target) }
                    val error = result.exceptionOrNull()
                    if (error is CancellationException) throw error
                    if (error is LlmdAuthorizationException) {
                        formState.update { it.copy(isImageAnalyzing = false) }
                        mutableEffects.emit(AddBalanceChangeEffect.RequestAuthorization(target))
                        return@launch
                    }
                    val recognized = result.getOrNull()
                    val valid = recognized?.records?.takeIf { records -> records.isNotEmpty() && records.all {
                        it.balance.isFinite() && (it.fundSourceId == null || batch.targets.any { target -> target.fundSourceId == it.fundSourceId })
                    } }
                    if (valid != null) {
                        val drafts = withContext(dispatchers.default) { valid.mapIndexed { row, balance -> BalanceDraft(
                            key = "${batch.index}:$row", imageIndex = batch.index, fundSourceId = balance.fundSourceId,
                            balance = formatBalanceForInput(balance.balance), label = balance.label.orEmpty(),
                            dateTime = formatLocalDateTime(timestamp), timestamp = timestamp, imagePath = recognized.imagePath,
                        ) } }
                        formState.update { it.copy(balanceRows = it.balanceRows + drafts) }
                        pendingBatch = batch.copy(index = batch.index + 1, timestamp = null)
                    } else pendingBatch = batch.copy(index = batch.index + 1, timestamp = null,
                        errors = batch.errors + "Image ${batch.index + 1}: no requested balances recognized; check accounts and recognition settings")
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                pendingBatch = null
                formState.update { it.copy(errorMessage = "Could not read balance images. Review any completed rows and try again.") }
            } finally {
                if (isActive) formState.update { it.copy(isImageAnalyzing = false) }
            }
        }
    }

    fun updateBalance(balance: String) = hostScope.launch {
        formState.update { it.copy(balance = balance, errorMessage = null) }
    }

    fun updateNote(note: String) = hostScope.launch {
        formState.update { it.copy(note = note) }
    }

    fun updateDateTime(dateTime: String) = hostScope.launch {
        dateTimeRevision++
        formState.update { it.copy(dateTime = dateTime, timestamp = parseLocalDateTime(dateTime.trim()), errorMessage = null) }
    }

    fun extractBalanceFromImage(imageReference: String) = hostScope.launch {
        pendingImageReference.value = imageReference
        dateTimeRevision++
        analyzeImage(imageReference, readImageTime = true)
    }

    private fun analyzeImage(imageReference: String, readImageTime: Boolean = false) {
        imageAnalysisJob?.cancel()
        formState.update { it.copy(isImageAnalyzing = true, errorMessage = null, imagePath = null) }
        val revision = dateTimeRevision
        imageAnalysisJob = hostScope.launch {
            try {
                if (readImageTime) {
                    val creationTime = withContext(dispatchers.io) { imageCreationTimeReader.readCreationTime(imageReference) }
                    if (revision == dateTimeRevision) {
                        val timestamp = creationTime?.takeIf { it > 0 } ?: now()
                        formState.update { it.copy(dateTime = formatLocalDateTime(timestamp), timestamp = timestamp) }
                    }
                }
                val target = withContext(dispatchers.io) { imageAnalysisTarget.first() }
                val result = withContext(dispatchers.io) { imageAnalyzer.extractBalanceWithImage(imageReference, target) }
                result.onSuccess { recognized ->
                    pendingImageReference.value = null
                    formState.update {
                        it.copy(
                            balance = formatBalanceForInput(recognized.balance),
                            imagePath = recognized.imagePath,
                            isImageAnalyzing = false,
                        )
                    }
                }.onFailure { error ->
                    if (error is LlmdAuthorizationException) {
                        formState.update { it.copy(isImageAnalyzing = false) }
                        mutableEffects.emit(AddBalanceChangeEffect.RequestAuthorization(target))
                    } else {
                        pendingImageReference.value = null
                        formState.update {
                            it.copy(
                                isImageAnalyzing = false,
                                errorMessage = error.message ?: "Failed to extract balance",
                            )
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                pendingImageReference.value = null
                formState.update { it.copy(errorMessage = "Failed to load image recognition settings") }
            } finally {
                if (isActive) formState.update { it.copy(isImageAnalyzing = false) }
            }
        }
    }

    fun onAuthorizationResult(authorized: Boolean) = hostScope.launch {
        if (pendingBatch != null) {
            if (authorized) analyzeBatch()
            else {
                pendingBatch = null
                formState.update { it.copy(isImageAnalyzing = false, errorMessage = "llmd authorization was not granted") }
            }
            return@launch
        }
        val imageReference = pendingImageReference.value
        if (authorized && imageReference != null) {
            analyzeImage(imageReference)
        } else {
            pendingImageReference.value = null
            formState.update {
                it.copy(errorMessage = "llmd authorization was not granted")
            }
        }
    }

    fun saveBalanceChange() = hostScope.launch {
        val state = formState.value
        if (state.isSaving || state.isImageAnalyzing) return@launch
        if (state.balanceRows.isNotEmpty()) {
            saveBalanceBatch(state)
            return@launch
        }
        if (state.entryMode == BalanceEntryMode.Screenshots) {
            formState.update { it.copy(errorMessage = "Choose images and review the recognized balances before saving") }
            return@launch
        }
        val fundSource = state.selectedFundSource
        if (fundSource == null) {
            formState.update { it.copy(errorMessage = "Select a fund source") }
            return@launch
        }
        val balance = parseBalanceInput(state.balance)
        if (balance == null) {
            formState.update { it.copy(errorMessage = "Enter a valid balance") }
            return@launch
        }
        if (state.isSaving || state.isImageAnalyzing) return@launch
        val timestamp = state.timestamp
        if (timestamp == null || timestamp <= 0) {
            formState.update { it.copy(errorMessage = "Enter a valid local date and time (yyyy-MM-ddTHH:mm:ss)") }
            return@launch
        }

        formState.update { it.copy(isSaving = true, errorMessage = null) }
        try {
            withContext(dispatchers.io) {
                val currentBalance = repository.getBalanceChangesByFundSource(fundSource.id)
                    .firstOrNull()
                    ?.firstOrNull { it.timestamp <= timestamp }
                    ?.newBalance

                repository.insertBalanceChange(
                    BalanceChange(
                        fundSourceId = fundSource.id,
                        newBalance = balance,
                        previousBalance = currentBalance,
                        note = state.note.ifBlank { null },
                        imagePath = state.imagePath,
                        timestamp = timestamp,
                    )
                )
            }
            pendingImageReference.value = null
            formState.value = freshForm()
            mutableEffects.emit(AddBalanceChangeEffect.Saved)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            formState.update {
                it.copy(errorMessage = error.message ?: "Failed to save balance")
            }
        } finally {
            formState.update { it.copy(isSaving = false) }
        }
    }

    private suspend fun saveBalanceBatch(state: AddBalanceChangeUiState) {
        val selected = state.balanceRows.filter { it.selected }
        if (selected.isEmpty() || selected.any { row -> row.fundSourceId == null || state.imageTargets.none { it.fundSourceId == row.fundSourceId } ||
                parseBalanceInput(row.balance) == null || row.timestamp == null || row.timestamp <= 0 }) {
            formState.update { it.copy(errorMessage = "Assign each selected balance to an account and verify its amount and local date/time") }
            return
        }
        formState.update { it.copy(isSaving = true, errorMessage = null) }
        try {
            val records = withContext(dispatchers.default) { selected.map { row -> BalanceChange(
                fundSourceId = requireNotNull(row.fundSourceId), newBalance = requireNotNull(parseBalanceInput(row.balance)),
                timestamp = requireNotNull(row.timestamp), imagePath = row.imagePath, note = row.note.ifBlank { null },
            ) } }
            withContext(dispatchers.io) { repository.insertBalanceChanges(records) }
            pendingBatch = null
            formState.value = freshForm()
            mutableEffects.emit(AddBalanceChangeEffect.Saved)
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { formState.update { it.copy(errorMessage = "Could not save balances; check that selected accounts still exist") } }
        finally { if (currentCoroutineContext().isActive) formState.update { it.copy(isSaving = false) } }
    }

    override fun close() {
        hostScope.cancel()
        imageAnalyzer.close()
    }

    private fun freshForm(): AddBalanceChangeUiState {
        val timestamp = now()
        return AddBalanceChangeUiState(dateTime = formatLocalDateTime(timestamp), timestamp = timestamp)
    }
}

sealed interface AddBalanceChangeEffect {
    data object Saved : AddBalanceChangeEffect
    data class RequestAuthorization(val target: LlmdTarget) : AddBalanceChangeEffect
}

enum class BalanceEntryMode { Manual, Screenshots }

data class AddBalanceChangeUiState(
    val entryMode: BalanceEntryMode = BalanceEntryMode.Manual,
    val fundSources: List<FundSource> = emptyList(),
    val selectedFundSource: FundSource? = null,
    val balance: String = "",
    val note: String = "",
    val isImageAnalyzing: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val imagePath: String? = null,
    val dateTime: String = "",
    val timestamp: Long? = null,
    val imageTargets: List<BalanceReadTarget> = emptyList(),
    val balanceRows: List<BalanceDraft> = emptyList(),
)

data class BalanceDraft(
    val key: String, val imageIndex: Int, val fundSourceId: Long?, val balance: String, val label: String,
    val dateTime: String, val timestamp: Long?, val imagePath: String?, val selected: Boolean = true, val note: String = "",
)

private data class PendingBalanceBatch(
    val images: List<String>, val targets: List<BalanceReadTarget>, val index: Int = 0,
    val timestamp: Long? = null, val fallbackTimestamp: Long, val errors: List<String> = emptyList(),
)

internal fun formatBalanceForInput(balance: Double): String =
    String.format(Locale.ROOT, "%.2f", balance)

internal fun parseBalanceInput(value: String, locale: Locale = Locale.getDefault()): Double? {
    val text = value.trim()
    if (text.isEmpty()) return null
    text.toDoubleOrNull()?.takeIf(Double::isFinite)?.let { return it }

    val position = ParsePosition(0)
    val parsed = NumberFormat.getNumberInstance(locale).parse(text, position)?.toDouble()
    return parsed?.takeIf { position.index == text.length && it.isFinite() }
}
