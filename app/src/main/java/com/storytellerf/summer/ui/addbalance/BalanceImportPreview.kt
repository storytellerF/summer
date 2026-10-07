package com.storytellerf.summer.ui.addbalance

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.storytellerf.summer.data.recognition.parseLocalDateTime
import com.storytellerf.summer.ui.components.*

@Composable
internal fun BalanceImportPreview(state: AddBalanceChangeUiState, viewModel: AddBalanceChangeViewModel,
    modifier: Modifier, onChooseAgain: () -> Unit) {
    val editable = !state.isSaving && !state.isImageAnalyzing
    var editingKey by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(20.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ImportProgressStep(reviewing = true)
                ReviewSummary(state.balanceRows.count { it.selected }, state.balanceRows.size,
                    "${state.balanceRows.map { it.imageIndex }.distinct().size} images · Tap a balance to check its details.", editable) {
                    val select = state.balanceRows.any { !it.selected }
                    state.balanceRows.forEach { viewModel.updateBalanceRow(it.key, it.copy(selected = select)) }
                }
                if (state.isImageAnalyzing) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Reading remaining images...", style = MaterialTheme.typography.bodyMedium)
                }
                state.errorMessage?.let { EntryMessage(it, isError = true) }
                TextButton(onClick = onChooseAgain, enabled = editable) { Text("Choose different images") }
            }
        }
        itemsIndexed(state.balanceRows, key = { _, row -> row.key }) { index, row ->
            val account = state.imageTargets.firstOrNull { it.fundSourceId == row.fundSourceId }?.name
            val issue = when {
                row.fundSourceId == null -> "Assign an account"
                row.timestamp == null || row.timestamp <= 0 -> "Check the recording time"
                parseBalanceInput(row.balance) == null -> "Check the balance amount"
                else -> null
            }
            ReviewItem(title = account ?: "Unassigned balance ${index + 1}", amount = parseBalanceInput(row.balance)?.let(::formatMoney) ?: row.balance,
                subtitle = "Image ${row.imageIndex + 1} · ${row.label.ifBlank { "Account balance" }}\n${row.dateTime.replace('T', ' ')}",
                selected = row.selected, editable = editable, onSelected = { viewModel.updateBalanceRow(row.key, row.copy(selected = it)) },
                onEdit = { editingKey = row.key }, issue = issue.takeIf { row.selected }, selectionLabel = "Include balance ${index + 1}")
        }
    }
    state.balanceRows.firstOrNull { it.key == editingKey }?.let { row ->
        ReviewEditor("Balance details", onDismiss = { editingKey = null }) {
            Text("Image ${row.imageIndex + 1} · ${row.label.ifBlank { "Account balance" }}", style = MaterialTheme.typography.bodyMedium)
            Text("Account", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.imageTargets.forEach { target ->
                    FilterChip(selected = row.fundSourceId == target.fundSourceId, enabled = editable,
                        modifier = Modifier.semantics { contentDescription = "Assign ${target.name}" },
                        onClick = { viewModel.updateBalanceRow(row.key, row.copy(fundSourceId = target.fundSourceId)) },
                        label = { Text(target.name) })
                }
            }
            if (row.fundSourceId == null) Text("Choose the account this balance belongs to.", color = MaterialTheme.colorScheme.error)
            OutlinedTextField(value = row.balance, onValueChange = { viewModel.updateBalanceRow(row.key, row.copy(balance = it)) },
                label = { Text("Balance (CNY)") }, prefix = { Text("¥") }, singleLine = true,
                enabled = editable, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = row.dateTime, onValueChange = {
                viewModel.updateBalanceRow(row.key, row.copy(dateTime = it, timestamp = parseLocalDateTime(it.trim())))
            }, label = { Text("Local date and time") }, supportingText = { Text("yyyy-MM-ddTHH:mm:ss · check the time for older images") },
                singleLine = true, enabled = editable, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = row.note, onValueChange = { viewModel.updateBalanceRow(row.key, row.copy(note = it)) },
                label = { Text("Note (Optional)") }, enabled = editable, modifier = Modifier.fillMaxWidth())
        }
    }
}
