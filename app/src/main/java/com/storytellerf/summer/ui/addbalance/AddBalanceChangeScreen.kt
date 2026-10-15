package com.storytellerf.summer.ui.addbalance

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
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
import com.storytellerf.summer.data.recognition.AndroidImageCreationTimeReader
import com.storytellerf.summer.data.recognition.configuredImageAnalyzer
import com.storytellerf.summer.ui.components.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddBalanceChangeScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onManageAccounts: () -> Unit = {},
    viewModel: AddBalanceChangeViewModel = viewModel(
        factory = AddBalanceChangeViewModel.Factory(
            DefaultDataRepository(SummerDatabase.getInstance(LocalContext.current.applicationContext)),
            configuredImageAnalyzer(LocalContext.current.applicationContext),
            DataStoreLlmdTargetSettings(LocalContext.current.applicationContext).selectedTarget,
            imageCreationTimeReader = AndroidImageCreationTimeReader(LocalContext.current.applicationContext),
        )
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnBack by rememberUpdatedState(onBack)
    var leavePreview by remember { mutableStateOf(false) }
    val hasPreview = state.balanceRows.isNotEmpty()
    BackHandler(enabled = hasPreview && !state.isSaving) { leavePreview = true }
    BackHandler(enabled = state.isSaving) { }
    val requestBack = { if (hasPreview) leavePreview = true else onBack() }

    val authorizationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        viewModel.onAuthorizationResult(result.resultCode == Activity.RESULT_OK)
    }

    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                withContext(Dispatchers.Main) {
                when (effect) {
                    AddBalanceChangeEffect.Saved -> currentOnBack()
                    is AddBalanceChangeEffect.RequestAuthorization -> {
                        authorizationLauncher.launch(
                            Intent(LlmdServiceConnection.ACTION_AUTHORIZE_CALLER)
                                .setPackage(effect.target.packageName)
                                .putExtra(
                                    LlmdServiceConnection.EXTRA_CALLER_PACKAGE,
                                    context.packageName,
                                ),
                        )
                    }
                }
                }
            }
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents(),
    ) { images ->
        if (images.isNotEmpty()) viewModel.extractBalancesFromImages(images.map { it.toString() })
    }

    val editable = !state.isSaving && !state.isImageAnalyzing
    val reviewing = state.balanceRows.isNotEmpty()
    val screenshots = state.entryMode == BalanceEntryMode.Screenshots
    val focus = LocalFocusManager.current
    var discardPreview by remember { mutableStateOf(false) }
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(if (reviewing) "Review balances" else "Record balance") }, navigationIcon = {
            IconButton(onClick = requestBack, enabled = !state.isSaving) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        }) },
        bottomBar = {
            EntryAction(
                label = when {
                    state.isSaving -> "Saving..."
                    state.isImageAnalyzing -> "Reading balances..."
                    reviewing -> "Save selected balances"
                    screenshots -> "Choose images"
                    else -> "Save Balance Change"
                },
                enabled = editable && when {
                    reviewing -> state.balanceRows.any { it.selected }
                    screenshots -> state.imageTargets.isNotEmpty() && state.imageTargets.all { it.balanceToRead.isNotBlank() }
                    else -> state.selectedFundSource != null && state.balance.isNotBlank()
                },
                busy = !editable,
                summary = if (reviewing) "${state.balanceRows.count { it.selected }} balances will be saved together" else null,
                onClick = {
                    focus.clearFocus()
                    if (screenshots && !reviewing) imagePickerLauncher.launch("image/*") else viewModel.saveBalanceChange()
                },
            )
        },
    ) { padding ->
        if (reviewing) {
            BalanceImportPreview(state, viewModel, Modifier.padding(padding), onChooseAgain = { discardPreview = true })
        } else {
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    BalanceEntryMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(selected = state.entryMode == mode, enabled = editable,
                            onClick = { focus.clearFocus(); viewModel.selectEntryMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index, BalanceEntryMode.entries.size)) {
                            Text(if (mode == BalanceEntryMode.Manual) "Manual" else "Screenshots")
                        }
                    }
                }
                if (state.fundSources.isEmpty()) {
                    EntryMessage("Add an account before recording a balance.")
                    TextButton(onClick = onManageAccounts) { Text("Manage accounts") }
                }
                if (screenshots) {
                    ImportProgressStep(reviewing = false)
                    EntryHeading("Which balances should we read?", "Select accounts, then choose up to 20 images. Each image can contain several balances.")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.fundSources.forEach { source ->
                            FilterChip(selected = state.imageTargets.any { it.fundSourceId == source.id }, enabled = editable,
                                onClick = { viewModel.toggleImageTarget(source) }, label = { Text(source.name) })
                        }
                    }
                    state.imageTargets.forEach { target ->
                        OutlinedTextField(value = target.balanceToRead,
                            onValueChange = { viewModel.updateBalanceToRead(target.fundSourceId, it) },
                            label = { Text("Balance to read for ${target.name}") },
                            supportingText = { Text("For example: available cash or savings balance") },
                            enabled = editable, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    EntryMessage("Use image creation time when available; otherwise time defaults to now. Check the time for older images.")
                } else {
                    EntryHeading("Account balance", "Record the balance you see, including negative balances.")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.fundSources.forEach { source ->
                            FilterChip(selected = state.selectedFundSource?.id == source.id, enabled = editable,
                                onClick = { viewModel.selectFundSource(source) }, label = { Text(source.name) })
                        }
                    }
                    OutlinedTextField(value = state.balance, onValueChange = viewModel::updateBalance,
                        label = { Text("New Balance") }, prefix = { Text(state.selectedFundSource?.currency.orEmpty()) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        enabled = editable, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = state.dateTime, onValueChange = viewModel::updateDateTime,
                        label = { Text("Local date and time") }, supportingText = { Text("yyyy-MM-ddTHH:mm:ss · defaults to now") },
                        singleLine = true, enabled = editable, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = state.note, onValueChange = viewModel::updateNote,
                        label = { Text("Note (Optional)") }, enabled = editable, modifier = Modifier.fillMaxWidth())
                }
                if (state.isImageAnalyzing) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.errorMessage?.let { EntryMessage(it, isError = true) }
            }
        }
    }
    if (discardPreview) {
        AlertDialog(onDismissRequest = { discardPreview = false }, title = { Text("Choose different images?") },
            text = { Text("This clears the unsaved preview. Your selected accounts and balance labels stay available.") },
            confirmButton = { TextButton(onClick = { discardPreview = false; viewModel.clearBalancePreview() }) { Text("Choose again") } },
            dismissButton = { TextButton(onClick = { discardPreview = false }) { Text("Keep reviewing") } })
    }
    if (leavePreview) ConfirmLeavePreview(onStay = { leavePreview = false }, onLeave = {
        leavePreview = false; onBack()
    })

}
