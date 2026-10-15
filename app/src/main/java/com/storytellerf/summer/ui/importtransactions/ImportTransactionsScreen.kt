package com.storytellerf.summer.ui.importtransactions

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.storytellerf.summer.data.DefaultDataRepository
import com.storytellerf.summer.data.db.SummerDatabase
import com.storytellerf.summer.data.llmd.DataStoreLlmdTargetSettings
import com.storytellerf.summer.data.llmd.LlmdServiceConnection
import com.storytellerf.summer.data.recognition.configuredImageAnalyzer
import com.storytellerf.summer.ui.components.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportTransactionsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onManageAccounts: () -> Unit = {},
    viewModel: ImportTransactionsViewModel = viewModel(factory = ImportTransactionsViewModel.Factory(
        DefaultDataRepository(SummerDatabase.getInstance(LocalContext.current.applicationContext)),
        configuredImageAnalyzer(LocalContext.current.applicationContext),
        DataStoreLlmdTargetSettings(LocalContext.current.applicationContext).selectedTarget,
    )),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val currentOnBack by rememberUpdatedState(onBack)
    var leavePreview by remember { mutableStateOf(false) }
    val hasPreview = state.rows.isNotEmpty()
    BackHandler(enabled = hasPreview && !state.isSaving) { leavePreview = true }
    BackHandler(enabled = state.isSaving) { }
    val requestBack = { if (hasPreview) leavePreview = true else onBack() }
    val authorization = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.onAuthorizationResult(it.resultCode == Activity.RESULT_OK)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.recognize(it.toString()) }
    }
    LaunchedEffect(viewModel, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect -> withContext(Dispatchers.Main) { when (effect) {
                is ImportTransactionsEffect.Saved -> {
                    Toast.makeText(context, "Imported ${effect.count} transactions", Toast.LENGTH_SHORT).show()
                    currentOnBack()
                }
                is ImportTransactionsEffect.Authorize -> authorization.launch(
                    Intent(LlmdServiceConnection.ACTION_AUTHORIZE_CALLER).setPackage(effect.target.packageName)
                        .putExtra(LlmdServiceConnection.EXTRA_CALLER_PACKAGE, context.packageName)
                )
            } } }
        }
    }
    val editable = !state.isSaving && !state.isAnalyzing
    val reviewing = state.rows.isNotEmpty()
    val focus = LocalFocusManager.current
    var editingRow by rememberSaveable { mutableStateOf<Int?>(null) }
    var discardPreview by remember { mutableStateOf(false) }
    var showDedupHelp by rememberSaveable { mutableStateOf(false) }
    Scaffold(modifier = modifier, topBar = {
        TopAppBar(title = { Text(if (reviewing) "Review transactions" else "Import Transactions") }, navigationIcon = {
            IconButton(onClick = requestBack, enabled = !state.isSaving) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        })
    }, bottomBar = {
        EntryAction(label = when {
            state.isSaving -> "Importing..."
            state.isAnalyzing -> "Reading transactions..."
            reviewing -> "Import selected transactions"
            else -> "Choose Screenshot"
        }, enabled = editable && state.fundSourceId != null && (!reviewing || state.rows.any { it.selected }),
            busy = !editable, summary = if (reviewing) "${state.rows.count { it.selected }} transactions selected" else null,
            onClick = { focus.clearFocus(); if (reviewing) viewModel.save() else picker.launch("image/*") })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ImportProgressStep(reviewing)
                    if (reviewing) {
                        val account = state.fundSources.firstOrNull { it.id == state.fundSourceId }?.name ?: "Selected account"
                        ReviewSummary(state.rows.count { it.selected }, state.rows.size,
                            "$account · Tap a transaction to check its details.", editable) {
                            val select = state.rows.any { !it.selected }
                            state.rows.forEachIndexed { i, row -> viewModel.updateRow(i, row.copy(selected = select)) }
                        }
                        TextButton(onClick = { showDedupHelp = !showDedupHelp }) { Text("How duplicates are checked") }
                        if (showDedupHelp) EntryMessage("Original order IDs detect duplicates across images. Without an ID, only repeated rows from the same image are detected. Check overlapping screenshots yourself.")
                        TextButton(onClick = { discardPreview = true }, enabled = editable) { Text("Choose different screenshot") }
                    } else {
                        EntryHeading("Choose an account", "Import completed transactions for one account at a time.")
                        if (state.fundSources.isEmpty()) {
                            EntryMessage("Add an account before importing transactions.")
                            TextButton(onClick = onManageAccounts) { Text("Manage accounts") }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.fundSources.forEach { source ->
                                FilterChip(selected = state.fundSourceId == source.id, enabled = editable,
                                    onClick = { viewModel.selectFundSource(source) }, label = { Text(source.name) })
                            }
                        }
                        EntryMessage("Choose an image with full dates and clear income or expense amounts. You'll review every row before saving.")
                    }
                    if (state.isAnalyzing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.error?.let { EntryMessage(it, isError = true) }
                }
            }
            itemsIndexed(state.rows, key = { _, row -> row.imageRow }) { index, row ->
                val issue = when {
                    row.date.isBlank() -> "Add the transaction date"
                    row.amount.toDoubleOrNull()?.isFinite() != true -> "Check the amount"
                    else -> null
                }
                ReviewItem(title = row.note.ifBlank { "Transaction ${index + 1}" }, amount = row.amount.toDoubleOrNull()?.takeIf(Double::isFinite)?.let { formatSignedMoney(it, state.currency ?: "?") } ?: row.amount,
                    subtitle = "${row.date.replace('T', ' ').ifBlank { "Date not recognized" }}\n${if (row.transactionId.isBlank()) "No order ID" else "Order ${row.transactionId}"}",
                    selected = row.selected, editable = editable,
                    onSelected = { viewModel.updateRow(index, row.copy(selected = it)) },
                    onEdit = { editingRow = index }, issue = issue.takeIf { row.selected }, selectionLabel = "Include transaction ${index + 1}")
            }
        }
    }
    editingRow?.let { index -> state.rows.getOrNull(index)?.let { row ->
        ReviewEditor("Transaction details", onDismiss = { focus.clearFocus(); editingRow = null }) {
            OutlinedTextField(value = row.amount, enabled = editable,
                onValueChange = { viewModel.updateRow(index, row.copy(amount = it)) },
                label = { Text("Signed amount (${state.currency.orEmpty()})") }, supportingText = { Text("Expenses are negative; income is positive") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = row.date, enabled = editable,
                onValueChange = { viewModel.updateRow(index, row.copy(date = it)) },
                label = { Text("Local date and time") }, supportingText = { Text("yyyy-MM-ddTHH:mm:ss") },
                isError = row.date.isBlank(), singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = row.note, enabled = editable,
                onValueChange = { viewModel.updateRow(index, row.copy(note = it)) },
                label = { Text("Description") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = row.transactionId, enabled = editable,
                onValueChange = { viewModel.updateRow(index, row.copy(transactionId = it)) },
                label = { Text("Original transaction ID") }, supportingText = { Text("Leave blank if the screenshot doesn't show an ID") },
                modifier = Modifier.fillMaxWidth())
        }
    } }
    if (discardPreview) AlertDialog(onDismissRequest = { discardPreview = false },
        title = { Text("Choose another screenshot?") }, text = { Text("This clears the unsaved preview so you can select an account and a different image.") },
        confirmButton = { TextButton(onClick = {
            discardPreview = false; editingRow = null; viewModel.clearPreview()
        }) { Text("Choose again") } },
        dismissButton = { TextButton(onClick = { discardPreview = false }) { Text("Keep reviewing") } })
    if (leavePreview) ConfirmLeavePreview(onStay = { leavePreview = false }, onLeave = {
        leavePreview = false; onBack()
    })

}
